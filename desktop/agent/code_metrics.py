"""Count-only coding telemetry. No prompts, code, tool arguments or outputs on disk."""
import contextlib
import json
import math
import sqlite3
import time
from pathlib import Path


def count(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)): return None
    try:
        return int(value) if math.isfinite(value) and 0 <= value <= 2**63 - 1 else None
    except OverflowError: return None


class UsageObserver:
    """Observe bounded SSE lines without changing, retaining or delaying the forwarded bytes."""
    def __init__(self, streaming):
        self.streaming = streaming
        self.pending = b''
        self.dropping = False
        self.usage = {}

    def accept(self, payload):
        try:
            data = json.loads(payload)
            usage = data.get('usage') or {}
            if usage:
                cached = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
                self.usage = {'prompt_tokens': count(usage.get('prompt_tokens')),
                              'cached_prompt_tokens': count(cached),
                              'completion_tokens': count(usage.get('completion_tokens'))}
        except (ValueError, TypeError, AttributeError):
            pass

    def feed(self, chunk):
        # Non-stream JSON is bounded by the same cap. Oversized lines are skipped,
        # not accumulated: ordinary content deltas never enter persistent storage.
        if not self.streaming:
            if not self.dropping:
                self.pending += chunk
                if len(self.pending) > 65536:
                    self.pending = b''
                    self.dropping = True
            return
        for piece in chunk.splitlines(keepends=True):
            if not self.dropping:
                self.pending += piece
                if len(self.pending) > 65536:
                    self.pending = b''
                    self.dropping = True
            if piece.endswith(b'\n'):
                if not self.dropping:
                    line = self.pending.strip()
                    if self.streaming and line.startswith(b'data:'): self.accept(line[5:].strip())
                self.pending = b''
                self.dropping = False

    def finish(self):
        if not self.streaming and not self.dropping: self.accept(self.pending)
        self.pending = b''
        return self.usage


class CodeMetrics:
    def __init__(self, root):
        self.path = Path(root) / 'code-metrics.sqlite3'
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with self.db() as db:
                db.execute('CREATE TABLE IF NOT EXISTS requests(id INTEGER PRIMARY KEY, session TEXT, started REAL, '
                           'wall_seconds REAL, status INTEGER, prompt_tokens INTEGER, cached_prompt_tokens INTEGER, '
                           'completion_tokens INTEGER, finished INTEGER)')
                db.execute('CREATE TABLE IF NOT EXISTS tasks(session TEXT PRIMARY KEY, counts TEXT NOT NULL)')
                db.execute('CREATE TABLE IF NOT EXISTS children(root TEXT, session TEXT, PRIMARY KEY(root,session))')
        except (sqlite3.Error, OSError):
            pass  # Optional telemetry must never prevent tasks from starting.

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15)
        try:
            with db: yield db
        finally:
            db.close()

    def request(self, session, started, seconds, status, usage, finished):
        # Telemetry must never cause an inference/tool failure.
        with contextlib.suppress(sqlite3.Error, OSError):
            with self.db() as db:
                db.execute('INSERT INTO requests(session,started,wall_seconds,status,prompt_tokens,cached_prompt_tokens,'
                           'completion_tokens,finished) VALUES(?,?,?,?,?,?,?,?)',
                           (session, started, seconds, status, usage.get('prompt_tokens'),
                            usage.get('cached_prompt_tokens'), usage.get('completion_tokens'), int(finished)))

    def snapshot(self, session, messages):
        """Idempotent session counts derived from OpenCode's already-recorded parts."""
        turns = compactions = lookups_then_reads = 0
        tools, retrieved = {}, set()
        stamps = []
        seen = set()
        for message in messages:
            info = message.get('info', {})
            if info.get('role') == 'assistant': turns += 1
            stamp = info.get('time') or {}
            stamps.extend(stamp[key] / 1000 for key in ('created', 'completed')
                          if isinstance(stamp.get(key), (int, float)) and not isinstance(stamp[key], bool))
            for part in message.get('parts', []):
                if part.get('type') == 'compaction': compactions += 1
                if part.get('type') != 'tool' or part.get('id') in seen: continue
                seen.add(part.get('id'))
                name = part.get('tool', 'unknown')
                tools[name] = tools.get(name, 0) + 1
                state = part.get('state') or {}
                if name == 'friday-code-lookup_search_code':
                    # Only transiently inspect snippet locations; persist the count alone.
                    import re
                    retrieved.update(re.findall(r'^([^\n:]+):\d+-\d+ score=', state.get('output') or '', re.M))
                if name == 'read':
                    path = str((state.get('input') or {}).get('filePath', ''))
                    if any(path == p or path.endswith('/' + p) for p in retrieved): lookups_then_reads += 1
        result = {'turns': turns, 'tools': tools, 'compactions': compactions,
                  'lookup_followed_by_read': lookups_then_reads,
                  'recorded_wall_seconds': max(stamps) - min(stamps) if stamps else None,
                  'requests': None, 'usage_available': False, 'cache_counts_available': False,
                  'prompt_tokens': None, 'cached_prompt_tokens': None, 'fresh_prompt_tokens': None,
                  'completion_tokens': None}
        with contextlib.suppress(sqlite3.Error, OSError):
            with self.db() as db:
                db.execute('INSERT OR REPLACE INTO tasks VALUES(?,?)', (session, json.dumps(result)))
                identities = {session} | {m.get('info', {}).get('sessionID') for m in messages if m.get('info', {}).get('sessionID')}
                db.executemany('INSERT OR IGNORE INTO children VALUES(?,?)', [(session, child) for child in identities])
                rows = db.execute('SELECT prompt_tokens,cached_prompt_tokens,completion_tokens FROM requests WHERE session IN '
                                  '(SELECT session FROM children WHERE root=?)', (session,)).fetchall()
                result['requests'] = len(rows)
                result['usage_available'] = bool(rows) and all(r[0] is not None and r[2] is not None for r in rows)
                result['cache_counts_available'] = bool(rows) and all(r[1] is not None for r in rows)
                result['prompt_tokens'] = sum(r[0] for r in rows) if result['usage_available'] else None
                result['completion_tokens'] = sum(r[2] for r in rows) if result['usage_available'] else None
                result['cached_prompt_tokens'] = sum(r[1] for r in rows) if result['cache_counts_available'] else None
                result['fresh_prompt_tokens'] = (max(0, result['prompt_tokens'] - result['cached_prompt_tokens'])
                    if result['usage_available'] and result['cache_counts_available'] else None)
        return result
