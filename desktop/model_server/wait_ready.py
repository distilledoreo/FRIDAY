"""Wait for Strata and prepare the stable opening before the API starts."""
import argparse
import asyncio
import logging
import os
from pathlib import Path
import sys
import time
from types import SimpleNamespace

import httpx

if __package__:
    from .strata import StrataClient, StrataOpeningSnapshots
else:
    from strata import StrataClient, StrataOpeningSnapshots


async def wait_loaded(client, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            response = await client.get('/health', timeout=3)
            if response.status_code == 200 and response.json().get('loaded') is True:
                return
        except (httpx.HTTPError, ValueError, AttributeError):
            pass
        await asyncio.sleep(1)
    raise RuntimeError(f'Strata did not load within {seconds:g} seconds')


async def prepare(args):
    # Installed beside app.py; use the unchanged opening/date implementation.
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import prompt_cache
    async with httpx.AsyncClient(base_url=args.url) as raw:
        client = StrataClient(raw)
        await wait_loaded(client, args.timeout)
        gate = SimpleNamespace(exclusive=True, readers=0)
        openings = StrataOpeningSnapshots(client, gate, prompt_cache)
        # The API is not running, so this owns the model without a chat lease.
        await prepare_opening(openings)


async def prepare_opening(openings):
    """The prepared opening only saves the first chat a read of the system prompt: never keep the API down for it."""
    try:
        await openings.after_reload()
    except Exception as error:
        logging.warning('Strata opening: not prepared before start (%s: %s); the first chat reads it',
                        type(error).__name__, error)


def main():
    if os.environ.get('FRIDAY_LLM_BACKEND') != 'strata':
        return
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--timeout', type=float, default=900)
    args = parser.parse_args()
    if not 30 <= args.timeout <= 1800:
        parser.error('timeout must be between 30 and 1800 seconds')
    logging.basicConfig(level=logging.INFO)
    asyncio.run(prepare(args))


if __name__ == '__main__':
    main()
