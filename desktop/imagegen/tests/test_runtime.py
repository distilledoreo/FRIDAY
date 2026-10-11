import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from desktop.imagegen.manager import SystemRuntime


class ReloadTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.raw = SimpleNamespace(get=AsyncMock())
        self.runtime = SystemRuntime({'llm_unit':'synthetic-model.service'}, self.raw)
        self.runtime.command = AsyncMock()

    async def asyncTearDown(self):
        await self.runtime.http.aclose()

    async def test_custom_budget_allows_a_larger_cold_model(self):
        self.runtime.config['llm_reload_timeout_seconds'] = 900
        self.raw.get.return_value = httpx.Response(200, json={'loaded':True})
        clock = iter([0, 300])
        with patch('desktop.imagegen.manager.time', SimpleNamespace(monotonic=lambda:next(clock))):
            await self.runtime.restore_chat()
        self.raw.get.assert_awaited_once()
        self.runtime.command.assert_awaited_once_with('systemctl','--user','start','synthetic-model.service')

    async def test_health_200_does_not_finish_before_loaded(self):
        self.raw.get.side_effect = [httpx.Response(200,json={'loaded':False}),
                                    httpx.Response(200,json={'loaded':True})]
        clock = iter([0, 0, 1])
        with patch('desktop.imagegen.manager.time', SimpleNamespace(monotonic=lambda:next(clock))), \
             patch('desktop.imagegen.manager.asyncio.sleep', AsyncMock()):
            await self.runtime.restore_chat()
        self.assertEqual(self.raw.get.await_count, 2)

    async def test_legacy_default_keeps_four_minute_budget(self):
        clock = iter([0, 300])
        with patch('desktop.imagegen.manager.time', SimpleNamespace(monotonic=lambda:next(clock))):
            with self.assertRaisesRegex(RuntimeError, '240 seconds'):
                await self.runtime.restore_chat()
        self.raw.get.assert_not_called()

    async def test_invalid_budget_is_rejected_before_service_start(self):
        self.runtime.config['llm_reload_timeout_seconds'] = 1801
        with self.assertRaises(ValueError):
            await self.runtime.restore_chat()
        self.runtime.command.assert_not_called()

    async def test_loaded_backend_rebuilds_cache_before_returning(self):
        self.raw.get.return_value = httpx.Response(200, json={'loaded': True})
        self.raw.after_reload = AsyncMock()
        await self.runtime.restore_chat()
        self.raw.after_reload.assert_awaited_once()

    async def test_cache_recovery_error_is_not_reported_as_ready(self):
        self.raw.get.return_value = httpx.Response(200, json={'loaded': True})
        self.raw.after_reload = AsyncMock(side_effect=RuntimeError('Prefix recovery failed'))
        with self.assertRaisesRegex(RuntimeError, 'Prefix recovery failed'):
            await self.runtime.restore_chat()
