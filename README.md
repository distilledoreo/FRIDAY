# Local Android Assistant

Phase 1 MVP. The Android app is the assistant: it keeps the conversation, decides which tools exist, and runs them. A computer on your network only serves an OpenAI-compatible chat model. This repo does not include a model server, voice, cloud providers, long-term memory, or PC-side tools.

Milestones implemented:

1. Text chat from the phone to a remote model, with the same conversation kept across turns.
2. A tool registry and one tool, `set_media_volume`, so the model can ask the phone to change media volume and then keep talking.

## Layout

```
core/   conversation engine, model provider interface, tools (JVM, unit-tested)
app/    Compose chat UI, server settings, AudioManager volume control
```

| Package | Responsibility |
| --- | --- |
| `com.localfirst.assistant.conversation` | `Message`, `ConversationSession`, `ConversationEngine` |
| `com.localfirst.assistant.model` | `ModelProvider`, `ModelResponse`, OpenAI-compatible HTTP client |
| `com.localfirst.assistant.tools` | `Tool`, `ToolRegistry`, `set_media_volume` |
| `com.localfirst.assistant.ui` | Transcript, text field, Send, settings |
| `com.localfirst.assistant.settings` | Server address stored on the device |

`ConversationSession` depends on `ModelProvider`, not on Ollama, llama.cpp, or OpenAI. The HTTP client is the only implementation, and the UI wires it in at startup.

Message roles are `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`, and `SYSTEM`. Tool calls and results are real entries in the session. They are not rewritten as user messages. The HTTP client is the only place that maps those entries onto the OpenAI `tool_calls` / `role: tool` wire format.

`Tool.requiresConfirmation` is the hook for a later permission step. Phase 1 has no confirmation UI. A tool that sets the flag is refused and the refusal is sent back to the model as a tool result. `set_media_volume` does not require confirmation.

## Point the app at a local model

Run any server that implements `POST {base}/chat/completions`. In the app, open **Server settings** and set:

- **Server address:** the API root, including `/v1` when the server uses that prefix. The app posts to `{address}/chat/completions`.
- **Model name:** the name your server expects.
- **API key:** leave blank unless the server requires a bearer token.
- **Timeout:** how long a single completion may take. Default is 90 seconds. Connecting gives up after 10 seconds so an unreachable PC fails faster than a slow generation.

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

## Volume tool

`set_media_volume` takes one integer, `level`, from **0 (mute) to 100 (maximum)**. That scale is the tool contract. Android's media stream often has fewer steps (commonly 15). The app maps the percentage onto `AudioManager.STREAM_MUSIC`, rounding half up. For a device whose max step is 15, 30% becomes step 5 (4.5 rounds to 5), 50% becomes step 8, and 100% becomes 15.

The system volume panel is shown when the change is applied. If Android refuses the change, or the stream does not land on the requested step, the session records a failed tool result and asks the model to continue. The transcript is not cleared.

`MODIFY_AUDIO_SETTINGS` is a normal permission and is granted at install.

## Try the two milestones

1. Install the app and set the server address and model name.
2. Send a short message. The reply should appear as **Assistant**. Status should show **Connected**.
3. Send a follow-up that depends on the first message. The same transcript is sent back; a new chat is not started.
4. Send `Set the volume to 30%.` You should see a tool line for `set_media_volume` with level 30, the media volume change, a tool result, and a short assistant reply in the same turn.
5. Send another message. The conversation continues, including the tool result.
6. Stop the server and send again. The new error is shown above the text field, earlier messages stay, and **Retry** asks the model to continue without duplicating your message or running the volume tool a second time.

Other phrases that should select the same tool: "Turn the volume down" or "Set media volume to 70". The model chooses the level. Smaller models sometimes answer in text instead of calling the tool; that is a model limitation, not a second code path.

**Clear conversation** drops the in-memory transcript only. Settings are kept. The transcript is not written to disk, so Android may discard it if the process is killed.

## Build

JDK 17 or newer and Android SDK 35 (compile SDK and build-tools 35.0.0).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

`local.properties` is gitignored. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with `./gradlew :app:installDebug` when a device or emulator is connected.

`:core:test` covers the conversation loop, tool failures, and the HTTP client against a local stub server. It does not change a device's volume. `AndroidMediaVolume` is a thin wrapper around `AudioManager` and needs a phone or emulator to exercise.

## Reliability

Unreachable hosts, timeouts, HTTP errors, non-JSON bodies, unknown tool names, invalid arguments, and tool exceptions become an error or a failed tool result. Prior messages stay in the session.

## Not in this build

Voice, cloud model providers, model routing, long-term memory, PC-side tools, and a permission dialog are specified for later and are not implemented.
