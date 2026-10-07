"""Own the GPU across chat requests and durable image jobs. No client disconnect owns a swap."""
import asyncio
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import time
import uuid

import httpx
from fastapi import HTTPException
from PIL import Image
from .policy import validate, MAX_LOAD_SECONDS, MAX_RENDER_SECONDS


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as f:
        json.dump(value, f)
        f.flush()
        os.fsync(f.fileno())
    temporary.replace(path)


class GpuGate:
    def __init__(self):
        self.condition = asyncio.Condition()
        self.readers = 0
        self.exclusive = False
        self.phase = 'chat_ready'
        self.error = None

    async def acquire_chat(self):
        async with self.condition:
            if self.exclusive:
                raise HTTPException(503, {'code': 'gpu_busy', 'phase': self.phase,
                                         'message': 'GPU is generating an image or restoring chat. Retry shortly.'},
                                    headers={'Retry-After': '3', 'X-Assistant-GPU-Busy':'1'})
            self.readers += 1

    async def release_chat(self):
        async with self.condition:
            self.readers -= 1
            self.condition.notify_all()

    @contextlib.asynccontextmanager
    async def image_lease(self):
        async with self.condition:
            if self.exclusive:
                raise RuntimeError('Another GPU handoff is active')
            self.exclusive = True
            self.phase = 'waiting_for_chat'
        try:
            async with self.condition:
                await asyncio.wait_for(self.condition.wait_for(lambda: self.readers == 0), 600)
            yield
        finally:
            async with self.condition:
                # Recovery failure blocks inference until an explicit successful recovery.
                self.exclusive = self.phase == 'recovery_failed'
                self.condition.notify_all()


class GatedClient:
    """Track the entire HTTP stream, including its final close, before allowing a swap."""
    def __init__(self, client, gate):
        self.client, self.gate = client, gate

    def build_request(self, *args, **kwargs):
        return self.client.build_request(*args, **kwargs)

    async def get(self, path, **kwargs):
        if path == '/health':
            return await self.client.get(path, **kwargs)
        return await self.wait_send(self.build_request('GET', path, **kwargs))

    async def post(self, path, **kwargs):
        return await self.wait_send(self.build_request('POST', path, **kwargs))

    async def wait_send(self, request):
        deadline = time.monotonic() + 600
        while True:
            try:
                return await self.send(request)
            except HTTPException as error:
                if error.status_code != 503 or self.gate.phase == "recovery_failed" or time.monotonic() >= deadline:
                    raise
                await asyncio.sleep(1)

    async def send(self, request, stream=False, **kwargs):
        await self.gate.acquire_chat()
        try:
            response = await self.client.send(request, stream=stream, **kwargs)
        except BaseException:
            await self.gate.release_chat()
            raise
        if not stream:
            await self.gate.release_chat()
            return response
        original_close = response.aclose
        released = False
        async def close():
            nonlocal released
            try:
                await original_close()
            finally:
                if not released:
                    released = True
                    await self.gate.release_chat()
        response.aclose = close
        return response


