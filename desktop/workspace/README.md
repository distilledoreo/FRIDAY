# Persistent local workspace

This extension shares the existing desktop assistant gateway and its bearer token. It adds saved chat synchronization, file storage, isolated calculations, a browser client, resumable research, and scheduled jobs. It has no connected-account integrations.

## Install

Install `requirements.txt` into the gateway's Python environment and install the `bubblewrap` package. Copy this directory to `api/workspace`. At the end of the gateway module:

```python
from pathlib import Path
from workspace.integration import enable
workspace_store = enable(app, auth, llm, search, fetch, FetchRequest,
    Path(__file__).resolve().parent.parent / "workspace-data")
```

The adapter expects the gateway's authenticated FastAPI dependency list, existing HTTP client for llama.cpp, `search(q, n)`, and `fetch(FetchRequest(...))`. Restart the gateway. SQLite and files under `workspace-data` survive restart; back up this directory while the service is stopped. Existing systemd `Restart=on-failure` recovery remains applicable.

Open `/workspace-ui` in a browser and enter the existing API token. The token stays in tab memory. The browser can continue synced chats, use project context, save memory, upload files, download artifacts, and manage tasks. Use Android Workspace → Sync now to exchange changes. Sync is manual; revision conflicts require choosing phone or computer. File deletions and job removals are explicit actions. Connected services are deliberately excluded.

## Python sandbox

Execution uses bubblewrap with separate user, network, process, IPC, and other namespaces. Only the Python runtime, system libraries, and explicitly selected uploads enter the sandbox. Personal directories, API tokens, and host network services are unavailable. Nested user namespaces are disabled. Limits include 25 seconds of CPU, 35 seconds elapsed, 1.5 GB virtual memory, 25 MB per output file, 128 MB temporary files, 256 temporary entries, and 8 returned artifacts. One analysis runs at a time. A monitoring loop enforces aggregate temporary storage limits; this is a practical local sandbox, not a multi-tenant hosting boundary.

Ubuntu's user-namespace AppArmor restrictions can block service-owned bubblewrap even when it works from a development terminal. `/workspace/health` probes the actual launch and reports its error. Install a profile granting `userns` to `/usr/bin/bwrap` if required; keep global restrictions enabled. The desktop deliverable includes the scoped profile and its installer. Do not fall back to unsandboxed Python.

Supported libraries include pandas/openpyxl, matplotlib, numpy, python-docx, and reportlab. Save generated XLSX, CSV, DOCX, PDF, Markdown, JSON, PNG/JPEG, or SVG directly in `/work`; inputs are in `/work/inputs`. Sandbox outputs are returned as authenticated `assistant://artifact/<id>` links. Uploads are capped at 25 MB each and workspace file storage at 500 MB. Android's Files tab can reclaim storage.

## Research and scheduling

Background jobs have public-web search, page fetch, and isolated Python tools. They have no phone actions. A completed assistant/tool group is checkpointed before the next model call. After a restart, running jobs resume from their last complete checkpoint. Uncheckpointed model generations may repeat; interrupted file generation can leave an extra artifact. Completed tool groups are not replayed. Twenty tool rounds pause a job for explicit resumption. Recurrence is at least 15 minutes. Pause/cancel can interrupt model waits; an in-progress tool finishes before the worker stops. Results remain available while the phone is offline. Android polls for changed results roughly every 15 minutes, subject to OS scheduling and notification permission.

The browser saves its prompt before generation and records the final answer only if the chat revision still matches. If another device edits the chat while generation runs, the answer remains in Tasks and does not overwrite the edit. Browser history displays final messages; full original Android tool transcripts remain in synced chat archives.

## App updates

Place the signed APK and `release.json` in the gateway's sibling `apk` directory. Example manifest:

```json
{"filename":"local-assistant-attachments-debug.apk","versionCode":2,"versionName":"0.2.0","sha256":"<APK SHA-256>"}
```

`/workspace/release` and `/workspace/update` require authentication. The Android app verifies checksum, package identity, a newer version, and the installed app's signing certificate before opening the Android installer. Installation remains an Android user action.

## Verification

```sh
python -m unittest discover -s desktop/workspace/tests -v
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

These cover conflict/tombstone persistence, authentication, filename handling, host/network isolation, workbook creation, scheduling, checkpoint resumption, browser continuation, concurrent edits, memory, project scope, transcript search, context trimming, tool schemas, confirmation, and file argument mapping. Phone microphone background behavior, MediaProjection lifecycle, notification delivery, SAF backup/restore, actual two-device sync, and installer handoff still require device testing.


## Android navigation (0.3.0)

Chat is the default screen. Open the sidebar to search chats, browse date-grouped history, open a project, view Library files, or manage Tasks. Project screens contain their chats, shared instructions and reference text. New chat in the sidebar starts a general chat; New chat inside a project retains that project’s context. A chat’s overflow menu opens project selection/details or Data controls for export.

The sidebar footer opens Settings. Memory and Data controls have separate screens; server/model/voice configuration is under Voice and server. Task creation uses local date/time pickers and repeat choices; non-existent daylight-saving times and past times are rejected. Sync conflicts show bounded, readable previews of both versions, and require another sync if the phone copy changed after comparison.

Device UX checks still required: back navigation across nested screens, date/time pickers, keyboard and system insets, large font sizes, light/dark mode, project chat creation/rename/delete, and selecting a sync version.
