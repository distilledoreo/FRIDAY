"""Independent deterministic outgoing review. No network effects or approvals."""
from datetime import date, datetime
import hashlib
import json
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


def fingerprint(action):
    return hashlib.sha256(json.dumps(action, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def email_address(value):
    return isinstance(value,str) and len(value)<=320 and re.fullmatch(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}',value) is not None


def attendees(value):
    if not isinstance(value,list) or len(value)>20 or any(not email_address(address) for address in value) or len({address.lower() for address in value})!=len(value):
        raise ValueError('Use at most 20 distinct attendee email addresses')
    return sorted(address.lower() for address in value)


def calendar_dates(payload):
    try:
        start,end,zone=(payload[key] for key in ('start','end','timezone'))
        if not isinstance(zone,str) or len(zone)>100: raise ValueError()
        ZoneInfo(zone)
        if not all(isinstance(value,str) and len(value)<=100 for value in (start,end)): raise ValueError()
        all_day=bool(re.fullmatch(r'\d{4}-\d{2}-\d{2}',start) and re.fullmatch(r'\d{4}-\d{2}-\d{2}',end))
        if all_day:
            first,last=date.fromisoformat(start),date.fromisoformat(end)
            if not 0<(last-first).days<=31: raise ValueError()
        else:
            first,last=(datetime.fromisoformat(value.replace('Z','+00:00')) for value in (start,end))
            if first.tzinfo is None or last.tzinfo is None or not 0<(last-first).total_seconds()<=31*86400: raise ValueError()
        return all_day,first,last,zone
    except (KeyError,TypeError,ValueError,ZoneInfoNotFoundError):
        raise ValueError('Use increasing timezone-aware times or all-day dates within 31 days, with an IANA timezone; the end date is exclusive') from None


class OutgoingReview:
    def __init__(self, accounts=None, executors=None): self.accounts,self.executors=accounts,executors

    def inspect(self, action):
        if not isinstance(action,dict): raise ValueError('Invalid outgoing action')
        issues=[];kind=action.get('kind');payload=action.get('payload');destination=action.get('destination');account=None
        if set(action)!={'kind','destination','payload'}: issues.append('Unexpected action fields')
        if kind not in ('send','calendar_create','calendar_update'): issues.append('This action type has no reviewed executor')
        if not isinstance(payload,dict): issues.append('A concrete payload is required')
        elif kind in ('send','calendar_create','calendar_update'):
            try:
                if self.accounts is None: raise ValueError()
                account=self.accounts.metadata(payload.get('account_id'))
            except (ValueError,TypeError): issues.append('Select an existing account')
            if kind=='send':
                if set(payload)!={'account_id','to','subject','body'}: issues.append('Specify account, recipient, subject and body only; no attachments, Cc or Bcc')
                if not email_address(payload.get('to')): issues.append('Use one concrete email address without headers or display names')
                if destination!=payload.get('to'): issues.append('Destination does not match the recipient')
                for field,limit in [('subject',200),('body',20000)]:
                    value=payload.get(field)
                    if not isinstance(value,str) or len(value)>limit or '\x00' in value or field=='subject' and any(ord(c)<32 or ord(c)==127 for c in value): issues.append(f'Invalid {field}')
            else:
                fields={'account_id','title','description','location','start','end','timezone','attendees','notify_attendees'}
                if kind=='calendar_update': fields|={'event_id','expected_version','previous_attendees'}
                if set(payload)!=fields: issues.append('Specify the exact supported calendar fields; no recurrence, attachments or hidden options')
                for field,limit in [('title',200),('description',20000),('location',1000)]:
                    value=payload.get(field)
                    if not isinstance(value,str) or len(value)>limit or '\x00' in value or field!='description' and any(ord(c)<32 or ord(c)==127 for c in value): issues.append(f'Invalid {field}')
                try: calendar_dates(payload)
                except ValueError as error: issues.append(str(error))
                try:
                    future=attendees(payload.get('attendees'))
                    previous=attendees(payload.get('previous_attendees')) if kind=='calendar_update' else []
                    if type(payload.get('notify_attendees')) is not bool or payload['notify_attendees']!=bool(future or previous): raise ValueError('Calendar invitations/updates must be explicitly approved for all affected attendees')
                except ValueError as error: issues.append(str(error))
                expected='primary'
                if kind=='calendar_update':
                    event=payload.get('event_id');version=payload.get('expected_version')
                    if not isinstance(event,str) or not re.fullmatch(r'[A-Za-z0-9_+=/-]{1,1024}',event) or not any(c.isalnum() for c in event): issues.append('Invalid event identifier')
                    if not isinstance(version,str) or not re.fullmatch(r'(?:W/)?"[\x21\x23-\x7E]{1,1000}"',version): issues.append('Use the exact event version from a confirmed calendar event read')
                    expected='primary/'+str(event)
                if destination!=expected: issues.append('Destination must match the primary calendar and selected event')
                if account and account['provider']=='imap': issues.append('IMAP does not provide a calendar')
            content=json.dumps(payload,ensure_ascii=False)
            if re.search(r'-----BEGIN .*PRIVATE KEY|Bearer\s+[A-Za-z0-9._-]+|\bsk-[A-Za-z0-9_-]{16,}',content,re.I): issues.append('Payload appears to contain a credential; remove it')
            if account:
                try:
                    credentials=self.accounts.credentials(account['id'])
                    if any(isinstance(credentials.get(key),str) and len(credentials[key])>=6 and credentials[key] in content for key in ('password','smtp_password','access_token','refresh_token','client_secret')):
                        issues.append('Payload contains a saved credential; remove it')
                except Exception: issues.append('Unlock the credential vault before reviewing an outgoing action')
        side_effect={'send':'Send one email','calendar_create':'Create one primary-calendar event','calendar_update':'Update one existing primary-calendar event'}.get(kind,'Unsupported outgoing effect')
        identity={};executable=False;detail='Outgoing activation is off; no action has run'
        if account and self.executors:
            try:
                identity=self.executors.identity(account['id'])
                executable=not issues and self.executors.ready_for(action)
                detail='Exact approval submits once; provider acceptance does not prove delivery' if executable else 'Account permission/setup or outgoing activation is missing; no action has run'
            except Exception: detail='Unlock/reconnect this account before outgoing execution; no action has run'
        binding={'action':action,'account':account,'identity':identity,'reviewer':'FRIDAY outgoing policy v2'}
        return {'fingerprint':fingerprint(action),'review_fingerprint':fingerprint(binding),'allowed':not issues,'issues':issues,
                'account':account,'identity':identity,'destination':destination,'side_effect':side_effect,
                'reviewer':'FRIDAY outgoing policy v2','executable':executable,'detail':detail}
