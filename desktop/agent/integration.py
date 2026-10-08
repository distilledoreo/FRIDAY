"""Optional gateway extension. No local model, GPU or shared profile access."""
import json
from pathlib import Path

from .api import install
from .cloud import FreeCloud
from .engine import OpenCodeEngine


def enable(app, auth, root, search):
    root = Path(root)
    config = root / 'runtime.json'
    engine = None
    detail = None
    if config.is_file():
        try:
            value = json.loads(config.read_text())
            # Credentials are read only by the host service and never mounted into
            # the sandbox, included in a prompt, or returned by a status endpoint.
            record = json.loads((Path.home() / '.local/share/opencode/auth.json').read_text()).get('openrouter', {})
            key = record.get('key')
            model = value.get('model', 'openrouter/free')
            if not key or not (model.endswith(':free') or model == 'openrouter/free'):
                raise ValueError('Free cloud configuration missing')
            async def research(query): return await search(q=query, n=5)
            engine = OpenCodeEngine(value['image'], lambda: FreeCloud(key, model=model), search=research)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            detail = 'Cloud agent unavailable: check its runtime configuration and OpenRouter credential. Local chat is unaffected.'
    outgoing_enabled=False
    outgoing_config=root/'outgoing.json'
    if outgoing_config.is_file():
        try:
            outgoing=json.loads(outgoing_config.read_text())
            outgoing_enabled=set(outgoing)=={'enabled','phone_and_provider_verified'} and outgoing['enabled'] is True and outgoing['phone_and_provider_verified'] is True
        except (OSError,ValueError,TypeError):pass
    return install(app, auth, root, engine, unavailable_detail=detail,outgoing_enabled=outgoing_enabled)
