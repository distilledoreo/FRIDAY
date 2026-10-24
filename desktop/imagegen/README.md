# Local image generation

## Resident images on a dedicated GPU

A private runtime config with `"placement":"dedicated"` keeps the managed image server warm between
successful requests. Set `environment.CUDA_VISIBLE_DEVICES` to the spare card's physical `GPU-...` UUID
and `CUDA_DEVICE_ORDER` to `PCI_BUS_ID`. Inside that isolated process the spare card is `cuda0`, regardless
of its host ordinal. Place diffusion and VAE compute/parameters on `cuda0`, and the encoder on `cpu`.
The existing shared-card handoff remains the default for installations without a dedicated profile.

Dedicated jobs do not acquire the chat GPU lease, stop/restart the LLM, wait for its active requests, or
write a borrowed-GPU journal. Startup warms the image service in the background. Ordinary and incognito
jobs share one image lock; successful jobs leave the service loaded. Cancellation, timeout or failure
recycles only the image unit so uncertain sampler work cannot linger. The next image
reloads it. API shutdown stops the owned unit. Closing a private session also clears the image process's
RAM job records, waiting for another session's render to finish first; the next image reloads. Parakeet
and the selector remain on the spare card and consume part of its memory budget.

`configure_dedicated` writes a new private config with verified asset metadata, the Q8 Turbo transformer,
Q4 encoder, CPU text encoding, GPU VAE/DiT and tiled VAE decoding. It preserves the base config's
sampling profile, step count, sigma schedule and LoRA adapters, including the existing four-step
Turbo/Lightning setup. Changing GPU placement does not require changing the sampling setup:

```sh
python3 -m desktop.imagegen.configure_dedicated --base-config /private/current.json \
  --binary /private/sd-server --transformer /private/turbo-Q8_0.gguf \
  --encoder /private/encoder-Q4_K_M.gguf --vision /private/mmproj-F16.gguf \
  --vae /private/qwen_image_2.1_vae_bf16.safetensors --gpu-uuid GPU-... \
  --output /private/imagegen-resident.json
```

Select it through `FRIDAY_IMAGEGEN_CONFIG`, deploy the imagegen module with the existing setup script,
and restart only the API. Check `/workspace/images/health`: `placement`, `loaded`, `runtime_available`
and `missing_files` distinguish configuration from a responding renderer. Test a full 1024×1024 image
while checking chat and GPU ASR; measured peaks and latency determine whether it fits. A warm process
does not guarantee every activation/graph allocation persists. Before an experiment that needs both GPUs,
pause image/background work through the maintenance endpoint and stop `assistant-qwen-image.service`.
Unpause afterward; the next image request reloads the service.

The Android `generate_image` tool submits a durable, idempotent job to the authenticated existing workspace API. The assistant chooses dimensions, steps, seed, transparency and up to two uploaded reference images. The composer’s plus menu → Create image activates image mode; its chip opens aspect ratio and other manual overrides; Images shows progress, cancellation and previous results. Completed PNGs appear directly in the conversation and in Library.

Server policy is authoritative: dimensions are multiples of 16 from 256 to 2000, at most 2000×2000 pixels, 4–12 Euler steps and at most 32 million pixel-steps (8 steps at the largest size). Defaults are 768×768 and 8 steps. Reference images are resized to at most 512 pixels. Rendering has a hard 15-minute timeout; loading and chat restoration have separate bounded waits. Maximum dimensions do not guarantee completion within the render deadline.

The gateway tracks each chat request until its HTTP stream closes, then reserves the GPU, drains existing requests and checks upstream slots. It persists a recovery journal before stopping `llama-qwen38-mtp.service`. The pinned existing Qwen Image 2.1 runtime starts in an owned transient user unit on localhost:8192. After completion, failure or cancellation, it stops that unit and restores the chat service. API restart recovers any borrowed GPU before accepting inference; interrupted jobs never regenerate automatically. A failed restoration blocks inference until recovery succeeds.

