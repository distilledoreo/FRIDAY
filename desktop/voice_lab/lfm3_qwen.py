"""Recorded-audio trial of LFM3-Audio with a read-only Qwen delegation tool.

Uses a separately configured loopback llama-server. Does not alter FRIDAY routing.
Microphone input is simulated at recording speed; audio and timings stay local.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import re
import subprocess
import time
import wave
from pathlib import Path

import numpy as np
from aiohttp import ClientSession, ClientTimeout, TCPConnector, WSMsgType

from .benchmark_asr import loopback_url
from .benchmark_turns import pipeline, playback_time, read_question, spoken_chunks
from .personaplex import DEFAULT_PERSONA, upstream_events


DIRECT_PROMPT = 'Respond with interleaved text and audio.'
HYBRID_PROMPT = '''Respond with function calls, and interleaved text and audio.

List of tools:

<tools>
{"name":"ask_qwen","description":"Ask FRIDAY's main reasoning model. Always use this tool for calculations, factual research, coding, planning with constraints, or uncertain requests. Copy the complete user request faithfully, including all numbers and constraints. The application speaks the answer, so do not speak an acknowledgment before the call. Answer only casual conversation and simple everyday suggestions yourself.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"],"additionalProperties":false}}
</tools>'''
ROUTED_PROMPT = HYBRID_PROMPT.replace(
    '\n\nList of tools:',
    '\nAnswer greetings, small talk, emotional support, and general everyday suggestions directly in speech. '
    'Do not call a tool for those requests. Use ask_qwen for arithmetic, time calculations, factual research, '
    'coding, or plans with numerical constraints.\n\nList of tools:')


def qwen_query(text):
    """Accept one bounded, allowlisted call; never execute arbitrary model output."""
    if '<tool_call' not in text and '<function=' not in text:
        return None
    match = re.fullmatch(
        r'\s*<tool_call>\s*<function=ask_qwen>\s*<parameter=query>\s*'
        r'(.*?)\s*</parameter>\s*</function>\s*</tool_call>\s*', text, re.S)
    if not match:
        raise ValueError('Malformed or unsupported delegation call')
    value = match[1].strip()
    if value.startswith('"'):
        value = json.loads(value)
    if not isinstance(value, str) or not 1 <= len(value) <= 4000 or '<tool_call' in value:
        raise ValueError('Delegation query must be a nonempty string of at most 4000 characters')
    return value


def speech_text(text):
    """Verbalize number notation for TTS without recomputing Qwen's answer."""
    from num2words import num2words
    text = re.sub(r'\*\*|__|`+', '', text)
    text = re.sub(r'(?<![\w.])[-−](?=\d)', 'minus ', text)
    text = re.sub(r'\b(\d{1,2}):(\d{2})\b', lambda m:
                  num2words(int(m[1])) + (" o'clock" if m[2] == '00' else
                  (' oh ' if int(m[2]) < 10 else ' ') + num2words(int(m[2]))), text)
    text = re.sub(r'\b(\d{1,12})\s*/\s*(\d{1,12})\b', lambda m:
                  num2words(int(m[1])) + ' over ' + num2words(int(m[2])), text)
    text = re.sub(r'\b\d{1,12}(?:\.\d{1,8})?\b', lambda m:
                  num2words(m[0]) if '.' in m[0] else num2words(int(m[0])), text)
    return text.replace('×', ' times ').replace('=', ' equals ')


def api_token():
    """Reuse the local service credential without logging or copying its environment."""
    token = os.environ.get('ASSISTANT_API_TOKEN')
    if not token:
        pid = subprocess.check_output(
            ['systemctl', '--user', 'show', 'assistant-api', '-p', 'MainPID', '--value'], text=True).strip()
        if not pid.isdecimal() or pid == '0':
            raise RuntimeError('Set ASSISTANT_API_TOKEN or start assistant-api')
        for entry in Path('/proc', pid, 'environ').read_bytes().split(b'\0'):
            if entry.startswith(b'ASSISTANT_API_TOKEN='):
                token = entry.split(b'=', 1)[1].decode()
                break
    if not token:
        raise RuntimeError('No FRIDAY API credential available')
    return token


