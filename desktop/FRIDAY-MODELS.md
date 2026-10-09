# Daily free-model selection

FRIDAY's native PC agent checks the live **OpenCode Zen** model list and models.dev pricing/capabilities every 24 hours. It chooses the highest published **Artificial Analysis Intelligence Index** score among currently listed, explicitly zero-priced models supporting text output and tools. Paid models, retired entries, missing prices and extra advertised charges are excluded. Go's currently available free promotions also appear in Zen; Go subscription models are excluded.

Inference uses Zen's public free-tier credential. The helper has no Zen/Go billing key or OpenRouter key, and only Zen, the private desktop broker and local Qwen are enabled. A provider whitelist limits native models to the verified free catalog; the small model used for session titles is also free. There is no OpenRouter inference fallback for native PC tasks.

## Scores and coverage

OpenRouter's official Artificial Analysis feed is used **only for benchmark metadata**, through the existing host credential. Its public model catalog supplies exact release identifiers. A models.dev canonical identity must match that catalog's exact current release and the AA record; FRIDAY never guesses a version or an anonymous preview's identity. Models without a verified score come after scored models. Equal scores use context size, then model ID, for a stable order. A missing score is not a claim that a model is weak.

On October 8, 2026, the strongest verified free default is `opencode/ling-3.1-flash-free` (41.1); desktop vision starts with `mimo-v2.6-flash-free` (37.9). Some newer/anonymous previews and the contributor variant of Muse Spark lack a verified identity/score join and remain unscored. Scores describe the benchmarked configuration, not guaranteed FRIDAY task performance.

## Desktop work and fallback

The main assistant handles reasoning and shell work, including with text-only models. All desktop work goes to `friday-desktop`, a ranked image-capable helper that inherits the parent's exact phone permission rules. The primary assistant is blocked from direct screenshot/input calls; the helper cannot run shell/file tools or approve its own actions.

The private, loopback-authenticated desktop broker tries free Zen models in pinned descending order before streaming begins. It supports chat-completions models and adapts Zen Responses models while preserving image and tool history. An already-started stream is never replayed through another model. Errors are redacted and terminal after exhaustion. Anthropic-protocol models can run as the main assistant but are currently excluded from this vision adapter.

Main-task fallbacks use the turn's recorded order and skip models no longer eligible. Local Qwen is the final text-only fallback, using the existing foreground/GPU gate and background slot. A human follow-up adopts the latest ranking. Historical OpenRouter task identities remain intact; continuing a task chooses the new Zen chain.

## Refresh and validation

Only public metadata/scores are stored in private `pc/model-ranking.json`. The provider migration invalidates the old OpenRouter catalog. Fresh caches survive restarts. Failed refreshes retain the last verified data, expose stale/error status and retry hourly; catalog data expires after three days. Public free-tier authentication continues to prevent spending during the cache grace period. Refresh requires the PC/gateway to be running and makes no inference call. Native configuration changes wait until idle, including pending child tools and approvals.

Run the CPU backend suite with `python -m unittest discover -v -s desktop/agent/tests`. With native OpenCode installed, also run `FRIDAY_NATIVE_PC_TEST=1 python -m unittest desktop.agent.tests.test_native_ranking -v`. Owned fake models/desktop cover native provider routing, permissions, denials, stable history and deferred refresh. Broker tests cover fixed Zen targets, public auth, paid overrides, ordered fallback, image/tool translation, stream adaptation and cleanup. These do not benchmark real model quality or operate the user's desktop.

The isolated research/schedule subsystem retains its separate existing provider configuration. Android remains 0.18.1; this backend correction needs no APK update. Model services, prompt-cache code and outgoing activation are unchanged.

Sources: [OpenCode Zen](https://opencode.ai/docs/zen/), [OpenCode Go](https://opencode.ai/docs/go/), [models.dev](https://models.dev/), [official AA benchmark feed](https://openrouter.ai/docs/api/api-reference/benchmarks/list-benchmarks), [Artificial Analysis](https://artificialanalysis.ai/).
