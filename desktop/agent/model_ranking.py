"""Daily free-model catalog and Artificial Analysis intelligence ranking.

Inference uses OpenCode Zen's public free tier. OpenRouter is used only as a metadata
source for AA scores and exact release identifiers, never as an inference fallback.
Only public metadata is cached, never a credential, prompt, or model response.
"""
import asyncio
import contextlib
import json
import math
import os
from pathlib import Path
import re
import time

import httpx

ENDPOINT = 'https://opencode.ai/zen/v1'
BENCHMARK_ENDPOINT = 'https://openrouter.ai/api/v1'
METADATA_ENDPOINT = 'https://models.dev/api.json'
PROVIDER = 'opencode'
DAY = 24 * 60 * 60
RETRY = 60 * 60
MAX_CATALOG_AGE = 3 * DAY


def openrouter_key():
    key = os.environ.get('OPENROUTER_API_KEY')
    if not key:
        with contextlib.suppress(OSError, ValueError, KeyError, TypeError, AttributeError):
            key = json.loads((Path.home() / '.local/share/opencode/auth.json').read_text())['openrouter']['key']
    return key


def score_number(value):
    if isinstance(value, bool): return None
    try: number = float(value)
    except (ValueError, TypeError): return None
    return number if math.isfinite(number) and 0 <= number <= 100 else None


def zero_cost(value):
    if isinstance(value, dict): return all(zero_cost(item) for item in value.values())
    if isinstance(value, bool): return False
    try: return math.isfinite(float(value)) and float(value) == 0
    except (TypeError, ValueError, OverflowError): return False


def free_tool_models(catalog, metadata):
    """Intersect the live Zen list with published, explicitly zero-cost capabilities."""
    result = {}
    available = {row['id'] for row in catalog['data'] if isinstance(row, dict) and isinstance(row.get('id'), str)}
    for name, model in metadata[PROVIDER]['models'].items():
        try:
            if name not in available or not re.fullmatch(r'[\w.-]{1,180}', name, flags=re.ASCII): continue
            if model['id'] != name or model.get('status') == 'deprecated': continue
            prices = model['cost']
            if not all(key in prices for key in ('input', 'output')) or not zero_cost(prices): continue
            inputs, outputs = model['modalities']['input'], model['modalities']['output']
            if model.get('tool_call') is not True or 'text' not in inputs or 'text' not in outputs: continue
            npm = (model.get('provider') or {}).get('npm', metadata[PROVIDER]['npm'])
            if npm not in ('@ai-sdk/openai-compatible', '@ai-sdk/openai', '@ai-sdk/anthropic'): continue
            context, output = int(model['limit']['context']), int(model['limit']['output'])
            if context <= 0 or output <= 0: continue
            result[name] = {'id': name, 'canonical_slug': model.get('canonical_model_id'), 'npm': npm,
                            'name': model.get('name', name), 'reasoning': model.get('reasoning', False),
                            'temperature': model.get('temperature', False), 'interleaved': model.get('interleaved'),
                            'input_modalities': [kind for kind in ('text', 'image') if kind in inputs],
                            'context': context, 'output': min(output, context)}
        except (KeyError, ValueError, TypeError, OverflowError): continue
    return result


def benchmark_scores(payload, identities=None):
    result = {}
    for row in payload['data']:
        if row.get('source') != 'artificial-analysis': continue
        name, score = row.get('model_permaslug'), score_number(row.get('intelligence_index'))
        if not isinstance(name, str) or score is None: continue
        # If AA publishes several configurations for an exact model, use its best score.
        if name not in result or score > result[name]['score']:
            result[name] = {'score': score, 'benchmark_name': row.get('display_name')}
    # models.dev identifies the underlying model; the official catalog identifies its
    # exact current release. Join both exactly, never infer dates or stealth identities.
    for model in (identities or {}).get('data', []):
        name, release = model.get('id'), model.get('canonical_slug')
        if isinstance(name, str) and release in result and name not in result:
            result[name] = {**result[release], 'release': release}
    return result


