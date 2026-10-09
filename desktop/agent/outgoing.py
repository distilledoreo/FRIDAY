"""Host-only mail/calendar executors. Activation defaults off; never retry effects."""
import base64
from datetime import datetime, time as day_time, timezone
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import format_datetime
import json
import re
import smtplib
import socket
import ssl
import time
from urllib.parse import quote
from zoneinfo import ZoneInfo

import httpx

from .mail import public_server
from .review import attendees, calendar_dates, email_address


class EffectRejected(RuntimeError): pass
class EffectUncertain(RuntimeError): pass


def mail_message(sender, payload, action_id):
    value=EmailMessage(policy=SMTP)
    value['From']=sender;value['To']=payload['to'];value['Subject']=payload['subject']
    value['Date']=format_datetime(datetime.now(timezone.utc))
    value['Message-ID']='<'+action_id+'@friday.local>'
    value.set_content(payload['body'],subtype='plain',charset='utf-8')
    data=value.as_bytes()
    if len(data)>512*1024: raise EffectRejected('Encoded email exceeded limit')
    return data


class PinnedSMTP(smtplib.SMTP):
    def __init__(self,host,address,port,guard):
        if type(port) is not int or port not in (465,587):raise EffectRejected('SMTP requires implicit TLS/465 or mandatory STARTTLS/587')
        self.address=address;self.implicit=port==465;self.guard=guard
        self.deadline=time.monotonic()+45;self.received=0;self.buffer=bytearray()
        self.tls_context=ssl.create_default_context()
        super().__init__(host,port,local_hostname='friday.local',timeout=10)
        self.set_debuglevel(0)

    def _print_debug(self,*args): pass

    def _get_socket(self,host,port,timeout):
        raw=socket.create_connection((self.address,port),timeout=timeout)
        if not self.implicit: return raw
        try:return self.tls_context.wrap_socket(raw,server_hostname=host)
        except BaseException:
            raw.close();raise

    def _line(self):
        while True:
            end=self.buffer.find(b'\n')
            if end>=0:
                if end+1>8192:raise EffectRejected('SMTP line exceeded limit')
                line=bytes(self.buffer[:end+1]);del self.buffer[:end+1];return line
            remaining=self.deadline-time.monotonic()
            if remaining<=0:raise TimeoutError()
            self.sock.settimeout(min(10,remaining))
            data=self.sock.recv(8192)
            if not data:raise smtplib.SMTPServerDisconnected()
            self.received+=len(data)
            if self.received>256*1024 or len(self.buffer)+len(data)>16384:raise EffectRejected('SMTP response exceeded limit')
            self.buffer.extend(data)

    def getreply(self):
        lines=[];total=0
        while True:
            line=self._line();total+=len(line)
            if total>65536 or len(line)<4 or not line[:3].isdigit() or line[3:4] not in (b'-',b' '):raise EffectRejected('Invalid SMTP response')
            lines.append(line[4:].strip())
            if line[3:4]==b' ':return int(line[:3]),b'\n'.join(lines)

    def data(self,msg):
        code,reply=self.docmd('data')
        if code!=354:raise smtplib.SMTPDataError(code,b'Provider rejected data')
        self.guard()  # Last check before body submission, including cancellation.
        if time.monotonic()>=self.deadline:raise EffectRejected('SMTP deadline expired before body submission')
        if not isinstance(msg,bytes):raise EffectRejected('Invalid encoded email')
        data=re.sub(br'(?m)^\.',b'..',msg)
        if not data.endswith(b'\r\n'):data+=b'\r\n'
        self.send(data+b'.\r\n')
        return self.getreply()


