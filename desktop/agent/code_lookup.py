"""Read-only semantic source lookup. Shared vectors, isolated worktree manifests."""
import concurrent.futures
import contextlib
import fnmatch
import hashlib
import json
import os
import sqlite3
import stat
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

import httpx
import numpy as np

from .pc import SECRET_FILES

SERVER = 'friday-code-lookup'
TOOL = SERVER + '_search_code'
PROMPT_VERSION = 'embeddinggemma2-code-v1'
MODEL = 'embeddinggemma-2-Q8_0'
DESCRIPTION = ('Index non-secret worktree files and find source code for conceptual questions in this coding task’s worktree. '
               'Returns focused source snippets, not an explanation. For exact names or paths use grep/read. '
               'Results supplement ordinary exploration; verify them before editing. Never searches secret files.')
MAX_FILE = 200_000
MAX_CORPUS = 64_000_000
MAX_CHUNKS = 50_000
FALLBACK = 'Code lookup is unavailable; use grep and read to continue.'
BUILDING = 'Code index is still building; use grep/read now or try search_code again shortly.'
SKIP = {'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'Cargo.lock', 'poetry.lock', 'uv.lock', 'gradle.lockfile'}


class EmbeddingService:
    def __init__(self, url=None, model=None):
        self.url = (url or os.environ.get('FRIDAY_CODE_LOOKUP_URL', '')).rstrip('/')
        parsed = urlparse(self.url)
        if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', '::1') or parsed.username or parsed.password:
            raise ValueError('Code embeddings require a loopback HTTP service')
        self.model = model or os.environ.get('FRIDAY_CODE_LOOKUP_MODEL', MODEL)

    def embed(self, texts):
        with httpx.Client(timeout=httpx.Timeout(10, connect=2), follow_redirects=False, trust_env=False) as client:
            response = client.post(self.url + '/v1/embeddings', json={'model': self.model, 'input': texts})
            response.raise_for_status()
            data = sorted(response.json()['data'], key=lambda x: x['index'])
            if len(data) != len(texts): raise ValueError('Embedding count mismatch')
            return normalize([row['embedding'] for row in data])


    def token_count(self, text):
        with httpx.Client(timeout=httpx.Timeout(5, connect=2), follow_redirects=False, trust_env=False) as client:
            response = client.post(self.url + '/tokenize', json={'content': text, 'add_special': False})
            response.raise_for_status()
            tokens = response.json()['tokens']
            if not isinstance(tokens, list): raise ValueError('Invalid tokenizer response')
            return len(tokens)


def normalize(vectors):
    array = np.asarray(vectors, dtype=np.float32)
    if array.ndim != 2 or array.shape[1] != 768 or not np.isfinite(array).all():
        raise ValueError('Expected finite 768-dimensional embeddings')
    norms = np.linalg.norm(array, axis=1, keepdims=True)
    if (norms < 1e-8).any(): raise ValueError('Empty embedding')
    return array / norms


def secret(path):
    value = str(path).replace(os.sep, '/')
    return any(fnmatch.fnmatchcase(v, p) for p in SECRET_FILES for v in (value, '/' + value))


def confined(root, path):
    value = root / path
    resolved = value.resolve()
    if not resolved.is_relative_to(root): raise ValueError('Path must stay inside this coding worktree')
    if any(p.is_symlink() for p in (value, *value.parents) if p.is_relative_to(root)):
        raise ValueError('Code lookup does not follow symbolic links')
    if secret(value.relative_to(root)) or secret(resolved): raise ValueError('Secret paths are excluded from code lookup')
    return resolved


def safe_bytes(root, path):
    """Read with no-follow directory FDs, including against concurrent link swaps."""
    parts = Path(path).parts
    if not parts or any(p in ('.', '..') for p in parts) or Path(path).is_absolute():
        raise ValueError('Expected a worktree-relative file')
    with contextlib.ExitStack() as stack:
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        stack.callback(os.close, directory)
        for component in parts[:-1]:
            directory = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            stack.callback(os.close, directory)
        descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        stack.callback(os.close, descriptor)
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE: raise ValueError('Source file too large or not regular')
        chunks, size = [], 0
        while True:
            piece = os.read(descriptor, min(65536, MAX_FILE + 1 - size))
            if not piece: break
            chunks.append(piece); size += len(piece)
            if size > MAX_FILE: raise ValueError('Source file too large')
        return b''.join(chunks)


def windows(path, text):
    lines = text.splitlines(keepends=True)
    result = []
    first = 0
    while first < len(lines):
        last = min(first + 60, len(lines))
        while last > first and len(''.join(lines[first:last]).encode()) > 6000: last -= 1
        if last <= first:
            first += 1
            continue
        code = ''.join(lines[first:last])
        sent = f'title: {path} | text: {code}'
        result.append({'start': first + 1, 'end': last, 'text': code, 'sent': sent})
        if last == len(lines): break
        first = max(first + 1, last - 15)
    return result


class LookupStore:
    def __init__(self, data, embedder=None):
        self.data = Path(data)
        self.data.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.data / 'index.sqlite3'
        self.embedder = embedder
        self.model = getattr(embedder, 'model', MODEL)
        with self.db() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS roots(root TEXT PRIMARY KEY, task TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS prebuild(root TEXT PRIMARY KEY, allowed INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS vectors(hash TEXT PRIMARY KEY, vector BLOB NOT NULL);
              CREATE TABLE IF NOT EXISTS files(root TEXT, path TEXT, digest TEXT, version TEXT, PRIMARY KEY(root,path));
              CREATE TABLE IF NOT EXISTS chunks(root TEXT, path TEXT, start INTEGER, end INTEGER, hash TEXT,
                PRIMARY KEY(root,path,start));
            ''')

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            with db: yield db
        finally:
            db.close()

    @contextlib.contextmanager
    def locked(self):
        import fcntl
        with (self.data / 'index.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield

    def register(self, root, task, prebuild=True):
        root = str(Path(root).resolve())
        if self.data.resolve().is_relative_to(Path(root)):
            raise ValueError('Code lookup data must stay outside the worktree')
        with self.db() as db:
            db.execute('INSERT OR REPLACE INTO roots VALUES(?,?)', (root, task))
            db.execute('INSERT OR REPLACE INTO prebuild VALUES(?,?)', (root, int(prebuild)))

    def allowed(self, root):
        with self.db() as db: return bool(db.execute('SELECT 1 FROM roots WHERE root=?', (str(root),)).fetchone())

    def prebuild_allowed(self, root):
        with self.db() as db:
            row = db.execute('SELECT allowed FROM prebuild WHERE root=?', (str(root),)).fetchone()
        return bool(row and row[0])

    def discard(self, root):
        root = str(Path(root).absolute())
        with self.db() as db:
            for table in ('roots', 'prebuild', 'files', 'chunks'): db.execute(f'DELETE FROM {table} WHERE root=?', (root,))
        # Builders notice the removed registration after their current request.
        with self.locked(), self.db() as db:
            db.execute('DELETE FROM vectors WHERE hash NOT IN (SELECT hash FROM chunks)')

    def discard_task(self, task):
        with self.db() as db:
            roots = [r[0] for r in db.execute('SELECT root FROM roots WHERE task=?', (task,))]
        for root in roots: self.discard(root)

    def candidates(self, root):
        result = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard'],
                                check=True, capture_output=True, timeout=15)
        if len(result.stdout) > 10_000_000: raise ValueError('Worktree listing too large')
        paths = set(os.fsdecode(p) for p in result.stdout.split(b'\0') if p)
        if len(paths) > 20_000: raise ValueError('Worktree has too many files')
        total_bytes = 0
        for path in sorted(paths):
            try:
                if len(path.encode()) > 1000: continue
            except UnicodeError: continue
            if Path(path).name in SKIP or path.endswith(('.min.js', '.min.css', '.lock')) or ':' in path or '\n' in path: continue
            try:
                file = confined(root, path)
                if not file.is_file() or file.stat().st_size > MAX_FILE: continue
                raw = safe_bytes(root, path)
                if b'\0' in raw or len(raw) > MAX_FILE: continue
                text = raw.decode('utf-8')
                if any(len(line) > 3000 for line in text.splitlines()): continue
                total_bytes += len(raw)
                if total_bytes > MAX_CORPUS: raise RuntimeError('Code corpus exceeds lookup size limit')
                yield path, hashlib.sha256(raw).hexdigest(), text
            except (OSError, UnicodeError, ValueError): continue

    def refresh(self, root):
        root = Path(root).resolve()
        version = self.model + PROMPT_VERSION
        with self.locked():
            if not self.allowed(root): raise ValueError('Not a registered coding worktree')
            with self.db() as db:
                old_paths = {row[0] for row in db.execute('SELECT path FROM files WHERE root=?', (str(root),))}
                previous = dict(db.execute('SELECT path,digest FROM files WHERE root=? AND version=?', (str(root), version)))
            files = list(self.candidates(root))
            current = {path for path, _, _ in files}
            changed = [(p, digest, windows(p, text)) for p, digest, text in files if previous.get(p) != digest]
            with self.db() as db:
                existing = dict(db.execute('SELECT path,count(*) FROM chunks WHERE root=? GROUP BY path', (str(root),)))
            total_chunks = sum(existing.get(p, 0) for p in current - {p for p, _, _ in changed}) + sum(len(c) for _, _, c in changed)
            if total_chunks > MAX_CHUNKS: raise ValueError('Code corpus exceeds lookup chunk limit')
            pending = {}
            for _, _, chunks in changed:
                for chunk in chunks:
                    key = hashlib.sha256((self.model + '\0' + PROMPT_VERSION + '\0' + chunk['sent']).encode()).hexdigest()
                    chunk['hash'] = key
                    with self.db() as db:
                        if not db.execute('SELECT 1 FROM roots WHERE root=?', (str(root),)).fetchone():
                            raise ValueError('Coding worktree was discarded')
                        known = db.execute('SELECT 1 FROM vectors WHERE hash=?', (key,)).fetchone()
                    if not known and key not in pending:
                        pending[key] = normalize(self.embedder.embed([chunk['sent']]))[0].tobytes()
                        # Retain completed vectors even if a task ends during the
                        # initial build. The manifest is still published atomically.
                        with self.db() as db:
                            db.execute('INSERT OR IGNORE INTO vectors VALUES(?,?)', (key, pending[key]))
            # Network work happens before the write transaction. Registering another
            # task remains fast, and a failed build never publishes a partial manifest.
            with self.db() as db:
                if not db.execute('SELECT 1 FROM roots WHERE root=?', (str(root),)).fetchone():
                    raise ValueError('Coding worktree was discarded')
                for path in old_paths - current:
                    db.execute('DELETE FROM chunks WHERE root=? AND path=?', (str(root), path))
                    db.execute('DELETE FROM files WHERE root=? AND path=?', (str(root), path))
                db.executemany('INSERT OR IGNORE INTO vectors VALUES(?,?)', pending.items())
                for path, digest, chunks in changed:
                    db.execute('DELETE FROM chunks WHERE root=? AND path=?', (str(root), path))
                    for chunk in chunks:
                        db.execute('INSERT INTO chunks VALUES(?,?,?,?,?)', (str(root), path, chunk['start'], chunk['end'], chunk['hash']))
                    db.execute('INSERT OR REPLACE INTO files VALUES(?,?,?,?)', (str(root), path, digest, version))
                db.execute('DELETE FROM vectors WHERE hash NOT IN (SELECT hash FROM chunks)')
                return db.execute('SELECT count(*) FROM chunks WHERE root=?', (str(root),)).fetchone()[0]

    def search(self, root, query, path=None, limit=5, budget=3000):
        root = Path(root).resolve()
        if not self.allowed(root): raise ValueError('Not a registered coding worktree')
        scope = confined(root, path or '.')
        with self.db() as db:
            rows = db.execute('SELECT c.path,c.start,c.end,v.vector FROM chunks c JOIN vectors v ON c.hash=v.hash '
                              'WHERE c.root=?', (str(root),)).fetchall()
            digests = dict(db.execute('SELECT path,digest FROM files WHERE root=?', (str(root),)))
        eligible = []
        for file, start, end, vector in rows:
            try:
                target = confined(root, file)
                if target == scope or target.is_relative_to(scope): eligible.append((file, start, end, vector))
            except ValueError: continue
        if not eligible: return 'No indexed source matches this path. Use grep/read.'
        query_vector = normalize(self.embedder.embed(['task: code retrieval | query: ' + query]))[0]
        matrix = np.stack([np.frombuffer(row[3], dtype=np.float32) for row in eligible])
        scores = matrix @ query_vector
        picked = []
        order = np.argsort(-scores)
        for rank, idx in enumerate(order[:max(limit * 4, 20)]):
            file, start, end, _ = eligible[int(idx)]
            score = float(scores[idx])
            overlap = next((p for p in picked if p['path'] == file and start <= p['end'] and end >= p['start']), None)
            if overlap:
                overlap.update(start=min(start, overlap['start']), end=max(end, overlap['end']))
            elif len(picked) < limit and not any(p['path'] == file for p in picked):
                picked.append({'path': file, 'start': start, 'end': end, 'anchor': start, 'score': score})
            # Continue over nearby candidates to merge windows without adding
            # more than limit distinct snippets. Keep the candidate pool bounded.
        # A later window can bridge two previously distinct windows. Merge again
        # so no returned ranges overlap even in that case.
        merged = []
        for item in picked:
            overlaps = [p for p in merged if p['path'] == item['path'] and item['start'] <= p['end'] and item['end'] >= p['start']]
            for other in overlaps:
                item.update(start=min(item['start'], other['start']), end=max(item['end'], other['end']), score=max(item['score'], other['score']))
                merged.remove(other)
            merged.append(item)
        picked = sorted(merged, key=lambda x: -x['score'])
        output, used = [], 0
        for item in picked:
            try:
                file = confined(root, item['path'])
                raw = safe_bytes(root, item['path'])
                if hashlib.sha256(raw).hexdigest() != digests[item['path']]: return BUILDING
                lines = raw.decode().splitlines(keepends=True)
            except (OSError, UnicodeError, ValueError): continue
            count_tokens = getattr(self.embedder, 'token_count', lambda text: len(text.encode()))
            share = (budget - used) // max(1, len(picked) - len(output))
            start, end = item['start'], item['end']
            def render(first, last):
                header = f"{item['path']}:{first}-{last} score={item['score']:.3f}\n"
                return header + ''.join(lines[first - 1:last]) + '\n'
            snippet = render(start, end)
            tokens = count_tokens(snippet)
            if tokens > share:
                # Keep the highest-scoring window's start when a merged range must
                # shrink. Earlier neighboring windows must not crowd out the match.
                start = max(start, item.get('anchor', start))
                end = min(end, start + 59)
                while end >= start:
                    snippet = render(start, end)
                    tokens = count_tokens(snippet)
                    if tokens <= share: break
                    end = start + max(0, int((end-start+1) * share / max(tokens,1)) - 1) - 1
            if end < start or tokens > share: continue
            used += tokens; output.append(snippet)
        return ''.join(output) if output else 'No snippets fit the lookup budget. Use grep/read.'


class CodeLookup:
    def __init__(self, store, root, wait=2):
        self.store, self.root, self.wait = store, Path(root).resolve(), wait
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.future = None
        if store.allowed(self.root) and store.prebuild_allowed(self.root): self.future = self.pool.submit(store.refresh, self.root)

    def call(self, arguments):
        try:
            query = arguments.get('query')
            path = arguments.get('path')
            limit = arguments.get('limit', 5)
            if set(arguments) - {'query', 'path', 'limit'} or not isinstance(query, str) or not query.strip() or len(query) > 2000:
                raise ValueError('Provide a conceptual query of 1–2000 characters')
            if path is not None and (not isinstance(path, str) or len(path) > 1000): raise ValueError('Invalid path')
            if type(limit) is not int or not 1 <= limit <= 5: raise ValueError('Limit must be 1–5')
            confined(self.root, path or '.')
            if not self.store.allowed(self.root): raise ValueError('Not a registered coding worktree')
            if self.future is not None:
                self.future.result(timeout=self.wait)
                self.future = None
            # Refresh on every lookup, including lookups after the initial build.
            self.future = self.pool.submit(self.store.refresh, self.root)
            self.future.result(timeout=self.wait)
            self.future = None
            return self.store.search(self.root, query, path, limit)
        except concurrent.futures.TimeoutError: return BUILDING
        except ValueError as error:
            if self.future is not None and self.future.done(): self.future = None
            return f'{error}. Use grep/read.'
        except Exception:
            self.future = None
            return FALLBACK


TOOLS = [{'name': 'search_code', 'description': DESCRIPTION, 'inputSchema': {'type': 'object',
          'properties': {'query': {'type': 'string', 'minLength': 1, 'maxLength': 2000},
                         'path': {'type': 'string', 'description': 'Optional worktree-relative file or directory'},
                         'limit': {'type': 'integer', 'minimum': 1, 'maximum': 5, 'default': 5}},
          'required': ['query'], 'additionalProperties': False}}]


def serve(lookup=None, stdin=sys.stdin, stdout=sys.stdout):
    if lookup is None:
        store = LookupStore(os.environ['FRIDAY_CODE_LOOKUP_DATA'], EmbeddingService())
        lookup = CodeLookup(store, Path.cwd())  # OpenCode's trusted directory, never a model argument.
    for line in stdin:
        request = None
        try:
            request = json.loads(line)
            if 'id' not in request: continue
            method, params = request.get('method'), request.get('params') or {}
            if method == 'initialize': result = {'protocolVersion': params.get('protocolVersion', '2024-11-05'),
                'capabilities': {'tools': {}}, 'serverInfo': {'name': SERVER, 'version': '1'}}
            elif method == 'ping': result = {}
            elif method == 'tools/list': result = {'tools': TOOLS}
            elif method == 'tools/call':
                text = lookup.call(params.get('arguments') or {}) if params.get('name') == 'search_code' else 'Unknown tool; use grep/read.'
                result = {'content': [{'type': 'text', 'text': text}]}
            else: raise ValueError('Unsupported method')
            response = {'jsonrpc': '2.0', 'id': request['id'], 'result': result}
        except Exception:
            response = {'jsonrpc': '2.0', 'id': (request or {}).get('id'), 'error': {'code': -32603, 'message': 'Invalid code lookup request'}}
        stdout.write(json.dumps(response) + '\n'); stdout.flush()


if __name__ == '__main__': serve()
