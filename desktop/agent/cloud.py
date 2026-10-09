"""Free-only cloud broker. Credentials stay outside the reasoning container.

The host owns endpoint, model selection, current pricing checks and request limits.
No caller-selected paid model, proxy URL, extra provider, local model or fallback.
"""
import json
import time
from decimal import Decimal, InvalidOperation

import httpx

ENDPOINT = 'https://openrouter.ai/api/v1'
PRICE_KEYS = ('prompt', 'completion', 'request', 'image', 'web_search',
              'internal_reasoning', 'input_cache_read', 'input_cache_write')


def free_models(catalog):
    allowed = {}
    for model in catalog.get('data', []):
        identifier = model.get('id', '')
        pricing = model.get('pricing', {})
        if not (identifier.endswith(':free') or identifier == 'openrouter/free'):
            continue
        if 'prompt' not in pricing or 'completion' not in pricing: continue
        try:
            if any(Decimal(str(pricing.get(key, '0'))) != 0 for key in PRICE_KEYS): continue
        except (InvalidOperation, ValueError): continue
        allowed[identifier] = model
    return allowed


class FreeCloud:
    def __init__(self, api_key, model='openrouter/free', client=None, max_requests=30):
        if not api_key: raise ValueError('A cloud provider key is required')
        if not (model.endswith(':free') or model == 'openrouter/free'):
            raise ValueError('Only a free model may be configured')
        self._api_key = api_key
        self.model = model
        self.client = client or httpx.AsyncClient(timeout=90, trust_env=False, follow_redirects=False)
        self._owns_client = client is None
        self.catalog = {}
        self.checked = 0
        self.requests = 0
        self.max_requests = max_requests

    async def close(self):
        if self._owns_client: await self.client.aclose()

    async def verify(self):
        response = await self.client.get(ENDPOINT + '/models')
        response.raise_for_status()
        if len(response.content) > 8 * 1024 * 1024: raise ValueError('Model catalog is too large')
        self.catalog = free_models(response.json())
        self.checked = time.monotonic()
        if self.model not in self.catalog: raise RuntimeError('Configured free model is unavailable or no longer free')

    async def complete(self, request):
        if not isinstance(request, dict) or request.get('model') not in (None, self.model, 'friday-free'):
            raise ValueError('Caller cannot select another model')
        encoded = json.dumps(request, allow_nan=False).encode()
        if len(encoded) > 256 * 1024: raise ValueError('Cloud context exceeds 256 KB')
        if self.requests >= self.max_requests: raise RuntimeError('Task cloud request limit reached')
        if time.monotonic() - self.checked > 300 or self.model not in self.catalog: await self.verify()
        self.requests += 1
        # Copy only supported fields, so plugins, URL overrides and fallback
        # lists from the sandbox cannot bypass the free-only policy.
        body = {'model': self.model, 'messages': request.get('messages', []),
                'max_tokens': 4096, 'stream': False,
                'provider': {'max_price': {'prompt': 0, 'completion': 0}}}
        for key in ('tools', 'tool_choice', 'temperature'):
            if key in request: body[key] = request[key]
        response = await self.client.post(ENDPOINT + '/chat/completions', json=body,
                                         headers={'Authorization': 'Bearer ' + self._api_key})
        response.raise_for_status()
        if len(response.content) > 2 * 1024 * 1024: raise ValueError('Cloud response exceeds 2 MB')
        result = response.json()
        # Never print authorization, provider error response bodies or secrets.
        if 'choices' not in result: raise RuntimeError('Cloud provider did not return a completion')
        return result
