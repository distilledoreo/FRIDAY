"""Unit tests for the desktop search service. No network and no paid search APIs."""

from __future__ import annotations

import importlib.util
import json
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

from search_service.config import ServiceConfig
from search_service.models import PageDocument, PageFetchError, SearchHit, SearchOptions, SearchResults
from search_service.searxng import SearxngSearchProvider
from search_service.service import create_search_provider, handle_search, make_handler


class StubProvider:
    name = "stub"

    def __init__(self, hits: list[SearchHit]) -> None:
        self.hits = hits
        self.calls: list[tuple[str, SearchOptions]] = []

    def search(self, query: str, options: SearchOptions) -> SearchResults:
        self.calls.append((query, options))
        return SearchResults(provider=self.name, results=self.hits[: options.limit])


class StubFetcher:
    name = "stub-fetcher"

    def __init__(self) -> None:
        self.urls: list[str] = []
        self.fail = False

    def fetch(self, url: str) -> PageDocument:
        self.urls.append(url)
        if self.fail:
            raise PageFetchError("boom")
        return PageDocument(url=url, text="Extracted " + url, title="T")


class HandleSearchTests(unittest.TestCase):
    def test_snippets_do_not_crawl(self) -> None:
        provider = StubProvider(
            [SearchHit(title="A", url="https://a.example", snippet="snip", score=1.25)]
        )
        fetcher = StubFetcher()
        status, body = handle_search(
            {"query": "local models", "limit": 5, "fetch_pages": False},
            provider,
            fetcher,
            page_fetch_limit=2,
            page_text_chars=100,
        )
        self.assertEqual(200, status)
        self.assertEqual([], fetcher.urls)
        self.assertFalse(body["fetched"])
        self.assertEqual("stub", body["provider"])
        self.assertEqual("https://a.example", body["results"][0]["url"])
        self.assertIsNone(body["results"][0]["page_text"])
        self.assertEqual("local models", provider.calls[0][0])

    def test_fetch_pages_is_opt_in_and_a_fetch_error_keeps_the_snippet(self) -> None:
        provider = StubProvider(
            [
                SearchHit("A", "https://a.example", "one"),
                SearchHit("B", "https://b.example", "two"),
                SearchHit("C", "https://c.example", "three"),
            ]
        )
        fetcher = StubFetcher()
        status, body = handle_search(
            {"query": "docs", "limit": 3, "fetch_pages": True},
            provider,
            fetcher,
            page_fetch_limit=1,
            page_text_chars=1000,
        )
        self.assertEqual(200, status)
        self.assertEqual(["https://a.example"], fetcher.urls)
        self.assertTrue(body["fetched"])
        self.assertEqual("Extracted https://a.example", body["results"][0]["page_text"])
        self.assertIsNone(body["results"][1]["page_text"])

        fetcher.fail = True
        status, body = handle_search(
            {"query": "docs", "fetch_pages": True},
            provider,
            fetcher,
            page_fetch_limit=1,
            page_text_chars=1000,
        )
        self.assertEqual(200, status)
        self.assertFalse(body["fetched"])
        self.assertEqual("one", body["results"][0]["snippet"])
        self.assertIn("boom", body["results"][0]["page_error"])

    def test_missing_query_is_rejected(self) -> None:
        status, body = handle_search(
            {"limit": 5},
            StubProvider([]),
            StubFetcher(),
            page_fetch_limit=1,
            page_text_chars=100,
        )
        self.assertEqual(400, status)
        self.assertIn("query", body["error"])


class SearxngProviderTests(unittest.TestCase):
    def test_maps_json_results(self) -> None:
        def transport(url: str, timeout: float) -> tuple[int, str]:
            self.assertIn("format=json", url)
            self.assertIn("q=weather+paris", url)
            self.assertEqual(20.0, timeout)
            return 200, json.dumps(
                {
                    "results": [
                        {
                            "title": "Forecast",
                            "url": "https://weather.example",
                            "content": "Sunny",
                            "score": 2,
                        },
                        {"title": "Skip", "content": "no url"},
                    ]
                }
            )

        provider = SearxngSearchProvider("http://127.0.0.1:8088/", timeout=20.0, transport=transport)
        results = provider.search("weather paris", SearchOptions(limit=5))
        self.assertEqual("searxng", results.provider)
        self.assertEqual(1, len(results.results))
        self.assertEqual("Sunny", results.results[0].snippet)
        self.assertEqual(2.0, results.results[0].score)

    def test_json_disabled_is_explicit(self) -> None:
        provider = SearxngSearchProvider(
            "http://127.0.0.1:8088",
            transport=lambda url, timeout: (403, "forbidden"),
        )
        with self.assertRaises(Exception) as caught:
            provider.search("q", SearchOptions())
        self.assertIn("search.formats", str(caught.exception))

    def test_paid_provider_names_are_not_implemented(self) -> None:
        for name in ("serpapi", "tavily", "bing", "google"):
            with self.assertRaises(Exception) as caught:
                create_search_provider(
                    ServiceConfig(search_provider=name),
                )
            self.assertIn("SearXNG only", str(caught.exception))


class HttpContractTests(unittest.TestCase):
    def test_post_search_round_trip(self) -> None:
        provider = StubProvider([SearchHit("A", "https://a.example", "snip")])
        handler = make_handler(
            provider,
            StubFetcher(),
            ServiceConfig(page_fetch_limit=2, page_text_chars=100),
        )
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        port = server.server_address[1]
        import threading

        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/search",
                data=json.dumps({"query": "hello", "limit": 5, "fetch_pages": False}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                body = json.loads(response.read().decode())
            self.assertEqual("https://a.example", body["results"][0]["url"])
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as response:
                health = json.loads(response.read().decode())
            self.assertTrue(health["ok"])
            self.assertEqual("stub", health["search_provider"])
        finally:
            server.shutdown()
            server.server_close()


class Crawl4AIOptionalTests(unittest.TestCase):
    def test_missing_install_is_a_fetch_error(self) -> None:
        if importlib.util.find_spec("crawl4ai") is not None:
            self.skipTest("crawl4ai is installed")
        from search_service.fetcher import Crawl4AIPageFetcher

        with self.assertRaises(PageFetchError) as caught:
            Crawl4AIPageFetcher().fetch("https://example.com")
        self.assertIn("not installed", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
