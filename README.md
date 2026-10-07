# Local Android Assistant

Phase 1 MVP. The Android app is the assistant: it keeps the conversation, decides which tools exist, and runs them. A computer on your network serves two independent things: an OpenAI-compatible chat model, and an optional search service. This repo includes the search service. It does not include a model server, voice, cloud model providers, long-term memory, or paid search APIs.

Milestones implemented:

1. Text chat from the phone to a remote model, with the same conversation kept across turns.
2. A tool registry and `set_media_volume`, so the model can ask the phone to change media volume and then keep talking.
3. `web_search` on that same registry. The phone calls a separate PC search service. SearXNG is the default search backend. Crawl4AI is an optional page fetch.
4. A ChatGPT-style chat experience: streamed replies, Markdown, Stop, copy/regenerate/edit, saved chats in a drawer with generated titles, and web searches shown as cards with their sources.
5. Phone control: apps, web, maps, media and Spotify, alarms and timers, flashlight, battery, contacts, calls and texts (approved in the chat), and the calendar.
6. Voice mode and assistant registration: a hands-free voice conversation with barge-in, and the app can be the phone's default digital assistant.
7. Images and file attachments: photos, camera capture, PDFs, Word documents, and text/code files, with previews and saved attachments.

## Layout

```
core/                     conversation engine, model provider, tools (JVM, unit-tested)
app/                      Compose chat UI, settings, AudioManager volume control
desktop/search_service/  PC search HTTP service (SearchProvider + optional PageFetcher)
```

| Package | Responsibility |
| --- | --- |
| `com.localfirst.assistant.conversation` | `Message`, `ConversationSession`, `ConversationEngine`, `FileConversationStore`, `ConversationTitles` |
| `com.localfirst.assistant.model` | `ModelProvider`, `ModelResponse`, OpenAI-compatible HTTP client with SSE streaming |
| `com.localfirst.assistant.search` | Phone client for the PC search service. Not a model provider. |
| `com.localfirst.assistant.tools` | `Tool`, `ToolRegistry`, `ToolConfirmer`, `set_media_volume`, `web_search` |
| `com.localfirst.assistant.tools.phone` | Phone tools and their argument checks, behind the `PhoneActions` interface |
| `com.localfirst.assistant.phone` (app) | `AndroidPhoneActions`, runtime permission requests, media-session access |
| `com.localfirst.assistant.presentation` | `Transcript`: messages → what the chat shows (tool cards, labels, thinking state). Plain Kotlin, unit-tested |
| `com.localfirst.assistant.ui` | Compose chat screen, chats drawer, composer, message views, settings |
| `com.localfirst.assistant.settings` | Model URL and search URL, stored separately |
| `desktop/search_service` | `SearchProvider` (SearXNG) and `PageFetcher` (Crawl4AI) |

`ConversationSession` depends on `ModelProvider`, not on Ollama, llama.cpp, or OpenAI. The model HTTP client does not know about SearXNG or Crawl4AI. The search service does not call the model.

Message roles are `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`, and `SYSTEM`. Tool calls and results are real entries in the session. They are not rewritten as user messages. The HTTP client is the only place that maps those entries onto the OpenAI `tool_calls` / `role: tool` wire format.

`Tool.requiresConfirmation` is the hook for a later permission step. Phase 1 has no confirmation UI. A tool that sets the flag is refused and the refusal is sent back to the model as a tool result. `set_media_volume` does not require confirmation.

## Point the app at a local model

Run any server that implements `POST {base}/chat/completions`. In the app, open **Server settings** and set:

- **Server address:** the API root, including `/v1` when the server uses that prefix. The app posts to `{address}/chat/completions`.
- **Model name:** the name your server expects.
- **API key:** leave blank unless the server requires a bearer token.
- **Timeout:** how long a single completion may take. Default is 90 seconds. Connecting gives up after 10 seconds so an unreachable PC fails faster than a slow generation.
- **Search service address:** optional root of the desktop search service, not the model URL. The app POSTs `{address}/search`. The emulator default is `http://10.0.2.2:8765`. Leave it blank to keep chat and volume without web search.
- **Search API key:** optional. Sent as `Authorization: Bearer <key>` on search requests. Separate from the model's API key, so the two services can live on different hosts.

Examples:

| Where the model runs | Server address in the app |
| --- | --- |
| Ollama on the computer, app on the Android emulator | `http://10.0.2.2:11434/v1` |
| Ollama on the computer, app on a phone (same Wi-Fi) | `http://<pc-lan-ip>:11434/v1` |
| Phone over USB, after `adb reverse tcp:11434 tcp:11434` | `http://127.0.0.1:11434/v1` |
| llama.cpp `llama-server` on port 8080 | `http://<host>:8080/v1` |

