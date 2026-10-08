import json
import unittest
import httpx

from desktop.agent.cloud import FreeCloud, free_models


class CloudTests(unittest.IsolatedAsyncioTestCase):
    def catalog(self, prompt='0'):
        return {'data': [{'id': 'openrouter/free', 'pricing': {'prompt': prompt, 'completion': '0'}},
                         {'id': 'paid/model', 'pricing': {'prompt': '0', 'completion': '0'}},
                         {'id': 'missing:free', 'pricing': {}},
                         {'id': 'extra:free', 'pricing': {'prompt': '0', 'completion': '0', 'request': '.01'}}]}

    async def test_only_catalog_entries_with_all_zero_prices_are_allowed(self):
        self.assertEqual(list(free_models(self.catalog())), ['openrouter/free'])
        self.assertEqual(free_models(self.catalog('0.001')), {})
        self.assertEqual(free_models(self.catalog('NaN')), {})

    async def test_broker_does_not_accept_caller_fallback_endpoint_or_paid_model(self):
        calls = []
        def handle(request):
            calls.append(request)
            if request.url.path.endswith('/models'): return httpx.Response(200, json=self.catalog())
            return httpx.Response(200, json={'choices': [{'message': {'content': 'Answer'}}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            broker = FreeCloud('secret-test', client=client, max_requests=1)
            with self.assertRaises(ValueError): await broker.complete({'model': 'paid/model'})
            self.assertEqual(calls, [])
            await broker.complete({'model': 'friday-free', 'messages': [], 'models': ['paid/model'], 'plugins': [{'id': 'web'}], 'stream': True})
            body = json.loads(calls[-1].content)
            self.assertEqual(body['model'], 'openrouter/free')
            self.assertFalse(body['stream'])
            self.assertNotIn('models', body)
            self.assertNotIn('plugins', body)
            self.assertEqual(body['provider']['max_price']['prompt'], 0)
            with self.assertRaises(RuntimeError): await broker.complete({'messages': []})

    async def test_price_change_fails_before_inference(self):
        calls = []
        def handle(request):
            calls.append(request.url.path)
            return httpx.Response(200, json=self.catalog('.01'))
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            broker = FreeCloud('secret-test', client=client)
            with self.assertRaises(RuntimeError): await broker.complete({'messages': []})
            self.assertEqual(calls, ['/api/v1/models'])

    async def test_provider_failure_never_falls_back_or_exposes_error_text(self):
        calls = []
        def handle(request):
            calls.append(request.url.path)
            if request.url.path.endswith('/models'): return httpx.Response(200, json=self.catalog())
            return httpx.Response(429, json={'error': 'private upstream detail'})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            broker = FreeCloud('secret-test', client=client)
            with self.assertRaises(httpx.HTTPStatusError) as failure: await broker.complete({'messages': []})
            self.assertNotIn('private upstream detail', str(failure.exception))
            self.assertNotIn('secret-test', str(failure.exception))
            self.assertEqual(calls, ['/api/v1/models', '/api/v1/chat/completions'])
