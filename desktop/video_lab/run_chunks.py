"""Generate bounded AV windows with frozen latent carry, then join their new frames."""
import argparse
import json
from pathlib import Path
import signal
import subprocess
import time

from .benchmark_placement import gpu_state, run_case, service_active, wait_ready
from .chunk_state import audit_carry, read_state


def validate(config):
    for key in ['binary', 'rpc_worker', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']:
        if not Path(config[key]).is_file():
            raise FileNotFoundError(config[key])
    window, carry = config['window_frames'], config['carry_frames']
    if window < 17 or (window - 1) % 8 or carry < 9 or (carry - 1) % 8 or carry >= window:
        raise ValueError('Window and carry must be 8k+1 frames, with a smaller multi-frame carry')
    if (window - carry) * 25 % 24:
        raise ValueError('Window stride must align with the 24fps video and 25Hz audio latent grids')
    if not 2 <= config['windows'] <= 100:
        raise ValueError('Use between 2 and 100 windows')
    if any(config[key] < 32 or config[key] % 32 for key in ['width', 'height']):
        raise ValueError('Dimensions must be positive multiples of 32')
    if len(config['gpu_uuids']) != 2 or len(set(config['gpu_uuids'])) != 2:
        raise ValueError('Provide distinct main and auxiliary GPU UUIDs')
    if not 0 <= config.get('audio_blend_ms', 20) <= 100:
        raise ValueError('Audio overlap blend must be between 0 and 100 milliseconds')
    owned = {'-W', '--width', '-H', '--height', '--video-frames', '--fps', '--steps', '--sigmas',
             '--diffusion-model', '--vae', '--audio-vae', '--llm'}
    if any(argument.partition('=')[0] in owned for argument in config.get('extra_args', [])):
        raise ValueError('extra_args cannot override the base models, dimensions, frame rate or schedule')


def initial_window(config):
    if not config.get('initial_window'):
        return None
    row = json.loads(Path(config['initial_window']).read_text())
    if row['returncode'] != 0 or row['frames'] != config['window_frames'] or \
            any(row[key] != config[key] for key in ['width', 'height']):
        raise ValueError('Initial window is not a matching completed render')
    for flag, key in [('--diffusion-model', 'transformer'), ('--vae', 'video_vae'),
                      ('--audio-vae', 'audio_vae'), ('--llm', 'text_encoder')]:
        if Path(row['command'][row['command'].index(flag) + 1]).resolve() != Path(config[key]).resolve():
            raise ValueError('Initial window uses a different model: ' + key)
    if not Path(row['output']).is_file():
        raise FileNotFoundError(row['output'])
    stages = ['stage1', 'stage2'] if '--hires' in config.get('extra_args', []) else ['stage1']
    for stage in stages:
        state = read_state(str(Path(row['output']).with_suffix('')) + '.' + stage + '.avc')
        scale = 2 if stage == 'stage2' else 1
        if state['fps'] != 24 or state['frames'] != (config['window_frames'] - 1) // 8 + 1 or \
                state['width'] != config['width'] * scale // 32 or \
                state['height'] != config['height'] * scale // 32:
            raise ValueError('Initial carry frame grid mismatch')
    return row


def stitch_command(files, output, window_frames, carry_frames, audio_blend_samples=960):
    args = ['ffmpeg', '-v', 'error', '-y']
    filters = []
    streams = []
    for index, file in enumerate(files):
        args += ['-threads', '2', '-i', str(file)]
        start = carry_frames if index else 0
        count = window_frames - start
        samples = count * 2000  # 48kHz / 24fps
        filters.append(f'[{index}:v]trim=start_frame={start}:end_frame={window_frames},setpts=N/(24*TB)[v{index}]')
        if index and audio_blend_samples:
            filters.append(f'[{index}:a]aresample=48000,asplit=2[am{index}][lead_source{index}]')
            filters.append(f'[lead_source{index}]atrim=start_sample={start * 2000 - audio_blend_samples}:'
                           f'end_sample={start * 2000},asetpts=N/SR/TB[lead{index}]')
        else:
            filters.append(f'[{index}:a]aresample=48000[am{index}]')
        filters.append(f'[am{index}]atrim=start_sample={start * 2000}:'
                       f'end_sample={window_frames * 2000},asetpts=N/SR/TB,apad=whole_len={samples},'
                       f'atrim=end_sample={samples}[rawa{index}]')
        streams.append(f'[v{index}][a{index}]')
    for index in range(len(files)):
        if index + 1 < len(files) and audio_blend_samples:
            filters.append(f'[rawa{index}][lead{index + 1}]acrossfade=ns={audio_blend_samples}:c1=tri:c2=tri[a{index}]')
        else:
            filters.append(f'[rawa{index}]anull[a{index}]')
    filters.append(''.join(streams) + f'concat=n={len(files)}:v=1:a=1[v][a]')
    args += ['-filter_complex_threads', '2', '-filter_complex', ';'.join(filters), '-map', '[v]', '-map', '[a]',
             '-c:v', 'libx264', '-threads', '2', '-crf', '18', '-pix_fmt', 'yuv420p', '-c:a', 'aac',
             '-b:a', '192k', '-movflags', '+faststart', str(output)]
    return args


def assemble(report, directory):
    config = report['config']
    if report['status'] not in ['generated', 'complete'] or len(report['windows']) != config['windows'] or \
            any(row['returncode'] != 0 for row in report['windows']):
        raise ValueError('All generation windows must finish before assembly')
    blend = config.get('audio_blend_ms', 20)
    if not 0 <= blend <= 100:
        raise ValueError('Audio overlap blend must be between 0 and 100 milliseconds')
    output = directory / 'continuous.mp4'
    temporary_output = directory / 'continuous-new.mp4'
    subprocess.run(stitch_command([row['output'] for row in report['windows']], temporary_output,
                                  config['window_frames'], config['carry_frames'], round(blend * 48)), check=True)
    metadata = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-count_frames', '-show_streams',
                                                  '-show_format', '-of', 'json', str(temporary_output)]))
    expected = config['window_frames'] + (config['windows'] - 1) * (config['window_frames'] - config['carry_frames'])
    video = next(stream for stream in metadata['streams'] if stream['codec_type'] == 'video')
    audio = next(stream for stream in metadata['streams'] if stream['codec_type'] == 'audio')
    scale = 2 if '--hires' in config.get('extra_args', []) else 1
    if int(video['nb_read_frames']) != expected or video['r_frame_rate'] != '24/1' or \
            (video['width'], video['height']) != (config['width'] * scale, config['height'] * scale):
        raise RuntimeError('Joined video dimensions, frame count or rate mismatch')
    if abs(float(audio['duration']) - expected / 24) > .002:
        raise RuntimeError('Joined audio duration does not match video')
    temporary_output.replace(output)
    metadata['format']['filename'] = str(output)
    report.update(status='complete', output=str(output), expected_frames=expected, media=metadata,
                  audio_blend_ms=blend, assembled_at=time.time())
    temporary = directory / 'results.tmp'
    temporary.write_text(json.dumps(report, indent=2))
    temporary.replace(directory / 'results.json')
    print('Continuous output saved: ' + str(output), flush=True)


