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
  image card, then restart speech recognition. The renderer recycles its own file cache above 3 GB (4 GB hard cap), so
  the weights it streams don't push the chat model's RAM into swap. Measured with chat running: short replies took
  about 1 s while it rendered, and 10-20 s during the two minutes it loads the transformer. A 2 GB limit stalled that
  load; no limit made replies take up to a minute.
- **Two cards**: as above, and also stop the chat model through the image service's runtime, after in-flight chat
  requests finish. A journal records the borrow first, so an API restart restores chat. While it renders, chat requests
  get `503` with `X-Assistant-GPU-Busy: video` and the minutes left; Android shows that instead of retrying. Chat is
  restored before the job reports completion.

Afterwards the resident image model reloads in the background. Cancelling stops the renderer units.

## Inputs, LoRAs and later features

Requests already carry `inputs` (`start_image`, `end_image`, `keyframe`, `reference_image`, `reference_video`,
`audio`, each a Library file with an optional `at_seconds` and `strength`) and `loras` (`name`, `strength`). The server
refuses them until both of these are true:

1. `runtime.INPUT_ARGS` maps the kind to renderer options. `start_image` (`--init-img`) and `end_image` (`--end-img`)
   are mapped.
2. The private config lists the kind in `inputs`, after it has been tested on these GPUs.

Capabilities report the enabled kinds and the LoRAs found in `lora_dir`. The app shows an input picker only for enabled
kinds, so adding one needs no app update.

## Setup

Build the runtime with [video_lab](../video_lab/README.md): `build_planner_variant` for the patched `sd-cli`, and
`build_rpc_worker` for the second card's worker. Then write the private config, which checksums every file once:

```sh
python3 -m desktop.videogen.configure --binary /private/ltx/sd-cli --rpc-worker /private/ltx/rpc-worker \
  --transformer /private/ltx-2.5-22b-distilled-transformer-Q3_K_M.gguf \
  --text-encoder /private/gemma4-12b-with-proj-ltx-2.5-Q5_1.gguf \
  --video-vae /private/ltx-2.5-video-vae-conv-bf16.safetensors --audio-vae /private/ltx-2.5-audio-vae-bf16.safetensors \
  --image-gpu GPU-... --chat-gpu GPU-... --output /private/videogen.json
```

Point `FRIDAY_VIDEOGEN_CONFIG` at it (or place it as `api/videogen/config.json`), deploy with `setup.sh`, and restart
the API. Without a config, the routes answer `available: false` and the app explains that video isn't set up.
`planner` in the config overrides the placements and timings; add measured points as new sizes are confirmed.

Checks: `python -m unittest desktop.videogen.tests.test_plan desktop.videogen.tests.test_manager desktop.videogen.tests.test_runtime`.
