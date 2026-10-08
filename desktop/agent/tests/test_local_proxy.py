import asyncio
import json
import tempfile
import unittest
from pathlib import Path

import httpx
from fastapi import FastAPI
from desktop.agent.local_proxy import install, local_payload
from desktop.agent.pc import PcAgent, Settings
from desktop.imagegen.manager import GpuGate


class LocalProxyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.agent = PcAgent(Path(self.tmp.name))
        self.gate = GpuGate()
        self.seen = []
        async def upstream(request):
            self.seen.append((str(request.url), json.loads(request.content)))
            return httpx.Response(200, content=b'data: [DONE]\n\n')
        self.app = FastAPI()
        install(self.app, self.agent, self.gate, lambda: httpx.AsyncClient(transport=httpx.MockTransport(upstream)))
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app, client=('127.0.0.1', 1)), base_url='http://owned-test')
        self.path = '/workspace/pc/internal/v1/chat/completions'
        self.headers = {'Authorization': 'Bearer ' + self.agent.password}
        self.body = {'model': 'ignored', 'messages': [{'role': 'user', 'content': [{'type': 'image_url', 'image_url': {'url': 'data:fake'}}, {'type': 'text', 'text': 'Synthetic'}]}], 'stream': True, 'id_slot': 0}

    async def asyncTearDown(self):
        await self.client.aclose()
        self.tmp.cleanup()

    async def test_private_token_and_loopback_required(self):
        self.assertEqual((await self.client.post(self.path, json=self.body)).status_code, 401)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app, client=('192.0.2.1', 1)), base_url='http://test') as foreign:
            self.assertEqual((await foreign.post(self.path, headers=self.headers, json=self.body)).status_code, 401)
        self.assertEqual(self.seen, [])

    async def test_background_slot_text_only_fixed_target_and_lease_cleanup(self):
        response = await self.client.post(self.path, headers=self.headers, json=self.body)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['cache-control'], 'no-store')
        url, body = self.seen[0]
        self.assertEqual(url, 'http://127.0.0.1:8080/v1/chat/completions')
        self.assertEqual(body['id_slot'], 1)
        self.assertFalse(body['chat_template_kwargs']['enable_thinking'])
        self.assertTrue(all(p['type'] == 'text' for p in body['messages'][0]['content']))
        self.assertEqual(self.gate.readers, 0)

    async def test_waits_for_foreground_and_image_work(self):
        self.gate.foreground['owned'] = __import__('time').monotonic() + 30
        task = asyncio.create_task(self.client.post(self.path, headers=self.headers, json=self.body))
        await asyncio.sleep(.05)
        self.assertEqual(self.seen, [])
        self.gate.foreground.clear()
        self.gate.exclusive = True
        await asyncio.sleep(.3)
        self.assertEqual(self.seen, [])
        self.gate.exclusive = False
        self.assertEqual((await asyncio.wait_for(task, 2)).status_code, 200)
        self.assertEqual(self.gate.readers, 0)

    async def test_disabled_and_invalid_requests_do_not_infer(self):
        self.assertEqual((await self.client.post(self.path, headers=self.headers, json={})).status_code, 422)
        self.agent.save_settings(Settings(enabled=False).model_dump())
        self.assertEqual((await self.client.post(self.path, headers=self.headers, json=self.body)).status_code, 403)
        self.assertEqual(self.seen, [])
