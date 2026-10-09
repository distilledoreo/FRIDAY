import asyncio
import json
from pathlib import Path
import tempfile
import unittest
from fastapi import HTTPException
from desktop.imagegen.manager import GpuGate, GatedClient, ImageManager, atomic
from desktop.imagegen.policy import validate


class Runtime:
    def __init__(self, fail=None):self.events=[];self.fail=fail
    async def preflight(self):self.events.append('preflight')
    async def unload_chat(self):self.events.append('unload_chat')
    async def load_image(self, cancelled):
        self.events.append('load_image')
        if self.fail=='load':raise RuntimeError('load failed')
    async def generate(self, request, cancelled, update):
        self.events.append('generate')
        if self.fail=='generate':raise RuntimeError('render failed')
        if self.fail=='cancel':raise asyncio.CancelledError()
        return b'fake image'
    async def unload_image(self):self.events.append('unload_image')
    async def restore_chat(self):
        self.events.append('restore_chat')
        if self.fail=='restore':raise RuntimeError('restore failed')


class Store:
    def put_file(self,*args):return {'id':'a'*32,'name':'image.png','url':'assistant://artifact/'+'a'*32}


class Tests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):self.tmp=tempfile.TemporaryDirectory()
    def tearDown(self):self.tmp.cleanup()
    def manager(self,fail=None):
        gate=GpuGate();runtime=Runtime(fail)
        return ImageManager(Path(self.tmp.name),gate,runtime,Store())
    def request(self):return {'id':'a'*32,'prompt':'A blue triangle','width':768,'height':768,'steps':8,'seed':1,'reference_file_ids':[],'transparent':False}
    async def test_waits_for_inflight_chat_and_blocks_new_inference(self):
        gate=GpuGate();await gate.acquire_chat();entered=asyncio.Event()
        async def borrow():
            async with gate.image_lease():entered.set()
        task=asyncio.create_task(borrow());await asyncio.sleep(.01)
        self.assertFalse(entered.is_set())
        with self.assertRaises(HTTPException) as e:await gate.acquire_chat()
        self.assertEqual(e.exception.headers['X-Assistant-GPU-Busy'],'1')
        await gate.release_chat();await task
        self.assertFalse(gate.exclusive)
    async def test_success_restores_before_completion_and_is_idempotent(self):
        m=self.manager();j=m.create(self.request());self.assertEqual(j,m.create(self.request()))
        await m.execute(j)
        self.assertEqual(m.runtime.events,['preflight','unload_chat','load_image','generate','unload_image','restore_chat'])
        self.assertEqual(m.get(j['id'])['status'],'completed');self.assertFalse(m.gate.exclusive)
    async def test_failures_and_cancellation_restore_chat(self):
        for fail in ['load','generate','cancel']:
            with self.subTest(fail=fail):
                m=self.manager(fail);j=m.create(self.request());await m.execute(j)
                self.assertEqual(m.runtime.events[-2:],['unload_image','restore_chat'])
                self.assertEqual(m.get(j['id'])['status'],'cancelled' if fail=='cancel' else 'failed')
                self.assertFalse(m.gate.exclusive)
    async def test_restore_failure_keeps_gpu_locked(self):
        m=self.manager('restore');j=m.create(self.request());await m.execute(j)
        self.assertTrue(m.gate.exclusive);self.assertEqual(m.gate.phase,'recovery_failed')
    async def test_restart_recovers_borrowed_gpu_without_repeating_image(self):
        m=self.manager();j=m.create(self.request());m.update(j['id'],status='running')
        atomic(m.journal,{'borrowed':True});await m.recover()
        self.assertEqual(m.runtime.events,['unload_image','restore_chat']);self.assertEqual(m.get(j['id'])['status'],'failed')
    async def test_limits_reject_before_gpu_changes(self):
        m=self.manager();r=self.request();r.update(width=2048,height=2048,steps=8)
        with self.assertRaises(HTTPException):m.create(r)
        self.assertEqual(m.runtime.events,[])
        for changes in [dict(width=777),dict(steps=40),dict(reference_file_ids=['a'*32]*3)]:
            with self.assertRaises(ValueError):validate(self.request()|changes)
    async def test_cancel_queued_job_does_not_unload_chat(self):
        m=self.manager();j=m.create(self.request());m.cancel(j['id']);await m.execute(m.get(j['id']))
        self.assertEqual(m.runtime.events,[]);self.assertEqual(m.get(j['id'])['status'],'cancelled')


class StreamTests(unittest.IsolatedAsyncioTestCase):
    async def test_stream_lease_survives_until_close(self):
        class Response:
            async def aclose(self): pass
        class Client:
            async def send(self, request, **kwargs): return Response()
        gate=GpuGate(); client=GatedClient(Client(),gate)
        response=await client.send(None, stream=True)
        self.assertEqual(gate.readers,1)
        await response.aclose(); await response.aclose()
        self.assertEqual(gate.readers,0)

    async def test_exact_max_size_is_allowed(self):
        validate(dict(prompt='robot',width=2000,height=2000,steps=8))
        with self.assertRaises(ValueError):
            validate(dict(prompt='robot',width=2000,height=2000,steps=12))
