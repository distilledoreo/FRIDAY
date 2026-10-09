import json
from pathlib import Path
import tempfile
import unittest
from cryptography.fernet import Fernet
from desktop.agent.vault import Vault, LockedVault
from desktop.agent.review import OutgoingReview
from desktop.agent.approvals import Approvals


class VaultReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.key = Fernet.generate_key()
        self.vault = Vault(Path(self.temp.name) / 'vault', lambda: self.key)

    def tearDown(self): self.temp.cleanup()

    def test_secret_is_encrypted_bound_to_account_and_not_in_metadata(self):
        account = self.vault.add('imap', 'Test mailbox', {'password': 'synthetic-password-123'})
        self.assertEqual(set(account), {'id','provider','label'})
        self.assertNotIn('synthetic-password', self.vault.path.read_bytes().decode(errors='ignore'))
        self.assertNotIn('password', json.dumps(self.vault.accounts()))
        self.assertEqual(self.vault.credentials(account['id'])['password'], 'synthetic-password-123')
        other = self.vault.add('google', 'Another mailbox', {'access_token': 'synthetic-token'})
        with self.vault.db() as db:
            cipher = db.execute('SELECT encrypted FROM accounts WHERE id=?', (account['id'],)).fetchone()[0]
            db.execute('UPDATE accounts SET encrypted=? WHERE id=?', (cipher, other['id']))
        with self.assertRaises(LockedVault): self.vault.credentials(other['id'])

    def test_locked_or_wrong_key_fails_without_plaintext_fallback(self):
        account = self.vault.add('microsoft', 'Test', {'refresh_token': 'synthetic-token'})
        other = Vault(self.vault.root, lambda: Fernet.generate_key())
        with self.assertRaises(LockedVault): other.credentials(account['id'])
        def locked(): raise LockedVault('Locked')
        blocked = Vault(self.vault.root, locked)
        with self.assertRaises(LockedVault): blocked.add('imap', 'Blocked', {'password': 'secret'})
        self.assertEqual(len(self.vault.accounts()), 1)

    def test_replace_and_remove_do_not_leave_usable_handles(self):
        account = self.vault.add('imap', 'Test', {'password': 'old'})
        self.vault.replace_credentials(account['id'], {'password': 'new'})
        self.assertEqual(self.vault.credentials(account['id'])['password'], 'new')
        self.vault.remove(account['id'])
        with self.assertRaises(ValueError): self.vault.credentials(account['id'])
        self.assertEqual(self.vault.accounts(), [])

    def test_outgoing_review_binds_recipient_account_and_exact_payload(self):
        account = self.vault.add('imap', 'Test', {'password': 'synthetic-password'})
        review = OutgoingReview(self.vault)
        action = {'kind':'send','destination':'test@example.com','payload':{'account_id':account['id'],'to':'test@example.com','subject':'Draft','body':'Hello'}}
        accepted = review.inspect(action)
        self.assertTrue(accepted['allowed'])
        self.assertFalse(accepted['executable'])
        self.assertNotIn('synthetic-password', json.dumps(accepted))
        for change in [dict(to='other@example.com'), dict(subject='Hello\r\nBcc: other@example.com'), dict(body='Bearer synthetic-secret'), dict(account_id=[]), dict(password='secret')]:
            with self.subTest(change=change):
                rejected = review.inspect(action | {'payload': action['payload'] | change})
                self.assertFalse(rejected['allowed'])
                self.assertNotEqual(rejected['fingerprint'], accepted['fingerprint'])
        self.assertFalse(review.inspect(action | {'kind':'buy'})['allowed'])

    def test_large_structured_audit_round_trips_unicode_and_escaping(self):
        store = Approvals(Path(self.temp.name) / 'agent.sqlite')
        task = store.propose_task('Audit', ['Read'])
        original = {'text': ('雪"\\\n' * 20000), 'engine': 'Synthetic'}
        with store.db() as db: store.event(db, task['id'], 'result', original)
        events = store.events(task['id'], limit=200)
        fragments = [e['data'] for e in events if e['kind']=='event_fragment']
        self.assertGreater(len(fragments), 1)
        restored = json.loads(''.join(e['text'] for e in fragments))
        self.assertEqual(restored, original)
        self.assertEqual(store.result(task['id']), original)
        self.assertTrue(all(len(json.dumps(e['data']).encode()) < 65536 for e in events))
