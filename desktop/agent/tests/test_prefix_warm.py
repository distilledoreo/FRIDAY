import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from desktop.agent.prefix_warm import PLACEHOLDER, PrefixWarmer, prefix_of
from desktop.imagegen.manager import GpuGate

BODY = {'model': 'qwen3.8-flash-next', 'reasoning_effort': 'low', 'stream': True,
        'messages': [{'role': 'system', 'content': 'Synthetic agent prompt'}, {'role': 'user', 'content': 'Private synthetic task'}],
        'tools': [{'type': 'function', 'function': {'name': 'read', 'description': 'Synthetic'}}]}


class PrefixWarmTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.gate = GpuGate()
        self.inference = asyncio.Lock()
        self.requests = []
        self.started = 1
        self.env = patch.dict('os.environ', {'FRIDAY_LLM_BACKEND': 'strata'})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def warmer(self, interval=900, enabled=True):
        def handler(request):
            if request.url.path == '/v1/status':
                return httpx.Response(200, json={'loaded': True, 'started': self.started, 'engine': 'e', 'model': 'm'})
            self.requests.append(json.loads(request.content))
            return httpx.Response(200, json={'choices': []})
        return PrefixWarmer(Path(self.tmp.name), self.gate, self.inference, lambda: enabled,
                            lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)), interval=interval)

    def test_prefix_excludes_the_task_and_needs_tools(self):
        spec = prefix_of(BODY)
        self.assertEqual(spec['messages'], [{'role': 'system', 'content': 'Synthetic agent prompt'}])
        self.assertNotIn('Private synthetic task', json.dumps(spec))
        self.assertNotIn('stream', spec)
        self.assertIsNone(prefix_of(dict(BODY, tools=[])))

    async def test_a_real_request_counts_as_warm_until_the_interval_or_a_model_restart(self):
        warmer = self.warmer()
        warmer.remember(BODY)
        self.assertEqual(await warmer.once(), 1)  # First sight of this engine start: the cache is unknown.
        self.assertEqual(self.requests[0]['messages'][-1], {'role': 'user', 'content': PLACEHOLDER})
        self.assertEqual(self.requests[0]['max_tokens'], 1)
        self.assertFalse(self.requests[0]['stream'])
        self.assertEqual(await warmer.once(), 0)
        warmer.remember(BODY)
        self.assertEqual(await warmer.once(), 0)
        self.started = 2
        self.assertEqual(await warmer.once(), 1)

    async def test_waits_for_people_and_running_tasks(self):
        warmer = self.warmer(interval=0.001)
        warmer.remember(BODY)
        await self.gate.acquire_chat()
        self.assertEqual(await warmer.once(), 0)
        await self.gate.release_chat()
        async with self.inference: self.assertEqual(await warmer.once(), 0)
        await asyncio.sleep(0.01)
        self.assertEqual(await warmer.once(), 1)
        self.assertEqual(self.gate.readers, 0)
        self.assertFalse(self.inference.locked())

    async def test_remembers_only_prefixes_on_disk_and_reloads_them(self):
        warmer = self.warmer()
        for index in range(5): warmer.remember(dict(BODY, messages=[{'role': 'system', 'content': f'Agent {index}'}, BODY['messages'][1]]))
        saved = (Path(self.tmp.name) / 'local-prefixes.json').read_text()
        self.assertNotIn('Private synthetic task', saved)
        self.assertEqual([spec['messages'][0]['content'] for spec in json.loads(saved)], ['Agent 2', 'Agent 3', 'Agent 4'])
        self.assertEqual(len(self.warmer().specs), 3)
        self.assertEqual(await self.warmer().once(), 3)

    async def test_off_switches(self):
        for warmer in (self.warmer(interval=0), self.warmer(enabled=False)):
            warmer.remember(BODY)
            self.assertEqual(await warmer.once(), 0)
        with patch.dict('os.environ', {'FRIDAY_LLM_BACKEND': 'llama'}):
            warmer = self.warmer()
            known = dict(warmer.specs)
            warmer.remember(dict(BODY, messages=[{'role': 'system', 'content': 'Another agent'}, BODY['messages'][1]]))
            self.assertEqual(warmer.specs, known)
            self.assertEqual(await warmer.once(), 0)


if __name__ == '__main__': unittest.main()
