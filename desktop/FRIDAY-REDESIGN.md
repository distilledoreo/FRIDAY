# FRIDAY mobile redesign — current working goal

Implement the user's complete October 8 UI/UX specification, using the supplied two-phone dark/light image as the primary visual reference. This is the current detour before resuming the unfinished functional roadmap. Preserve the existing application architecture, every feature/integration, private-data boundaries, stored data, Claude changes and user-approved privacy/inference preferences. The chat goal tracker permits only one unfinished goal; the earlier roadmap remains active rather than being falsely marked complete.

## Design and behavior contract

- Dark default #111113, charcoal surfaces #1B1B1E–#242427; intentional light #F6F6F4/white. Warm coral #E89980 is the customizable signature accent. Neutral text/borders, restrained elevation, meaningful independent semantic colors. Readable contrast takes precedence over using pale coral for small text.
- Shared color, type, spacing, 6–8dp radius, control and motion tokens. Use Segoe UI Variable only if legally available; otherwise the Android system sans-serif fallback, with careful hierarchy and line spacing. No bundled unlicensed font. Target a future desktop-compatible token vocabulary.
- Ultra-minimal home: FRIDAY wordmark/micro-menu upper left, essential controls upper right, diffuse organic accent light/particles, prominent lower-middle composer, subtle dashboard handle. No greeting/prompts/recent chats/weather/calendar/tasks/grid/bottom navigation by default.
- Same composer transitions to bottom when a conversation starts. Plus menu contains attachments/images/tools/web/model/integrations; microphone remains available alongside text/send. Multiline, focus/loading/disabled states, keyboard/safe insets preserved.
- Primary sidebar retains new/incognito chats, history/search/rename/delete, projects, library/images, tasks/activity and settings. Two precise menu strokes merge into the separately constructed F, synchronized with sidebar movement around 200–250ms, without wordmark layout shifts. Edge opening respects system back; visible button/close/back alternatives remain. Reduced motion snaps.
- Dashboard collapsed by default, accessible handle and drag, partial/expanded/dismiss states. Real connected calendar, explicit follow-ups/reminders/tasks/attention and evidence-backed suggestions only; clean unavailable/empty/error states. Conservative/Thoughtful(default)/High proactivity is real selection behavior, never fabricated content or an implicit notification/account opt-in.
- Ambient is diffuse light and a few organic particles, no mascot/orb/eyes/neon. Idle subtle; listening responds to voice level, thinking/speaking subdued, completion settles. Accent-aware, bounded work, pauses when backgrounded, reduced motion static.
- Conversations use bubbles for ordinary text, full-width rendering for images/tables/structured results/controls and compact expandable rich content. Reusable native renderer integrates existing real model/tool results, with text fallback. No arbitrary executable model UI, demo charts or fake controls.
- Voice remains in chat: existing audio/controller, live transcription, responses, speech, start/stop/interrupt/resume, approvals, errors, Bluetooth/car/screen-context/background behavior preserved. No fullscreen voice overlay.
- All existing Settings/account/memory/data/voice/server/status/update, activity/resource/task, library, image, project/history and dialog screens share the design. Settings includes theme/accent/proactivity/reduced motion. No leftover old visual subsystem.
- Accessible 48dp targets, screen-reader labels, sufficient contrast, font scaling, loading/empty/error states, keyboard/back support, gesture alternatives and reduced motion. Effects never intercept content.

## Existing feature map to preserve

ChatScreen/ChatViewModel: conversation creation/history/search/projects, drafts and attachments, edit/regenerate/copy, grounded sources/research proposals, tool approvals, incognito/Fresh slate, shared content/camera/screen context, workspace/updates. Composer: image mode/options, files, mic, send/cancel. ChatDrawer: every destination and chat management. MessageViews/AttachmentViews: markdown, tool states/source links/images/files/approvals/errors. VoiceController/VoiceEngines: phone/computer engines, live heard/speaking state, interruption, confirmations, audio routing and foreground service. WorkspacePage and children: accounts/native OAuth/IMAP, memory/import/archive/recall/review, projects, tasks/schedules/activity/audit/outgoing review, library/images, daily brief/follow-ups/proposals, exports/backup/restore/settings and computer status. Existing APIs and data formats remain authoritative.

