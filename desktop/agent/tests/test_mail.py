import asyncio
from datetime import datetime, timedelta, timezone
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
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from cryptography.fernet import Fernet
from fastapi import Depends, FastAPI, Header, HTTPException
import httpx

from desktop.agent.accounts import Accounts, AccountError
from desktop.agent.api import install
from desktop.agent.mail import Mail, MailError, PinnedIMAP, MAX_MESSAGE, MAX_LINE, MAX_WIRE, settings, public_server, message
from desktop.agent.vault import Vault

CONFIG = {'email':'test@example.com', 'username':'test@example.com', 'password':'synthetic-password', 'imap_host':'mail.example.com', 'smtp_host':'smtp.example.com', 'smtp_port':587}
RAW = b'From: Sender <sender@example.com>\r\nTo: test@example.com\r\nSubject: =?utf-8?b?SGVsbG8g8J+YgA==?=\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nSynthetic body'


def resolve(host, port, **kwargs): return [(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('8.8.8.8', port))]


class FakeIMAP:
    def __init__(self, host, address):
        self.calls = []
        self.validity = b'7'
        self.count = b'500'
        self.closed = False
        self.literal = None
    def login(self, username, password): self.calls.append(('login',))
    def select(self, inbox, readonly=False):
        self.calls.append(('select', inbox, readonly))
        return 'OK', [self.count]
    def response(self, name): return name, [self.validity]
    def uid(self, command, *args):
        self.calls.append((command, *args))
        if command == 'SEARCH': return 'OK', [b'1 2 3']
        return 'OK', [(b'3 (UID ' + args[0].encode() + b' RFC822.SIZE ' + str(len(RAW)).encode(), RAW), b')']
    def shutdown(self): self.closed = True


class MailTests(unittest.TestCase):
    def setUp(self):
        self.connections = []
        def connection(host, address):
            client = FakeIMAP(host, address); self.connections.append(client); return client
        self.mail = Mail(resolve, connection)
    def test_tls_settings_and_private_mixed_dns_are_rejected_before_login(self):
        for changes in ({'imap_host':'localhost'}, {'imap_host':'a.ts.net'}, {'smtp_port':25}, {'username':'bad\r\nname'}, {'password':'bad\x00password'}, {'imap_port':143}, {'smtp_host':'https://example.com'}):
            with self.assertRaises(ValueError): settings(dict(CONFIG, **changes)) if 'imap_host' not in changes or changes['imap_host'] == 'localhost' else public_server(changes['imap_host'], resolve=resolve)
        for address in ('127.0.0.1', '100.64.0.1', '192.168.1.1', '::ffff:8.8.8.8'):
            def mixed(host, port, **kwargs): return resolve(host, port) + [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (address, port))]
            with self.assertRaises(ValueError): public_server(CONFIG['imap_host'], resolve=mixed)
        self.assertEqual(settings(CONFIG)['features'], ['mail_read'])
        self.assertFalse(settings(CONFIG)['smtp_verified'])
    def test_inbox_is_readonly_recent_bounded_and_unicode_query_is_literal(self):
        result = self.mail.read_messages(settings(CONFIG), 'café "test"', 2)
        client = self.connections[0]
        self.assertIn(('select', 'INBOX', True), client.calls)
        self.assertIn(('SEARCH', 'CHARSET', 'UTF-8', '401:500', 'TEXT'), client.calls)
        self.assertEqual(client.literal, 'café "test"'.encode())
        self.assertEqual([value['id'] for value in result], ['7:3', '7:2'])
        self.assertEqual(result[0]['subject'], 'Hello 😀')
        self.assertTrue(all('BODY.PEEK' in call[-1] for call in client.calls if call[0] == 'FETCH'))
        self.assertTrue(client.closed)
    def test_uidvalidity_prevents_reusing_an_old_message_and_headers_are_bounded(self):
        with self.assertRaises(MailError): self.mail.read_message(settings(CONFIG), '8:3')
        self.assertFalse(any(call[0] == 'FETCH' for call in self.connections[0].calls))
        self.assertEqual(self.mail.read_message(settings(CONFIG), '7:3')['body'], 'Synthetic body')
        with self.assertRaises(ValueError): self.mail.read_message(settings(CONFIG), '7:3\r\nDELETE')
    def test_html_attachments_truncation_and_mime_limit(self):
        raw = b'Content-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\nContent-Type: text/html\r\n\r\n<p>Visible</p><script>hidden()</script>\r\n--x\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=secret.txt\r\n\r\nDo not include\r\n--x--'
        result = message(raw, '7:1')
        self.assertIn('Visible', result['body']); self.assertNotIn('hidden', result['body']); self.assertNotIn('Do not include', result['body'])
        self.assertTrue(message(RAW + b'x'*31000, '7:1')['truncated'])
        excessive = b'Content-Type: multipart/mixed; boundary=x\r\n\r\n' + b'--x\r\nContent-Type: text/plain\r\n\r\nbody\r\n'*101 + b'--x--'
        with self.assertRaises(MailError): message(excessive, '7:1')
    def test_socket_read_budgets_deadlines_and_pinned_certificate_name(self):
        client = PinnedIMAP.__new__(PinnedIMAP)
        client.buffer = bytearray(b'x'*(MAX_LINE+1))
        with self.assertRaises(MailError): client.readline()
        with self.assertRaises(MailError): client.read(MAX_MESSAGE+2)
        client.deadline = time.monotonic()-1
        with self.assertRaises(MailError): client._receive()
        client.deadline = time.monotonic()+30; client.received = MAX_WIRE
        class FakeSocket:
            def settimeout(self, value): pass
            def recv(self, value): return b'x'
        client.sock = FakeSocket()
        with self.assertRaises(MailError): client._receive()

    def test_real_tls_protocol_uses_pinned_ip_certificate_readonly_and_peek(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'mail.example.com')])
            cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
                    .not_valid_before(datetime.now(timezone.utc)-timedelta(minutes=1)).not_valid_after(datetime.now(timezone.utc)+timedelta(days=1))
                    .add_extension(x509.SubjectAlternativeName([x509.DNSName('mail.example.com')]), False).sign(key, hashes.SHA256()))
            cert_path = Path(tmp)/'cert.pem'; cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
            key_path = Path(tmp)/'key.pem'; key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); server_context.load_cert_chain(cert_path, key_path)
            trusted = ssl.create_default_context(cafile=str(cert_path))
            commands, failures = [], []
            listener = socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(); listener.settimeout(5)
            def serve():
                try:
                    raw, _ = listener.accept()
                    with server_context.wrap_socket(raw, server_side=True) as sock:
                        sock.settimeout(5); stream = sock.makefile('rb'); sock.sendall(b'* OK synthetic\r\n')
                        while line := stream.readline():
                            tag, command = line.split(b' ', 1); commands.append(command)
                            if command.startswith(b'CAPABILITY'): reply = b'* CAPABILITY IMAP4rev1\r\n'
                            elif command.startswith(b'EXAMINE'): reply = b'* 3 EXISTS\r\n* OK [UIDVALIDITY 7] stable\r\n'
                            elif command.startswith(b'UID FETCH'): reply = b'* 3 FETCH (UID 3 RFC822.SIZE '+str(len(RAW)).encode()+b' BODY[] {'+str(len(RAW)).encode()+b'}\r\n'+RAW+b')\r\n'
                            else: reply = b''
                            sock.sendall(reply + tag + b' OK done\r\n')
                except Exception as error: failures.append(type(error).__name__)
                finally: listener.close()
            worker = threading.Thread(target=serve); worker.start()
            original = socket.create_connection
            def pinned(address, **kwargs):
                self.assertEqual(address, ('8.8.8.8',993))
                return original(listener.getsockname(), **kwargs)
            with patch('desktop.agent.mail.socket.create_connection', side_effect=pinned), patch('desktop.agent.mail.ssl.create_default_context', return_value=trusted):
                result = Mail(resolve).read_message(settings(CONFIG), '7:3')
            worker.join(6)
            self.assertFalse(worker.is_alive()); self.assertEqual(failures, [])
            self.assertEqual(result['body'], 'Synthetic body')
            self.assertTrue(any(command.startswith(b'EXAMINE') for command in commands))
            self.assertTrue(any(b'BODY.PEEK[]' in command for command in commands))


class MailAccountTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory(); key = Fernet.generate_key()
        self.vault = Vault(Path(self.tmp.name), lambda:key)
        self.mail = Mail(resolve, FakeIMAP)
        self.accounts = Accounts(self.vault, mail=self.mail)
    async def asyncTearDown(self):
        await self.accounts.close(); self.tmp.cleanup()
    async def test_verified_login_encrypted_reconnect_read_remove_and_no_calendar(self):
        account = await self.accounts.connect_mail(CONFIG)
        self.assertNotIn('synthetic-password', json.dumps(account))
        self.assertNotIn(b'synthetic-password', self.vault.path.read_bytes())
        self.assertEqual((await self.accounts.connect_mail(dict(CONFIG, password='replacement')))['id'], account['id'])
        self.assertTrue((await self.accounts.read_messages(account['id']))['untrusted'])
        self.assertEqual((await self.accounts.read_message(account['id'],'7:3'))['message']['body'], 'Synthetic body')
        with self.assertRaises(AccountError): await self.accounts.token(account['id'], 'mail_send')
        self.vault.remove(account['id'])
        with self.assertRaises(ValueError): await self.accounts.read_messages(account['id'])
    async def test_authentication_failure_is_sanitized_and_not_saved(self):
        def fail(*args): raise OSError('synthetic-password')
        self.mail.verify = fail
        with self.assertRaises(AccountError) as error: await self.accounts.connect_mail(CONFIG)
        self.assertNotIn('synthetic-password', str(error.exception)); self.assertEqual(self.vault.accounts(), [])
    async def test_cancelled_reads_keep_resource_slot_until_host_work_finishes(self):
        release = threading.Event()
        def wait(): release.wait(3)
        first = asyncio.create_task(self.accounts.mail_call(wait)); second = asyncio.create_task(self.accounts.mail_call(wait))
        await asyncio.sleep(.02); first.cancel(); second.cancel()
        await asyncio.gather(first, second, return_exceptions=True)
        try:
            with self.assertRaises(AccountError): await self.accounts.mail_call(wait)
        finally: release.set()
        await asyncio.sleep(.03)
        self.assertFalse(self.accounts.mail_tasks)
    async def test_all_account_validation_is_private_and_authentication_precedes_work(self):
        app = FastAPI()
        def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer test': raise HTTPException(401)
        install(app, [Depends(auth)], Path(self.tmp.name)/'api')
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            for path, body in (('/accounts/imap',dict(CONFIG,password={'secret':'synthetic-password'})),
                               ('/accounts/config/google',{'client_id':{'secret':'synthetic-password'}}),
                               ('/accounts/oauth/complete',{'code':{'secret':'synthetic-password'}}),
                               ('/accounts/imap',dict(CONFIG,smtp_port=True)),
                               ('/accounts/imap',dict(CONFIG,unsupported_secret='synthetic-password'))):
                response = await client.post('/workspace/agent'+path,json=body,headers={'Authorization':'Bearer test'})
                self.assertEqual(response.status_code, 422); self.assertNotIn('synthetic-password', response.text)
            response = await client.post('/workspace/agent/accounts/imap',json=CONFIG)
            self.assertEqual(response.status_code,401)
