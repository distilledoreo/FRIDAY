import asyncio
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from urllib.parse import parse_qs

import httpx
from cryptography.fernet import Fernet
from desktop.agent.accounts import Accounts, AccountError, MICROSOFT_REDIRECT, SCOPES
from desktop.agent.vault import Vault


class AccountsTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        key=Fernet.generate_key()
        self.vault=Vault(Path(self.temp.name),lambda:key)
        self.requests=[]
        self.grant='Mail.Read Calendars.Read User.Read'
        self.reply_status=200
        self.return_refresh=True
        def reply(request):
            self.requests.append(request)
            host=request.url.host
            if request.url.path.endswith('/token'):
                return httpx.Response(self.reply_status,json={'access_token':'synthetic-access','refresh_token':'synthetic-refresh' if self.return_refresh else None,'token_type':'Bearer','expires_in':3600,
                    'scope': ' '.join(SCOPES['google'].values()) if host=='oauth2.googleapis.com' else self.grant})
            if host=='openidconnect.googleapis.com': return httpx.Response(200,json={'email':'synthetic@example.com','email_verified':True})
            if request.url.path=='/v1.0/me': return httpx.Response(200,json={'mail':'synthetic@example.com'})
            if request.url.path.endswith('/messages'):
                return httpx.Response(200,json={'value':[{'id':'mail-id','subject':'Inbox','bodyPreview':'Untrusted preview','from':{'emailAddress':{'address':'sender@example.com'}}}], 'messages':[{'id':'abc123'}]})
            if host=='gmail.googleapis.com': return httpx.Response(200,json={'snippet':'Untrusted Gmail preview','payload':{'mimeType':'text/plain','body':{'data':base64.urlsafe_b64encode(b'Synthetic message body').decode()},'headers':[{'name':'Subject','value':'Gmail inbox'}]}})
            if request.url.path.startswith('/v1.0/me/messages/'):
                return httpx.Response(200,json={'subject':'Synthetic mail','body':{'contentType':'text','content':'Synthetic message body'},'from':{'emailAddress':{'address':'sender@example.com'}}})
            if request.url.path.endswith('/events') or request.url.path.endswith('/calendarView'):
                return httpx.Response(200,json={'items':[{'id':'event','summary':'Meeting','start':{'dateTime':'2026-10-09T10:00:00Z'}}], 'value':[{'id':'event','subject':'Meeting','start':{'dateTime':'2026-10-09T10:00:00'}}]})
            raise AssertionError('Unexpected provider destination')
        self.client=httpx.AsyncClient(transport=httpx.MockTransport(reply),follow_redirects=False)
        self.accounts=Accounts(self.vault,self.client)
        self.accounts.configure('google',{'client_id':'synthetic.apps.googleusercontent.com','client_secret':'synthetic-config-secret'})
        self.accounts.configure('microsoft',{'client_id':'00000000-0000-0000-0000-000000000001'})

    async def asyncTearDown(self):
        await self.accounts.close()
        await self.client.aclose()
        self.temp.cleanup()

    async def connect(self,provider='microsoft',features=None):
        flow=await self.accounts.begin(provider,features or ['mail_read','calendar_read'])
        return await self.accounts.complete(flow['flow_id'],flow['state'],'synthetic-auth-code')

    async def test_pkce_state_one_use_and_secrets_not_in_result(self):
        flow=await self.accounts.begin('microsoft',['mail_read'])
        url=parse_qs(flow['authorization_url'].split('?',1)[1])
        self.assertEqual(url['code_challenge_method'],['S256'])
        self.assertEqual(url['redirect_uri'],[MICROSOFT_REDIRECT])
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],'wrong','code')
        account=await self.accounts.complete(flow['flow_id'],flow['state'],'code')
        token_form=parse_qs(self.requests[0].content.decode())
        challenge=base64.urlsafe_b64encode(hashlib.sha256(token_form['code_verifier'][0].encode()).digest()).decode().rstrip('=')
        self.assertEqual(url['code_challenge'],[challenge])
        self.assertEqual(set(account),{'id','provider','label'})
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],flow['state'],'code')

    async def test_expired_flow_and_configuration_change_are_rejected(self):
        flow=await self.accounts.begin('google',['mail_read'])
        self.accounts.flows[flow['flow_id']]['expires']=0
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],flow['state'],'code')
        flow=await self.accounts.begin('google',['mail_read'])
        self.accounts.configure('google',{'client_id':'another.apps.googleusercontent.com','client_secret':'new-secret'})
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],flow['state'],'code')
        self.assertEqual(self.requests,[])

    async def test_google_exchange_stays_on_host_and_configuration_is_encrypted(self):
        account=await self.connect('google')
        raw=self.vault.path.read_bytes()
        self.assertNotIn(b'synthetic-config-secret',raw)
        self.assertNotIn(b'synthetic-refresh',raw)
        flow=await self.accounts.begin('google',['calendar_read'])
        self.assertNotIn('client_secret',flow)
        self.assertNotIn('synthetic-config-secret',json.dumps(self.accounts.status()))
        self.assertEqual(self.requests[0].url.host,'oauth2.googleapis.com')
        self.assertIn('client_secret',parse_qs(self.requests[0].content.decode()))
        self.assertEqual(self.vault.metadata(account['id'])['provider'],'google')

    async def test_declined_scope_does_not_authorize_sending(self):
        self.grant='User.Read Mail.Read'
        account=await self.connect(features=['mail_read','mail_send'])
        with self.assertRaises(AccountError): await self.accounts.token(account['id'],'mail_send')
        self.assertEqual(self.vault.credentials(account['id'])['features'],['mail_read'])

    async def test_refresh_is_serial_and_rotated_credentials_remain_in_vault(self):
        account=await self.connect()
        record=self.vault.credentials(account['id']);record['expires_at']=0
        self.vault.replace_credentials(account['id'],record)
        initial=len(self.requests)
        await asyncio.gather(*(self.accounts.token(account['id'],'mail_read') for _ in range(5)))
        self.assertEqual(len(self.requests)-initial,1)
        self.assertGreater(self.vault.credentials(account['id'])['expires_at'],time.time())
        self.assertNotIn('access_token',self.vault.metadata(account['id']))

    async def test_bounded_readers_mark_content_untrusted_and_do_not_send(self):
        for provider in ('google','microsoft'):
            account=await self.connect(provider)
            mail=await self.accounts.read_messages(account['id'],'from:sender@example.com',2)
            calendar=await self.accounts.read_calendar(account['id'],'2026-10-09T00:00:00Z','2026-10-10T00:00:00Z')
            self.assertTrue(mail['untrusted'] and calendar['untrusted'])
            self.assertEqual(len(mail['messages']),1)
            self.assertEqual(calendar['events'][0]['title'],'Meeting')
        self.assertFalse(any('sendMail' in str(r.url) or str(r.url).endswith('/send') for r in self.requests))

    async def test_full_message_read_and_path_injection_rejection(self):
        for provider in ('google','microsoft'):
            account=await self.connect(provider)
            message=await self.accounts.read_message(account['id'],'mail-id')
            self.assertEqual(message['message']['body'],'Synthetic message body')
            self.assertTrue(message['untrusted'])
            with self.assertRaises(ValueError): await self.accounts.read_message(account['id'],'../sendMail')
        self.assertTrue(all(request.method=='GET' or str(request.url).endswith('/token') for request in self.requests))

    async def test_reconnect_preserves_handle_and_existing_offline_refresh(self):
        account=await self.connect('google')
        self.return_refresh=False
        same=await self.connect('google')
        self.assertEqual(same['id'],account['id'])
        self.assertEqual(len(self.vault.accounts()),1)
        self.assertEqual(self.vault.credentials(same['id'])['refresh_token'],'synthetic-refresh')
        flow=await self.accounts.begin('microsoft',['mail_read'])
        await self.accounts.cancel_sign_in(flow['flow_id'])
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],flow['state'],'code')

    async def test_removed_account_and_unsupported_windows_do_not_make_requests(self):
        account=await self.connect()
        self.vault.remove(account['id'])
        initial=len(self.requests)
        with self.assertRaises(ValueError): await self.accounts.read_messages(account['id'])
        with self.assertRaises(ValueError): await self.accounts.read_calendar(account['id'],'2026-10-09','2026-12-09')
        self.assertEqual(len(self.requests),initial)

    async def test_missing_offline_grant_does_not_save_an_unusable_sign_in(self):
        self.return_refresh=False
        with self.assertRaises(AccountError): await self.connect('google')
        self.assertEqual(self.vault.accounts(),[])

    async def test_token_redirect_error_is_not_followed_or_leaked(self):
        self.reply_status=307
        flow=await self.accounts.begin('microsoft',['mail_read'])
        with self.assertRaises(AccountError) as error: await self.accounts.complete(flow['flow_id'],flow['state'],'synthetic-auth-code')
        self.assertNotIn('synthetic',str(error.exception))
        self.assertEqual(len(self.requests),1)
        with self.assertRaises(ValueError): await self.accounts.complete(flow['flow_id'],flow['state'],'code')
