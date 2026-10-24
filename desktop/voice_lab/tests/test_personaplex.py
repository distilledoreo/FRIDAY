import asyncio
import json
import unittest

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from desktop.voice_lab.personaplex import create_app


class BrowserBridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.upload_finished = asyncio.Event()

        async def live(request):
            response = web.StreamResponse(headers={'Content-Type': 'text/event-stream'})
            await response.prepare(request)
            await request.content.readexactly(48_000)
            await response.write(b'data: {"type":"speech.audio.delta","audio":"AAA="}\n\n')
            chunk = await request.content.readexactly(4800)
            self.assertEqual(chunk, b'\0' * 4800)
            await response.write(b'data: {"type":"speech.text.delta","delta":"Hello"}\n\n')
            await request.read()
            self.upload_finished.set()
            await response.write(b'data: [DONE]\n\n')
            return response
        upstream = web.Application()
        upstream.router.add_post('/v1/audio/speech/live', live)
        self.upstream = TestServer(upstream)
        await self.upstream.start_server()
        self.client = TestClient(TestServer(create_app(str(self.upstream.make_url('')).rstrip('/'))))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()

    async def test_live_reply_arrives_before_microphone_upload_ends(self):
        ws = await self.client.ws_connect('/voice')
        await ws.send_json({'voice': 'NATF2', 'persona': 'A concise assistant.'})
        self.assertEqual((await ws.receive_json())['type'], 'preparing')
        self.assertEqual((await ws.receive_json())['type'], 'ready')
        self.assertEqual((await ws.receive_json())['type'], 'speech.audio.delta')
        await ws.send_bytes(b'\0' * 4800)
        event = await asyncio.wait_for(ws.receive_json(), 3)
        self.assertEqual(event, {'type': 'speech.text.delta', 'delta': 'Hello'})
        self.assertFalse(self.upload_finished.is_set())
        await ws.send_json({'type': 'finish'})
        await asyncio.wait_for(self.upload_finished.wait(), 3)
        await ws.close()

    async def test_foreign_browser_origin_cannot_open_microphone_bridge(self):
        response = await self.client.get('/status', headers={'Origin': 'https://example.com'})
        self.assertEqual(response.status, 403)

    async def test_rebound_hostname_is_rejected(self):
        response = await self.client.get('/status', headers={'Host': 'example.com'})
        self.assertEqual(response.status, 403)

    async def test_oversized_audio_chunk_closes_session_with_error(self):
        ws = await self.client.ws_connect('/voice')
        await ws.send_json({'voice': 'NATF2', 'persona': 'A concise assistant.'})
        for _ in range(3):
            await ws.receive_json()
        await ws.send_bytes(b'\0' * 10_000)
        event = await asyncio.wait_for(ws.receive_json(), 3)
        self.assertEqual(event['type'], 'error')
        self.assertIn('200 ms', event['message'])
        await ws.close()

    def test_only_local_native_runtime_is_allowed(self):
        with self.assertRaises(ValueError):
            create_app('http://example.com:8997')


if __name__ == '__main__':
    unittest.main()
