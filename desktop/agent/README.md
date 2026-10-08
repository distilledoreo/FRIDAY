# FRIDAY agent foundations

This package is not yet connected to chat or Activity and does not run a cloud
agent. It provides two boundaries for that integration:

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

The authenticated user API must own approval methods. Model tools must not expose
them. Keep secrets in a separate vault; proposal payloads and events are not a
credential store. Artifact export, screenshots, cloud models, public browsing,
outgoing review, task recovery and phone Activity are still to be implemented.
The offline boundary must remain in place until a restricted network broker is
verified. Merely selecting Docker bridge networking would allow home-network
access and is insufficient.

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
