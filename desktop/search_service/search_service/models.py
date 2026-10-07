"""Search and page-fetch contracts. Providers implement these; the HTTP layer does not care which one."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class SearchOptions:
    limit: int = 5


@dataclass(frozen=True)
class SearchHit:
    title: str
    url: str
    snippet: str
    score: float | None = None


@dataclass(frozen=True)
class SearchResults:
    provider: str
    results: list[SearchHit]


class SearchProvider(Protocol):
    name: str

    def search(self, query: str, options: SearchOptions) -> SearchResults:
        """Return snippet hits. Do not fetch page bodies here."""


@dataclass(frozen=True)
class PageDocument:
    url: str
    text: str
    title: str | None = None


class PageFetcher(Protocol):
    name: str

    def fetch(self, url: str) -> PageDocument:
        """Extract readable text from one URL. Called only when a search asks for pages."""


class SearchProviderError(Exception):
    """The configured search backend could not answer."""


class PageFetchError(Exception):
    """One page could not be extracted. The search snippets can still be returned."""


class UnknownSearchProvider(Exception):
    """No SearchProvider is registered under that name."""
