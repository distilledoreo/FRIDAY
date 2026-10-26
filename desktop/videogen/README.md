# Local video generation

`/workspace/videos` renders short videos with sound using LTX-2.5 (the 22B distilled transformer, eight steps)
on the PC's GPUs. Clients choose a **length**, a **shape** and a **speed ↔ quality** level; the server turns that into
a size, a GPU placement and a time estimate. Jobs queue like images; the MP4 lands in Library and a poster frame is
served at `/workspace/videos/{id}/poster`. A chat can queue one with the `generate_video` tool, which returns at once.

## How sizes are chosen

LTX works on 32×32-pixel blocks and on every eighth frame, so a video's GPU workload is
`(width/32) × (height/32) × ((frames − 1)/8 + 1)` blocks. One number limits resolution and duration together. On two
P100s, 1600×896 for 4 s (18,200 blocks) and 640×352 for 26 s (17,380) rendered fully; 1664×928 for 4 s and 640×352
for 28 s ran out of memory. See [video_lab](../video_lab/README.md) for those runs.

`plan.py` holds placements, each with a block limit and measured whole-job times:

| Placement | Cards | Chat | Measured |
| --- | --- | --- | --- |
| `single` | the image card alone | keeps running | 640×352 4 s in 6.8 min; 832×480 4 s in 10.4 min; 10 s at 352p ran out of memory decoding |
| `dual` | both, layers split through a local RPC worker | stopped, then restored | 660 to 18,200 blocks, 2.4 to 40.6 min |

For each length and shape, **fastest** is the smallest acceptable size (about 640×352's area), or a larger one if it
still renders within `fast_seconds`. **Best** is the largest size any placement fits. The three levels between step
evenly in blocks on a log scale. Each level uses the first placement that fits, so the one that keeps chat wins when it
can. `GET /workspace/videos/capabilities` returns the whole table so clients show sizes and times without asking.

One-card jobs read the weights from disk every time, because the chat model holds most of the RAM. That's about 3.5
minutes of each one-card job, so fast videos take about five minutes at least. Two-card jobs stop chat first, so RAM
is free.

## GPU handoff

A job holds the image lock for its whole run, so image jobs wait.

- **One card**: stop the resident image server and GPU speech recognition (which falls back to the CPU), render on the
  image card, then restart speech recognition. The renderer recycles its own file cache above 5 GB (6 GB hard cap), so
  the weights it streams don't push the chat model's RAM into swap. It holds about 2 GB itself plus about 1.7 GB of CUDA
  shared memory that can't be reclaimed, so 2-3 GB limits stalled it. Measured with chat running: short replies took
  about 1 s while it rendered and 10-20 s while it loaded the transformer; with no limit, up to a minute. The chat
  service itself is protected with `MemoryLow` (see Setup).
- **Two cards**: as above, and also stop the chat model through the image service's runtime, after in-flight chat
  requests finish. A journal records the borrow first, so an API restart restores chat. While it renders, chat requests
  get `503` with `X-Assistant-GPU-Busy: video` and the minutes left; Android shows that instead of retrying. Chat is
  restored before the job reports completion.

Afterwards the resident image model reloads in the background. Cancelling stops the renderer units.

## Inputs

A request's `inputs` are Library files that steer the video. The patched runtime
([video_lab/native/ltx_conditioning.h](../video_lab/native/ltx_conditioning.h), built by
`python -m desktop.video_lab.build_conditioning_variant`) handles them:

| Kind | Files | How |
| --- | --- | --- |
| `start_image` | image | encoded in place as the first latent (stock `--init-img`) |
| `end_image` | image | an appended keyframe at the last frame (LTX-2.5's keyframe position embedding) |
| `keyframe` | up to 4 images, each with `at_seconds` | appended keyframes at their own frames (`-r` plus `FRIDAY_LTX_KEYFRAME_FRAMES`) |
| `source_video` | video | its frames (24 fps, trimmed to 8k+1, leaving at least a second to generate) are encoded and frozen as the opening latents, so the rest continues them (`--control-video`) |
| `audio` | audio, or a video's soundtrack | encoded on the CPU by `audio_encode.py` (ComfyUI's PyTorch LTX audio VAE; the runtime has only the decoder) and frozen from the start of the soundtrack (`FRIDAY_LTX_AUDIO_IN`) |

Images are fitted to the video's size by cropping. Keyframes and the end image each add one frame of work, so the
planner rechecks the size and placement with them. `strength` (0-1) loosens conditioning; the renderer applies the
lowest one given to every input. A start image and a source video can't be combined.

Measured on the image card at 640×352 for 4 s: a start image, a keyframe at frame 48 and an end image each came back
almost unchanged at their frames, and a two-tone soundtrack came back at the same frequencies (330 and 495 Hz).
LTX-2.5 has no reference-image or reference-video conditioning (identity or style without fixing a frame), so those
aren't offered.

A kind is accepted only when the config lists it in `inputs`; capabilities report the list, and the app shows a picker
for each. `loras` (`name`, `strength`) take files from `lora_dir`.

## Setup

Build the runtime with [video_lab](../video_lab/README.md): `build_planner_variant`, then `build_conditioning_variant`
(on top of the planner variant) for the patched `sd-cli`, and `build_rpc_worker` for the second card's worker. Then
write the private config, which checksums every file once:

```sh
python3 -m desktop.videogen.configure --binary /private/ltx/sd-cli --rpc-worker /private/ltx/rpc-worker \
  --transformer /private/ltx-2.5-22b-distilled-transformer-Q3_K_M.gguf \
  --text-encoder /private/gemma4-12b-with-proj-ltx-2.5-Q5_1.gguf \
  --video-vae /private/ltx-2.5-video-vae-conv-bf16.safetensors --audio-vae /private/ltx-2.5-audio-vae-bf16.safetensors \
  --image-gpu GPU-... --chat-gpu GPU-... --output /private/videogen.json \
  --inputs start_image,end_image,keyframe,source_video,audio \
  --audio-python /private/ComfyUI/venv/bin/python --comfyui /private/ComfyUI/app
```

One-card videos share RAM with the chat model. Protect it so the renderer's file cache doesn't push it into swap. The
protection only takes effect when every parent cgroup grants it too:

```sh
systemctl --user set-property app.slice MemoryLow=48G
systemctl --user set-property assistant-strata.service MemoryLow=48G
sudo systemctl set-property user.slice MemoryLow=48G
sudo systemctl set-property user-1000.slice MemoryLow=48G
sudo systemctl set-property user@1000.service MemoryLow=48G
```

Point `FRIDAY_VIDEOGEN_CONFIG` at it (or place it as `api/videogen/config.json`), deploy with `setup.sh`, and restart
the API. Without a config, the routes answer `available: false` and the app explains that video isn't set up.
`planner` in the config overrides the placements and timings; add measured points as new sizes are confirmed.

Checks: `python -m unittest desktop.videogen.tests.test_plan desktop.videogen.tests.test_manager desktop.videogen.tests.test_runtime`.
