# Browser client: implemented checklist

The browser and Android render portable controls from `shared-ui`. Browser permissions, audio,
HTTP, downloads and DOM hosting remain platform adapters.

| Item | Implementation |
| --- | --- |
| Attach | Browser multi-file picker, authenticated uploads, document extraction and scanned-page images, bounded vision previews, and a removable composer strip. Attachments survive refresh and revision conflicts. |
| Voice | Microphone capture as 16 kHz WAV, `/transcribe` dictation, silence-triggered conversation turns, and `/speak` playback followed by listening again. Ending voice or locking releases microphone tracks, audio and requests. |
| Library | Shared file list, downloads, deletion confirmation and browser uploads. Android uses the same Library screen. |
| Images | Shared generation controls, size/steps/seed/transparency, reference selection, image history, previews, cancellation and removal. Android and browser use the same screen. |
| FRIDAY | Shared computer sessions, follow-ups, permission/question cards, PC screenshots and access controls; research plans, schedules, explicit account scopes, reports, runs, events, page previews and exact approvals. The header opens FRIDAY. |
| Incognito | `/workspace/chat/ephemeral` streams a conversation without writing chats, jobs, recall or checkpoints. Conversation and AppBlock state stay in tab memory and disappear on leaving or locking. Persistent service destinations end the private conversation first. |
| Code | Existing Code screens, models and controller moved into shared UI, including repositories, branches, model selection, tasks, approvals, questions, diffs, follow-ups, stop/discard, push and pull requests. |
| Chat actions | Edit a message (it keeps its attachments), regenerate, retry after an error, branch into a new chat, open an answer in Canvas (save to Library or download), share through the browser's share sheet, and thumbs up/down saved as a preference memory. Message menus open with right-click or long-press. Logic is in `core` `ChatEdits`/`WorkspaceController`; the screens are shared with Android. |
| Tool orchestration | Saved browser chats run as PC jobs with the Android app's non-phone tools (`desktop/workspace/chat_tools.py`): web/page reads, Python, files, images (manual image settings override the model, as on the phone), memory and history search, tasks, PC tasks, coding tasks, accounts, briefs and FRIDAY proposals. Tools that ask first on Android pause the job (`waiting`) and show the shared approval card; `POST /workspace/jobs/{id}/approval` continues it from its checkpoint, and every finished tool is checkpointed so nothing repeats. The job stream carries the steps taken so far, so tool activity appears live; image, FRIDAY, PC and coding results show as cards. A chat whose job is running is followed again after a reload or from another tab; leaving it does not cancel the job, and Stop keeps the finished steps and text received so far. |
| Advanced voice | The phone's voice loop now lives in `core` (`VoiceController`): replies are spoken sentence by sentence while they stream, talking over FRIDAY interrupts her (barge-in from the echo-cancelled microphone), approval cards are read aloud and answered with "yes"/"no", and mute, pause, resume and interrupt use the shared inline voice controls. The browser supplies microphone capture, `/transcribe`, pipelined `/speak` playback and onset detection (`BrowserVoiceIo`). |
| Data workflows | ChatGPT export import (streamed straight to the computer, previewed, then imported), memory suggestion review, recent situations, archive search and investigation (view, exclude, continue as a chat, build suggestions, delete) and "what influenced the last reply" use shared screens with Android. Data controls export the open chat as Markdown or JSON or to Library, and export/restore the portable backup ZIP that Android reads and writes (`core` `ChatBackup`); restores create new chat copies and upload attachment images. |
| Dashboard | The personal dashboard (calendar, weather, follow-ups, open topics and FRIDAY tasks) and its brief settings are shared with Android through `DashboardActions`; the browser uses `BriefingController`. Times use the brief's zone on both clients (`ZoneTime`). |

The earlier gaps are addressed:

- Sidebar project rows open a project page with its chats, settings and a new-chat action.
- The header opens FRIDAY's activity instead of Tasks.
- Image search/loading uses authenticated gateway adapters. AppBlocks open in an opaque-origin iframe
  with scripts isolated from the host, network resources blocked, and messages confirmed into the composer.
- Accounts use shared configuration, sign-in, IMAP/SMTP forms, inbox/calendar previews and removal.
  Android retains its Google authorization effect. Browser OAuth uses PKCE and a one-use gateway callback;
  provider tokens and client secrets remain in the PC vault.
- Ordinary jobs publish incremental replies over `/workspace/jobs/{id}/events`; final replies and
  tool checkpoints retain the existing revision/conflict and restart behavior.

The remaining non-phone parity features are also implemented:

- Current-fact questions retrieve public evidence before generation, audit answer citations, show source chips,
  and offer a reviewed deeper research proposal through shared grounding logic.
- Web calls ask before sending identifying details from recalled memories or private tool results. Earlier
  tool results retain this protection across turns; denied calls do not execute.
