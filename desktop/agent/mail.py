"""Host-only, bounded IMAP reads. SMTP settings are saved, never executed here.

Implicit TLS IMAP (993), SMTP TLS (465) or mandatory STARTTLS (587).
Connections pin a public DNS answer but verify the certificate against the name.
See RFC 8314 and Python's imaplib documentation.
"""
import contextlib
from email import policy
from email.parser import BytesParser
import imaplib
import re
import socket
import ssl
import time

from .public_web import destination

MAX_MESSAGE = 512 * 1024
MAX_WIRE = 2 * 1024 * 1024
MAX_LINE = 64 * 1024


class MailError(RuntimeError): pass


def hostname(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 253:
        raise ValueError('Invalid mail server name')
    try: host = value.encode('idna').decode('ascii').lower()
    except UnicodeError: raise ValueError('Invalid mail server name') from None
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', host) or '.' not in host or any(
        not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label) for label in host.split('.')):
        raise ValueError('Use the provider’s full mail server name')
    return host


def settings(value):
    required = {'email', 'username', 'password', 'imap_host'}
    optional = {'smtp_host', 'smtp_port', 'smtp_username', 'smtp_password'}
    if not isinstance(value, dict) or not required <= set(value) or set(value) - required - optional:
        raise ValueError('Invalid mail settings')
    email = value['email']
    if not isinstance(email, str) or not re.fullmatch(r'[^\s@<>]{1,64}@[^\s@<>]{1,253}', email) or len(email) > 320 or any(ord(c) < 33 or ord(c) == 127 for c in email):
        raise ValueError('Enter the account email address')
    for name in ('username', 'password', 'smtp_username', 'smtp_password'):
        if name in value and (not isinstance(value[name], str) or not 1 <= len(value[name]) <= (1024 if 'password' in name else 320)
                              or any(ord(c) < 32 or ord(c) == 127 for c in value[name])):
            raise ValueError('Invalid mail login settings')
    result = dict(value, imap_host=hostname(value['imap_host']), features=['mail_read'])
    if value.get('smtp_host'):
        result['smtp_host'] = hostname(value['smtp_host'])
        port = value.get('smtp_port', 465)
        if type(port) is not int or port not in (465, 587): raise ValueError('SMTP requires TLS on 465 or STARTTLS on 587')
        result['smtp_port'] = port
        result['smtp_verified'] = False
    elif set(value) & optional:
        raise ValueError('SMTP settings require a server name')
    return result


def public_server(host, port=993, resolve=socket.getaddrinfo):
    host = hostname(host)
    # Reuse the public-web policy, including mixed/private DNS answer rejection.
    def answers(name, ignored_port, **kwargs): return resolve(name, port, **kwargs)
    return destination('https://' + host, answers)[:2]


