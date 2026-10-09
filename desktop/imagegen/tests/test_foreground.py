import asyncio,tempfile,time,unittest
from pathlib import Path
from desktop.imagegen.manager import GpuGate,GatedClient

class Client:
    def __init__(self):self.calls=0
    def build_request(self,*args,**kwargs):return object()
    async def send(self,*args,**kwargs):self.calls+=1;return object()

class ForegroundTests(unittest.IsolatedAsyncioTestCase):
    async def test_background_waits_for_foreground_then_resumes(self):
        gate=GpuGate();client=Client();wrapped=GatedClient(client,gate);gate.foreground['voice']=time.monotonic()+45
        task=asyncio.create_task(wrapped.post('/v1/chat/completions'))
        await asyncio.sleep(.05);self.assertEqual(client.calls,0)
        gate.foreground.clear();await asyncio.wait_for(task,1);self.assertEqual(client.calls,1);self.assertEqual(gate.readers,0)
    async def test_maintenance_does_not_block_direct_foreground_request(self):
        with tempfile.TemporaryDirectory() as root:
            gate=GpuGate();gate.maintenance_file=Path(root)/'paused';gate.maintenance_file.touch();client=Client();wrapped=GatedClient(client,gate)
            task=asyncio.create_task(wrapped.post('/v1/chat/completions'));await asyncio.sleep(.05);self.assertEqual(client.calls,0)
            await wrapped.send(object());self.assertEqual(client.calls,1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):await task
    async def test_expired_leases_do_not_block_after_phone_disconnect(self):
        gate=GpuGate();gate.foreground['old']=time.monotonic()-1;self.assertTrue(gate.background_allowed())
        await gate.acquire_chat();self.assertFalse(gate.background_allowed());await gate.release_chat()