class SystemRuntime:
    IMAGE_UNIT = 'assistant-qwen-image.service'

    def __init__(self, config, raw_llm):
        self.config = config
        self.raw_llm = raw_llm
        self.http = httpx.AsyncClient(base_url='http://127.0.0.1:8192', timeout=30)

    async def command(self, *args, timeout=65):
        proc = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout)
        except BaseException:
            proc.kill()
            await proc.wait()
            raise
        if proc.returncode:
            raise RuntimeError(f'{args[0]} failed: {err.decode()[-500:]}')
        return out.decode()

    async def preflight(self):
        def verify():
            c = self.config
            for item in c['files'] + [{'path': c['command'][0], 'sha256': c['binary_sha256']}]:
                p = Path(item['path'])
                if not p.is_file():
                    raise RuntimeError(f'Image runtime file is missing: {p.name}')
                if 'bytes' in item and p.stat().st_size != item['bytes']:
                    raise RuntimeError(f'Image model size mismatch: {p.name}')
            # Config was generated after checksum verification; recheck when metadata changes.
            for item in c['files']:
                stat = Path(item['path']).stat()
                if stat.st_size != item['verified_size'] or stat.st_mtime_ns != item['verified_mtime_ns']:
                    raise RuntimeError('Image model changed since verification. Reverify before using it.')
            p = Path(c['command'][0]); stat = p.stat()
            if stat.st_size != c['binary_size'] or stat.st_mtime_ns != c['binary_mtime_ns']:
                raise RuntimeError('Image runtime changed since verification. Reverify before using it.')
        await asyncio.to_thread(verify)
        # Never stop a directly connected client outside our gateway while it is generating.
        end = time.monotonic() + 600
        while True:
            r = await self.raw_llm.get('/slots', timeout=5)
            r.raise_for_status()
            if not any(s.get('is_processing') for s in r.json()):
                break
            if time.monotonic() > end:
                raise RuntimeError('Chat model is still busy; image generation was not started')
            await asyncio.sleep(1)
        proc = await asyncio.create_subprocess_exec('systemctl', '--user', 'is-active', '--quiet', self.config['llm_unit'])
        if await proc.wait() != 0:
            raise RuntimeError('Chat service is not active; restore it before requesting images')

    async def unload_chat(self):
        await self.command('systemctl', '--user', 'stop', self.config['llm_unit'])
        gpu = await self.command('nvidia-smi', '--query-compute-apps=pid,used_memory', '--format=csv,noheader,nounits')
        if any(line.strip() for line in gpu.splitlines()):
            raise RuntimeError('Another GPU compute process is active; image model was not started')

    async def load_image(self, cancelled):
        command = self.config['command']
        await self.command('systemd-run', '--user', '--unit=assistant-qwen-image', '--collect',
                           '--property=KillMode=control-group', '--property=TimeoutStopSec=30',
                           '--property=BindsTo=assistant-api.service', '--property=After=assistant-api.service', *command)
        end = time.monotonic() + MAX_LOAD_SECONDS
        while time.monotonic() < end:
            if cancelled():
                raise asyncio.CancelledError()
            try:
                r = await self.http.get('/sdcpp/v1/capabilities', timeout=3)
                if r.status_code == 200:
                    model = r.json().get('model', {})
                    if 'qwen_image_2.1-Q8_0' not in str(model):
                        raise RuntimeError('Unexpected image model on the managed port')
                    return
            except httpx.HTTPError:
                pass
            await asyncio.sleep(1)
        raise RuntimeError('Qwen Image did not become ready within 90 seconds')

    async def generate(self, request, cancelled, update):
        prompt = request['prompt']
        if request.get('transparent'):
            prompt = 'This is an RGBA image with transparency. ' + prompt + ' The image has alpha channel and the background is transparent.'
        body = {'prompt': prompt, 'width': request['width'], 'height': request['height'],
                'seed': request['seed'], 'batch_count': 1, 'output_format': 'png',
                'sample_params': {'sample_method': 'euler', 'sample_steps': request['steps'],
                                  'guidance': {'txt_cfg': 4}}}
        if request.get('references'):
            body['ref_images'] = request['references']
            body['auto_resize_ref_image'] = False
        r = await self.http.post('/sdcpp/v1/img_gen', json=body)
        r.raise_for_status()
        remote_id = r.json()['id']
        update(remote_id=remote_id)
        end = time.monotonic() + MAX_RENDER_SECONDS
        while time.monotonic() < end:
            if cancelled():
                raise asyncio.CancelledError()
            r = await self.http.get(f'/sdcpp/v1/jobs/{remote_id}')
            r.raise_for_status()
            state = r.json()
            if state['status'] == 'completed':
                raw = base64.b64decode(state['result']['images'][0]['b64_json'], validate=True)
                with Image.open(io.BytesIO(raw)) as image:
                    if image.size != (request['width'], request['height']):
                        raise RuntimeError('Image output has unexpected dimensions')
                    image.verify()
                return raw
            if state['status'] in ('failed', 'cancelled'):
                raise RuntimeError(str(state.get('error') or state['status'])[:500])
            update(progress=state.get('progress'))
            await asyncio.sleep(1)
        raise RuntimeError('Image generation exceeded the 150-second render limit')

    async def unload_image(self):
        # This unit is owned exclusively by this manager, never a prior panorama server.
        state = (await self.command('systemctl', '--user', 'show', self.IMAGE_UNIT, '--property=LoadState', '--value')).strip()
        if state != 'not-found':
            await self.command('systemctl', '--user', 'stop', self.IMAGE_UNIT)

    async def restore_chat(self):
        await self.command('systemctl', '--user', 'start', self.config['llm_unit'])
        end = time.monotonic() + 240
        while time.monotonic() < end:
            try:
                if (await self.raw_llm.get('/health', timeout=3)).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            await asyncio.sleep(1)
        raise RuntimeError('Chat service did not reload within four minutes')


