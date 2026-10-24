# Video placement experiments

`benchmark_placement` compares the installed LTX runtime's per-module GPU placement with a single-card CPU-offload baseline. It uses existing local weights and does not download or change a model. Keep machine paths, GPU UUIDs, generated media and raw telemetry in a private directory outside the repository.

The config's `gpu_uuids` lists the fast diffusion card first and the auxiliary card second. This explicit visibility order maps them to the CLI's logical `cuda0` and `cuda1`, independent of physical ordinals. Profiles set both computation and parameter storage:

| Profile | Diffusion | Text encoder | Video/audio VAE |
| --- | --- | --- | --- |
| `single-offload` | cuda0, CPU weights | cuda0, CPU weights | cuda0, CPU weights |
| `dual-resident` | cuda0, GPU weights | cuda1, GPU weights | cuda1, GPU weights |
| `dual-te-offload` | cuda0, GPU weights | cuda1, CPU weights | cuda1, GPU weights |
| `dual-layer` | layers across cuda0 and cuda1, GPU weights | cuda0, CPU weights | cuda0, CPU weights |
| `dual-layer-rpc` | layers across cuda0 and RPC0, GPU weights | cuda0, CPU weights | cuda0, CPU weights |

`dual-resident` should have no CPU parameter storage. An `eager_load` case also allocates every module's weights before generation, exposing residency conflicts early. Text and VAE working buffers must fit alongside the second card's weights; two 16 GB devices still have separate memory limits. Modules execute sequentially, so this placement does not inherently halve sampling time.

`dual-layer` intentionally leaves diffusion parameter placement unspecified: the runtime puts each shard on its assigned device. Its lazy shard allocation allows the text encoder to use GPU memory first, then release staging before the diffusion graph is built. The VAE uses CPU parameter storage and tiled GPU computation. Check `partition` and `split_verified` in each result; a successful process alone does not prove that the transformer actually used both devices.

Provide JSON fields `binary`, `transformer`, `video_vae`, `audio_vae`, `text_encoder`, `gpu_uuids`, `prompt`, `negative_prompt`, and `cases`. Each case has a unique filesystem-safe `name`, a `profile` from the table, integer `width`, `height`, `frames`, optional `eager_load`, and optional CLI `extra_args`. Set `chat_unit` and `health_url` only when the local installation differs from the defaults. The first implementation uses the existing eight-step schedule, Euler, CFG 1, seed 42 and 24 fps for every case.

Capacity cases can set `steps: 1` to exercise full-size computation and decoding buffers with a one-step Euler schedule. Label these as allocation probes; they do not establish finished video quality. Confirm promising settings with `steps: 8`. `timeout_s` limits an individual case; a timeout is recorded separately from an OOM. Resolution and duration searches should change one dimension at a time.

For an adaptive experiment, set `queue_open: true`. The runner rereads the config's case queue after each run and waits for new cases until `queue_open` becomes false. Update the JSON atomically, preserve existing case names, and close the queue when testing is complete so chat is restored. The model paths, GPU mapping and service selected at startup remain fixed during the run.

For a denoising-only capacity search, mark one-step cases with `stop_after_sampling: true` and run `sampling_probe_watcher` against the same config/output directory. It only terminates an exact matching renderer output inside the named benchmark systemd cgroup. A successful sampling marker plus return code `-15` is an intentional probe stop; it does not establish successful full decoding. The `summarize_capacity` command distinguishes these from finished media.

## Isolated second-GPU worker

The installed Pascal runtime can fail in native multi-GPU execution even when the weights are correctly partitioned. `dual-layer-rpc` uses a separate local CUDA process for the second card; pass its loopback endpoint through `extra_args` (`--rpc-servers`). This keeps model weights and computation on both physical GPUs while moving graph boundaries through the RPC transport. It adds overhead and is a capacity experiment.

`build_rpc_worker` links the existing llama.cpp RPC example against the exact GGML archives already used by the installed sd-cli, avoiding a protocol mismatch or another CUDA kernel build. It requires a Ninja build tree and currently matches the installed `GGML_MAX_NAME=160` layout; verify this definition before using another runtime. Keep source/build/output paths outside this public repository's configs.

Bind the worker to loopback and select only the auxiliary card through its CUDA visibility. Restart it between capacity cases to clear cached CUDA allocations. Give its managed unit a conflict with the chat model unit and stop it before normal chat recovery. A worker CUDA OOM can appear in the client as a receive error: preserve both worker and client logs when classifying failures.

The installed layer planner can pack all transformer weights onto the first card if they fit. An explicit per-device `--max-vram` budget may be necessary to force a split. This does not pool device memory; verify the actual partition and each device's peak. Native row mode can silently fall back to layer mode if the linked backend has no split-buffer support.

