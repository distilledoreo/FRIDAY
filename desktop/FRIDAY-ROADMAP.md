# FRIDAY implementation roadmap

Goal: deliver the user's October 8 plan while preserving Claude's existing memory paging, 15-minute image limit, car microphone/echo/Android Auto support, and end-of-2024 cutoff guidance. Claude is experimenting with LLM TTFT concurrently. No live LLM or image inference, model restart, model configuration changes, or VRAM benchmarks are part of our current verification. Use fake models for inference behavior; keep background inference paused until experiments finish. CPU-only builds, SQLite backups, search and sandbox tests are allowed.

## Current delivery: 0.6

- Incognito threads remain in app memory; persistence checkpoints, title calls, archive, sync, fact suggestions, situation writes, and persistent workspace tools are blocked. Default memory retrieval uses approved facts only and does not update check-in counters. Fresh slate excludes memory retrieval/tools and starts a new blank private session. The app visibly labels the mode.
- Incognito uploads, generated files/images and image prompt/job records use a separate authenticated tmpfs namespace, not the ordinary Library or archive. Leaving cancels image work, restores chat if necessary, and deletes artifacts. Abandoned namespaces expire after 30 idle minutes; restart removes old namespaces. Phone cache is cleared on exit or next process start. File pickers/camera temporarily suspend background-exit cleanup; user originals are retained. Only anonymous GPU-recovery metadata remains in the ordinary recovery journal. Python analysis and persistent scheduling are unavailable inside incognito until they can preserve that boundary.
- Daily SQLite backups use SQLite's online backup API, integrity verification and atomic rename. Destination: Storage/FRIDAY/backups/memory. Retain seven snapshots within 2 GB and reserve 2 GB free; unrelated files and existing good snapshots survive failures. First live CPU-only snapshot verified.
- Home widget and quick-settings tile open foreground voice, with existing permissions and car microphone settings.
- Foreground chat/voice leases expire after disconnect and defer new background inference. In-flight inference is allowed to finish. A persistent maintenance pause stops new background/image jobs during model experiments without blocking direct foreground requests. It is not a substitute for coordinating model service changes.
- CPU-only grounding pre-check searches before replies involving recent/current, date-sensitive, price/weather, officeholders, regulations, health and recommendation cues. Respect explicit no-search requests and text transformations. Supply labeled untrusted evidence and source links, report verification failure without fabricated citations. This English trigger is conservative, not complete semantic understanding.
- Image quality testing deferred until GPU is available. Existing dimensions/step caps and 15-minute timeout remain intact; no speculative quality change without a render comparison.

## Current additional delivery: 0.8.1

The approved cloud agent is deployed on the PC. Activity reviews plans, requests explicit approval, polls running tasks, pages events, displays authenticated static source screenshots, and supports cancellation. FRIDAY branding/persona now spans chat, voice and agent. Opening Activity refreshes its state directly, including read-only updates during a chat.

OpenCode runs in a pinned Docker container with four CPU threads, 8 GB RAM, no GPU, no network and no personal files. A private Unix capability connects it to free-only cloud inference and constrained public search/page reads. Credentials stay in the host broker. Work is temporary and cleaned up. Screenshot previews disable JavaScript/external resources; they are not authenticated interactive browser sessions.

33 agent checks, including real Docker/OpenCode/Chromium tests, passed. A live approved synthetic public-page task produced a report and screenshot through the free cloud route. Android build, lint and all 129 tests passed for the final 0.7.1 release. No GPU/local model inference was used. API deployment preserved Claude's changes and the maintenance pause.

