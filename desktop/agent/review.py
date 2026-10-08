"""Independent deterministic outgoing review. This module has no network effects."""
import hashlib
import json
import re
from email.utils import parseaddr


def fingerprint(action):
    return hashlib.sha256(json.dumps(action, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


class OutgoingReview:
    def __init__(self, accounts=None): self.accounts = accounts

    def inspect(self, action):
        issues = []
        kind = action.get('kind')
        payload = action.get('payload')
        destination = action.get('destination')
        account = None
        if set(action) != {'kind','destination','payload'}: issues.append('Unexpected action fields')
        if kind != 'send': issues.append('This action type has no reviewed executor yet')
        if not isinstance(payload, dict): issues.append('A concrete payload is required')
        elif kind == 'send':
            expected = {'account_id','to','subject','body'}
            if set(payload) != expected: issues.append('Specify account, recipient, subject and body only; attachments need separate support')
            recipient = payload.get('to')
            if not isinstance(recipient, str) or not re.fullmatch(r'[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+', recipient) or parseaddr(recipient)[1] != recipient:
                issues.append('Use one concrete email address without headers or display-name tricks')
            if destination != recipient: issues.append('Destination does not match the recipient')
            for field, limit in [('subject', 200), ('body', 20000)]:
                value = payload.get(field)
                if not isinstance(value, str) or len(value) > limit: issues.append(f'Invalid {field}')
            if isinstance(payload.get('subject'), str) and any(c in payload['subject'] for c in '\r\n'): issues.append('Subject contains injected headers')
            try:
                if self.accounts is None: raise ValueError()
                account = self.accounts.metadata(payload.get('account_id'))
            except (ValueError, TypeError): issues.append('Select an existing account')
            content = json.dumps(payload)
            if re.search(r'-----BEGIN .*PRIVATE KEY|Bearer\s+[A-Za-z0-9._-]+|\bsk-[A-Za-z0-9_-]{16,}', content, re.I):
                issues.append('Payload appears to contain a credential; remove it before submitting')
        return {'fingerprint': fingerprint(action), 'allowed': not issues, 'issues': issues,
                'account': account, 'destination': destination,
                'side_effect': 'Send one email' if kind == 'send' else 'Unsupported outgoing effect',
                'reviewer': 'FRIDAY outgoing policy v1', 'executable': False,
                'detail': 'Separate account/provider execution is not connected; no outgoing action has run'}
