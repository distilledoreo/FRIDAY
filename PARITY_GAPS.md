# FRIDAY parity and usability register

Started and last audited: **2026-10-13**. This is the running list for comparing
FRIDAY with ChatGPT and identifying the smaller workflows that make capabilities
usable. Record capabilities, UX gaps, evidence, and closure checks separately.

## Scope and decisions

The target is everyday ChatGPT on web/mobile, with ChatGPT Work/desktop capabilities
identified separately. Features observed on one account or platform are not assumed
available everywhere. This is not a comparison against every Codex or enterprise feature.
A Python tool capable of creating a file is not evidence of a finished document workflow.

FRIDAY source baseline: `1a0259e` (main, Android 0.25.2); the Android Auto experiments
through 0.25.4 are tracked separately in [PR #17](https://github.com/distilledoreo/FRIDAY/pull/17).
This branch does not contain those unmerged Android Auto changes.

| ID | Previously raised area | User decision / current standing |
| --- | --- | --- |
| F001 | Live visual conversations | **Unverified; not an active parity requirement.** The user questions whether ChatGPT still offers this. Current official desktop Voice documentation describes on-demand macOS appshots, which do not establish continuous live camera/screen sharing. Do not repeat the earlier broad claim as fact. Revisit only with verified surface/plan evidence and user interest. [O4] |
| F002 | Presentation creation | **Active, P1.** FRIDAY has file generation tools but no dedicated slide creation, visual review, revision and PPTX export workflow. Work's document/presentation workflow is a relevant comparison, not a claim that ordinary Chat has a full slide editor. [O2], [S12] |
| F003 | Rich document editing | **Active, P1.** DOCX/XLSX/PDF generation exists through Python. In-app review, targeted revisions and reusable document workflows are incomplete. Break this down into concrete gaps below. [O2], [S4], [S12] |
| F004 | Connected-service breadth and completed actions | **Active, P1.** Accounts, inbox/calendar reads and reviewed action proposals exist; outgoing execution remains gated. Track provider onboarding, recovery and completion UX as well as additional integrations. [O5], [S8], [S12] |
| F005 | Hosted app publishing | **Deferred by the user.** Keep it recorded; do not prioritize hosting or shareable-site deployment in this pass. |
| F006 | Cloud operation independent of the PC | **Accepted architecture, not a gap to fix.** The PC server is the user's cloud. Audit unavailable-server recovery, not a replacement cloud infrastructure. |

Also preserve earlier decisions: browser offline storage is not required; web-to-phone
control remains deferred. Notifications retain Off / Tab open / Tab closed choices.
Do not mistake these decisions for overlooked parity requirements.

## Active implementation goal — 2026-10-13

Close the in-scope interface, app and execution-harness parity gaps in this register.
**Model intelligence, reasoning quality, model capability and model benchmarking are
excluded.** Harness support for files, tools, recovery, permissions and usable workflows
remains in scope independently of the model selected.

Start with UX001, UX003, UX004, UX006, UX013 and UX014, then complete the remaining
in-scope entries and the presentation/document/integration workflows. Investigate the
validation queue and add confirmed findings as needed. Deliver changes through the
shared Android/browser architecture, relevant verification, commits/pushes and reviewable
PRs. Record completion evidence per entry; keep device/provider checks pending until
actually verified. The goal stays active until in-scope acceptance checks are satisfied.
Deferred and accepted-architecture decisions above remain unchanged.

## Evidence and priority rules

- **Confirmed source gap:** a specific audited FRIDAY screen/controller lacks the described
  workflow, or implements the stated behavior. This does not prove a runtime failure or
  exclude an alternative workflow in every subsystem.
- **Observed comparison:** inspected in the current ChatGPT web interface, without sending
  prompts, changing account settings, or modifying files. Availability is account-specific.
- **Documented comparison:** supported by a fetched official OpenAI page linked below.
- **Needs validation:** a UX test or suspected problem; not counted as a confirmed gap.
- **P1:** prevents completing/recovering everyday work or risks losing context/work.
- **P2:** recurring friction, discoverability or organization. **P3:** optional refinement.
  These priorities are audit recommendations; only the scope decisions above are user selections.

Every closed entry needs the acceptance check on the relevant client(s), a source/commit
reference, and any remaining device/service limitation. Never close a gap solely because
a button exists or an unrelated unit suite passes.

## Confirmed chat and composer gaps

These findings establish FRIDAY behavior. Where no OpenAI source is listed, the desired
behavior is a usability benchmark rather than an asserted ChatGPT feature.

| ID / priority / scope | Gap and concrete completion check | Evidence |
| --- | --- | --- |
| UX001 · P1 · Web | **Streaming takes over scroll position.** The chat animates to the bottom as the transcript grows, without checking whether the user is reading above. Complete when scrolling upward suspends following, a jump-to-latest control appears, and returning to the bottom resumes following. Validate Android independently. | [S3] `ChatScreen` scroll effect |
| UX002 · P1 · Shared/Web | **No explicit desktop send-key workflow.** The shared composer has a multiline TextField and clickable Send, but no app-specific Enter/Shift+Enter or configurable send shortcut. Complete when keyboard sending, multiline entry and IME composition have an explicit tested contract; do not break mobile keyboards. | [S1]; scoped search found no key handlers in shared UI/browser host |
| UX003 · P1 · Android/Web | **Draft context is not scoped to each chat.** Browser switching previously preserved one draft string while clearing attachments; New chat cleared the draft. Android switching clears the composer and deletes its imported draft attachments. Complete when switching A → B → A restores each unsent draft and its attachments, or explicitly asks before discarding. Session-memory retention is sufficient; offline persistence is not required. | [S2] `select`, `cleared`, `newChat`; [S13] `switchTo`, `resetToNewChat` |
| UX004 · P1 · Android/Web | **Editing overwrites an unsent draft.** Both hosts replace the unsent draft on Start editing and clear it on Cancel; Android additionally cleans up imported draft attachments. Complete when cancel restores the pre-edit draft/attachments and saving an edit explains its effect on later messages. | [S2], [S13] `startEditing`, `cancelEditing` |
| UX005 · P1 · Web | **Files enter through the picker only.** No explicit drag/drop or clipboard-image attachment handler was found in the scoped browser/shared source. Complete when dropping multiple files and pasting a screenshot use the same validation/status path as the picker. | [S7] `pickBrowserFiles`; scoped DOM/clipboard handler search |
| UX006 · P1 · Web | **Uploads have no per-file progress/cancel/retry workflow.** Files are processed sequentially with global busy/error state, and the batch stops at a failure. Complete when each file has queued/uploading/extracting/ready/failed state, actionable limits and retry/cancel without reselecting successful files. Android already has per-attachment status; reuse the portable contract. | [S7], [S2] `upload`; [S13] `DraftAttachment` handling |
| UX007 · P2 · Shared | **Only the latest answer exposes Canvas, Share and feedback controls.** Older answers can be copied, but these actions disappear once another answer exists. Complete when applicable actions remain available on earlier completed answers and operate on the selected answer. | [S5] `AssistantMessage`, `item.isLatest` guards |
| UX008 · P2 · Shared/Web | **No large-paste/formatted-paste treatment.** The composer stores a plain string; no long-paste-to-attachment or explicit formatted-paste adapter was found. Complete when long pasted material can become an attachment, with an option to keep it inline, and links/lists/headings survive supported paste paths. | [S1], [S7]; documented ChatGPT web behavior [O3] |
| UX009 · P2 · Shared | **Chat pinning and project pinning are absent from the shared sidebar.** Chat options are Rename/Delete and projects have no pin control. Complete when pinned items retain position across clients without changing their context scope. | [S6]; documented [O1], also observed on ChatGPT web |
| UX010 · P1 · Shared | **No ordinary chat archive/restore workflow.** Removing a chat from the sidebar currently means deletion. Memory's recall archive is a different subsystem. Complete when archive hides history without deletion and an archived-chats view can restore it; preserve separate recall controls. | [S6]; documented [O1] |
| UX011 · P2 · Web/Shared | **Search results do not explain the match.** Browser search matches message bodies, but sidebar rows display only titles. Complete when results show a matching excerpt and can navigate to/highlight the match. Existing global chat search is not missing. | [S2] `filteredChats`; [S6] `ConversationRow` |
| UX012 · P2 · Shared/Web | **Regeneration has no inline answer-variant navigation.** Regenerate replaces the answer suffix; branching exists as a separate action. Complete when alternate responses can be revisited without requiring the user to remember to branch before regenerating. Check storage semantics before choosing the implementation. | [S2] `regenerate`; [S5] response action row |

## Confirmed files, artifacts and project gaps

| ID / priority / scope | Gap and concrete completion check | Evidence |
| --- | --- | --- |
| UX013 · P1 · Shared | **Library has no search, type filters or user-controlled sort.** The screen is a flat list with upload, open and delete; the server returns only the latest 200 files. Search must cover the complete collection, not silently filter that limited subset. Complete when filename search, file-type filtering and useful sort orders work with clear empty-result states. | [S8] `LibraryScreen`, [S14] `/files`; ChatGPT web search/filters directly observed; [O3] documents search |
| UX014 · P1 · Shared | **No Library-to-composer reuse action.** Existing image references can be selected in image settings, but Library does not offer Add to chat. Complete when a saved file can be attached without downloading/reuploading, with private-chat handling made explicit. | [S8] `LibraryScreen`; [S2] upload/remove attachment paths; documented [O3] |
| UX015 · P2 · Shared | **No Library folders/favorites or multi-selection.** Library rows support individual open/delete only. Complete when users can organize growing collections and perform appropriate bulk actions with confirmation. Folders/favorites/grid/list selection were observed in ChatGPT web; these are optional organization choices, not prerequisites for file upload. | [S8]; current web UI observation |
| UX016 · P1 · Web/Shared | **No dedicated generated-document review surface.** Browser artifact opening downloads bytes; shared Library does not render document pages, spreadsheet cells or slides. Complete when supported documents can be inspected before download and feedback identifies a page/slide/range. Android's external file opening remains useful but does not establish in-app review parity. | [S2] `artifact`; [S8]; Work previews documented [O2] |
| UX017 · P1 · Shared | **Canvas is a title/body text editor, not a formatted artifact editor.** Complete when Markdown preview and explicit export formats are available for supported content, with clear limits between text Canvas and binary documents. Do not relabel current Markdown export as DOCX/PDF editing. | [S4] `CanvasSheet`; [S2] `saveCanvas`, `downloadCanvas` |
| UX018 · P1 · Shared | **Canvas closes without an unsaved-change decision or revision history.** Closing clears the in-memory document. Complete when unsaved edits survive accidental dismissal or get a discard/save choice, and successive saved revisions are recoverable. Test both Android and browser dismissal paths. | [S4]; [S2] `closeCanvas`; [S13] `closeCanvas` |
| UX019 · P1 · Shared | **Project sources are reference text, not a managed file collection.** The shared project editor exposes name, instructions and reference text. Complete when files can be added/reused/removed at project scope and each chat shows what shared sources it will use. Existing project instructions/context are already supported. | [S3] `ProjectsScreen` editor; documented project Sources workflow [O1] |
| UX020 · P2 · Shared Images screen | **Image history lacks a focused refinement workflow.** The audited screen shows previews, reference checkboxes, generation settings and open/remove controls, but no image selection annotations or side-by-side revision review. Complete when a selected image can become the explicit edit target and users can compare iterations; confirm other Android preview surfaces before adding duplicate zoom controls. | [S8] `ImagesScreen`; documented image-review workflow [O6] |
| UX021 · P2 · Shared | **Image previews merge loading and failure into one message.** ServiceImage displays “Preview loading or unavailable” for both cases and has no retry action. Complete when loading, failed and unsupported states are distinct, failed requests can retry, and cancellation does not appear as a broken image. | [S8] `ServiceImage` |

## Confirmed task and settings friction

| ID / priority / scope | Gap and concrete completion check | Evidence |
| --- | --- | --- |
| UX022 · P2 · Shared task editor | **Recurrence wording omits elapsed-time/DST semantics.** “Daily” maps to 86,400 seconds; “Weekly” maps to 604,800. The editor does not explain that this differs from a fixed local wall-clock schedule across DST. Complete when the time zone, next run and recurrence semantics are readable before saving, or calendar-aware recurrence is implemented. Date/time pickers already exist. | [S9] `TaskEditor`; do not confuse this workspace editor with the separate isolated-agent schedule UI |
| UX023 · P2 · Shared workspace tasks | **Task history has no search or status filters.** Tasks render as one list containing prompts, status, results and actions. Complete when waiting/running/scheduled/failed/completed work can be found quickly, with clear empty states and retained results. | [S3] `TasksScreen`; separate FRIDAY Activity/Code views also need their own audit |
| UX024 · P2 · Shared connection fields | **API-key fields have no reveal control.** They are always visually masked, making pasted-key diagnosis harder. Complete when temporary show/hide is accessible and secrets remain excluded from logs/exports; never show the key by default. This is a usability recommendation, not a claimed ChatGPT connection-setting equivalent. | [S10] `ModelConnectionFields` |

## Validation queue — do not count these as confirmed gaps yet

| ID / priority | Journey to inspect | Evidence needed / closure check |
| --- | --- | --- |
| V001 · P1 | First-time gateway, model, voice and image setup | Test fresh Android/browser setup and each unavailable service. Determine whether the user can distinguish address, authentication, model, microphone permission and service readiness failures and recover from the same screen. Shared validation exists; do not claim it is missing. |
| V002 · P1 | Interruptions and resumable work | Disconnect during a saved response, restart the server, return from another client, and review a waiting approval. Check retained draft, job reattachment, conflict explanations, no duplicate tools and a usable recovery action. Existing checkpoint/reconnect code and tests are evidence of support, not of complete UX. |
| V003 · P1 | Accounts and completed actions | Test OAuth callback/error/cancel, IMAP setup, credential expiry, granted scopes and gated outgoing actions. Confirm that proposing an email/calendar action is never presented as sending/completing it; provide a specific next step when activation/setup is unavailable. [S8], [S12] |
| V004 · P1 | Accessibility and desktop/mobile input | Inspect keyboard-only navigation, focus return after dialogs, screen-reader labels/order, contrast, text scaling, narrow windows, mobile keyboard overlap, browser back and hit targets. No accessibility failure is asserted before a reproducible check. |
| V005 · P1 | Voice recovery and contextual use | Test denied permission, disconnected voice service, mute/pause/interrupt, silent timeout, attachments/project context, spoken approvals, return from background and stopping capture. Do not assume the browser and Android fail identically. Live visual sessions remain F001, not a requirement here. |
| V006 · P1 | Notification delivery and deep links | Exercise all three browser modes, test-notification discoverability, permission denied/revoked, unsupported push, locked-client notices and opening the exact task/approval. Actual OS Web Push delivery remains unverified. |
| V007 · P2 | Research/source inspection | Check source chip opening, provenance dates, clipped excerpts, unavailable pages, citation warnings and “research deeply” proposal/approval/result handoff. These features exist; measure the number of steps and whether limitations are explained. |
| V008 · P2 | Computer/Code completion loop | Test exact permission/question → resume → changed files/diff → validation results → follow-up/discard/PR. Existing Code/PC controls should not be replaced without observing a concrete failure or friction point. |
| V009 · P2 | Files and export edge cases | Test scanned PDFs, failed extraction, unsupported formats, large files, cancellation, download names, private-file expiry, backup restore and conflict handling. Keep technical parsing failures separate from confusing UI. |
| V010 · P2 | Image workflow service failures | Test GPU wait, image service unavailable, cancellation, reference ordering, retries and saved-result retrieval. No assertion of matching image quality is made. |
| V011 · P2 | Long-chat navigation | Compare finding text within the open chat, returning to a source message, reading wide tables/code, selection/copy feedback and keeping scroll position between chats. Partial text selection and response/code copy already exist. |
| V012 · P2 | Cross-client and privacy explanations | Test revision conflicts, local versus shared settings, chat deletion versus recall deletion, private-chat exit and Lock. Readable consequences matter; browser offline storage remains excluded. |

## Existing capabilities not to re-add as “missing”

- Global chat search, project instructions/reference text, rename, edit, regenerate,
  branching, stop and retry exist. Search excerpts, alternative-response navigation and
  draft restoration are narrower gaps.
- File picking, multiple uploads, limits, extraction, composer attachment removal,
  generated files, downloads and deletion confirmation exist. Upload recovery and reuse
  are narrower gaps; Android already has per-file attachment states.
- Response copy and text selection exist. Do not file “cannot copy text” without a runtime repro.
- Memory search/edit/forget, source review/exclusion, import/archive investigation and
  portable backup/export exist. Memory's archive does not replace ordinary chat archiving.
- Voice dictation/conversation, streaming speech, barge-in, mute/pause/interrupt and
  spoken approvals exist. Test their recovery and discoverability rather than listing voice as absent.
- Task schedules, pause/cancel/resume, durable results, approval cards and Code diffs exist.
- Library image reference selection, image settings/history and incognito RAM attachments exist.
- Model connection fields/validation and the three browser notification choices are shared
  implementations. Shared fields do not imply credentials are synchronized or persisted.

## Audit coverage and next passes

| Area | This pass | Remaining validation |
| --- | --- | --- |
| Chat/composer/sidebar | Shared UI and browser controller source inspected; ChatGPT web composer/sidebar observed | FRIDAY runtime/mobile, keyboard/scroll/draft repros; ChatGPT send/edit/variant behavior |
| Library/images | Shared screen source inspected; ChatGPT web Library controls observed | FRIDAY runtime, file previews, bulk/organization journeys, image refinement |
| Canvas/documents/projects | Shared/editor/controller/tool source inspected; official Work/project docs fetched | Complete representative document/deck creation and revision on both products |
| Voice/notifications | Current shared controls and platform boundaries reviewed; official Voice docs fetched | Real mic, OS permission/push and background behavior |
| Accounts/tasks/Code | Shared forms/task editor/actions reviewed; outgoing gates checked in tool contracts | Provider setup, actual action completion, schedule/readiness/error/permission journeys |
| Cross-client/privacy/accessibility | Existing contracts noted, scenarios defined | Two-device, narrow-screen and assistive-technology validation |

This is an initial detailed source/docs audit with a read-only ChatGPT web inspection,
not an exhaustive hands-on sign-off. There was no connected FRIDAY browser session in
the browser inventory, and no Android device runtime was inspected in this pass.
Only generic control observations are recorded: no account names, chat titles, file
names, screenshots of private data, tokens or installation addresses belong in this public register.

Suggested first implementation batch: **UX001, UX003, UX004, UX006, UX013 and UX014**.
They address reading, preserving unsent work, recovering attachments and reusing files.
F002/F003 then build on the artifact review/editor work; F004 needs provider/device verification.
The active goal now authorizes implementation of in-scope entries; their acceptance checks govern closure.

## Implementation progress

| Entry | State | Evidence / remaining work |
| --- | --- | --- |
| UX001 | Implemented; automated and wheel/jump checks passed; keyboard validation open | Shared [reading-position controller][S15] and Jump to latest are wired into the browser chat. The [Compose interaction regression][S16] passes for scrolling upward, new messages, jumping, continued following and chat change. In Edge against an isolated scripted gateway, wheel scrolling showed older messages during streaming and Jump returned to the end. Page Up after clicking the transcript did not move it; investigate focus/keyboard handling under V012. Android's existing reverse-layout chat is a separate validation path. |
| UX003 | Implemented across both clients; targeted checks passed | Both hosts use the shared [session draft ownership book][S17] for per-chat/project unsent work. Browser Lock clears parked drafts; private drafts never enter the book. Twenty-one chat-action regressions pass on each JVM/JS runtime. Seven [native recovery regressions][S18] verify imported-image ownership across navigation, late completion, deletion, lifecycle cleanup, cancelled imports, private transitions and dashboard quick-send. Browser A → B → A restored separate text drafts in a scripted gateway smoke check. Real-device/share-intent and non-image import journeys remain under V009; no offline persistence was introduced. |
| UX004 | Implemented across both clients; targeted checks passed | Both hosts restore the original text/attachments on edit cancellation and after accepting an edit, and retain input on failed preflight. Shared composer explains that sending replaces later messages. Native imported-image lifecycle and common JVM/JS regressions pass; browser right-click → Edit → Cancel visibly restored an existing unsent draft. Device interaction coverage remains in V009/V012. |

## Maintaining the running list

Keep stable IDs. Add a dated audit note when evidence, platform scope or priority changes;
retain deferred/accepted items instead of silently deleting them. Split entries when a
single row hides independently testable work. Link implementing commits/PRs and record
Android, browser, service and manual verification separately. Move candidates to confirmed
only after source evidence or a reproducible observation; close with the acceptance check.
No recurring automation or notification schedule is implied by this register.

- **2026-10-13:** Added shared session draft ownership and Android file/job cleanup; seven native regressions and the full preceding Android/JVM/JS test/build matrix passed. Browser fixture checks verified separate chat drafts, edit cancellation, streaming wheel navigation and Jump to latest. V012 now includes an observed keyboard/focus question (Page Up had no effect after clicking the transcript) and an accessibility probe that exposed only the canvas image. These observations require further focus/assistive-technology investigation; they do not establish that every keyboard or screen-reader path fails. No live-server deployment or Android release was performed.
- **2026-10-13:** First implementation batch added browser draft/edit recovery and a shared scroll-follow controller. Full initial JVM/JS/Android test and build checks passed; final chat-action regressions (20 on each runtime), scroll interaction regression, Android APK build and production browser staging passed. UX003/UX004 scope expanded to Android after verifying its destructive draft cleanup. UX013 now explicitly requires full-collection server search beyond the latest 200 files. The goal remains active; these entries are not closed while platform-specific acceptance checks remain.
- **2026-10-13:** User activated the implementation goal for interface/app/harness parity, explicitly excluding model intelligence/capability. Earlier deferred and accepted items remain outside the goal.
- **2026-10-13:** Created the register. Recorded six user scope decisions, 24 source-backed
  UX gaps and 12 validation journeys. ChatGPT web inspection confirmed sidebar pin controls
  and Library search/filters/view modes/folders/favorites/multi-selection. FRIDAY runtime
  behavior remains to be validated; existing features are explicitly excluded from the gap count.

## Official comparison sources

[O1]: https://learn.chatgpt.com/docs/projects
[O2]: https://learn.chatgpt.com/docs/artifacts-viewer
[O3]: https://learn.chatgpt.com/docs/whats-new
[O4]: https://learn.chatgpt.com/docs/features/voice
[O5]: https://learn.chatgpt.com/docs/plugins
[O6]: https://learn.chatgpt.com/docs/image-generation

- [O1 — Projects/chats, pinning, archive/restore and shared Sources](https://learn.chatgpt.com/docs/projects).
- [O2 — Work file review, document/presentation/spreadsheet/PDF previews and targeted feedback](https://learn.chatgpt.com/docs/artifacts-viewer).
- [O3 — What's new: Library reuse/search, formatted paste and long-paste attachments](https://learn.chatgpt.com/docs/whats-new).
- [O4 — Voice: turn-taking, task coordination and on-demand macOS screen context](https://learn.chatgpt.com/docs/features/voice).
- [O5 — Plugins and their account/workspace availability](https://learn.chatgpt.com/docs/plugins).
- [O6 — Image generation and review/edit workflows](https://learn.chatgpt.com/docs/image-generation).

## FRIDAY source references

[S1]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/SharedComposer.kt
[S2]: core/src/commonMain/kotlin/com/localfirst/assistant/client/WorkspaceController.kt
[S3]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/workspace/WorkspaceScreens.kt
[S4]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/ChatCards.kt
[S5]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/ChatMessages.kt
[S6]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/ChatDrawer.kt
[S7]: web/src/jsMain/kotlin/com/localfirst/assistant/web/data/BrowserMedia.kt
[S8]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/workspace/FeatureScreens.kt
[S9]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/TaskEditor.kt
[S10]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/workspace/ModelConnectionFields.kt
[S12]: desktop/workspace/chat_tools.py
[S13]: app/src/main/java/com/localfirst/assistant/ui/ChatViewModel.kt
[S14]: desktop/workspace/workspace.py
[S15]: shared-ui/src/commonMain/kotlin/com/localfirst/assistant/ui/TranscriptScroll.kt
[S16]: app/src/test/java/com/localfirst/assistant/ui/TranscriptScrollTest.kt
[S17]: core/src/commonMain/kotlin/com/localfirst/assistant/client/SessionDrafts.kt
[S18]: app/src/test/java/com/localfirst/assistant/ui/NativeDraftRecoveryTest.kt

- [S1 — Shared composer][S1], [S2 — Browser/common workspace controller][S2].
- [S3 — Workspace, chat, task and project screens][S3], [S4 — Canvas][S4], [S5 — Message actions][S5].
- [S6 — Shared sidebar][S6], [S7 — Browser file/audio adapters][S7].
- [S8 — Library, Images, Accounts and Activity screens][S8], [S9 — Task schedule editor][S9].
- [S10 — Connection fields][S10], [S12 — Chat tool capabilities and outgoing-action gates][S12].
- [S13 — Android attachment, edit and Canvas orchestration][S13], [S14 — File API and collection limit][S14].

- [S15 — Shared reading-position controller][S15], [S16 — Scroll interaction regression][S16].
