import json
import os
from pathlib import Path
import uuid

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from .manager import GpuGate, GatedClient, ImageManager, SystemRuntime


class ImageRequest(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    prompt: str = Field(min_length=1, max_length=4000)
    width: int = Field(default=768, ge=256, le=2000)
    height: int = Field(default=768, ge=256, le=2000)
    steps: int = Field(default=8, ge=4, le=12)
    reference_file_ids: list[str] = Field(default_factory=list, max_length=2)
    transparent: bool = False
    seed: int = Field(default=42, ge=0, le=2147483647)


def prepare(raw_llm):
    gate = GpuGate()
    return GatedClient(raw_llm, gate), gate


def enable(app, auth, workspace, raw_llm, gate, config_path):
    config = json.loads(Path(config_path).read_text())
    manager = ImageManager(workspace.root / 'image-jobs', gate, SystemRuntime(config, raw_llm), workspace)
    router = APIRouter(prefix='/workspace/images', dependencies=auth)

    @router.get('/health')
    async def health():
        return {'phase':gate.phase, 'error':gate.error, 'active_chat_requests':gate.readers, 'maintenance_paused':gate.maintenance_paused(), 'model':'Qwen Image 2.1'}

    @router.get('')
    async def jobs():
        return manager.jobs()

    @router.post('')
    async def create(body: ImageRequest):
        return manager.create(body.model_dump() if hasattr(body, 'model_dump') else body.dict())

    @router.get('/{job_id}')
    async def get(job_id: str):
        return manager.get(job_id)

    @router.post('/{job_id}/cancel')
    async def cancel(job_id: str):
        return manager.cancel(job_id)

    @router.delete('/{job_id}')
    async def delete(job_id: str):
        job = manager.get(job_id)
        if job['status'] not in manager.TERMINAL:
            raise HTTPException(409, 'Cancel the image job and wait for chat restoration first')
        manager.path(job_id).unlink()
        return {'removed':True}

    @app.on_event('startup')
    async def start():
        await manager.start()

    @app.on_event('shutdown')
    async def stop():
        await manager.stop()

    app.include_router(router)
    from .foreground import enable as enable_foreground
    enable_foreground(app, auth, gate, workspace.root)
    from .incognito import enable as enable_incognito
    manager.incognito = enable_incognito(app, auth, workspace, manager, ImageRequest)
    return manager
