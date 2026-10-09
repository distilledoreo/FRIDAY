# Native OpenCode model selection

FRIDAY's native PC agent checks OpenRouter's model catalog and its official Artificial Analysis benchmark feed every 24 hours. It selects the highest **Intelligence Index** score among named, zero-priced, text-output models that support tools. Zero-priced previews qualify even without a `:free` suffix. Random routers, paid models and models with additional advertised charges are excluded.

Scores join exact canonical release identifiers first, then exact API identifiers; there is no fuzzy name matching or invented score. Models without a published score follow scored models. Equal scores use context size, then model ID as deterministic tie breakers. All cloud requests enforce a maximum prompt/completion price of zero.

The main assistant can use a stronger text-only model for reasoning and shell/file work. Screen tasks use `friday-desktop`, an OpenCode vision helper with its own descending-score list of free vision/tool models. A private, authenticated loopback broker sends this list to OpenRouter for provider-side failover, so the parent waits for the actual desktop result. It cannot choose a paid model or arbitrary endpoint. If all vision models fail, the helper reports an error; the local fallback does not pretend to see images.

The environment plugin blocks direct screen tools in the parent and blocks shell/file/task tools in the vision helper. Child sessions receive the parent's exact phone permission rules before their first model turn. Ask every time, Ask before changes, Full access, deny rules, approvals and cancellation retain their meaning. The helper cannot approve its own actions.

Each human turn starts with the current best registered model and stores its fallback order. Automatic fallback advances through that snapshot, skips removed/non-free candidates and ultimately uses the existing text-only local Qwen path with the shared GPU gate. Ranking refreshes do not relabel history or switch active turns. New catalog entries and helper configuration reload when OpenCode is idle; active tools and pending human requests defer reload. Previously saved index-based task records migrate using the existing OpenCode configuration.

Only public model metadata and scores are cached in `pc/model-ranking.json`, with private file permissions. A fresh cache survives API restart. Refresh failures retry after one hour and retain the last known scores/catalog with a stale status. A catalog older than three days disables cloud selection until it can be verified again. The zero-price routing cap remains enforced during the cache grace period. The daily loop fetches metadata only; it performs no model inference or account action. Refresh resumes on gateway startup; the computer must be running.

Authenticated `/workspace/pc/status` returns `model_ranking` and `desktop_ranking`, including score coverage, check times, source citation, staleness and errors. `benchmark_api_version` describes the feed API schema, not the AA index methodology version. An Intelligence Index score ranks published benchmark performance; it does not guarantee a model will perform every PC task well.

This update targets native PC tasks. Explicit isolated research jobs and existing schedules retain their separate cloud configuration and restrictions. No Android install, model-service change or prompt-cache edit is needed.

## Verification

Run the normal CPU-only backend suite with `python -m unittest discover -v -s desktop/agent/tests`. Ranking regressions cover exact release matching, score ordering/coverage, hidden costs, capability filters, daily refresh/restart caching, concurrent refresh, catalog expiry, network failure, model identity migration and active-session stability. Broker checks cover private authentication, fixed destination, paid overrides, zero-cost fallbacks, terminal failure and stream cleanup.

With native OpenCode installed, run `FRIDAY_NATIVE_PC_TEST=1 python -m unittest desktop.agent.tests.test_native_ranking -v`. It uses only an owned fake cloud and fake desktop: strongest text default, actual child vision routing, transmitted free fallback order and zero-price cap, blocked parent screen input, all three permission modes, rejected input, deferred active refresh, idle configuration reload and stable historical model identity. It makes no real inference call or desktop input.

Sources: [OpenRouter AA benchmark feed](https://openrouter.ai/docs/api/api-reference/benchmarks/list-benchmarks), [model catalog](https://openrouter.ai/docs/api/api-reference/models/get-models), [model failover](https://openrouter.ai/docs/guides/routing/model-fallbacks), [OpenCode agents](https://opencode.ai/docs/agents/), [plugins](https://opencode.ai/docs/plugins/).
