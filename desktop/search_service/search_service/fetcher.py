"""Optional page extraction. Search snippets do not require a fetcher."""

from __future__ import annotations

from search_service.models import PageDocument, PageFetchError


class DisabledPageFetcher:
    """Used when PAGE_FETCHER=none. Search still returns snippets."""

    name = "none"

    def fetch(self, url: str) -> PageDocument:
        raise PageFetchError("Page fetch is disabled on the search service.")


class Crawl4AIPageFetcher:
    """Default PageFetcher. Imports Crawl4AI only when a page is actually requested."""

    name = "crawl4ai"

    def __init__(self, timeout_seconds: float = 45.0, text_chars: int = 4000) -> None:
        self.timeout_seconds = timeout_seconds
        self.text_chars = text_chars

    def fetch(self, url: str) -> PageDocument:
        try:
            from crawl4ai import AsyncWebCrawler
        except ImportError as exc:
            raise PageFetchError(
                "Crawl4AI is not installed. From desktop/search_service run: "
                "pip install -r requirements-fetch.txt && crawl4ai-setup"
            ) from exc
        import asyncio

        try:
            return asyncio.run(self._fetch(AsyncWebCrawler, url))
        except PageFetchError:
            raise
        except Exception as exc:
            raise PageFetchError(f"Crawl4AI failed for {url}: {exc}") from exc

    async def _fetch(self, crawler_cls: type, url: str) -> PageDocument:
        run_config = _run_config(self.timeout_seconds)
        async with crawler_cls() as crawler:
            if run_config is None:
                result = await crawler.arun(url=url)
            else:
                result = await crawler.arun(url=url, config=run_config)
        if getattr(result, "success", True) is False:
            message = getattr(result, "error_message", None) or f"Crawl4AI could not read {url}."
            raise PageFetchError(str(message))
        text = _markdown_text(result).strip()
        if not text:
            raise PageFetchError(f"Crawl4AI returned no text for {url}.")
        metadata = getattr(result, "metadata", None)
        title = metadata.get("title") if isinstance(metadata, dict) else None
        return PageDocument(
            url=url,
            title=title if isinstance(title, str) else None,
            text=text[: self.text_chars],
        )


def _run_config(timeout_seconds: float) -> object | None:
    """Best-effort page timeout. Older Crawl4AI builds are called without a config."""
    try:
        from crawl4ai import CrawlerRunConfig
    except ImportError:
        return None
    try:
        return CrawlerRunConfig(page_timeout=int(timeout_seconds * 1000))
    except Exception:
        return None


def _markdown_text(result: object) -> str:
    markdown = getattr(result, "markdown", None)
    if isinstance(markdown, str):
        return markdown
    if markdown is None:
        return ""
    for attr in ("fit_markdown", "raw_markdown"):
        value = getattr(markdown, attr, None)
        if isinstance(value, str) and value.strip():
            return value
    return str(markdown)