def smtp_send(config,raw,recipient,guard,resolve=socket.getaddrinfo,connection=PinnedSMTP):
    client=None
    try:
        if type(config.get('smtp_port')) is not int or config['smtp_port'] not in (465,587) or not email_address(config.get('email')) or not email_address(recipient):
            raise EffectRejected('Invalid secure SMTP settings or address')
        host,address=public_server(config['smtp_host'],config['smtp_port'],resolve)
        client=connection(host,address,config['smtp_port'],guard)
        if client.ehlo()[0]!=250:raise EffectRejected('SMTP greeting rejected')
        if config['smtp_port']==587:
            client.starttls(context=client.tls_context)
            client.buffer.clear()
            if client.ehlo()[0]!=250:raise EffectRejected('SMTP TLS greeting rejected')
        client.login(config.get('smtp_username',config['username']),config.get('smtp_password',config['password']))
        guard()
        refused=client.sendmail(config['email'],[recipient],raw)
        if refused:raise EffectRejected('SMTP rejected the recipient')
        return {'outcome':'accepted','provider':'smtp','detail':'Accepted by the SMTP server; delivery is not confirmed'}
    except EffectRejected:raise
    except (ValueError,ssl.SSLCertVerificationError,smtplib.SMTPAuthenticationError,smtplib.SMTPSenderRefused,smtplib.SMTPRecipientsRefused,smtplib.SMTPDataError,smtplib.SMTPNotSupportedError):
        raise EffectRejected('SMTP rejected this action or its secure settings; no automatic retry') from None
    except Exception:raise EffectUncertain('SMTP outcome is uncertain; inspect Sent/provider records before creating another proposal') from None
    finally:
        if client:client.close()


