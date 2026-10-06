"""SearXNG-backed SearchProvider. This module does not talk to paid search APIs."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import Any

from search_service.models import SearchHit, SearchOptions, SearchProviderError, SearchResults

Transport = Callable[[str, float], tuple[int, str]]


class SearxngSearchProvider:
    name = "searxng"

    def __init__(
        self,
        base_url: str,
        timeout: float = 20.0,
        transport: Transport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._transport = transport or _urlopen_transport

    def search(self, query: str, options: SearchOptions) -> SearchResults:
        cleaned = query.strip()
        if not cleaned:
            raise SearchProviderError("query must not be blank.")
        url = self.base_url + "/search?" + urllib.parse.urlencode(
            {"q": cleaned, "format": "json"}
        )
        try:
            status, body = self._transport(url, self.timeout)
        except SearchProviderError:
            raise
        except Exception as exc:
            raise SearchProviderError(f"Can't reach SearXNG at {self.base_url}. {exc}") from exc
        if status == 403:
            raise SearchProviderError(
                "SearXNG refused JSON search (HTTP 403). "
                "Enable json under search.formats in settings.yml."
            )
        if status < 200 or status >= 300:
            raise SearchProviderError(f"SearXNG returned HTTP {status}. {body[:300]}")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as exc:
            raise SearchProviderError("SearXNG did not return JSON.") from exc
        if not isinstance(payload, dict):
            raise SearchProviderError("SearXNG did not return a JSON object.")
        raw_results = payload.get("results", [])
        if not isinstance(raw_results, list):
            raise SearchProviderError("SearXNG results were not a list.")
        hits = [_hit(item) for item in raw_results]
        hits = [hit for hit in hits if hit is not None][: options.limit]
        return SearchResults(provider=self.name, results=hits)


def _hit(item: Any) -> SearchHit | None:
    if not isinstance(item, dict):
        return None
    url = item.get("url")
    if not isinstance(url, str) or not url.strip():
        return None
    title = item.get("title")
    content = item.get("content")
    score = item.get("score")
    return SearchHit(
        title=title.strip() if isinstance(title, str) else "",
        url=url.strip(),
        snippet=content.strip() if isinstance(content, str) else "",
        score=float(score) if isinstance(score, (int, float)) and not isinstance(score, bool) else None,
    )


def _urlopen_transport(url: str, timeout: float) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "local-android-assistant-search",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        return exc.code, detail
    except urllib.error.URLError as exc:
        raise SearchProviderError(f"Can't reach SearXNG. {exc.reason}") from exc
