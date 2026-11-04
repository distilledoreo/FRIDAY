"""Reviewed local extension library and MCP connection management.

Import/preview never executes code. Plugins/hooks are explicit host-user trust
grants; only a confirmation of the exact revision exposes them to OpenCode.
"""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import time
from itertools import islice

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .checkpoints import atomic_write
from .pc import MCP_NAME, RESERVED_MCP, MAX_MCP_SERVERS, _digest


class ExtensionDraft(BaseModel):
    model_config = ConfigDict(extra='forbid')
    name: str = Field(min_length=1, max_length=80, pattern=r'^[a-z0-9][a-z0-9-]*$')
    description: str = Field(default='', max_length=500)
    version: str = Field(default='1', min_length=1, max_length=60)
    kind: str = Field(pattern='^(skill|plugin|hook)$')
    content: str = Field(min_length=1, max_length=32000)
    event: str = Field(default='tool.execute.after', pattern=r'^(tool\.execute\.before|tool\.execute\.after|experimental\.session\.compacting)$')


class ConfirmRevision(BaseModel):
    model_config = ConfigDict(extra='forbid')
    digest: str = Field(pattern='^[a-f0-9]{64}$')


class DiscoverExtensions(BaseModel):
    model_config = ConfigDict(extra='forbid')
    path: str = Field(min_length=1, max_length=4096)


class McpDraft(BaseModel):
    model_config = ConfigDict(extra='forbid')
    entry: dict


class OAuthCode(BaseModel):
    model_config = ConfigDict(extra='forbid')
    code: str = Field(min_length=1, max_length=4096)