class Recording:
    def __init__(self, started):
        self.started = started
        self.pcm = bytearray()
        self.events = []
        self.rate = 24000

    def append(self, pcm, rate=24000):
        if not pcm:
            return
        if rate != self.rate:
            raise ValueError('Unexpected output sample rate')
        self.events.append({'audio_offset_s': len(self.pcm) / (rate * 2),
                            'duration_s': len(pcm) / (rate * 2),
                            'arrival_s': time.perf_counter() - self.started})
        self.pcm.extend(pcm)

    def save(self, path):
        with wave.open(str(path), 'wb') as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(self.rate)
            wav.writeframes(self.pcm)

    def onset(self, speech_end):
        samples = np.frombuffer(self.pcm, dtype='<i2').astype(np.float32) / 32768
        frame = self.rate // 100
        for i in range(0, len(samples), frame):
            chunk = samples[i:i + frame]
            if len(chunk) and float(np.sqrt(np.mean(chunk * chunk))) > .005:
                return playback_time(self.events, i / self.rate) - speech_end
        return None


async def lfm_response(client, args, source, started, instructions):
    """Prefill streamed audio while it is spoken, then collect native text/audio."""
    recording = Recording(started)
    text, first_text, sender = '', None, None
    ws_url = args.lfm.replace('http', 'ws', 1) + '/v1/realtime'
    async with client.ws_connect(ws_url, heartbeat=20, max_msg_size=8_000_000) as ws:
        async def receive():
            msg = await asyncio.wait_for(ws.receive(), 120)
            if msg.type != WSMsgType.TEXT:
                raise RuntimeError('LFM realtime connection closed unexpectedly')
            event = json.loads(msg.data)
            if event.get('type') == 'error':
                raise RuntimeError(str(event.get('error', {})))
            return event
        event = await receive()
        if event['type'] != 'session.created':
            raise RuntimeError('Expected session.created')
        in_rate = event['session']['audio']['input']['format']['rate']
        session = {'type': 'realtime', 'instructions': instructions, 'max_output_tokens': 768}
        if getattr(args, 'force_mixed', False):
            session['output_modalities'] = ['text', 'audio']
        await ws.send_json({'type': 'session.update', 'session': session})
        event = await receive()
        if event['type'] != 'session.updated':
            raise RuntimeError('Expected session.updated')

        with wave.open(str(source), 'rb') as wav:
            rate = wav.getframerate()
            samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2')
        samples = np.interp(np.arange(round(len(samples) * in_rate / rate)) * rate / in_rate,
                            np.arange(len(samples)), samples).astype('<i2')

        async def send_audio():
            # Pace each packet to arrive after its simulated microphone capture.
            origin = time.perf_counter()
            step = in_rate // 10
            for i in range(0, len(samples), step):
                chunk = samples[i:i + step]
                await asyncio.sleep(max(0, origin + (i + len(chunk)) / in_rate - time.perf_counter()))
                await ws.send_json({'type': 'input_audio_buffer.append',
                                    'audio': base64.b64encode(chunk.tobytes()).decode()})
            await ws.send_json({'type': 'response.create'})
        sender = asyncio.create_task(send_audio())
        try:
            while True:
                event = await receive()
                kind = event['type']
                if kind == 'response.output_text.delta':
                    text += event['delta']
                    if first_text is None:
                        first_text = time.perf_counter() - started
                    if len(text) > 16000:
                        raise RuntimeError('LFM text response exceeded trial limit')
                elif kind == 'response.output_audio.delta':
                    recording.append(base64.b64decode(event['delta']))
                    if len(recording.pcm) > 24000 * 2 * 60:
                        raise RuntimeError('LFM audio exceeded one-minute trial limit')
                elif kind == 'response.done':
                    await sender
                    return text, first_text, recording
        finally:
            if sender and not sender.done():
                sender.cancel()
                await asyncio.gather(sender, return_exceptions=True)


