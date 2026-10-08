"""Per-task Unix IPC capability for a container with network=none.

Mount only the socket into that task's container. It is not an HTTP proxy and
does not expose arbitrary destinations, headers, files, shell commands or approvals.
"""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import struct

from .public_web import read

MAX_REQUEST = 2 * 1024 * 1024
MAX_RESPONSE = 4 * 1024 * 1024


class TaskBroker:
    def __init__(self, store, task_id, cloud, reader=read, search=None):
        self.store, self.task_id, self.cloud, self.reader = store, task_id, cloud, reader
        self.web_reads = 0
        self.search = search
        self.searches = 0
        self.sources = set()
        self.lock = asyncio.Lock()

    async def dispatch(self, request):
        async with self.lock:
            if self.store.task(self.task_id)['status'] != 'running':
                raise ValueError('Task is not running')
            if not isinstance(request, dict): raise ValueError('Invalid request')
            operation = request.get('operation')
            arguments = request.get('arguments', {})
            if not isinstance(arguments, dict): raise ValueError('Invalid arguments')
            if operation == 'model':
                result = await self.cloud.complete(arguments)
                record = {'model': self.cloud.model, 'request': self.cloud.requests}
            elif operation == 'read_page':
                if set(arguments) != {'url'}: raise ValueError('Only a page URL is accepted')
                if self.web_reads >= 100: raise ValueError('Task page limit reached')
                self.web_reads += 1
                result = await asyncio.wait_for(asyncio.to_thread(self.reader, arguments['url']), timeout=30)
                self.sources.add(result['url'])
                record = {'url': result['url'], 'mime': result['mime'], 'chars': len(result['content'])}
            elif operation == 'record_screenshot':
                if arguments.get('url') not in self.sources: raise ValueError('Screenshot source was not read by this task')
                result = self.store.save_screenshot(self.task_id, arguments['url'], arguments.get('png'))
                record = result
            elif operation == 'search':
                query = arguments.get('query')
                if self.search is None: raise ValueError('Search is not configured')
                if set(arguments) != {'query'} or not isinstance(query, str) or not 1 <= len(query) <= 500:
                    raise ValueError('Search requires a query of 1–500 characters')
                if self.searches >= 30: raise ValueError('Task search limit reached')
                self.searches += 1
                result = await self.search(query)
                record = {'query': query}
            elif operation == 'propose_action':
                result = self.store.propose_action(self.task_id, arguments.get('kind'),
                                                   arguments.get('destination'), arguments.get('payload'))
                result['executable'] = False
                result['detail'] = 'Proposal saved for review. Account/executor setup is still required; no action has run.'
                record = {'id': result['id'], 'fingerprint': result['fingerprint']}
            else:
                raise ValueError('Unsupported broker operation')
            # A cancelled task cannot return more content to the reasoning model.
            if self.store.task(self.task_id)['status'] != 'running': raise ValueError('Task stopped')
            with self.store.db() as db: self.store.event(db, self.task_id, 'broker_' + operation, record)
            return result


class UnixBroker:
    def __init__(self, path, task):
        self.path, self.task, self.server = Path(path), task, None
        self.handlers = set()

    async def __aenter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.path.exists(): raise ValueError('Broker socket already exists')
        self.server = await asyncio.start_unix_server(self.handle, path=str(self.path))
        os.chmod(self.path, 0o600)
        return self

    async def __aexit__(self, *exc):
        self.server.close()
        await self.server.wait_closed()
        pending = list(self.handlers)
        for handler in pending: handler.cancel()
        if pending: await asyncio.gather(*pending, return_exceptions=True)
        self.path.unlink(missing_ok=True)

    async def handle(self, reader, writer):
        current = asyncio.current_task()
        if len(self.handlers) >= 8:
            writer.close()
            return
        self.handlers.add(current)
        try:
            prefix = await asyncio.wait_for(reader.readexactly(4), timeout=5)
            amount = struct.unpack('!I', prefix)[0]
            if amount > MAX_REQUEST: raise ValueError('Request too large')
            raw = await asyncio.wait_for(reader.readexactly(amount), timeout=10)
            request = json.loads(raw)
            result = await self.task.dispatch(request)
            encoded = json.dumps({'ok': True, 'result': result}, ensure_ascii=False, allow_nan=False).encode()
            if len(encoded) > MAX_RESPONSE: raise ValueError('Response too large')
        except asyncio.CancelledError:
            writer.close()
            self.handlers.discard(current)
            raise
        except Exception as error:
            # Do not expose exception bodies, credential-bearing requests or
            # provider debug output to the model or task audit.
            status = getattr(getattr(error, 'response', None), 'status_code', 400)
            if status not in (400, 401, 403, 404, 429, 500, 502, 503, 504): status = 400
            encoded = json.dumps({'ok': False, 'error': type(error).__name__, 'status_code': status}).encode()
        try:
            writer.write(struct.pack('!I', len(encoded)) + encoded)
            await writer.drain()
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError): await writer.wait_closed()
            self.handlers.discard(current)