Sending, submitting, logging in, buying and deleting are deliberately unavailable until separate review/vault/executors are implemented. Proposed actions remain pending setup. Completion notifications open Activity using Android’s background polling, and a dedicated authenticated report endpoint feeds a read-only chat tool. Discuss in chat prepares a draft and preserves existing drafts/attachments; it never starts inference automatically. Activity shows reports and readable event summaries with optional raw details. The final 0.7.2 build/lint and 130 Android tests pass; seven changed API checks and the live authenticated report check pass. Deployment now refuses active agent work as well as active chat. Approved scheduled/recurring plans are now implemented. Each schedule binds first run, IANA display timezone, elapsed repeat interval and 1–100 run count to its approval fingerprint. Once/Hourly/Daily/Weekly phone controls use date/time pickers; minimum API recurrence is 15 minutes. Every run has its own task/report/events and a link from the schedule. Claiming is transactional, prevents duplicate/overlapping runs, skips missed intervals rather than catching up in a burst, and pauses on interrupted runs/unresolved actions. Cancelling a schedule revokes future and active runs. Repeats use elapsed time, with explicit DST/local-hour guidance. Nineteen changed schedule/API/approval checks pass, including asynchronous execution with a fake engine; live future approval/cancellation passed without inference. Large structured audit events now retain all Unicode/escaped content in bounded fragments rather than dropping long events. The engine permits a full bounded log line and labels report truncation explicitly; complete text stays in events. Physical phone verification and richer research interaction remain. The broader roadmap below remains active.

## Then: persona, credentials and automation

- Unify FRIDAY name/tone across chat, voice, prompts and agent. Preserve current user preferences rather than inventing familiarity or emotion.
- PC credential vault is implemented with authenticated encryption and a master key stored only in Linux Secret Service. Account-bound ciphertext prevents record substitution; locked vaults fail without plaintext fallback. A real synthetic desktop keyring test passed. Account metadata exposes id/provider/label only, with no credential export API or model IPC. Provider sign-in, host-only adapters and account UI remain unfinished; no actual account is connected.
- A separate deterministic outgoing review now binds destination, account, exact payload and side effect, rejects unsupported fields/attachments, header injection and likely credential leaks, and shows its checks in Activity. The review fingerprint matches the approval payload; account/executor wiring is still unavailable and approval returns 503 without being recorded. Sending/submitting/login/purchase/delete are not enabled.
- Scheduled/recurring agent plans are reviewable and cancellable with bounded run counts, missed-interval handling and restart isolation. Remaining automation work includes richer multi-step goal progress, proactive proposal cadence and robust provider quota/backoff reporting.

## Proactive accounts and hands-free

- Provider-independent account registry and secure removal. OAuth uses Microsoft/Google authorization screens with PKCE/state and least required scopes; provider app registrations/redirect configuration may require user-supplied credentials. Other email providers use explicit mail settings and protected secrets. Never claim authentication works without testing the user's account.
- Read email/calendar and propose drafts; send/modify only after approving the exact payload. Morning brief composes calendar/weather/situations/follow-ups with current citations and explicit missing-source states.
- Proactive suggestions are proposals only; no implicit action. Respect opt-outs, cadence, quiet hours and previously dismissed suggestions.
- Phone wake word requires an evaluated local detector, explicit microphone/background permission, visible foreground service and car-audio compatibility. Measure false activations/battery on a real device; never imply phone verification from JVM tests.

## Grounding expansion

Retrieve full public pages through existing SSRF-protected fetch, select supported passages, attach citations, express uncertainty, and check factual/research responses. Delegate larger research only to the approved agent. Preserve source provenance and bounded context; untrusted pages never override approval rules.

## Validation and completion

Each phase needs its own meaningful tests and deployable artifact. No mock-only claim of real OAuth, phone wake word, cloud agent or image quality. Preserve GPU embargo in live testing and do not mark the overall goal complete until remaining phases and required delivery are handled. Report specific prerequisites rather than inventing credentials or providers.

## Vault/audit validation checkpoint

0.8.1 build/lint and 130 Android tests passed; the full CPU-only agent suite passed 45 tests including Docker/OpenCode/Chromium with fake inference, followed by nine final changed API tests. A real Linux Secret Service synthetic credential round trip passed, with no secrets emitted and the temporary account removed. Live metadata/authentication/review gating and the GPU pause passed. APK signing/download checksum verified. OAuth app registration is an external prerequisite; setup guidance is saved in the user-facing account setup artifact.
