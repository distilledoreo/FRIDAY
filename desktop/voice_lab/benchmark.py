"""Measure recorded questions against the local PersonaPlex trial.

python -m desktop.voice_lab.benchmark --output <directory> question1.wav question2.wav
Native requests run unpaced to measure throughput; one browser request runs at microphone speed.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import math
import sys
import time
import wave
from array import array
from pathlib import Path

from aiohttp import ClientSession, ClientTimeout, WSMsgType

from .personaplex import DEFAULT_PERSONA, RATE, upstream_events


def rms(pcm):
    values = array('h')
    values.frombytes(pcm)
    if sys.byteorder != 'little':
        values.byteswap()
    return math.sqrt(sum(value * value for value in values) / max(1, len(values))) / 32768


def read_audio(path, silence=8):
    with wave.open(str(path), 'rb') as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (RATE, 1, 2):
            raise ValueError('Questions must be mono 24 kHz PCM16 WAV files.')
        speech = wav.readframes(wav.getnframes())
    return speech + bytes(silence * RATE * 2), len(speech) / (RATE * 2)


def write_audio(path, pcm):
    with wave.open(str(path), 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm)


async def run_case(client, source, out, upstream, browser=None):
    pcm, speech_seconds = read_audio(source)
    chunks = []
    text = []
    events = []
    first_packet = first_audible = None
    started = time.perf_counter()

    def collect(event):
        nonlocal first_packet, first_audible
        elapsed = time.perf_counter() - started
        kind = event.get('type')
        if kind == 'error':
            raise RuntimeError(event.get('message') or str(event.get('error')))
        if kind == 'speech.audio.delta':
            audio = base64.b64decode(event['audio'])
            if first_packet is None:
                first_packet = elapsed
            if first_audible is None and rms(audio) > .005:
                first_audible = elapsed
            chunks.append(audio)
            events.append({'type': kind, 'elapsed_s': elapsed, 'bytes': len(audio)})
        else:
            events.append({'elapsed_s': elapsed, **event})
            if kind == 'speech.text.delta':
                text.append(event.get('delta', ''))

    if browser:
        async with client.ws_connect(browser + '/voice') as ws:
            await ws.send_json({'voice': 'NATF2', 'persona': DEFAULT_PERSONA})
            preparing_started = time.perf_counter()
            ready = await ws.receive_json(timeout=180)
            while ready.get('type') == 'preparing':
                ready = await ws.receive_json(timeout=180)
            if ready.get('type') != 'ready':
                raise RuntimeError(str(ready))
            preparation_s = time.perf_counter() - preparing_started
            started = time.perf_counter()

            async def send():
                for offset in range(0, len(pcm), 4800):
                    await ws.send_bytes(pcm[offset:offset + 4800])
                    deadline = started + min(len(pcm), offset + 4800) / (RATE * 2)
                    await asyncio.sleep(max(0, deadline - time.perf_counter()))
                await ws.send_json({'type': 'finish'})
            sender = asyncio.create_task(send())
            try:
                async for msg in ws:
                    if msg.type == WSMsgType.TEXT:
                        collect(json.loads(msg.data))
                    elif msg.type == WSMsgType.ERROR:
                        raise RuntimeError('Browser bridge websocket failed')
                await sender
            finally:
                sender.cancel()
                await asyncio.gather(sender, return_exceptions=True)
    else:
        async def send():
            for offset in range(0, len(pcm), 4800):
                yield pcm[offset:offset + 4800]
        params = {'model': 'personaplex-q8', 'input': DEFAULT_PERSONA, 'voice_id': 'NATF2',
                  'sample_rate': RATE, 'channels': 1, 'sample_format': 's16le',
                  'stream_format': 'sse', 'response_format': 'pcm'}
        async with client.post(upstream + '/v1/audio/speech/live', params=params, data=send(),
                               headers={'Content-Type': 'application/octet-stream'}) as response:
            if response.status != 200:
                raise RuntimeError(f'HTTP {response.status}: {(await response.text())[:1000]}')
            async for event in upstream_events(response):
                collect(event)
    elapsed = time.perf_counter() - started
    audio = b''.join(chunks)
    if not audio or first_audible is None:
        raise RuntimeError('Model produced no audible output')
    output_duration = len(audio) / (RATE * 2)
    label = source.stem + ('-browser' if browser else '-native')
    write_audio(out / (label + '.wav'), audio)
    windows = range(0, len(audio), 4800)
    audible_start = next((i for i in windows if rms(audio[i:i + 4800]) > .005), 0)
    trimmed = audio[max(0, audible_start - 9600):]
    write_audio(out / (label + '-reply.wav'), trimmed)
    result = {'case': label, 'question_duration_s': speech_seconds,
              'input_duration_s': len(pcm) / (RATE * 2), 'output_duration_s': output_duration,
              'elapsed_s': elapsed, 'elapsed_over_output_duration': elapsed / output_duration,
              'paced_input': bool(browser), 'preparation_s': preparation_s if browser else None,
              'first_packet_s': first_packet,
              'first_audible_s': first_audible, 'text': ''.join(text), 'events': events}
    (out / (label + '.json')).write_text(json.dumps(result, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k != 'events'}), flush=True)
    return result


async def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    async with ClientSession(timeout=ClientTimeout(total=300, sock_read=90), read_bufsize=1_048_576) as client:
        for source in args.questions:
            results.append(await run_case(client, source, args.output, args.upstream))
        results.append(await run_case(client, args.questions[0], args.output, args.upstream, args.browser))
    (args.output / 'results.json').write_text(json.dumps(results, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('questions', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--upstream', default='http://127.0.0.1:8997')
    parser.add_argument('--browser', default='http://127.0.0.1:8998')
    asyncio.run(run(parser.parse_args()))


if __name__ == '__main__':
    main()
