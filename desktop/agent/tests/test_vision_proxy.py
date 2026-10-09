import json
import tempfile
import time
import unittest
from types import SimpleNamespace

import httpx
from fastapi import FastAPI, HTTPException

from desktop.agent.model_ranking import ModelRanking
from desktop.agent.vision_proxy import install, vision_payload


class VisionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        ranking = ModelRanking(self.tmp.name)
        ranking.catalog_checked = time.time()
        ranking.catalog = {name:{'id':name,'context':65536,'input_modalities':inputs}
                           for name,inputs in (('v/best:free',['text','image']),('v/next:free',['text','image']),('v/text',['text']))}
        self.settings = {'enabled':True,'computer_use':True}
        self.agent = SimpleNamespace(password='private-fixture',ranking=ranking,vision_chain=['v/best:free','v/next:free'],settings=lambda:self.settings)

    async def asyncTearDown(self): self.tmp.cleanup()

    def test_payload_forces_ranked_free_vision_and_cannot_accept_paid_override(self):
        body = vision_payload({'model':'v/best:free','models':['paid/model'],'messages':[],
                               'provider':{'max_price':{'prompt':10}},'plugins':[{'id':'web'}]}, self.agent)
        self.assertEqual(body['models'], ['v/best:free','v/next:free'])
        self.assertEqual(body['provider'], {'max_price':{'prompt':0,'completion':0}})
        self.assertNotIn('plugins', body)
        for name in ('paid/model','v/text','openrouter/free'):
            with self.assertRaises(HTTPException): vision_payload({'model':name,'messages':[]}, self.agent)
        self.agent.ranking.catalog.pop('v/best:free')
        self.assertEqual(vision_payload({'model':'v/best:free','messages':[]}, self.agent)['model'], 'v/next:free')

    async def test_private_authenticated_stream_has_fixed_target_and_no_credential_echo(self):
        requests = []
        async def upstream(request):
            requests.append(request)
            return httpx.Response(200, content=b'data: [DONE]\n\n', headers={'Content-Type':'text/event-stream'})
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        app = FastAPI(); install(app,self.agent,client_factory=lambda:cloud,key_loader=lambda:'remote-secret')
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app,client=('127.0.0.1',1234)),base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions',json={})).status_code,401)
            response = await client.post('/workspace/pc/vision/v1/chat/completions',headers={'Authorization':'Bearer private-fixture'},json={'model':'v/best:free','messages':[],'stream':True})
            self.assertEqual(response.status_code,200)
            self.assertEqual(response.text,'data: [DONE]\n\n')
            self.assertNotIn('secret',response.text)
        self.assertTrue(cloud.is_closed)
        self.assertEqual(str(requests[0].url),'https://openrouter.ai/api/v1/chat/completions')
        self.assertEqual(requests[0].headers['Authorization'],'Bearer remote-secret')
        self.assertEqual(json.loads(requests[0].content)['models'], ['v/best:free','v/next:free'])

    async def test_non_loopback_and_disabled_desktop_never_contact_cloud(self):
        app = FastAPI(); install(app,self.agent,key_loader=lambda: (_ for _ in ()).throw(AssertionError('Cloud accessed')))
        headers = {'Authorization':'Bearer private-fixture'}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app,client=('192.0.2.1',1234)),base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions',headers=headers,json={})).status_code,401)
        self.settings['computer_use'] = False
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app,client=('127.0.0.1',1234)),base_url='http://owned') as client:
            self.assertEqual((await client.post('/workspace/pc/vision/v1/chat/completions',headers=headers,json={})).status_code,403)

    async def test_exhausted_vision_models_return_terminal_redacted_error_and_close_stream(self):
        async def upstream(request): return httpx.Response(429,json={'error':'remote-secret and upstream detail'})
        cloud = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
        app = FastAPI(); install(app,self.agent,client_factory=lambda:cloud,key_loader=lambda:'remote-secret')
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app,client=('127.0.0.1',1234)),base_url='http://owned') as client:
            r = await client.post('/workspace/pc/vision/v1/chat/completions',headers={'Authorization':'Bearer private-fixture'},json={'model':'v/best:free','messages':[]})
        self.assertEqual(r.status_code,400)
        self.assertNotIn('remote-secret',r.text)
        self.assertTrue(cloud.is_closed)