The emulator default is `http://10.0.2.2:11434/v1`. On a physical phone, replace that with the computer's LAN address. `10.0.2.2` only works inside the emulator.

Ollama usually listens on loopback only. The emulator can still reach it through `10.0.2.2`. A phone on Wi-Fi cannot, until the server listens on the LAN, for example `OLLAMA_HOST=0.0.0.0:11434 ollama serve`. Open the port on the computer's firewall. Plain `http://` is allowed because local servers are typically not TLS.

Check the endpoint from the computer before using the app:

```bash
curl http://127.0.0.1:11434/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"llama3.2","stream":false,"messages":[{"role":"user","content":"Hello"}]}'
```

Use the model name you actually have. Milestone 2 needs a model and server that emit OpenAI-style `tool_calls`. A server that only returns text can still hold a normal chat; it just will not call `set_media_volume`.

## Images and files

Tap **+** beside the message field to pick photos, take a photo, or select files. You can attach up to six files per message and send them with or without text. Android's share menu can also send files or text to **Assistant**; shared content opens a new draft for review before sending.

Images are resized to a maximum edge of 1,280 pixels, saved as JPEG in the app's private storage, and sent as OpenAI-compatible `image_url` parts. The model server must support vision. Text-only chats keep their existing request format. Tap a sent photo to enlarge it. Attachments stay with saved chats, follow-up turns, retries, and text edits.

Documents are read by the configured **Search service address** using its API key, via `POST /extract`. The desktop assistant API must provide that endpoint; its implementation and integration contract are in [desktop/attachments](desktop/attachments/README.md):

- PDF text and DOCX paragraphs/tables are extracted; plain text, CSV, Markdown, JSON, and source code are read as text.
- Scanned PDFs use up to four page images for the vision model.
- Each document is limited to 25 MB and 60,000 extracted characters. Reading errors and truncation notes appear beside the file. Remove failed attachments before sending.
- Spreadsheet workbooks, presentations, audio, and video attachments are not supported in this version.

Original files stay where they were picked. The app stores the processed attachment data with the chat. Deleting a saved chat also removes its private attachment images.

## Volume tool

`set_media_volume` takes one integer, `level`, from **0 (mute) to 100 (maximum)**. That scale is the tool contract. Android's media stream often has fewer steps (commonly 15). The app maps the percentage onto `AudioManager.STREAM_MUSIC`, rounding half up. For a device whose max step is 15, 30% becomes step 5 (4.5 rounds to 5), 50% becomes step 8, and 100% becomes 15.

The system volume panel is shown when the change is applied. If Android refuses the change, or the stream does not land on the requested step, the session records a failed tool result and asks the model to continue. The transcript is not cleared.

`MODIFY_AUDIO_SETTINGS` is a normal permission and is granted at install.

## Web search

The model never talks to a search engine. It calls `web_search`. Android runs that tool and POSTs to the PC search service:

```json
{"query": "local models", "limit": 5, "fetch_pages": false}
```

`fetch_pages` defaults to false. The reply is titles, URLs, and snippets. Set it true only when the answer needs extracted page text. The desktop service then uses its PageFetcher for the top results and still returns snippets if a page fails.

How to run SearXNG and the service is in [desktop/search_service/README.md](desktop/search_service/README.md). Config knobs:

| Where | Knob | Default | Meaning |
| --- | --- | --- | --- |
| Phone settings | Search service address | `http://10.0.2.2:8765` | Search service root. Independent of the model URL. |
| Phone settings | Search API key | blank | Bearer token for the search service. Blank sends no `Authorization` header. |
| Phone tool call | `fetch_pages` | false | Ask the PC to extract page text. |
| PC | `SEARCH_HOST` / `SEARCH_PORT` | `127.0.0.1:8765` | Bind address. Use `0.0.0.0` for a phone on Wi-Fi. |
| PC | `SEARCH_PROVIDER` | `searxng` | Only SearXNG is implemented. |
| PC | `SEARXNG_URL` | `http://127.0.0.1:8088` | Your SearXNG instance. |
| PC | `PAGE_FETCHER` | `crawl4ai` | `none` disables extraction. Snippets still work. |
| PC | `PAGE_FETCH_LIMIT` | `2` | How many hits to fetch when `fetch_pages` is true. |

A search-service failure becomes a failed tool result. The transcript stays.

Any server that accepts that `POST /search` body and returns `provider`, `fetched`, and `results` (`title`, `url`, `snippet`, optional `score`, `page_text`, `page_error`) works. A single authenticated gateway can serve both chat and search: set **Server address** to `https://<host>/v1`, **Search service address** to `https://<host>`, and put the same token in both key fields.

