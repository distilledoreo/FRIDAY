import base64
import io
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from PIL import Image

from desktop.imagegen.manager import SystemRuntime


class ReloadTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.raw = SimpleNamespace(get=AsyncMock())
        self.runtime = SystemRuntime({'llm_unit':'synthetic-model.service'}, self.raw)
        self.runtime.command = AsyncMock()

    async def asyncTearDown(self):
        await self.runtime.http.aclose()

    async def test_dedicated_runtime_reuses_warm_service(self):
        self.runtime.dedicated=True
        self.runtime.config['command']=['sd-server','--diffusion-model','/models/turbo.gguf']
        self.runtime.command.return_value='active'
        await self.runtime.http.aclose()
        self.runtime.http=httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(
            lambda request:httpx.Response(200,json={'model':{'diffusion_model':'turbo'}})))
        await self.runtime.load_image(lambda:False)
        await self.runtime.load_image(lambda:False)
        self.assertTrue(self.runtime.loaded)
        self.assertTrue(all(call.args[0]=='systemctl' for call in self.runtime.command.await_args_list))
        self.raw.get.assert_not_awaited()

    async def test_dedicated_unit_receives_pinned_gpu_environment(self):
        self.runtime.dedicated=True
        self.runtime.config.update(command=['sd-server','--diffusion-model','/models/turbo.gguf'],
                                   environment={'CUDA_VISIBLE_DEVICES':'GPU-spare','CUDA_DEVICE_ORDER':'PCI_BUS_ID'})
        self.runtime.command.return_value='inactive'
        await self.runtime.http.aclose()
        self.runtime.http=httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(
            lambda request:httpx.Response(200,json={'model':{'diffusion_model':'turbo'}})))
        await self.runtime.load_image(lambda:False)
        command=self.runtime.command.await_args_list[-1].args
        self.assertEqual(command[0],'systemd-run')
        self.assertIn('--setenv=CUDA_VISIBLE_DEVICES=GPU-spare',command)
        self.assertIn('--setenv=CUDA_DEVICE_ORDER=PCI_BUS_ID',command)

    async def test_dedicated_preflight_does_not_wait_for_or_stop_chat(self):
        with tempfile.TemporaryDirectory() as directory:
            asset=Path(directory)/'asset';asset.write_bytes(b'verified fixture')
            stat=asset.stat()
            self.runtime.dedicated=True
            self.runtime.config.update(command=[str(asset)],binary_sha256='fixture',
                                       binary_size=stat.st_size,binary_mtime_ns=stat.st_mtime_ns,
                                       files=[{'path':str(asset),'bytes':stat.st_size,
                                               'verified_size':stat.st_size,'verified_mtime_ns':stat.st_mtime_ns}])
            await self.runtime.preflight()
            self.raw.get.assert_not_awaited()
            self.runtime.command.assert_not_awaited()

    async def test_health_rejects_missing_or_wrong_renderer_after_a_warm_load(self):
        self.runtime.config['command']=['sd-server','--diffusion-model','/models/turbo.gguf']
        await self.runtime.http.aclose()
        for status,model,ready in [(200,'turbo',True),(200,'other-model',False),(503,'turbo',False)]:
            self.runtime.loaded=True
            async with httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(
                    lambda request:httpx.Response(status,json={'model':model}))) as client:
                self.runtime.http=client
                self.assertEqual(await self.runtime.image_ready(),ready)

    async def test_readiness_matches_the_configured_diffusion_model(self):
        self.runtime.config['command']=['sd-server','--diffusion-model','/models/qwen-image-2.1-turbo-Q8_0.gguf']
        await self.runtime.http.aclose()
        for model,accepted in [('qwen-image-2.1-turbo-Q8_0',True),('qwen_image_2.1-Q8_0',False)]:
            with self.subTest(model=model):
                async with httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(
                        lambda request:httpx.Response(200,json={'model':{'diffusion_model':model}}))) as client:
                    self.runtime.http=client
                    if accepted:
                        await self.runtime.load_image(lambda:False)
                    else:
                        with self.assertRaisesRegex(RuntimeError,'Unexpected image model'):
                            await self.runtime.load_image(lambda:False)

    async def test_model_sampling_overrides_reach_renderer(self):
        png=io.BytesIO();Image.new('RGB',(256,256)).save(png,format='PNG')
        encoded=base64.b64encode(png.getvalue()).decode()
        bodies=[]
        def respond(request):
            if request.method=='POST':
                bodies.append(json.loads(request.content))
                return httpx.Response(200,json={'id':'image-job'})
            return httpx.Response(200,json={'status':'completed','result':{'images':[{'b64_json':encoded}]}})
        await self.runtime.http.aclose()
        self.runtime.http=httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(respond))
        request={'prompt':'blue triangle','width':256,'height':256,'steps':4,'seed':1}
        await self.runtime.generate(request,lambda:False,lambda **fields:None)
        self.assertEqual(bodies[-1]['sample_params']['sample_steps'],4)
        self.assertEqual(bodies[-1]['sample_params']['guidance'],{'txt_cfg':4})
        profile={'sample_method':'euler','sample_steps':8,'guidance':{'txt_cfg':1},
                 'custom_sigmas':[1.0,0.978453,0.95418,0.926626,0.89508,0.845148,0.704534,0.414568,0.0]}
        self.runtime.config['sample_params']=profile
        result=await self.runtime.generate(request,lambda:False,lambda **fields:None)
        self.assertEqual(bodies[-1]['sample_params'],profile)
        self.assertEqual(result,png.getvalue())
        self.assertEqual(request['steps'],4)

    async def test_lightning_schedule_tracks_resolution_and_includes_adapter(self):
        bodies=[]
        def respond(request):
            if request.method=='POST':
                bodies.append(json.loads(request.content))
                return httpx.Response(200,json={'id':'image-job'})
            body=bodies[-1];png=io.BytesIO()
            Image.new('RGB',(body['width'],body['height'])).save(png,format='PNG')
            return httpx.Response(200,json={'status':'completed','result':{'images':[{
                'b64_json':base64.b64encode(png.getvalue()).decode()}]}})
        await self.runtime.http.aclose()
        self.runtime.http=httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(respond))
        adapter=[{'path':'lightning.safetensors','multiplier':1.0}]
        self.runtime.config.update(sampling_profile='qwen-image-2.1-lightning',lora=adapter)
        for dimensions,expected in [((512,512),[1,0.8371702074,0.6315122048,0.3635699939,0]),
                                    ((1024,1024),[1,0.8571919774,0.6667558177,0.4000962934,0])]:
            with self.subTest(dimensions=dimensions):
                await self.runtime.generate({'prompt':'mug','width':dimensions[0],'height':dimensions[1],
                                             'steps':8,'seed':42},lambda:False,lambda **fields:None)
                params=bodies[-1]['sample_params']
                self.assertEqual(params['sample_steps'],4)
                self.assertEqual(params['sample_method'],'euler')
                self.assertEqual(params['guidance'],{'txt_cfg':1})
                self.assertEqual(bodies[-1]['lora'],adapter)
                for actual,want in zip(params['custom_sigmas'],expected,strict=True):
                    self.assertAlmostEqual(actual,want,places=8)

    async def test_configured_lightning_shift_is_used_at_every_size(self):
        bodies=[]
        def respond(request):
            if request.method=='POST':
                bodies.append(json.loads(request.content))
                return httpx.Response(200,json={'id':'image-job'})
            body=bodies[-1];png=io.BytesIO()
            Image.new('RGB',(body['width'],body['height'])).save(png,format='PNG')
            return httpx.Response(200,json={'status':'completed','result':{'images':[{
                'b64_json':base64.b64encode(png.getvalue()).decode()}]}})
        await self.runtime.http.aclose()
        self.runtime.http=httpx.AsyncClient(base_url='http://renderer',transport=httpx.MockTransport(respond))
        adapter=[{'path':'lightning.safetensors','multiplier':0.35}]
        self.runtime.config.update(sampling_profile='qwen-image-2.1-lightning',lora=adapter,lightning_shift=3.0)
        for dimensions in [(1024,1024),(1984,1984)]:
            await self.runtime.generate({'prompt':'mug','width':dimensions[0],'height':dimensions[1],
                                         'steps':8,'seed':42},lambda:False,lambda **fields:None)
            for actual,want in zip(bodies[-1]['sample_params']['custom_sigmas'],[1,0.9,0.75,0.5,0],strict=True):
                self.assertAlmostEqual(actual,want,places=8)
            self.assertEqual(bodies[-1]['lora'],adapter)

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
