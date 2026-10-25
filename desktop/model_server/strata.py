"""Use Strata's native prefix cache without pretending it has llama.cpp slots.

The gateway retains prompt_cache.stabilize and its shared GPU gate. Strata owns
conversation checkpoints in RAM; no llama.cpp snapshot is loaded into it.
"""
import asyncio
from collections import OrderedDict
import contextlib
import datetime
import hashlib
import json
import logging
import os
from pathlib import Path
import uuid

import httpx

log = logging.getLogger('uvicorn.error')


def _error(response):
    try:
        return str(response.json()['error']['message'])[:200]
    except (ValueError, KeyError, TypeError):
        return f'HTTP {response.status_code}'


def strata_payload(body):
    if not isinstance(body, dict):
        return body
    result = dict(body)
    result['model'] = 'qwen3.8-flash-next'
    result.pop('id_slot', None)
    # cache_prompt false marks a private turn (incognito, a one-shot helper): the client sends x-memory-private, and
    # the gateway answers it natively without keeping anything. Strata's own no-checkpoint mode (strata_checkpoint
    # false) isn't used: with it, answers came back garbled or from other requests' text (2026-10-25).
    if result.pop('cache_prompt', None) is False:
        result.pop('strata_prefix', None)
    # Ordinary chat keeps native conversation parking. Explicit pinning is only
    # used by warmup: pinning every turn suppresses parking sibling chats.
    template = result.get('chat_template_kwargs')
    explicit_template = isinstance(template, dict) and any(k in template for k in ('reasoning_effort', 'enable_thinking'))
    if 'reasoning_effort' not in result and 'reasoning' not in result and not explicit_template:
        result['reasoning_effort'] = 'none'
    return result


class StrataClient:
    """Keep httpx's streaming/cancellation semantics and adapt request payloads."""
    def __init__(self, client):
        self.client = client
        self.reload_hook = None

    async def after_reload(self):
        if self.reload_hook is not None:
            await self.reload_hook()

    def build_request(self, method, url, **kwargs):
        if str(url).split('?')[0].endswith('/v1/chat/completions') and 'json' in kwargs:
            # Orchid can replay identical bodies as retries. A newly submitted
            # turn needs a fresh identity even if a deleted chat is recreated.
            headers = httpx.Headers(kwargs.get('headers'))
            if 'x-request-id' not in headers:
                headers['x-request-id'] = uuid.uuid4().hex
            # A private (incognito) request: the ORCHID gateway keeps nothing of it in its event store.
            if isinstance(kwargs['json'], dict) and kwargs['json'].get('cache_prompt') is False:
                headers['x-memory-private'] = '1'
            kwargs = dict(kwargs, json=strata_payload(kwargs['json']), headers=headers)
        return self.client.build_request(method, url, **kwargs)

    async def send(self, request, **kwargs):
        # The gateway sends foreground requests with transport streaming even
        # when JSON responses are requested. Background helpers use send(False).
        # Classify here rather than treating every tool-free chat as background.
        if not kwargs.get('stream', False) and request.url.path == '/v1/chat/completions':
            body = json.loads(request.content)
            if isinstance(body, dict) and not body.get('tools'):
                # A one-shot helper (a title, a memory note, a check): private, so it neither parks nor is kept.
                headers = dict(request.headers)
                headers['x-memory-private'] = '1'
                headers.pop('content-length', None)
                request = httpx.Request(request.method, request.url, headers=headers,
                                        json=body, extensions=request.extensions)
        return await self.client.send(request, **kwargs)

    async def get(self, url, **kwargs):
        response = await self.client.get(url, **kwargs)
        if str(url).split('?')[0].endswith('/health') and response.status_code == 200:
            try:
                state = response.json()
                if not isinstance(state, dict) or state.get('loaded') is not True:
                    response.status_code = 503
            except ValueError:
                response.status_code = 503
        return response

    async def post(self, url, **kwargs):
        return await self.send(self.build_request('POST', url, **kwargs))

    async def aclose(self):
        await self.client.aclose()