async def speak_qwen(client, args, query, headers, recording):
    """Speak verbatim Qwen clauses; LFM does not rewrite the answer."""
    # The Qwen response is capped at 192 tokens. Let it finish if TTS fails,
    # rather than deadlocking a producer on a full queue after its worker exits.
    queue = asyncio.Queue()
    chunks, text, consumed, first_text = [], '', 0, None

    async def synthesize():
        while (chunk := await queue.get()) is not None:
            before = len(recording.pcm)
            tts_input = chunk if getattr(args, 'raw_speech', False) else speech_text(chunk)
            async with client.post(args.lfm + '/tts', json={
                    'input': tts_input, 'lang': 'en', 'voice': 'female', 'seed': 42,
                    'stream': True, 'response_format': 'pcm', 'n_predict': 384}) as resp:
                resp.raise_for_status()
                if 'audio/pcm' not in resp.headers.get('Content-Type', ''):
                    raise RuntimeError('Unexpected LFM TTS response format')
                pending = b''
                async for data in resp.content.iter_chunked(4096):
                    pending += data
                    n = len(pending) // 4 * 4
                    if n:
                        audio = np.frombuffer(pending[:n], dtype='<f4')
                        recording.append((np.clip(audio, -1, 1) * 32767).astype('<i2').tobytes())
                        pending = pending[n:]
                    if len(recording.pcm) > 24000 * 2 * 90:
                        raise RuntimeError('Delegated speech exceeded trial limit')
                if pending:
                    raise RuntimeError('Truncated LFM speech frame')
            chunks.append({'text': chunk, 'tts_input': tts_input, 'audio_bytes': len(recording.pcm) - before})

    worker = asyncio.create_task(synthesize())
    try:
        persona = DEFAULT_PERSONA + ' Start with the answer or a concrete suggestion. Skip greetings and acknowledgments. Use short sentences.'
        body = {'messages': [{'role': 'system', 'content': persona},
                    *getattr(args, 'qwen_history', []),
                    {'role': 'user', 'content': query}], 'stream': True, 'temperature': 0,
                'max_tokens': 192}
        request_headers = dict(headers)
        reasoning = getattr(args, 'qwen_reasoning', 'off')
        if reasoning != 'off':
            request_headers['X-Assistant-Reasoning'] = reasoning
            body['max_tokens'] = 2048
        async with client.post(args.api + '/chat', json=body, headers=request_headers) as resp:
            resp.raise_for_status()
            async for event in upstream_events(resp):
                delta = event.get('choices', [{}])[0].get('delta', {}).get('content') or ''
                if delta and first_text is None:
                    first_text = time.perf_counter() - recording.started
                text += delta
                if len(text) > 16000:
                    raise RuntimeError('Qwen text exceeded trial limit')
                ready, consumed = spoken_chunks(text, consumed, clauses=True)
                for chunk in ready:
                    if worker.done():
                        await worker
                    await queue.put(chunk)
        ready, consumed = spoken_chunks(text, consumed, final=True, clauses=True)
        for chunk in ready:
            if worker.done():
                await worker
            await queue.put(chunk)
        await queue.put(None)
        await worker
        if not text.strip() or not recording.pcm:
            raise RuntimeError('Qwen delegation produced no answer or speech')
        return text, first_text, chunks
    finally:
        if not worker.done():
            worker.cancel()
        await asyncio.gather(worker, return_exceptions=True)


async def audio_turn(client, args, source, label, headers, hybrid):
    _, speech_end = read_question(source)
    started = time.perf_counter()
    text, first_text, recording = await lfm_response(
        client, args, source, started,
        (ROUTED_PROMPT if getattr(args, 'routing_policy', False) else HYBRID_PROMPT) if hybrid else DIRECT_PROMPT)
    native_text, native_done = text, time.perf_counter() - started
    (args.output / (label + '-native.json')).write_text(json.dumps({
        'text': text, 'first_text_s': first_text, 'complete_s': native_done,
        'events': recording.events}, indent=2))
    if recording.pcm:
        recording.save(args.output / (label + '-native.wav'))
    query = qwen_query(text) if hybrid else None
    chunks = []
    if query is not None:
        if recording.pcm:
            recording.save(args.output / (label + '-before-delegation.wav'))
        recording = Recording(started)
        text, first_text, chunks = await speak_qwen(client, args, query, headers, recording)
    audio = args.output / (label + '.wav')
    recording.save(audio)
    return {'case': label, 'path': 'hybrid' if hybrid else 'direct', 'question': source.name,
            'audio': str(audio), 'input_speech_end_s': speech_end,
            'input_duration_s': wave_duration(source), 'native_text': native_text,
            'delegated_query': query, 'native_done_s': native_done,
            'first_text_after_speech_end_s': None if first_text is None else first_text - speech_end,
            'first_audio_packet_after_speech_end_s':
                recording.events[0]['arrival_s'] - speech_end if recording.events else None,
            'first_non_silent_playback_s': recording.onset(speech_end),
            'complete_after_speech_end_s': time.perf_counter() - started - speech_end,
            'full_text': text, 'chunks': chunks, 'events': recording.events,
            'output_duration_s': len(recording.pcm) / 48000}


