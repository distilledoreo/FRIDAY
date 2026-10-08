"""Authenticated RAM-backed artifacts and image jobs; never touch the normal archive."""
import asyncio
import contextlib
from dataclasses import dataclass
from pathlib import Path
import shutil
import tempfile
import time
import uuid
from fastapi import APIRouter,HTTPException,Request
from fastapi.responses import FileResponse
from pydantic import BaseModel,Field
from .manager import ImageManager

@dataclass
class Session:
    root:Path
    store:object
    images:object
    touched:float

class EphemeralSessions:
    def __init__(self,workspace,images,ram_root=Path('/dev/shm'),ttl=1800):
        self.workspace=workspace;self.images=images;self.ram_root=Path(ram_root);self.ttl=ttl;self.sessions={}
    async def create(self):
        if len(self.sessions)>=4:raise HTTPException(429,'Close an existing incognito chat first')
        if not self.ram_root.is_dir():raise HTTPException(503,'RAM-backed incognito storage unavailable')
        if shutil.disk_usage(self.ram_root).free<128*1024**2:raise HTTPException(507,'Not enough RAM storage for incognito')
        ident=uuid.uuid4().hex;root=Path(tempfile.mkdtemp(prefix='friday-incognito-',dir=self.ram_root));root.chmod(0o700)
        store=type(self.workspace)(root)
        images=ImageManager(root/'image-jobs',self.images.gate,self.images.runtime,store)
        # Only GPU recovery metadata lives on disk. Prompts/artifacts remain on RAM storage.
        images.journal=self.images.journal
        images.worker_task=asyncio.create_task(images.worker())
        self.sessions[ident]=Session(root,store,images,time.monotonic());return ident
    def get(self,ident):
        session=self.sessions.get(ident)
        if not session:raise HTTPException(410,'Incognito session ended; start a new chat')
        session.touched=time.monotonic();return session
    async def close(self,ident):
        session=self.sessions.pop(ident,None)
        if not session:return
        try:await session.images.stop()
        finally:shutil.rmtree(session.root,ignore_errors=True)
    async def expire(self):
        for ident,session in list(self.sessions.items()):
            if time.monotonic()-session.touched>self.ttl:await self.close(ident)
    def put_file(self,session,data,name,mime=None):
        with session.store.db() as db:total=db.execute('SELECT COALESCE(SUM(size),0) FROM files').fetchone()[0]
        if total+len(data)>128*1024**2:raise HTTPException(507,'Incognito file limit reached')
        return session.store.put_file(data,name,mime)

class TextFile(BaseModel):
    name:str=Field(max_length=160)
    content:str=Field(max_length=4*1024**2)

def enable(app,auth,workspace,images,image_request,ram_root=Path('/dev/shm')):
    # Refuse persistent-disk fallback in production.
    if str(ram_root)=='/dev/shm':
        mounts=Path('/proc/mounts').read_text().splitlines()
        if not any(line.split()[1:3]==['/dev/shm','tmpfs'] for line in mounts):raise RuntimeError('Incognito requires tmpfs /dev/shm')
    sessions=EphemeralSessions(workspace,images,ram_root);router=APIRouter(prefix='/workspace/incognito',dependencies=auth);worker=None
    @router.post('')
    async def create():return {'id':await sessions.create(),'storage':'RAM','expires_after_idle_seconds':sessions.ttl}
    @router.delete('/{ident}')
    async def close(ident:str):await sessions.close(ident);return {'deleted':True}
    @router.get('/{ident}/files')
    def files(ident:str):
        with sessions.get(ident).store.db() as db:return [dict(r) for r in db.execute('SELECT * FROM files ORDER BY created DESC')]
    @router.post('/{ident}/files')
    async def upload(ident:str,request:Request):
        session=sessions.get(ident);data=bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data)>25*1024**2:raise HTTPException(413,'File exceeds 25 MB')
        return sessions.put_file(session,bytes(data),request.headers.get('x-file-name','file'),request.headers.get('content-type'))
    @router.get('/{ident}/files/{file_id}')
    def download(ident:str,file_id:str):
        session=sessions.get(ident);info=session.store.file(file_id)
        return FileResponse(session.store.files/info['id'],media_type=info['mime'],filename=info['name'])
    @router.post('/{ident}/create-file')
    def text_file(ident:str,body:TextFile):
        if Path(body.name).suffix.lower() not in {'.txt','.md','.csv','.tsv','.json','.svg','.html'}:raise HTTPException(400,'Unsupported text file format')
        return sessions.put_file(sessions.get(ident),body.content.encode(),body.name)
    @router.post('/{ident}/images')
    async def image(ident:str,body:image_request):return sessions.get(ident).images.create(body.model_dump() if hasattr(body,'model_dump') else body.dict())
    @router.get('/{ident}/images')
    async def jobs(ident:str):return sessions.get(ident).images.jobs()
    @router.get('/{ident}/images/{job_id}')
    async def job(ident:str,job_id:str):return sessions.get(ident).images.get(job_id)
    @router.post('/{ident}/images/{job_id}/cancel')
    async def cancel(ident:str,job_id:str):return sessions.get(ident).images.cancel(job_id)
    async def sweep():
        while True:await asyncio.sleep(60);await sessions.expire()
    @app.on_event('startup')
    async def start():
        nonlocal worker
        # API restart already restores chat through the ordinary image recovery journal.
        for old in sessions.ram_root.glob('friday-incognito-*'):
            if old.is_dir() and old.stat().st_uid==__import__('os').getuid():shutil.rmtree(old,ignore_errors=True)
        worker=asyncio.create_task(sweep())
    @app.on_event('shutdown')
    async def stop():
        if worker:
            worker.cancel()
            with contextlib.suppress(asyncio.CancelledError):await worker
        for ident in list(sessions.sessions):await sessions.close(ident)
    app.include_router(router);return sessions
