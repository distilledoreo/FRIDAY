"""Host-only native OAuth and provider readers; no credentials returned to callers."""
import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
import re
import secrets
import time
from urllib.parse import urlencode, quote
import uuid

import httpx

FEATURES = frozenset({'mail_read', 'calendar_read', 'mail_send'})
SCOPES = {
    'google': {'mail_read': 'https://www.googleapis.com/auth/gmail.readonly',
               'calendar_read': 'https://www.googleapis.com/auth/calendar.readonly',
               'mail_send': 'https://www.googleapis.com/auth/gmail.send'},
    'microsoft': {'mail_read': 'Mail.Read', 'calendar_read': 'Calendars.Read', 'mail_send': 'Mail.Send'},
}
TOKEN = {'google': 'https://oauth2.googleapis.com/token',
         'microsoft': 'https://login.microsoftonline.com/common/oauth2/v2.0/token'}
MICROSOFT_REDIRECT = 'msauth://com.localfirst.assistant/k%2B71zY1PlQ%2Ba2Hd9Wur3tSmBEcI%3D'


class AccountError(RuntimeError): pass


async def request_json(client, method, url, **kwargs):
    # Fixed provider endpoints are supplied only by trusted adapters below.
    async with client.stream(method, url, **kwargs) as response:
        if response.status_code not in (200, 201):
            raise AccountError(f'Provider request failed ({response.status_code}); reconnect or check permissions')
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > 2 * 1024 * 1024: raise AccountError('Provider response exceeded limit')
        try: value = json.loads(body)
        except ValueError: raise AccountError('Invalid provider response') from None
        if not isinstance(value, dict): raise AccountError('Invalid provider response')
        return value


