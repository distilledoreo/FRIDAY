"""HTTP service the phone calls. It is not an LLM server."""

from __future__ import annotations

import json
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from search_service.config import ServiceConfig
from search_service.fetcher import Crawl4AIPageFetcher, DisabledPageFetcher
from search_service.models import (
    PageFetchError,
    PageFetcher,
    SearchOptions,
    SearchProvider,
    SearchProviderError,
    UnknownSearchProvider,
)
from search_service.searxng import SearxngSearchProvider

MAX_QUERY_CHARS = 500
MAX_LIMIT = 10


def create_search_provider(config: ServiceConfig) -> SearchProvider:
    name = config.search_provider.strip().lower()
    if name == "searxng":
        return SearxngSearchProvider(config.searxng_url, timeout=config.searxng_timeout)
    # Extension point: add another SearchProvider implementation and branch on its name.
    # Paid hosted search (Bing, Google, SerpAPI, Tavily, and similar) is intentionally absent.
    raise UnknownSearchProvider(
        f"Unknown search provider '{config.search_provider}'. This service ships with SearXNG only."
    )


def create_page_fetcher(config: ServiceConfig) -> PageFetcher:
    name = config.page_fetcher.strip().lower()
    if name in {"none", "off", "disabled"}:
        return DisabledPageFetcher()
    if name == "crawl4ai":
        return Crawl4AIPageFetcher(
            timeout_seconds=config.fetch_timeout,
            text_chars=config.page_text_chars,
        )
    raise UnknownSearchProvider(
        f"Unknown page fetcher '{config.page_fetcher}'. Use crawl4ai or none."
    )


def handle_search(
    payload: Any,
    provider: SearchProvider,
    fetcher: PageFetcher,
    *,
    page_fetch_limit: int,
    page_text_chars: int,
) -> tuple[int, dict[str, Any]]:
    if not isinstance(payload, dict):
        return 400, {"error": "Body must be a JSON object."}
    query = payload.get("query")
    if not isinstance(query, str) or not query.strip():
        return 400, {"error": "query is required."}
    query = query.strip()
    if len(query) > MAX_QUERY_CHARS:
        return 400, {"error": f"query must be at most {MAX_QUERY_CHARS} characters."}
    try:
        limit = _read_limit(payload.get("limit", 5))
        fetch_pages = _read_fetch_pages(payload.get("fetch_pages", False))
    except ValueError as exc:
        return 400, {"error": str(exc)}
    try:
        found = provider.search(query, SearchOptions(limit=limit))
    except SearchProviderError as exc:
        return 502, {"error": str(exc)}

    results: list[dict[str, Any]] = []
    fetched_any = False
    for index, hit in enumerate(found.results):
        page_text = None
        page_error = None
        if fetch_pages and index < page_fetch_limit:
            if not _is_http_url(hit.url):
                page_error = "Only http and https pages can be fetched."
            else:
                try:
                    document = fetcher.fetch(hit.url)
                    page_text = document.text[:page_text_chars]
                    fetched_any = True
                except PageFetchError as exc:
                    page_error = str(exc)
        item: dict[str, Any] = {
            "title": hit.title,
            "url": hit.url,
            "snippet": hit.snippet,
            "score": hit.score,
            "page_text": page_text,
        }
        if page_error:
            item["page_error"] = page_error
        results.append(item)
    return 200, {
        "provider": found.provider,
        "fetched": fetched_any,
        "results": results,
    }


def make_handler(
    provider: SearchProvider,
    fetcher: PageFetcher,
    config: ServiceConfig,
) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            if urllib.parse.urlparse(self.path).path != "/health":
                self._send(404, {"error": "Not found."})
                return
            self._send(
                200,
                {
                    "ok": True,
                    "search_provider": provider.name,
                    "page_fetcher": fetcher.name,
                },
            )

        def do_POST(self) -> None:  # noqa: N802
            if urllib.parse.urlparse(self.path).path != "/search":
                self._send(404, {"error": "Not found."})
                return
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length).decode("utf-8") if length else ""
            try:
                payload = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                self._send(400, {"error": "Body was not valid JSON."})
                return
            status, body = handle_search(
                payload,
                provider,
                fetcher,
                page_fetch_limit=config.page_fetch_limit,
                page_text_chars=config.page_text_chars,
            )
            self._send(status, body)

        def log_message(self, fmt: str, *args: object) -> None:
            return

        def _send(self, status: int, body: dict[str, Any]) -> None:
            encoded = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

    return Handler


def serve(config: ServiceConfig) -> None:
    provider = create_search_provider(config)
    fetcher = create_page_fetcher(config)
    server = ThreadingHTTPServer((config.host, config.port), make_handler(provider, fetcher, config))
    print(
        f"Search service on http://{config.host}:{config.port} "
        f"search_provider={provider.name} page_fetcher={fetcher.name} searxng={config.searxng_url}",
        flush=True,
    )
    server.serve_forever()


def main() -> None:
    serve(ServiceConfig.from_env())


def _read_limit(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1 or value > MAX_LIMIT:
        raise ValueError(f"limit must be an integer from 1 to {MAX_LIMIT}.")
    return value


def _read_fetch_pages(value: Any) -> bool:
    if not isinstance(value, bool):
        raise ValueError("fetch_pages must be a boolean.")
    return value


def _is_http_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)
