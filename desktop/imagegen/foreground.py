"""Short foreground leases hold back background inference; no transcript storage."""
import time
from pathlib import Path
from fastapi import APIRouter,HTTPException
from pydantic import BaseModel,Field

class ForegroundLease(BaseModel):
    id:str=Field(min_length=16,max_length=64,pattern='^[a-f0-9-]+$')
    active:bool

class Maintenance(BaseModel):
    paused:bool

def enable(app,auth,gate,root):
    gate.maintenance_file=Path(root)/'background-inference.paused'
    router=APIRouter(prefix='/workspace/foreground',dependencies=auth)
    @router.post('')
    async def update(body:ForegroundLease):
        gate.foreground={k:v for k,v in gate.foreground.items() if v>time.monotonic()}
        if body.active:
            if len(gate.foreground)>=64 and body.id not in gate.foreground:raise HTTPException(429,'Too many foreground sessions')
            gate.foreground[body.id]=time.monotonic()+45
        else:gate.foreground.pop(body.id,None)
        return {'active':body.active,'expires_in_seconds':45 if body.active else 0}
    @router.post('/maintenance')
    async def maintenance(body:Maintenance):
        if body.paused:gate.maintenance_file.touch(mode=0o600)
        else:gate.maintenance_file.unlink(missing_ok=True)
        return {'background_paused':body.paused}
    @router.get('')
    async def status():return {'background_allowed':gate.background_allowed(),'maintenance_paused':gate.maintenance_paused()}
    app.include_router(router)
