import json
import tempfile
import unittest
from pathlib import Path

import httpx

from desktop.agent.model_ranking import DAY, RETRY, MAX_CATALOG_AGE, ModelRanking, free_tool_models, benchmark_scores


def model(name, *, vision=True, price='0', tools=True, canonical=None, context=100000):
    return {'id': name, 'canonical_slug': canonical, 'context_length': context,
            'architecture': {'input_modalities': ['text', 'image'] if vision else ['text'], 'output_modalities': ['text']},
            'pricing': {'prompt': price, 'completion': '0'}, 'supported_parameters': ['tools'] if tools else [],
            'top_provider': {'max_completion_tokens': 8192}}


def benchmark(name, score, source='artificial-analysis'):
    return {'model_permaslug': name, 'intelligence_index': score, 'source': source, 'display_name': name}


class RankingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1000000
        self.calls = []
        self.catalog = {'data': [model('vendor/weak:free', canonical='vendor/weak-20260101'),
                                 model('vendor/strong:free', canonical='vendor/strong-20260101'),
                                 model('vendor/unknown:free'), model('vendor/text-preview', vision=False)]}
        self.benchmarks = {'data': [benchmark('vendor/weak-20260101', 12), benchmark('vendor/strong-20260101', 55),
                                    benchmark('vendor/text-preview', 60)], 'meta': {'as_of': '2026-10-08', 'version': 'v1'}}
        self.fail_catalog = self.fail_scores = False
        async def respond(request):
            self.calls.append(request)
            if request.url.path.endswith('/models'):
                return httpx.Response(503 if self.fail_catalog else 200, json=self.catalog)
            return httpx.Response(503 if self.fail_scores else 200, json=self.benchmarks)
        self.client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        self.ranker = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)

    async def asyncTearDown(self):
        await self.client.aclose()
        self.tmp.cleanup()

    async def test_exact_canonical_scores_descending_and_unscored_last(self):
        await self.ranker.refresh()
        self.assertEqual([m['id'] for m in self.ranker.ranked()], ['vendor/text-preview', 'vendor/strong:free', 'vendor/weak:free', 'vendor/unknown:free'])
        self.assertEqual([m['score'] for m in self.ranker.ranked(vision=True)], [55, 12, None])
        self.assertEqual(self.calls[1].url.params['source'], 'artificial-analysis')
        self.assertEqual(self.calls[1].headers['Authorization'], 'Bearer private-fixture-key')

    async def test_daily_refresh_and_restart_cache_do_not_repeat_requests(self):
        await self.ranker.refresh()
        await self.ranker.refresh()
        restored = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)
        await restored.refresh()
        self.assertEqual(len(self.calls), 2)
        self.now += DAY
        self.benchmarks['data'][0]['intelligence_index'] = 70
        await restored.refresh()
        self.assertEqual(len(self.calls), 4)
        self.assertEqual(restored.ranked()[0]['id'], 'vendor/weak:free')
        self.assertFalse(restored.status()['stale'])

    async def test_score_outage_retains_last_scores_but_applies_current_prices(self):
        await self.ranker.refresh()
        self.now += DAY
        self.fail_scores = True
        self.catalog['data'][1]['pricing']['completion'] = '0.01'
        await self.ranker.refresh()
        self.assertEqual([m['id'] for m in self.ranker.ranked(vision=True)], ['vendor/weak:free', 'vendor/unknown:free'])
        self.assertTrue(self.ranker.status()['stale'])
        self.assertIsNotNone(self.ranker.status()['error'])
        await self.ranker.refresh()
        self.assertEqual(len(self.calls), 4)
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
        self.assertEqual(len(self.calls), 2)

    def test_free_eligibility_rejects_hidden_costs_and_unsupported_capabilities(self):
        hidden = model('vendor/image-cost:free'); hidden['pricing']['image'] = '0.001'
        invalid = model('vendor/malformed:free'); invalid['pricing'].pop('prompt')
        rows = [model('vendor/free:free'), model('vendor/free-preview'), model('vendor/paid:free', price='1'),
                model('vendor/notools:free', tools=False), model('openrouter/free'), hidden, invalid]
        self.assertEqual(set(free_tool_models({'data': rows})), {'vendor/free:free', 'vendor/free-preview'})

    def test_non_intelligence_sources_invalid_scores_and_duplicate_configurations(self):
        rows = [benchmark('v/a', 10), benchmark('v/a', 25), benchmark('v/b', 99, source='design-arena')]
        rows += [benchmark('v/'+str(i), score) for i, score in enumerate((None, float('nan'), float('inf'), True, -1, 101))]
        self.assertEqual(benchmark_scores({'data': rows}), {'v/a': {'score': 25, 'benchmark_name': 'v/a'}})

    async def test_corrupt_cache_is_rebuilt(self):
        self.ranker.path.write_text('{bad json')
        restored = ModelRanking(self.tmp.name, client=self.client, key=lambda: 'private-fixture-key', clock=lambda: self.now)
        await restored.refresh()
        self.assertEqual(restored.ranked()[0]['score'], 60)
