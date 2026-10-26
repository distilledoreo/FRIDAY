import asyncio
import tempfile
import unittest
from pathlib import Path

from desktop.imagegen.manager import GpuGate, ImageManager


class Runtime:
    dedicated = True
    def __init__(self): self.ready = False
    async def image_ready(self): return self.ready


class WarmTests(unittest.IsolatedAsyncioTestCase):
    """Opening the Images page loads the model ahead of time, but never against a video or twice at once."""
    async def test_warm_starts_once_and_never_against_a_video(self):
        with tempfile.TemporaryDirectory() as root:
            runtime = Runtime(); manager = ImageManager(Path(root), GpuGate(), runtime, None)
            loads = []; release = asyncio.Event()
            async def rewarm(): loads.append(True); await release.wait()
            manager.rewarm = rewarm
            manager.video_busy = lambda: True
            self.assertFalse(await manager.warm())          # A video is rendering or queued.
            manager.video_busy = lambda: False
            self.assertTrue(await manager.warm())
            self.assertFalse(await manager.warm())          # Already loading.
            release.set(); await manager.warming
            runtime.ready = True
            self.assertFalse(await manager.warm())          # Already loaded.
            self.assertEqual(loads, [True])
