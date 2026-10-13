"""Adapter for the existing desktop assistant gateway (no new public listener)."""
import os
import json
from pathlib import Path
from .workspace import install


def enable(app, auth, llm, search, fetch, fetch_request_type, data_dir):
    async def model(messages, tools, model_id=None):
        model_id = model_id or os.environ.get('ASSISTANT_MODEL')
        if not model_id:
            response = await llm.get('/v1/models')
            response.raise_for_status()
            model_id = response.json()['data'][0]['id']
        response = await llm.post('/v1/chat/completions', json={
            'model': model_id, 'messages': messages, 'tools': tools, 'stream': False,
            'temperature': 0.3, 'max_tokens': 2048, 'chat_template_kwargs': {'enable_thinking': False},
            'id_slot': 1,  # background slot: keeps the phone chat's cache intact
        })
        response.raise_for_status()
        return response.json()['choices'][0]['message']

    async def stream_model(messages, tools, report, model_id=None):
        model_id = model_id or os.environ.get('ASSISTANT_MODEL')
        if not model_id:
            response = await llm.get('/v1/models'); response.raise_for_status()
            model_id = response.json()['data'][0]['id']
        answer = {'role': 'assistant', 'content': ''}
        calls = {}
        async with llm.stream('POST', '/v1/chat/completions', json={
            'model': model_id, 'messages': messages, 'tools': tools, 'stream': True,
            'temperature': .3, 'max_tokens': 2048, 'chat_template_kwargs': {'enable_thinking': False}, 'id_slot': 1,
        }) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if not line.startswith('data: '): continue
                data = line[6:]
                if data == '[DONE]': break
                choices = json.loads(data).get('choices') or []
                if not choices: continue
                delta = choices[0].get('delta') or {}
                if delta.get('content'):
                    answer['content'] += delta['content']
                    await report(answer['content'])
                for part in delta.get('tool_calls') or []:
                    call = calls.setdefault(part['index'], {'id': '', 'type': 'function', 'function': {'name': '', 'arguments': ''}})
                    if part.get('id'): call['id'] = part['id']
                    for key in ('name', 'arguments'):
                        call['function'][key] += (part.get('function') or {}).get(key) or ''
        if calls: answer['tool_calls'] = [calls[i] for i in sorted(calls)]
        return answer
    model.stream = stream_model

    async def research(query):
        if not isinstance(query, str) or not 1 <= len(query) <= 500:
            raise ValueError('Search query must contain 1–500 characters')
        return await search(q=query, n=5)

    async def read_page(url):
        return await fetch(fetch_request_type(url=url, max_chars=16000))

    return install(app, auth, Path(data_dir), model, research, read_page)


def loopback(app, token):
    """Calls this gateway's own routes in-process with its token, for chat tools; nothing leaves the process."""
    import httpx
    from .chat_tools import ToolError
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://friday.internal',
                               headers={'Authorization': f'Bearer {token}'}, timeout=httpx.Timeout(30.0, read=900.0))

    async def call(method, path, body=None):
        response = await client.request(method, path, json=body)
        try:
            value = response.json()
        except ValueError:
            value = response.text
        if response.status_code >= 400:
            detail = value.get('detail') if isinstance(value, dict) else value
            raise ToolError(f'{response.status_code}: {str(detail)[:500]}')
        return value
    return call
