import asyncio
from pathlib import Path
import tempfile
import unittest
import httpx
from fastapi import FastAPI,Depends,Header,HTTPException
from desktop.memory.integration import enable
from desktop.imagegen.manager import GpuGate

class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.app=FastAPI()
        def auth(authorization:str=Header(default='')):
            if authorization!='Bearer test':raise HTTPException(401)
        self.store=enable(self.app,[Depends(auth)],Path(self.tmp.name),None,GpuGate())
        self.client=httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app),base_url='http://test',headers={'Authorization':'Bearer test'})
    async def asyncTearDown(self):await self.client.aclose();self.tmp.cleanup()
    async def test_auth_and_recall_controls_apply_to_tools(self):
        r=await self.client.get('/workspace/memory',headers={'Authorization':'wrong'});self.assertEqual(r.status_code,401)
        r=await self.client.post('/workspace/memory/memories',json={'text':'I prefer concise answers','category':'preference'});self.assertEqual(r.status_code,200)
        await self.client.patch('/workspace/memory/settings',json={'use_memories':False,'use_history':False})
        r=await self.client.get('/workspace/memory/tool-search?kind=memory&q=concise');self.assertFalse(r.json()['enabled']);self.assertEqual(r.json()['results'],[])
        self.assertEqual((await self.client.post('/workspace/memory/context',json={'query':'concise'})).json()['context'],'')
    async def test_streamed_import_preview_and_commit(self):
        raw=b'[{"id":"one","title":"Import","current_node":"u","mapping":{"u":{"parent":null,"message":{"author":{"role":"user"},"content":{"parts":["I prefer concise answers."]}}}}}]'
        async def stream():
            for start in range(0,len(raw),17):yield raw[start:start+17]
        r=await self.client.post('/workspace/memory/imports',content=stream());self.assertEqual(r.status_code,200)
        self.assertEqual(self.store.summary()['sources'],0)
        r=await self.client.post('/workspace/memory/imports/'+r.json()['id'],json={'extract':False});self.assertEqual(r.json()['imported'],1)
        self.assertEqual(self.store.summary()['memories'],0);self.assertFalse(list(Path(self.tmp.name).glob('upload-*')))
    async def test_bounded_source_preview_and_capture_off(self):
        body={'id':'a'*32,'title':'Long source','messages':[{'role':'user','content':'x'*4000} for _ in range(30)]}
        r=await self.client.post('/workspace/memory/chats',json=body);self.assertEqual(r.status_code,200)
        source=(await self.client.get('/workspace/memory/sources/'+r.json()['id'])).json()
        self.assertTrue(source['truncated']);self.assertEqual(len(source['messages']),20);self.assertEqual(len(source['messages'][0]['content']),2000)
        await self.client.patch('/workspace/memory/settings',json={'archive_chats':False})
        body['id']='b'*32;await self.client.post('/workspace/memory/chats',json=body);self.assertEqual(self.store.summary()['sources'],1)
    async def test_legacy_migration_deduplicates(self):
        body={'memories':[{'text':'I prefer concise answers'},{'text':'I prefer concise answers'}]}
        r=await self.client.post('/workspace/memory/legacy',json=body);self.assertEqual(r.status_code,200);self.assertEqual(self.store.summary()['memories'],1)
