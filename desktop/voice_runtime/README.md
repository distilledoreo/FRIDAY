# Live voice runtime

Normal Android and browser live voice sessions resume the shared `Live voice` conversation defined by `LiveVoiceConversation` in `core`. It has a stable synchronized ID, independent of the text chat from which voice was opened. Existing text drafts stay with their chats. Incognito voice keeps its private session and never opens the saved conversation. Deleting the saved voice chat starts a new history on the next normal voice session.

The shared voice prompt asks for direct, short answers while retaining normal tools, memory and approvals. The speech queue can start a complete long clause before the rest of a sentence arrives; conservative comma handling avoids splitting adjectives and lists. Neither client promises a fixed response time: context growth, cache eviction, model reloads, platform-specific tool prompts and tool validation still affect latency.

## Optional GPU recognition

The API normally loads CPU Parakeet and Paradee. `FRIDAY_VOICE_ASR_URL=http://127.0.0.1:8999` makes recognition prefer the local audio.cpp Parakeet companion, falling back to the resident CPU model if that process is absent, loading, or returns an invalid response. The next request automatically tries the GPU again. Audio is restricted to a loopback destination. `FRIDAY_TTS_THREADS=4` configures four-thread CPU synthesis; the default remains one thread.

Install a native audio.cpp build with Parakeet support and a compatible GGUF model. Put its loopback configuration in a private file with model ID `parakeet-gpu`, family `parakeet_tdt`, task `asr`, CUDA backend and logical device zero. Select the intended physical card with the companion unit's `CUDA_VISIBLE_DEVICES`. See `desktop/voice_lab/README.md` for benchmarking and native builds.

The optional `friday-voice-asr.service` reads `~/.config/friday/voice-asr.env`:

```sh
FRIDAY_VOICE_ASR_BINARY=<absolute-path-to-audiocpp_server>
FRIDAY_VOICE_ASR_CONFIG=<absolute-path-to-private-server-json>
CUDA_DEVICE_ORDER=PCI_BUS_ID
CUDA_VISIBLE_DEVICES=GPU-<recognition-card-uuid>
```

It is bound to `assistant-strata.service`. Enable it only after configuring the runtime:

```sh
systemctl --user enable --now friday-voice-asr.service
```

The unit stops when Strata stops and starts with it, so the image runtime's existing model-service handoff releases both GPUs. The API stays alive and falls back to CPU recognition while the companion is stopped. It does not download weights or replace the response LLM.

With Orchid SpecPrefill, the service parent owns the small context selector while Strata's native and vision child processes use the external model config's `env`. To separate them, select the spare card in the parent unit's `CUDA_VISIBLE_DEVICES` and keep the response card's UUID in the child config. Verify both placements after reloading. If the selector uses another GPU, `ORCHID_SPECPREFILL_STUDENT_VRAM_MIB` can match the engine's own minimum reserve instead of reserving additional room for the selector on the response card. Orchid still moves the selector to CPU for native turns. Short voice turns bypass selection, so this placement alone does not establish a voice latency improvement.

## Deployment and checks

Deploy code with `FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh` and restart `assistant-api`. The default setup installs all feature modules. For a scoped update that preserves other installed modules, `FRIDAY_SETUP_MODULES="voice_runtime workspace"` selects only those modules; the API core is always copied. `FRIDAY_SETUP_SKIP_DEPENDENCIES=1` skips venv/package setup only when the installed dependencies are already sufficient. Existing secrets and local unit files remain protected by setup's usual behavior.

```sh
python -m unittest desktop.voice_runtime.tests.test_asr
./gradlew :core:testDebugUnitTest :core:jsNodeTest :app:testDebugUnitTest \
  :shared-ui:testDebugUnitTest :shared-ui:jsNodeTest :app:assembleDebug :web:stageGateway
```

Verify a real transcription reports `stt_backend=gpu`, stop the companion and verify CPU fallback, then start it and verify GPU recovery without restarting the API. After deployment, verify Strata and the selector's actual GPU UUIDs, not just their logical CUDA ordinals. A selector running on a separate card still follows Orchid's CPU-parking policy unless that runtime is separately configured otherwise.
