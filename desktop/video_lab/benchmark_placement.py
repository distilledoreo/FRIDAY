"""Compare LTX module placement with matched inputs and restore the chat service."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import time
import urllib.request


SIGMAS = '1.0,0.99375,0.9875,0.98125,0.975,0.909375,0.725,0.421875,0.0'
PROFILES = {
    'single-offload': ('diffusion=cuda0,te=cuda0,vae=cuda0', 'cpu'),
    'dual-resident': ('diffusion=cuda0,te=cuda1,vae=cuda1', 'diffusion=cuda0,te=cuda1,vae=cuda1'),
    'dual-te-offload': ('diffusion=cuda0,te=cuda1,vae=cuda1', 'diffusion=cuda0,te=cpu,vae=cuda1'),
    # Leave diffusion params unspecified so each shard stays on its own card.
    'dual-layer': ('diffusion=cuda0&cuda1,te=cuda0,vae=cuda0', 'te=cpu,vae=cpu'),
    'dual-layer-rpc': ('diffusion=cuda0&RPC0,te=cuda0,vae=cuda0', 'te=cpu,vae=cpu'),
}


def command(config, case, output):
    backend, params = PROFILES[case['profile']]
    steps = case.get('steps', 8)
    if steps not in (1, 8):
        raise ValueError('Use one-step capacity probes or the full eight-step schedule')
    args = [config['binary'], '-M', 'vid_gen', '--diffusion-model', config['transformer'],
            '--vae', config['video_vae'], '--audio-vae', config['audio_vae'], '--llm', config['text_encoder'],
            '--backend', backend, '--params-backend', params, '--mmap', '--diffusion-fa',
            '--sampling-method', 'euler', '--cfg-scale', '1.0', '--sigmas', SIGMAS if steps == 8 else '1.0,0.0', '--steps', str(steps),
            '-W', str(case['width']), '-H', str(case['height']), '--video-frames', str(case['frames']),
            '--fps', '24', '-p', config['prompt'], '-n', config['negative_prompt'], '-s', '42',
            '--temporal-tiling', '-v', '-o', str(output)]
    args += case.get('extra_args', [])
    if case['profile'] in ('dual-layer', 'dual-layer-rpc'):
        args += ['--split-mode', 'layer']
    if case.get('eager_load'):
        args.append('--eager-load')
    return args


def gpu_state():
    raw = subprocess.check_output(['nvidia-smi', '--query-gpu=index,uuid,memory.used,temperature.gpu,utilization.gpu',
                                   '--format=csv,noheader,nounits'], text=True)
    rows = []
    for line in raw.strip().splitlines():
        index, uuid, memory, temp, utilization = [s.strip() for s in line.split(',')]
        rows.append(dict(index=int(index), uuid=uuid, memory_mib=int(memory), temperature_c=int(temp),
                         utilization=int(utilization)))
    return rows


def service_active(unit):
    return subprocess.run(['systemctl', '--user', 'is-active', '--quiet', unit]).returncode == 0


def wait_ready(url, timeout=600):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if json.load(response).get('loaded') is True:
                    return True
        except (OSError, ValueError):
            pass
        time.sleep(3)
    return False


def timings(text):
    result = {}
    for name, pattern in {
        'text_encoding_s': r'get_learned_condition completed, taking ([\d.]+)s',
        'sampling_s': r'sampling completed, taking ([\d.]+)s',
        'latent_upscale_s': r'LTX latent spatial upscale completed, taking ([\d.]+)s',
        'refine_sampling_s': r'sampling\(latent upscale\) completed, taking ([\d.]+)s',
        'video_decode_s': r'decode_first_stage completed, taking ([\d.]+)s',
        'audio_decode_s': r'decoding audio latent completed, taking ([\d.]+)s',
        'generation_s': r'generate_video completed in ([\d.]+)s',
    }.items():
        matches = re.findall(pattern, text)
        if matches:
            result[name] = sum(float(v) for v in matches)
    return result


def terminate(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def run_case(config, case, directory, *, extra_env=None):
    output, log = directory / (case['name'] + '.webm'), directory / (case['name'] + '.log')
    args = command(config, case, output)
    if '--rpc-servers' in args:
        for endpoint in args[args.index('--rpc-servers') + 1].split(','):
            host, port = endpoint.rsplit(':', 1)
            host = host.strip('[]')
            if host not in ('127.0.0.1', 'localhost', '::1'):
                raise ValueError('Capacity worker must be on loopback')
            deadline = time.monotonic() + 20
            while True:
                try:
                    with socket.create_connection((host, int(port)), timeout=.25):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Capacity worker did not become ready')
                    time.sleep(.1)
    env = dict(os.environ, CUDA_DEVICE_ORDER='PCI_BUS_ID',
               CUDA_VISIBLE_DEVICES=','.join(config['gpu_uuids']))
    if extra_env:
        env.update(extra_env)
    started = time.monotonic()
    samples = []
    timed_out = False
    with log.open('w') as stream:
        process = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT, env=env, start_new_session=True)
        try:
            while process.poll() is None:
                samples.append({'elapsed_s': round(time.monotonic() - started, 2), 'gpus': gpu_state()})
                if time.monotonic() - started > case.get('timeout_s', config.get('case_timeout_s', 1800)):
                    timed_out = True
                    break
                time.sleep(1)
        finally:
            terminate(process)
    text = log.read_text(errors='replace')
    row = dict(case, returncode=process.returncode, timed_out=timed_out, wall_s=round(time.monotonic() - started, 3),
               output=str(output), log=str(log), command=args, timings=timings(text), samples=samples,
               errors=[line[-400:] for line in text.replace('\r', '\n').splitlines()
                       if '[ERROR]' in line or 'out of memory' in line.lower()
                       or 'GGML_ASSERT' in line or 'terminate called' in line])
    row['partition'] = [line for line in text.replace('\r', '\n').splitlines()
                        if 'layer split:' in line and '<-' in line]
    if case['profile'] in ('dual-layer', 'dual-layer-rpc'):
        devices = {match.group(1) for line in row['partition']
                   if (match := re.search(r'layer split: (\S+) <-', line))}
        row['split_verified'] = len(devices) >= 2
    if process.returncode == 0:
        row['media'] = json.loads(subprocess.check_output([
            'ffprobe', '-v', 'error', '-show_entries',
            'format=duration:stream=codec_type,codec_name,width,height,r_frame_rate,sample_rate,channels',
            '-of', 'json', str(output)], text=True))
    return row


def benchmark(config, directory, config_path=None):
    directory.mkdir(parents=True, exist_ok=True)
    for name in ['binary', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']:
        if not Path(config[name]).is_file():
            raise FileNotFoundError(config[name])
    if len(config['gpu_uuids']) != 2 or len(set(config['gpu_uuids'])) != 2:
        raise ValueError('Provide distinct fast and spare GPU UUIDs, in that order')
    unit = config.get('chat_unit', 'assistant-strata.service')
    restore = service_active(unit)
    report = {'config': config, 'before': gpu_state(), 'cases': [], 'restore_chat': restore}
    def save():
        (directory / 'results.json').write_text(json.dumps(report, indent=2))
    save()
    try:
        if restore:
            subprocess.run(['systemctl', '--user', 'stop', unit], check=True)
        processes = subprocess.check_output(['nvidia-smi', '--query-compute-apps=pid,gpu_uuid',
                                            '--format=csv,noheader'], text=True).strip()
        if processes:
            raise RuntimeError('GPU processes remain after stopping chat; refusing to interrupt them: ' + processes)
        print('Both GPUs free; beginning matched video tests', flush=True)
        completed = set()
        while True:
            queue = json.loads(config_path.read_text()) if config_path else config
            names = [case['name'] for case in queue['cases']]
            if len(names) != len(set(names)) or any(not re.fullmatch(r'[a-zA-Z0-9_-]+', name) for name in names):
                raise ValueError('Case names must be unique filesystem-safe labels')
            pending = [case for case in queue['cases'] if case['name'] not in completed]
            if not pending:
                if queue.get('queue_open', False) and config_path:
                    time.sleep(1)
                    continue
                break
            case = pending[0]
            print('Starting ' + case['name'], flush=True)
            row = run_case(config, case, directory)
            report['cases'].append(row)
            completed.add(case['name'])
            save()
            print(json.dumps({k: row[k] for k in ('name', 'returncode', 'wall_s', 'timings', 'errors')}), flush=True)
    finally:
        if restore:
            subprocess.run(['systemctl', '--user', 'start', unit], check=True)
            print('Restoring chat model', flush=True)
            report['chat_ready'] = wait_ready(config.get('health_url', 'http://127.0.0.1:8080/health'))
        report['after'] = gpu_state()
        save()
        print('Finished; chat ready=' + str(report.get('chat_ready')), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    def stop(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    benchmark(json.loads(args.config.read_text()), args.output, args.config)


if __name__ == '__main__':
    main()
