# Semantic code lookup

`friday-code-lookup_search_code(query, path?, limit?)` is an optional read-only OpenCode MCP tool for the
coding agent. Use it for conceptual questions; exact names still belong to grep/read. It returns source,
not a generated answer. No hybrid ranking, automatic context injection or chat-memory changes are included.

## Runtime

Install server code with `FRIDAY_LLM_BACKEND=strata server/scripts/setup.sh`. This adds the embedding
launcher and systemd user unit without replacing existing configuration or restarting any service.
Configure the installation's `code-lookup/embeddings.env` using the example. Set a llama.cpp binary with
EmbeddingGemma 2 support (tested b11570) and the text-only Q8 GGUF. No weights are included or downloaded by
setup. The launcher verifies that the selected Vulkan device is RX580/POLARIS10; CUDA and other GPUs are
refused. It listens only on loopback, with mean pooling and context/batch/ubatch all 8192.

Enable `friday-code-embeddings.service`. In the API service environment set:

```
FRIDAY_CODE_LOOKUP_URL=http://127.0.0.1:8093
FRIDAY_CODE_LOOKUP_ENABLED=1
FRIDAY_CODE_LOOKUP_MODEL=embeddinggemma-2-Q8_0
```

`FRIDAY_CODE_LOOKUP_MODEL` is the vector-cache identity. Change it when changing weights or quantization;
otherwise identical source hashes would reuse old vectors. The embedding service alias should match it.
The prompt version is independently included in cache keys. Restart only `assistant-api` after checking
that no tasks, approvals or questions are active; never restart/reload the coding model to enable lookup.
Omitting the enable flag keeps telemetry on and retrieval off for baseline measurements.

The service is independent of the P100 GPU gate. Check RX580 memory and other clients before enabling;
being on a separate card does not guarantee no shared CPU/RAM or power contention. An unavailable embedder
returns a grep/read fallback and must not fail a task.

## Worktree and privacy boundaries

OpenCode starts a local MCP process in its directory-scoped instance's working directory. The MCP server
uses only that OS working directory, checked against a registry populated by CodeProjects before session
creation. There is no model-supplied root. A home/chat instance is unregistered and cannot search. The
`friday` and desktop agents additionally disable this tool. Its coding permission matches grep in all
three access modes; secret-file exclusions remain even for trusted projects.

The registry, manifests and vectors live under the agent data root's `code-lookup/`, outside repositories.
Tracked plus nonignored untracked UTF-8 files are indexed, including tests/docs. Secret patterns from pc.py,
symlinks, binaries, lockfiles, minified files, files over 200KB, paths over 1000 UTF-8 bytes and pathological long lines are excluded. Corpora are capped
at 64MB of text and 50,000 chunks; larger corpora return a grep/read fallback.
No-follow directory/file descriptors also prevent link-swap escapes during reads.

Windows cover up to 60 lines with 15-line overlap, bounded to 6000 UTF-8 bytes before adding the path prefix.
Queries use `task: code retrieval | query: ...`; chunks use `title: path | text: ...`. Normalized 768D
float32 vectors are scored by dot product in memory. Exact prompt, model identity and prompt version form
the shared cache key. Each worktree has its own file digests/chunk references; another branch's text cannot
be returned. No vector database is needed.

Background builds start with tasks/MCP instances when grep is allowed. In Ask-every-time mode, an
untrusted task does not read or embed files before the user approves search_code; that call starts the
index build. Trusted-project behavior matches the existing full-access override. Every search refreshes changed files, embeds unknown
hashes only and waits up to two seconds before reporting “index still building.” Index writes are atomic;
network work does not hold SQLite write transactions. The final source digest is rechecked before returning
snippets, so an edit during lookup produces a retry message instead of stale text. Discard removes the
manifest and collects unreferenced vectors. Merged results have at most five snippets; complete lines and
headers share a 3000-token budget checked with the EmbeddingGemma tokenizer (an approximate budget for
Flash Next, whose tokenizer differs). Results prefer distinct files;
overlapping windows are merged and clipped around the highest-scoring window if needed. Use read to expand
evidence. Tests without a tokenizer use UTF-8 byte count as a conservative bound.

## Count-only telemetry

The OpenCode plugin adds `x-friday-session` only to the private local provider's requests. The proxy observes
bounded SSE lines as bytes pass through, without buffering the response or changing its chunks. Its
`code-metrics.sqlite3` stores session ID, timestamps, request wall time/status, prompt/cached/completion
counts and stream completion status. Missing usage/cache counts are NULL, never inferred as zero. Nonstream JSON
is also observed up to 64KB. Prompts, source, arguments and output are never persisted by telemetry.

Idempotent task counts come from recorded OpenCode messages: assistant/model turns (including delegated sessions), tool counts by name,
compactions, recorded wall time and a count of reads following retrieved-file snippets. Only counts are
saved; matching paths exist transiently while deriving the count. This is a later-read path-match heuristic,
not proof that a read depended on the retrieved snippet. Metrics are refreshed on session polling
and final completion and included in the session API's metrics field. The phone receives the friendly step
title “Find relevant code” through its existing title field; no Android protocol/UI change is required.

## Verification

Run the server venv's Python from the repository root:

```
python -m unittest desktop.agent.tests.test_code_lookup desktop.agent.tests.test_code \
  desktop.agent.tests.test_pc desktop.agent.tests.test_local_proxy
```

Coverage includes incremental changes, shared vectors, branch isolation, discarded manifests, model identity,
secret/ignored files, links and outside paths, line ranges, merged budgets, service outages, build timeouts,
static MCP protocol, tool permissions/agent configuration and byte-preserving stream telemetry.

Measure baseline before enabling. Compare repeated tasks with the same loaded coding model and record wall
time, fresh/cached/completion counts, turns, tools, compactions, search/read overlap and source-verified
outcomes. Index-build cost, model-cache warmth and timeouts must be reported. Retrieval is successful only
when it reduces work without reducing verified result quality. Automatic retrieval remains out of scope.