During the swap, HTTP chat requests receive 503 with `X-Assistant-GPU-Busy: 1`; Android retries only this explicit pre-inference response. Internal workspace jobs wait without submitting inference. Conversation history is saved before and after the image tool, and the full transcript is sent again after reloading the model. The GPU KV cache is discarded; the conversation is retained.

Deployment wraps the existing `llm` client with `prepare(raw_llm)` before installing workspace routes, then calls `enable(app, auth, workspace_store, raw_llm, gate, config_path)`. The local configuration contains the existing pinned launch command, the chat service name, and verified binary/model SHA-256 hashes, sizes and modification timestamps. Reverify changed runtime files before use. Installation reuses existing model files and never modifies the panorama runtime or resumes its jobs. The image unit binds to the API service so an API crash cannot leave an orphaned GPU process.

No new public listener or connected-service account is introduced. Artifact downloads and image job controls require the existing workspace authentication. Cancellation preserves any already-saved artifact. Removing an image history entry does not remove its Library file.

Set `FRIDAY_IMAGEGEN_CONFIG` to use a separate local runtime configuration without replacing the installed config. Its optional `sample_params` object overrides request sampling settings. Qwen Image 2.1 Turbo needs Euler, CFG 1 and the fixed eight-step schedule documented in [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/qwen_image_2.1.md#turbo):

```json
"sample_params": {
  "sample_method": "euler",
  "sample_steps": 8,
  "guidance": {"txt_cfg": 1},
  "custom_sigmas": [1.0, 0.978453, 0.95418, 0.926626, 0.89508, 0.845148, 0.704534, 0.414568, 0.0]
}
```

Keep the model's path and verified checksum/metadata in sync with the configuration. The existing text encoder, vision projector and Qwen Image 2.1 VAE can be reused.

For [LightX2V's four-step Lightning adapter](https://huggingface.co/lightx2v/Qwen-Image-2.1-Lightning), set `sampling_profile` to `qwen-image-2.1-lightning` and `lora` to an array of `{"path":"adapter.safetensors","multiplier":1.0}`. Paths are relative to the runtime's `--lora-model-dir`; include the adapter's absolute path in the verified `files` list. The profile uses Euler, CFG 1 and resolution-dependent exponential sigma shifting with the adapter's 256/8192 sequence anchors and 0.5/0.9 shifts, without terminal stretching. It overrides caller step counts to four. The publisher documents the standard Qwen Image 2.1 base; combining this adapter with Turbo is experimental and requires separate quality and timing checks.

Checks: `python -m unittest desktop.imagegen.tests.test_manager` in an environment containing FastAPI, httpx and Pillow; Android checks follow the repository's normal Gradle workflow.

## Privacy and foreground control (0.6)

Image integration also installs authenticated RAM-only incognito artifact sessions at `/workspace/incognito`, plus `/workspace/foreground` leases. Incognito has four sessions maximum, 128 MB upload quota per session, 25 MB per file, and 30-minute idle expiry. Prompts/job records/artifacts use `/dev/shm`; ordinary Library and image history remain separate. Exit cancels any running image and waits for restoration before deletion. A shared recovery journal stores only the GPU borrowed flag and opaque job ID on disk. No incognito conversation or prompt is archived. Background/expired process state cleanup is not a guarantee of forensic erasure.

The gateway uses `privacy.prepare_chat` for requests with `X-Assistant-Incognito: 1`. These bypass prompt-cache stabilization and opening snapshots and set cache_prompt false. `desktop/patch_gateway.py` applies these minimal hooks while preserving Claude's existing TTFT implementation, and changes the startup registration to the current router interface. Default API access logs are disabled in this installation. No verbose-prompt/model log-file flags are configured. Debug logging or a differently configured upstream service would require another audit.

POST `/workspace/foreground` with an opaque id and active bool grants a 45-second renewable lease; Android renews during chat/voice. New internal model requests wait while leases, foreground inference or image handoff are active. In-flight work may finish. POST `/workspace/foreground/maintenance` with `{"paused":true}` persists a pause marker for background model work, opening-cache warmups and image workers. New image requests report the pause. Set false once the concurrent model experiments are finished. Direct foreground chat stays available; this does not stop or alter the model service. GPU-dependent quality testing is deferred during the pause.
