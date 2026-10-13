# Browser client: next steps

The goal is a browser client that looks and behaves like the Android app, minus what a browser cannot do.
The rule for getting there is in [`shared-ui/README.md`](../shared-ui/README.md): a screen or control is
written once in `shared-ui` and both clients render it. A browser-only copy drifts.

## Done

- Sidebar: the Android drawer is shared (`ChatDrawer`). Search, icon rows, projects, chats grouped by day,
  rename and delete on each chat's row. A destination a client lacks is passed as `null` and left out.
- Header: the new chat, FRIDAY and profile buttons are shared (`HomeHeaderActions`).
- Identity: the F menu button that unfolds into the wordmark (`FridayBrand`) and the ambient backdrop
  (`FridayAmbient`) are shared. An empty chat shows the backdrop with the composer a third of the way up.
- Sync: both clients learn what changed through `ChangeFeed` and `/workspace/changes`.

## Next, in order

| Item | What it needs | Size |
| --- | --- | --- |
| Attach | A browser file picker, upload to the existing `/workspace/files`, and the attachment strip in the composer. The gateway already accepts attachments on a browser send. | Medium |
| Voice | Microphone capture in the browser and the gateway's existing `/transcribe` and `/speak`. Dictation into the composer first, then a full spoken conversation. The browser gateway adapter only allows `/workspace/` paths today. | Medium, then large |
| Library | Move the Android files screen into `shared-ui`. The API exists. | Medium |
| Images | Move the image generation screen into `shared-ui`. Depends on the image service on the PC. | Medium |
| FRIDAY | Move the activity screens (her computer, agent tasks, approvals) into `shared-ui`. The largest Android-only screens. | Large |
| Incognito | New gateway work. A browser chat runs as a PC job that saves the chat, so a path that saves nothing has to be added before the browser can offer it. | Large |
| Code | Move the Code workspace screens into `shared-ui`. | Large |

## Known gaps

- Chats that belong to a project are left out of the main chat list unless you search, as on Android. The
  browser has no project page that lists a project's chats yet, so they are hard to find there.
- The header's dots button opens Tasks in the browser. On Android it opens FRIDAY's activity; it should do
  the same in the browser once that screen is shared.
- No image loading, embedded app blocks or account management in the browser.
- Replies arrive when the PC job finishes. They do not stream.

## Working on it

`./gradlew :web:stageGateway` builds the bundle into `desktop/workspace/web-dist/`. On a machine short of
memory, webpack needs `NODE_OPTIONS=--max-old-space-size=3072`.

To look at the app without the real computer, serve `web-dist` at `/friday/` from a small local server that
answers `/workspace/changes`, `/workspace/items/{id}`, `/workspace/jobs` and `/workspace/memory*` with
sample data. Compose draws to a canvas, so browser automation has to send `keydown` events to the canvas
to type.
