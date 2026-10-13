"""Explicit user-configured model connections. Credentials remain in process memory."""
import time
import json
import uuid
from urllib.parse import urlsplit

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, Field, field_validator


class Connection(BaseModel):
    base_url: str = Field(min_length=1, max_length=2000)
    model: str = Field(min_length=1, max_length=200)
    api_key: str = Field(default='', max_length=4000)
    timeout_seconds: int = Field(default=90, ge=5, le=600)

    @field_validator('base_url')
    @classmethod
    def address(cls, value):
        value = value.strip().rstrip('/')
        u = urlsplit(value)
        if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment:
            raise ValueError('Use an HTTP or HTTPS model address without credentials, query or fragment')
        return value


class Connections:
    def __init__(self):
        self.values = {}
        self.leases = {}

    def add(self, connection):
        self.expire()
        if len(self.values) >= 32:
            raise HTTPException(429, 'Close an existing model connection first')
        ident = uuid.uuid4().hex
        self.values[ident] = (connection, time.monotonic())
        return ident

    def lease(self, ident, job):
        self.leases[job] = (ident, self.get(ident), time.monotonic())

    def expire(self):
        now = time.monotonic()
        self.values = {k: v for k, v in self.values.items() if now - v[1] < 86400}
        self.leases = {k: v for k, v in self.leases.items() if now - v[2] < 86400}

    def get(self, ident):
        self.expire()
        if ident not in self.values:
            raise HTTPException(410, 'Model connection ended. Reconnect this client before resuming the task.')
        connection, _ = self.values[ident]
        self.values[ident] = (connection, time.monotonic())
        return connection

    async def generate(self, ident, messages, tools, report, job=None):
        self.expire()
        lease = self.leases.get(job)
        connection = lease[1] if lease and lease[0] == ident else self.get(ident)
        answer = {'role': 'assistant', 'content': ''}
        calls = {}
        headers = {'Authorization': 'Bearer ' + connection.api_key} if connection.api_key else {}
        # No gateway credentials are forwarded to the configured model. Redirects cannot forward credentials elsewhere.
        async with httpx.AsyncClient(headers=headers, timeout=connection.timeout_seconds, follow_redirects=False) as client:
            async with client.stream('POST', connection.base_url + '/chat/completions', json={
                'model': connection.model, 'messages': messages, 'tools': tools, 'stream': True,
                'temperature': .3, 'max_tokens': 2048,
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
        if calls: answer['tool_calls'] = list(calls.values())
        return answer


def enable(router):
    connections = Connections()

    @router.post('/model-connections')
    async def create(body: Connection):
        return {'id': connections.add(body)}

    @router.delete('/model-connections/{ident}')
    async def close(ident: str):
        connections.values.pop(ident, None)
        return {'deleted': True}

    return connections
