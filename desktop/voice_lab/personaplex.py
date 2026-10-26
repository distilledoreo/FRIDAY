"""Loopback browser microphone bridge for audio.cpp's full-duplex speech endpoint.

Run with: python -m desktop.voice_lab.personaplex --upstream http://127.0.0.1:8997
Requires aiohttp. The native model process is configured and started separately.
"""
from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web

RATE = 24_000
VOICES = ('NATF0', 'NATF1', 'NATF2', 'NATF3', 'NATM0', 'NATM1', 'NATM2', 'NATM3')
@dataclass
class TrialState:
    upstream: str
    busy: bool = False
    client: ClientSession | None = None


STATE = web.AppKey('trial_state', TrialState)


DEFAULT_PERSONA = (
    'You are FRIDAY, a friendly voice assistant. Speak naturally in short, helpful sentences. '
    'This is a voice model trial. You have no access to tools, accounts, or saved memory.'
)


def loopback_host(host: str | None) -> bool:
    if host == 'localhost':
        return True
    try:
        return ipaddress.ip_address(host or '').is_loopback
    except ValueError:
        return False


@web.middleware
async def local_only(request, handler):
    # Reject browser requests aimed at a rebound hostname or initiated by another origin.
    if not loopback_host(request.remote) or not loopback_host(urlsplit('http://' + request.host).hostname):
        raise web.HTTPForbidden(text='This voice trial is available on loopback only.')
    origin = request.headers.get('Origin')
    if origin and origin != f'{request.scheme}://{request.host}':
        raise web.HTTPForbidden(text='Open the voice trial directly in this browser.')
    response = await handler(request)
    if not isinstance(response, web.WebSocketResponse):
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


async def upstream_events(response):
    """Parse bounded SSE records across arbitrary network chunk boundaries."""
    lines = []
    size = 0
    async for raw in response.content:
        if len(raw) > 1_000_000:
            raise ValueError('Audio response record is too large.')
        line = raw.decode('utf-8').rstrip('\r\n')
        if not line:
            if lines:
                data = '\n'.join(lines)
                if data == '[DONE]':
                    return
                yield json.loads(data)
            lines, size = [], 0
        elif line.startswith('data:'):
            value = line[5:].lstrip()
            size += len(value)
            if size > 1_000_000:
                raise ValueError('Audio response record is too large.')
            lines.append(value)
    if lines:
        data = '\n'.join(lines)
        if data != '[DONE]':
            yield json.loads(data)


