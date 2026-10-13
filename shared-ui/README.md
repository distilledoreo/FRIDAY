# Shared client UI

`shared-ui` is a Compose Multiplatform library compiled for Android and JavaScript. Both clients use its theme, appearance controls, composer, user/assistant messages, tool activity, Markdown/table rendering, generated interface components, and project/memory/task screens. Change those implementations here once.

## Where changes belong

| Change | Source |
| --- | --- |
| Portable preferences, validation, projects, workspace API operations | `core/src/commonMain/.../settings` and `.../client` |
| Conversation wire format, transcript grouping and tool labels | `core/src/commonMain/.../conversation` and `.../presentation` |
| Colors, spacing, typography, composer, messages, standard workspace screens | `shared-ui/src/commonMain` |
| Generated interface controls, charts, forms, timers, date/time fields | `shared-ui/src/commonMain/.../ui/genui` |
| Browser HTTP, file picker, microphone/audio, image decoding, authentication lifetime, downloads, legacy browser import, startup | `web/src/jsMain` |
| Android voice, permissions, attachments, offline storage, lifecycle, notifications | `app/src/main` |
| System motion, maps links, dialog windows, embedded app hosts, local scheduling time | `shared-ui/src/androidMain` and `shared-ui/src/jsMain` |

Shared screens depend on `WorkspaceActions`. The browser uses `WorkspaceController`; Android uses `NativeWorkspaceActions` to project its offline/native state and perform the same shared repository operations through `ClientGateway`. Android retains its local conversation engine, three-way offline synchronization, and native streaming/session persistence. The browser continues conversations through PC jobs. These orchestration paths are still distinct; this migration does not claim that every non-device workflow is consolidated.

Library, Images, FRIDAY activity, account forms and Code screens are shared. `FeatureController` owns their portable API operations; Code retains its shared controller. Android-only import/review, archive investigation, voice hardware and data controls remain in their existing hosts. Moving or adding a portable feature should add a shared screen/controller contract and a platform adapter, rather than a second screen implementation. The web module contains no Compose screen or component implementations.

Android supplies `GenUiImageService` and the offline WebView AppBlock host. The browser supplies authenticated image loading and an opaque-origin DOM AppBlock host with a restrictive CSP. Browser AppBlock state stays in tab memory, and messages require confirmation into the composer. The same component registry renders supported interface replies on both clients. A contract test rejects advertised components without registered renderers.

## Verification

```sh
./gradlew :core:testDebugUnitTest :core:jsNodeTest
./gradlew :shared-ui:testDebugUnitTest :shared-ui:jsNodeTest
./gradlew :app:testDebugUnitTest :app:assembleDebug :web:stageGateway
```

Common workspace, transcript, preference, and renderer contract tests run on JVM and Node. Android Compose interaction tests also exercise the shared UI through the native host. Browser smoke checks should use the staged production bundle and the gateway, including small screens and cross-client updates.
