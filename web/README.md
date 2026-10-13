# FRIDAY Compose web client

This is the Kotlin/JS + Compose frontend, served at `/friday/` by the existing PC gateway. It uses Android's shared conversation codec, script interpreter, interface compiler, catalog and theme design. The older `/workspace-ui` client remains available separately.

## Shared data

Connect to the same single-user PC gateway as Android. Leave the computer-address field blank when opening the app from that gateway, and enter its existing API token. Credentials stay in tab memory and are cleared on Lock; the earlier web app's persisted model token is removed at startup.

History, rename/delete tombstones, project instructions/reference text, and portable appearance preferences use the revisioned `/workspace/items` API. Every write checks the revision it was based on. A conflicting send preserves the composer draft; rename and project/preference edits retain their original revision even if automatic refresh occurs during editing. Refresh preserves unsent composer text. Remote chat deletion removes the open transcript without discarding the draft.

The browser refreshes every five seconds while visible and on return to the tab. Android syncs every fifteen seconds while open and idle. Closed-client background sync remains unfinished. The browser needs the PC to connect or send; an unsent draft lasts for the current tab session.

Memory uses the same `/workspace/memory` endpoints as Android: approved facts, edit/forget, recall settings, paged facts and source previews/exclusions. Older saved memories can be imported explicitly. Tasks show PC work and retained results, with pause/cancel/resume/delete controls. A reply produced after another client edits its chat remains in Tasks and is also shown separately when that browser turn finishes.

Browser-generated assistant/tool groups are stored in the same conversation format as Android. Completed tool checkpoints survive a server restart and are archived once. Public source reference IDs match across JVM and JavaScript. Existing attachment metadata is retained through continuation and rename; authenticated artifact links download from the PC.

Older browser-local chats can be copied into shared history from Settings. JSON escapes and Unicode are preserved; deterministic IDs make repeated imports idempotent. The original local chats remain available for recovery. Older browser-local appearance values do not silently overwrite the shared preferences; set the desired shared values in Settings.

Shared appearance fields are theme, accent, proactivity, reduced motion, display name and interactive answers. Connection/model routing, network timeouts, phone permissions, Bluetooth/voice hardware, Android Auto and Wake-on-LAN remain local. The web app uses the same Compose components as Android for messages, Markdown/tables, appearance, the composer, projects, memory and tasks (including scheduling). Its generated interfaces use the shared layout, input, form, chart, timer and data renderers and bounded script runtime. See [`shared-ui/README.md`](../shared-ui/README.md) for the module boundaries and remaining orchestration differences. Raw AppBlock execution, AsyncImage loading, file picking, voice, account management and the remaining Android control surfaces still need browser adapters or shared screens.

## Build and serve

Use JDK 17, Android SDK 35 and the repository's Gradle wrapper. Kotlin/JS installs its Node/Yarn tools; its generated dependency lock is committed in `kotlin-js-store/yarn.lock`.

```sh
./gradlew :web:stageGateway
```

This builds a production bundle and stages it in `desktop/workspace/web-dist/` (generated and ignored). Run the existing server setup procedure on the PC after staging; it copies these public assets alongside the workspace module. Restart the gateway and open `/friday/`. Without staged assets the endpoint returns a clear 503 response. No extra listener, database or account provider is needed. The bundled HTML loads Skiko before starting Compose, and assets use relative URLs so the `/friday/` prefix works.

Deploy the updated Android build and gateway together. Android 0.23.0 includes the new preference protocol; older Android builds do not understand preference records. Build the APK with the normal signing setup so an installed app can be updated. This work does not deploy or restart a live PC installation.

Development compilation is available with `./gradlew :web:jsBrowserDevelopmentWebpack`. Opening a development app on another origin requires an explicitly allowed gateway CORS configuration; serving from the gateway avoids that requirement.

## Verification

```sh
./gradlew :core:testDebugUnitTest :core:jsNodeTest :shared-ui:testDebugUnitTest :shared-ui:jsNodeTest
./gradlew :app:testDebugUnitTest :app:assembleDebug :web:stageGateway
python -m unittest desktop.workspace.tests.test_workspace.ApiTests -v
python -m unittest discover -s desktop/memory/tests -v
```

The common interpreter/interface tests run on both Android/JVM and Node. Shared workspace tests cover shared history, draft preservation, deletion, stale writes, per-field preferences, lock/reconnect, paged memory, explicit import, project conflicts and retained task replies. Server tests cover canonical tool transcripts, restart checkpoints, source references, optional renderer instructions and public assets with authenticated data APIs.

Robolectric needs a writable Java user-home/cache directory. Browser smoke checks used the staged app and a local fixture gateway, exercising sign-in, real chat persistence, interactive calculator rendering and memory controls. Real two-device behavior, broad browser/accessibility coverage and live model/provider verification still require device testing. Existing Android lint findings and the environment-dependent bubblewrap analysis test are outside this port.
