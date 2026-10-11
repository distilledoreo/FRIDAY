import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from desktop.model_server.wait_ready import prepare_opening, wait_loaded


class StartupTests(unittest.IsolatedAsyncioTestCase):
    async def test_waits_for_real_loaded_state_not_just_http_200(self):
        client = SimpleNamespace(get=AsyncMock(side_effect=[
            httpx.Response(200, json={'loaded': False}),
            httpx.Response(503, json={'loaded': False}),
            httpx.Response(200, json={'loaded': True})]))
        with patch('desktop.model_server.wait_ready.asyncio.sleep', AsyncMock()):
            await wait_loaded(client, 30)
        self.assertEqual(client.get.await_count, 3)

    async def test_unavailable_model_fails_startup_with_bounded_wait(self):
        client = SimpleNamespace(get=AsyncMock())
        clock = iter([0, 31])
        with patch('desktop.model_server.wait_ready.time', SimpleNamespace(monotonic=lambda: next(clock))):
            with self.assertRaisesRegex(RuntimeError, '30 seconds'):
                await wait_loaded(client, 30)
        client.get.assert_not_called()

    async def test_failed_opening_warmup_does_not_block_startup(self):
        request = httpx.Request('POST', 'http://fixture/v1/chat/completions')
        error = httpx.HTTPStatusError('400', request=request, response=httpx.Response(400, request=request))
        openings = SimpleNamespace(after_reload=AsyncMock(side_effect=error))
        with self.assertLogs(level='WARNING') as logs:
            await prepare_opening(openings)
        self.assertIn('not prepared before start', logs.output[0])
