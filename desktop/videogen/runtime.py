"""Run one LTX video render in an owned systemd unit, then make a phone-playable MP4 and a poster frame."""
import asyncio
import json
import os
from pathlib import Path
import re
import shutil
import time

from .spawn import spawn_args

VIDEO_UNIT = 'assistant-ltx-video.service'
WORKER_UNIT = 'assistant-ltx-rpc.service'
# The distilled model's eight-step schedule.
SIGMAS = [1.0, 0.99375, 0.9875, 0.98125, 0.975, 0.909375, 0.725, 0.421875, 0.0]
NEGATIVE = 'worst quality, low quality, blurry, distorted, artifacts'
STEP = re.compile(r'(\d+)/(\d+) - [\d.]+s/it')
# Inputs the renderer understands (FRIDAY's conditioning runtime, desktop/video_lab/native/ltx_conditioning.h): the
# file types each takes and how many one video may have. A kind is accepted once the config lists it in `inputs`.
INPUTS = {
    'start_image': (('image/',), 1),
    'end_image': (('image/',), 1),
    'keyframe': (('image/',), 4),     # placed at `at_seconds`
    'source_video': (('video/',), 1),  # continued from its last frames; the result is the whole clip, extended
    'audio': (('audio/', 'video/'), 1),  # its soundtrack is kept from the start
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

    def argv(self, request, plan, output, extra=()):
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
        argv += list(extra)
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

    async def start_unit(self, unit, gpu, argv, log, memory_max, memory_high='infinity', environment=None):
        await self.stop(unit)
        await self.command('systemd-run', '--user', f'--unit={unit}', '--property=KillMode=control-group',
                           '--property=TimeoutStopSec=30', '--property=BindsTo=assistant-api.service',
                           '--property=After=assistant-api.service', f'--property=MemoryMax={memory_max}',
                           f'--property=MemoryHigh={memory_high}',
                           '--property=MemorySwapMax=0', f'--property=StandardOutput=append:{log}',
                           f'--property=StandardError=append:{log}', f'--working-directory={log.parent}',
                           '--setenv=CUDA_DEVICE_ORDER=PCI_BUS_ID', f'--setenv=CUDA_VISIBLE_DEVICES={gpu}',
                           *[f'--setenv={key}={value}' for key, value in (environment or {}).items()], *argv)

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

    async def prepare_inputs(self, request, plan, work, inputs):
        """Renderer options and environment for the request's input files: images fitted to the video's size, a
        source clip's frames at 24 fps, and a soundtrack encoded to audio latents."""
        folder = work / 'inputs'
        shutil.rmtree(folder, ignore_errors=True)
        (work / 'join.json').unlink(missing_ok=True)
        folder.mkdir(parents=True)
        fit = f'scale={plan["width"]}:{plan["height"]}:force_original_aspect_ratio=increase,crop={plan["width"]}:{plan["height"]}'
        args, environment, keyframes = [], {}, []
        for index, (item, path) in enumerate(inputs):
            kind = item['kind']
            if kind in ('start_image', 'end_image', 'keyframe'):
                image = folder / f'{kind}-{index}.png'
                await self.command('ffmpeg', '-v', 'error', '-y', '-i', str(path), '-vf', fit, '-frames:v', '1', str(image), timeout=120)
                if kind == 'keyframe':
                    keyframes.append((item['at_seconds'], image))
                else:
                    args += ['-i' if kind == 'start_image' else '--end-img', str(image)]
            elif kind == 'source_video':
                # The clip's last frames open the new video, which carries on from them. A short context (33 frames,
                # about 1.4 s) keeps continuity: encoding many frames at once leaves the card too full to decode.
                frames = folder / 'source'
                frames.mkdir()
                total = round(await self.duration(path) * 24)
                context = min(self.config.get('source_context_frames', 33), total, plan['frames'] - 24)
                context = (context - 1) // 8 * 8 + 1  # the encoder takes 8k+1 frames
                if context < 9:
                    raise RuntimeError('The source video is too short to continue (it needs at least 9 frames)')
                await self.command('ffmpeg', '-v', 'error', '-y', '-i', str(path), '-vf',
                                   f'fps=24,{fit},trim=start_frame={total - context},setpts=PTS-STARTPTS',
                                   '-frames:v', str(context), str(frames / '%05d.png'), timeout=600)
                if len(list(frames.glob('*.png'))) < 9:
                    raise RuntimeError('Could not read frames from the source video')
                args += ['--control-video', str(frames)]
                # finish() puts the rest of the original in front, so the result is the whole clip, extended.
                (work / 'join.json').write_text(json.dumps({'path': str(path), 'keep_frames': total - context}))
            elif kind == 'audio':
                encoder = self.config.get('audio_encoder') or {}
                if not all(encoder.get(key) for key in ('python', 'comfyui', 'vae')):
                    raise RuntimeError('Audio input needs the audio encoder in the video config')
                latents = folder / 'audio.frau'
                await self.command(encoder['python'], str(Path(__file__).with_name('audio_encode.py')),
                                   '--comfyui', encoder['comfyui'], '--vae', encoder['vae'], '--input', str(path),
                                   '--seconds', f'{plan["frames"] / 24:.3f}', '--output', str(latents), timeout=600)
                environment['FRIDAY_LTX_AUDIO_IN'] = str(latents)
        if keyframes:
            last = plan['frames'] - 1
            frames = sorted({min(last - 1, max(1, round(at * 24))) for at, _ in keyframes})
            if len(frames) != len(keyframes):
                raise RuntimeError('Two keyframes fall on the same frame; space them further apart')
            for at, image in sorted(keyframes, key=lambda pair: pair[0]):
                args += ['-r', str(image)]
            environment['FRIDAY_LTX_KEYFRAME_FRAMES'] = ','.join(map(str, frames))
        strengths = [item['strength'] for item, _ in inputs if item.get('strength') is not None]
        if strengths:
            # The renderer applies one conditioning strength to every input.
            args += ['--strength', f'{min(strengths):g}']
        return args, environment

    async def render(self, request, plan, work, cancelled, update, inputs=()):
        """Runs the renderer to a WebM in `work`, reporting phases and sampling steps from its log."""
        work.mkdir(parents=True, exist_ok=True)
        log, output = work / 'render.log', work / 'render.webm'
        output.unlink(missing_ok=True)
        dual = plan['placement'] == 'dual'
        extra, environment = (await self.prepare_inputs(request, plan, work, inputs)) if inputs else ([], {})
        try:
            if dual:
                update(phase='starting_worker')
                await self.start_worker(work / 'worker.log')
            update(phase='loading')
            # With chat running, the renderer recycles its own file cache above 5 GB: the weights it streams from disk
            # (about 25 GB) would otherwise push the chat model's RAM into swap and slow its replies. It holds about
            # 2 GB itself plus about 1.7 GB of CUDA shared memory, which can't be reclaimed, so a tighter limit stalls it.
            await self.start_unit(VIDEO_UNIT, self.config['gpus']['chat' if dual else 'image'],
                                  self.argv(request, plan, output, extra), log, '40G' if dual else '6G',
                                  'infinity' if dual else self.config.get('single_memory_high', '5G'), environment)
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

    async def duration(self, path):
        probe = json.loads(await self.command('ffprobe', '-v', 'error', '-print_format', 'json', '-show_format', str(path)))
        return float(probe['format']['duration'])

    async def has_audio(self, path):
        out = await self.command('ffprobe', '-v', 'error', '-select_streams', 'a', '-show_entries', 'stream=index',
                                 '-of', 'csv=p=0', str(path))
        return bool(out.strip())

    async def finish(self, source, work, plan):
        """H.264/AAC MP4 (plays on phones and browsers, unlike the renderer's VP8/PCM WebM) and a JPEG poster. When the
        video continues a source clip, the clip's earlier part (with its own sound) goes in front."""
        video, poster = work / 'video.mp4', work / 'poster.jpg'
        encode = ['-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k',
                  '-movflags', '+faststart', str(video)]
        join = json.loads((work / 'join.json').read_text()) if (work / 'join.json').is_file() else None
        if join and join['keep_frames'] > 0:
            fit = f'scale={plan["width"]}:{plan["height"]}:force_original_aspect_ratio=increase,crop={plan["width"]}:{plan["height"]}'
            keep = join['keep_frames']
            sound = (f'[0:a]atrim=end={keep / 24:.4f},asetpts=PTS-STARTPTS,aresample=48000[a0];' if await self.has_audio(join['path'])
                     else f'anullsrc=r=48000:cl=stereo,atrim=end={keep / 24:.4f}[a0];')
            graph = (f'[0:v]fps=24,{fit},setsar=1,trim=end_frame={keep},setpts=PTS-STARTPTS[v0];{sound}'
                     '[1:v]setsar=1,setpts=PTS-STARTPTS[v1];[1:a]aresample=48000,asetpts=PTS-STARTPTS[a1];'
                     '[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]')
            await self.command('ffmpeg', '-v', 'error', '-y', '-i', join['path'], '-i', str(source), '-filter_complex', graph,
                               '-map', '[v]', '-map', '[a]', *encode, timeout=900)
        else:
            await self.command('ffmpeg', '-v', 'error', '-y', '-i', str(source), *encode, timeout=900)
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
