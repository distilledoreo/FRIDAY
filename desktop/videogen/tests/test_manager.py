import asyncio
import json
from pathlib import Path
import tempfile
import unittest

from fastapi import HTTPException

from desktop.imagegen.manager import GpuGate
from desktop.videogen.manager import VideoManager
from desktop.videogen.plan import Planner


class Images:
    """The image service's runtime: it owns the image card's server and chat stop/restore."""
    def __init__(self, events, fail=None):
        self.events, self.fail = events, fail
        self.runtime = self
    async def unload_image(self): self.events.append('unload_image')
    async def unload_chat(self): self.events.append('unload_chat')
    async def restore_chat(self):
        self.events.append('restore_chat')
        if self.fail == 'restore':
            raise RuntimeError('restore failed')


class Runtime:
    def __init__(self, events, fail=None, gate=None):
        self.events, self.fail, self.gate = events, fail, gate
        self.seen_gate = None
    async def command(self, *args, check=True, timeout=65):
        self.events.append(' '.join(args[2:]))
    async def preflight(self): self.events.append('preflight')
    async def cleanup(self): self.events.append('cleanup')
    async def render(self, request, plan, work, cancelled, update, inputs=()):
        self.events.append('render:' + plan['placement'])
        self.seen_gate = (self.gate.exclusive, self.gate.phase)
        if self.fail == 'render':
            raise RuntimeError('The GPU ran out of memory for this size.')
        if self.fail == 'cancel':
            raise asyncio.CancelledError()
        work.mkdir(parents=True, exist_ok=True)
        (work / 'render.webm').write_bytes(b'webm')
        return work / 'render.webm'
    async def finish(self, source, work, plan):
        self.events.append('finish')
        (work / 'video.mp4').write_bytes(b'mp4'); (work / 'poster.jpg').write_bytes(b'jpg')
        return work / 'video.mp4', work / 'poster.jpg', 4.04


class Store:
    def __init__(self, root):
        self.files = Path(root)
        self.saved = []
    def put_file(self, data, name, mime=None, limit=None):
        self.saved.append((name, mime, limit))
        return {'id': 'b' * 32, 'name': name, 'mime': mime, 'url': 'assistant://artifact/' + 'b' * 32}
    def file(self, file_id):
        return {'mime': 'image/png'}


