"""Loopback-only Paradee trial with configurable CPU threads; no production routing changes."""
import argparse
import asyncio
import io
import threading
from pathlib import Path

from aiohttp import web

from .personaplex import local_only


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--port', type=int, default=9000)
    args = parser.parse_args()
    if not 1 <= args.threads <= 16:
        parser.error('Use 1–16 CPU threads')
    import numpy as np
    import soundfile as sf
    from paradee import Paradee, SAMPLE_RATE
    model = Paradee(model_path=str(args.models / 'paradee_int8.onnx'),
                    config_path=str(args.models / 'config.json'), threads=args.threads)
    lock = threading.Lock()

    def synthesize(text):
        with lock:
            samples = model(text)
        buffer = io.BytesIO()
        sf.write(buffer, np.clip(samples, -1, 1), SAMPLE_RATE, format='WAV', subtype='PCM_16')
        return buffer.getvalue()

    async def speak(request):
        data = await request.json()
        text = data.get('text') if isinstance(data, dict) else None
        if not isinstance(text, str) or not 1 <= len(text) <= 1000:
            raise web.HTTPBadRequest(text='Send 1–1000 characters of text')
        return web.Response(body=await asyncio.to_thread(synthesize, text), content_type='audio/wav')

    app = web.Application(middlewares=[local_only], client_max_size=16_384)
    app.router.add_post('/speak', speak)
    web.run_app(app, host='127.0.0.1', port=args.port, access_log=None)


if __name__ == '__main__':
    main()
