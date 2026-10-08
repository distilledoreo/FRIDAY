"""Exact, bounded account read grants for a separately approved cloud task."""
from datetime import datetime
import re


def normalize(reads):
    if not isinstance(reads, list) or len(reads) > 5: raise ValueError('Choose at most five account reads')
    result = []
    for value in reads:
        if not isinstance(value, dict) or not isinstance(value.get('account_id'), str) or not re.fullmatch(r'[a-f0-9]{32}', value['account_id']): raise ValueError('Invalid scoped account')
        kind = value.get('kind')
        required = {'account_id', 'kind'}
        allowed = required | ({'query', 'limit'} if kind == 'inbox' else {'message_id'} if kind == 'message' else {'start', 'end', 'limit'} if kind == 'calendar' else set())
        if kind not in ('inbox', 'message', 'calendar') or set(value) - allowed: raise ValueError('Unsupported account read scope')
        item = {'account_id':value['account_id'], 'kind':kind}
        if kind == 'inbox':
            query = value.get('query', '')
            if not isinstance(query, str) or len(query) > 500: raise ValueError('Invalid inbox scope query')
            item['query'] = query
        if kind in ('inbox', 'calendar'):
            limit = value.get('limit', 10 if kind == 'inbox' else 30)
            if type(limit) is not int or not 1 <= limit <= (20 if kind == 'inbox' else 100): raise ValueError('Invalid account read limit')
            item['limit'] = limit
        if kind == 'message':
            identifier = value.get('message_id')
            if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9_+=/:-]{1,1024}', identifier) or not any(c.isalnum() for c in identifier): raise ValueError('Invalid scoped message')
            item['message_id'] = identifier
        if kind == 'calendar':
            try:
                if any(not isinstance(value.get(name), str) or len(value[name]) > 100 for name in ('start', 'end')): raise ValueError()
                first, last = (datetime.fromisoformat(value[name].replace('Z', '+00:00')) for name in ('start', 'end'))
                if first.tzinfo is None or last.tzinfo is None or not 0 < (last-first).total_seconds() <= 31*86400: raise ValueError()
            except (KeyError, ValueError, TypeError, AttributeError): raise ValueError('Calendar scope needs timezone-aware dates within 31 days') from None
            item.update(start=first.isoformat(), end=last.isoformat())
        if item not in result: result.append(item)
    return result


def bound(reads):
    if not isinstance(reads, list): raise ValueError('Invalid account scopes')
    plain = normalize([{key:value for key, value in read.items() if key != 'account'} for read in reads if isinstance(read, dict)])
    if len(plain) != len(reads): raise ValueError('Invalid or duplicate account scopes')
    result = []
    for read, item in zip(reads, plain):
        account = read.get('account')
        if not isinstance(account, dict) or set(account) != {'id','provider','label'} or account['id'] != item['account_id'] or account['provider'] not in ('google','microsoft','imap') or not isinstance(account['label'], str) or not 1 <= len(account['label']) <= 320:
            raise ValueError('Account scope must bind its identity')
        if item['kind'] == 'calendar' and account['provider'] == 'imap': raise ValueError('IMAP does not provide a calendar')
        result.append(dict(item, account=account))
    return result