class Tests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.events = []

    def tearDown(self):
        self.tmp.cleanup()

    def manager(self, fail=None, config=None):
        gate = GpuGate()
        self.gate = gate
        manager = VideoManager(Path(self.tmp.name) / 'jobs', gate, Runtime(self.events, fail, gate), Planner(),
                               Store(self.tmp.name), Images(self.events, fail), {'asr_unit': 'asr.service', **(config or {})})
        return manager

    def request(self, quality=0, **extra):
        return {'id': 'a' * 32, 'prompt': 'Waves at sunset', 'negative_prompt': None, 'duration_seconds': 4,
                'aspect': '16:9', 'quality': quality, 'seed': 1, 'inputs': [], 'loras': [], **extra}

    async def test_one_card_video_keeps_chat_and_pauses_speech_and_images(self):
        m = self.manager()
        job = m.create(self.request())
        self.assertEqual(job, m.create(self.request()))
        await m.execute(job)
        self.assertEqual(self.events, ['preflight', 'unload_image', 'stop asr.service', 'render:single',
                                       'start asr.service', 'finish'])
        self.assertEqual(m.runtime.seen_gate, (False, 'chat_ready'))
        done = m.get(job['id'])
        self.assertEqual(done['status'], 'completed')
        self.assertEqual(done['artifact']['mime'], 'video/mp4')
        self.assertTrue(done['poster'])
        self.assertGreater(m.store.saved[0][2], 25 * 1024 * 1024)
        self.assertTrue(m.poster(job['id']).is_file())

    async def test_two_card_video_stops_chat_and_restores_it(self):
        m = self.manager()
        job = m.create(self.request(quality=4))
        self.assertEqual(job['plan']['placement'], 'dual')
        await m.execute(job)
        self.assertEqual(self.events, ['preflight', 'unload_image', 'unload_chat', 'render:dual', 'restore_chat',
                                       'start asr.service', 'finish'])
        # Chat requests were refused with the video flag while it rendered, and allowed again after.
        self.assertEqual(m.runtime.seen_gate, (True, 'video'))
        self.assertFalse(m.gate.exclusive)
        self.assertEqual(m.gate.phase, 'chat_ready')
        self.assertFalse(json.loads(m.journal.read_text())['borrowed'])

    async def test_chat_is_told_how_long_a_video_takes(self):
        gate = GpuGate()
        gate.exclusive, gate.phase = True, 'video'
        import time
        gate.video_until = time.time() + 600
        with self.assertRaises(HTTPException) as e:
            await gate.acquire_chat()
        self.assertEqual(e.exception.headers['X-Assistant-GPU-Busy'], 'video')
        self.assertEqual(e.exception.detail['minutes_left'], 10)

    async def test_failure_still_restores_chat_and_speech(self):
        m = self.manager('render')
        job = m.create(self.request(quality=4))
        await m.execute(job)
        self.assertIn('restore_chat', self.events)
        self.assertIn('start asr.service', self.events)
        failed = m.get(job['id'])
        self.assertEqual(failed['status'], 'failed')
        self.assertIn('ran out of memory', failed['error'])
        self.assertFalse(m.gate.exclusive)

    async def test_cancellation_restores_and_records_cancelled(self):
        m = self.manager('cancel')
        job = m.create(self.request())
        await m.execute(job)
        self.assertEqual(m.get(job['id'])['status'], 'cancelled')
        self.assertIn('start asr.service', self.events)

    async def test_failed_restore_blocks_chat_and_the_queue(self):
        m = self.manager('restore')
        job = m.create(self.request(quality=4))
        await m.execute(job)
        self.assertEqual(m.gate.phase, 'recovery_failed')
        self.assertIn('Chat restoration failed', m.get(job['id'])['error'])
        self.assertTrue(json.loads(m.journal.read_text())['borrowed'])

    async def test_recovery_after_a_crash_restores_chat(self):
        m = self.manager()
        m.journal.write_text(json.dumps({'borrowed': True, 'card': True}))
        job = m.create(self.request())
        m.update(job['id'], status='running')
        await m.recover()
        self.assertEqual(self.events, ['cleanup', 'restore_chat', 'start asr.service'])
        self.assertEqual(m.get(job['id'])['status'], 'failed')
        self.assertFalse(m.gate.exclusive)

    def test_validation(self):
        m = self.manager()
        with self.assertRaises(HTTPException) as e:
            m.create(self.request(duration_seconds=40))
        self.assertEqual(e.exception.status_code, 400)
        # Inputs and LoRAs are refused until the computer enables them.
        with self.assertRaises(HTTPException) as e:
            m.create(self.request(inputs=[{'kind': 'start_image', 'file_id': 'c' * 32, 'at_seconds': None, 'strength': None}]))
        self.assertIn('not available', e.exception.detail)
        with self.assertRaises(HTTPException) as e:
            m.create(self.request(loras=[{'name': 'style', 'strength': 1.0}]))
        self.assertIn('No LoRA', e.exception.detail)
        enabled = self.manager(config={'inputs': ['start_image']})
        job = enabled.create(self.request(inputs=[{'kind': 'start_image', 'file_id': 'c' * 32, 'at_seconds': 0, 'strength': None}]))
        self.assertEqual(job['status'], 'queued')

    def test_queue_and_delete(self):
        m = self.manager()
        for i in range(3):
            m.create(self.request(id=f'{i:032x}'))
        with self.assertRaises(HTTPException) as e:
            m.create(self.request(id='f' * 32))
        self.assertEqual(e.exception.status_code, 429)
        with self.assertRaises(HTTPException):
            m.delete('0' * 32)
        m.update('0' * 32, status='completed')
        self.assertEqual(m.delete('0' * 32), {'removed': True})


if __name__ == '__main__':
    unittest.main()
