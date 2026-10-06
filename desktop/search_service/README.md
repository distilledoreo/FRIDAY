# Desktop search service

A small HTTP service the Android app calls when the model requests `web_search`. It is not an LLM server and it does not import the phone's model client.

```
Android web_search tool
    → POST /search on this service
        → SearchProvider (default: SearXNG)
        → PageFetcher, only if fetch_pages is true (default: Crawl4AI)
```

Search snippets work without Crawl4AI. Crawling is opt-in per request.

This service implements one SearchProvider: SearXNG. Paid hosted search clients (Bing, Google, SerpAPI, Tavily, and similar) are not included. Another backend can implement `SearchProvider` and be selected in `create_search_provider` later.

## Run SearXNG

JSON output is off in a stock SearXNG config. This repo's [searxng-settings.yml](searxng-settings.yml) turns it on and disables the limiter for a private instance. Port 8088 avoids llama.cpp's usual 8080.

```bash
docker run --rm --name searxng \
  -p 8088:8080 \
  -v "$PWD/searxng-settings.yml:/etc/searxng/settings.yml:ro" \
  docker.io/searxng/searxng:latest
```

Run that from `desktop/search_service`. Check JSON:

```bash
curl 'http://127.0.0.1:8088/search?q=example&format=json'
```

HTTP 403 means `search.formats` does not include `json`. SearXNG still contacts whatever engines are enabled in its own settings. That engine list is SearXNG's concern. This service only talks to your SearXNG URL.

## Run this service

Python 3.11 or newer. No third-party packages are required for snippet search.

```bash
cd desktop/search_service
export SEARXNG_URL=http://127.0.0.1:8088
python -m search_service
```

Defaults:

| Variable | Default | Role |
| --- | --- | --- |
| `SEARCH_HOST` | `127.0.0.1` | Bind address. The Android emulator reaches this through `10.0.2.2`. A phone on Wi-Fi needs `SEARCH_HOST=0.0.0.0` and a firewall hole for port 8765. |
| `SEARCH_PORT` | `8765` | Port the phone calls. |
| `SEARCH_PROVIDER` | `searxng` | Search backend. Unknown names fail at startup. |
| `SEARXNG_URL` | `http://127.0.0.1:8088` | SearXNG base URL. |
| `SEARXNG_TIMEOUT` | `20` | Seconds to wait for SearXNG. |
| `PAGE_FETCHER` | `crawl4ai` | `none` skips extraction even if a call asks for pages. |
| `PAGE_FETCH_LIMIT` | `2` | Max pages to fetch on one opted-in search. |
| `PAGE_TEXT_CHARS` | `4000` | Cap on extracted text per page. |
| `FETCH_TIMEOUT` | `45` | Passed through to the Crawl4AI fetcher. |

Health check and a snippet search:

```bash
curl http://127.0.0.1:8765/health
curl http://127.0.0.1:8765/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"example","limit":3,"fetch_pages":false}'
```

The response is `provider`, `fetched`, and `results` of `title`, `url`, `snippet`, `score`, and `page_text`.

Point the Android app's **Search service address** at this process, not at the model:

| Phone | Address |
| --- | --- |
| Emulator | `http://10.0.2.2:8765` |
| USB, after `adb reverse tcp:8765 tcp:8765` | `http://127.0.0.1:8765` |
| Same Wi-Fi, service bound to `0.0.0.0` | `http://<pc-lan-ip>:8765` |

The model URL stays whatever serves `/v1/chat/completions`. Changing one does not change the other.

## Optional page text (Crawl4AI)

Leave `fetch_pages` false unless you need citations from the page body. To enable extraction:

```bash
pip install -r requirements-fetch.txt
crawl4ai-setup
```

`crawl4ai-setup` installs the browser Crawl4AI drives. If that command is missing, `python -m playwright install chromium` is the usual fallback. Crawl4AI is imported only when a request sets `fetch_pages` to true. If it is not installed, the search still returns snippets and a `page_error` on those hits.

```bash
curl http://127.0.0.1:8765/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"example","limit":2,"fetch_pages":true}'
```

Set `PAGE_FETCHER=none` to refuse extraction without uninstalling anything.

## Tests

```bash
cd desktop/search_service
python -m unittest discover -s tests -v
```

Tests use a stub SearchProvider and a fake SearXNG transport. They do not start Docker or download a browser.