## Resident-placement planner variant

On later sampling steps, the installed planner can compare the full model's weights against live free VRAM even though those weights already occupy their assigned devices. Near capacity this rejects step two after step one succeeded. `patches/reuse-resident-layer-placement.patch` preserves the established per-segment assignment on later graphs. First-time placements still use the original capacity checks, and CUDA allocations still enforce physical memory limits. This experiment validates the resident LTX diffusion path, not every model/storage mode.

`build_planner_variant` copies and patches only the planner source into a private output directory, rebuilds that object with the original Ninja compilation flags, replaces it in a copy of the static archive, and relinks a separate CLI. It does not edit the installed source or binary. `run_native_regression` checks that the original archive reproduces the repeated-placement failure and the variant reuses its placement when simulated free memory falls; a genuinely fresh placement still fails under that shortage. The small eight-step output comparison should precede larger confirmations.

`run_case` can run one case with an isolated binary while a parent experiment continues to own the service handoff. Use a managed unit tied to that parent and conflicting with the chat model. Its individual result JSON is included by `summarize_capacity`. Do not close the parent's queue until those child units finish.

Run from the repository root:

```sh
python3 -m desktop.video_lab.benchmark_placement /private/placement.json --output /private/results
python3 -m unittest desktop.video_lab.tests.test_placement
```

The runner stops an initially active Strata unit, refuses to proceed if other GPU compute processes remain, then restores Strata in `finally`, waiting for its model health check. The voice recognition companion bound to Strata follows that lifecycle. Use a managed job with an independent stop hook to restore the model service if the benchmark itself is killed. Do not run concurrently with image generation or another video job.

Results include wall time, generation phases parsed from native logs, per-second GPU memory/utilization/temperature, errors and `ffprobe` media metadata. A first small fit check can warm disk caches; compare later matched full clips using identical resolution, duration, prompt, seed and tiling. CLI startup includes weight loading; `generation_s` alone excludes startup and does not prove weights persist across separate jobs. No physical phone or FRIDAY video-tool integration is implied.

## Continuous audio/video windows

`build_chunk_variant` adds a private latent handoff to the resident-placement variant. It patches private copies of the runtime source and LTX model header, rebuilds only the main runtime object, then links a separate CLI against a copy of the patched archive. The installed runtime and its source remain untouched. The integration anchors fail if the source layout changes; this is an experiment for the installed LTX AV architecture, not an upstream runtime replacement.