## Try the two milestones

1. Install the app and set the server address and model name.
2. Send a short message. The reply should appear as **Assistant**. Status should show **Connected**.
3. Send a follow-up that depends on the first message. The same transcript is sent back; a new chat is not started.
4. Send `Set the volume to 30%.` You should see a tool line for `set_media_volume` with level 30, the media volume change, a tool result, and a short assistant reply in the same turn.
5. Send another message. The conversation continues, including the tool result.
6. Stop the server and send again. The new error is shown above the text field, earlier messages stay, and **Retry** asks the model to continue without duplicating your message or running the volume tool a second time.

To try web search after the desktop service is up, ask something the model should look up, such as `Search the web for SearXNG`. The transcript should show a `web_search` tool call, then titles and URLs, then an assistant reply in the same conversation. A follow-up message still uses that transcript. Stopping only the search service fails that tool and leaves the chat in place. Stopping only the model does not require you to change the search address.

Other phrases that should select the same tool: "Turn the volume down" or "Set media volume to 70". The model chooses the level. Smaller models sometimes answer in text instead of calling the tool; that is a model limitation, not a second code path.

## Phone tools

Each tool is an explicit Android API or intent; the model never gets general control of the phone. Tool definitions and argument checks live in `core` (`tools/phone`, unit-tested against a fake phone). `AndroidPhoneActions` in the app does the Android work.

