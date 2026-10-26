"""Compare the production CPU recognizer with a resident audio.cpp ASR server.

Set ASSISTANT_API_TOKEN in the environment; recordings and transcripts remain local.
Both endpoints must run on loopback. Timings include HTTP and audio decoding.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import time
import wave
from pathlib import Path
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout, FormData


def loopback_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise argparse.ArgumentTypeError('Use an HTTP loopback URL')
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise argparse.ArgumentTypeError('URL must not contain credentials, query, or fragment')
    return value.rstrip('/')


async def benchmark(args):
    token = os.environ['ASSISTANT_API_TOKEN']
    rows = []
    async with ClientSession(timeout=ClientTimeout(total=180)) as client:
        for iteration in range(args.repeats + 1):
            for path in args.audio:
                audio = path.read_bytes()
                with wave.open(str(path), 'rb') as wav:
                    duration = wav.getnframes() / wav.getframerate()
                # Alternate endpoint order to reduce ordering effects. Round zero warms both models.
                for backend in (('cpu', 'gpu') if iteration % 2 == 0 else ('gpu', 'cpu')):
                    if backend == 'cpu':
                        url = args.cpu + '/transcribe'
                        body = audio
                        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'audio/wav'}
                    else:
                        url = args.gpu + '/v1/audio/transcriptions'
                        body = FormData()
                        body.add_field('model', args.model)
                        body.add_field('file', audio, filename=path.name, content_type='audio/wav')
                        headers = {}
                    started = time.perf_counter()
                    async with client.post(url, data=body, headers=headers) as response:
                        response.raise_for_status()
                        result = await response.json()
                    elapsed = time.perf_counter() - started
                    row = {'backend': backend, 'iteration': iteration, 'warmup': iteration == 0,
                           'audio': path.name, 'duration_s': duration, 'elapsed_s': elapsed,
                           'rtf': elapsed / duration, 'text': result['text']}
                    rows.append(row)
                    print(json.dumps(row), flush=True)
    summary = {}
    for backend in ('cpu', 'gpu'):
        measured = [r for r in rows if not r['warmup'] and r['backend'] == backend]
        summary[backend] = {'median_s': statistics.median(r['elapsed_s'] for r in measured),
                            'median_rtf': statistics.median(r['rtf'] for r in measured)}
    summary['speedup'] = summary['cpu']['median_s'] / summary['gpu']['median_s']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'summary': summary, 'rows': rows}, indent=2) + '\n')
    print(json.dumps(summary), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cpu', type=loopback_url, default='http://127.0.0.1:8700')
    parser.add_argument('--gpu', type=loopback_url, default='http://127.0.0.1:8999')
    parser.add_argument('--model', default='parakeet-gpu')
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('audio', type=Path, nargs='+')
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error('--repeats must be positive')
    if not os.environ.get('ASSISTANT_API_TOKEN'):
        parser.error('Set ASSISTANT_API_TOKEN in the environment')
    asyncio.run(benchmark(args))


if __name__ == '__main__':
    main()
