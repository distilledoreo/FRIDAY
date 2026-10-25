"""Run one LTX video render in an owned systemd unit, then make a phone-playable MP4 and a poster frame."""
import asyncio
import json
import os
from pathlib import Path
import re
import time

from .spawn import spawn_args

VIDEO_UNIT = 'assistant-ltx-video.service'
WORKER_UNIT = 'assistant-ltx-rpc.service'
# The distilled model's eight-step schedule.
SIGMAS = [1.0, 0.99375, 0.9875, 0.98125, 0.975, 0.909375, 0.725, 0.421875, 0.0]
NEGATIVE = 'worst quality, low quality, blurry, distorted, artifacts'
STEP = re.compile(r'(\d+)/(\d+) - [\d.]+s/it')
# How each optional input reaches the renderer once it's enabled in the config. Inputs are files from the workspace;
# `at` is where an image belongs in the clip. Kinds not listed here can be accepted only after this table has them.
INPUT_ARGS = {
    'start_image': lambda path, item: ['--init-img', path],
    'end_image': lambda path, item: ['--end-img', path],
}


class VideoRuntime:
    def __init__(self, config, run_dir):
        self.config = config
        self.run_dir = Path(run_dir)
        gpus = config.get('gpus', {})
        for role in ('image', 'chat'):
            if not str(gpus.get(role, '')).startswith('GPU-') or ',' in gpus[role]:
                raise ValueError(f'Video config needs the {role} card as one physical GPU UUID')
        self.files = {item['role']: item['path'] for item in config['files']}
        for role in ('transformer', 'text_encoder', 'video_vae', 'audio_vae'):
            if role not in self.files:
                raise ValueError(f'Video config is missing its {role}')

    async def command(self, *args, timeout=65, check=True):
        argv, spawn = spawn_args(*args)
        proc = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, **spawn)
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout)
        except BaseException:
            proc.kill()
            await proc.wait()
            raise
        if check and proc.returncode:
            raise RuntimeError(f'{args[0]} failed: {err.decode()[-500:]}')
        return out.decode()

    def missing(self):
        paths = [self.config['binary']['path'], self.config['rpc_worker']['path'], *self.files.values()]
        return [Path(p).name for p in paths if not Path(p).is_file()]

    async def preflight(self):
        def verify():
            for item in [self.config['binary'], self.config['rpc_worker'], *self.config['files']]:
                p = Path(item['path'])
                if not p.is_file():
                    raise RuntimeError(f'Video runtime file is missing: {p.name}')
                stat = p.stat()
                # The config was written after checksum verification; a changed file needs reverifying.
                if stat.st_size != item['verified_size'] or stat.st_mtime_ns != item['verified_mtime_ns']:
                    raise RuntimeError(f'{p.name} changed since it was verified. Rerun videogen.configure.')
        await asyncio.to_thread(verify)

    def argv(self, request, plan, output, inputs=()):
        sampling = self.config.get('sampling', {})
        prompt = request['prompt']
        argv = [self.config['binary']['path'], '-M', 'vid_gen',
                '--diffusion-model', self.files['transformer'], '--llm', self.files['text_encoder'],
                '--vae', self.files['video_vae'], '--audio-vae', self.files['audio_vae'],
                '--mmap', '--diffusion-fa', '--sampling-method', 'euler', '--cfg-scale', str(sampling.get('cfg', 1.0)),
                '--sigmas', ','.join(map(str, sampling.get('sigmas', SIGMAS))), '--steps', str(len(sampling.get('sigmas', SIGMAS)) - 1),
                '-W', str(plan['width']), '-H', str(plan['height']), '--video-frames', str(plan['frames']), '--fps', '24',
                '-s', str(request['seed']), '-n', request.get('negative_prompt') or sampling.get('negative_prompt', NEGATIVE),
                '--temporal-tiling', '--vae-tiling', '--extra-tiling-args', 'temporal_tile_frames=2', '-v', '-o', str(output)]
        if plan['placement'] == 'single':
            # The 12 GB transformer stays on the card; the text encoder and decoder weights stream from disk, and small
            # decode tiles fit beside the transformer.
            argv += ['--backend', 'diffusion=cuda0,te=cuda0,vae=cuda0', '--params-backend', 'te=cpu,vae=cpu',
                     '--vae-tile-size', '10x11']
        else:
            # Half the transformer's layers on each card; without the caps the planner packs them all onto the first.
            argv += ['--backend', 'diffusion=cuda0&RPC0,te=cuda0,vae=cuda0', '--params-backend', 'te=cpu,vae=cpu',
                     '--rpc-servers', f'127.0.0.1:{self.config.get("rpc_port", 50053)}', '--max-vram', 'cuda0=6.5,RPC0=6.5',
                     '--split-mode', 'layer', '--vae-tile-size', '20x22']
        for item, path in inputs:
            argv += INPUT_ARGS[item['kind']](str(path), item)
        if request.get('loras'):
            argv += ['--lora-model-dir', self.config['lora_dir']]
            prompt += ''.join(f' <lora:{lora["name"]}:{lora["strength"]:g}>' for lora in request['loras'])
        return argv + ['-p', prompt]

    async def unit_state(self, unit):
        out = await self.command('systemctl', '--user', 'show', unit, '--property=ActiveState,Result,ExecMainStatus,LoadState', check=False)
        return dict(line.split('=', 1) for line in out.splitlines() if '=' in line)

    async def stop(self, unit):
        await self.command('systemctl', '--user', 'stop', unit, check=False)
        await self.command('systemctl', '--user', 'reset-failed', unit, check=False)

    async def start_unit(self, unit, gpu, argv, log, memory_max):
        await self.stop(unit)
        await self.command('systemd-run', '--user', f'--unit={unit}', '--property=KillMode=control-group',
                           '--property=TimeoutStopSec=30', '--property=BindsTo=assistant-api.service',
                           '--property=After=assistant-api.service', f'--property=MemoryMax={memory_max}',
                           '--property=MemorySwapMax=0', f'--property=StandardOutput=append:{log}',
                           f'--property=StandardError=append:{log}', f'--working-directory={log.parent}',
                           '--setenv=CUDA_DEVICE_ORDER=PCI_BUS_ID', f'--setenv=CUDA_VISIBLE_DEVICES={gpu}', *argv)

    async def start_worker(self, log):
        port = self.config.get('rpc_port', 50053)
        await self.start_unit(WORKER_UNIT, self.config['gpus']['image'],
                              [self.config['rpc_worker']['path'], '-H', '127.0.0.1', '-p', str(port)], log, '4G')
        end = time.monotonic() + 60
        while time.monotonic() < end:
            try:
                _, writer = await asyncio.wait_for(asyncio.open_connection('127.0.0.1', port), 2)
                writer.close()
                return
            except (OSError, asyncio.TimeoutError):
                if (await self.unit_state(WORKER_UNIT)).get('ActiveState') == 'failed':
                    break
                await asyncio.sleep(1)
        raise RuntimeError('The second card\'s render worker did not start')

    async def render(self, request, plan, work, cancelled, update, inputs=()):
        """Runs the renderer to a WebM in `work`, reporting phases and sampling steps from its log."""
        work.mkdir(parents=True, exist_ok=True)
        log, output = work / 'render.log', work / 'render.webm'
        output.unlink(missing_ok=True)
        dual = plan['placement'] == 'dual'
        try:
            if dual:
                update(phase='starting_worker')
                await self.start_worker(work / 'worker.log')
            update(phase='loading')
            # With chat running, the renderer gets a small RAM cap so memory pressure can't reach the chat model.
            await self.start_unit(VIDEO_UNIT, self.config['gpus']['chat' if dual else 'image'],
                                  self.argv(request, plan, output, inputs), log, '40G' if dual else '4G')
            started = time.monotonic()
            phase = 'loading'
            while True:
                if cancelled():
                    raise asyncio.CancelledError()
                await asyncio.sleep(2)
                state = await self.unit_state(VIDEO_UNIT)
                text = await asyncio.to_thread(tail, log)
                next_phase = phase
                if 'get_learned_condition completed' in text or phase != 'loading':
                    next_phase = 'sampling' if phase == 'loading' else phase
                if 'sampling completed' in text:
                    next_phase = 'decoding'
                if 'decode_first_stage completed' in text:
                    next_phase = 'saving'
                phase = next_phase
                fields = {'phase': phase, 'progress': min(.97, (time.monotonic() - started) / max(60, plan['estimate_seconds']))}
                steps = STEP.findall(text.split('get_learned_condition completed')[-1]) if phase == 'sampling' else []
                if steps:
                    fields['step'] = {'step': int(steps[-1][0]), 'steps': int(steps[-1][1])}
                update(**fields)
                if state.get('ActiveState') in ('inactive', 'failed') or state.get('LoadState') == 'not-found':
                    break
            if state.get('Result') not in ('success', None) or not output.is_file():
                raise RuntimeError(failure(await asyncio.to_thread(tail, log, 20000)))
            return output
        finally:
            await self.stop(VIDEO_UNIT)
            if dual:
                await self.stop(WORKER_UNIT)

    async def finish(self, source, work, plan):
        """H.264/AAC MP4 (plays on phones and browsers, unlike the renderer's VP8/PCM WebM) and a JPEG poster."""
        video, poster = work / 'video.mp4', work / 'poster.jpg'
        await self.command('ffmpeg', '-v', 'error', '-y', '-i', str(source), '-c:v', 'libx264', '-preset', 'medium',
                           '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart',
                           str(video), timeout=900)
        probe = json.loads(await self.command('ffprobe', '-v', 'error', '-print_format', 'json', '-show_streams',
                                              '-show_format', str(video)))
        stream = next(s for s in probe['streams'] if s['codec_type'] == 'video')
        if (stream['width'], stream['height']) != (plan['width'], plan['height']):
            raise RuntimeError('The video came out at an unexpected size')
        duration = float(probe['format']['duration'])
        await self.command('ffmpeg', '-v', 'error', '-y', '-ss', f'{min(1.0, duration / 2):.2f}', '-i', str(video),
                           '-frames:v', '1', '-q:v', '3', str(poster), timeout=120)
        return video, poster, duration

    async def cleanup(self):
        """Stop anything a previous API process left running (after a crash or restart)."""
        await self.stop(VIDEO_UNIT)
        await self.stop(WORKER_UNIT)


def tail(path, size=6000):
    try:
        with open(path, 'rb') as f:
            f.seek(max(0, os.path.getsize(path) - size))
            return f.read().decode(errors='replace').replace('\r', '\n')
    except FileNotFoundError:
        return ''


def failure(text):
    """The renderer's own reason, shortened: out of GPU memory is the usual one."""
    if 'out of memory' in text or 'cudaMalloc failed' in text:
        return 'The GPU ran out of memory for this size. Choose a shorter video or a faster setting.'
    errors = [line.split(' - ', 1)[-1].strip() for line in text.splitlines() if '[ERROR]' in line]
    return ('Video render failed: ' + '; '.join(errors[-3:]))[:500] if errors else 'Video render failed'
