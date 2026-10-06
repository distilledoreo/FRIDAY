"""Environment configuration. Search and the LLM endpoint are not configured together."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ServiceConfig:
    host: str = "127.0.0.1"
    port: int = 8765
    search_provider: str = "searxng"
    searxng_url: str = "http://127.0.0.1:8088"
    page_fetcher: str = "crawl4ai"
    page_fetch_limit: int = 2
    page_text_chars: int = 4000
    searxng_timeout: float = 20.0
    fetch_timeout: float = 45.0

    @staticmethod
    def from_env() -> "ServiceConfig":
        return ServiceConfig(
            host=os.environ.get("SEARCH_HOST", "127.0.0.1"),
            port=_int_env("SEARCH_PORT", 8765),
            search_provider=os.environ.get("SEARCH_PROVIDER", "searxng"),
            searxng_url=os.environ.get("SEARXNG_URL", "http://127.0.0.1:8088"),
            page_fetcher=os.environ.get("PAGE_FETCHER", "crawl4ai"),
            page_fetch_limit=_int_env("PAGE_FETCH_LIMIT", 2),
            page_text_chars=_int_env("PAGE_TEXT_CHARS", 4000),
            searxng_timeout=_float_env("SEARXNG_TIMEOUT", 20.0),
            fetch_timeout=_float_env("FETCH_TIMEOUT", 45.0),
        )


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


def _float_env(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return float(raw)