- PC task cards refresh live in chat and show command permissions and questions there. Computer settings
  include explicit MCP server confirmation.
- Incognito offers saved memories or a fresh slate. Its tool set includes public sources, file creation and
  image generation; saved-memory search is available only when memories are enabled. Approvals remain usable
  while the private stream waits, and stopping or leaving clears its session.
- Each browser keeps its own model selection, computer or browser voice choice, and barge-in setting.
  Model endpoint, model name, API key and timeout use the same shared form and validation on Android and web.
  Browser custom endpoints are reached by the PC; credentials live only in server memory. Lock revokes the client connection; an existing PC job
  keeps its connection until it finishes so closing a tab does not interrupt authorized work.
  A server restart requires reconnecting a custom endpoint before starting new turns.
- Notifications offer No notifications, With tab open, and With tab closed. The open-tab mode polls while
  connected; closed-tab mode uses a service worker and encrypted Web Push, with generic lock-screen text.
  Only the push subscription is persisted, never the gateway token. Notifications depend on browser/OS
  permissions and background settings; closed-tab mode continues after Lock until explicitly disabled.

## Service requirements and behavior

Microphone capture requires HTTPS or localhost and browser permission. A spoken reply may require the
browser's audio playback permission. Barge-in relies on the browser's echo cancellation; with loud speakers,
use headphones or mute the microphone while FRIDAY speaks. Compressed backups from Android need a browser with
`DecompressionStream` (current Chromium, Firefox and Safari). Voice text uses the same shared Markdown/interface speech cleanup.
Images, PC tasks, Code and accounts depend on their existing PC services. Unavailable services report
errors on their screen. Browser OAuth applications must register the public gateway's
`/friday/oauth-callback` URL; native redirects continue to work independently.

Private chats can attach images/documents, use public search/page reads, and create files or images,
but cannot run Python or access accounts. Files and generated images use Android’s RAM-backed incognito
sessions, are absent from Library/history, and are deleted on exit/Lock (or after the server idle expiry). They use saved memories when selected, and never recall past chats or project context.
Model and public-source services
still receive the requests needed to answer. Closing the request cancels private generation; it cannot
be resumed after a tab or server restart. Ordinary PC jobs continue if a browser disconnects.

## Build and verification

```sh
NODE_OPTIONS=--max-old-space-size=3072 ./gradlew \
  :core:jsNodeTest :core:testDebugUnitTest \
  :shared-ui:jsNodeTest :shared-ui:testDebugUnitTest \
  :app:testDebugUnitTest :app:assembleDebug :web:stageGateway
python -m unittest desktop.workspace.tests.test_browser_chat desktop.workspace.tests.test_chat_tools desktop.agent.tests.test_accounts \
  desktop.agent.tests.test_api desktop.agent.tests.test_account_scopes
python -m unittest desktop.workspace.tests.test_workspace.ApiTests
```

`stageGateway` writes generated public assets to `desktop/workspace/web-dist/`. Build changes do not
install, deploy, or restart the live PC. Fixture browser checks can run this bundle with a local gateway;
real microphones, OAuth providers, GPU generation and two-device synchronization need device/service checks.

Regression checks cover private storage isolation, authenticated streams and canonical saved replies,
fragmented model tool calls, browser OAuth/PKCE, attachment conflicts, shared Code edits, renderer support
and Markdown speech formatting on both Android and JavaScript.

Verification covers both Android and JavaScript tests, relevant server tests, the Android debug APK and
the production browser bundle. Chromium fixture checks exercised
sign-in, shared destinations, project chats, native file picking/upload/extraction, private streaming,
dictation, spoken replies followed by listening again, and microphone release on Lock without browser
errors. Both the Android debug APK and production browser bundle build successfully.

Continuation verification: 485 Kotlin tests and 65 relevant server tests pass. A local scripted browser
fixture verifies private-detail approvals and denial without saving a conversation. Real microphone, provider
OAuth, GPU generation, native PC task execution and OS notification delivery still require device/service checks.

To publish the verified Android build, run `python3 server/scripts/publish-apk.py app/build/outputs/apk/debug/app-debug.apk`.
The publisher validates the package and signature, requires a higher version code, and replaces the update manifest atomically.

Notification service-worker checks: `node web/tests/notifications-sw.test.cjs`. Server regressions: `python -m unittest desktop.workspace.tests.test_notifications desktop.workspace.tests.test_model_connections`.

Version 0.25.2 verification: 491 Kotlin tests, 80 relevant server tests, both client builds and the service-worker
unit checks pass. A browser fixture confirms the three notification options, a private image sent through a
custom model connection, no persistent workspace records, and RAM file deletion on exit. Real OS push delivery
is not verified. Closed-tab notifications require HTTPS/localhost, permission and browser push support.
