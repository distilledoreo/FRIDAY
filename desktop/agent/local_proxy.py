"""Private, fixed-target local fallback. Shares FRIDAY's existing GPU gate."""
import asyncio
import json
import os
import re
import secrets
import time

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse

from .prefix_warm import PrefixWarmer
from .prompt_slim import slim


def local_payload(value):
    if not isinstance(value, dict) or not isinstance(value.get('messages'), list):
        raise HTTPException(422, 'Invalid local model request')
    value = dict(slim(value))
    messages = []
    for message in value['messages']:
        message = dict(message)
        if isinstance(message.get('content'), list):
            message['content'] = [part if part.get('type') == 'text' else
                                  {'type': 'text', 'text': '[Image unavailable: the local fallback is text-only.]'}
                                  for part in message['content']]
        messages.append(message)
    # This private OpenCode route is separate from foreground chat. Keep coding
    # reasoning low even when a saved OpenCode session still sends thinking off.
    value.update(messages=messages, id_slot=1, reasoning_effort='low',
                 chat_template_kwargs={'enable_thinking': True, 'reasoning_effort': 'low'})
    value.pop('reasoning', None)
    # A caller cannot select a paid/remote model or a different destination here.
    value['model'] = 'qwen3.8-27b'
    if os.environ.get('FRIDAY_LLM_BACKEND') == 'strata':
        value['model'] = 'qwen3.8-flash-next'
        # Low effort controls the reasoning style; do not impose an additional
        # thinking cap. Explicit zero also overrides any backend default budget.
        value['reasoning_budget_tokens'] = 0
        value.pop('id_slot', None)
        # Strata's no-checkpoint mode (strata_checkpoint false) garbled answers; a one-off turn is simply not reused.
        if value.pop('cache_prompt', None) is False:
            value.pop('strata_prefix', None)
    return value


async def supports_parallel(client):
    """Only bypass foreground waiting when the loaded backend confirms two slots."""
    if os.environ.get('FRIDAY_LLM_BACKEND') != 'strata': return False
    try:
        response = await client.get('http://127.0.0.1:8080/v1/status', timeout=3)
        if response.status_code != 200: return False
        status = response.json()
        return status.get('loaded') is True and int(status.get('concurrency', {}).get('serving', 1)) >= 2
    except (httpx.HTTPError, ValueError, TypeError, AttributeError):
        return False


def install(app, agent, gate, client_factory=None):
    router = APIRouter(prefix='/workspace/pc/internal/v1')
    inference = asyncio.Lock()
    warmer = PrefixWarmer(agent.root, gate, inference, lambda: agent.settings()['enabled'], client_factory)
    app.router.add_event_handler('startup', warmer.start)
    app.router.add_event_handler('shutdown', warmer.stop)

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
        warmer.remember(body)
        await inference.acquire()
        acquired = False
        client = response = None
        error_content = None
        try:
            client = (client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(600, connect=5), follow_redirects=False)))()
            concurrent = await supports_parallel(client)
            deadline = time.monotonic() + 600
            while not gate.background_allowed(concurrent=concurrent):
                if not agent.settings()['enabled'] or await request.is_disconnected():
                    raise HTTPException(409, 'Local task cancelled')
                if time.monotonic() >= deadline or gate.phase == 'recovery_failed':
                    raise HTTPException(503, 'Local inference is unavailable')
                await asyncio.sleep(.25)
            if not agent.settings()['enabled'] or await request.is_disconnected(): raise HTTPException(409, 'Local task cancelled')
            await gate.acquire_chat()
            acquired = True
            response = await client.send(client.build_request('POST', 'http://127.0.0.1:8080/v1/chat/completions', json=body), stream=True)
            if response.status_code == 400:
                raw_error = await response.aread()
                # Strata rejects these requests before inference. A single retry can fit
                # the output budget after a large tool result, without truncating history.
                detail = raw_error.decode(errors='replace')
                room = re.search(r'at most (\d+) here', detail)
                if 'request exceeds the context window' in detail and room and int(room[1]) >= 256:
                    limit = min(int(room[1]), body.get('max_tokens', 8192), 8192)
                    await response.aclose()
                    body['max_tokens'] = limit
                    response = await client.send(client.build_request('POST', 'http://127.0.0.1:8080/v1/chat/completions', json=body), stream=True)
            if response.status_code == 400:
                detail = (await response.aread()).decode(errors='replace')
                if 'request exceeds the context window' in detail:
                    # OpenCode recognizes this standard code and compacts rather than
                    # retrying an unchanged oversized request as a server outage.
                    try:
                        error = json.loads(detail)
                        message = error.get('error', {}).get('message', detail)
                    except (ValueError, AttributeError): message = detail
                    error_content = json.dumps({'error': {'message': message,
                        'type': 'invalid_request_error', 'code': 'context_length_exceeded'}}).encode()
            if response.status_code not in (200, 400, 413, 422):
                raise HTTPException(503, 'Local inference is unavailable')
        except BaseException:
            if response is not None: await response.aclose()
            if client is not None: await client.aclose()
            if acquired: await gate.release_chat()
            inference.release()
            raise

        async def stream():
            try:
                if error_content is not None: yield error_content
                else:
                    async for chunk in response.aiter_bytes(): yield chunk
            finally:
                await response.aclose()
                await client.aclose()
                await gate.release_chat()
                inference.release()
        return StreamingResponse(stream(), status_code=response.status_code,
                                 media_type='text/event-stream' if response.status_code == 200 and body.get('stream') else 'application/json',
                                 headers={'Cache-Control': 'no-store'})

    app.include_router(router)
