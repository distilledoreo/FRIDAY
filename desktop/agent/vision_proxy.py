"""Private vision-helper broker: fixed OpenRouter target, ranked free fallbacks only."""
import json
import secrets

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse

from .model_ranking import ENDPOINT, openrouter_key


def vision_payload(value, agent):
    if not isinstance(value, dict) or not isinstance(value.get('messages'), list):
        raise HTTPException(422, 'Invalid vision request')
    allowed = {row['id'] for row in agent.ranking.ranked(vision=True)}
    chain = [name for name in agent.vision_chain if name in allowed]
    if value.get('model') not in agent.vision_chain or not chain:
        raise HTTPException(400, 'Free desktop vision is unavailable')
    # Caller options cannot introduce a paid model, router, plugin or provider override.
    body = {key: value[key] for key in ('messages', 'stream', 'tools', 'tool_choice', 'temperature', 'top_p',
                                       'max_tokens', 'max_completion_tokens', 'stop', 'parallel_tool_calls', 'response_format') if key in value}
    body.update(model=chain[0], models=chain, provider={'max_price': {'prompt': 0, 'completion': 0}})
    return body


def install(app, agent, client_factory=None, key_loader=openrouter_key):
    router = APIRouter(prefix='/workspace/pc/vision/v1')

    @router.post('/chat/completions')
    async def completions(request: Request):
        if not request.client or request.client.host not in ('127.0.0.1', '::1') or not secrets.compare_digest(
                request.headers.get('authorization', ''), 'Bearer ' + agent.password):
            raise HTTPException(401, 'Private desktop vision')
        settings = agent.settings()
        if not settings['enabled'] or not settings['computer_use']: raise HTTPException(403, 'Desktop access is off')
        raw = await request.body()
        if len(raw) > 16 * 1024 * 1024: raise HTTPException(413, 'Vision request too large')
        try: body = vision_payload(json.loads(raw), agent)
        except (ValueError, TypeError, AttributeError): raise HTTPException(422, 'Invalid vision request') from None
        key = key_loader()
        if not key: raise HTTPException(400, 'Free desktop vision is not configured')
        client = response = None
        try:
            client = (client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(180, connect=10), follow_redirects=False)))()
            response = await client.send(client.build_request('POST', ENDPOINT + '/chat/completions', json=body,
                headers={'Authorization': 'Bearer ' + key, 'HTTP-Referer': 'https://opencode.ai/', 'X-Title': 'FRIDAY desktop'}), stream=True)
            if response.status_code != 200:
                # OpenRouter has already tried the ranked list. A terminal error lets the
                # parent finish/report instead of leaving a task tool in infinite retry.
                raise HTTPException(400, 'Free vision models are unavailable or rate-limited; use the CLI or try later')
        except BaseException as error:
            if response is not None: await response.aclose()
            if client is not None: await client.aclose()
            if isinstance(error, httpx.HTTPError): raise HTTPException(400, 'Free desktop vision could not be reached') from None
            raise

        async def stream():
            try:
                async for chunk in response.aiter_bytes(): yield chunk
            finally:
                await response.aclose()
                await client.aclose()
        return StreamingResponse(stream(), media_type='text/event-stream' if body.get('stream') else 'application/json', headers={'Cache-Control':'no-store'})

    app.include_router(router)
