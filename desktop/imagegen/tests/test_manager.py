import asyncio
import base64
import io
import json
from pathlib import Path
import tempfile
import unittest
from fastapi import HTTPException
from PIL import Image
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
        if self.fail=='empty_error':raise RuntimeError()
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
    def manager(self,fail=None,dedicated=False):
        gate=GpuGate();runtime=Runtime(fail)
        runtime.dedicated=dedicated
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
    async def test_empty_exception_is_a_failure_without_an_artifact(self):
        m=self.manager('empty_error');j=m.create(self.request());await m.execute(j)
        result=m.get(j['id'])
        self.assertEqual(result['status'],'failed')
        self.assertEqual(result['error'],'RuntimeError')
        self.assertIsNone(result['artifact'])
        self.assertEqual(m.runtime.events[-2:],['unload_image','restore_chat'])

    async def test_recovery_repairs_false_completion_but_preserves_saved_images(self):
        m=self.manager();j=m.create(self.request())
        m.update(j['id'],status='completed',artifact=None,error='')
        await m.recover()
        self.assertEqual(m.get(j['id'])['status'],'failed')
        self.assertTrue(m.get(j['id'])['error'])
        m.update(j['id'],status='completed',artifact={'id':'b'*32},error=None)
        await m.recover()
        self.assertEqual(m.get(j['id'])['status'],'completed')

    async def test_reference_images_fit_qwen_vae_grid(self):
        for dimensions in [(478,679),(7,19),(512,512),(679,478)]:
            with self.subTest(dimensions=dimensions):
                m=self.manager();m.store.files=Path(self.tmp.name)/'files'
                m.store.files.mkdir(exist_ok=True)
                ref='b'*32
                Image.new('RGB',dimensions).save(m.store.files/ref,format='PNG')
                m.store.file=lambda file_id:{'mime':'image/png'}
                seen=[]
                async def generate(request,cancelled,update):
                    with Image.open(io.BytesIO(base64.b64decode(request['references'][0]))) as image:
                        seen.append(image.size)
                    return b'fake image'
                m.runtime.generate=generate
                request=self.request()|{'reference_file_ids':[ref]}
                j=m.create(request);await m.execute(j)
                self.assertEqual(m.get(j['id'])['status'],'completed')
                self.assertEqual(len(seen),1)
                for side in seen[0]:
                    self.assertGreaterEqual(side,32)
                    self.assertLessEqual(side,512)
                    self.assertEqual(side%32,0)
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

    async def test_dedicated_render_keeps_live_chat_and_image_model_loaded(self):
        m=self.manager(dedicated=True)
        await m.gate.acquire_chat()
        j=m.create(self.request())
        await asyncio.wait_for(m.execute(j),1)
        self.assertEqual(m.get(j['id'])['status'],'completed')
        self.assertEqual(m.runtime.events,['preflight','load_image','generate'])
        self.assertFalse(m.journal.exists())
        self.assertFalse(m.gate.exclusive)
        self.assertEqual(m.gate.phase,'chat_ready')
        await m.gate.acquire_chat()
        await m.gate.release_chat();await m.gate.release_chat()

    async def test_dedicated_failure_or_cancel_stops_only_the_image_process(self):
        for failure in ('load','generate','cancel'):
            with self.subTest(failure=failure):
                m=self.manager(failure,dedicated=True);j=m.create(self.request())
                await m.execute(j)
                self.assertEqual(m.runtime.events[-1],'unload_image')
                self.assertNotIn('unload_chat',m.runtime.events)
                self.assertNotIn('restore_chat',m.runtime.events)
                self.assertFalse(m.gate.exclusive)

    async def test_dedicated_sessions_serialize_images_without_blocking_chat(self):
        m=self.manager(dedicated=True)
        private=ImageManager(Path(self.tmp.name)/'private',m.gate,m.runtime,Store(),owns_runtime=False)
        entered=asyncio.Event();release=asyncio.Event();active=0;maximum=0
        async def generate(request,cancelled,update):
            nonlocal active,maximum
            active+=1;maximum=max(maximum,active);entered.set()
            await release.wait();active-=1
            return b'image'
        m.runtime.generate=generate
        first=asyncio.create_task(m.execute(m.create(self.request())))
        await asyncio.wait_for(entered.wait(),1)
        second=asyncio.create_task(private.execute(private.create(self.request())))
        await m.gate.acquire_chat();await m.gate.release_chat()
        release.set();await asyncio.gather(first,second)
        self.assertEqual(maximum,1)
        self.assertNotIn('unload_image',m.runtime.events)
        await private.stop()
        self.assertEqual(m.runtime.events[-1],'unload_image')

    async def test_closing_private_session_waiting_for_gpu_does_not_cancel_other_image(self):
        m=self.manager(dedicated=True)
        private=ImageManager(Path(self.tmp.name)/'private',m.gate,m.runtime,Store(),owns_runtime=False)
        entered=asyncio.Event();release=asyncio.Event()
        async def generate(request,cancelled,update):
            entered.set();await release.wait();return b'image'
        m.runtime.generate=generate
        first=asyncio.create_task(m.execute(m.create(self.request())))
        await asyncio.wait_for(entered.wait(),1)
        j=private.create(self.request())
        private.worker_task=asyncio.create_task(private.worker())
        while private.current is None:
            await asyncio.sleep(0)
        closing=asyncio.create_task(private.stop())
        await asyncio.sleep(0)
        self.assertFalse(first.done())
        self.assertNotIn('unload_image',m.runtime.events)
        release.set();await asyncio.gather(first,closing)
        self.assertEqual(private.get(j['id'])['status'],'cancelled')
        self.assertEqual(m.runtime.events[-1],'unload_image')


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

    async def test_stream_context_holds_lease_and_streams(self):
        class Response:
            status_code=200
            async def aclose(self): pass
        class Client:
            def build_request(self, method, path, **kwargs): return (method, path, kwargs)
            async def send(self, request, **kwargs):
                self.sent=(request, kwargs); return Response()
        gate=GpuGate(); raw=Client(); client=GatedClient(raw,gate)
        async with client.stream('POST', '/v1/chat/completions', json={'stream': True}) as response:
            self.assertEqual(response.status_code,200)
            self.assertEqual(gate.readers,1)
            self.assertTrue(raw.sent[1]['stream'])
        self.assertEqual(gate.readers,0)

    async def test_exact_max_size_is_allowed(self):
        validate(dict(prompt='robot',width=2000,height=2000,steps=8))
        with self.assertRaises(ValueError):
            validate(dict(prompt='robot',width=2000,height=2000,steps=12))
