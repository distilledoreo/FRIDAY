import json
from pathlib import Path
from typing import Literal
import uuid

from fastapi import APIRouter
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .manager import VideoManager
from .plan import Planner, ASPECTS
from .runtime import VideoRuntime, INPUTS


class VideoInput(BaseModel):
    """A file that steers the video. Each kind is accepted once the computer's config enables it."""
    kind: Literal[tuple(INPUTS)]
    file_id: str = Field(min_length=32, max_length=32)
    at_seconds: float | None = Field(default=None, ge=0, le=60)
    strength: float | None = Field(default=None, ge=0, le=1)


class VideoLora(BaseModel):
    name: str = Field(min_length=1, max_length=120, pattern=r'^[A-Za-z0-9._ -]+$')
    strength: float = Field(default=1.0, ge=-2, le=2)


class VideoRequest(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    prompt: str = Field(min_length=1, max_length=4000)
    negative_prompt: str | None = Field(default=None, max_length=1000)
    duration_seconds: int = Field(default=4, ge=1, le=60)
    aspect: Literal[tuple(ASPECTS)] = '16:9'
    # 0 is fastest; the top level is the largest size these GPUs can render for the duration.
    quality: int = Field(default=0, ge=0, le=9)
    seed: int = Field(default=42, ge=0, le=2147483647)
    inputs: list[VideoInput] = Field(default_factory=list, max_length=8)
    loras: list[VideoLora] = Field(default_factory=list, max_length=4)


def enable(app, auth, workspace, gate, images, config_path):
    """Adds /workspace/videos. Without a config the routes still answer, saying video isn't set up."""
    router = APIRouter(prefix='/workspace/videos', dependencies=auth)
    path = Path(config_path) if config_path else None
    config = json.loads(path.read_text()) if path and path.is_file() else None
    manager = None
    if config:
        planner = Planner(config.get('planner'))
        manager = VideoManager(workspace.root / 'video-jobs', gate, VideoRuntime(config, workspace.root / 'video-jobs'),
                               planner, workspace, images, config)
        manager.rewarm = getattr(images, 'rewarm', None)
        if hasattr(images, 'video_busy'):
            images.video_busy = lambda: manager.current_job_id is not None or any(j['status'] == 'queued' for j in manager.jobs())

    def required():
        if manager is None:
            from fastapi import HTTPException
            raise HTTPException(503, 'Video generation is not set up on this computer')
        return manager

    @router.get('/capabilities')
    async def capabilities():
        if manager is None:
            return {'available': False}
        return {'available': True, 'model': 'LTX-2.5', 'fps': 24, **manager.planner.table(),
                'inputs': manager.enabled_inputs, 'loras': manager.loras(), 'audio': True}

    @router.get('/health')
    async def health():
        if manager is None:
            return {'available': False}
        missing = manager.runtime.missing()
        return {'available': not missing, 'missing_files': missing, 'phase': gate.phase,
                'maintenance_paused': gate.maintenance_paused(), 'running': manager.current_job_id}

    @router.get('')
    async def jobs():
        return required().jobs() if manager else []

    @router.post('')
    async def create(body: VideoRequest):
        return required().create(body.model_dump())

    @router.get('/{job_id}')
    async def get(job_id: str):
        return required().get(job_id)

    @router.get('/{job_id}/poster')
    async def poster(job_id: str):
        return FileResponse(required().poster(job_id), media_type='image/jpeg')

    @router.post('/{job_id}/cancel')
    async def cancel(job_id: str):
        return required().cancel(job_id)

    @router.delete('/{job_id}')
    async def delete(job_id: str):
        return required().delete(job_id)

    if manager:
        @app.on_event('startup')
        async def start():
            await manager.start()

        @app.on_event('shutdown')
        async def stop():
            await manager.stop()

    app.include_router(router)
    return manager
