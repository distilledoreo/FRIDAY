import json
import tempfile
import time
import unittest
from types import SimpleNamespace

import httpx
from fastapi import FastAPI, HTTPException

from desktop.agent.model_ranking import ModelRanking, ENDPOINT
from desktop.agent.vision_proxy import install, vision_payload, responses_payload, chat_result


class VisionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        ranking = ModelRanking(self.tmp.name)
        ranking.catalog_checked = time.time()
        ranking.catalog = {name: {'id': name, 'context': 65536, 'input_modalities': inputs}
                           for name, inputs in (('best-free', ['text', 'image']), ('next-free', ['text', 'image']), ('text-free', ['text']))}
        self.settings = {'enabled': True, 'computer_use': True}
        self.agent = SimpleNamespace(password='private-fixture', ranking=ranking, vision_chain=['best-free', 'next-free'], settings=lambda: self.settings)
        self.headers = {'Authorization': 'Bearer private-fixture'}

    async def asyncTearDown(self): self.tmp.cleanup()

    def test_payload_forces_verified_free_vision_and_rejects_paid_overrides(self):
        body, chain = vision_payload({'model': 'best-free', 'models': ['paid-model'], 'messages': [],
                                      'provider': {'max_price': {'prompt': 10}}, 'plugins': [{'id': 'web'}]}, self.agent)
        self.assertEqual(chain, ['best-free', 'next-free'])
        self.assertNotIn('provider', body)
        self.assertNotIn('models', body)
        self.assertNotIn('plugins', body)
        for name in ('paid-model', 'text-free', 'openrouter/free'):
            with self.assertRaises(HTTPException): vision_payload({'model': name, 'messages': []}, self.agent)
        self.agent.ranking.catalog.pop('best-free')
        self.assertEqual(vision_payload({'model': 'best-free', 'messages': []}, self.agent)[0]['model'], 'next-free')

    async def request(self, cloud, body):
        app = FastAPI(); install(app, self.agent, client_factory=lambda: cloud)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=('127.0.0.1', 1234)), base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions', json={})).status_code, 401)
            return await client.post('/workspace/pc/vision/v1/chat/completions', headers=self.headers, json=body)

    async def test_fixed_zen_target_public_auth_and_free_fallback_before_streaming(self):
        requests = []
        async def upstream(request):
            requests.append(request)
            if len(requests) == 1: return httpx.Response(429, json={'error': 'private detail'})
            return httpx.Response(200, content=b'data: [DONE]\n\n', headers={'Content-Type': 'text/event-stream'})
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        response = await self.request(cloud, {'model': 'best-free', 'messages': [], 'stream': True})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.text, 'data: [DONE]\n\n')
        self.assertTrue(cloud.is_closed)
        self.assertEqual([str(r.url) for r in requests], [ENDPOINT + '/chat/completions'] * 2)
        self.assertEqual([json.loads(r.content)['model'] for r in requests], ['best-free', 'next-free'])
        self.assertTrue(all(r.headers['Authorization'] == 'Bearer public' for r in requests))
        self.assertTrue(all('models' not in json.loads(r.content) and 'provider' not in json.loads(r.content) for r in requests))

    async def test_non_loopback_and_disabled_desktop_never_contact_zen(self):
        app = FastAPI(); install(app, self.agent, client_factory=lambda: (_ for _ in ()).throw(AssertionError('Cloud accessed')))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=('192.0.2.1', 1234)), base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions', headers=self.headers, json={})).status_code, 401)
        self.settings['computer_use'] = False
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=('127.0.0.1', 1234)), base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions', headers=self.headers, json={})).status_code, 403)

    async def test_exhausted_models_return_terminal_redacted_error_and_close(self):
        calls = []
        async def upstream(request):
            calls.append(request)
            return httpx.Response(429, json={'error': 'remote-secret and upstream detail'})
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        response = await self.request(cloud, {'model': 'best-free', 'messages': []})
        self.assertEqual(response.status_code, 400)
        self.assertNotIn('remote-secret', response.text)
        self.assertEqual(len(calls), 2)
        self.assertTrue(cloud.is_closed)

    def test_responses_conversion_preserves_images_tool_history_and_tool_definitions(self):
        converted = responses_payload({'model': 'muse-free', 'stream': True, 'messages': [
            {'role': 'system', 'content': 'Owned system'},
            {'role': 'user', 'content': [{'type': 'text', 'text': 'Owned'}, {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,owned'}}]},
            {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'call-owned', 'function': {'name': 'screenshot', 'arguments': '{}'}}]},
            {'role': 'tool', 'tool_call_id': 'call-owned', 'content': 'Owned screenshot result'}],
            'tools': [{'type': 'function', 'function': {'name': 'screenshot', 'parameters': {'type': 'object'}}}],
            'tool_choice': {'type': 'function', 'function': {'name': 'screenshot'}}})
        self.assertEqual(converted['input'][1]['content'][1]['type'], 'input_image')
        self.assertEqual(converted['input'][2]['type'], 'function_call')
        self.assertEqual(converted['input'][3]['call_id'], 'call-owned')
        self.assertEqual(converted['tools'][0]['name'], 'screenshot')
        self.assertEqual(converted['tool_choice'], {'type': 'function', 'name': 'screenshot'})
        self.assertFalse(converted['store'])

    async def test_cross_protocol_fallback_adapts_responses_text_and_tool_stream_without_duplication(self):
        self.agent.ranking.catalog['next-free']['npm'] = '@ai-sdk/openai'
        requests = []
        events = [
            {'type': 'response.output_text.delta', 'delta': 'Owned text'},
            {'type': 'response.output_item.added', 'output_index': 1, 'item': {'type': 'function_call', 'call_id': 'owned-call', 'name': 'screenshot', 'arguments': ''}},
            {'type': 'response.function_call_arguments.delta', 'output_index': 1, 'delta': '{}'},
            {'type': 'response.output_item.done', 'output_index': 1, 'item': {'type': 'function_call', 'arguments': '{}'}},
            {'type': 'response.completed'}]
        async def upstream(request):
            requests.append(request)
            if len(requests) == 1: return httpx.Response(503)
            return httpx.Response(200, content=''.join('data: ' + json.dumps(e) + '\n\n' for e in events).encode())
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        response = await self.request(cloud, {'model': 'best-free', 'messages': [{'role': 'user', 'content': 'Owned'}], 'stream': True})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(str(requests[1].url), ENDPOINT + '/responses')
        self.assertEqual(json.loads(requests[1].content)['input'][0]['content'], 'Owned')
        chunks = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith('data: ') and '[DONE]' not in line]
        self.assertEqual(chunks[0]['choices'][0]['delta']['content'], 'Owned text')
        arguments = ''.join(c['choices'][0]['delta'].get('tool_calls', [{}])[0].get('function', {}).get('arguments', '') for c in chunks)
        self.assertEqual(arguments, '{}')
        self.assertEqual(chunks[-1]['choices'][0]['finish_reason'], 'tool_calls')
        self.assertTrue(cloud.is_closed)

    async def test_nonstream_responses_result_and_transport_failure_fallthrough(self):
        self.agent.ranking.catalog['next-free']['npm'] = '@ai-sdk/openai'
        async def upstream(request):
            if request.url.path.endswith('/chat/completions'): raise httpx.ConnectError('private detail', request=request)
            return httpx.Response(200, json={'id': 'owned', 'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': 'Owned result'}]}]})
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        response = await self.request(cloud, {'model': 'best-free', 'messages': []})
        self.assertEqual(response.json()['choices'][0]['message']['content'], 'Owned result')
        self.assertTrue(cloud.is_closed)

    def test_nonstream_responses_tool_calls(self):
        result = chat_result({'output': [{'type': 'function_call', 'call_id': 'owned', 'name': 'screenshot', 'arguments': '{}'}]}, 'muse-free')
        self.assertEqual(result['choices'][0]['finish_reason'], 'tool_calls')
        self.assertEqual(result['choices'][0]['message']['tool_calls'][0]['id'], 'owned')

    async def test_started_stream_failure_is_not_replayed_through_another_model(self):
        calls = []
        class BrokenStream(httpx.AsyncByteStream):
            closed = False
            async def __aiter__(self):
                yield b'data: {"choices":[{"delta":{"content":"Owned partial output"}}]}\n\n'
                raise httpx.ReadError('Owned stream interrupted')
            async def aclose(self): self.closed = True
        stream = BrokenStream()
        async def upstream(request):
            calls.append(request)
            return httpx.Response(200, stream=stream)
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        with self.assertRaises(Exception):
            await self.request(cloud, {'model': 'best-free', 'messages': [], 'stream': True})
        self.assertEqual(len(calls), 1)
        self.assertTrue(stream.closed and cloud.is_closed)
