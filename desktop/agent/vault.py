"""Host-only encrypted credential store. Never expose decrypt through model IPC."""
import contextlib
import json
from pathlib import Path
import sqlite3
import uuid
import os
import fcntl
import re

from cryptography.fernet import Fernet, InvalidToken


class LockedVault(RuntimeError): pass


class DesktopKey:
    """Only Linux Secret Service; never fall back to a plaintext keyring."""
    def __init__(self, lock): self.lock = Path(lock)

    def __call__(self):
        descriptor = os.open(self.lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            return self._key()
        finally: os.close(descriptor)

    def _key(self):
        import secretstorage
        from keyring.backends.SecretService import Keyring
        try:
            connection = secretstorage.dbus_init()
            collection = secretstorage.get_default_collection(connection)
            if collection.is_locked(): raise LockedVault('Unlock your desktop keyring to use saved accounts')
            ring = Keyring()
            value = ring.get_password('FRIDAY credential vault', 'master-key-v1')
            if value is None:
                value = Fernet.generate_key().decode('ascii')
                ring.set_password('FRIDAY credential vault', 'master-key-v1', value)
            return value.encode('ascii')
        except LockedVault: raise
        except Exception: raise LockedVault('Desktop credential vault is unavailable') from None


class Vault:
    def __init__(self, root, key=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.root / 'accounts.sqlite'
        self.root.chmod(0o700)
        self.key = key or DesktopKey(self.root / "key.lock")
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS applications (provider TEXT PRIMARY KEY, encrypted BLOB NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, provider TEXT NOT NULL, label TEXT NOT NULL, encrypted BLOB NOT NULL)')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    def add(self, provider, label, credentials):
        if provider not in ('google', 'microsoft', 'imap'): raise ValueError('Unsupported account provider')
        if not isinstance(label, str) or not 1 <= len(label.strip()) <= 320 or any(ord(c)<32 for c in label): raise ValueError('Invalid account label')
        if not isinstance(credentials, dict) or not credentials: raise ValueError('Credentials required')
        identifier = uuid.uuid4().hex
        wrapper = json.dumps({'id': identifier, 'provider': provider, 'credentials': credentials}, allow_nan=False).encode()
        if len(wrapper) > 32768: raise ValueError('Credential record exceeds limit')
        encrypted = Fernet(self.key()).encrypt(wrapper)
        with self.db() as db:
            db.execute('INSERT INTO accounts VALUES(?,?,?,?)', (identifier, provider, label.strip(), encrypted))
        return self.metadata(identifier)

    def accounts(self):
        with self.db() as db:
            return [dict(row) for row in db.execute('SELECT id,provider,label FROM accounts ORDER BY rowid')]

    def find_account(self, provider, label):
        with self.db() as db:
            row=db.execute('SELECT id,provider,label FROM accounts WHERE provider=? AND lower(label)=lower(?) ORDER BY rowid LIMIT 1',(provider,label)).fetchone()
        return dict(row) if row else None

    def metadata(self, identifier):
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-f0-9]{32}", identifier): raise ValueError("Invalid account handle")
        with self.db() as db:
            row = db.execute('SELECT id,provider,label FROM accounts WHERE id=?', (identifier,)).fetchone()
        if row is None: raise ValueError('Unknown account')
        return dict(row)

    def credentials(self, identifier):
        """Trusted provider adapters only; no HTTP/model route calls this method."""
        with self.db() as db:
            row = db.execute('SELECT * FROM accounts WHERE id=?', (identifier,)).fetchone()
        if row is None: raise ValueError('Unknown account')
        try:
            wrapper = json.loads(Fernet(self.key()).decrypt(row['encrypted']))
            if wrapper['id'] != identifier or wrapper['provider'] != row['provider']: raise ValueError()
            return wrapper['credentials']
        except (InvalidToken, KeyError, ValueError, TypeError):
            raise LockedVault('Credential record could not be authenticated') from None

    def replace_credentials(self, identifier, credentials):
        provider = self.metadata(identifier)['provider']
        body = json.dumps({'id': identifier, 'provider': provider, 'credentials': credentials}, allow_nan=False).encode()
        if len(body) > 32768 or not isinstance(credentials, dict) or not credentials: raise ValueError('Invalid credentials')
        encrypted = Fernet(self.key()).encrypt(body)
        with self.db() as db:
            if db.execute('UPDATE accounts SET encrypted=? WHERE id=?', (encrypted, identifier)).rowcount != 1: raise ValueError('Unknown account')

    def remove(self, identifier):
        # Logical removal makes the handle unusable. Flash/backup remnants cannot
        # be promised securely erased; credential ciphertext stays protected.
        with self.db() as db:
            if db.execute('DELETE FROM accounts WHERE id=?', (identifier,)).rowcount != 1: raise ValueError('Unknown account')

    def set_application(self, provider, config):
        if provider not in ('google','microsoft'): raise ValueError('Unsupported application')
        raw = json.dumps({'provider':provider,'config':config},allow_nan=False).encode()
        if len(raw)>32768: raise ValueError('Application configuration too large')
        encrypted = Fernet(self.key()).encrypt(raw)
        with self.db() as db:
            db.execute('INSERT INTO applications VALUES(?,?) ON CONFLICT(provider) DO UPDATE SET encrypted=excluded.encrypted',(provider,encrypted))

    def has_application(self, provider):
        with self.db() as db: return db.execute('SELECT 1 FROM applications WHERE provider=?',(provider,)).fetchone() is not None

    def application(self, provider):
        with self.db() as db: row=db.execute('SELECT encrypted FROM applications WHERE provider=?',(provider,)).fetchone()
        if row is None: raise ValueError('Register and configure this OAuth application first')
        try:
            data=json.loads(Fernet(self.key()).decrypt(row['encrypted']))
            if data['provider']!=provider: raise ValueError()
            return data['config']
        except (InvalidToken, KeyError, ValueError, TypeError): raise LockedVault('Application configuration could not be authenticated') from None
