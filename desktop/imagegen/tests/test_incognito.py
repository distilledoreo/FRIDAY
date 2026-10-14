import tempfile,time,unittest
from pathlib import Path
from fastapi import FastAPI,Header,Depends,HTTPException
import httpx
from desktop.workspace.workspace import Workspace
from desktop.imagegen.manager import GpuGate,ImageManager
from desktop.imagegen.integration import ImageRequest
from desktop.imagegen.incognito import enable
from desktop.imagegen.tests.test_manager import Runtime

class IncognitoTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.app=FastAPI()
        def auth(authorization:str=Header(default='')):
            if authorization!='Bearer test':raise HTTPException(401)
        self.normal=Workspace(self.root/'normal');self.runtime=Runtime();self.images=ImageManager(self.root/'normal-images',GpuGate(),self.runtime,self.normal)
        self.ram=self.root/'ram';self.ram.mkdir();self.sessions=enable(self.app,[Depends(auth)],self.normal,self.images,ImageRequest,self.ram)
        self.client=httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app),base_url='http://test',headers={'Authorization':'Bearer test'})
    async def asyncTearDown(self):
        for ident in list(self.sessions.sessions):await self.sessions.close(ident)
        await self.client.aclose();self.tmp.cleanup()
    async def test_files_never_enter_normal_workspace_and_disappear_on_exit(self):
        r=await self.client.post('/workspace/incognito');self.assertEqual(r.status_code,200);ident=r.json()['id']
        r=await self.client.post(f'/workspace/incognito/{ident}/files',content=b'private attachment',headers={'X-File-Name':'note.txt','Content-Type':'text/plain'});self.assertEqual(r.status_code,200);fid=r.json()['id']
        self.assertEqual((await self.client.get(f'/workspace/incognito/{ident}/files/{fid}')).content,b'private attachment')
        self.assertEqual(list(self.normal.files.iterdir()),[])
        root=self.sessions.get(ident).root;await self.client.delete('/workspace/incognito/'+ident)
        self.assertFalse(root.exists());self.assertEqual((await self.client.get(f'/workspace/incognito/{ident}/files/{fid}')).status_code,410)
    async def test_auth_and_session_isolation(self):
        self.assertEqual((await self.client.post('/workspace/incognito',headers={'Authorization':'wrong'})).status_code,401)
        a=(await self.client.post('/workspace/incognito')).json()['id'];b=(await self.client.post('/workspace/incognito')).json()['id']
        fid=(await self.client.post(f'/workspace/incognito/{a}/files',content=b'a')).json()['id']
        self.assertEqual((await self.client.get(f'/workspace/incognito/{b}/files/{fid}')).status_code,404)
    async def test_library_queries_page_only_the_current_ram_session(self):
        a=(await self.client.post('/workspace/incognito')).json()['id'];b=(await self.client.post('/workspace/incognito')).json()['id']
        for ident in (a,b):
            for name in ('Beta.txt','Alpha.txt','Image.png'):
                self.sessions.put_file(self.sessions.get(ident),b'fixture',name,'image/png' if name.endswith('.png') else 'text/plain')
        self.normal.put_file(b'normal','Alpha-normal.txt','text/plain')
        path=f'/workspace/incognito/{a}/files'
        legacy=(await self.client.get(path)).json();self.assertIsInstance(legacy,list);self.assertEqual(len(legacy),3)
        first=(await self.client.get(path,params={'paged':'true','kind':'documents','sort':'name','limit':1})).json()
        self.assertEqual(first['total'],2);self.assertEqual(first['files'][0]['name'],'Alpha.txt');self.assertEqual(first['next_offset'],1)
        second=(await self.client.get(path,params={'paged':'true','kind':'documents','sort':'name','limit':1,'offset':1})).json()
        self.assertEqual(second['files'][0]['name'],'Beta.txt');self.assertIsNone(second['next_offset'])
        found=(await self.client.get(path,params={'paged':'true','q':'BETA'})).json();self.assertEqual(found['total'],1)
        self.assertEqual((await self.client.get(path,params={'sort':'invalid'})).status_code,422)
        self.assertEqual((await self.client.get(path,headers={'Authorization':'wrong'})).status_code,401)
        await self.sessions.close(a);self.assertEqual((await self.client.get(path,params={'paged':'true'})).status_code,410)
    async def test_reference_resolves_only_the_current_ram_session(self):
        a=(await self.client.post('/workspace/incognito')).json()['id'];b=(await self.client.post('/workspace/incognito')).json()['id']
        fid=self.sessions.put_file(self.sessions.get(a),b'private notes','note.txt','text/plain')['id']
        ref=(await self.client.get(f'/workspace/incognito/{a}/files/{fid}/reference')).json()
        self.assertEqual(ref['text'],'private notes');self.assertEqual(ref['id'],fid)
        self.assertEqual((await self.client.get(f'/workspace/incognito/{b}/files/{fid}/reference')).status_code,404)
        self.assertEqual((await self.client.get(f'/workspace/incognito/{a}/files/{"0"*32}/reference')).status_code,404)
        await self.sessions.close(a)
        self.assertEqual((await self.client.get(f'/workspace/incognito/{a}/files/{fid}/reference')).status_code,410)
    async def test_image_prompt_is_temporary_and_cleanup_restores_fake_chat(self):
        ident=(await self.client.post('/workspace/incognito')).json()['id'];session=self.sessions.get(ident)
        response=await self.client.post(f'/workspace/incognito/{ident}/images',json={'prompt':'private image prompt'});self.assertEqual(response.status_code,200)
        for _ in range(30):
            if session.images.jobs()[0]['status']=='completed':break
            await __import__('asyncio').sleep(.05)
        self.assertEqual(session.images.jobs()[0]['status'],'completed')
        self.assertEqual(self.images.jobs(),[]);self.assertEqual(list(self.normal.files.iterdir()),[])
        self.assertNotIn('private image prompt',self.images.journal.read_text())
        await self.sessions.close(ident);self.assertFalse(session.root.exists());self.assertIn('restore_chat',self.runtime.events)
    async def test_abandoned_session_expires(self):
        ident=(await self.client.post('/workspace/incognito')).json()['id'];session=self.sessions.get(ident);session.touched=time.monotonic()-1900
        await self.sessions.expire();self.assertFalse(session.root.exists());self.assertNotIn(ident,self.sessions.sessions)