async def relay(request):
    ws = web.WebSocketResponse(max_msg_size=32_000, heartbeat=20)
    await ws.prepare(request)
    if request.app[STATE].busy:
        await ws.send_json({'type': 'error', 'message': 'Another voice trial is active. Stop it first.'})
        await ws.close()
        return ws
    request.app[STATE].busy = True
    tasks = []
    queue = asyncio.Queue(maxsize=60)  # At most six seconds of 100 ms microphone chunks.
    try:
        first = await asyncio.wait_for(ws.receive(), 15)
        if first.type != WSMsgType.TEXT:
            raise ValueError('Send the voice settings before microphone audio.')
        settings = json.loads(first.data)
        voice = settings.get('voice', 'NATF2')
        persona = settings.get('persona', DEFAULT_PERSONA)
        if voice not in VOICES or not isinstance(persona, str) or not 1 <= len(persona) <= 2000:
            raise ValueError('Choose a supported voice and a persona of 1–2000 characters.')

        # Prime the model with one second of silence before asking the user to speak.
        # This keeps voice-prompt preparation from becoming queued microphone latency.
        for _ in range(10):
            queue.put_nowait(bytes(4800))
        await ws.send_json({'type': 'preparing'})

        async def microphone():
            async for message in ws:
                if message.type == WSMsgType.BINARY:
                    if not message.data or len(message.data) % 2 or len(message.data) > 9_600:
                        raise ValueError('Expected at most 200 ms of mono PCM16 audio per chunk.')
                    try:
                        queue.put_nowait(message.data)
                    except asyncio.QueueFull:
                        raise ValueError('The model is falling behind live audio. Stop and restart the trial.')
                elif message.type == WSMsgType.TEXT:
                    if json.loads(message.data).get('type') == 'finish':
                        await queue.put(None)
                        return
                elif message.type == WSMsgType.ERROR:
                    raise ValueError('The microphone connection failed.')
            await queue.put(None)

        async def audio_body():
            while True:
                chunk = await queue.get()
                if chunk is None:
                    return
                yield chunk

        async def model():
            params = {'model': 'personaplex-q8', 'input': persona, 'voice_id': voice,
                      'sample_rate': str(RATE), 'channels': '1', 'sample_format': 's16le',
                      'stream_format': 'sse', 'response_format': 'pcm'}
            async with request.app[STATE].client.post(request.app[STATE].upstream + '/v1/audio/speech/live',
                    params=params, data=audio_body(), headers={'Content-Type': 'application/octet-stream'}) as response:
                if response.status != 200:
                    detail = (await response.content.read(2000)).decode(errors='replace')
                    raise ValueError(f'Voice runtime returned HTTP {response.status}: {detail}')
                announced = False
                async for event in upstream_events(response):
                    if event.get('type') == 'speech.audio.delta' and not announced:
                        await ws.send_json({'type': 'ready', 'sample_rate': RATE})
                        announced = True
                    await ws.send_json(event)
            await ws.close()

        tasks = [asyncio.create_task(microphone()), asyncio.create_task(model())]
        done, _ = await asyncio.wait(tasks, timeout=180, return_when=asyncio.FIRST_COMPLETED)
        if not done:
            raise ValueError('The three-minute trial ended. Start a new session to continue.')
        for task in done:
            task.result()
        # A finish message closes upload but permits the remaining generated audio to drain.
        if tasks[0] in done and not ws.closed:
            await asyncio.wait_for(tasks[1], 30)
    except (ValueError, asyncio.TimeoutError, OSError) as exc:
        if not ws.closed:
            await ws.send_json({'type': 'error', 'message': str(exc) or 'Voice runtime timed out.'})
    except Exception:
        if not ws.closed:
            await ws.send_json({'type': 'error', 'message': 'The voice runtime stopped. Check its local log.'})
    finally:
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        request.app[STATE].busy = False
        await ws.close()
    return ws


def create_app(upstream='http://127.0.0.1:8997'):
    parsed = urlsplit(upstream)
    if parsed.scheme != 'http' or not loopback_host(parsed.hostname) or parsed.path not in ('', '/'):
        raise ValueError('The native runtime must use an HTTP loopback URL.')
    app = web.Application(middlewares=[local_only])
    app[STATE] = TrialState(upstream.rstrip('/'))

    async def lifecycle(app):
        async with ClientSession(timeout=ClientTimeout(total=None, connect=5, sock_read=60),
                                 read_bufsize=1_048_576) as client:
            app[STATE].client = client
            yield
    app.cleanup_ctx.append(lifecycle)

    async def index(request):
        return web.FileResponse(Path(__file__).with_name('personaplex.html'))

    async def status(request):
        try:
            async with app[STATE].client.get(app[STATE].upstream + '/health', timeout=ClientTimeout(total=3)) as r:
                data = await r.json()
                return web.json_response({'ready': r.status == 200, 'runtime': data, 'busy': app[STATE].busy})
        except Exception:
            return web.json_response({'ready': False, 'busy': app[STATE].busy})
    app.router.add_get('/', index)
    app.router.add_get('/status', status)
    app.router.add_get('/voice', relay)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', default='http://127.0.0.1:8997')
    parser.add_argument('--port', type=int, default=8998)
    args = parser.parse_args()
    web.run_app(create_app(args.upstream), host='127.0.0.1', port=args.port, access_log=None)


if __name__ == '__main__':
    main()
