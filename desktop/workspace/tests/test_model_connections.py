import json
import unittest
from unittest.mock import patch
import httpx
from pydantic import ValidationError
from desktop.workspace.model_connections import Connection, Connections

class ModelConnectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_custom_stream_uses_only_model_credentials_and_timeout(self):
        registry = Connections()
        ident = registry.add(Connection(base_url='https://model.example/v1/', model='chosen', api_key='synthetic-model-key', timeout_seconds=40))
        requests = []
        def respond(request):
            requests.append(request)
            return httpx.Response(200, text='data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: [DONE]\n\n')
        client_type = httpx.AsyncClient
        def client(**kwargs):
            self.assertEqual(kwargs['timeout'], 40)
            self.assertFalse(kwargs['follow_redirects'])
            return client_type(transport=httpx.MockTransport(respond), **kwargs)
        registry.lease(ident, 'running-job')
        registry.values.pop(ident)  # Closing the client does not revoke its already authorized job.
        text = []
        async def report(value): text.append(value)
        with patch('desktop.workspace.model_connections.httpx.AsyncClient', client):
            answer = await registry.generate(ident, [{'role':'user','content':'Hi'}], [], report, job='running-job')
        self.assertEqual(answer['content'], 'Hello'); self.assertEqual(text, ['Hello'])
        self.assertEqual(str(requests[0].url), 'https://model.example/v1/chat/completions')
        self.assertEqual(requests[0].headers['authorization'], 'Bearer synthetic-model-key')
        self.assertEqual(json.loads(requests[0].content)['model'], 'chosen')
        registry.values.clear()
        from fastapi import HTTPException
        with self.assertRaises(HTTPException): registry.get(ident)
    def test_validation_rejects_credentials_in_urls_queries_and_timeouts(self):
        for address in ('file:///tmp/model', 'https://user:pass@example.org/v1', 'https://example.org/v1?token=x', 'https://example.org/#x'):
            with self.assertRaises(ValidationError): Connection(base_url=address, model='chosen')
        with self.assertRaises(ValidationError): Connection(base_url='https://example.org/v1', model='chosen', timeout_seconds=1)
