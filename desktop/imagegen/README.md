# Local image generation

The Android `generate_image` tool submits a durable, idempotent job to the authenticated existing workspace API. The assistant chooses dimensions, steps, seed, transparency and up to two uploaded reference images. Android Settings → Image generation supplies manual overrides; Images shows progress, cancellation and previous results. Completed PNGs appear directly in the conversation and in Library.

Server policy is authoritative: dimensions are multiples of 16 from 256 to 2000, at most 2000×2000 pixels, 4–12 Euler steps and at most 32 million pixel-steps (8 steps at the largest size). Defaults are 768×768 and 8 steps. Reference images are resized to at most 512 pixels. Rendering has a hard 150-second timeout; loading and chat restoration have separate bounded waits. Maximum dimensions do not guarantee completion within the render deadline.

The gateway tracks each chat request until its HTTP stream closes, then reserves the GPU, drains existing requests and checks upstream slots. It persists a recovery journal before stopping `llama-qwen38-mtp.service`. The pinned existing Qwen Image 2.1 runtime starts in an owned transient user unit on localhost:8192. After completion, failure or cancellation, it stops that unit and restores the chat service. API restart recovers any borrowed GPU before accepting inference; interrupted jobs never regenerate automatically. A failed restoration blocks inference until recovery succeeds.

During the swap, HTTP chat requests receive 503 with `X-Assistant-GPU-Busy: 1`; Android retries only this explicit pre-inference response. Internal workspace jobs wait without submitting inference. Conversation history is saved before and after the image tool, and the full transcript is sent again after reloading the model. The GPU KV cache is discarded; the conversation is retained.

Deployment wraps the existing `llm` client with `prepare(raw_llm)` before installing workspace routes, then calls `enable(app, auth, workspace_store, raw_llm, gate, config_path)`. The local configuration contains the existing pinned launch command, the chat service name, and verified binary/model SHA-256 hashes, sizes and modification timestamps. Reverify changed runtime files before use. Installation reuses existing model files and never modifies the panorama runtime or resumes its jobs. The image unit binds to the API service so an API crash cannot leave an orphaned GPU process.

No new public listener or connected-service account is introduced. Artifact downloads and image job controls require the existing workspace authentication. Cancellation preserves any already-saved artifact. Removing an image history entry does not remove its Library file.

Checks: `python -m unittest desktop.imagegen.tests.test_manager` in an environment containing FastAPI, httpx and Pillow; Android checks follow the repository's normal Gradle workflow.