class Executors:
    def __init__(self,accounts,enabled=False,smtp=smtp_send):
        self.accounts=accounts;self.vault=accounts.vault;self.enabled=enabled is True;self.smtp=smtp

    def identity(self,identifier):
        account=self.vault.metadata(identifier);credentials=self.vault.credentials(identifier)
        sender=credentials.get('email') if account['provider']=='imap' else account['label']
        result={'provider':account['provider'],'sender':sender}
        if account['provider']=='imap':result.update(server=credentials.get('smtp_host'),port=credentials.get('smtp_port'),login=credentials.get('smtp_username',credentials.get('username')))
        return result

    def ready_for(self,action):
        if not self.enabled:return False
        payload=action['payload'];account=self.vault.metadata(payload['account_id']);credentials=self.vault.credentials(account['id'])
        if not email_address(self.identity(account['id'])['sender']):return False
        if action['kind']=='send':
            if account['provider']=='imap':return bool(credentials.get('smtp_host')) and credentials.get('smtp_port') in (465,587)
            return 'mail_send' in credentials.get('features',[])
        return action['kind'] in ('calendar_create','calendar_update') and account['provider'] in ('google','microsoft') and 'calendar_write' in credentials.get('features',[])

    async def submit(self,method,url,headers,expected,identifier=None,**kwargs):
        try:
            async with self.accounts.client.stream(method,url,headers=headers,**kwargs) as response:
                status=response.status_code
                if status not in expected:
                    if 400<=status<500:raise EffectRejected(f'Provider rejected the action (HTTP {status}); review a new proposal, never retry this approval')
                    raise EffectUncertain('Provider outcome is uncertain; inspect its records before proposing another action')
                # A successful status acknowledges acceptance even if no body is returned.
                body=bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body)>2*1024*1024:raise EffectUncertain('Provider accepted but receipt exceeded limit; inspect provider records')
                try:value=json.loads(body) if body else {}
                except ValueError:value={}
                receipt=value.get('id') if isinstance(value,dict) else None
                if not isinstance(receipt,str) or not re.fullmatch(r'[A-Za-z0-9_+=/-]{1,1024}',receipt):receipt=None
                if receipt and identifier:
                    try:
                        credentials=self.vault.credentials(identifier)
                        if any(isinstance(credentials.get(key),str) and len(credentials[key])>=6 and credentials[key] in receipt for key in ('password','smtp_password','access_token','refresh_token','client_secret')):receipt=None
                    except Exception:receipt=None
                return {'outcome':'accepted','http_status':status,'receipt_id':receipt[:1024] if isinstance(receipt,str) else None,
                        'detail':'Provider accepted the request; email delivery/attendee receipt is not confirmed'}
        except (EffectRejected,EffectUncertain):raise
        except httpx.HTTPError:raise EffectUncertain('Connection ended without a reliable receipt; inspect provider records before proposing another action') from None

    async def execute(self,action,action_id,guard):
        from .review import OutgoingReview
        review=OutgoingReview(self.vault,self).inspect(action)
        if not review['allowed'] or not review['executable']:raise EffectRejected('Outgoing policy/activation/account permission is unavailable')
        payload=action['payload'];identifier=payload['account_id'];identity=self.identity(identifier)
        guard()
        if action['kind']=='send':
            raw=mail_message(identity['sender'],payload,action_id)
            if identity['provider']=='imap':
                config=self.vault.credentials(identifier)
                # Keep the host slot while an irreversible SMTP call finishes.
                # Avoid Accounts.mail_call's exception conversion: preserve uncertain outcome.
                if len(self.accounts.mail_tasks)>=2:raise EffectRejected('Mail access is busy; review a new proposal later')
                import asyncio
                task=asyncio.create_task(asyncio.to_thread(self.smtp,config,raw,payload['to'],guard))
                self.accounts.mail_tasks.add(task)
                def finished(value):
                    self.accounts.mail_tasks.discard(value)
                    if not value.cancelled():value.exception()
                task.add_done_callback(finished)
                return await asyncio.shield(task)
            try:provider,token=await self.accounts.token(identifier,'mail_send')
            except Exception:raise EffectRejected('Account could not authorize sending; no outgoing request submitted') from None
            guard();headers={'Authorization':'Bearer '+token}
            if provider=='google':return await self.submit('POST','https://gmail.googleapis.com/gmail/v1/users/me/messages/send',headers,(200,),identifier=identifier,json={'raw':base64.urlsafe_b64encode(raw).decode()})
            return await self.submit('POST','https://graph.microsoft.com/v1.0/me/sendMail',headers,(202,),identifier=identifier,json={
                'message':{'from':{'emailAddress':{'address':identity['sender']}},'subject':payload['subject'],'body':{'contentType':'Text','content':payload['body']},'toRecipients':[{'emailAddress':{'address':payload['to']}}]},'saveToSentItems':True})
        try:provider,token=await self.accounts.token(identifier,'calendar_write')
        except Exception:raise EffectRejected('Account could not authorize calendar changes; no outgoing request submitted') from None
        roles={}
        if action['kind']=='calendar_update':
            try:current=(await self.accounts.read_calendar_event(identifier,payload['event_id'],feature='calendar_write'))['event']
            except Exception:raise EffectRejected('Calendar source/version could not be checked; no change submitted') from None
            if current['version']!=payload['expected_version'] or not current['organizer_self'] or not current['single_event'] or current['truncated'] or attendees(current['attendees'])!=attendees(payload['previous_attendees']):
                raise EffectRejected('Calendar version/organizer/attendees changed or are unsupported; read and review a new proposal')
            roles=current.get('attendee_roles',{})
            if any(role not in ('required','optional','resource') for role in roles.values()):raise EffectRejected('Unsupported existing attendee role; no change submitted')
        all_day,start,end,zone=calendar_dates(payload)
        headers={'Authorization':'Bearer '+token}
        method='POST' if action['kind']=='calendar_create' else 'PATCH'
        if method=='PATCH':headers['If-Match']=payload['expected_version']
        if provider=='google':
            body={'summary':payload['title'],'description':payload['description'],'location':payload['location'],
                  'start':{'date':start.isoformat()} if all_day else {'dateTime':start.isoformat(),'timeZone':zone},
                  'end':{'date':end.isoformat()} if all_day else {'dateTime':end.isoformat(),'timeZone':zone},
                  'attendees':[dict({'email':address},**({'optional':True} if roles.get(address.lower())=='optional' else {'resource':True} if roles.get(address.lower())=='resource' else {})) for address in payload['attendees']]}
            url='https://www.googleapis.com/calendar/v3/calendars/primary/events'
            if method=='POST':body['id']='f'+action_id
            else:url+='/'+quote(payload['event_id'],safe='')
            guard()
            return await self.submit(method,url,headers,(200,201),identifier=identifier,json=body,params={'sendUpdates':'all' if payload['notify_attendees'] else 'none'})
        def graph_time(value):
            if all_day:return {'dateTime':datetime.combine(value,day_time()).isoformat(),'timeZone':zone}
            return {'dateTime':value.astimezone(timezone.utc).replace(tzinfo=None).isoformat(),'timeZone':'UTC'}
        body={'subject':payload['title'],'body':{'contentType':'Text','content':payload['description']},'location':{'displayName':payload['location']},
              'start':graph_time(start),'end':graph_time(end),'isAllDay':all_day,
              'attendees':[{'emailAddress':{'address':address},'type':roles.get(address.lower(),'required')} for address in payload['attendees']]}
        url='https://graph.microsoft.com/v1.0/me/events'
        if method=='POST':body['transactionId']=action_id
        else:url+='/'+quote(payload['event_id'],safe='')
        guard()
        return await self.submit(method,url,headers,(200,201),identifier=identifier,json=body)
