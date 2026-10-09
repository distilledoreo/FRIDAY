"""Adapter for the existing desktop assistant gateway (no new public listener)."""
import os
from pathlib import Path
from .workspace import install


def enable(app, auth, llm, search, fetch, fetch_request_type, data_dir):
    async def model(messages, tools):
        model_id = os.environ.get('ASSISTANT_MODEL')
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

    async def research(query):
        if not isinstance(query, str) or not 1 <= len(query) <= 500:
            raise ValueError('Search query must contain 1–500 characters')
        return await search(q=query, n=5)

    async def read_page(url):
        return await fetch(fetch_request_type(url=url, max_chars=16000))

    return install(app, auth, Path(data_dir), model, research, read_page)