Each window exports lossless video and audio latents. A continuation imports a span from the previous window's tail into its prefix, freezes that entire span with a denoise mask, and gives the frozen video and audio tokens zero timesteps. New noise and sampling generate the remaining portion. No rendered frame is encoded back into the model, and video frames are joined without blending. This follows the multi-frame carry mechanism in [Lightricks' chunk operators](https://github.com/Lightricks/LTX-2/blob/main/packages/ltx-pipelines/src/ltx_pipelines/chunks/operators.py), with separate state for each stage when latent hires refinement is enabled. Compatibility and visible continuity still require a rendered check on the selected checkpoint.

Audio decoders can reconstruct the same carried context with a small waveform offset. Assembly blends the previous audio tail into the next window's corresponding carried audio over 20 ms by default, then appends the new audio. This uses shared history, preserves duration and removes a hard waveform step; it does not blend the pictures or replace latent conditioning. Set `audio_blend_ms` to zero for a raw diagnostic join, or choose up to 100 ms.

The installed model assumes a scalar audio timestep in its cross-modal helper. The private header retains per-token timesteps for conditioned audio, uses each modality's own token timesteps for cross-attention scale/shift, and uses a scalar window noise level for prompt modulation and cross-modal gates. This matches the distinction in [upstream transformer preprocessing](https://github.com/Lightricks/LTX-2/blob/main/packages/ltx-core/src/ltx_core/model/transformer/transformer_args.py). The prefix-only experiment derives the scalar from the final, generated audio token; arbitrary masks or separately scheduled modalities are outside this implementation's scope. Ordinary unconditioned generation retains the installed path.

`run_chunks` manages the two-card worker, model-service handoff, per-window telemetry, state validation, exact carry audits, and media assembly. It refuses nonempty output directories. The first window is ordinary generation; each later window changes its seed while preserving the carried context. An audit compares every carried video/audio value against the prior tail and fails if the maximum error exceeds `1e-5`. A successful render alone does not prove the conditioning was preserved. The renderer is restarted per window; this is bounded-window execution, not a persistent model server or real-time video streaming.

Add these fields to the private model configuration:

- `rpc_worker`: worker binary linked to the same GGML version.
- `width`, `height` (optional): multiples of 32, before any latent upscale. Native generation defaults to **864×480 (480p)**; explicit dimensions override these defaults. With latent hires enabled, specify the base dimensions explicitly because the output is twice the base size. The default resolution is a preference, not a measured continuation capacity limit.
- `window_frames`, `carry_frames`: `8k+1` frames, with at least nine carried frames and a smaller carry than the window.
- `windows`: number of generated windows, from 2 to 100.
- `seed` (optional), `extra_args` (optional), `case_timeout_s` (optional).
- `initial_window` (optional): a completed first window's result JSON, with matching model paths, dimensions, frame grid, media and exported state. This allows reusing an already rendered starting window when testing a runtime change.

The current runner fixes video at 24 fps. Its window stride must align with the 25 Hz audio latent grid. For example, 97-frame windows with 25-frame carry advance by 72 frames, exactly three seconds. Three windows produce 241 frames, about ten seconds. Overlap occupies part of each denoising window and increases work per new second.

`extra_args` may configure tiling and hires refinement but cannot override the base model paths, dimensions, frame rate, frame count or base sampling schedule. These belong to the runner's AV alignment contract.

```sh
python3 -m desktop.video_lab.build_chunk_variant --source /private/source --build /private/build --planner-variant /private/planner --output /private/chunk-runtime
python3 -m desktop.video_lab.run_chunks /private/chunks.json --output /private/new-run
python3 -m desktop.video_lab.run_chunks --assemble-only --output /private/completed-run --audio-blend-ms 20
python3 -m unittest desktop.video_lab.tests.test_chunks
```

Run under a managed parent unit named `friday-video-chunks.service` (or configure `parent_unit`), with a runtime limit, sufficient stop time for Strata recovery, and an independent model-service restoration hook. The runner stops its worker before restarting initially active chat and waits for model readiness before CPU-only media assembly. A 90-minute runtime and 12-minute stop timeout are suitable for the initial small trial. The API stays available with its CPU speech fallback while Strata/GPU speech are stopped.

`--assemble-only` re-exports completed windows on the CPU, including audio-boundary adjustments, without loading models or stopping services. It requires every generation window to have succeeded and checks final dimensions, frame count, frame rate and matching audio duration.

The private CLI reads `FRIDAY_LTX_CARRY_IN`, `FRIDAY_LTX_CARRY_OUT` and `FRIDAY_LTX_CARRY_FRAMES`. State files use a versioned little-endian float format with a bounded shape and must come from the same checkpoint, VAE, resolution and frame rate. Files contain generated latent data and belong outside the public repository. Combined image/keyframe conditioning and non-LTX models are rejected for this experiment. Memory on the GPUs is limited to one window; host storage and final export still grow with total duration. Long-range identity drift, camera consistency and seam quality are separate from the exact frozen-context audit.

`analyze_chunks` checks a completed output by decoding every frame, measuring frame changes and optical flow around the joins, and writing contact sheets and audio levels. Its optional analysis environment needs NumPy and OpenCV; generation itself uses only Python's standard library plus the existing native binaries and FFmpeg. These measurements aid inspection and do not establish perceptual continuity automatically.

### Observed native trial

On two 16 GB P100s with the existing LTX-2.5 distilled Q3_K_M transformer, three 97-frame windows with 25-frame carry produced 241 frames at 640×352 and 24 fps (10.042 seconds). Every window completed eight sampling steps and decoding. Both handoffs preserved all carried channels within maximum absolute errors of `2.39e-7` for video and `1.20e-7` for audio. Sampled frames and optical flow around both joins showed a consistent rolling-ball scene. This validates the short trial, not arbitrary-duration identity or motion consistency.

The two conditioned windows peaked at 11,661 MiB on the main card and 9,105 MiB on the auxiliary card. They took about 14–17 minutes each, including startup and decoding. Conditioning uses substantially more working memory and time than the ordinary window. Earlier unconditioned capacity measurements must be rechecked for this path. The native integration was tested against stable-diffusion.cpp commit `6b3edaaf`; source anchors and model conventions need review when updating that runtime. This remains a separate experimental runner and is not connected to the regular FRIDAY video interface.

A two-stage smoke test also passed: two 33-frame windows with nine-frame carry generated at 320×192 and refined through the existing ×2 latent upscaler to 640×384. Each window used eight base steps and two refinement steps. The assembled output contains 57 frames (2.375 seconds), with matching audio duration. Both stage-1 and stage-2 AV carry audits stayed below `2.39e-7` maximum absolute error. This establishes both-stage handoff at the small test size; higher-resolution capacity remains unmeasured for conditioned windows.
