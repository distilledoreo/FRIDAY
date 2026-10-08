# FRIDAY agent foundations

This package does not yet run a cloud agent. Android Activity and model proposal
tools have been implemented, but this backend is not deployed or connected to an
execution engine yet. It provides boundaries for that integration:

- `sandbox.Sandbox`: offline Python execution in a digest-pinned Docker image,
  four CPU cores of quota, 8 GB memory with no extra swap, 128 processes, unprivileged
  user, read-only root, no capabilities, no GPU runtime or devices, no host mounts
  and no network. Work files use a 256 MB private tmpfs and `/tmp` has 64 MB.
  Output is capped at 1 MB; wall time at 15 minutes. Docker logging is disabled.
  Temporary files and containers are removed after completion or failure.
- `approvals.Approvals`: SQLite-backed proposed/approved/running/done task states,
  cancellation and paged events. A task approval does not grant action approval.
  Sending, submitting, logging in, purchasing and deleting require their own exact
  payload fingerprint. Action approvals can be claimed once; cancellation revokes
  pending approvals. A crash after claiming requires reconciliation, never an
  automatic replay of an uncertain external operation.

- `api.install`: authenticated task proposals, UI-only approvals, cancellation,
  bounded event pages and interruption after restart. An unconfigured execution
  engine returns 503 before recording approval. A model has proposal/status tools
  only; incognito blocks both. The Android Activity screen reviews complete plans
  and exact action payloads, pages events and polls running tasks.
- `public_web.read`: public HTTPS reads, validated DNS addresses pinned to the
  TLS connection with the original hostname, private/tailnet/metadata/mapped IPv6
  rejection, redirect revalidation, no cookies or authorization, 2 MB response cap.
- `cloud.FreeCloud`: credential held by the host broker, current catalog pricing
  verification, free model allowlist, zero maximum token price, fixed endpoint,
  bounded context/output/request count, no paid/local fallback or provider plugins.
  Only public catalog metadata has been verified live; no inference was run.

The authenticated user API owns approval methods. Model tools must not expose
them. Proposal payloads and events are not a credential store. Artifact export,
screenshots, sandbox IPC, OpenCode execution, outgoing review and account/vault
integration are still pending. Restart marks uncertain tasks interrupted without
replaying external effects. The offline boundary remains in place; the tested
public reader and free broker still need a narrowly scoped IPC adapter into the
  reasoning container. `ipc.UnixBroker` now provides a private, size-limited,
  task-bound Unix socket capability for model/page/action-proposal requests only,
  with cancellation revocation. Its three tests pass. The OpenCode container
  still needs to connect to that capability through its local HTTP/MCP adapters.
  A real pinned TLS read of example.com passed. Docker bridge networking would allow home-network access
and is insufficient.

Docker stores its images on `/mnt/docker-data`; the small Python image fits its
existing free capacity. Nothing was resized or pruned. Enabling the Docker group
requires the user's administrator action. After membership is added, a process
can use `sg docker` without restarting Docker or the user's desktop session.

Run unit tests from the repository root:

```sh
python3 -m unittest desktop.agent.tests.test_approvals desktop.agent.tests.test_sandbox -v
```

Opt in to the actual CPU-only Docker checks after pulling the pinned image:

```sh
sg docker -c 'FRIDAY_DOCKER_TEST=1 python3 -m unittest desktop.agent.tests.test_sandbox -v'
```

These tests check host-file/device isolation, network failure, cgroup resource
limits, read-only root, bounded output, timeout cleanup and approval persistence/
exact-payload/one-time-claim behavior. They make no local-model or GPU calls.
