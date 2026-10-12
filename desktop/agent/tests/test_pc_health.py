import tempfile
import time
import unittest
from pathlib import Path

import httpx

from desktop.agent.pc import LOCAL, UNHEALTHY_GRACE, PcAgent


class Stopped(Exception): pass


class LivePc(PcAgent):
    """A PC agent whose OpenCode process is alive; ensure() must decide whether to keep it."""
    def __init__(self, root, health):
        super().__init__(root)
        self.stops = 0
        self.process = type('Process', (), {'returncode': None})()
        self.client = httpx.AsyncClient(base_url='http://opencode.invalid', transport=httpx.MockTransport(health))
        self.ranking.ranked = lambda **kwargs: []

    async def models(self): return [LOCAL]

    async def stop(self):
        self.stops += 1
        raise Stopped


def slow(request): raise httpx.ReadTimeout('Synthetic slow health reply')
def ok(request): return httpx.Response(200, json={})


class OpenCodeHealthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    async def asyncTearDown(self):
        self.tmp.cleanup()

    async def test_one_slow_health_reply_does_not_restart_a_live_server(self):
        pc = LivePc(Path(self.tmp.name), slow)
        for _ in range(3): self.assertIs(await pc.ensure(), pc.client)
        self.assertEqual(pc.stops, 0)
        self.assertIsNotNone(pc.unhealthy_since)

    async def test_a_minute_of_silence_restarts_it(self):
        pc = LivePc(Path(self.tmp.name), slow)
        pc.unhealthy_since = time.monotonic() - UNHEALTHY_GRACE - 1
        with self.assertRaises(Stopped): await pc.ensure()
        self.assertEqual(pc.stops, 1)
        self.assertIsNone(pc.unhealthy_since)

    async def test_a_healthy_reply_clears_the_silence(self):
        pc = LivePc(Path(self.tmp.name), ok)
        pc.unhealthy_since = time.monotonic() - 30
        pc.config_digest = None
        with pc.db() as db:
            db.execute('INSERT INTO sessions(id, source, title, created, model, state) VALUES(?,?,?,?,?,?)',
                       ('ses_busy', 'test', 'Synthetic task', time.time(), 0, 'busy'))
        self.assertIs(await pc.ensure(), pc.client)
        self.assertIsNone(pc.unhealthy_since)
        self.assertEqual(pc.stops, 0)


if __name__ == '__main__': unittest.main()