def run(config, directory):
    config = {'width': 864, 'height': 480, **config}
    validate(config)
    initial = initial_window(config)
    if directory.exists() and any(directory.iterdir()):
        raise FileExistsError('Use a new output directory to preserve previous experiment results')
    directory.mkdir(parents=True, exist_ok=True)
    unit = config.get('chat_unit', 'assistant-strata.service')
    worker = config.get('worker_unit', 'friday-video-chunks-rpc.service')
    parent = config.get('parent_unit', 'friday-video-chunks.service')
    restore = service_active(unit)
    report = {'config': config, 'restore_chat': restore, 'windows': [initial] if initial else [], 'before': gpu_state(),
              'started_at': time.time(), 'status': 'running'}

    def save():
        temporary = directory / 'results.tmp'
        temporary.write_text(json.dumps(report, indent=2))
        temporary.replace(directory / 'results.json')

    save()
    try:
        if restore:
            subprocess.run(['systemctl', '--user', 'stop', unit], check=True)
        active = subprocess.check_output(['nvidia-smi', '--query-compute-apps=pid,gpu_uuid',
                                          '--format=csv,noheader'], text=True).strip()
        if active:
            raise RuntimeError('GPU processes remain after stopping chat: ' + active)
        previous = Path(initial['output']).with_suffix('') if initial else None
        stages = ['stage1', 'stage2'] if '--hires' in config.get('extra_args', []) else ['stage1']
        for index in range(len(report['windows']), config['windows']):
            subprocess.run(['systemctl', '--user', 'stop', worker], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(['systemd-run', '--user', '--unit=' + worker.removesuffix('.service'), '--collect',
                            '--property=Type=exec', '--property=Conflicts=' + unit,
                            '--property=PartOf=' + parent, '--property=MemoryMax=20G',
                            '--setenv=CUDA_DEVICE_ORDER=PCI_BUS_ID',
                            '--setenv=CUDA_VISIBLE_DEVICES=' + config['gpu_uuids'][1],
                            config['rpc_worker'], '--host', '127.0.0.1', '--port', '50053', '--device', 'CUDA0'],
                           check=True, stdout=subprocess.DEVNULL)
            name = f'window-{index:03d}'
            prefix = directory / name
            case = dict(name=name, profile='dual-layer-rpc', width=config['width'], height=config['height'],
                        frames=config['window_frames'], steps=8, timeout_s=config.get('case_timeout_s', 1800),
                        extra_args=config.get('extra_args', []) + ['--rpc-servers', '127.0.0.1:50053',
                            '--max-vram', 'cuda0=6.5,RPC0=6.5', '-s', str(config.get('seed', 42) + index)])
            env = {'FRIDAY_LTX_CARRY_IN': str(previous) if previous else '',
                   'FRIDAY_LTX_CARRY_OUT': str(prefix), 'FRIDAY_LTX_CARRY_FRAMES': str(config['carry_frames'])}
            print('Generating ' + name + (' with frozen AV context' if previous else ''), flush=True)
            row = run_case(config, case, directory, extra_env=env)
            (directory / (name + '.json')).write_text(json.dumps(row, indent=2))
            report['windows'].append(row)
            save()
            if row['returncode'] != 0:
                raise RuntimeError(name + ' failed: ' + str(row['errors'][-3:]))
            row['carry_audit'] = {}
            for stage in stages:
                current_file = Path(str(prefix) + '.' + stage + '.avc')
                read_state(current_file)
                if previous:
                    row['carry_audit'][stage] = audit_carry(str(previous) + '.' + stage + '.avc',
                                                          current_file, config['carry_frames'])
            (directory / (name + '.json')).write_text(json.dumps(row, indent=2))
            save()
            print(json.dumps({key: row[key] for key in ['name', 'wall_s', 'timings', 'carry_audit']}), flush=True)
            previous = prefix
        report['status'] = 'generated'
    except BaseException as error:
        report['status'] = 'failed'
        report['failure'] = str(error)
        raise
    finally:
        subprocess.run(['systemctl', '--user', 'stop', worker], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if restore:
            subprocess.run(['systemctl', '--user', 'start', unit], check=True)
            print('Restoring chat model', flush=True)
            report['chat_ready'] = wait_ready(config.get('health_url', 'http://127.0.0.1:8080/health'))
        report['after'] = gpu_state()
        report['finished_at'] = time.time()
        save()
    assemble(report, directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path, nargs='?')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--assemble-only', action='store_true')
    parser.add_argument('--audio-blend-ms', type=float)
    args = parser.parse_args()

    def stop(*_):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, stop)
    if args.assemble_only:
        report = json.loads((args.output / 'results.json').read_text())
        if args.audio_blend_ms is not None:
            report['config']['audio_blend_ms'] = args.audio_blend_ms
        assemble(report, args.output)
    else:
        if args.config is None:
            parser.error('Provide a config for generation, or use --assemble-only')
        config = json.loads(args.config.read_text())
        if args.audio_blend_ms is not None:
            config['audio_blend_ms'] = args.audio_blend_ms
        run(config, args.output)


if __name__ == '__main__':
    main()
