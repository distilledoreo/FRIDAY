"""Daily free-model catalog and Artificial Analysis intelligence ranking.

OpenRouter publishes AA's scores with canonical model identifiers. Join those identifiers
exactly; a similarly named model is not evidence of a benchmark score. Only public metadata
is cached, never a credential, prompt, or model response.
"""
import asyncio
import contextlib
import json
import math
import os
from pathlib import Path
import time

import httpx

ENDPOINT = 'https://openrouter.ai/api/v1'
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


def free_tool_models(catalog):
    result = {}
    for model in catalog['data']:
        try:
            name = model['id']
            # Named zero-priced previews also qualify. The random free router does not.
            if not isinstance(name, str) or '/' not in name or name.startswith('openrouter/'): continue
            prices = model['pricing']
            if any(float(prices[key]) != 0 for key in ('prompt', 'completion')): continue
            if any(float(value) != 0 for value in prices.values()): continue
            inputs = model['architecture']['input_modalities']
            outputs = model['architecture'].get('output_modalities', ['text'])
            if 'tools' not in model.get('supported_parameters', []) or 'text' not in inputs or 'text' not in outputs: continue
            provider = model.get('top_provider') or {}
            context = int(provider.get('context_length') or model.get('context_length') or 65536)
            output = int(provider.get('max_completion_tokens') or min(context, 16384))
            if context <= 0 or output <= 0: continue
            result[name] = {'id': name, 'canonical_slug': model.get('canonical_slug'),
                            'input_modalities': [kind for kind in ('text', 'image') if kind in inputs],
                            'context': context, 'output': min(output, context)}
        except (KeyError, ValueError, TypeError, OverflowError): continue
    return result


def benchmark_scores(payload):
    result = {}
    for row in payload['data']:
        if row.get('source') != 'artificial-analysis': continue
        name, score = row.get('model_permaslug'), score_number(row.get('intelligence_index'))
        if not isinstance(name, str) or score is None: continue
        # If AA publishes several configurations for an exact model, use its best score.
        if name not in result or score > result[name]['score']:
            result[name] = {'score': score, 'benchmark_name': row.get('display_name')}
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
            if cache['schema'] == 1:
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
            cache = {'schema': 1, 'catalog': self.catalog, 'scores': self.scores, 'meta': self.meta,
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
            response = await client.get(ENDPOINT + '/models')
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload.get('data'), list) or not payload['data']: raise ValueError('catalog')
            self.catalog = free_tool_models(payload)
            self.catalog_checked, self.catalog_error = self.clock(), None

        async def scores():
            key = self.key()
            if not key: raise ValueError('credential')
            response = await client.get(ENDPOINT + '/benchmarks', params={'source': 'artificial-analysis'},
                                        headers={'Authorization': 'Bearer ' + key})
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload.get('data'), list): raise ValueError('benchmarks')
            values = benchmark_scores(payload)
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
            if vision and 'image' not in model['input_modalities']: continue
            match = None
            # Prefer the exact release, then its exact API id. Never strip date/version suffixes.
            for identifier in (model.get('canonical_slug'), model['id'], model['id'].removesuffix(':free')):
                if identifier in self.scores: match = self.scores[identifier]; break
            rows.append({**model, 'score': match['score'] if match else None,
                         'benchmark_name': match.get('benchmark_name') if match else None})
        return sorted(rows, key=lambda m: (m['score'] is None, -(m['score'] or 0), -m['context'], m['id']))

    def status(self, vision=False):
        now = self.clock()
        return {'source': 'Artificial Analysis via OpenRouter', 'metric': 'intelligence_index',
                'refresh_interval_seconds': DAY, 'catalog_checked': self.catalog_checked or None,
                'scores_checked': self.scores_checked or None, 'benchmark_as_of': self.meta.get('as_of'),
                'benchmark_api_version': self.meta.get('version'), 'citation': self.meta.get('citation'),
                'stale': any(not stamp or not 0 <= now - stamp < DAY for stamp in (self.catalog_checked, self.scores_checked)),
                'error': self.catalog_error or self.scores_error, 'requires_vision': vision,
                'models': self.ranked(vision)}