## Incremental implementation and acceptance evidence

- [x] Phase 1: shared tokens, persisted dark/light/system/accent/proactivity/reduced-motion settings, contrast and fallback fonts.
- [x] Phase 2: refined sidebar/header and synchronized micro-menu/F, system-safe edge and button/back navigation.
- [x] Phase 3: minimal home, adaptive composer, ambient lifecycle/voice states, plus-menu feature preservation and keyboard handling.
- [x] Phase 4: collapsed real-data dashboard, actual source/error/empty states, useful proactivity selection and actions.
- [x] Phase 5: consistent bubbles, native adaptive renderer/full-width/expandable modes, integrated existing voice/approval/transcript behavior.
- [x] Phase 6: every workspace/settings/history/project/account/library/image/activity/remaining screen and dialog updated consistently.
- [x] Phase 7: final visual/interaction/font-scale/accessibility/reduced-motion/performance/regression validation, same-signer installable APK/checksum/source ZIP/draft PR and explicit private-route availability and user-facing notes.

Required visual matrix: empty dark/light home; sidebar open/closed/morph; composer empty/text/multiline/keyboard/active chat; dashboard collapsed/partial/expanded/empty/unavailable; conversation text/table/image/tool/approval/error/expanded; voice listening/thinking/speaking/paused/error; appearance/settings; projects/history/library/images/activity/accounts/memory/data/brief; custom accent, reduced motion, large fonts and narrow landscape. Use actual Compose renders, not HTML mockups. No physical device was connected; the CPU-only emulator encountered Android system ANRs. Screenshot and interaction results must be observed before claiming validation. Real provider/device prerequisites remain explicit. No outgoing activation or model/GPU changes.

Reference concept: https://openai.com/index/gpt-6-for-everyone/ describes native streamable components and coherent text fallback. This redesign builds a supported rendering foundation for existing FRIDAY responses; it does not claim the model has OpenAI's Intelligent UI training or capabilities.

Additional Intelligent UI reference (read through connected Edge): https://x.com/rabi_guha/status/2108238432355123572. Treat the author's reverse-engineering analysis as design guidance: a known native component catalog, validated data, stable local interaction state and a coherent text fallback. Do not claim support for OpenAI's internal language/compiler or execute arbitrary model-written scripts.

Claude integration: redesign now starts from 3b0f02e/0.15.0, preserving PrivateLeak decisions, explicit incognito exit and the FRIDAY plan/computer/activity views and real actions. The integrated redesign passed regression checks; the original WIP backup remains available in the owned git stash until final delivery.

## Delivered implementation: FRIDAY 0.16.0 / code23

All seven implementation phases are complete. Native Android validation: 145 core tests and 40 app tests passed (185 total), signed release build passed, Android lint completed with no errors (31 dependency/API/style warnings). Source formatting check passed. Native Skia/Compose renders and controls cover both themes, custom blue accent, all workspace destinations, wordmark stages, ordinary/reduced motion, edge swipe/back, injected keyboard insets, actual engine-driven conversation positioning with a fake model, table/expanded result/copy, live voice states and controls, empty/unavailable dashboard and native partial/expanded sheets. Synthetic source and voice fixtures exist only in tests; the delivered APK contains neither the test activity nor fixture replies.

Remaining device verification: physical Android IME/back gestures, real microphone/STT/TTS/Bluetooth/Android Auto, live connected calendars/accounts and smoothness/battery measurements. CPU-only emulator hit Android system/SystemUI ANRs; native tests do not constitute a physical-phone performance benchmark. No real model inference, account consent/action, backend deployment, model configuration change or outgoing activation was performed for this redesign. Segoe is not bundled; the Android system sans fallback is used.

Delivery: signed APK and source ZIP are supplied directly in the chat. Update files are staged in the existing private download directory, but the private route is currently unavailable: Tailscale reports NeedsLogin and port 8081 has no active listener. HTTP download verification could not be completed; restoring that route requires its existing login/service setup.

APK SHA-256: `6eb13fc98b6533978831c09965e55889b65d5a180f63ecf1838c2d25d411db4b`. Same application id and signer as the existing release; data formats remain unchanged.
