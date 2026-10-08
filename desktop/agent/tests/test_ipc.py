import asyncio
import json
from pathlib import Path
import struct
import tempfile
import unittest

from desktop.agent.approvals import Approvals
from desktop.agent.ipc import MAX_REQUEST, TaskBroker, UnixBroker


class FakeCloud:
    model = 'free-model'
    requests = 0
    async def complete(self, request):
        self.requests += 1
        return {'choices': [{'message': {'content': 'Answer'}}]}


class IpcTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='friday-ipc-')
        root = Path(self.temp.name)
        self.store = Approvals(root / 'agent.sqlite')
        self.task = self.store.propose_task('Research', ['Read public sources'])
        self.cloud = FakeCloud()
        self.broker = TaskBroker(self.store, self.task['id'], self.cloud,
                                reader=lambda url: {'url': url, 'mime': 'text/plain', 'content': 'Page'})
        self.path = root / 'broker.sock'
        self.server = UnixBroker(self.path, self.broker)
        await self.server.__aenter__()

    async def asyncTearDown(self):
        await self.server.__aexit__()
        self.assertFalse(self.path.exists())
        self.temp.cleanup()

    def start(self):
        self.store.approve_task(self.task['id'], self.task['fingerprint'])
        self.store.start_task(self.task['id'], self.task['fingerprint'])

    async def call(self, request, oversized=False):
        reader, writer = await asyncio.open_unix_connection(str(self.path))
        body = json.dumps(request).encode()
        writer.write(struct.pack('!I', MAX_REQUEST + 1 if oversized else len(body)) + body)
        await writer.drain()
        size = struct.unpack('!I', await reader.readexactly(4))[0]
        response = json.loads(await reader.readexactly(size))
        writer.close()
        await writer.wait_closed()
        return response

    async def test_socket_is_private_and_no_operations_run_without_task_approval(self):
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        result = await self.call({'operation': 'model'})
        self.assertFalse(result['ok'])
        self.assertEqual(self.cloud.requests, 0)

    async def test_only_narrow_operations_work_and_cancellation_revokes_capability(self):
        self.start()
        response = await self.call({'operation': 'read_page', 'arguments': {'url': 'https://example.com'}})
        self.assertEqual(response['result']['content'], 'Page')
        for op in ['approve_action', 'send', 'shell', 'read_file', 'proxy']:
            self.assertFalse((await self.call({'operation': op}))['ok'])
        self.store.cancel_task(self.task['id'])
        self.assertFalse((await self.call({'operation': 'model'}))['ok'])
        self.assertEqual(self.cloud.requests, 0)

    async def test_actions_remain_proposals_and_payload_is_bounded(self):
        self.start()
        response = await self.call({'operation': 'propose_action', 'arguments': {'kind': 'send', 'destination': 'person@example.com', 'payload': {'text': 'Reviewed'}}})
        self.assertEqual(response['result']['status'], 'proposed')
        self.assertFalse((await self.call({}, oversized=True))['ok'])
        self.assertEqual(self.store.actions(self.task['id'])[0]['status'], 'proposed')
