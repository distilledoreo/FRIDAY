"""Private browser turns and incremental replies use authenticated APIs without a second archive."""
import asyncio
import json
import tempfile
import unittest

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from desktop.workspace.workspace import install
from desktop.workspace.integration import enable


class BrowserChatTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = FastAPI()
        self.inputs = []
        async def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer synthetic': raise HTTPException(401)
        async def model(messages, tools):
            self.inputs.append((messages[:], tools))
            return {'role': 'assistant', 'content': 'Private answer'}
        async def streaming(messages, tools, report):
            self.inputs.append((messages[:], tools))
            await report('Private')
            await asyncio.sleep(.01)
            await report('Private answer')
            return {'role': 'assistant', 'content': 'Private answer'}
        model.stream = streaming
        async def search(query): return {'results': []}
        async def fetch(url): return {'markdown': ''}
        self.workspace = install(self.app, [Depends(auth)], self.temp.name, model, search, fetch)
        self.life = self.app.router.lifespan_context(self.app)
        await self.life.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://gateway', headers={'Authorization': 'Bearer synthetic'})
    async def asyncTearDown(self):
        await self.client.aclose()
        await self.life.__aexit__(None, None, None)
        self.temp.cleanup()
    def chat(self):
        return {'version': 1, 'summary': {'id': 'private', 'title': 'Private', 'createdAt': 1, 'updatedAt': 1}, 'messages': [{'role': 'user', 'content': 'Sensitive question'}]}
    @staticmethod
    def events(response):
        return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith('data: ')]
    async def test_private_turn_streams_and_never_writes_items_jobs_or_recall(self):
        class Memory:
            def context(self, *args): raise AssertionError('Private turns must not recall memory')
            def ingest_native(self, *args): raise AssertionError('Private turns must not index history')
        self.workspace.memory_store = Memory()
        before = (self.workspace.root / 'workspace.sqlite').read_bytes()
        response = await self.client.post('/workspace/chat/ephemeral', json={'chat': json.dumps(self.chat())})
        self.assertEqual(response.status_code, 200)
        events = self.events(response)
        self.assertEqual(events[0]['text'], 'Private')
        self.assertEqual(events[-1], {'text': 'Private answer', 'done': True})
        self.assertEqual(self.workspace.items(), [])
        self.assertEqual(self.workspace.jobs(), [])
        self.assertEqual((self.workspace.root / 'workspace.sqlite').read_bytes(), before)
        self.assertEqual({t['function']['name'] for t in self.inputs[0][1]}, {'web_search', 'fetch_page'})
        self.assertEqual(response.headers['cache-control'], 'no-store')
    async def test_private_route_rejects_attachments_malformed_chats_and_missing_auth(self):
        chat = self.chat(); chat['messages'][0]['attachments'] = [{'remoteFileId': 'normal-file'}]
        for value in ('{}', '[]', json.dumps(chat), '{"messages": [null]}', '{"messages": "invalid"}'):
            response = await self.client.post('/workspace/chat/ephemeral', json={'chat': value})
            self.assertEqual(response.status_code, 400)
        response = await self.client.post('/workspace/chat/ephemeral', json={'chat': json.dumps(self.chat())}, headers={'Authorization': ''})
        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.inputs, [])
    async def test_saved_job_stream_finishes_with_the_canonical_saved_reply(self):
        response = await self.client.post('/workspace/items/private/continue', json={'revision': 0, 'payload': {'chat': json.dumps(self.chat())}})
        self.assertEqual(response.status_code, 200)
        events = await asyncio.wait_for(self.client.get('/workspace/jobs/' + response.json()['id'] + '/events'), 10)
        self.assertTrue(self.events(events)[-1]['done'])
        self.assertEqual(self.events(events)[-1]['text'], 'Private answer')
        chat = json.loads(self.workspace.items()[0]['payload']['chat'])
        self.assertEqual(chat['messages'][-1], {'role': 'assistant', 'content': 'Private answer'})
        self.assertEqual(self.workspace.jobs()[0]['status'], 'completed')
        denied = await self.client.get('/workspace/jobs/' + response.json()['id'] + '/events', headers={'Authorization': ''})
        self.assertEqual(denied.status_code, 401)
        self.assertEqual((await self.client.get('/workspace/jobs/missing/events')).status_code, 404)
    async def test_oauth_callback_does_not_load_external_resources_or_cache_codes(self):
        response = await self.client.get('/friday/oauth-callback?code=synthetic&state=synthetic')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['cache-control'], 'no-store')
        self.assertEqual(response.headers['referrer-policy'], 'no-referrer')
        self.assertIn('history.replaceState', response.text)
        self.assertIn('location.origin', response.text)
        self.assertNotIn('synthetic', response.text)


class StreamingAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_model_adapter_accumulates_text_and_fragmented_tool_arguments(self):
        requests = []
        def provider(request):
            requests.append(json.loads(request.content))
            frames = [
                {'choices': [{'delta': {'content': 'Hello '}}]},
                {'choices': [{'delta': {'content': 'world', 'tool_calls': [{'index': 0, 'id': 'c1', 'function': {'name': 'web_search', 'arguments': '{"query":'}}]}}]},
                {'choices': [{'delta': {'tool_calls': [{'index': 0, 'function': {'arguments': '"hello"}'}}]}}]},
            ]
            return httpx.Response(200, text=''.join('data: ' + json.dumps(f) + '\n\n' for f in frames) + 'data: [DONE]\n\n', headers={'content-type': 'text/event-stream'})
        with tempfile.TemporaryDirectory() as folder:
            client = httpx.AsyncClient(transport=httpx.MockTransport(provider), base_url='http://model')
            import os
            from unittest.mock import patch
            async def search(**kwargs): return {}
            async def fetch(body): return {}
            with patch.dict(os.environ, {'ASSISTANT_MODEL': 'synthetic'}):
                app = FastAPI(); enable(app, [], client, search, fetch, dict, folder)
                # Exercise via private route, where the same adapter is used as for persistent jobs.
                http = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://gateway')
                chat = {'messages': [{'role': 'user', 'content': 'hi'}]}
                # A provider that always requests a tool ends at the bounded research limit.
                response = await http.post('/workspace/chat/ephemeral', json={'chat': json.dumps(chat)})
                self.assertEqual(response.status_code, 200)
                self.assertIn('"text": "Hello world"', response.text)
                self.assertTrue(requests[0]['stream'])
                call = requests[1]['messages'][2]['tool_calls'][0]
                self.assertEqual(call['id'], 'c1')
                self.assertEqual(call['function'], {'name': 'web_search', 'arguments': '{"query":"hello"}'})
                await http.aclose()
            await client.aclose()
