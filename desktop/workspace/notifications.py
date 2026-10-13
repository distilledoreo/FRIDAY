"""Opt-in Web Push. Subscriptions hold no gateway tokens; pushes contain generic notices."""
import asyncio
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
from urllib.parse import urlsplit

from fastapi import HTTPException
from pydantic import BaseModel, Field, field_validator


class Subscription(BaseModel):
    endpoint: str = Field(max_length=4000)
    keys: dict[str, str]

    @field_validator('endpoint')
    @classmethod
    def endpoint_url(cls, value):
        u = urlsplit(value)
        host = u.hostname or ''
        allowed = any(host == d or host.endswith('.' + d) for d in (
            'fcm.googleapis.com', 'push.services.mozilla.com', 'notify.windows.com', 'web.push.apple.com'))
        if u.scheme != 'https' or not allowed or u.username or u.password or u.port not in (None, 443) or u.fragment:
            raise ValueError('Unsupported browser push service')
        return value

    @field_validator('keys')
    @classmethod
    def subscription_keys(cls, value):
        try:
            public = base64.urlsafe_b64decode(value['p256dh'] + '===')
            secret = base64.urlsafe_b64decode(value['auth'] + '===')
            from cryptography.hazmat.primitives.asymmetric import ec
            ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public)
            assert len(secret) == 16
        except Exception:
            raise ValueError('Invalid browser push keys')
        return {k: value[k] for k in ('p256dh', 'auth')}