def wave_duration(path):
    with wave.open(str(path), 'rb') as wav:
        return wav.getnframes() / wav.getframerate()


async def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    headers = {'Authorization': 'Bearer ' + api_token()}
    rows = []
    anchor = [{'role': 'user', 'content': 'This is a continuing voice trial. Answer the following questions briefly.'},
              {'role': 'assistant', 'content': 'Ready.'}]
    histories = {path: list(anchor) if args.prepared else [] for path in args.paths}
    # Long microphone turns outlive some local HTTP keepalive windows. Fresh
    # connections prevent a stale pooled POST from failing during delegation.
    async with ClientSession(timeout=ClientTimeout(total=240, sock_read=120),
                             connector=TCPConnector(force_close=True)) as client:
        for repeat in range(-1, args.repeats):
            sources = args.questions[:1] if repeat == -1 else args.questions
            for source in sources:
                paths = args.paths if repeat % 2 else list(reversed(args.paths))
                for path in paths:
                    label = f'{"warmup" if repeat == -1 else repeat+1}-{source.stem}-{path}'
                    args.history = histories[path]
                    args.qwen_history = histories[path]
                    try:
                        if path == 'pipeline':
                            row = await pipeline(client, args, source, label, headers)
                            row['path'] = path
                            recording = Recording(0)
                            with wave.open(row['audio'], 'rb') as wav:
                                recording.rate = wav.getframerate()
                                recording.pcm.extend(wav.readframes(wav.getnframes()))
                            recording.events = row['events']
                            row['first_non_silent_playback_s'] = recording.onset(row['input_speech_end_s'])
                        else:
                            row = await audio_turn(client, args, source, label, headers, path == 'hybrid')
                        async with client.post(args.api + '/transcribe',
                                data=Path(row['audio']).read_bytes(),
                                headers={**headers, 'Content-Type': 'audio/wav'}) as response:
                            response.raise_for_status()
                            row['spoken_transcript'] = (await response.json())['text']
                        row['ok'] = True
                        if args.prepared:
                            user_text = row.get('delegated_query') or row.get('request_user_text')
                            if user_text:
                                histories[path].extend([{'role': 'user', 'content': user_text},
                                                        {'role': 'assistant', 'content': row['full_text']}])
                    except Exception as exc:
                        row = {'case': label, 'question': source.name, 'path': path,
                               'ok': False, 'error': f'{type(exc).__name__}: {exc}'}
                    row['warmup'] = repeat == -1
                    rows.append(row)
                    (args.output / 'results.json').write_text(json.dumps(rows, indent=2))
                    print(json.dumps({k: v for k, v in row.items()
                                      if k not in ('events', 'text_deltas', 'native_text', 'full_text', 'chunks')}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lfm', type=loopback_url, default='http://127.0.0.1:8093')
    parser.add_argument('--api', type=loopback_url, default='http://127.0.0.1:8700')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--force-mixed', action='store_true', help='Force interleaved speech in diagnostic runs')
    parser.add_argument('--qwen-reasoning', choices=['off', 'low', 'medium', 'high'], default='off',
                        help='Thinking level for delegated requests only')
    parser.add_argument('--prepared', action='store_true', help='Retain a stable Qwen conversation anchor and history')
    parser.add_argument('--routing-policy', action='store_true', help='Explicitly keep everyday advice on LFM')
    parser.add_argument('--raw-speech', action='store_true', help='Disable number verbalization for diagnostic TTS runs')
    parser.add_argument('--paths', nargs='+', choices=['direct', 'hybrid', 'pipeline'],
                        default=['direct', 'hybrid', 'pipeline'])
    parser.add_argument('questions', nargs='+', type=Path)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 10:
        parser.error('Use 1–10 measured repetitions')
    args.answer_first, args.clauses, args.request_id_only = True, True, True
    asyncio.run(run(args))


if __name__ == '__main__':
    main()
