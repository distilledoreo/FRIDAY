"""Keeps the local agents' fixed prompt prefix (system prompt and tools) in Strata's prompt cache.

Strata has one pinned prefix per engine and chat's opening holds it, so the OpenCode agents rely on ordinary
checkpoints, which other conversations can evict. While the GPU is idle this re-reads each remembered prefix
with a one-token request: quick when it is still cached, and otherwise paid here, not at the start of a task.
Only the prefix is remembered on disk, never a user message or an answer.
"""
import asyncio
import contextlib
import hashlib
import json
import os
import time
from pathlib import Path

import httpx

KEEP = 3
PLACEHOLDER = '⁣next-message⁣'
OPTIONS = ('model', 'tool_choice', 'chat_template_kwargs', 'reasoning_effort', 'reasoning_budget_tokens')


def prefix_of(body):
    """The task-independent start of an adapted request, or None when there is nothing worth keeping warm."""
    messages = body.get('messages') or []
    system = []
    for message in messages:
        if message.get('role') != 'system' or not isinstance(message.get('content'), str): break
        system.append({'role': 'system', 'content': message['content']})
    if not system or not body.get('tools'): return None
    return {'messages': system, 'tools': body['tools'], **{key: body[key] for key in OPTIONS if key in body}}


def key_of(spec):
    return hashlib.sha256(json.dumps(spec, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class PrefixWarmer:
    def __init__(self, root, gate, inference, enabled, client_factory=None, interval=None, poll=30):
        self.path = Path(root) / 'local-prefixes.json'
        self.gate, self.inference, self.enabled, self.poll = gate, inference, enabled, poll
        self.client_factory = client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(180, connect=5), follow_redirects=False))
        self.interval = float(os.environ.get('FRIDAY_LOCAL_PREFIX_REWARM_SECONDS', '900')) if interval is None else interval
        self.specs, self.warmed, self.epoch, self.task = {}, {}, None, None
        try:
            for spec in json.loads(self.path.read_text())[-KEEP:]: self.specs[key_of(spec)] = spec
        except (OSError, ValueError, TypeError, AttributeError): pass

    def active(self):
        return self.interval > 0 and os.environ.get('FRIDAY_LLM_BACKEND') == 'strata' and self.gate is not None

    def remember(self, body):
        """Called with each real request: it has just read or reused its own prefix."""
        if not self.active(): return
        spec = prefix_of(body)
        if spec is None: return
        key = key_of(spec)
        known = key in self.specs
        self.specs[key] = self.specs.pop(key, spec)
        self.warmed[key] = time.monotonic()
        if known: return
        while len(self.specs) > KEEP: self.warmed.pop(next(iter(self.specs)), None); self.specs.pop(next(iter(self.specs)))
        try:
            temporary = self.path.with_suffix('.tmp')
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, 'w') as file: json.dump(list(self.specs.values()), file, ensure_ascii=False)
            temporary.replace(self.path)
        except OSError: pass

    async def _epoch(self, client):
        status = (await client.get('http://127.0.0.1:8080/v1/status', timeout=3)).json()
        return (status.get('started'), status.get('engine'), status.get('model')) if status.get('loaded') is True else None

    async def once(self):
        """Warm every prefix that is due. Returns how many requests were sent."""
        if not self.active() or not self.specs or not self.enabled(): return 0
        sent = 0
        client = self.client_factory()
        try:
            epoch = await self._epoch(client)
            if epoch is None: return 0
            if epoch != self.epoch: self.warmed.clear(); self.epoch = epoch
            for key, spec in list(self.specs.items()):
                if time.monotonic() - self.warmed.get(key, float('-inf')) < self.interval: continue
                # Never ahead of a person: only while nothing else is reading or waiting.
                if self.inference.locked() or not self.gate.background_allowed(): break
                async with self.inference:
                    await self.gate.acquire_chat()
                    try:
                        body = dict(spec, messages=spec['messages'] + [{'role': 'user', 'content': PLACEHOLDER}], max_tokens=1, stream=False)
                        response = await client.post('http://127.0.0.1:8080/v1/chat/completions', json=body)
                    finally:
                        await self.gate.release_chat()
                if response.status_code == 200: self.warmed[key] = time.monotonic(); sent += 1
                else: break
        except Exception:
            pass  # Warming is optional: a task still works from the full prompt.
        finally:
            await client.aclose()
        return sent

    async def start(self):
        async def loop():
            while True:
                await asyncio.sleep(self.poll)
                await self.once()
        if self.active() and self.task is None: self.task = asyncio.create_task(loop())

    async def stop(self):
        if self.task:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError): await self.task
            self.task = None
