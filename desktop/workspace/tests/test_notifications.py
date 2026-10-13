import json
import tempfile
import unittest
from unittest.mock import Mock
from pydantic import ValidationError
from desktop.workspace.notifications import PushNotifications, Subscription
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
import base64


def subscription():
    public = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    encode = lambda data: base64.urlsafe_b64encode(data).decode().rstrip('=')
    return dict(endpoint='https://fcm.googleapis.com/fcm/send/synthetic', keys=dict(p256dh=encode(public), auth=encode(b'0' * 16)))


class NotificationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.jobs = []; self.sent = []
        async def request(method, path, body):
            if path == '/workspace/jobs': return self.jobs
            return [] if path.endswith('/sessions') or path.startswith('/workspace/agent/tasks') else {}
        self.request = request
        self.push = PushNotifications(self.temp.name, request, lambda sub, notice: self.sent.append((sub, notice)))
        self.subscription = Subscription(**subscription()).model_dump()
    async def asyncTearDown(self): self.temp.cleanup()
    async def test_baseline_no_backlog_delivery_deduplication_and_removal(self):
        self.jobs = [dict(id='old', status='completed', result='Private old result')]
        ident = await self.push.subscribe(self.subscription)
        await self.push.poll(); self.assertEqual(self.sent, [])
        self.jobs.append(dict(id='new', status='completed', result='Private new result'))
        await self.push.poll(); self.assertEqual(len(self.sent), 1)
        self.assertNotIn('Private', json.dumps(self.sent[0][1]))
        self.push = PushNotifications(self.temp.name, self.request, lambda sub, notice: self.sent.append((sub, notice)))
        await self.push.poll(); self.assertEqual(len(self.sent), 1)
        self.push.remove(ident)
        self.jobs.append(dict(id='next', status='completed', result='Done'))
        await self.push.poll(); self.assertEqual(len(self.sent), 1)
    async def test_transient_failures_retry_and_burst_does_not_drop_sixth_notice(self):
        await self.push.subscribe(self.subscription)
        self.jobs = [dict(id=str(i), status='completed', result='Done') for i in range(6)]
        self.push.sender = Mock(side_effect=RuntimeError('offline'))
        await self.push.poll()
        self.push.sender = lambda sub, notice: self.sent.append(notice)
        await self.push.poll(); self.assertEqual(len(self.sent), 5)
        await self.push.poll(); self.assertEqual(len(self.sent), 6)
        await self.push.poll(); self.assertEqual(len(self.sent), 6)
    async def test_watch_changes_and_coding_approvals_use_their_destinations(self):
        report = ['Same report']
        state = ['busy']
        permissions = []
        async def request(method, path, body):
            if path == '/workspace/agent/tasks?limit=100': return [{'id': 'run', 'status': 'done', 'proposal': {'schedule_origin': {'id': 'watch'}}}]
            if path == '/workspace/agent/tasks/watch': return {'proposal': {'schedule': {'notify': 'on_change'}}}
            if path.endswith('/report'): return {'result': {'text': report[0]}}
            if path == '/workspace/pc/sessions': return [{'id': 'code-session', 'agent': 'friday-code', 'state': state[0]}]
            if path == '/workspace/pc/approvals': return {'permissions': permissions}
            return [] if path == '/workspace/jobs' else {}
        self.push.request = request
        await self.push.subscribe(self.subscription)
        await self.push.poll(); self.assertEqual(self.sent, [])
        report[0] = 'Changed report'; state[0] = 'idle'; permissions.append({'id': 'p', 'owner': 'code-session'})
        await self.push.poll()
        notices = {notice['tag']: notice for _, notice in self.sent}
        self.assertEqual(notices['watch-watch']['open'], 'activity')
        self.assertEqual(notices['pc-permissions-p']['open'], 'code')
        self.assertEqual(notices['session-code-session']['open'], 'code')
        self.assertNotIn('Changed report', json.dumps(notices))
        await self.push.poll(); self.assertEqual(len(self.sent), 3)

    async def test_expired_subscription_is_removed(self):
        ident = await self.push.subscribe(self.subscription)
        class Gone(Exception): response = type('Response', (), {'status_code': 410})()
        self.push.sender = Mock(side_effect=Gone())
        self.jobs = [dict(id='new', status='failed')]
        await self.push.poll()
        with self.push.db() as db: self.assertEqual(db.execute('SELECT COUNT(*) FROM subscriptions').fetchone()[0], 0)
    def test_subscription_rejects_arbitrary_urls_and_invalid_keys(self):
        for endpoint in ('http://fcm.googleapis.com/x', 'https://localhost/x', 'https://fcm.googleapis.com.evil.test/x', 'https://fcm.googleapis.com:444/x', 'https://user@fcm.googleapis.com/x'):
            with self.assertRaises(ValidationError): Subscription(**dict(subscription(), endpoint=endpoint))
        with self.assertRaises(ValidationError): Subscription(endpoint=subscription()['endpoint'], keys={'auth':'bad','p256dh':'bad'})
    def test_vapid_key_survives_restart_and_is_private(self):
        first = self.push.public_key()
        self.assertEqual(first, PushNotifications(self.temp.name, self.request).public_key())
        from pathlib import Path
        self.assertEqual((Path(self.temp.name)/'vapid.pem').stat().st_mode & 0o777, 0o600)
