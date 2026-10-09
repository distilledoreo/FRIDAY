import asyncio
import base64
from datetime import datetime,timedelta,timezone
from email import policy
from email.parser import BytesParser
import json
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from cryptography import x509
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from fastapi import Depends,FastAPI,Header,HTTPException
import httpx

from desktop.agent.accounts import Accounts
from desktop.agent.api import install
from desktop.agent.approvals import Approvals
from desktop.agent.outgoing import Executors,EffectRejected,EffectUncertain,smtp_send,mail_message,PinnedSMTP
from desktop.agent.review import OutgoingReview
from desktop.agent.vault import Vault


class OutgoingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory();self.key=Fernet.generate_key()
        self.vault=Vault(Path(self.temp.name)/'vault',lambda:self.key)
        self.sent=[];self.status=200;self.disconnect=False;self.version='"version1"';self.old=['guest@example.com'];self.organizer=True
        def reply(request):
            if request.method=='GET':
                if request.url.host=='www.googleapis.com':return httpx.Response(200,json={'id':'event1','etag':self.version,'organizer':{'self':self.organizer},'attendees':[{'email':email} for email in self.old]})
                return httpx.Response(200,json={'id':'event1','@odata.etag':self.version,'isOrganizer':self.organizer,'type':'singleInstance','attendees':[{'emailAddress':{'address':email}} for email in self.old]})
            self.sent.append(request)
            if self.disconnect:raise httpx.ReadTimeout('synthetic private provider error')
            status=202 if request.url.path.endswith('/sendMail') and self.status==200 else self.status
            return httpx.Response(status,json={'id':'receipt1'} if status!=202 else None)
        self.client=httpx.AsyncClient(transport=httpx.MockTransport(reply),follow_redirects=False)
        self.accounts=Accounts(self.vault,self.client);self.executors=Executors(self.accounts,True)
        self.google=self.vault.add('google','owner@example.com',{'access_token':'synthetic-access-token','refresh_token':'synthetic-refresh-token','expires_at':time.time()+3600,'features':['mail_read','mail_send','calendar_read','calendar_write']})
        self.microsoft=self.vault.add('microsoft','owner@outlook.example',{'access_token':'synthetic-ms-access','refresh_token':'synthetic-ms-refresh','expires_at':time.time()+3600,'features':['mail_read','mail_send','calendar_read','calendar_write']})
    async def asyncTearDown(self):
        await self.accounts.close();await self.client.aclose();self.temp.cleanup()
    def email(self,account=None):return {'kind':'send','destination':'recipient@example.com','payload':{'account_id':(account or self.google)['id'],'to':'recipient@example.com','subject':'Exact draft 😀','body':'Exact body\n.Line two'}}
    def calendar(self,kind='calendar_create',account=None):
        payload={'account_id':(account or self.google)['id'],'title':'Exact meeting','description':'Exact agenda','location':'Room 1','start':'2026-10-09T10:00:00-04:00','end':'2026-10-09T11:00:00-04:00','timezone':'America/New_York','attendees':['guest@example.com'],'notify_attendees':True}
        if kind=='calendar_update':payload.update(event_id='event1',expected_version='"version1"',previous_attendees=['guest@example.com'])
        return {'kind':kind,'destination':'primary' if kind=='calendar_create' else 'primary/event1','payload':payload}
    async def test_google_microsoft_exact_mail_and_provider_acceptance_not_delivery(self):
        for account in (self.google,self.microsoft):
            action=self.email(account);result=await self.executors.execute(action,'a'*32,lambda:None)
            self.assertEqual(result['outcome'],'accepted');self.assertIn('not confirmed',result['detail'])
            body=json.loads(self.sent[-1].content)
            if account['provider']=='google':
                mime=BytesParser(policy=policy.default).parsebytes(base64.urlsafe_b64decode(body['raw']))
                self.assertEqual(str(mime['From']),'owner@example.com');self.assertEqual(str(mime['To']),action['destination'])
                self.assertEqual(str(mime['Subject']),action['payload']['subject']);self.assertEqual(mime.get_content().replace('\r\n','\n').rstrip('\n'),action['payload']['body'])
                self.assertIsNone(mime['Bcc']);self.assertIsNone(mime['Cc']);self.assertEqual(mime['Message-ID'],'<'+'a'*32+'@friday.local>')
            else:
                self.assertEqual(body['message']['body']['content'],action['payload']['body']);self.assertEqual(body['message']['toRecipients'],[{'emailAddress':{'address':action['destination']}}])
                self.assertEqual(result['http_status'],202)
    async def test_calendar_create_update_notifications_versions_and_stable_identifiers(self):
        for account in (self.google,self.microsoft):
            result=await self.executors.execute(self.calendar(account=account),'b'*32,lambda:None)
            self.assertEqual(result['outcome'],'accepted');request=self.sent[-1];body=json.loads(request.content)
            self.assertEqual(body.get('id') if account['provider']=='google' else body.get('transactionId'),'f'+'b'*32 if account['provider']=='google' else 'b'*32)
            if account['provider']=='google':self.assertEqual(request.url.params['sendUpdates'],'all')
            else:self.assertEqual(body['start'],{'dateTime':'2026-10-09T14:00:00','timeZone':'UTC'})
            await self.executors.execute(self.calendar('calendar_update',account),'c'*32,lambda:None)
            self.assertEqual(self.sent[-1].method,'PATCH');self.assertEqual(self.sent[-1].headers['If-Match'],'"version1"')
    async def test_changed_event_attendees_organizer_or_version_fail_before_mutation(self):
        for attribute,value in (('version','"new"'),('old',['other@example.com']),('organizer',False)):
            original=getattr(self,attribute);setattr(self,attribute,value)
            with self.assertRaises(EffectRejected):await self.executors.execute(self.calendar('calendar_update'),'d'*32,lambda:None)
            setattr(self,attribute,original)
        self.assertEqual(self.sent,[])
    async def test_policy_blocks_hidden_recipients_secrets_unapproved_notifications_and_disabled_activation(self):
        review=OutgoingReview(self.vault,self.executors)
        action=self.email();self.assertTrue(review.inspect(action)['executable'])
        for change in ({'to':'bad\r\nBcc: other@example.com'},{'cc':'other@example.com'},{'body':'synthetic-access-token'},{'body':'Bearer private-credential'}):
            rejected=dict(action,payload=dict(action['payload'],**change))
            self.assertFalse(review.inspect(rejected)['allowed'])
            with self.assertRaises(EffectRejected):await self.executors.execute(rejected,'e'*32,lambda:None)
        calendar=self.calendar();calendar['payload']['notify_attendees']=False
        with self.assertRaises(EffectRejected):await self.executors.execute(calendar,'e'*32,lambda:None)
        self.executors.enabled=False
        with self.assertRaises(EffectRejected):await self.executors.execute(action,'e'*32,lambda:None)
        self.assertEqual(self.sent,[])
    async def test_definitive_rejection_uncertain_timeout_and_guard_never_retry(self):
        self.status=412
        with self.assertRaises(EffectRejected):await self.executors.execute(self.email(),'f'*32,lambda:None)
        self.status=500
        with self.assertRaises(EffectUncertain):await self.executors.execute(self.email(),'f'*32,lambda:None)
        self.disconnect=True
        with self.assertRaises(EffectUncertain) as error:await self.executors.execute(self.email(),'f'*32,lambda:None)
        self.assertNotIn('synthetic',str(error.exception));self.assertEqual(len(self.sent),3)
        def canceled():raise EffectRejected('Canceled')
        with self.assertRaises(EffectRejected):await self.executors.execute(self.email(),'f'*32,canceled)
        self.assertEqual(len(self.sent),3)
    async def test_all_day_dates_have_explicit_zone_exclusive_end_and_no_hidden_attendees(self):
        for account in (self.google,self.microsoft):
            action=self.calendar(account=account);action['payload'].update(start='2026-10-09',end='2026-10-10',attendees=[],notify_attendees=False)
            await self.executors.execute(action,'a'*32,lambda:None);request=self.sent[-1];body=json.loads(request.content)
            if account['provider']=='google':self.assertEqual(body['end'],{'date':'2026-10-10'});self.assertEqual(request.url.params['sendUpdates'],'none')
            else:self.assertTrue(body['isAllDay']);self.assertEqual(body['start'],{'dateTime':'2026-10-09T00:00:00','timeZone':'America/New_York'})
    async def test_api_requires_both_exact_reviews_and_one_claim_then_durable_receipt(self):
        root=Path(self.temp.name)/'api';vault=Vault(root/'vault',lambda:self.key)
        account=vault.add('google','owner@example.com',{'access_token':'synthetic-access-token','expires_at':time.time()+3600,'features':['mail_send']})
        calls=[];release=asyncio.Event()
        class FakeExecutors(Executors):
            async def execute(self,action,identifier,guard):
                guard();calls.append(action);await release.wait();return {'outcome':'accepted','detail':'Synthetic provider acceptance only'}
        app=FastAPI()
        def auth(authorization:str=Header(default='')):
            if authorization!='Bearer test':raise HTTPException(401)
        with patch('desktop.agent.vault.DesktopKey',return_value=lambda:self.key):store=install(app,[Depends(auth)],root,outgoing_enabled=True,outgoing_factory=FakeExecutors)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test',headers={'Authorization':'Bearer test'}) as client:
                draft=(await client.post('/workspace/agent/outgoing/drafts',json=self.email(account))).json()
                self.assertEqual(calls,[]);self.assertEqual(store.task(draft['task_id'])['status'],'awaiting_setup')
                path='/workspace/agent/actions/'+draft['id']+'/approve'
                body={'fingerprint':draft['fingerprint'],'review_fingerprint':draft['review']['review_fingerprint']}
                self.assertEqual((await client.post(path,json=body,headers={'Authorization':''})).status_code,401)
                self.assertEqual((await client.post(path,json={'fingerprint':draft['fingerprint']})).status_code,409)
                self.assertEqual((await client.post(path,json=dict(body,review_fingerprint='0'*64))).status_code,409)
                self.assertEqual(calls,[])
                self.assertEqual((await client.post(path,json=body)).status_code,200)
                await asyncio.sleep(.02);self.assertEqual(len(calls),1)
                self.assertEqual((await client.post(path,json=body)).status_code,409)
                release.set();await asyncio.sleep(.02)
                self.assertEqual(store.action(draft['id'])['status'],'accepted');self.assertEqual(store.task(draft['task_id'])['status'],'done')
                event=next(event for event in store.events(draft['task_id']) if event['kind']=='action_accepted')
                self.assertFalse(event['data']['automatic_retry'])
    async def test_restart_claims_become_uncertain_and_canceled_actions_cannot_execute(self):
        store=Approvals(Path(self.temp.name)/'agent.sqlite')
        draft=store.propose_outgoing(self.email())
        store.approve_action(draft['id'],draft['fingerprint']);store.claim_action(draft['id'],self.email())
        store.interrupt_running()
        self.assertEqual(store.action(draft['id'])['status'],'uncertain');self.assertEqual(store.task(draft['task_id'])['status'],'interrupted')
        with self.assertRaises(ValueError):store.claim_action(draft['id'],self.email())
        other=store.propose_outgoing(self.email());store.cancel_task(other['task_id'])
        with self.assertRaises(ValueError):store.approve_action(other['id'],other['fingerprint'])
    async def test_uncertain_api_receipt_cannot_replay_and_cancellation_preserves_unknown_outcome(self):
        for cancel in (False,True):
            root=Path(self.temp.name)/('cancel' if cancel else 'uncertain');vault=Vault(root/'vault',lambda:self.key)
            account=vault.add('google','owner@example.com',{'access_token':'synthetic-token','expires_at':time.time()+3600,'features':['mail_send']})
            calls=[];started=asyncio.Event();release=asyncio.Event()
            class FakeExecutors(Executors):
                async def execute(self,action,identifier,guard):
                    guard();calls.append(identifier);started.set()
                    if cancel:await release.wait()
                    raise EffectUncertain('Synthetic connection interrupted')
            app=FastAPI()
            with patch('desktop.agent.vault.DesktopKey',return_value=lambda:self.key):store=install(app,[],root,outgoing_enabled=True,outgoing_factory=FakeExecutors)
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
                    draft=(await client.post('/workspace/agent/outgoing/drafts',json=self.email(account))).json()
                    path='/workspace/agent/actions/'+draft['id']+'/approve';body={'fingerprint':draft['fingerprint'],'review_fingerprint':draft['review']['review_fingerprint']}
                    await client.post(path,json=body);await asyncio.wait_for(started.wait(),1)
                    if cancel:await client.post('/workspace/agent/tasks/'+draft['task_id']+'/cancel')
                    await asyncio.sleep(.02)
                    self.assertEqual(store.action(draft['id'])['status'],'uncertain')
                    self.assertEqual((await client.post(path,json=body)).status_code,409);self.assertEqual(len(calls),1)
    async def test_private_action_validation_and_disabled_production_gate_do_not_record_approval(self):
        root=Path(self.temp.name)/'disabled';app=FastAPI()
        store=install(app,[],root)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            self.assertFalse((await client.get('/workspace/agent/health')).json()['outgoing_ready'])
            response=await client.post('/workspace/agent/outgoing/drafts',json={'kind':{'secret':'synthetic-secret'},'payload':{}})
            self.assertEqual(response.status_code,422);self.assertNotIn('synthetic-secret',response.text)
            draft=store.propose_outgoing(self.email())
            response=await client.post('/workspace/agent/actions/'+draft['id']+'/approve',json={'fingerprint':draft['fingerprint']})
            self.assertEqual(response.status_code,503);self.assertEqual(store.action(draft['id'])['status'],'proposed')
    async def test_changed_smtp_identity_invalidates_the_separate_review_before_approval(self):
        root=Path(self.temp.name)/'identity';vault=Vault(root/'vault',lambda:self.key)
        config={'email':'owner@example.com','imap_host':'imap.example.com','username':'owner','password':'synthetic-password','smtp_host':'smtp.example.com','smtp_port':465,'features':['mail_read']}
        account=vault.add('imap','owner@example.com',config);app=FastAPI()
        with patch('desktop.agent.vault.DesktopKey',return_value=lambda:self.key):store=install(app,[],root,outgoing_enabled=True)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            draft=(await client.post('/workspace/agent/outgoing/drafts',json=self.email(account))).json()
            vault.replace_credentials(account['id'],dict(config,smtp_host='changed.example.com'))
            response=await client.post('/workspace/agent/actions/'+draft['id']+'/approve',json={'fingerprint':draft['fingerprint'],'review_fingerprint':draft['review']['review_fingerprint']})
            self.assertEqual(response.status_code,409);self.assertEqual(store.action(draft['id'])['status'],'proposed')
    async def test_provider_receipt_cannot_echo_saved_credentials(self):
        client=httpx.AsyncClient(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'id':'synthetic-access-token'})))
        old=self.accounts.client;self.accounts.client=client
        try:
            result=await self.executors.execute(self.email(),'a'*32,lambda:None)
            self.assertEqual(result['outcome'],'accepted');self.assertIsNone(result['receipt_id'])
            self.assertNotIn('synthetic-access-token',json.dumps(result))
        finally:self.accounts.client=old;await client.aclose()