class PinnedIMAP(imaplib.IMAP4_SSL):
    def __init__(self, host, address):
        self.address = address
        self.deadline = time.monotonic() + 30
        self.received = 0
        self.buffer = bytearray()
        super().__init__(host, 993, ssl_context=ssl.create_default_context(), timeout=10)
        self.debug = 0

    def _create_socket(self, timeout):
        raw = socket.create_connection((self.address, 993), timeout=timeout)
        try: return self.ssl_context.wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise

    def _receive(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0: raise MailError('Mail read timed out')
        self.sock.settimeout(min(10, remaining))
        data = self.sock.recv(16384)
        if not data: raise MailError('Mail server closed the connection')
        self.received += len(data)
        if self.received > MAX_WIRE: raise MailError('Mail response exceeded limit')
        self.buffer.extend(data)

    def read(self, size):
        if not 0 <= size <= MAX_MESSAGE + 1: raise MailError('Mail literal exceeded limit')
        while len(self.buffer) < size: self._receive()
        value = bytes(self.buffer[:size]); del self.buffer[:size]
        return value

    def readline(self):
        while True:
            end = self.buffer.find(b'\n')
            if end >= 0:
                if end + 1 > MAX_LINE: raise MailError('Mail line exceeded limit')
                value = bytes(self.buffer[:end + 1]); del self.buffer[:end + 1]
                return value
            if len(self.buffer) > MAX_LINE: raise MailError('Mail line exceeded limit')
            self._receive()

    def _log(self, *args): pass  # imaplib normally keeps recent wire content.


def message(raw, identifier, truncated=False):
    parsed = BytesParser(policy=policy.default).parsebytes(raw)
    headers = {name: str(parsed.get(name, ''))[:2000] for name in ('subject', 'from', 'to', 'date')}
    plain, html = [], []
    for index, part in enumerate(parsed.walk()):
        if index >= 100: raise MailError('Message MIME structure exceeded limit')
        if part.get_content_disposition() == 'attachment' or part.get_filename(): continue
        if part.get_content_type() not in ('text/plain', 'text/html') or part.is_multipart(): continue
        content = part.get_payload(decode=True) or b''
        charset = part.get_content_charset() or 'utf-8'
        try: text = content.decode(charset, errors='replace')
        except (LookupError, UnicodeError): text = content.decode('utf-8', errors='replace')
        (plain if part.get_content_type() == 'text/plain' else html).append(text)
    if plain: body = '\n'.join(plain)
    else:
        from .container_bridge import page_text
        body = page_text('\n'.join(html))
        truncated = truncated or len(''.join(html)) > 20000
    return dict(headers, id=identifier, body=body[:30000], truncated=truncated or len(body) > 30000)


class Mail:
    def __init__(self, resolve=socket.getaddrinfo, connection=PinnedIMAP):
        self.resolve = resolve
        self.connection = connection

    @contextlib.contextmanager
    def session(self, config):
        client = None
        try:
            host, address = public_server(config['imap_host'], resolve=self.resolve)
            client = self.connection(host, address)
            client.login(config['username'], config['password'])
            yield client
        except ValueError:
            raise MailError('Mail server must resolve only to public addresses') from None
        except MailError: raise
        except Exception:
            raise MailError('Mail connection failed; check server, credentials, TLS and provider access') from None
        finally:
            if client:
                # Shutdown closes locally; avoid logout network work after a timeout.
                with contextlib.suppress(Exception): client.shutdown()

    def verify(self, config):
        if config.get('smtp_host'): public_server(config['smtp_host'], config['smtp_port'], self.resolve)
        with self.session(config): pass

    def _inbox(self, client):
        status, data = client.select('INBOX', readonly=True)
        if status != 'OK' or not data or not re.fullmatch(rb'[0-9]{1,10}', data[0]): raise MailError('Inbox unavailable')
        count = int(data[0])
        status, validity = client.response('UIDVALIDITY')
        if not validity or not isinstance(validity[0], bytes) or not re.fullmatch(rb'[1-9][0-9]{0,9}', validity[0]):
            raise MailError('Inbox did not provide stable message identifiers')
        return count, validity[0].decode()

    def _fetch(self, client, uid, full):
        section = f'BODY.PEEK[]<0.{MAX_MESSAGE + 1}>' if full else 'BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE)]<0.16384>'
        status, data = client.uid('FETCH', uid, f'(UID RFC822.SIZE {section})')
        records = [entry for entry in (data or []) if isinstance(entry, tuple) and len(entry) == 2]
        if status != 'OK' or len(records) != 1: raise MailError('Message no longer available')
        metadata, raw = records[0]
        returned = re.search(rb'\bUID ([0-9]+)\b', metadata)
        size = re.search(rb'\bRFC822.SIZE ([0-9]+)\b', metadata)
        if not returned or returned[1].decode() != uid or not size or not isinstance(raw, bytes): raise MailError('Invalid message response')
        limit = MAX_MESSAGE if full else 16384
        if len(raw) > limit + (1 if full else 0): raise MailError('Message preview exceeded limit')
        return raw[:limit], int(size[1]) > len(raw) or len(raw) > limit

    def read_messages(self, config, query, limit):
        with self.session(config) as client:
            count, validity = self._inbox(client)
            if not count: return []
            scope = f'{max(1, count - 99)}:{count}'
            if query:
                client.literal = query.encode('utf-8')
                status, values = client.uid('SEARCH', 'CHARSET', 'UTF-8', scope, 'TEXT')
            else: status, values = client.uid('SEARCH', None, scope, 'ALL')
            if status != 'OK' or not values or not isinstance(values[0], bytes): raise MailError('Mail search unavailable')
            uids = values[0].split()
            if len(uids) > 100 or any(not re.fullmatch(rb'[1-9][0-9]{0,9}', uid) for uid in uids): raise MailError('Mail search exceeded limit')
            result = []
            for uid in sorted(set(uids), key=int, reverse=True)[:limit]:
                raw, truncated = self._fetch(client, uid.decode(), False)
                headers = BytesParser(policy=policy.default).parsebytes(raw, headersonly=True)
                result.append({'id':validity + ':' + uid.decode(), 'subject':str(headers.get('subject', ''))[:2000],
                    'from':str(headers.get('from', ''))[:2000], 'date':str(headers.get('date', ''))[:2000],
                    'preview':'Header preview', 'truncated':truncated})
            return result

    def read_message(self, config, identifier):
        if not isinstance(identifier, str) or not re.fullmatch(r'[1-9][0-9]{0,9}:[1-9][0-9]{0,9}', identifier):
            raise ValueError('Invalid IMAP message identifier')
        validity, uid = identifier.split(':')
        with self.session(config) as client:
            _, current = self._inbox(client)
            if current != validity: raise MailError('Inbox changed; refresh before opening this message')
            raw, truncated = self._fetch(client, uid, True)
            return message(raw, identifier, truncated)
