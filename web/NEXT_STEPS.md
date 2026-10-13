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

## Service requirements and behavior

Microphone capture requires HTTPS or localhost and browser permission. A spoken reply may require the
browser's audio playback permission. Voice text uses the same shared Markdown/interface speech cleanup.
Images, PC tasks, Code and accounts depend on their existing PC services. Unavailable services report
errors on their screen. Browser OAuth applications must register the public gateway's
`/friday/oauth-callback` URL; native redirects continue to work independently.

Private chats can use public search/page reads, but cannot upload files, create artifacts, run Python,
or access accounts. They do not use saved memory or project context. Model and public-source services
still receive the requests needed to answer. Closing the request cancels private generation; it cannot
be resumed after a tab or server restart. Ordinary PC jobs continue if a browser disconnects.

## Build and verification

```sh
NODE_OPTIONS=--max-old-space-size=3072 ./gradlew \
  :core:jsNodeTest :core:testDebugUnitTest \
  :shared-ui:jsNodeTest :shared-ui:testDebugUnitTest \
  :app:testDebugUnitTest :app:assembleDebug :web:stageGateway
python -m unittest desktop.workspace.tests.test_browser_chat desktop.agent.tests.test_accounts \
  desktop.agent.tests.test_api desktop.agent.tests.test_account_scopes
python -m unittest desktop.workspace.tests.test_workspace.ApiTests
```

`stageGateway` writes generated public assets to `desktop/workspace/web-dist/`. Build changes do not
install, deploy, or restart the live PC. Fixture browser checks can run this bundle with a local gateway;
real microphones, OAuth providers, GPU generation and two-device synchronization need device/service checks.

Regression checks cover private storage isolation, authenticated streams and canonical saved replies,
fragmented model tool calls, browser OAuth/PKCE, attachment conflicts, shared Code edits, renderer support
and Markdown speech formatting on both Android and JavaScript.

This change passed 448 Kotlin tests and 48 relevant server tests. Chromium fixture checks exercised
sign-in, shared destinations, project chats, native file picking/upload/extraction, private streaming,
dictation, spoken replies followed by listening again, and microphone release on Lock without browser
errors. Both the Android debug APK and production browser bundle build successfully.
