# FRIDAY native computer control — 0.18.0

Completed from Claude’s unfinished native OpenCode draft after the user clarified that the continuation was computer use. His 0.17.9 UI/dashboard work, existing data and integrations remain intact.

## Implementation

- OpenCode runs as the PC user with isolated FRIDAY configuration/history and authenticated loopback control. Shell tools retain the user’s ordinary CLI profiles. No generic shell endpoint is exposed to the phone/model.
- Persistent access modes: Ask every time, Ask before changes (default), Full access. Explicit allow/deny shell patterns, desktop switch, current-task versus remembered approvals, task ownership and human questions. Deny rules win. Access changes stop active tasks; later follow-ups refresh rules. Disabled access cannot restart the helper.
- Native X11 screenshots and bounded mouse/keyboard tools. The phone can explicitly view an authenticated current frame. Screenshot coordinates follow the resized model image. Inputs validate shape/finite coordinates and release held modifiers/buttons on failure.
- Catalog-verified free tool models ranked daily by Artificial Analysis Intelligence Index, plus a zero-price routing cap. Strong text-only models can lead; a ranked vision helper handles screen work with the same phone permissions. Automatic local Qwen fallback preserves recorded results and avoids repeating completed/uncertain actions. Text-only local input strips images, uses background slot 1 and shares the existing foreground/GPU gate. No model-service flags or prompt-cache edits. See [daily model ranking](FRIDAY-MODELS.md).
- Phone task progress, exact permission/file-diff review, questions, cancellation, follow-up, screenshot preview and Computer access settings. Confirmed chat tools can request/read tasks; they cannot approve permissions and are blocked in incognito.
- Existing isolated research, schedules, accounts and outgoing foundations are preserved. Native PC access follows the user’s explicit shell/desktop authorization; existing provider-specific outgoing activation remains disabled.

## Validation and delivery

209 Android tests passed (152 core, 57 app); release assembly/lint pass with 0 errors and 32 warnings. Backend suite: 134 run, 129 pass, 5 optional integration checks skipped; final focused changed checks also pass. Native dark/light/large-text permission/question renders were visually checked. The installed OpenCode passed owned synthetic approval/denial, idempotence, redirection, zero-price cap and simulated cloud-to-local fallback checks.

API-only deployment preserved the prompt cache/model services and restored the prior background setting. Authenticated live PC status, real desktop frame metadata, native desktop MCP and legacy research were verified. Signed APK: 0.18.0/code34, existing certificate, no test fixtures. APK/source/notes/native previews are delivered in the Codex task outputs; the existing PR remains draft.

Sudo needs the PC’s existing password authorization; no password storage or sudoers change. Tailscale needs login and the private APK server is offline, so direct APK delivery is the verified route. Physical-phone, real keyboard/mouse actions, real model quality and performance/battery remain unverified. Synthetic fixtures do not establish those properties. The broader roadmap remains unfinished.

References: [OpenCode permissions](https://opencode.ai/docs/permissions/), [server API](https://opencode.ai/docs/server/), [plugins](https://opencode.ai/docs/plugins/), [OpenRouter routing cap](https://openrouter.ai/docs/guides/routing/provider-selection).