class PushNotifications:
    def __init__(self, root, request, sender=None):
        self.root = Path(root); self.root.mkdir(parents=True, exist_ok=True)
        self.request = request
        self.sender = sender or self.send
        self.database = self.root / 'subscriptions.sqlite'
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS subscriptions (id TEXT PRIMARY KEY, subscription TEXT, seen TEXT)')
        self.database.chmod(0o600)
        self.last_checkins = 0

    def db(self):
        db = sqlite3.connect(self.database); db.row_factory = sqlite3.Row
        return db

    def public_key(self):
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import serialization
        target = self.root / 'vapid.pem'
        if not target.exists():
            key = ec.generate_private_key(ec.SECP256R1())
            data = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as f: f.write(data)
        key = serialization.load_pem_private_key(target.read_bytes(), None)
        public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        return base64.urlsafe_b64encode(public).decode().rstrip('=')

    async def optional(self, path, method='GET', body=None):
        try: return await self.request(method, path, body)
        except asyncio.CancelledError: raise
        except Exception: return None

    async def notices(self, checkins=False):
        """Same event classes as NoticePlanner; generic text keeps account/task content off lock screens."""
        out = {}
        def add(key, signature, title, destination):
            out[key] = {'signature': str(signature), 'title': title, 'body': 'Open FRIDAY to review it.', 'open': destination, 'tag': key}
        for job in await self.optional('/workspace/jobs') or []:
            if job.get('status') == 'waiting' and job.get('pending'):
                add('approval-' + job['id'], job['pending'].get('call_id'), 'FRIDAY needs your approval', 'chat:' + job.get('item_id', ''))
            elif job.get('result') or job.get('status') == 'failed':
                add('job-' + job['id'], hashlib.sha256((str(job.get('result')) + str(job.get('error'))).encode()).hexdigest(), 'Your task finished' if job.get('status') != 'failed' else 'Your task needs attention', 'tasks')
        for task in await self.optional('/workspace/agent/tasks?limit=100') or []:
            if task.get('status') in ('done', 'failed', 'interrupted', 'awaiting_setup'):
                origin = task.get('proposal', {}).get('schedule_origin', {}).get('id')
                if origin and task.get('status') == 'done':
                    parent = await self.optional('/workspace/agent/tasks/' + origin) or {}
                    if parent.get('proposal', {}).get('schedule', {}).get('notify') == 'on_change':
                        report = await self.optional('/workspace/agent/tasks/' + task['id'] + '/report') or {}
                        text = report.get('result', {}).get('text', '')
                        if text: add('watch-' + origin, hashlib.sha256(text.encode()).hexdigest(), 'FRIDAY found a watched change', 'activity')
                        continue
                add('agent-' + task['id'], str(task.get('status')) + ':' + str(task.get('updated')), 'FRIDAY has a task update', 'activity')
        sessions = await self.optional('/workspace/pc/sessions') or []
        coding = {s['id'] for s in sessions if s.get('agent') == 'friday-code'}
        approvals = await self.optional('/workspace/pc/approvals') or {}
        for kind in ('permissions', 'questions'):
            for item in approvals.get(kind, []):
                # Remind every two hours while an approval remains unanswered.
                add('pc-' + kind + '-' + item['id'], int(time.time() // 7200), 'FRIDAY needs your permission' if kind == 'permissions' else 'FRIDAY has a question', 'code' if (item.get('owner') or item.get('session') or item.get('sessionID')) in coding else 'activity')
        for session in sessions:
            if session.get('state') == 'busy':
                add('session-' + session['id'], 'busy', '', 'activity')
            else:
                add('session-' + session['id'], session.get('state'), 'Your PC task has an update', 'code' if session.get('agent') == 'friday-code' else 'activity')
        if checkins:
            result = await self.optional('/workspace/agent/briefing/proposals/refresh', 'POST', {'scope': ''}) or {}
            if result.get('notify'):
                for item in result.get('items', []):
                    add('brief-' + item['id'], item['id'], 'Your morning brief is ready' if item.get('kind') == 'morning' else 'FRIDAY has a check-in', 'brief')
        return out

    async def subscribe(self, subscription):
        self.public_key()
        ident = hashlib.sha256(subscription['endpoint'].encode()).hexdigest()
        baseline = {k: n['signature'] for k, n in (await self.notices(True)).items()}
        with self.db() as db:
            count = db.execute('SELECT COUNT(*) FROM subscriptions').fetchone()[0]
            if count >= 32 and not db.execute('SELECT id FROM subscriptions WHERE id=?', (ident,)).fetchone():
                raise HTTPException(429, 'Too many browser subscriptions')
            # Resubscription is idempotent; keep deduplication state on reconnect.
            db.execute('INSERT INTO subscriptions (id, subscription, seen) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET subscription=excluded.subscription', (ident, json.dumps(subscription), json.dumps(baseline)))
        return ident

    def remove(self, ident):
        with self.db() as db: db.execute('DELETE FROM subscriptions WHERE id=?', (ident,))

    def send(self, subscription, notice):
        from pywebpush import webpush
        from py_vapid import Vapid02
        from cryptography.hazmat.primitives import serialization
        key = serialization.load_pem_private_key((self.root / 'vapid.pem').read_bytes(), None)
        # RFC 8292 allows HTTPS contact URIs with paths. py_vapid's subject-only regex rejects them.
        # The contact is a fixed, valid URI below; audience validation and ES256 signing still apply.
        vapid = Vapid02(private_key=key, conf={"no-strict": True})
        # A stable public project contact, not a personal email or private machine address.
        return webpush(subscription_info=subscription, data=json.dumps({k: v for k, v in notice.items() if k != 'signature'}),
                       vapid_private_key=vapid, vapid_claims={'sub': 'https://github.com/distilledoreo/FRIDAY'},
                       ttl=3600, timeout=15)

    async def poll(self):
        with self.db() as db: rows = list(db.execute('SELECT * FROM subscriptions'))
        if not rows: return
        checkins = time.time() - self.last_checkins >= 900
        if checkins: self.last_checkins = time.time()
        notices = await self.notices(checkins)
        for row in rows:
            seen = json.loads(row['seen']); sent = 0
            for key, notice in notices.items():
                signature = notice['signature']
                if not notice['title']:
                    seen[key] = signature
                    continue
                if seen.get(key) == signature: continue
                if key.startswith('session-') and seen.get(key) != 'busy':
                    seen[key] = signature
                    continue
                if sent >= 5: continue  # Keep undispatched notices pending for the next poll.
                try:
                    await asyncio.to_thread(self.sender, json.loads(row['subscription']), notice)
                except Exception as exc:
                    status = getattr(getattr(exc, 'response', None), 'status_code', None)
                    if status in (404, 410): self.remove(row['id']); break
                    continue  # Retry transient failures; never log subscription credentials.
                seen[key] = signature; sent += 1
            seen = dict(list(seen.items())[-1000:])
            with self.db() as db: db.execute('UPDATE subscriptions SET seen=? WHERE id=?', (json.dumps(seen), row['id']))


def enable(app, router, workspace):
    async def request(method, path, body):
        if workspace.loopback is None: raise RuntimeError('Services unavailable')
        return await workspace.loopback(method, path, body)
    push = PushNotifications(workspace.root / 'webpush', request)

    @router.get('/notifications/key')
    async def key(): return {'key': push.public_key()}

    @router.post('/notifications/subscriptions')
    async def subscribe(body: Subscription): return {'id': await push.subscribe(body.model_dump())}

    @router.delete('/notifications/subscriptions/{ident}')
    async def unsubscribe(ident: str): push.remove(ident); return {'deleted': True}

    task = None
    async def worker():
        while True:
            try: await push.poll()
            except asyncio.CancelledError: raise
            except Exception: pass  # Optional unavailable providers must not stop other subscriptions.
            await asyncio.sleep(60)

    @app.on_event('startup')
    async def start():
        nonlocal task
        task = asyncio.create_task(worker())

    @app.on_event('shutdown')
    async def stop():
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError): await task
    return push
