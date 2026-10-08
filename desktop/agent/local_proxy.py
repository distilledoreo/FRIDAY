"""Private, fixed-target local fallback. Shares FRIDAY's existing GPU gate."""
import asyncio
import json
import secrets
import time

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse


def local_payload(value):
    if not isinstance(value, dict) or not isinstance(value.get('messages'), list):
        raise HTTPException(422, 'Invalid local model request')
    value = dict(value)
    messages = []
    for message in value['messages']:
        message = dict(message)
        if isinstance(message.get('content'), list):
            message['content'] = [part if part.get('type') == 'text' else
                                  {'type': 'text', 'text': '[Image unavailable: the local fallback is text-only.]'}
                                  for part in message['content']]
        messages.append(message)
    value.update(messages=messages, id_slot=1, chat_template_kwargs={'enable_thinking': False})
    # A caller cannot select a paid/remote model or a different destination here.
    value['model'] = 'qwen3.8-27b'
    return value


def install(app, agent, gate, client_factory=None):
    router = APIRouter(prefix='/workspace/pc/internal/v1')
    inference = asyncio.Lock()

    @router.post('/chat/completions')
    async def completions(request: Request):
        expected = 'Bearer ' + agent.password
        if not request.client or request.client.host not in ('127.0.0.1', '::1') or not secrets.compare_digest(request.headers.get('authorization', ''), expected):
            raise HTTPException(401, 'Private local fallback')
        if not agent.settings()['enabled']: raise HTTPException(403, 'PC access is off')
        if gate is None: raise HTTPException(503, 'Local fallback requires the shared GPU gate')
        raw = await request.body()
        if len(raw) > 2 * 1024 * 1024: raise HTTPException(413, 'Local request too large')
        try: body = local_payload(json.loads(raw))
        except (ValueError, TypeError, AttributeError): raise HTTPException(422, 'Invalid local model request') from None
        await inference.acquire()
        acquired = False
        client = response = None
        try:
            deadline = time.monotonic() + 600
            while not gate.background_allowed():
                if not agent.settings()['enabled'] or await request.is_disconnected():
                    raise HTTPException(409, 'Local task cancelled')
                if time.monotonic() >= deadline or gate.phase == 'recovery_failed':
                    raise HTTPException(503, 'Local inference is unavailable')
                await asyncio.sleep(.25)
            if not agent.settings()['enabled'] or await request.is_disconnected(): raise HTTPException(409, 'Local task cancelled')
            await gate.acquire_chat()
            acquired = True
            client = (client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(600, connect=5), follow_redirects=False)))()
            response = await client.send(client.build_request('POST', 'http://127.0.0.1:8080/v1/chat/completions', json=body), stream=True)
            if response.status_code != 200:
                raise HTTPException(503, 'Local inference is unavailable')
        except BaseException:
            if response is not None: await response.aclose()
            if client is not None: await client.aclose()
            if acquired: await gate.release_chat()
            inference.release()
            raise

        async def stream():
            try:
                async for chunk in response.aiter_bytes(): yield chunk
            finally:
                await response.aclose()
                await client.aclose()
                await gate.release_chat()
                inference.release()
        return StreamingResponse(stream(), media_type='text/event-stream' if body.get('stream') else 'application/json', headers={'Cache-Control': 'no-store'})

    app.include_router(router)
