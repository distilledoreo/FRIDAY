"""Queue video jobs and hand the GPUs over for each: the image card alone (chat keeps running), or both cards
(chat stops and is restored afterwards, with a journal so a crash still brings it back)."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import shutil
import time
import uuid

from fastapi import HTTPException

from .runtime import INPUTS

MAX_JOBS = 100
MAX_QUEUED = 3
MAX_VIDEO_BYTES = 300 * 1024 * 1024


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as f:
        json.dump(value, f)
        f.flush()
        os.fsync(f.fileno())
    temporary.replace(path)


class VideoManager:
    TERMINAL = {'completed', 'failed', 'cancelled'}

    def __init__(self, root, gate, runtime, planner, store, images, config):
        self.root = Path(root); self.root.mkdir(parents=True, exist_ok=True)
        self.work = self.root / 'work'
        self.gate, self.runtime, self.planner, self.store = gate, runtime, planner, store
        # The image service shares the image card and owns chat stop/restore (its runtime knows the chat unit).
        self.images = images
        self.config = config
        self.enabled_inputs = [k for k in config.get('inputs', []) if k in INPUTS]
        self.journal = self.root / 'gpu-state.json'
        self.worker_task = None
        self.current = None
        self.current_job_id = None
        self.stopping = False
        self.rewarm = None

    # ---- jobs ----
    def path(self, job_id):
        try:
            job_id = uuid.UUID(job_id).hex
        except (ValueError, AttributeError, TypeError):
            raise HTTPException(400, 'Invalid video job id')
        return self.root / f'{job_id}.json'

    def get(self, job_id):
        p = self.path(job_id)
        if not p.exists():
            raise HTTPException(404, 'Video job not found')
        return json.loads(p.read_text())

    def jobs(self):
        return sorted((json.loads(p.read_text()) for p in self.root.glob('*.json') if p.name != self.journal.name),
                      key=lambda j: j['created'], reverse=True)[:MAX_JOBS]

    def update(self, job_id, **fields):
        job = self.get(job_id); job.update(fields, updated=time.time()); atomic(self.path(job_id), job)
        return job

    def validate(self, request):
        if not request['prompt'].strip():
            raise ValueError('Describe the video to generate')
        inputs = request.get('inputs', [])
        for item in inputs:
            kind, name = item['kind'], item['kind'].replace('_', ' ').capitalize()
            if kind not in self.enabled_inputs:
                raise ValueError(f'{name} inputs are not available on this computer yet')
            file_id = item['file_id']
            if len(file_id) != 32 or any(c not in '0123456789abcdef' for c in file_id):
                raise ValueError('Invalid input file id')
            types, limit = INPUTS[kind]
            if not self.store.file(file_id)['mime'].startswith(types):
                raise ValueError(f'{name} needs {" or ".join(("an " if t[0] in "aeiou" else "a ") + t.rstrip("/") for t in types)} file')
            if sum(other['kind'] == kind for other in inputs) > limit:
                raise ValueError(f'Use at most {limit} {name.lower()} input{"s" if limit > 1 else ""}')
            if kind == 'keyframe' and not (item.get('at_seconds') is not None and 0 < item['at_seconds'] < request['duration_seconds']):
                raise ValueError('Place each keyframe between the start and the end of the video')
        kinds = {item['kind'] for item in inputs}
        if {'source_video', 'start_image'} <= kinds:
            raise ValueError('A video continues either a source video or a start image, not both')
        extra = sum(item['kind'] in ('keyframe', 'end_image') for item in inputs)
        plan = self.planner.resolve(request['duration_seconds'], request['aspect'], request['quality'], extra)
        if request.get('loras'):
            available = set(self.loras())
            for lora in request['loras']:
                if lora['name'] not in available:
                    raise ValueError(f'No LoRA named {lora["name"]} on this computer')
        return plan

    def loras(self):
        directory = self.config.get('lora_dir')
        if not directory or not Path(directory).is_dir():
            return []
        return sorted(p.stem for p in Path(directory).iterdir() if p.suffix in ('.safetensors', '.gguf'))

    def create(self, request):
        if self.gate.maintenance_paused():
            raise HTTPException(503, 'Video generation is paused during model experiments. Try again when tests finish.')
        try:
            plan = self.validate(request)
        except ValueError as e:
            raise HTTPException(400, str(e))
        p = self.path(request['id'])
        if p.exists():
            old = self.get(request['id'])
            if old['request'] != request:
                raise HTTPException(409, 'Video job id already has different parameters')
            return old
        jobs = self.jobs()
        if sum(j['status'] not in self.TERMINAL for j in jobs) >= MAX_QUEUED:
            raise HTTPException(429, 'Video queue is full. Wait for one to finish.')
        if len(jobs) >= MAX_JOBS:
            raise HTTPException(429, 'Remove finished videos before creating more')
        job = {'id': uuid.UUID(request['id']).hex, 'request': request, 'prompt': request['prompt'], 'plan': plan,
               'status': 'queued', 'phase': 'queued', 'created': time.time(), 'updated': time.time(),
               'cancel_requested': False, 'artifact': None, 'poster': False, 'error': None}
        atomic(p, job)
        return job

    def cancel(self, job_id):
        """A job that hasn't started is cancelled at once; a running one stops at its next check."""
        job = self.get(job_id)
        if job['status'] in self.TERMINAL:
            return job
        if job['status'] == 'queued':
            return self.update(job_id, cancel_requested=True, status='cancelled', phase='cancelled')
        return self.update(job_id, cancel_requested=True)

    def delete(self, job_id):
        job = self.get(job_id)
        if job['status'] not in self.TERMINAL:
            raise HTTPException(409, 'Cancel the video and wait for it to stop first')
        shutil.rmtree(self.work / job['id'], ignore_errors=True)
        self.path(job_id).unlink()
        return {'removed': True}

    def poster(self, job_id):
        job = self.get(job_id)
        path = self.work / job['id'] / 'poster.jpg'
        if not job.get('poster') or not path.is_file():
            raise HTTPException(404, 'This video has no poster')
        return path

    # ---- GPU handoff ----
    async def asr(self, action):
        unit = self.config.get('asr_unit')
        if unit:
            # Speech recognition falls back to the CPU while its GPU service is stopped.
            await self.runtime.command('systemctl', '--user', action, unit, check=False)

    async def recover(self):
        async with self.gate.image_lock:
            await self.runtime.cleanup()
            state = json.loads(self.journal.read_text()) if self.journal.exists() else {}
            if state.get('borrowed'):
                self.gate.exclusive = True
                self.gate.phase = 'restoring_chat'
                try:
                    await self.images.runtime.restore_chat()
                    atomic(self.journal, {'borrowed': False})
                except Exception as e:
                    self.gate.phase = 'recovery_failed'; self.gate.error = str(e)
                    raise
                self.gate.exclusive = False; self.gate.phase = 'chat_ready'
            if state.get('borrowed') or state.get('card'):
                await self.asr('start')
                atomic(self.journal, {'borrowed': False})
        for job in self.jobs():
            if job['status'] == 'running':
                self.update(job['id'], status='failed', phase='interrupted',
                            error='The server restarted during this video. Start it again to retry.')

    @contextlib.asynccontextmanager
    async def handoff(self, plan, update):
        """Free the cards a placement needs, and give them back afterwards whatever happens."""
        dual = plan['placement'] == 'dual'
        lease = self.gate.shared_image_lease() if dual else contextlib.nullcontext()
        async with lease:
            atomic(self.journal, {'borrowed': dual, 'card': True})
            try:
                update(phase='unloading_chat' if dual else 'pausing_images')
                await self.images.runtime.unload_image()
                if dual:
                    self.gate.phase = 'video'
                    self.gate.video_until = time.time() + plan['chat_paused_seconds']
                    await self.images.runtime.unload_chat()
                else:
                    await self.asr('stop')
                yield
            finally:
                try:
                    if dual:
                        update(phase='restoring_chat')
                        self.gate.phase = 'restoring_chat'
                        try:
                            await self.images.runtime.restore_chat()
                        except Exception as e:
                            self.gate.phase = 'recovery_failed'; self.gate.error = str(e)
                            raise RuntimeError(f'Chat restoration failed: {e}')
                        self.gate.phase = 'chat_ready'
                    await self.asr('start')
                    atomic(self.journal, {'borrowed': False})
                finally:
                    self.gate.video_until = None

    async def execute(self, job):
        job_id, plan = job['id'], job['plan']
        def update(**fields):
            return self.update(job_id, **fields)
        def cancelled():
            return self.stopping or self.get(job_id)['cancel_requested']
        if cancelled():
            update(status='cancelled', phase='cancelled'); return
        error = None; was_cancelled = False
        work = self.work / job_id
        async with self.gate.image_lock:
            if cancelled():
                # Cancelled while it waited for the GPU (cancel() already marked it): nothing ran.
                update(status='cancelled', phase='cancelled'); return
            try:
                update(status='running', phase='preparing', started=time.time())
                await self.runtime.preflight()
                inputs = [(item, self.store.files / item['file_id']) for item in job['request'].get('inputs', [])]
                async with self.handoff(plan, update):
                    if cancelled():
                        raise asyncio.CancelledError()
                    source = await self.runtime.render(job['request'], plan, work, cancelled, update, inputs)
                update(phase='encoding', progress=.98)
                video, poster, duration = await self.runtime.finish(source, work, plan)
                data = await asyncio.to_thread(video.read_bytes)
                artifact = await asyncio.to_thread(self.store.put_file, data, f'ltx-video-{job_id[:8]}.mp4', 'video/mp4', MAX_VIDEO_BYTES)
                source.unlink(missing_ok=True); video.unlink(missing_ok=True)
                update(artifact=artifact, poster=poster.is_file(), seconds=round(duration, 2))
            except asyncio.CancelledError:
                was_cancelled = True
            except Exception as e:
                error = (str(e).strip() or type(e).__name__)[:1000]
            finally:
                update(status='cancelled' if was_cancelled else 'failed' if error else 'completed',
                       phase='cancelled' if was_cancelled else 'failed' if error else 'completed',
                       error=error, finished=time.time())
        if self.rewarm and not self.stopping and not any(j['status'] == 'queued' for j in self.jobs()):
            # Bring the resident image model back now rather than on the next image request; with more videos
            # queued it would only be unloaded again, so it waits for the last one.
            asyncio.create_task(self.rewarm())

    async def worker(self):
        while not self.stopping:
            job = next((j for j in reversed(self.jobs()) if j['status'] == 'queued'), None)
            if job and self.gate.phase != 'recovery_failed' and not self.gate.maintenance_paused():
                self.current_job_id = job['id']
                self.current = asyncio.create_task(self.execute(job))
                try:
                    await self.current
                except Exception as error:
                    self.update(job['id'], status='failed', phase='failed', error=str(error)[:500])
                finally:
                    self.current = None
                    self.current_job_id = None
            await asyncio.sleep(1)

    async def start(self):
        await self.recover()
        self.worker_task = asyncio.create_task(self.worker())

    async def stop(self):
        self.stopping = True
        if self.current:
            # execute() sees `stopping`, stops the renderer and restores chat before the API exits.
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await asyncio.wait_for(asyncio.shield(self.current), 300)
        if self.worker_task:
            self.worker_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.worker_task