class StrataOpeningSnapshots:
    """Prewarm the actual system/tool prefix, held by Strata in memory.

    One discarded token lets the native engine capture its prefix checkpoint.
    Only the stable opening specification is persisted, never a user message,
    model answer or inference snapshot. A cold engine can rebuild it.
    """
    def __init__(self, client, gate, cache, remember_path=None, wait_seconds=120):
        self.client, self.gate, self.cache = client, gate, cache
        self.path = Path(remember_path or cache.SNAPSHOT_DIR / 'last-strata-opening.json')
        self.wait_seconds = wait_seconds
        self.tasks = {}
        self.ready = OrderedDict()
        self.urgent = set()
        self.epoch = None
        self.unpinned = False   # Strata refused the prefix pin once (logged once)
        self.client.reload_hook = self.after_reload

    async def start(self):
        for path in (self.path, self.cache._LAST_SPEC):
            try:
                spec = json.loads(path.read_text())
                if self._valid(spec):
                    spec = self.cache._for_date(spec, datetime.date.today()) or spec
                    self._schedule(spec, urgent=False)
                    break
            except (OSError, ValueError, TypeError):
                continue

    async def stop(self):
        pending = list(self.tasks.values())
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)

    @staticmethod
    def _key(spec):
        return hashlib.sha256(json.dumps(spec, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    @staticmethod
    def _valid(spec):
        if not isinstance(spec, dict) or not spec.get('tools'):
            return False
        messages = spec.get('messages')
        return isinstance(messages, list) and len(messages) == 1 and isinstance(messages[0], dict) and \
            messages[0].get('role') == 'system' and isinstance(messages[0].get('content'), str)

    def _spec(self, body):
        if not body.get('tools') or body.get('cache_prompt') is False:
            return None
        spec = self.cache._opening(body)
        return spec if self._valid(spec) else None

    def _remember(self, spec):
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary = self.path.with_suffix('.tmp')
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'w') as f:
                json.dump(spec, f, ensure_ascii=False)
            temporary.replace(self.path)
        except OSError:
            log.warning('Strata opening: could not remember the stable specification')

    def prime(self, body):
        spec = self._spec(body)
        if spec is None:
            return 'ignored'
        key = self._key(spec)
        self._remember(spec)
        if key in self.ready:
            return 'ready'
        return 'building' if self._schedule(spec, urgent=True) else 'ignored'

    async def before_chat(self, body):
        if body.get('cache_prompt') is False or not self.cache._is_new_chat(body.get('messages') or []):
            return
        spec = self._spec(body)
        if spec is None:
            return
        await self._refresh_generation()
        self._remember(spec)
        key = self._key(spec)
        if key in self.ready:
            return
        task = self._schedule(spec, urgent=True)
        if task:
            try:
                await asyncio.wait_for(asyncio.shield(task), self.wait_seconds)
            except Exception:
                # Priming is optional: inference still works from the full prompt.
                log.warning('Strata opening: continuing without a completed warmup')

    def _schedule(self, spec, urgent):
        key = self._key(spec)
        task = self.tasks.get(key)
        if task:
            if urgent:
                self.urgent.add(key)
            return task
        if len(self.tasks) >= 8:
            return None
        if urgent:
            self.urgent.add(key)
        task = asyncio.create_task(self._build(spec, urgent))
        self.tasks[key] = task
        def done(completed):
            self.tasks.pop(key, None)
            self.urgent.discard(key)
            if not completed.cancelled():
                with contextlib.suppress(Exception):
                    completed.result()
        task.add_done_callback(done)
        return task

    async def _refresh_generation(self):
        try:
            response = await self.client.get('/v1/status', timeout=3)
            response.raise_for_status()
            status = response.json()
            if not status.get('loaded'):
                raise ValueError('Model is not loaded')
            epoch = (status.get('started'), status.get('engine'), status.get('model'))
            if epoch != self.epoch:
                self.ready.clear()
                self.epoch = epoch
        except Exception:
            self.ready.clear()
            self.epoch = None

    async def _build(self, spec, urgent):
        key = self._key(spec)
        for attempt in range(3):
            while self.gate.exclusive or self.gate.readers or self.gate.maintenance_paused() or \
                    (key not in self.urgent and not self.gate.background_allowed()):
                await asyncio.sleep(.25)
            acquired = False
            try:
                await self.gate.acquire_chat()
                acquired = True
                await self._refresh_generation()
                await self._prewarm(spec)
                return
            except asyncio.CancelledError:
                raise
            except Exception as error:
                log.warning('Strata opening: warmup attempt %d failed (%s)', attempt + 1, type(error).__name__)
            finally:
                if acquired:
                    await self.gate.release_chat()
            await asyncio.sleep(attempt + 1)

    async def _prewarm(self, spec):
        """Caller owns either the chat reader or the image writer lease."""
        body = dict(spec, messages=spec['messages'] + [{'role': 'user', 'content': '\u2063next-message\u2063'}],
                    max_tokens=1, stream=False, reasoning_effort='none', strata_checkpoint=True)
        response = await self.client.post('/v1/chat/completions', json=dict(body, strata_prefix={'messages': 1}),
                                          timeout=180)
        if response.status_code == 400:
            # Strata (through 0.1.42) renders a {"messages": K} prefix on its own, and Qwen3.8's chat template refuses
            # a prompt with no user turn ("No user query found in messages."). Read the opening without the pin: the
            # conversation cache still keeps it, though a long unrelated chat can evict it.
            if not self.unpinned:
                self.unpinned = True
                log.warning('Strata opening: the prefix pin was refused (%s); warming without it', _error(response))
            response = await self.client.post('/v1/chat/completions', json=body, timeout=180)
        response.raise_for_status()
        payload = response.json()
        if not payload.get('choices') or not isinstance(payload.get('usage'), dict):
            raise ValueError('The prefix request did not complete')
        key = self._key(spec)
        self.ready[key] = None
        self.ready.move_to_end(key)
        while len(self.ready) > 8:
            self.ready.popitem(last=False)
        log.info('Strata opening: native prefix prewarmed')

    async def after_reload(self):
        """Restore the stable opening before releasing an image's GPU lease.

        Reacquiring a chat lease here would deadlock behind the image writer.
        Failure leaves recovery blocked, rather than claiming the cache is ready.
        """
        if not self.gate.exclusive:
            raise RuntimeError('Prefix recovery requires the exclusive GPU lease')
        await self._refresh_generation()
        for path in (self.path, self.cache._LAST_SPEC):
            try:
                spec = json.loads(path.read_text())
            except (OSError, ValueError):
                continue
            if self._valid(spec):
                spec = self.cache._for_date(spec, datetime.date.today()) or spec
                await self._prewarm(spec)
                return
