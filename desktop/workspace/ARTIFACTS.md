# Editable artifacts

FRIDAY 0.30 adds persistent documents, interactive apps, template-based slides and bounded sheets to the existing authenticated workspace. Android and the browser share `ArtifactController` and the artifact workspace UI. Open **Library → Documents, apps, slides and sheets**, save an answer from Canvas, or follow an `assistant://document/<id>` link from chat. Existing `assistant://artifact/<id>` links still download Library files.

## Editing and recovery

- Documents have stable block IDs, headings, flat lists, quotes, code, simple tables, uploaded images, emphasis and links. The offline ProseMirror editor supplies selection, formatting and undo. Image blocks use placeholders inside the editor and display the actual image in the workspace and exports.
- Select a passage in the rich editor, return to the workspace, and ask FRIDAY for a change. Suggestions show the exact replacement and require acceptance. Structural, app, slide and sheet changes can also be proposed through chat. A proposal based on an older revision cannot overwrite current work.
- Every accepted save is an immutable revision. Restore creates another revision. Source and interactive-app data have independent histories. Autosave acknowledgments cannot clear edits made while a request is pending.
- Concurrent saves return a conflict; the local draft stays available for **Save copy**. Android retains drafts in app-private storage. The browser warns about unsaved work on exit; it does not persist artifact drafts or credentials in browser storage.
- Comments retain their original block, quote and revision. They are not automatically moved after arbitrary edits. Original-file annotations retain the file and page.
- Artifacts can be assigned to projects and reused as templates. Trash retains revisions and assets indefinitely until the owner explicitly confirms permanent deletion by entering the title. Library exports and downloaded backups survive artifact deletion.
- Artifact backups include source/data history, comments, proposals and referenced files. Restoring creates new copies and remaps asset IDs; old pending proposals require renewed review.

The native and server chat engines expose matching `list_artifacts`, `read_artifact`, `create_artifact`, `propose_artifact_edit` and `export_artifact` tools. The model has no proposal-acceptance tool. Persistent artifact tools are unavailable in incognito chats.

## Formats and practical limits

| Kind | Editing and import | Export |
| --- | --- | --- |
| Document | Native blocks; Markdown/text; DOCX paragraphs, safe links, basic marks, standard lists, headings and tables | Markdown, DOCX, PDF rendered from the actual DOCX |
| Interactive app | Existing HTML/AppBlock and GenUI hosts; source/preview, diagnostics, separate persistent data | Offline HTML or GenUI JSON; host data/actions are not portable |
| Slides | Title/body, reorder, three themes and presentation view; text-only PPTX import | PDF and PPTX |
| Sheet | One rectangular grid, typed cells, TSV paste; active-sheet XLSX or CSV/TSV import | XLSX preserving formulas and CSV |
| Original files | PDF, images, DOCX and PPTX page previews and annotations | Original download |

Imports retain the original and display warnings. This is not lossless Office editing: DOCX images/advanced layout, PPTX layouts/charts/animations and XLSX formatting/additional sheets are not imported. PDF pages are reviewable, not editable document text. Fonts may be substituted by the installed Office runtime. Slide PDF export rejects text that exceeds its template capacity instead of silently clipping it.

The sheet calculator supports arithmetic, references, ranges and `SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`. Unsupported functions, cycles, invalid references and oversized intermediate numbers return explicit errors. It is not Excel's calculation engine, and exported XLSX files do not promise fresh cached formula results. Limits: 100 rows, 26 columns and 2,000 cells; documents: 2,000 blocks and 1 MB canonical JSON; app source: 800 KB; uploads/exports: 25 MB each. Revisions, app state, proposals and files share the existing 500 MB workspace quota. Derived preview pages are pruned before authored content.

## Installation and isolation

Install the gateway requirements and `bubblewrap` using the normal server setup procedure. Document PDF export and Office previews additionally require LibreOffice. The renderer uses `FRIDAY_SOFFICE` when configured, an installed `libreoffice`, or the dedicated local distribution installed by:

```sh
server/scripts/install-artifact-office.sh /path/to/trusted/libreoffice/program/soffice
FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh
systemctl --user restart assistant-api
```

The helper copies an existing trusted distribution, including licenses, to `$XDG_DATA_HOME/friday/libreoffice` (default `~/.local/share/friday/libreoffice`). It does not download software or overwrite an existing installation. The model service does not need restarting.

Converters run in disposable bubblewrap namespaces without host home directories, bearer tokens or network access. Office gets a fresh profile with macros disabled. Two conversion slots, process/resource limits, wall-time and output-size checks bound jobs; cancelled requests signal the worker to stop. There is no unsandboxed fallback. Generated HTML remains in the existing opaque, offline AppBlock host, separate from the trusted editor bridge. Saving source does not execute it.

The checked-in editor bundle works without a CDN. Rebuild and verify it with:

```sh
cd desktop/workspace/editor
npm ci
npm test
npm audit --omit=dev
```

`dist/THIRD_PARTY_NOTICES.txt` records bundled licenses. The Android APK and gateway serve the same bundled editor.

## Verification

```sh
python -m unittest discover -s desktop/workspace/tests
./gradlew :core:jsNodeTest :shared-ui:jsNodeTest :shared-ui:testDebugUnitTest \
  :core:test :app:testDebugUnitTest :app:assembleDebug :web:stageGateway
python -m desktop.workspace.tests.fixtures.local_artifacts \
  --model-url http://127.0.0.1:8080 --report /tmp/friday-artifact-evaluation.json
```

Use the gateway's Python environment, with the declared dependencies installed. The opt-in model fixture uses disposable synthetic documents and only permits a loopback model endpoint. It measures ten controlled selected-edit tasks, repeated three times, with proposal acceptance, unchanged-neighbor assertions, reopen, restoration and actual DOCX/PDF rendering. It does not establish general autonomous document authoring quality.

The implementation includes JVM/JS recovery and conflict tests, Robolectric workspace/review tests, editor DOM tests, authenticated API tests, real Office and spreadsheet round trips, backup/restore tests and a sandbox host/network/credential probe. Physical Android WebView/IME/accessibility, actual connected-browser interaction and simultaneous phone/browser use still require device verification. Chat links open a resizable companion workspace on wide screens and a full-screen workspace on phones. Multi-user collaboration, public hosting, freeform slide design, full Office fidelity and LaTeX compilation remain outside this release.

### Release verification record — 2026-11-05

All 111 workspace server tests passed using the installed gateway environment. The Android and web build command above passed. App tests: 142; core Android/JVM tests: 338; core JS tests: 126; shared UI: 14 on JVM and 14 on JS, all passing. The app tests include opening a saved document, explicit suggestion acceptance and keeping chat interactive beside the wide workspace. Editor DOM checks and the production dependency audit passed (zero reported vulnerabilities).

The local Qwen model completed all 30 controlled editing trials, preserving untouched blocks and requiring acceptance. Real DOCX/PDF and PPTX/PDF pages from synthetic fixtures were rendered and visually inspected; headings, emphasis, links, numbering, tables, images and slide layout were present without clipping in those fixtures. An earlier evaluation exposed ambiguous selection prompting and a temporary-file monitoring race; both were corrected before the final run. This evidence does not establish arbitrary document fidelity or unconstrained authoring reliability.

After deployment, the authenticated live gateway passed create → propose → accept → DOCX/PDF export → actual page rendering → revision restore. The synthetic verification artifact was moved to Trash; its two exports remain in Library for inspection. The published Android 0.30.0 update (code 86) was downloaded through the gateway and matched its manifest checksum. No model-service restart was required.