| Tool | What it does | Needs |
| --- | --- | --- |
| `open_app` | Opens an installed app by name. An ambiguous name ("maps") makes the assistant ask which app. | — |
| `open_url` | Opens an http(s) page in the browser. | — |
| `open_maps` | Shows a place, or starts navigation (Google Maps if installed, otherwise any `geo:` app). | — |
| `media_control` | Play, pause, next, previous for whatever is playing. | Notification access for direct control; otherwise a media key |
| `play_music` | Asks Spotify to play a song, artist, album, or playlist (Android's *play from search*). Falls back to opening a Spotify search. | Spotify installed |
| `now_playing` | What's playing (title, artist, album, app). | Notification access |
| `set_alarm`, `set_timer` | Sets them in the Clock app without opening it. | — |
| `flashlight` | On or off. | — |
| `battery_status` | Level, charging source, time to full. | — |
| `search_contacts` | Names and numbers. | Contacts |
| `place_call` | Calls a contact or number. **Asks for approval in the chat first.** | Phone |
| `send_text` | Sends an SMS and waits for the phone to confirm it was sent. **Asks for approval in the chat first.** | SMS |
| `add_calendar_event`, `upcoming_events` | Adds events to the primary calendar; lists events for given days. | Calendar |

- **Approvals:** tools with `requiresConfirmation` (calls and texts) pause the turn and show an approval card with exactly what will happen, for example "Text Jordan Lee (mobile, +1 555 0142): “Running late”". Deny tells the model the user declined, and Stop cancels the request. Contact names are resolved before the card is shown; if several contacts or numbers match, the model asks you which one instead of guessing.
- **Permissions:** contacts, phone, SMS, and calendar are requested the first time a tool needs them. Notification access can't be requested with a dialog; **Settings → Phone access → Allow notification access** opens the system screen. It's used only to see and control media sessions.
- **Spotify:** there's no Spotify account or developer app to register. Playback starts through Android's standard *play from search* intent, which Spotify handles, and `now_playing` reads Spotify's media session.

The system prompt includes today's date and time zone, and the newest message carries the time it was sent ("[Sent at 9:41 AM]", added when sending, not stored or shown), so "tomorrow at 3" and "in 20 minutes" resolve correctly. Keeping the time out of the system prompt keeps it identical all day, so the model server reuses its cached prompt instead of re-reading the whole conversation every minute. On the author's setup, the next turn of a ~1,400-token chat went from 12.3 s to 2.2 s before the reply started.

## Voice mode

Tap the mic in the composer (shown while the message box is empty), or launch the app as the assistant. Voice mode is a loop: listen → send when you stop talking → speak the reply as it streams, sentence by sentence → listen again. After two quiet turns it pauses ("Tap to talk").

There are two engines (**Settings → Voice & assistant**):

| Engine | Speech recognition | Voice | Echo handling |
| --- | --- | --- | --- |
| **Your computer** (default) | [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), int8 ONNX, on the desktop API's `/transcribe` | [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) via `/speak` (default voice "Heart"; seven voices to choose from) | Full duplex in communication mode (below) |
| **This phone** | Android `SpeechRecognizer`, on-device when available | Android text-to-speech | Separate barge-in mic; weaker |

The computer engine uses the **search service address and key**; the desktop assistant API serves `/voice`, `/transcribe`, and `/speak` next to `/search`. If that address isn't set or the service can't be reached, voice falls back to the phone engine and says so. On the author's Ryzen 7 3700X, recognition takes about 0.3 s for a 4 s utterance, and each sentence of speech is ready in about 1 s.

**Full duplex (computer engine).** While voice mode is open, the phone is in communication mode, like a speakerphone call: audio goes to the loudspeaker, or to a headset or Bluetooth device if one is connected, and the platform echo canceller removes the assistant's own voice from the mic.

- **One continuous mic stream.** It's cut into utterances by `Endpointer` (core), which tracks the background level, needs about 200 ms of speech to trigger, ends after about 1 s of silence, and keeps 800 ms of audio from before the trigger, so the first word of an interruption isn't lost.
- **Interrupting.** Start talking while it's speaking or thinking: speech stops, the reply is cancelled (keeping what arrived), and what you said is transcribed. The trigger is 6 dB stricter while the assistant is speaking.
- **Pausing mid-thought.** If you start talking again before the assistant has said anything (within 8 s of sending), the new words are joined to your last message, which is sent again as one. If a tool already ran for the first half, it's sent as a separate message instead, so actions never repeat.
- **Other controls.** Tapping the orb always interrupts. Turn **Interrupt by talking** off to ignore the mic while it speaks.
- **Volume.** Because it's a communication-mode stream, the volume buttons control call volume while voice mode is open.

**Spoken approvals.** Calls and texts are read out ("Text Jordan Lee (mobile, …): “Running late”. Should I go ahead?"). Say yes or no; anything unclear is asked again, and "no" wins over "yes" in mixed answers. The Approve/Deny card works too.

Voice pauses when the app goes to the background, because Android blocks the mic for background apps.

## Default assistant

The activity handles `android.intent.action.ASSIST` and `android.intent.action.VOICE_COMMAND`. To use it:

1. Go to **Settings → Voice & assistant → Set as default assistant**. This opens **Default apps**.
2. Choose **Digital assistant app → Assistant**.

Then long-press the power button (or use your assistant gesture), or press a Bluetooth headset's voice button, and the app opens a new chat in voice mode.

Not possible for a regular app: a wake word (hotword detection is reserved for privileged system apps), and opening over the lock screen without unlocking.

## Chat features

- **Streaming.** Replies appear as they're generated (`stream: true`, server-sent events). Tool calls stream too and run as soon as the model finishes them. A server that ignores `stream` and returns plain JSON still works.
- **Stop.** The send button becomes Stop while a reply is in progress. Text received so far is kept. Tool calls that were still running get a "stopped" result, so the next request is valid.
- **Markdown.** Assistant replies render Markdown: lists, tables, code blocks, and links. Links open in the browser.
- **Copy, regenerate, edit.** Copy and Regenerate sit under the latest answer. Long-press a message to copy it, or edit one of your own; editing drops everything after it and asks again.
- **Saved chats.** Chats are saved on the phone as JSON in the app's private storage (`files/conversations/`). The drawer lists them by Today, Yesterday, Previous 7 days, and so on. Long-press or ⋮ to rename or delete. After a new chat's first reply, the model is asked for a short title; if that fails, the first message is the title.
- **Tool cards.** Tool calls show as compact cards ("Searched the web for “…”", "Set media volume to 30%") instead of raw JSON. Web searches list their sources; tap one to open it. The source links are display-only and are not sent back to the model.
- **Theme.** Follows the system light/dark setting, with Material You colors on Android 12+.

## Build

JDK 17 or newer and Android SDK 35 (compile SDK and build-tools 35.0.0).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

`local.properties` is gitignored. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with `./gradlew :app:installDebug` when a device or emulator is connected.

`:core:test` covers the conversation loop, tool failures, the model HTTP client, and the search HTTP client against local stubs. It does not change a device's volume and does not call SearXNG. `AndroidMediaVolume` needs a phone or emulator.

Desktop search tests:

```bash
cd desktop/search_service
python -m unittest discover -s tests -v
```

## Reliability

Unreachable hosts, timeouts, HTTP errors, non-JSON bodies, unknown tool names, invalid arguments, and tool exceptions become an error or a failed tool result. Prior messages stay in the session. A down search service does not drop the chat.

## Not in this build

Voice, cloud model providers, model routing, long-term memory, image/video/file PC tools, paid hosted search APIs, and a permission dialog are specified for later and are not implemented.
