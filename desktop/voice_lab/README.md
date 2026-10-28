# PersonaPlex browser trial

This optional loopback-only experiment runs PersonaPlex as a separate speech-to-speech assistant. It does not replace FRIDAY's Parakeet/Paradee voice path or connect PersonaPlex to FRIDAY's tools, memory, or conversation history.

The browser captures mono 24 kHz PCM with echo cancellation and streams it over a WebSocket. The bridge sends a concurrent chunked HTTP upload to audio.cpp's `/v1/audio/speech/live` endpoint and streams the returned audio events back to the browser. One second of silence primes the voice before live microphone streaming starts, so model preparation does not build a backlog of captured speech. Stop closes the connection, microphone, and playback. Sessions are limited to three minutes and microphone recordings are not saved by the bridge.

## Native runtime

Use [audio.cpp](https://github.com/0xShug0/audio.cpp) and its matching [PersonaPlex Q8 package](https://huggingface.co/audio-cpp/audio.cpp-gguf/tree/main/PersonaPlex-GGUF). Arbitrary GGUFs from other runtimes are not interchangeable.

For a P100, build with CUDA 12 and `CMAKE_CUDA_ARCHITECTURES=60`. A reduced build can use:

```sh
cmake -S "$AUDIOCPP_ROOT" -B "$AUDIOCPP_ROOT/build-p100" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DENGINE_ENABLE_CUDA=ON \
  -DCMAKE_CUDA_ARCHITECTURES=60 -DAUDIOCPP_MODEL_SET=custom \
  -DAUDIOCPP_MODELS=personaplex -DAUDIOCPP_DEPLOYMENT_BUILD=ON
cmake --build "$AUDIOCPP_ROOT/build-p100" --target audiocpp_server audiocpp_cli --parallel 2
```

Use a private native-server configuration such as the following, replacing the model path locally:

```json
{
  "host": "127.0.0.1",
  "port": 8997,
  "backend": "cuda",
  "device": 0,
  "threads": 4,
  "lazy_load": false,
  "models": [{
    "id": "personaplex-q8",
    "family": "personaplex",
    "path": "<absolute-path-to-personaplex-7b-v1-q8_0.gguf>",
    "task": "s2s",
    "mode": "streaming"
  }]
}
```

Select the second physical GPU through the environment. The runtime then sees it as device zero:

```sh
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=1 \
  "$AUDIOCPP_ROOT/build-p100/bin/audiocpp_server" --config "$PERSONAPLEX_CONFIG"
```

## Browser bridge

From the FRIDAY repository root, use a Python environment with `aiohttp` installed:

```sh
python -m desktop.voice_lab.personaplex --upstream http://127.0.0.1:8997 --port 8998
```

Open [the local voice trial](http://127.0.0.1:8998), wait for Ready, then select **Start conversation** and allow the microphone. The microphone stays off until you start. Both listeners bind to loopback; remote access is outside the scope of this trial.

The displayed preparation time measures voice-prompt setup before microphone streaming starts. It is not an end-of-utterance response-latency measurement. The current native PersonaPlex runtime provides audio replies without a text transcript. Use headphones when testing interruptions.

Stop the two processes to unload the trial and release GPU 1. No production service configuration changes are required.

## Checks

```sh
python -m unittest desktop.voice_lab.tests.test_personaplex
```

The bridge tests include a reply that arrives before the microphone upload finishes. Real-time factor, hardware compatibility, output quality, and interruption behavior require a loaded model and audio testing.

For recorded-audio measurements, supply mono 24 kHz PCM16 WAV questions:

```sh
python -m desktop.voice_lab.benchmark --output "$TRIAL_RESULTS" question1.wav question2.wav
```

The native run sends input without pacing to measure throughput. The browser-protocol run sends it at microphone speed and measures the output drain after input ends. It uses a one-second preparation pre-roll and the same browser bridge as the page. Physical microphone and human interruption checks still need a browser session.

If started as the trial's transient user units, release GPU 1 with:

```sh
systemctl --user stop friday-personaplex-browser friday-personaplex-q8
```

The existing image handoff checks for GPU processes across the machine. Stop the trial before requesting image generation through that path.

## Compare the existing recognizer with GPU Parakeet

`benchmark_asr` compares the authenticated FRIDAY `/transcribe` endpoint with a resident audio.cpp Parakeet server using identical WAV recordings. It alternates endpoint order, warms both paths once, then records three measured rounds, transcripts, real-time factors, and median HTTP latency. Set `ASSISTANT_API_TOKEN` through your local secret environment; do not put it in a command argument or a results file.

Build audio.cpp with `'-DAUDIOCPP_MODELS=personaplex;parakeet_tdt'` to include both model families. Stop PersonaPlex before loading Parakeet on the same card. Use audio.cpp's `examples/server/parakeet-asr.json` as the configuration template, with loopback port `8999`, model ID `parakeet-gpu`, and your local standalone Parakeet GGUF path. Pin that server to GPU 1 using the environment shown above.

```sh
python -m desktop.voice_lab.benchmark_asr --output "$TRIAL_RESULTS/asr.json" \
  question1.wav question2.wav question3.wav
```

The production recognizer uses ONNX int4/int8 weights; this experiment uses the same Parakeet model in the native GGUF runtime. Quantization and runtime both change, so compare transcripts as well as timing. This benchmark does not route production recognition to the experimental server. A speech-to-speech model also generates the answer itself, while these ASR timings exclude the language model and TTS; those measurements cannot be treated as equivalent end-to-end reply latencies.

## Compare complete spoken turns

With both production services and the PersonaPlex bridge running, use:

```sh
python -m desktop.voice_lab.benchmark_turns --output "$TRIAL_RESULTS/turns" \
  question1.wav question2.wav question3.wav
```

This uses the actual authenticated `/transcribe`, `/chat`, and `/speak` endpoints. It synthesizes the first completed sentence while the rest of the LLM answer streams. The `/chat` gateway applies the normal Strata settings; calling Strata directly can use different reasoning defaults. Every LLM request has a unique turn identifier to prevent exact-prompt completion replay. The shared trial persona excludes tools, memory, and conversation history, so this is a controlled voice-path comparison rather than a benchmark of full FRIDAY agent jobs.

PersonaPlex receives the same questions through the browser bridge at microphone speed, followed by ten seconds of silence for its reply. Each packet arrives after its simulated capture interval. Preparation is timed separately. One warmup per path is excluded, followed by three rounds with alternating path order. Recordings and packet arrival times are saved locally; no microphone or speaker is opened.

Identify the first answer content in the saved audio before comparing latency: greetings and filler are not answers. `playback_time` maps an aligned word's audio timestamp onto the bridge's FIFO playback schedule, including its 60 ms buffer and any stalls. Physical phone/network/playback and automatic endpoint detection are outside this local replay. The phone's current endpointer normally waits for one second of silence; the replay records the WAV's actual trailing silence separately instead.

For pipeline-only experiments, `--path pipeline` does not require the PersonaPlex bridge. Optional `--asr-url` accepts a native multipart transcription endpoint, and `--tts-url` selects an isolated TTS trial. These settings affect only the benchmark process. For example, run a four-thread CPU synthesis trial with:

```sh
python -m desktop.voice_lab.tts_trial --models "$VOICE_MODELS/paradee-8m-v1.0" --threads 4
python -m desktop.voice_lab.benchmark_turns --path pipeline \
  --asr-url http://127.0.0.1:8999/v1/audio/transcriptions \
  --tts-url http://127.0.0.1:9000/speak --answer-first --clauses \
  --output "$TRIAL_RESULTS/fast-speech" question1.wav question2.wav question3.wav
```

`--answer-first` tests short, direct answers without acknowledgments. `--clauses` tests long clause boundaries in addition to sentence boundaries. Commas require at least eight preceding words and a following connective, avoiding breaks between adjectives or list items. Keep phrase-completeness and audio-quality checks separate from latency; the benchmark does not change either client's speech queue.

Fresh-chat latency and continuing-conversation latency are different workloads. Changing the first user message changes Orchid's conversation anchor and can trigger state parking/restoration. For a continuing-session test, retain the full message history, including the unchanged first user message, and append new turns. Preserve this distinction in reports rather than attributing every timing difference to GPU or speech changes.

## LFM3-Audio with Qwen delegation

`lfm3_qwen` is an isolated recorded-audio experiment, not a production voice backend.
Run an LFM3-compatible llama-server with the Audio GGUF and its matching audio
projector, bound to loopback (default trial port `8093`). A P100 needs the CUDA 12
build targeting `sm_60`. Keep the existing Qwen service on its own card.

The three paths are:

- `direct`: stream microphone-paced PCM to LFM3-Audio's `/v1/realtime` and collect its text and speech.
- `hybrid`: expose one `ask_qwen` tool in the model's documented tool prompt. The adapter validates the generated call, streams the query through FRIDAY's authenticated `/chat`, and sends Qwen's clauses to LFM's streaming `/tts`. A deterministic formatter verbalizes numeric notation and removes Markdown emphasis; LFM does not rewrite the answer. Both original clauses and TTS input are recorded.
- `pipeline`: use FRIDAY's existing recognition, Qwen and Paradee endpoints, with the benchmark's answer-first prompt and clause queue.

The native realtime API currently does not transport structured tool messages, so
the hybrid path parses the model's XML call. Unknown, malformed, multiple and
oversized calls fail instead of executing. This trial exposes no action tools,
account data or personal memory. Each recording opens a new LFM session; it is not
a test of long conversation continuity. Qwen's usual prefix caching can still
apply to repeated questions.

Use the official audio response prompt. In the initial native tests, adding a
general assistant persona caused the model to repeat the input question; that
behavior is a failed answer, regardless of how quickly audio arrives.

```sh
python -m desktop.voice_lab.lfm3_qwen --output "$TRIAL_RESULTS" \
  --repeats 3 --paths direct hybrid pipeline -- question1.wav question2.wav
```

The command needs `aiohttp`, `numpy` and `num2words`. It uses `ASSISTANT_API_TOKEN` from the
environment, or reads only that variable from the running local `assistant-api`
service. Credentials are never saved in results. `--qwen-reasoning low` enables
thinking for delegated requests; the default preserves FRIDAY's fast reasoning-off
setting. Keep those runs separate when comparing speed and correctness.
`--prepared` retains a stable Qwen conversation anchor and history; otherwise
the questions are independent. `--routing-policy` adds explicit direct-answer
categories to the audio prompt, but prompting alone has not established reliable
complexity routing. `--raw-speech` disables numeric verbalization to reproduce
speech-quality diagnostics; compact numbers and fractions need separate checks.

One warmup per path is excluded. Results include native text, delegation queries,
Qwen answers, synthesized WAVs, output transcripts and audio packet arrival times.
The first non-silent playback estimate includes a 60 ms playback buffer; it can
still be a greeting or filler. Identify the first meaningful answer word before
claiming answer latency. Recognition of generated speech runs after the timed
turn. Real microphone endpointing, phone/LAN delivery, interruptions and live
speaker playback are not measured. Failed requests remain in `results.json`.

```sh
python -m unittest desktop.voice_lab.tests.test_lfm3_qwen desktop.voice_lab.tests.test_benchmark_turns
```

The audio server must participate in the existing model-service/image handoff or
be stopped before a memory-intensive image job. A trial unit with
`PartOf=assistant-strata.service` stops when the main model service is stopped;
starting Strata again does not automatically restart the trial.
