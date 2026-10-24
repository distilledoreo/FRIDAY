import asyncio
import json
from pathlib import Path
import tempfile
import unittest

import httpx

from desktop.imagegen.manager import GpuGate
from desktop.model_server.strata import StrataClient, StrataOpeningSnapshots, strata_payload


class Cache:
    def __init__(self, root):
        self.SNAPSHOT_DIR = Path(root)
        self._LAST_SPEC = Path(root) / 'last-opening.json'

    @staticmethod
    def _opening(body):
        messages = body.get('messages', [])
        if not messages or messages[0].get('role') != 'system':
            return None
        return {'tools': body['tools'], 'messages': [messages[0]]}

    @staticmethod
    def _is_new_chat(messages):
        return sum(m.get('role') == 'user' for m in messages) == 1

    @staticmethod
    def _for_date(spec, date):
        return None


def request():
    return {'model': 'qwen3.8-27b', 'id_slot': 0, 'stream': True,
            'messages': [{'role': 'system', 'content': 'Stable instructions'},
                         {'role': 'user', 'content': 'Synthetic test message'}],
            'tools': [{'type': 'function', 'function': {'name': 'fixture', 'parameters': {'type': 'object'}}}]}


class PayloadTests(unittest.TestCase):
    def test_new_identical_turns_have_distinct_request_ids(self):
        with httpx.Client(base_url='http://fixture') as raw:
            client = StrataClient(raw)
            first = client.build_request('POST', '/v1/chat/completions', json=request())
            second = client.build_request('POST', '/v1/chat/completions', json=request())
            self.assertTrue(first.headers['x-request-id'])
            self.assertNotEqual(first.headers['x-request-id'], second.headers['x-request-id'])
            explicit = client.build_request('POST', '/v1/chat/completions', json=request(),
                                            headers={'X-Request-ID': 'retry-fixture'})
            self.assertEqual(explicit.headers['x-request-id'], 'retry-fixture')

    def test_tool_history_and_vision_survive(self):
        body = request()
        body['messages'] += [
            {'role': 'assistant', 'tool_calls': [{'id': 'fixture_call', 'type': 'function',
             'function': {'name': 'fixture', 'arguments': '{"count":2}'}}]},
            {'role': 'tool', 'tool_call_id': 'fixture_call', 'content': 'fixture result'},
            {'role': 'user', 'content': [{'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,fixture'}}]}]
        original = json.loads(json.dumps(body))
        adapted = strata_payload(body)
        self.assertEqual(adapted['messages'], original['messages'])
        self.assertEqual(adapted['tools'], original['tools'])
        self.assertTrue(adapted['stream'])
        self.assertNotIn('strata_prefix', adapted)
        self.assertNotIn('strata_checkpoint', adapted)
        self.assertNotIn('id_slot', adapted)
        self.assertEqual(body, original)

    def test_incognito_disables_parking_and_prefix(self):
        body = dict(request(), cache_prompt=False, strata_checkpoint=True, strata_prefix={'messages': 1})
        result = strata_payload(body)
        self.assertFalse(result['strata_checkpoint'])
        self.assertNotIn('strata_prefix', result)
        self.assertNotIn('cache_prompt', result)

    def test_tool_free_chat_keeps_parking_and_explicit_reasoning(self):
        result = strata_payload({'messages': [{'role': 'user', 'content': 'title'}], 'reasoning_effort': 'high'})
        self.assertNotIn('strata_checkpoint', result)
        self.assertEqual(result['reasoning_effort'], 'high')
        self.assertNotIn('strata_prefix', result)

    def test_reasoning_aliases_are_not_overridden_by_voice_default(self):
        for options in ({'reasoning': {'effort': 'high'}},
                        {'chat_template_kwargs': {'reasoning_effort': 'high'}},
                        {'chat_template_kwargs': {'enable_thinking': True}},
                        {'chat_template_kwargs': {'enable_thinking': False}}):
            with self.subTest(options=options):
                result = strata_payload(dict(request(), **options))
                self.assertNotIn('reasoning_effort', result)
                for key, value in options.items():
                    self.assertEqual(result[key], value)


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.gate = GpuGate()
        self.calls = []
        self.epoch = 1
        self.block = None
        self.fail = False
        self.refuse_prefix = False
        async def transport(req):
            if req.url.path == '/v1/status':
                return httpx.Response(200, json={'loaded': True, 'started': self.epoch, 'engine': 'test', 'model': 'test'})
            body = json.loads(req.content)
            self.calls.append(body)
            self.assertEqual(self.gate.readers, 0 if self.gate.exclusive else 1)
            if self.block:
                await self.block.wait()
            if self.fail:
                return httpx.Response(503, json={'error': 'fixture failure'})
            if self.refuse_prefix and 'strata_prefix' in body:
                return httpx.Response(400, json={'error': {'type': 'invalid_request_error',
                                                           'message': 'No user query found in messages.'}})
            return httpx.Response(200, json={'choices': [{'message': {'content': 'x'}}],
                                           'usage': {'prompt_tokens': 100, 'completion_tokens': 1}})
        self.raw = httpx.AsyncClient(base_url='http://fixture', transport=httpx.MockTransport(transport))
        self.client = StrataClient(self.raw)
        self.openings = StrataOpeningSnapshots(self.client, self.gate, Cache(self.directory.name), wait_seconds=.02)

    async def asyncTearDown(self):
        await self.openings.stop()
        await self.client.aclose()
        self.directory.cleanup()

    async def test_warmup_gates_gpu_and_saves_only_system_spec(self):
        await self.openings.before_chat(request())
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.gate.readers, 0)
        self.assertEqual(self.calls[0]['max_tokens'], 1)
        # Strata ignores the prefix pin when checkpointing is disabled.
        self.assertTrue(self.calls[0]['strata_checkpoint'])
        self.assertEqual(self.calls[0]['strata_prefix'], {'messages': 1})
        remembered = json.loads(self.openings.path.read_text())
        self.assertEqual(remembered['messages'], [request()['messages'][0]])
        self.assertNotIn('Synthetic test message', self.openings.path.read_text())
        self.assertEqual(self.openings.prime(request()), 'ready')

    async def test_refused_prefix_pin_warms_without_it(self):
        self.refuse_prefix = True
        with self.assertLogs('uvicorn.error', level='WARNING') as logs:
            await self.openings.before_chat(request())
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.calls[0]['strata_prefix'], {'messages': 1})
        self.assertNotIn('strata_prefix', self.calls[1])
        self.assertTrue(self.calls[1]['strata_checkpoint'])
        self.assertEqual(self.openings.prime(request()), 'ready')
        self.assertIn('No user query found', logs.output[0])

    async def test_incognito_never_primes_or_remembers(self):
        body = dict(request(), cache_prompt=False)
        self.assertEqual(self.openings.prime(body), 'ignored')
        await self.openings.before_chat(body)
        self.assertFalse(self.calls)
        self.assertFalse(self.openings.path.exists())

    async def test_service_restart_rebuilds_native_prefix(self):
        await self.openings.before_chat(request())
        await self.openings.before_chat(request())
        self.assertEqual(len(self.calls), 1)
        self.epoch = 2
        await self.openings.before_chat(request())
        self.assertEqual(len(self.calls), 2)

    async def test_changed_system_prompt_builds_a_new_prefix(self):
        await self.openings.before_chat(request())
        changed = request()
        changed['messages'][0]['content'] = 'Updated stable instructions'
        await self.openings.before_chat(changed)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.calls[1]['messages'][0]['content'], 'Updated stable instructions')
        self.assertTrue(self.calls[1]['strata_checkpoint'])
        await self.openings.before_chat(changed)
        self.assertEqual(len(self.calls), 2)

    async def test_image_reload_rebuilds_prefix_under_writer_lease(self):
        await self.openings.before_chat(request())
        self.epoch = 2
        self.gate.exclusive = True
        await asyncio.wait_for(self.client.after_reload(), 1)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(self.gate.readers, 0)
        self.assertTrue(self.openings.ready)

    async def test_image_reload_failure_does_not_leave_prefix_ready(self):
        await self.openings.before_chat(request())
        self.epoch = 2
        self.gate.exclusive = True
        self.fail = True
        with self.assertRaises(httpx.HTTPStatusError):
            await self.client.after_reload()
        self.assertFalse(self.openings.ready)

    async def test_cancelled_warmup_releases_gpu(self):
        self.block = asyncio.Event()
        self.openings.prime(request())
        for _ in range(20):
            if self.calls:
                break
            await asyncio.sleep(.01)
        self.assertEqual(self.gate.readers, 1)
        await self.openings.stop()
        self.assertEqual(self.gate.readers, 0)
        self.assertFalse(self.openings.ready)

    async def test_image_lease_delays_warmup(self):
        self.gate.exclusive = True
        self.openings.prime(request())
        await asyncio.sleep(.02)
        self.assertFalse(self.calls)
        self.gate.exclusive = False
        await asyncio.wait_for(asyncio.gather(*self.openings.tasks.values()), 1)
        self.assertEqual(len(self.calls), 1)

    async def test_prime_promotes_background_work_during_voice_lease(self):
        self.gate.foreground['fixture'] = float('inf')
        spec = self.openings._spec(request())
        self.openings._schedule(spec, urgent=False)
        await asyncio.sleep(.02)
        self.assertFalse(self.calls)
        self.openings.prime(request())
        await asyncio.wait_for(asyncio.gather(*self.openings.tasks.values()), 1)
        self.assertEqual(len(self.calls), 1)

    async def test_timeout_does_not_cancel_shared_priming_task(self):
        self.block = asyncio.Event()
        await self.openings.before_chat(request())
        self.assertTrue(self.openings.tasks)
        self.block.set()
        await asyncio.gather(*self.openings.tasks.values())
        self.assertEqual(self.gate.readers, 0)
        self.assertTrue(self.openings.ready)

    async def test_http_adapter_preserves_sse_and_stream_close(self):
        expected = b'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"fixture","arguments":"{}"}}]}}]}\n\ndata: [DONE]\n\n'
        received = []
        closed = []
        class Stream(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield expected[:40]
                yield expected[40:]
            async def aclose(self):
                closed.append(True)
        async def transport(req):
            received.append(json.loads(req.content))
            return httpx.Response(200, stream=Stream(), headers={'Content-Type': 'text/event-stream'})
        async with httpx.AsyncClient(base_url='http://fixture', transport=httpx.MockTransport(transport)) as raw:
            client = StrataClient(raw)
            response = await client.send(client.build_request('POST', '/v1/chat/completions', json=request()), stream=True)
            self.assertEqual(b''.join([part async for part in response.aiter_bytes()]), expected)
            await response.aclose()
        self.assertNotIn('strata_prefix', received[0])
        self.assertEqual(len(closed), 1)

    async def test_health_does_not_claim_an_unloaded_engine_is_ready(self):
        async def transport(req):
            return httpx.Response(200, json={'loaded': False})
        async with httpx.AsyncClient(base_url='http://fixture', transport=httpx.MockTransport(transport)) as raw:
            response = await StrataClient(raw).get('/health')
        self.assertEqual(response.status_code, 503)
        self.assertFalse(response.json()['loaded'])

    async def test_background_transport_does_not_disable_tool_free_foreground_cache(self):
        received = []
        async def transport(req):
            received.append(json.loads(req.content))
            self.assertEqual(int(req.headers['content-length']), len(req.content))
            self.assertEqual(req.headers['authorization'], 'Bearer fixture')
            self.assertIn('timeout', req.extensions)
            return httpx.Response(200, json={'choices': []})
        async with httpx.AsyncClient(base_url='http://fixture', transport=httpx.MockTransport(transport)) as raw:
            client = StrataClient(raw)
            body = {'messages': [{'role': 'user', 'content': 'Synthetic text'}]}
            req = client.build_request('POST', '/v1/chat/completions', json=body,
                                       headers={'Authorization': 'Bearer fixture'})
            response = await client.send(req, stream=True)
            await response.aclose()
            await client.send(req, stream=False)
            await client.post('/v1/chat/completions', json=body,
                              headers={'Authorization': 'Bearer fixture'})
        self.assertNotIn('strata_checkpoint', received[0])
        self.assertFalse(received[1]['strata_checkpoint'])
        self.assertFalse(received[2]['strata_checkpoint'])


if __name__ == '__main__':
    unittest.main()


class PrivateRequestTests(unittest.TestCase):
    def test_private_requests_ask_the_gateway_not_to_record_them(self):
        import httpx
        from desktop.model_server.strata import StrataClient
        client = StrataClient(httpx.AsyncClient(base_url='http://model'))
        private = client.build_request('POST', '/v1/chat/completions', json={'messages': [], 'cache_prompt': False})
        normal = client.build_request('POST', '/v1/chat/completions', json={'messages': []})
        self.assertEqual(private.headers.get('x-memory-private'), '1')
        self.assertIsNone(normal.headers.get('x-memory-private'))
        self.assertNotIn(b'cache_prompt', private.content)