class Extensions:
    def __init__(self, code):
        self.code = code
        self.directory = code.root / 'extensions'
        self.directory.mkdir(mode=0o700, exist_ok=True)
        with code.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS code_extensions(id TEXT PRIMARY KEY,draft TEXT NOT NULL,digest TEXT NOT NULL,'
                       'approved TEXT,enabled INTEGER NOT NULL DEFAULT 0,created REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS code_extension_revisions(extension TEXT,digest TEXT,draft TEXT NOT NULL,created REAL NOT NULL,PRIMARY KEY(extension,digest))')
            db.execute('INSERT OR IGNORE INTO code_extension_revisions SELECT id,digest,draft,created FROM code_extensions')
        code.pc.extension_config = self.runtime

    def get(self, identifier):
        with self.code.db() as db: row = db.execute('SELECT * FROM code_extensions WHERE id=?', (identifier,)).fetchone()
        if not row: raise HTTPException(404, 'No such extension')
        with self.code.db() as db: versions = db.execute('SELECT digest,draft,created FROM code_extension_revisions WHERE extension=? ORDER BY created DESC LIMIT 50', (identifier,)).fetchall()
        return {**json.loads(row['draft']), 'id': row['id'], 'digest': row['digest'], 'enabled': bool(row['enabled']),
                'reviewed': row['approved'] == row['digest'], 'versions': [{'digest': v['digest'], 'version': json.loads(v['draft'])['version']} for v in versions]}

    def list(self):
        with self.code.db() as db: ids = [r['id'] for r in db.execute('SELECT id FROM code_extensions ORDER BY created DESC')]
        return [{k: v for k, v in self.get(i).items() if k != 'content'} for i in ids]

    def save(self, draft, identifier=None):
        if identifier: self.get(identifier)
        elif len(self.list()) >= 50: raise HTTPException(409, 'The extension library is limited to fifty entries')
        identifier = identifier or secrets.token_hex(8)
        data = draft.model_dump()
        digest = _digest(data)
        with self.code.db() as db:
            count = db.execute('SELECT COUNT(*) FROM code_extension_revisions WHERE extension=?', (identifier,)).fetchone()[0]
            known = db.execute('SELECT 1 FROM code_extension_revisions WHERE extension=? AND digest=?', (identifier, digest)).fetchone()
            if count >= 50 and not known: raise HTTPException(409, 'This extension already has fifty saved revisions; export the needed source before replacing the library entry')
            db.execute('INSERT OR IGNORE INTO code_extension_revisions VALUES(?,?,?,?)', (identifier, digest, json.dumps(data), time.time()))
            db.execute('INSERT OR REPLACE INTO code_extensions VALUES(?,?,?,?,?,?)',
                       (identifier, json.dumps(data), digest, None, 0, time.time()))
        return self.get(identifier)

    def restore(self, identifier, digest):
        self.get(identifier)
        with self.code.db() as db: row = db.execute('SELECT draft FROM code_extension_revisions WHERE extension=? AND digest=?', (identifier, digest)).fetchone()
        if not row: raise HTTPException(404, 'No such saved revision')
        return self.save(ExtensionDraft.model_validate(json.loads(row['draft'])), identifier)

    def artifact(self, item):
        folder = self.directory / item['id'] / item['digest']
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        if item['kind'] == 'skill':
            # Generate front matter from reviewed metadata; body content cannot
            # replace the name/description used in discovery.
            skill = folder / 'skills' / item['name']
            skill.mkdir(parents=True, exist_ok=True, mode=0o700)
            text = '---\nname: ' + json.dumps(item['name']) + '\ndescription: ' + json.dumps(item['description'] or item['name']) + '\n---\n\n' + item['content']
            self.write_artifact(skill / 'SKILL.md', text.encode())
            return folder / 'skills'
        module = folder / 'extension.mjs'
        self.write_artifact(module, item['content'].encode())
        if item['kind'] == 'hook':
            # The reviewed hook module must export a default async callback.
            # Its module initialization still has host access, as the UI states.
            wrapper = folder / 'hook.mjs'
            source = ('import handler from "./extension.mjs";\nexport const FridayReviewedHook = async ({client}) => ({\n' +
                json.dumps(item['event']) + ': async (input, output) => {\n'
                'if (!input.sessionID) return;\nconst result = await client.session.messages({path:{id:input.sessionID}});\n'
                'const user = result.data?.findLast((m) => m.info.role === "user");\n'
                'if (user?.info.agent !== "friday-code") return;\nawait handler(input, output);\n}});\n')
            self.write_artifact(wrapper, source.encode())
            return wrapper
        return module

    @staticmethod
    def write_artifact(path, content):
        try:
            with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
                import stat
                if stat.S_ISREG(os.fstat(stream.fileno()).st_mode) and stream.read(len(content) + 1) == content: return
        except OSError: pass
        atomic_write(path, content)

    async def approve(self, identifier, digest):
        item = self.get(identifier)
        if digest != item['digest']: raise HTTPException(409, 'This extension changed; review the new revision')
        path = self.artifact(item)
        if item['kind'] != 'skill':
            node = shutil.which('node')
            if not node: raise HTTPException(409, 'Node.js is required to validate this extension before enabling it')
            result, _, error = await self.code.run(node, '--check', str(path), timeout=10, check=False, output_limit=2000)
            if result: raise HTTPException(422, 'JavaScript syntax check failed: ' + error[-500:])
            if item['kind'] == 'hook':
                result, _, error = await self.code.run(node, '--check', str(path.with_name('extension.mjs')), timeout=10, check=False, output_limit=2000)
                if result: raise HTTPException(422, 'Hook syntax check failed: ' + error[-500:])
        with self.code.db() as db:
            changed = db.execute('UPDATE code_extensions SET approved=?,enabled=1 WHERE id=? AND digest=?', (digest, identifier, digest)).rowcount
        if not changed: raise HTTPException(409, 'This extension changed during review')
        return self.get(identifier)

    def disable(self, identifier):
        self.get(identifier)
        with self.code.db() as db: db.execute('UPDATE code_extensions SET enabled=0 WHERE id=?', (identifier,))
        return self.get(identifier)

    def remove(self, identifier):
        self.get(identifier)
        with self.code.db() as db: db.execute('DELETE FROM code_extensions WHERE id=?', (identifier,))
        # Immutable files are kept until ordinary private-state cleanup, because
        # an active helper may still have this revision loaded.
        return {'id': identifier}

    def runtime(self):
        plugins, skills = [], []
        for metadata in self.list():
            if not metadata['enabled'] or not metadata['reviewed']: continue
            item = self.get(metadata['id'])
            path = self.artifact(item)
            if item['kind'] == 'skill': skills.append(str(path))
            else: plugins.append(path.as_uri())
        return {'plugins': plugins, 'skills': skills}

    def discover(self, raw):
        from .code import read_worktree_file
        root = Path(raw).expanduser()
        if not root.is_absolute() or root.is_symlink() or not root.is_dir(): raise HTTPException(422, 'Choose a real local folder on the PC')
        root = root.resolve()
        candidates = []
        # Bounded discovery: chosen folder plus one level, never recursive scans.
        for entry in sorted(islice(root.iterdir(), 100)):
            if entry.is_symlink(): continue
            if entry.is_file() and entry.suffix in ('.md', '.mjs', '.js'): candidates.append(entry)
            elif entry.is_dir() and (entry / 'SKILL.md').is_file(): candidates.append(entry / 'SKILL.md')
        result = []
        for path in candidates[:30]:
            try:
                data, size, _ = read_worktree_file(root, path.relative_to(root), 32000)
                if size > len(data): continue
                content = data.decode()
            except (OSError, UnicodeError): continue
            name = path.parent.name if path.name == 'SKILL.md' else path.stem
            name = re.sub('[^a-z0-9-]', '-', name.lower()).strip('-')[:80] or 'extension'
            result.append({'name': name, 'version': '1', 'description': str(path.relative_to(root)),
                           'kind': 'skill' if path.suffix == '.md' else 'plugin', 'content': content, 'event': 'tool.execute.after'})
        return result

    def raw_mcp(self):
        path = self.code.pc.root / 'mcp.json'
        try:
            if path.stat().st_size > 2 * 1024 * 1024: raise ValueError()
            data = json.loads(path.read_text())
            if not isinstance(data, dict) or not isinstance(data.get('servers'), dict): raise ValueError()
            return data
        except FileNotFoundError: return {'servers': {}}
        except (OSError, ValueError): raise HTTPException(409, 'The existing MCP file is invalid; repair it on the PC before editing connections') from None

    def mcp_name(self, name):
        if not MCP_NAME.fullmatch(name) or name in RESERVED_MCP: raise HTTPException(422, 'Choose a nonreserved MCP connection name')

    def mcp_list(self):
        result = []
        confirmed = {r['name']: r['confirmed'] for r in self.code.pc.mcp_status()['servers']}
        for name, raw in self.raw_mcp()['servers'].items():
            try:
                self.mcp_name(name)
                entry = self.code.pc._mcp_entry(raw)
            except (HTTPException, ValueError, KeyError, TypeError): continue
            preview = {**entry}
            for field in ('environment', 'headers'):
                if field in preview: preview[field] = {key: '(stored privately)' for key in preview[field]}
            if isinstance(preview.get('oauth'), dict): preview['oauth'] = {k: '(stored privately)' if k == 'clientSecret' else v for k, v in preview['oauth'].items()}
            result.append({'name': name, 'entry': preview, 'digest': _digest(entry), 'enabled': raw.get('enabled', True), 'confirmed': confirmed.get(name, False)})
        return result

    def save_mcp(self, name, body):
        self.mcp_name(name)
        data = self.raw_mcp()
        candidate = json.loads(json.dumps(body.entry))
        previous = data['servers'].get(name, {})
        for field in ('headers', 'environment', 'oauth'):
            if not isinstance(candidate.get(field), dict): continue
            for key, value in candidate[field].items():
                if value == '(stored privately)':
                    if not isinstance(previous.get(field), dict) or key not in previous[field]: raise HTTPException(422, 'Supply the new credential value')
                    candidate[field][key] = previous[field][key]
        try: entry = self.code.pc._mcp_entry(candidate)
        except (ValueError, KeyError, TypeError): raise HTTPException(422, 'Invalid MCP server configuration') from None
        if name not in data['servers'] and len(data['servers']) >= MAX_MCP_SERVERS: raise HTTPException(409, 'At most ten MCP servers are supported')
        data['servers'][name] = {**entry, 'enabled': False}
        atomic_write(self.code.pc.root / 'mcp.json', json.dumps(data).encode())
        with self.code.pc.db() as db: db.execute('DELETE FROM mcp_known WHERE name=?', (name,))
        return next(item for item in self.mcp_list() if item['name'] == name)

    async def status(self):
        runtime = {}
        if self.code.pc.client:
            try: runtime = await self.code.pc.call('GET', '/mcp')
            except Exception: pass
        servers = self.mcp_list()
        for server in servers:
            value = runtime.get(server['name'], {}) if isinstance(runtime, dict) else {}
            server['state'] = value.get('status', 'not loaded' if server['confirmed'] else 'disabled')
        return {'extensions': self.list(), 'mcp': servers}

    def approve_mcp(self, name, digest):
        self.mcp_name(name)
        data = self.raw_mcp()
        try: entry = self.code.pc._mcp_entry(data['servers'][name])
        except (KeyError, TypeError, ValueError): raise HTTPException(404, 'No valid MCP connection by that name') from None
        if digest != _digest(entry): raise HTTPException(409, 'This MCP configuration changed; review it again')
        data['servers'][name] = entry
        atomic_write(self.code.pc.root / 'mcp.json', json.dumps(data).encode())
        return self.code.pc.confirm_mcp(name)

    async def disable_mcp(self, name, remove=False):
        self.mcp_name(name)
        if self.code.pc.active_count(): raise HTTPException(409, 'Wait for or stop active tasks before disabling an MCP connection')
        data = self.raw_mcp()
        if name not in data['servers']: raise HTTPException(404, 'No such MCP connection')
        if remove: del data['servers'][name]
        else: data['servers'][name]['enabled'] = False
        atomic_write(self.code.pc.root / 'mcp.json', json.dumps(data).encode())
        with self.code.pc.db() as db: db.execute('DELETE FROM mcp_known WHERE name=?', (name,))
        # All tasks are idle. Closing the owned helper also closes clients cached
        # in idle worktree instances, not just the home instance's connection.
        if self.code.pc.client: await self.code.pc.stop()
        return {'name': name, 'enabled': False}

    async def mcp_action(self, name, action, code=None):
        self.mcp_name(name)
        if name not in self.code.pc.mcp_servers(): raise HTTPException(409, 'Review and enable this exact MCP configuration first')
        if self.code.pc.active_count(): raise HTTPException(409, 'Wait for active tasks before changing MCP authentication or connections')
        if action == 'disconnect': return await self.disable_mcp(name)
        if action == 'connect' and self.code.pc.client: await self.code.pc.stop()
        paths = {'auth': ('POST', 'auth'), 'callback': ('POST', 'auth/callback'), 'logout': ('DELETE', 'auth'),
                 'connect': ('POST', 'connect'), 'disconnect': ('POST', 'disconnect')}
        method, suffix = paths[action]
        result = await self.code.pc.call(method, f'/mcp/{name}/{suffix}', **({'json': {'code': code}} if code is not None else {}))
        if action in ('logout', 'callback') and self.code.pc.client: await self.code.pc.stop()
        return result
