import json
import tempfile
import unittest
from pathlib import Path

import httpx

from desktop.agent.model_ranking import DAY, RETRY, MAX_CATALOG_AGE, ModelRanking, free_tool_models, benchmark_scores, ENDPOINT, BENCHMARK_ENDPOINT


def model(name, *, vision=True, price='0', tools=True, canonical=None, context=100000):
    return {'id': name, 'canonical_model_id': canonical,
            'modalities': {'input': ['text', 'image'] if vision else ['text'], 'output': ['text']},
            'cost': {'input': price, 'output': '0'}, 'tool_call': tools,
            'limit': {'context': context, 'output': 8192}}


def metadata(rows):
    return {'opencode': {'npm': '@ai-sdk/openai-compatible', 'models': {row['id']: row for row in rows}}}


def benchmark(name, score, source='artificial-analysis'):
    return {'model_permaslug': name, 'intelligence_index': score, 'source': source, 'display_name': name}


class RankingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1000000
        self.calls = []
        self.catalog = metadata([model('weak-free', canonical='vendor/weak-20260101'),
                                 model('strong-free', canonical='vendor/strong-20260101'),
                                 model('unknown-free'), model('text-preview', vision=False)])
        self.benchmarks = {'data': [benchmark('vendor/weak-20260101', 12), benchmark('vendor/strong-20260101', 55),
                                    benchmark('text-preview', 60)], 'meta': {'as_of': '2026-10-08', 'version': 'v1'}}
        self.fail_catalog = self.fail_scores = False
        async def respond(request):
            self.calls.append(request)
            if request.url.host == 'models.dev':
                return httpx.Response(503 if self.fail_catalog else 200, json=self.catalog)
            if str(request.url).startswith(ENDPOINT + '/models'):
                return httpx.Response(503 if self.fail_catalog else 200, json={'data': [{'id': name} for name in self.catalog['opencode']['models']]})
            if request.url.path.endswith('/models'):
                return httpx.Response(200, json={'data': []})
            return httpx.Response(503 if self.fail_scores else 200, json=self.benchmarks)
        self.client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        self.ranker = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)

    async def asyncTearDown(self):
        await self.client.aclose()
        self.tmp.cleanup()

    async def test_exact_canonical_scores_descending_and_unscored_last(self):
        await self.ranker.refresh()
        self.assertEqual([m['id'] for m in self.ranker.ranked()], ['text-preview', 'strong-free', 'weak-free', 'unknown-free'])
        self.assertEqual([m['score'] for m in self.ranker.ranked(vision=True)], [55, 12, None])
        self.assertEqual(next(c for c in self.calls if c.url.path.endswith('/benchmarks')).url.params['source'], 'artificial-analysis')
        self.assertEqual(next(c for c in self.calls if c.url.path.endswith('/benchmarks')).headers['Authorization'], 'Bearer private-fixture-key')

    async def test_daily_refresh_and_restart_cache_do_not_repeat_requests(self):
        await self.ranker.refresh()
        await self.ranker.refresh()
        restored = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)
        await restored.refresh()
        self.assertEqual(len(self.calls), 4)
        self.now += DAY
        self.benchmarks['data'][0]['intelligence_index'] = 70
        await restored.refresh()
        self.assertEqual(len(self.calls), 8)
        self.assertEqual(restored.ranked()[0]['id'], 'weak-free')
        self.assertFalse(restored.status()['stale'])

    async def test_score_outage_retains_last_scores_but_applies_current_prices(self):
        await self.ranker.refresh()
        self.now += DAY
        self.fail_scores = True
        self.catalog['opencode']['models']['strong-free']['cost']['output'] = '0.01'
        await self.ranker.refresh()
        self.assertEqual([m['id'] for m in self.ranker.ranked(vision=True)], ['weak-free', 'unknown-free'])
        self.assertTrue(self.ranker.status()['stale'])
        self.assertIsNotNone(self.ranker.status()['error'])
        await self.ranker.refresh()
        self.assertEqual(len(self.calls), 8)
        self.now += RETRY
        self.fail_scores = False
        await self.ranker.refresh()
        self.assertFalse(self.ranker.status()['stale'])

    async def test_old_catalog_expires_to_no_cloud_after_outage(self):
        await self.ranker.refresh()
        self.fail_catalog = self.fail_scores = True
        self.now += DAY
        await self.ranker.refresh()
        self.assertTrue(self.ranker.ranked())
        self.now += MAX_CATALOG_AGE
        await self.ranker.refresh()
        self.assertEqual(self.ranker.ranked(), [])

    async def test_missing_scores_are_never_fabricated_and_cache_has_no_credentials(self):
        self.fail_scores = True
        await self.ranker.refresh()
        self.assertTrue(all(row['score'] is None for row in self.ranker.ranked()))
        self.assertNotIn('private-fixture-key', self.ranker.path.read_text())
        self.assertNotIn('Authorization', json.dumps(self.ranker.status()))
        self.assertEqual(self.ranker.path.stat().st_mode & 0o777, 0o600)

    async def test_removed_release_never_inherits_similarly_named_score(self):
        self.benchmarks['data'] = [benchmark('vendor/strong-20250101', 99), benchmark('vendor/stronger', 88)]
        await self.ranker.refresh()
        self.assertTrue(all(row['score'] is None for row in self.ranker.ranked()))

    async def test_concurrent_refresh_is_coalesced(self):
        import asyncio
        await asyncio.gather(*(self.ranker.refresh() for _ in range(10)))
        self.assertEqual(len(self.calls), 4)

    def test_free_eligibility_rejects_hidden_costs_missing_live_entries_and_deprecated(self):
        hidden = model('image-cost-free'); hidden['cost']['image'] = '0.001'
        invalid = model('malformed-free'); invalid['cost'].pop('input')
        retired = model('retired-free'); retired['status'] = 'deprecated'
        tiered = model('tiered-free'); tiered['cost']['over_200k'] = {'input': 1, 'output': 0}
        rows = [model('free'), model('free-preview'), model('paid-free', price='1'),
                model('notools-free', tools=False), model('not-live-free'), hidden, invalid, retired, tiered]
        live = {'data': [{'id': row['id']} for row in rows if row['id'] != 'not-live-free']}
        self.assertEqual(set(free_tool_models(live, metadata(rows))), {'free', 'free-preview'})

    def test_release_identity_join_does_not_guess_versions_or_stealth_models(self):
        scores = benchmark_scores({'data': [benchmark('vendor/known-20261008', 50), benchmark('vendor/retired-20250101', 99)]},
            {'data': [{'id': 'vendor/known', 'canonical_slug': 'vendor/known-20261008'},
                      {'id': 'vendor/retired', 'canonical_slug': 'vendor/retired-20261008'}]})
        self.assertEqual(scores['vendor/known']['score'], 50)
        self.assertNotIn('vendor/retired', scores)

    async def test_openrouter_catalog_cache_is_invalidated_on_provider_migration(self):
        self.ranker.path.write_text(json.dumps({'schema': 1, 'catalog': {'wrong/provider:free': {}}, 'scores': {}, 'meta': {},
            'catalog_checked': self.now, 'scores_checked': self.now}))
        restored = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'fixture', clock=lambda: self.now)
        self.assertEqual(restored.ranked(), [])
        await restored.refresh()
        self.assertEqual(restored.ranked()[0]['id'], 'text-preview')
        self.assertEqual(json.loads(restored.path.read_text())['provider'], 'opencode')

    def test_non_intelligence_sources_invalid_scores_and_duplicate_configurations(self):
        rows = [benchmark('v/a', 10), benchmark('v/a', 25), benchmark('v/b', 99, source='design-arena')]
        rows += [benchmark('v/'+str(i), score) for i, score in enumerate((None, float('nan'), float('inf'), True, -1, 101))]
        self.assertEqual(benchmark_scores({'data': rows}), {'v/a': {'score': 25, 'benchmark_name': 'v/a'}})

    async def test_corrupt_cache_is_rebuilt(self):
        self.ranker.path.write_text('{bad json')
        restored = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)
        await restored.refresh()
        self.assertEqual(restored.ranked()[0]['score'], 60)