class ImageManager:
    TERMINAL = {'completed', 'failed', 'cancelled'}

    def __init__(self, root, gate, runtime, store):
        self.root = Path(root); self.root.mkdir(parents=True, exist_ok=True)
        self.gate, self.runtime, self.store = gate, runtime, store
        self.worker_task = None
        self.current = None
        self.stopping = False
        self.journal = self.root / 'gpu-state.json'

    def path(self, job_id):
        try:
            job_id = uuid.UUID(job_id).hex
        except ValueError:
            raise HTTPException(400, 'Invalid image job id')
        return self.root / f'{job_id}.json'

    def get(self, job_id):
        p = self.path(job_id)
        if not p.exists():
            raise HTTPException(404, 'Image job not found')
        return json.loads(p.read_text())

    def jobs(self):
        return sorted((json.loads(p.read_text()) for p in self.root.glob('*.json') if p.name != self.journal.name), key=lambda j:j['created'], reverse=True)[:100]

    def update(self, job_id, **fields):
        job = self.get(job_id); job.update(fields, updated=time.time()); atomic(self.path(job_id), job)
        return job

    def create(self, request):
        try:
            validate(request)
            for file_id in request.get('reference_file_ids', []):
                if not isinstance(file_id, str) or len(file_id) != 32 or any(c not in '0123456789abcdef' for c in file_id):
                    raise ValueError('Invalid reference image id')
                if not self.store.file(file_id)['mime'].startswith('image/'):
                    raise ValueError('References must be image files')
        except ValueError as e:
            raise HTTPException(400, str(e))
        p = self.path(request['id'])
        if p.exists():
            old = self.get(request['id'])
            if old['request'] != request:
                raise HTTPException(409, 'Image job id already has different parameters')
            return old
        if sum(j['status'] not in self.TERMINAL for j in self.jobs()) >= 4:
            raise HTTPException(429, 'Image queue is full')
        if len(self.jobs()) >= 100:
            raise HTTPException(429, 'Remove finished image jobs before creating more')
        job = {'id': uuid.UUID(request['id']).hex, 'request': request, 'prompt': request['prompt'],
               'status':'queued', 'phase':'queued', 'created':time.time(), 'updated':time.time(),
               'cancel_requested':False, 'artifact':None, 'error':None}
        atomic(p, job)
        return job

    def cancel(self, job_id):
        job = self.get(job_id)
        if job['status'] in self.TERMINAL:
            return job
        return self.update(job_id, cancel_requested=True)

    async def recover(self):
        if self.journal.exists() and json.loads(self.journal.read_text()).get('borrowed'):
            self.gate.exclusive = True
            self.gate.phase = 'restoring_chat'
            try:
                await self.runtime.unload_image()
                await self.runtime.restore_chat()
                atomic(self.journal, {'borrowed':False})
            except Exception as e:
                self.gate.phase = 'recovery_failed'; self.gate.error = str(e)
                raise
            self.gate.exclusive = False; self.gate.phase = 'chat_ready'
        for job in self.jobs():
            if job['status'] == 'running':
                self.update(job['id'], status='failed', phase='interrupted', error='Server restarted during generation; chat restored. Start a new image request to retry.')

    async def execute(self, job):
        job_id = job['id']
        def update(**fields):
            if 'phase' in fields:
                self.gate.phase = fields['phase']
            return self.update(job_id, **fields)
        def cancelled():
            return self.get(job_id)['cancel_requested']
        if cancelled():
            update(status='cancelled', phase='cancelled'); return
        result = None; error = None; borrowed = False; was_cancelled = False
        async with self.gate.image_lease():
            try:
                update(status='running', phase='preparing')
                await self.runtime.preflight()
                if cancelled():
                    raise asyncio.CancelledError()
                # Persist before the stop command, so a crash always triggers restoration.
                atomic(self.journal, {'borrowed':True, 'job_id':job_id})
                borrowed = True
                update(phase='unloading_chat')
                await self.runtime.unload_chat()
                update(phase='loading_image')
                await self.runtime.load_image(cancelled)
                update(phase='generating')
                request = dict(job['request'])
                def references():
                    encoded = []
                    for file_id in request.get('reference_file_ids', []):
                        with Image.open(self.store.files / file_id) as image:
                            if image.width * image.height > 32_000_000:
                                raise ValueError('Reference image is too large')
                            image = image.convert('RGB'); image.thumbnail((512, 512))
                            out = io.BytesIO(); image.save(out, format='PNG')
                            encoded.append(base64.b64encode(out.getvalue()).decode())
                    return encoded
                request['references'] = await asyncio.to_thread(references)
                result = await asyncio.wait_for(self.runtime.generate(request, cancelled, update), MAX_RENDER_SECONDS + 5)
                # Save the file before reloading chat; only advertise completion after restoration.
                artifact = await asyncio.to_thread(self.store.put_file, result, f'qwen-image-{job_id[:8]}.png', 'image/png')
                update(artifact=artifact)
            except asyncio.CancelledError:
                was_cancelled = True
            except Exception as e:
                error = str(e)[:1000]
            finally:
                if borrowed:
                    update(phase='restoring_chat')
                    try:
                        await self.runtime.unload_image()
                        await self.runtime.restore_chat()
                        atomic(self.journal, {'borrowed':False})
                    except Exception as e:
                        self.gate.phase = 'recovery_failed'; self.gate.error = str(e)
                        update(status='failed', phase='recovery_failed', error=f'Chat restoration failed: {e}')
                        return
                self.gate.phase = 'chat_ready'
                update(status='cancelled' if was_cancelled else 'failed' if error else 'completed',
                       phase='cancelled' if was_cancelled else 'failed' if error else 'completed', error=error)
                self.gate.phase = 'chat_ready'

    async def worker(self):
        while not self.stopping:
            job = next((j for j in reversed(self.jobs()) if j['status'] == 'queued'), None)
            if job and self.gate.phase != 'recovery_failed':
                self.current = asyncio.create_task(self.execute(job))
                try:
                    await self.current
                except Exception as error:
                    self.update(job['id'], status='failed', phase='recovery_failed' if self.gate.phase == 'recovery_failed' else 'failed', error=str(error)[:500])
                finally:
                    self.current = None
            await asyncio.sleep(.5)

    async def start(self):
        await self.recover()
        self.worker_task = asyncio.create_task(self.worker())

    async def stop(self):
        self.stopping = True
        if self.current:
            self.current.cancel()
            await self.current  # Restoration must finish before the API exits.
        if self.worker_task:
            self.worker_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.worker_task
