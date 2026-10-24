# Local model backends

FRIDAY's Android conversation engine calls the authenticated desktop gateway (`server/api/app.py`). The model
server behind it is a private loopback service on 127.0.0.1:8080, and the gateway's GPU gate owns foreground chat,
background inference and image handoffs. Two backends are supported:

| `FRIDAY_LLM_BACKEND` | Model service | Opening cache |
| --- | --- | --- |
| `llama` (default) | `llama-qwen38-mtp.service` (llama.cpp, Qwen3.8 27B) | `prompt_cache.OpeningSnapshots`: slot save/restore files |
| `strata` | `assistant-strata.service` ([Strata](https://github.com/Niko1221/Strata), Qwen3.8-Flash-Next) | `strata.StrataOpeningSnapshots`: Strata's in-memory prefix checkpoint |

The gateway picks the backend at start from `FRIDAY_LLM_BACKEND`. Without it, nothing in this package is imported
and the llama.cpp path is unchanged.

## Installing the Strata backend

1. Install Strata next to its data folder (`~/AI/Strata`, `~/AI/Strata-data`) with its own setup, and check that
   `serve/server.py` answers on 127.0.0.1:8080 with the model's config (`strata-<model>.json`). A Pascal card such as
   the Tesla P100 needs Strata's CUDA 12 engine built with `-DSTRATA_EXPERIMENTAL_SM60=ON` (Strata's setup does
   this for sm_60/sm_70 cards).
2. `FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh`. Besides the usual install, this adds
   `assistant-strata.service` (edit its `--config` to your model's config) and the drop-in
   `assistant-api.service.d/20-strata.conf`, which sets `FRIDAY_LLM_BACKEND=strata` and `FRIDAY_LLM_CONTEXT`, orders
   the API after the model and runs `wait_ready.py` before it starts. Like every unit, they are added only when absent.
3. Stop and disable `llama-qwen38-mtp`, then `systemctl --user enable --now assistant-strata assistant-api`.
4. In `api/imagegen/config.json` set `"llm_unit": "assistant-strata.service"` and
   `"llm_reload_timeout_seconds": 900` (Strata takes a few minutes to reload its expert arena).

Set `FRIDAY_LLM_CONTEXT` in the drop-in to the model's `--max-context`, so OpenCode's local fallback advertises the
right limit. Rolling back is the reverse: remove the drop-in, re-enable the llama.cpp service and restore `llm_unit`.

## What the adapter does

`strata.py` adapts OpenAI chat requests to Strata. It keeps the gateway's stable prompt assembly, tool history,
image parts, streaming and cancellation. Strata uses native sequences and no llama.cpp slots: `id_slot` is dropped and
llama.cpp snapshot files are never loaded into it. Incognito requests (`cache_prompt: false`) turn off checkpoints
and the prefix pin. Background one-shot requests (the gateway's non-streaming transport) do not park their
conversation; ordinary chat keeps Strata's conversation parking, so switching chats reuses longer histories.

Opening priming warms the stable system/tool prefix with one discarded token and asks Strata to pin it
(`strata_prefix: {"messages": 1}` with `strata_checkpoint: true`; without the checkpoint Strata ignores the pin).
Only the stable opening specification is remembered on disk (`slot-cache/last-strata-opening.json`), never a user
message, an answer or inference state.

`wait_ready.py` waits for the model's real loaded state and prepares the opening before the API accepts chats. A
failed preparation is logged and the API starts anyway: the first chat then reads the opening itself. After an image
handoff, the reload hook prepares the opening under the exclusive GPU lease, before chat is reported ready.

## Strata settings that matter here

- **Conversation cache.** Enable it (`--conversation-cache-mib`): pinning alone does not keep an opening when an
  unrelated conversation replaces the active one. Strata parks a conversation only while free RAM stays above
  `--conversation-cache-min-free-mib` (default 2,560 MiB). On a 64 GB PC whose model takes ~51 GB, that floor blocked
  every park: chat and background work took turns evicting each other and re-read 7-12K tokens (25-40 s) on every
  switch. With `--conversation-cache-mib 2560 --conversation-cache-min-free-mib 512` (and the voice models' RAM cut,
  see `server/api/app.py`), switching between two such conversations took 0.8-1.0 s instead of 15-16 s.
- **CPU help for short prompts.** `STRATA_PREFILL_CPU_SHARE=auto` with `STRATA_PREFILL_CPU_SHARE_MAX=3072` in the
  config's `env` block read 2-3K-token prompts 4-8% faster on a P100 + Ryzen 7 3700X.
- Keep Strata's disk session saving, request tracing and MCP tools off: FRIDAY runs tools through the gateway and
  OpenCode's approval flow. The sample unit keeps Strata's temporary image files in its private runtime directory.

### Selecting a card on a multi-GPU PC

Check the negotiated PCIe width before selecting a card for a CPU-offloaded model:

```sh
nvidia-smi --query-gpu=index,uuid,pci.bus_id,pcie.link.width.current --format=csv
```

Identical GPUs can occupy slots with different bandwidth. Compare the same model, context and voice workload on each card; do not assume a fourfold link-width difference produces a fourfold inference gain.

For an Orchid-wrapped Strata service, configure both the parent process and its native children. Set the parent unit's `CUDA_DEVICE_ORDER=PCI_BUS_ID` and `CUDA_VISIBLE_DEVICES=GPU-<selected-uuid>`. Strata's local JSON config also selects physical cards through `gpu`/`gpus`; its launcher can overwrite inherited visibility. Set the matching physical card in that config, or explicitly set the same UUID in its `env` mapping. Verify the actual processes with `nvidia-smi --query-compute-apps=gpu_uuid,pid,process_name --format=csv` after reloading.

Wrapped runtimes can also override prefill and caching settings. Inspect the child process's effective arguments/environment before concluding a config change took effect. Keep local card UUIDs and customized runtime configs outside this public repository.

Orchid's small context selector can use a different card by giving the parent service its own GPU UUID while retaining the native response GPU in the child config. Its CPU-parking policy still applies. See [live voice runtime](../voice_runtime/README.md) for this placement and GPU speech recognition.

## Known Strata issue: the prefix pin and Qwen3.8's template

Strata (through 0.1.42) renders a `{"messages": K}` prefix on its own to find where it ends, and Qwen3.8's chat
template refuses a prompt with no user turn ("No user query found in messages."), so the pin request gets a 400.
The adapter then warms the opening without the pin (logged once): the conversation cache still keeps it, though a
long unrelated chat can evict it. A local Strata fix renders the prefix with an empty user turn after it and cuts
there; until it is upstream it has to be re-applied after each Strata update.

## Tests

```sh
python -m unittest desktop.model_server.tests.test_strata desktop.model_server.tests.test_wait_ready -v
```

## Concurrent Chat and Code

For a shared Strata engine, set `"parallel": 2` in its external model config and add `"--batch-mtp"` to
`args` alongside the existing `--mtp` and `--spec` options. Keep normal prompt checkpoints enabled: moving a
running coding conversation into a slot can otherwise reread its history. Verify `/v1/status` reports
`concurrency.serving: 2`; the engine can fall back to fewer slots if allocation fails. Additional slot state
consumes RAM and VRAM and can reduce expert residency. Start with a modest context and verify memory headroom
before raising it. See [Strata batching](https://github.com/Niko1221/Strata/blob/main/docs/BATCHING.md).

The coding proxy probes the loaded backend's actual slot count. With two Strata slots it can share chat's GPU
read leases while retaining one-at-a-time coding inference. Single-slot backends keep foreground priority.
Image generation, maintenance and recovery still block coding inference and wait for all outstanding streams.

Chat coding tools default new delegated tasks to explicit local selection, expose automatic cloud selection
when requested, and can read results, send follow-up briefs or stop tasks without discarding their changes.
All coding permissions and model pinning stay with the task; chat cannot approve coding-agent requests.
Delegation and follow-ups require the user's request and the existing tool confirmation flow. Incognito
chat excludes these tools. Starting a task does not install an unattended orchestrator or approve publication.
