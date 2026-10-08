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
            ''')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    def event(self, db, task_id, kind, data):
        db.execute('INSERT INTO events(task_id,kind,data,created) VALUES(?,?,?,?)',
                   (task_id, kind, encode(data), time.time()))

    def propose_task(self, prompt, plan):
        if not isinstance(prompt, str) or not prompt.strip() or not isinstance(plan, list) or not plan:
            raise ValueError('Task requires a prompt and a nonempty plan')
        proposal = encode({'prompt': prompt, 'plan': plan})
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
        self.transition(task_id, fingerprint, 'proposed', 'approved')

    def start_task(self, task_id, fingerprint):
        self.transition(task_id, fingerprint, 'approved', 'running')

    def finish_task(self, task_id, fingerprint, success=True):
        self.transition(task_id, fingerprint, 'running', 'done' if success else 'failed')

    def cancel_task(self, task_id):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            changed = db.execute("UPDATE tasks SET status='cancelled', updated=? WHERE id=? AND status IN ('proposed','approved','running')",
                                 (time.time(), task_id)).rowcount
            if changed != 1: raise ValueError('Task cannot be cancelled')
            db.execute("UPDATE actions SET status='cancelled' WHERE task_id=? AND status IN ('proposed','approved')", (task_id,))
            self.event(db, task_id, 'task_cancelled', {})

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

    def tasks(self, limit=50):
        if not 1 <= limit <= 100: raise ValueError('Limit must be 1–100')
        with self.db() as db:
            return [dict(r, proposal=json.loads(r['proposal'])) for r in db.execute(
                'SELECT * FROM tasks ORDER BY updated DESC LIMIT ?', (limit,))]

    def task(self, task_id):
        with self.db() as db:
            row = db.execute('SELECT * FROM tasks WHERE id=?', (task_id,)).fetchone()
            if row is None: raise ValueError('Unknown task')
            return dict(row, proposal=json.loads(row['proposal']))

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