class Accounts:
    def __init__(self, vault, client=None, mail=None):
        self.vault = vault
        self.client = client or httpx.AsyncClient(timeout=45, trust_env=False, follow_redirects=False)
        self.owns_client = client is None
        self.flows = {}
        self.lock = asyncio.Lock()
        self.refresh_locks = {}
        from .mail import Mail
        self.mail = mail or Mail()
        self.mail_tasks = set()

    async def close(self):
        self.flows.clear()
        if self.mail_tasks: await asyncio.gather(*self.mail_tasks, return_exceptions=True)
        if self.owns_client: await self.client.aclose()

    async def mail_call(self, method, *args):
        # Cancellation never releases the slot while its bounded host read runs.
        if len(self.mail_tasks) >= 2: raise AccountError('Mail access is busy; try again shortly')
        task = asyncio.create_task(asyncio.to_thread(method, *args))
        self.mail_tasks.add(task)
        def finished(value):
            self.mail_tasks.discard(value)
            if not value.cancelled(): value.exception()  # Consume detached failures.
        task.add_done_callback(finished)
        from .mail import MailError
        try: return await asyncio.wait_for(asyncio.shield(task), timeout=45)
        except MailError as error: raise AccountError(str(error)) from None
        except Exception: raise AccountError('Mail access unavailable; check settings and reconnect') from None

    async def connect_mail(self, value):
        from .mail import settings
        config = settings(value)
        await self.mail_call(self.mail.verify, config)
        existing = self.vault.find_account('imap', config['email'])
        if existing:
            previous = self.vault.credentials(existing['id'])
            if (previous.get('imap_host'), previous.get('username')) != (config['imap_host'], config['username']):
                raise ValueError('Mail account identity changed; remove the old account before connecting this server/login')
            self.vault.replace_credentials(existing['id'], config)
            return self.vault.metadata(existing['id'])
        return self.vault.add('imap', config['email'], config)

    def mail_config(self, identifier):
        metadata = self.vault.metadata(identifier)
        if metadata['provider'] != 'imap': return None
        config = self.vault.credentials(identifier)
        if 'mail_read' not in config.get('features', []): raise AccountError('This account did not authorize mail reads')
        return config

    def bind_scopes(self, reads):
        from .account_scopes import normalize, bound
        return bound([dict(read, account=self.vault.metadata(read['account_id'])) for read in normalize(reads)])

    def validate_scopes(self, reads):
        from .account_scopes import bound
        for read in bound(reads):
            if self.vault.metadata(read['account_id']) != read['account']: raise ValueError('Scoped account changed or was removed; review a new task')

    async def scoped_snapshot(self, reads):
        self.validate_scopes(reads)
        result = []
        for read in reads:
            if read['kind'] == 'inbox': value = await self.read_messages(read['account_id'], read['query'], read['limit'])
            elif read['kind'] == 'message': value = await self.read_message(read['account_id'], read['message_id'])
            else: value = await self.read_calendar(read['account_id'], read['start'], read['end'], read['limit'])
            result.append({'scope':read, 'data':value, 'untrusted':True})
            if len(json.dumps(result, ensure_ascii=False).encode()) > 120 * 1024: raise AccountError('Selected account data exceeds cloud context limit; approve narrower reads')
        self.validate_scopes(reads)
        return result

    def configure(self, provider, config):
        if provider not in SCOPES or not isinstance(config, dict): raise ValueError('Unsupported OAuth provider')
        if provider == 'google':
            if set(config) != {'client_id', 'client_secret'} or not re.fullmatch(r'[A-Za-z0-9_-]+\.apps\.googleusercontent\.com', config.get('client_id', '')) or not isinstance(config.get('client_secret'), str) or not 1 <= len(config['client_secret']) <= 500:
                raise ValueError('Google requires the backend web client id and secret')
        else:
            if set(config) != {'client_id'}: raise ValueError('Microsoft uses a public native client, without a secret')
            try: uuid.UUID(config['client_id'])
            except (ValueError, TypeError, KeyError): raise ValueError('Invalid Microsoft client id')
        self.vault.set_application(provider, config)

    def status(self):
        return [{'provider': name, 'configured': self.vault.has_application(name)} for name in SCOPES]

    async def begin(self, provider, features):
        if provider not in SCOPES or not isinstance(features, list) or not features or not all(isinstance(feature,str) for feature in features) or not set(features) <= FEATURES:
            raise ValueError('Choose supported account features')
        config = self.vault.application(provider)
        scopes = (['openid', 'email'] if provider == 'google' else ['openid', 'offline_access', 'User.Read']) + [SCOPES[provider][feature] for feature in sorted(set(features))]
        async with self.lock:
            self.flows = {key: value for key, value in self.flows.items() if value['expires'] > time.time()}
            if len(self.flows) >= 20: raise AccountError('Too many pending sign-ins')
            identifier, state = secrets.token_urlsafe(24), secrets.token_urlsafe(32)
            verifier = secrets.token_urlsafe(48)
            self.flows[identifier] = {'provider': provider, 'state': state, 'verifier': verifier,
                'expires': time.time()+600, 'scopes': scopes, 'features': sorted(set(features)), 'client_id': config['client_id']}
        result = {'flow_id': identifier, 'state': state, 'provider': provider, 'client_id': config['client_id'], 'scopes': scopes}
        if provider == 'microsoft':
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
            result['redirect_uri'] = MICROSOFT_REDIRECT
            result['authorization_url'] = 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize?' + urlencode({
                'client_id': config['client_id'], 'response_type': 'code', 'redirect_uri': MICROSOFT_REDIRECT,
                'scope': ' '.join(scopes), 'state': state, 'code_challenge': challenge, 'code_challenge_method': 'S256', 'prompt': 'select_account'})
        else:
            result['method'] = 'android_google_authorization_server_code'
        return result

    async def complete(self, identifier, state, code):
        if not isinstance(code, str) or not 1 <= len(code) <= 8192: raise ValueError('Invalid authorization code')
        async with self.lock:
            flow = self.flows.get(identifier)
            if not flow or flow['expires'] < time.time() or not isinstance(state, str) or not secrets.compare_digest(flow['state'], state):
                raise ValueError('Sign-in expired or state did not match; start again')
            del self.flows[identifier]  # One-use even when an exchange fails/has uncertain outcome.
        provider = flow['provider']
        config = self.vault.application(provider)
        if config['client_id'] != flow['client_id']: raise ValueError('Provider configuration changed; start sign-in again')
        body = {'client_id': config['client_id'], 'grant_type': 'authorization_code', 'code': code}
        if provider == 'google': body.update(client_secret=config['client_secret'], redirect_uri='')
        else: body.update(redirect_uri=MICROSOFT_REDIRECT, code_verifier=flow['verifier'], scope=' '.join(flow['scopes']))
        tokens = await request_json(self.client, 'POST', TOKEN[provider], data=body)
        record = self._tokens(tokens, flow['features'], config['client_id'], provider=provider)
        headers = {'Authorization': 'Bearer ' + record['access_token']}
        if provider == 'google':
            profile = await request_json(self.client, 'GET', 'https://openidconnect.googleapis.com/v1/userinfo', headers=headers)
            label = profile.get('email')
            if profile.get('email_verified') is not True: raise AccountError('Google email identity was not verified')
        else:
            profile = await request_json(self.client, 'GET', 'https://graph.microsoft.com/v1.0/me', headers=headers, params={'$select':'id,displayName,mail,userPrincipalName'})
            label = profile.get('mail') or profile.get('userPrincipalName')
        if not isinstance(label, str) or not label.strip(): raise AccountError('Provider did not identify the account')
        existing=self.vault.find_account(provider,label)
        if existing:
            previous=self.vault.credentials(existing['id'])
            if previous.get('client_id')!=config['client_id']: previous=None
            requested=sorted(set(flow['features']) | set((previous or {}).get('features',[])))
            record=self._tokens(tokens,requested,config['client_id'],previous,provider)
            if not record.get('refresh_token'): raise AccountError('Provider did not grant offline access; reconnect and approve it')
            self.vault.replace_credentials(existing['id'],record)
            return self.vault.metadata(existing['id'])
        if not record.get('refresh_token'): raise AccountError('Provider did not grant offline access; reconnect and approve it')
        return self.vault.add(provider, label, record)

    def _tokens(self, tokens, features, client_id, previous=None, provider=None):
        access = tokens.get('access_token')
        refresh = tokens.get('refresh_token') or (previous or {}).get('refresh_token')
        if not isinstance(access, str) or not 1 <= len(access) <= 16000: raise AccountError('Provider did not return an access token')
        if tokens.get('token_type', '').lower() != 'bearer': raise AccountError('Unsupported provider token type')
        try: expires = int(tokens['expires_in'])
        except (ValueError, TypeError, KeyError): raise AccountError('Provider token expiry missing')
        if not 1 <= expires <= 86400: raise AccountError('Invalid token expiry')
        if refresh is not None and (not isinstance(refresh, str) or len(refresh) > 16000): raise AccountError('Invalid refresh token')
        scope = tokens.get('scope', (previous or {}).get('scope', ''))
        if not isinstance(scope, str): raise AccountError('Invalid granted scope')
        granted = set(scope.split())
        allowed = [feature for feature in features if SCOPES[provider][feature] in granted or provider=='microsoft' and 'https://graph.microsoft.com/'+SCOPES[provider][feature] in granted]
        return {'access_token': access, 'refresh_token': refresh, 'expires_at': time.time()+expires,
                'features': allowed, 'client_id': client_id, 'scope': scope}

    async def token(self, identifier, feature):
        metadata = self.vault.metadata(identifier)
        if metadata['provider'] not in SCOPES: raise AccountError('This provider adapter is not connected')
        lock = self.refresh_locks.setdefault(identifier, asyncio.Lock())
        async with lock:
            credentials = self.vault.credentials(identifier)
            if feature not in credentials.get('features', []): raise AccountError('This account did not authorize that feature')
            if credentials.get('expires_at', 0) < time.time()+30:
                if not credentials.get('refresh_token'): raise AccountError('Reconnect this account for offline access')
                config = self.vault.application(metadata['provider'])
                if config['client_id'] != credentials.get('client_id'): raise AccountError('OAuth configuration changed; reconnect the account')
                body = {'grant_type':'refresh_token', 'refresh_token':credentials['refresh_token'], 'client_id':config['client_id']}
                if metadata['provider']=='google': body['client_secret']=config['client_secret']
                tokens = await request_json(self.client, 'POST', TOKEN[metadata['provider']], data=body)
                credentials = self._tokens(tokens, credentials['features'], credentials['client_id'], credentials, metadata['provider'])
                self.vault.replace_credentials(identifier, credentials)
            if feature not in credentials.get('features', []): raise AccountError('Provider no longer grants this feature; reconnect the account')
            return metadata['provider'], credentials['access_token']

    async def read_messages(self, identifier, query='', limit=10):
        if not isinstance(query, str) or len(query)>500 or type(limit) is not int or not 1<=limit<=20: raise ValueError('Invalid mail query/limit')
        config = self.mail_config(identifier)
        if config is not None:
            messages = await self.mail_call(self.mail.read_messages, config, query, limit)
            self.vault.metadata(identifier)
            return {'account_id':identifier, 'messages':messages, 'untrusted':True,
                    'detail':'Read only; at most 20 headers from the latest 100 inbox messages. Search is limited to that window.'}
        provider, token = await self.token(identifier, 'mail_read')
        headers = {'Authorization':'Bearer '+token}
        if provider == 'google':
            listing = await request_json(self.client, 'GET', 'https://gmail.googleapis.com/gmail/v1/users/me/messages', headers=headers, params={'q':query, 'maxResults':limit})
            messages = []
            for item in listing.get('messages', [])[:limit]:
                message_id = self._message_id(item.get('id'))
                message = await request_json(self.client, 'GET', 'https://gmail.googleapis.com/gmail/v1/users/me/messages/'+quote(message_id,safe=''), headers=headers, params={'format':'metadata','metadataHeaders':['From','To','Subject','Date']})
                fields = {entry['name'].lower():entry.get('value','')[:2000] for entry in message.get('payload',{}).get('headers',[]) if entry.get('name','').lower() in ('from','to','subject','date')}
                messages.append({'id':message_id, 'subject':fields.get('subject',''), 'from':fields.get('from',''), 'date':fields.get('date',''), 'preview':message.get('snippet','')[:1000]})
        else:
            params = {'$top':limit, '$select':'id,subject,from,receivedDateTime,bodyPreview', '$orderby':'receivedDateTime desc'}
            if query:
                params.pop('$orderby')
                params['$search'] = '"' + query.replace('\\','\\\\').replace('"','\\"') + '"'
            listing = await request_json(self.client,'GET','https://graph.microsoft.com/v1.0/me/messages',headers=headers,params=params)
            messages = [{'id':self._message_id(m.get('id')), 'subject':str(m.get('subject',''))[:2000],
                'from':str(m.get('from',{}).get('emailAddress',{}).get('address',''))[:2000], 'date':m.get('receivedDateTime',''), 'preview':str(m.get('bodyPreview',''))[:1000]} for m in listing.get('value',[])[:limit]]
        self.vault.metadata(identifier)  # Removed accounts cannot return new data.
        return {'account_id':identifier,'messages':messages,'untrusted':True,'detail':'First bounded page; refine the query for older messages'}

    def _message_id(self, value):
        if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_+=/-]{1,1024}',value) or not any(c.isalnum() for c in value): raise ValueError('Invalid message id')
        return value

    async def cancel_sign_in(self, identifier):
        async with self.lock: self.flows.pop(identifier, None)
        return {'cancelled': True}

    async def read_message(self, identifier, message_id):
        config = self.mail_config(identifier)
        if config is not None:
            if not isinstance(message_id, str) or not re.fullmatch(r'[1-9][0-9]{0,9}:[1-9][0-9]{0,9}', message_id): raise ValueError('Invalid IMAP message identifier')
            value = await self.mail_call(self.mail.read_message, config, message_id)
            self.vault.metadata(identifier)
            return {'account_id':identifier, 'message':value, 'untrusted':True,
                    'detail':'Read only; message stays unread. Attachments and remote images are not fetched.'}
        message_id = self._message_id(message_id)
        provider, token = await self.token(identifier, 'mail_read')
        headers = {'Authorization':'Bearer '+token}
        from .container_bridge import page_text
        if provider=='google':
            value=await request_json(self.client,'GET','https://gmail.googleapis.com/gmail/v1/users/me/messages/'+quote(message_id,safe=''),headers=headers,params={'format':'full'})
            fields={entry['name'].lower():entry.get('value','')[:2000] for entry in value.get('payload',{}).get('headers',[]) if entry.get('name','').lower() in ('from','to','subject','date')}
            plain, html = [], []
            parts=[value.get('payload',{})]
            visited=0
            while parts:
                part=parts.pop(); visited+=1
                if visited>100: raise AccountError('Message MIME structure exceeds limit')
                parts.extend(part.get('parts',[]))
                data=part.get('body',{}).get('data','')
                if part.get('mimeType') not in ('text/plain','text/html') or not data: continue
                if len(data)>700000: raise AccountError('Message body exceeds limit')
                try: decoded=base64.urlsafe_b64decode(data+'='*(-len(data)%4)).decode('utf-8',errors='replace')
                except (ValueError,TypeError): raise AccountError('Invalid message body') from None
                (plain if part.get('mimeType')=='text/plain' else html).append(decoded)
            body='\n'.join(plain) if plain else page_text('\n'.join(html))
            message={'id':message_id,'subject':fields.get('subject',''),'from':fields.get('from',''),'to':fields.get('to',''),'date':fields.get('date',''),'body':body[:30000],'truncated':len(body)>30000 or not plain and len(''.join(html))>20000}
        else:
            headers['Prefer']='outlook.body-content-type="text"'
            value=await request_json(self.client,'GET','https://graph.microsoft.com/v1.0/me/messages/'+quote(message_id,safe=''),headers=headers,params={'$select':'id,subject,from,toRecipients,receivedDateTime,body'})
            body=value.get('body',{}).get('content','')
            html_truncated=value.get('body',{}).get('contentType','').lower()=='html' and len(body)>20000
            if value.get('body',{}).get('contentType','').lower()=='html': body=page_text(body)
            message={'id':message_id,'subject':str(value.get('subject',''))[:2000], 'from':value.get('from',{}).get('emailAddress',{}).get('address',''),
                     'to':[entry.get('emailAddress',{}).get('address','') for entry in value.get('toRecipients',[])[:20]], 'date':value.get('receivedDateTime',''),'body':body[:30000],'truncated':len(body)>30000 or html_truncated}
        self.vault.metadata(identifier)
        return {'account_id':identifier,'message':message,'untrusted':True,'detail':'Read only; attachments and remote images are not fetched'}

    async def read_calendar(self, identifier, start, end, limit=30):
        if not all(isinstance(value,str) and len(value)<=100 for value in (start,end)): raise ValueError('Invalid calendar window')
        try:
            first, last = (datetime.fromisoformat(value.replace('Z','+00:00')) for value in (start,end))
            if first.tzinfo is None or last.tzinfo is None or not 0<(last-first).total_seconds()<=31*86400: raise ValueError()
        except (ValueError, TypeError, AttributeError): raise ValueError('Use timezone-aware dates spanning at most 31 days')
        if type(limit) is not int or not 1<=limit<=100: raise ValueError('Invalid calendar limit')
        provider, token = await self.token(identifier,'calendar_read')
        headers = {'Authorization':'Bearer '+token}
        if provider=='google':
            listing = await request_json(self.client,'GET','https://www.googleapis.com/calendar/v3/calendars/primary/events',headers=headers,
                params={'timeMin':start,'timeMax':end,'maxResults':limit,'singleEvents':'true','orderBy':'startTime'})
            events = [{'id':str(e.get('id',''))[:1024],'title':str(e.get('summary',''))[:2000], 'start':e.get('start',{}), 'end':e.get('end',{}), 'location':str(e.get('location',''))[:2000]} for e in listing.get('items',[])[:limit]]
        else:
            listing = await request_json(self.client,'GET','https://graph.microsoft.com/v1.0/me/calendarView',headers=headers,
                params={'startDateTime':start,'endDateTime':end,'$top':limit,'$select':'id,subject,start,end,location'})
            events = [{'id':str(e.get('id',''))[:1024], 'title':str(e.get('subject',''))[:2000], 'start':e.get('start',{}), 'end':e.get('end',{}), 'location':str(e.get('location',{}).get('displayName',''))[:2000]} for e in listing.get('value',[])[:limit]]
        self.vault.metadata(identifier)
        return {'account_id':identifier,'events':events,'untrusted':True,'detail':'Primary calendar, bounded date window'}
