"""Durable task and action approval boundary. Model output cannot grant approval.

Only authenticated, explicit user routes should call approve_* methods. This
module intentionally has no model callback and no implicit execution path.
"""
import contextlib
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import uuid
import base64
import shutil
import struct
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import math

ACTION_KINDS = frozenset({'submit', 'send', 'login', 'buy', 'delete'})


def encode(value):
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    if len(text.encode()) > 65536:
        raise ValueError('Proposal exceeds 64 KB')
    return text


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


class Approvals:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.screenshots = self.path.parent / 'screenshots'
        self.screenshots.mkdir(exist_ok=True, mode=0o700)
        with self.db() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY, proposal TEXT NOT NULL, fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS actions (
                    id TEXT PRIMARY KEY, task_id TEXT NOT NULL, payload TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, status TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL,
                    kind TEXT NOT NULL, data TEXT NOT NULL, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS schedules (
                    task_id TEXT PRIMARY KEY, next_run REAL NOT NULL,
                    interval_seconds INTEGER NOT NULL, remaining INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS schedule_runs (
                    task_id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS screenshots (
                    id TEXT PRIMARY KEY, task_id TEXT NOT NULL, url TEXT NOT NULL,
                    size INTEGER NOT NULL, created REAL NOT NULL);
            ''')
        self.path.chmod(0o600)

    def save_screenshot(self, task_id, url, encoded):
        if not isinstance(encoded, str) or len(encoded) > 1400000: raise ValueError('Screenshot too large')
        if not isinstance(url, str) or not url.startswith('https://') or len(url) > 4096: raise ValueError('Invalid source URL')
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) < 24 or len(raw) > 1024 * 1024 or raw[:8] != b'\x89PNG\r\n\x1a\n' or raw[12:16] != b'IHDR':
            raise ValueError('Invalid PNG screenshot')
        width, height = struct.unpack('!II', raw[16:24])
        if not (1 <= width <= 2000 and 1 <= height <= 2000): raise ValueError('Screenshot dimensions exceed limits')
        if shutil.disk_usage(self.screenshots).free < 2 * 1024**3: raise ValueError('Screenshot storage low')
        identifier = uuid.uuid4().hex
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            task = db.execute('SELECT status FROM tasks WHERE id=?', (task_id,)).fetchone()
            if not task or task['status'] != 'running': raise ValueError('Task is not running')
            total = db.execute('SELECT COALESCE(SUM(size),0) FROM screenshots').fetchone()[0]
            count = db.execute('SELECT COUNT(*) FROM screenshots WHERE task_id=?', (task_id,)).fetchone()[0]
            if total + len(raw) > 256 * 1024**2 or count >= 200: raise ValueError('Screenshot storage quota reached')
            target = self.screenshots / (identifier + '.png')
            target.write_bytes(raw)
            target.chmod(0o600)
            try:
                db.execute('INSERT INTO screenshots VALUES(?,?,?,?,?)', (identifier, task_id, url, len(raw), time.time()))
                self.event(db, task_id, 'screenshot', {'id': identifier, 'url': url, 'preview': 'Static source preview; scripts and external assets disabled'})
            except BaseException:
                target.unlink(missing_ok=True)
                raise
        return {'id': identifier, 'url': url}

    def screenshot(self, task_id, identifier):
        if not re.fullmatch(r'[a-f0-9]{32}', identifier): raise ValueError('Invalid screenshot id')
        with self.db() as db:
            row = db.execute('SELECT * FROM screenshots WHERE task_id=? AND id=?', (task_id, identifier)).fetchone()
        target = self.screenshots / (identifier + '.png')
        if not row or not target.is_file(): raise ValueError('Screenshot not found')
        return target

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    def event(self, db, task_id, kind, data):
        serialized = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
        if len(serialized.encode()) > 2 * 1024 * 1024: raise ValueError('Event exceeds task audit limit')
        if len(serialized.encode()) > 60000:
            group = uuid.uuid4().hex
            pieces = [serialized[i:i + 6000] for i in range(0, len(serialized), 6000)]
            for index, piece in enumerate(pieces):
                db.execute('INSERT INTO events(task_id,kind,data,created) VALUES(?,?,?,?)',
                    (task_id, 'event_fragment', encode({'group': group, 'kind': kind, 'index': index, 'count': len(pieces), 'text': piece}), time.time()))
            data = {'fragmented': True, 'group': group, 'count': len(pieces)}
        db.execute('INSERT INTO events(task_id,kind,data,created) VALUES(?,?,?,?)',
                   (task_id, kind, encode(data), time.time()))

    def propose_task(self, prompt, plan, schedule=None, data_scopes=None):
        if not isinstance(prompt, str) or not prompt.strip() or not isinstance(plan, list) or not plan:
            raise ValueError('Task requires a prompt and a nonempty plan')
        body = {'prompt': prompt, 'plan': plan}
        if data_scopes:
            from .account_scopes import bound
            body['data_scopes'] = bound(data_scopes)
        if schedule is not None:
            if not isinstance(schedule, dict): raise ValueError('Invalid schedule')
            when = schedule.get('run_at')
            interval = schedule.get('interval_seconds', 0)
            count = schedule.get('max_runs', 1)
            zone = schedule.get('timezone', 'UTC')
            if isinstance(when, bool) or not isinstance(when, (int, float)) or not math.isfinite(when) or not time.time() <= when <= time.time() + 366 * 86400:
                raise ValueError('First run must be in the future, within one year')
            if type(interval) is not int or interval != 0 and not 900 <= interval <= 366 * 86400:
                raise ValueError('Repeat interval must be zero or at least 15 minutes')
            if type(count) is not int or not 1 <= count <= 100 or interval == 0 and count != 1:
                raise ValueError('Use 1–100 runs; one-time schedules have one run')
            try: ZoneInfo(zone)
            except (ZoneInfoNotFoundError, TypeError, ValueError): raise ValueError('Invalid time zone')
            body['schedule'] = {'run_at': when, 'interval_seconds': interval, 'max_runs': count, 'timezone': zone}
        proposal = encode(body)
        task_id = uuid.uuid4().hex
        fingerprint = digest(proposal)
        with self.db() as db:
            db.execute('INSERT INTO tasks VALUES(?,?,?,?,?,?)',
                       (task_id, proposal, fingerprint, 'proposed', time.time(), time.time()))
            self.event(db, task_id, 'task_proposed', {'fingerprint': fingerprint})
        return {'id': task_id, 'proposal': json.loads(proposal), 'fingerprint': fingerprint, 'status': 'proposed'}

    def transition(self, task_id, fingerprint, old, new):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            changed = db.execute('UPDATE tasks SET status=?, updated=? WHERE id=? AND fingerprint=? AND status=?',
                                 (new, time.time(), task_id, fingerprint, old)).rowcount
            if changed != 1: raise ValueError('Task state or proposal has changed')
            self.event(db, task_id, 'task_' + new, {'fingerprint': fingerprint})

    def approve_task(self, task_id, fingerprint):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT proposal FROM tasks WHERE id=? AND fingerprint=? AND status='proposed'", (task_id, fingerprint)).fetchone()
            if not row: raise ValueError('Task state or proposal has changed')
            schedule = json.loads(row['proposal']).get('schedule')
            status = 'scheduled' if schedule else 'approved'
            db.execute('UPDATE tasks SET status=?, updated=? WHERE id=?', (status, time.time(), task_id))
            if schedule:
                db.execute('INSERT INTO schedules VALUES(?,?,?,?)', (task_id, schedule['run_at'], schedule['interval_seconds'], schedule['max_runs']))
            self.event(db, task_id, 'task_' + status, {'fingerprint': fingerprint})

    def start_task(self, task_id, fingerprint):
        self.transition(task_id, fingerprint, 'approved', 'running')

    def finish_task(self, task_id, fingerprint, success=True):
        self.transition(task_id, fingerprint, 'running', 'done' if success else 'failed')

    def await_setup(self, task_id, fingerprint):
        self.transition(task_id, fingerprint, 'running', 'awaiting_setup')

    def cancel_task(self, task_id):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            changed = db.execute("UPDATE tasks SET status='cancelled', updated=? WHERE id=? AND status IN ('proposed','approved','running','awaiting_setup','scheduled')",
                                 (time.time(), task_id)).rowcount
            if changed != 1: raise ValueError('Task cannot be cancelled')
            db.execute("UPDATE actions SET status='cancelled' WHERE task_id=? AND status IN ('proposed','approved')", (task_id,))
            db.execute('DELETE FROM schedules WHERE task_id=?', (task_id,))
            children = db.execute("SELECT t.id FROM tasks t JOIN schedule_runs r ON r.task_id=t.id WHERE r.schedule_id=? AND t.status IN ('approved','running','awaiting_setup')", (task_id,)).fetchall()
            for child in children:
                db.execute("UPDATE tasks SET status='cancelled', updated=? WHERE id=?", (time.time(), child['id']))
                db.execute("UPDATE actions SET status='cancelled' WHERE task_id=? AND status IN ('proposed','approved')", (child['id'],))
                self.event(db, child['id'], 'task_cancelled', {'schedule_id': task_id})
            self.event(db, task_id, 'task_cancelled', {})
        return [row['id'] for row in children]

    def claim_due(self, now=None):
        now = time.time() if now is None else now
        claimed = []
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            completed = db.execute("SELECT task_id FROM schedules s WHERE remaining=0 AND NOT EXISTS (SELECT 1 FROM schedule_runs r JOIN tasks t ON t.id=r.task_id WHERE r.schedule_id=s.task_id AND t.status IN ('approved','running','awaiting_setup','interrupted'))").fetchall()
            for row in completed:
                db.execute('DELETE FROM schedules WHERE task_id=?', (row['task_id'],))
                db.execute("UPDATE tasks SET status='done', updated=? WHERE id=?", (now, row['task_id']))
                self.event(db, row['task_id'], 'schedule_completed', {})
            rows = db.execute("SELECT s.*, t.proposal, t.fingerprint FROM schedules s JOIN tasks t ON t.id=s.task_id WHERE t.status='scheduled' AND s.remaining>0 AND s.next_run<=? ORDER BY s.next_run LIMIT 10", (now,)).fetchall()
            for row in rows:
                parent = row['task_id']
                # Unresolved actions and uncertain interrupted runs pause future
                # work until the user cancels/reviews the schedule.
                active = db.execute("SELECT COUNT(*) FROM schedule_runs r JOIN tasks t ON t.id=r.task_id WHERE r.schedule_id=? AND t.status IN ('approved','running','awaiting_setup','interrupted')", (parent,)).fetchone()[0]
                if active: continue
                identifier = uuid.uuid4().hex
                body = json.loads(row['proposal'])
                body.pop('schedule', None)
                body['schedule_origin'] = {'id': parent, 'approved_fingerprint': row['fingerprint'], 'due_at': row['next_run']}
                proposal = encode(body)
                fingerprint = digest(proposal)
                db.execute('INSERT INTO tasks VALUES(?,?,?,?,?,?)', (identifier, proposal, fingerprint, 'approved', now, now))
                db.execute('INSERT INTO schedule_runs VALUES(?,?)', (identifier, parent))
                self.event(db, identifier, 'schedule_authorized', body['schedule_origin'])
                remaining = row['remaining'] - 1
                if remaining <= 0:
                    db.execute('UPDATE schedules SET remaining=0 WHERE task_id=?', (parent,))
                else:
                    # Fixed elapsed intervals, no catch-up burst or DST ambiguity.
                    skipped = max(0, int((now - row['next_run']) // row['interval_seconds']))
                    next_run = row['next_run'] + (skipped + 1) * row['interval_seconds']
                    db.execute('UPDATE schedules SET next_run=?, remaining=? WHERE task_id=?', (next_run, remaining, parent))
                    db.execute('UPDATE tasks SET updated=? WHERE id=?', (now, parent))
                self.event(db, parent, 'schedule_run', {'task_id': identifier, 'due_at': row['next_run'], 'remaining': remaining})
                claimed.append({'id': identifier, 'fingerprint': fingerprint})
        return claimed

    def propose_action(self, task_id, kind, destination, payload):
        if kind not in ACTION_KINDS or not isinstance(destination, str) or not destination.strip():
            raise ValueError('Action requires a supported kind and concrete destination')
        body = encode({'kind': kind, 'destination': destination, 'payload': payload})
        action_id, fingerprint = uuid.uuid4().hex, digest(body)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            task = db.execute('SELECT status FROM tasks WHERE id=?', (task_id,)).fetchone()
            if not task or task['status'] != 'running': raise ValueError('Task is not running')
            db.execute('INSERT INTO actions VALUES(?,?,?,?,?)', (action_id, task_id, body, fingerprint, 'proposed'))
            self.event(db, task_id, 'action_proposed', {'id': action_id, 'fingerprint': fingerprint})
        return {'id': action_id, 'action': json.loads(body), 'fingerprint': fingerprint, 'status': 'proposed'}

    def approve_action(self, action_id, fingerprint):
        self._action_transition(action_id, fingerprint, 'proposed', 'approved')

    def claim_action(self, action_id, exact_action):
        # Claim before side effects. A failed/uncertain external operation is never
        # blindly retried with the same approval. A new proposal is required.
        self._action_transition(action_id, digest(encode(exact_action)), 'approved', 'claimed')

    def _action_transition(self, action_id, fingerprint, old, new):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            action = db.execute('SELECT * FROM actions WHERE id=?', (action_id,)).fetchone()
            if not action: raise ValueError('Unknown action')
            task = db.execute('SELECT status FROM tasks WHERE id=?', (action['task_id'],)).fetchone()
            if task['status'] != 'running': raise ValueError('Task is not running')
            changed = db.execute('UPDATE actions SET status=? WHERE id=? AND fingerprint=? AND status=?',
                                 (new, action_id, fingerprint, old)).rowcount
            if changed != 1: raise ValueError('Action state or payload has changed')
            self.event(db, action['task_id'], 'action_' + new, {'id': action_id, 'fingerprint': fingerprint})

    def runs(self, schedule_id):
        with self.db() as db:
            return [dict(row) for row in db.execute("SELECT t.id,t.status,t.created FROM tasks t JOIN schedule_runs r ON r.task_id=t.id WHERE r.schedule_id=? ORDER BY t.created DESC LIMIT 100", (schedule_id,))]

    def result(self, task_id):
        self.task(task_id)
        with self.db() as db:
            row = db.execute("SELECT data FROM events WHERE task_id=? AND kind='result' ORDER BY seq DESC LIMIT 1", (task_id,)).fetchone()
        if not row: return None
        result = json.loads(row['data'])
        if result.get('fragmented'):
            with self.db() as db:
                pieces = [json.loads(item['data']) for item in db.execute("SELECT data FROM events WHERE task_id=? AND kind='event_fragment' AND json_extract(data,'$.group')=? ORDER BY seq", (task_id, result['group']))]
            if len(pieces) != result['count']: raise ValueError('Incomplete report audit')
            result = json.loads(''.join(piece['text'] for piece in pieces))
        return result

    def tasks(self, limit=50):
        if not 1 <= limit <= 100: raise ValueError('Limit must be 1–100')
        with self.db() as db:
            return [dict(r, proposal=json.loads(r['proposal'])) for r in db.execute(
                'SELECT * FROM tasks ORDER BY updated DESC LIMIT ?', (limit,))]

    def task(self, task_id):
        with self.db() as db:
            row = db.execute('SELECT * FROM tasks WHERE id=?', (task_id,)).fetchone()
            if row is None: raise ValueError('Unknown task')
            result = dict(row, proposal=json.loads(row['proposal']))
            schedule = db.execute('SELECT next_run, remaining FROM schedules WHERE task_id=?', (task_id,)).fetchone()
            if schedule:
                result['schedule_state'] = dict(schedule)
                result['schedule_state']['blocked'] = bool(db.execute("SELECT COUNT(*) FROM schedule_runs r JOIN tasks t ON t.id=r.task_id WHERE r.schedule_id=? AND t.status IN ('awaiting_setup','interrupted')", (task_id,)).fetchone()[0])
            return result

    def actions(self, task_id):
        with self.db() as db:
            return [dict(r, payload=json.loads(r['payload'])) for r in db.execute(
                'SELECT * FROM actions WHERE task_id=? ORDER BY rowid', (task_id,))]

    def interrupt_running(self):
        # Restart cannot tell whether an external operation happened. Retain all
        # claims and approvals for inspection; never automatically replay them.
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            rows = db.execute("SELECT id FROM tasks WHERE status IN ('approved','running')").fetchall()
            for row in rows:
                db.execute("UPDATE tasks SET status='interrupted', updated=? WHERE id=?", (time.time(), row['id']))
                db.execute("UPDATE actions SET status='cancelled' WHERE task_id=? AND status IN ('proposed','approved')", (row['id'],))
                self.event(db, row['id'], 'task_interrupted', {'reason': 'Service restarted; review before resuming'})

    def events(self, task_id, after=0, limit=100):
        if after < 0 or not 1 <= limit <= 200: raise ValueError('Invalid event page')
        with self.db() as db:
            return [dict(r, data=json.loads(r['data'])) for r in db.execute(
                'SELECT * FROM events WHERE task_id=? AND seq>? ORDER BY seq LIMIT ?', (task_id, after, limit))]
