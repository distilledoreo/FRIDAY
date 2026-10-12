import asyncio
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

import httpx
from fastapi import FastAPI
from desktop.agent.local_proxy import install, local_payload
from desktop.agent.pc import PcAgent, Settings
from desktop.imagegen.manager import GpuGate


class LocalProxyTests(unittest.IsolatedAsyncioTestCase):
    def test_strata_alias_and_private_cache_are_adapted(self):
        with patch.dict('os.environ', {'FRIDAY_LLM_BACKEND':'strata'}):
            body=local_payload({'messages':[{'role':'user','content':'Synthetic'}],
                                'cache_prompt':False,'strata_prefix':{'messages':1}})
        self.assertEqual(body['model'],'qwen3.8-flash-next')
        self.assertNotIn('id_slot',body)
        self.assertNotIn('strata_prefix',body)
        self.assertFalse(body['strata_checkpoint'])
        self.assertEqual(body['reasoning_effort'], 'low')
        self.assertEqual(body['reasoning_budget_tokens'], 0)

    def test_saved_session_cannot_disable_or_raise_coding_reasoning(self):
        with patch.dict('os.environ', {'FRIDAY_LLM_BACKEND': 'strata'}):
            body = local_payload({'messages': [], 'reasoning_effort': 'high',
                                  'reasoning': {'effort': 'high'}, 'reasoning_budget_tokens': 50000,
                                  'chat_template_kwargs': {'enable_thinking': False}})
        self.assertEqual(body['chat_template_kwargs'], {'enable_thinking': True, 'reasoning_effort': 'low'})
        self.assertEqual(body['reasoning_effort'], 'low')
        self.assertEqual(body['reasoning_budget_tokens'], 0)
        self.assertNotIn('reasoning', body)

    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.agent = PcAgent(Path(self.tmp.name))
        self.gate = GpuGate()
        self.seen = []
        self.responses = []
        async def upstream(request):
            self.seen.append((str(request.url), json.loads(request.content)))
            return self.responses.pop(0) if self.responses else httpx.Response(200, content=b'data: [DONE]\n\n')
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
        self.assertTrue(body['chat_template_kwargs']['enable_thinking'])
        self.assertEqual(body['reasoning_effort'], 'low')
        self.assertTrue(all(p['type'] == 'text' for p in body['messages'][0]['content']))
        self.assertEqual(self.gate.readers, 0)

    async def test_strata_output_budget_fits_after_large_tool_result(self):
        error = {'error': {'message': 'request exceeds the context window: prompt (59983 tokens) + max tokens (8192) exceeds the context (65536); Send a smaller max_tokens (at most 5545 here)'}}
        self.responses = [httpx.Response(400, json=error)]
        response = await self.client.post(self.path, headers=self.headers, json={**self.body, 'max_tokens': 8192})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(self.seen), 2)
        self.assertEqual(self.seen[1][1]['max_tokens'], 5545)
        self.assertEqual(self.seen[0][1]['messages'], self.seen[1][1]['messages'])
        self.assertEqual(self.gate.readers, 0)

    async def test_no_output_room_returns_recognizable_context_overflow(self):
        error = {'error': {'message': 'request exceeds the context window; at most 12 here'}}
        self.responses = [httpx.Response(400, json=error)]
        response = await self.client.post(self.path, headers=self.headers, json=self.body)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()['error']['code'], 'context_length_exceeded')
        self.assertEqual(response.json()['error']['message'], error['error']['message'])
        self.assertEqual(len(self.seen), 1)
        self.assertEqual(self.gate.readers, 0)

    async def test_validation_errors_are_preserved_and_retry_is_bounded(self):
        error = {'error': {'message': 'maximum context length exceeded'}}
        self.responses = [httpx.Response(400, json=error)]
        response = await self.client.post(self.path, headers=self.headers, json=self.body)
        self.assertEqual((response.status_code, response.json()), (400, error))
        self.assertEqual(len(self.seen), 1)
        self.assertEqual(self.gate.readers, 0)
        room_error = {'error': {'message': 'request exceeds the context window; at most 5551 here'}}
        self.responses = [httpx.Response(400, json=room_error), httpx.Response(400, json=error)]
        response = await self.client.post(self.path, headers=self.headers, json=self.body)
        self.assertEqual((response.status_code, response.json()), (400, error))
        self.assertEqual(len(self.seen), 3)
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

    async def test_confirmed_parallel_strata_overlaps_foreground_but_respects_gpu_handoff(self):
        from desktop.agent.local_proxy import supports_parallel
        async def status(request):
            return httpx.Response(200, json={'loaded': True, 'concurrency': {'serving': 2}})
        with patch.dict('os.environ', {'FRIDAY_LLM_BACKEND': 'strata'}):
            async with httpx.AsyncClient(transport=httpx.MockTransport(status)) as client:
                self.assertTrue(await supports_parallel(client))
        self.gate.readers = 1
        self.gate.foreground['chat'] = __import__('time').monotonic() + 30
        self.assertFalse(self.gate.background_allowed())
        self.assertTrue(self.gate.background_allowed(concurrent=True))
        with patch('desktop.agent.local_proxy.supports_parallel', return_value=True):
            response = await self.client.post(self.path, headers=self.headers, json=self.body)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.gate.readers, 1)
        self.gate.exclusive = True
        self.assertFalse(self.gate.background_allowed(concurrent=True))
        self.gate.exclusive = False
        self.gate.maintenance_paused = lambda: True
        self.assertFalse(self.gate.background_allowed(concurrent=True))

    async def test_image_handoff_waits_for_both_concurrent_streams(self):
        await self.gate.acquire_chat()
        await self.gate.acquire_chat()
        entered = asyncio.Event()
        async def image():
            async with self.gate.image_lease(): entered.set()
        task = asyncio.create_task(image())
        await asyncio.sleep(.01)
        self.assertFalse(entered.is_set())
        self.assertFalse(self.gate.background_allowed(concurrent=True))
        await self.gate.release_chat()
        await asyncio.sleep(.01)
        self.assertFalse(entered.is_set())
        await self.gate.release_chat()
        await asyncio.wait_for(task, 1)
        self.assertTrue(entered.is_set())
        self.assertEqual(self.gate.readers, 0)
        self.assertFalse(self.gate.exclusive)

    async def test_parallel_capability_fails_closed_when_backend_is_single_slot_or_unloaded(self):
        from desktop.agent.local_proxy import supports_parallel
        for payload in [{'loaded': True, 'concurrency': {'serving': 1}}, {'loaded': False, 'concurrency': {'serving': 2}}, {'loaded': True, 'concurrency': {'serving': 'invalid'}}]:
            with patch.dict('os.environ', {'FRIDAY_LLM_BACKEND': 'strata'}):
                async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=payload))) as client:
                    self.assertFalse(await supports_parallel(client))

    async def test_disabled_and_invalid_requests_do_not_infer(self):
        self.assertEqual((await self.client.post(self.path, headers=self.headers, json={})).status_code, 422)
        self.agent.save_settings(Settings(enabled=False).model_dump())
        self.assertEqual((await self.client.post(self.path, headers=self.headers, json=self.body)).status_code, 403)
        self.assertEqual(self.seen, [])
