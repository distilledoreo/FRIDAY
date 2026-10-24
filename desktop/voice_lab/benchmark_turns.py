"""Record matched speech turns through FRIDAY and the PersonaPlex browser bridge.

Latency analysis must identify answer content in the saved recordings; the first
non-silent output may only be a greeting. No microphone or speaker is opened.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import io
import json
import os
import re
import time
import uuid
import wave
from pathlib import Path

from aiohttp import ClientSession, ClientTimeout, FormData, WSMsgType

from .benchmark import rms, write_audio
from .benchmark_asr import loopback_url
from .personaplex import DEFAULT_PERSONA, RATE, upstream_events


def spoken_chunks(text, consumed=0, final=False, clauses=False):
    """Incremental plain-prose form of SpeechChunker, including final remainders."""
    def boundary(start):
        pattern = r'\n|[.!?](?=\s)|[,;:](?=\s)' if clauses else r'\n|[.!?](?=\s)'
        for match in re.finditer(pattern, text[start:]):
            at = start + match.start()
            if match.group() in ',;:' and len(text[start:at].split()) < 8:
                continue
            if match.group() == ',' and not re.match(
                    r'\s+(?:and|but|then|so|because|which|while|before|after|or|yet|as)(?=\s)', text[at + 1:]):
                # A comma can separate adjectives or list items, not a spoken clause.
                continue
            if match.group() == '.' and text[max(start, at - 3):at].endswith(('e.g', 'i.e', 'Mr', 'Dr')):
                continue
            return start + match.end()
        return None
    chunks = []
    while (end := boundary(consumed)) is not None:
        if len(text[consumed:end].strip()) < 24 and not final and end < len(text):
            end = boundary(end)
            if end is None:
                break
        chunks.append(text[consumed:end].strip())
        consumed = end
    if final and consumed < len(text):
        chunks.append(text[consumed:].strip())
        consumed = len(text)
    return [chunk for chunk in chunks if any(c.isalnum() for c in chunk)], consumed


def read_question(path):
    with wave.open(str(path), 'rb') as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (RATE, 1, 2):
            raise ValueError('Use mono 24 kHz PCM16 WAV questions')
        pcm = wav.readframes(wav.getnframes())
    frame = RATE // 100 * 2
    active = [i for i in range(0, len(pcm), frame) if rms(pcm[i:i + frame]) > .005]
    if not active:
        raise ValueError('Question contains no audible speech')
    return pcm, min(len(pcm), active[-1] + frame) / (RATE * 2)


def playback_time(events, audio_position, buffer=.06):
    """Map a word's recording timestamp onto the browser's FIFO playback clock.

    Audio chunks cannot play before arrival or before the preceding queued chunk.
    The bridge page adds 60 ms whenever its playback queue has run dry.
    """
    next_at = 0.
    for event in events:
        start = max(next_at, event['arrival_s'] + buffer)
        offset = audio_position - event['audio_offset_s']
        if 0 <= offset < event['duration_s']:
            return start + offset
        next_at = start + event['duration_s']
    raise ValueError('Audio position is outside recorded chunks')


async def pipeline(client, args, source, label, headers):
    pcm, speech_end = read_question(source)
    duration = len(pcm) / (RATE * 2)
    # The recording has just ended. Speech end was slightly earlier if WAV has trailing silence.
    started = time.perf_counter()
    if getattr(args, 'asr_url', None):
        asr_url, asr_headers = args.asr_url, {}
        asr_body = FormData()
        asr_body.add_field('model', 'parakeet-gpu')
        asr_body.add_field('file', source.read_bytes(), filename=source.name, content_type='audio/wav')
    else:
        asr_url, asr_headers = args.api + '/transcribe', {**headers, 'Content-Type': 'audio/wav'}
        asr_body = source.read_bytes()
    async with client.post(asr_url, data=asr_body, headers=asr_headers) as response:
        response.raise_for_status()
        asr = await response.json()
    asr_done = time.perf_counter() - started
    text = ''
    tts_tasks = []
    chunk_at = None
    consumed = 0
    first_token = None
    text_deltas = []

    async def synthesize(chunk, queued_at):
        t = time.perf_counter()
        async with client.post(getattr(args, 'tts_url', None) or args.api + '/speak', json={'text': chunk}, headers=headers) as response:
            response.raise_for_status()
            audio = await response.read()
        return chunk, queued_at, audio, time.perf_counter() - started, time.perf_counter() - t

    def enqueue(chunks):
        nonlocal chunk_at
        for chunk in chunks:
            queued_at = time.perf_counter() - started
            if chunk_at is None:
                chunk_at = queued_at
            tts_tasks.append(asyncio.create_task(synthesize(chunk, queued_at)))

    # Older gateways need a nonce to prevent completion replay. Updated FRIDAY
    # assigns distinct upstream request IDs, preserving a stable conversation anchor.
    turn_id = uuid.uuid4().hex[:12]
    user_text = asr['text'] if getattr(args, 'request_id_only', False) else asr['text'] + f'\n[Trial {turn_id}: answer the spoken question only.]'
    persona = DEFAULT_PERSONA
    if getattr(args, 'answer_first', False):
        persona += ' Start with the answer or a concrete suggestion. Skip greetings and acknowledgments. Use short sentences.'
    persona += getattr(args, 'style_suffix', '')
    body = {'messages': [{'role': 'system', 'content': persona},
                         *getattr(args, 'history', []), {'role': 'user', 'content': user_text}],
            'stream': True, 'temperature': 0, 'max_tokens': 256}
    body.update(getattr(args, 'llm_options', {}))
    async with client.post(args.api + '/chat', json=body, headers=headers) as response:
        response.raise_for_status()
        async for event in upstream_events(response):
            choice = event.get('choices', [{}])[0]
            delta = choice.get('delta', {}).get('content') or ''
            if delta and first_token is None:
                first_token = time.perf_counter() - started
            if delta:
                text_deltas.append({'at_s': time.perf_counter() - started, 'text': delta})
            text += delta
            chunks, consumed = spoken_chunks(text, consumed, clauses=getattr(args, 'clauses', False))
            enqueue(chunks)
    chunks, consumed = spoken_chunks(text, consumed, final=True, clauses=getattr(args, 'clauses', False))
    enqueue(chunks)
    if not tts_tasks:
        raise RuntimeError('LLM produced no spoken answer')
    llm_done = time.perf_counter() - started
    replies = await asyncio.gather(*tts_tasks)
    events, metadata, all_pcm = [], [], bytearray()
    output_rate = None
    for chunk, queued_at, audio, audio_at, tts_s in replies:
        with wave.open(io.BytesIO(audio), 'rb') as wav:
            rate = wav.getframerate()
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or output_rate not in (None, rate):
                raise RuntimeError('TTS output format changed')
            output_rate = rate
            frames = wav.readframes(wav.getnframes())
        output_s = len(frames) / (rate * 2)
        events.append({'audio_offset_s': len(all_pcm) / (rate * 2), 'duration_s': output_s,
                       'arrival_s': duration + audio_at})
        metadata.append({'text': chunk, 'queued_s': queued_at, 'audio_ready_s': audio_at, 'tts_s': tts_s})
        all_pcm.extend(frames)
    path = args.output / (label + '.wav')
    with wave.open(str(path), 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(output_rate)
        wav.writeframes(all_pcm)
    return {'case': label, 'path': 'pipeline', 'audio': str(path), 'question': source.name,
            'input_duration_s': duration, 'input_speech_end_s': speech_end,
            'input_trailing_s': duration - speech_end, 'asr_s': asr_done,
            'input_transcript': asr['text'], 'llm_first_token_s': first_token - asr_done if first_token else None,
            'turn_id': turn_id,
            'request_user_text': user_text,
            'first_chunk_s': chunk_at - asr_done, 'first_chunk': metadata[0]['text'],
            'full_text': text, 'llm_complete_s': llm_done - asr_done,
            'tts_s': metadata[0]['tts_s'], 'audio_ready_after_input_end_s': metadata[0]['audio_ready_s'],
            'output_duration_s': len(all_pcm) / (output_rate * 2), 'chunks': metadata,
            'text_deltas': text_deltas, 'events': events}


async def personaplex(client, args, source, label):
    pcm, speech_end = read_question(source)
    duration = len(pcm) / (RATE * 2)
    incoming = pcm + bytes(int(args.silence * RATE) * 2)
    events, audio = [], bytearray()
    async with client.ws_connect(args.browser + '/voice') as ws:
        before = time.perf_counter()
        await ws.send_json({'voice': 'NATF2', 'persona': DEFAULT_PERSONA})
        while True:
            ready = await ws.receive_json(timeout=180)
            if ready.get('type') == 'ready':
                break
            if ready.get('type') != 'preparing':
                raise RuntimeError(str(ready))
        preparation = time.perf_counter() - before
        started = time.perf_counter()

        async def upload():
            for offset in range(0, len(incoming), 4800):
                # Deliver each microphone packet only after its capture interval has elapsed.
                deadline = started + min(len(incoming), offset + 4800) / (RATE * 2)
                await asyncio.sleep(max(0, deadline - time.perf_counter()))
                await ws.send_bytes(incoming[offset:offset + 4800])
            await ws.send_json({'type': 'finish'})

        sender = asyncio.create_task(upload())
        try:
            async for message in ws:
                if message.type == WSMsgType.TEXT:
                    event = json.loads(message.data)
                    if event.get('type') == 'error':
                        raise RuntimeError(str(event))
                    if event.get('type') == 'speech.audio.delta':
                        chunk = base64.b64decode(event['audio'])
                        events.append({'audio_offset_s': len(audio) / (RATE * 2),
                                       'duration_s': len(chunk) / (RATE * 2),
                                       'arrival_s': time.perf_counter() - started})
                        audio.extend(chunk)
                elif message.type == WSMsgType.ERROR:
                    raise RuntimeError('Trial WebSocket failed')
            await sender
        finally:
            sender.cancel()
            await asyncio.gather(sender, return_exceptions=True)
    path = args.output / (label + '.wav')
    write_audio(path, audio)
    return {'case': label, 'path': 'personaplex', 'audio': str(path), 'question': source.name,
            'input_duration_s': duration, 'input_speech_end_s': speech_end,
            'preparation_s': preparation, 'output_duration_s': len(audio) / (RATE * 2),
            'elapsed_s': time.perf_counter() - started, 'events': events}


async def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    headers = {'Authorization': 'Bearer ' + os.environ['ASSISTANT_API_TOKEN']}
    rows = []
    paths = ['pipeline', 'personaplex'] if args.path == 'both' else [args.path]
    async with ClientSession(timeout=ClientTimeout(total=300, sock_read=120)) as client:
        if 'personaplex' in paths:
            async with client.get(args.browser + '/status') as response:
                status = await response.json()
                if status.get('busy'):
                    raise RuntimeError('Browser microphone trial is already in use')
        # A normal first-request warmup, saved separately and excluded from measured rounds.
        for name, runner in [('pipeline', pipeline), ('personaplex', personaplex)]:
            if name not in paths:
                continue
            label = 'warmup-' + name
            row = await runner(client, args, args.questions[0], label, headers) if name == 'pipeline' else await runner(client, args, args.questions[0], label)
            row['warmup'] = True
            rows.append(row)
            (args.output / (label + '.json')).write_text(json.dumps(row, indent=2))
            print(json.dumps({k: v for k, v in row.items() if k != 'events'}), flush=True)
        for repeat in range(args.repeats):
            for source in args.questions:
                order = ['pipeline', 'personaplex'] if repeat % 2 == 0 else ['personaplex', 'pipeline']
                for name in order:
                    if name not in paths:
                        continue
                    label = f'{source.stem}-{repeat + 1}-{name}'
                    row = await pipeline(client, args, source, label, headers) if name == 'pipeline' else await personaplex(client, args, source, label)
                    row['warmup'] = False
                    rows.append(row)
                    (args.output / (label + '.json')).write_text(json.dumps(row, indent=2))
                    (args.output / 'results.json').write_text(json.dumps(rows, indent=2))
                    print(json.dumps({k: v for k, v in row.items() if k != 'events'}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=loopback_url, default='http://127.0.0.1:8700')
    parser.add_argument('--browser', type=loopback_url, default='http://127.0.0.1:8998')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--silence', type=float, default=10)
    parser.add_argument('--path', choices=('pipeline', 'personaplex', 'both'), default='both')
    parser.add_argument('--asr-url', type=loopback_url, help='Optional native multipart transcription endpoint')
    parser.add_argument('--tts-url', type=loopback_url, help='Optional experimental synthesis endpoint')
    parser.add_argument('--clauses', action='store_true', help='Allow long clauses to start synthesis')
    parser.add_argument('--answer-first', action='store_true', help='Test direct, short spoken answers')
    parser.add_argument('--request-id-only', action='store_true', help='Updated FRIDAY: use upstream request IDs instead of a prompt nonce')
    parser.add_argument('questions', nargs='+', type=Path)
    args = parser.parse_args()
    if args.repeats < 1 or args.silence <= 0:
        parser.error('Repeats and silence must be positive')
    if not os.environ.get('ASSISTANT_API_TOKEN'):
        parser.error('Set ASSISTANT_API_TOKEN in the environment')
    asyncio.run(run(args))


if __name__ == '__main__':
    main()