class SMTPTests(unittest.TestCase):
    def test_real_local_tls_smtp_pin_authentication_dot_stuffing_and_no_real_delivery(self):
        for port,cancel_body in ((465,False),(587,False),(465,True),(587,True)):
            with self.subTest(port=port,cancel_body=cancel_body),tempfile.TemporaryDirectory() as tmp:
                key=rsa.generate_private_key(public_exponent=65537,key_size=2048);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'smtp.example.com')])
                cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number())
                      .not_valid_before(datetime.now(timezone.utc)-timedelta(minutes=1)).not_valid_after(datetime.now(timezone.utc)+timedelta(days=1))
                      .add_extension(x509.SubjectAlternativeName([x509.DNSName('smtp.example.com')]),False).sign(key,hashes.SHA256()))
                cert_path=Path(tmp)/'cert.pem';cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
                key_path=Path(tmp)/'key.pem';key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
                server_context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);server_context.load_cert_chain(cert_path,key_path)
                trusted=ssl.create_default_context(cafile=str(cert_path))
                listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.settimeout(5)
                commands=[];messages=[];failures=[];guard_calls=[]
                def serve():
                    try:
                        sock,_=listener.accept();sock.settimeout(5)
                        if port==465:sock=server_context.wrap_socket(sock,server_side=True)
                        with sock:
                            stream=sock.makefile('rb');sock.sendall(b'220 Synthetic SMTP\r\n')
                            while line:=stream.readline():
                                command=line.split(b' ',1)[0].strip().upper();commands.append(command)
                                if command==b'EHLO':sock.sendall(b'250-synthetic\r\n250-STARTTLS\r\n250 AUTH PLAIN\r\n')
                                elif command==b'STARTTLS':
                                    sock.sendall(b'220 Begin TLS\r\n');stream.close();sock=server_context.wrap_socket(sock,server_side=True);stream=sock.makefile('rb')
                                elif command==b'AUTH':
                                    self.assertIsInstance(sock,ssl.SSLSocket);sock.sendall(b'235 Authenticated\r\n')
                                elif command in (b'MAIL',b'RCPT'):sock.sendall(b'250 OK\r\n')
                                elif command==b'DATA':
                                    sock.sendall(b'354 Send data\r\n');data=bytearray();complete=False
                                    while body:=stream.readline():
                                        if body==b'.\r\n':complete=True;break
                                        data.extend(body)
                                    if complete:messages.append(bytes(data));sock.sendall(b'250 Queued synthetic only\r\n')
                                else:sock.sendall(b'250 OK\r\n')
                            stream.close()
                    except Exception as error:failures.append(type(error).__name__)
                    finally:
                        if 'sock' in locals():sock.close()
                        listener.close()
                worker=threading.Thread(target=serve);worker.start();original=socket.create_connection
                def pinned(address,**kwargs):
                    self.assertEqual(address,('8.8.8.8',port));return original(listener.getsockname(),**kwargs)
                def resolve(host,port,**kwargs):return [(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',port))]
                config={'smtp_host':'smtp.example.com','smtp_port':port,'username':'test','password':'synthetic-password','email':'test@example.com'}
                raw=mail_message(config['email'],{'to':'recipient@example.com','subject':'Synthetic','body':'Line\n.dot'},'a'*32)
                def guard():
                    guard_calls.append(True)
                    if cancel_body and len(guard_calls)==2:raise EffectRejected('Canceled before body submission')
                with patch('desktop.agent.outgoing.socket.create_connection',side_effect=pinned),patch('desktop.agent.outgoing.ssl.create_default_context',return_value=trusted):
                    if cancel_body:
                        with self.assertRaises(EffectRejected):smtp_send(config,raw,'recipient@example.com',guard,resolve=resolve)
                    else:result=smtp_send(config,raw,'recipient@example.com',guard,resolve=resolve)
                worker.join(6);self.assertFalse(worker.is_alive());self.assertEqual(failures,[])
                self.assertEqual(len(guard_calls),2)
                if cancel_body:self.assertEqual(messages,[])
                else:self.assertEqual(result['outcome'],'accepted');self.assertEqual(len(messages),1);self.assertIn(b'\r\n..dot',messages[0])
                if port==587:self.assertLess(commands.index(b'STARTTLS'),commands.index(b'AUTH'))
    def test_missing_starttls_fails_closed_before_auth_or_data(self):
        calls=[]
        class FakeSMTP:
            tls_context=None
            def __init__(self,*args):pass
            def ehlo(self):return 250,b'hello'
            def starttls(self,**kwargs):raise __import__('smtplib').SMTPNotSupportedError('synthetic password must not leak')
            def login(self,*args):calls.append('login')
            def sendmail(self,*args):calls.append('send')
            def close(self):pass
        config={'smtp_host':'smtp.example.com','smtp_port':587,'username':'test','password':'synthetic-password','email':'test@example.com'}
        def resolve(host,port,**kwargs):return [(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',port))]
        with self.assertRaises(EffectRejected) as error:smtp_send(config,b'body','recipient@example.com',lambda:None,resolve,FakeSMTP)
        self.assertNotIn('synthetic',str(error.exception));self.assertEqual(calls,[])
        with self.assertRaises(EffectRejected):smtp_send(dict(config,smtp_port=25),b'body','recipient@example.com',lambda:None,resolve,FakeSMTP)
        self.assertEqual(calls,[])