class ModelRanking:
    def __init__(self, root, *, client=None, key=None, clock=time.time):
        self.path = Path(root) / 'model-ranking.json'
        self.client, self.key, self.clock = client, key or openrouter_key, clock
        self.lock = asyncio.Lock()
        self.catalog, self.scores, self.meta = {}, {}, {}
        self.catalog_checked = self.scores_checked = self.attempted = 0
        self.catalog_error = self.scores_error = None
        with contextlib.suppress(OSError, ValueError, KeyError, TypeError):
            cache = json.loads(self.path.read_text())
            if cache['schema'] == 2 and cache['provider'] == PROVIDER:
                self.catalog, self.scores, self.meta = cache['catalog'], cache['scores'], cache['meta']
                self.catalog_checked, self.scores_checked = cache['catalog_checked'], cache['scores_checked']

    def due(self):
        now = self.clock()
        fresh = all(0 <= now - stamp < DAY for stamp in (self.catalog_checked, self.scores_checked) if stamp)
        if self.catalog_checked and self.scores_checked and fresh: return False
        return not self.attempted or now - self.attempted >= RETRY

    async def refresh(self):
        async with self.lock:
            if not self.due(): return
            self.attempted = self.clock()
            if self.client is None:
                async with httpx.AsyncClient(timeout=20) as client: await self._fetch(client)
            else: await self._fetch(self.client)
            cache = {'schema': 2, 'provider': PROVIDER, 'catalog': self.catalog, 'scores': self.scores, 'meta': self.meta,
                     'catalog_checked': self.catalog_checked, 'scores_checked': self.scores_checked}
            temporary = self.path.with_suffix('.tmp')
            try:
                temporary.write_text(json.dumps(cache, allow_nan=False))
                os.chmod(temporary, 0o600)
                temporary.replace(self.path)
            except OSError:
                # Network results remain usable if the disk is temporarily unavailable.
                with contextlib.suppress(OSError): temporary.unlink()

    async def _fetch(self, client):
        async def catalog():
            response, metadata = await asyncio.gather(client.get(ENDPOINT + '/models'), client.get(METADATA_ENDPOINT))
            response.raise_for_status(); metadata.raise_for_status()
            payload, definitions = response.json(), metadata.json()
            if not isinstance(payload.get('data'), list) or not payload['data']: raise ValueError('catalog')
            self.catalog = free_tool_models(payload, definitions)
            self.catalog_checked, self.catalog_error = self.clock(), None

        async def scores():
            key = self.key()
            if not key: raise ValueError('credential')
            response, identities = await asyncio.gather(
                client.get(BENCHMARK_ENDPOINT + '/benchmarks', params={'source': 'artificial-analysis'},
                           headers={'Authorization': 'Bearer ' + key}),
                client.get(BENCHMARK_ENDPOINT + '/models'))
            response.raise_for_status(); identities.raise_for_status()
            payload = response.json()
            if not isinstance(payload.get('data'), list): raise ValueError('benchmarks')
            values = benchmark_scores(payload, identities.json())
            if not values: raise ValueError('benchmarks')
            self.scores, self.meta = values, {key: (payload.get('meta') or {}).get(key)
                                            for key in ('as_of', 'version', 'citation', 'source_url')}
            self.scores_checked, self.scores_error = self.clock(), None

        results = await asyncio.gather(catalog(), scores(), return_exceptions=True)
        # Never expose raw errors: a transport exception can contain authorization headers.
        if isinstance(results[0], Exception): self.catalog_error = 'Free-model catalog refresh failed; retaining the last verified catalog.'
        if isinstance(results[1], Exception): self.scores_error = 'Artificial Analysis refresh failed; retaining the last known scores.'

    def ranked(self, vision=False):
        if not self.catalog_checked or not 0 <= self.clock() - self.catalog_checked < MAX_CATALOG_AGE: return []
        rows = []
        for model in self.catalog.values():
            if vision and ('image' not in model['input_modalities'] or model.get('npm') == '@ai-sdk/anthropic'): continue
            match = None
            # Prefer the exact release, then its exact API id. Never strip date/version suffixes.
            for identifier in (model.get('canonical_slug'), model['id']):
                if identifier in self.scores: match = self.scores[identifier]; break
            rows.append({**model, 'score': match['score'] if match else None,
                         'benchmark_name': match.get('benchmark_name') if match else None})
        return sorted(rows, key=lambda m: (m['score'] is None, -(m['score'] or 0), -m['context'], m['id']))

    def status(self, vision=False):
        now = self.clock()
        return {'provider': 'OpenCode Zen (public free tier)', 'catalog_source': 'OpenCode Zen + models.dev',
                'source': 'Artificial Analysis via OpenRouter (benchmark metadata only)', 'metric': 'intelligence_index',
                'refresh_interval_seconds': DAY, 'catalog_checked': self.catalog_checked or None,
                'scores_checked': self.scores_checked or None, 'benchmark_as_of': self.meta.get('as_of'),
                'benchmark_api_version': self.meta.get('version'), 'citation': self.meta.get('citation'),
                'stale': any(not stamp or not 0 <= now - stamp < DAY for stamp in (self.catalog_checked, self.scores_checked)),
                'error': self.catalog_error or self.scores_error, 'requires_vision': vision,
                'models': self.ranked(vision)}
