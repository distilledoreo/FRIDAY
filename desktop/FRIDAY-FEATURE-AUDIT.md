# FRIDAY coding feature audit

Assessed 2026-11-03; implementation status updated 2026-11-04 against the repository implementation, including the new
verification receipts, and current official documentation/upstream source.
This is a comparison against the **union** of Claude Code, Codex and T3 Code:
a feature need only exist in one comparator to belong in the comparison. It is
not a claim that all three have every feature, or that matching a feature name
matches their models' coding performance.

T3 Code is a control surface for other agent engines, including OpenCode; its
engine capabilities must not be counted as a third independent reasoning system.
Its current upstream documentation describes substantially more than a Codex-only
GUI. Some OpenAI documentation now redirects to the unified ChatGPT desktop docs;
app-only capabilities below are not attributed to Codex CLI or the IDE extension.
Product availability depends on platform, configuration and plan. Upstream `main`
documentation can describe features ahead of an installed release.

## Result

FRIDAY already has the basic coding engine. Its largest gaps are safe unattended
execution, recovery controls, repeatable project-specific verification, developer
workspace ergonomics, and systematic evidence that the local model can complete
larger tasks. Replacing OpenCode or adding another agent framework would duplicate
working capabilities without resolving these gaps.

The receipts and follow-up improvements close several concrete gaps: code task details and both chat paths can
now distinguish recorded checks on the current worktree from stale or inconclusive
evidence. Reviewed project profiles now enumerate required checks, and the summary requires every configured check on the current files. They do not certify task correctness or the completeness of the configured profile.

The follow-up local-folder pagination evaluation passed the strict workflow in
104 seconds: five failing baseline tests, a two-file repair, five passing tests,
current configured verification and independent edge checks. Earlier attempts
failed, including a normalizer run before tool use and a correct pagination repair
whose stderr merge was initially rejected by the profile matcher. The latter
comparison bug is fixed and regression-tested. One passing case does not establish
broad local-model reliability. See [FRIDAY-GAP-CLOSURE.md](FRIDAY-GAP-CLOSURE.md).

**Legend:** **Verified** = exercised in this work's tests; **Present** = found in
source, not necessarily exercised end to end here; **Partial** = meaningful existing
support with an integration/coverage gap; **Gap** = no first-class implementation
found in the inspected FRIDAY coding API/client paths; **Unverified** = upstream
engine capability may exist, but FRIDAY integration is not established. A Gap does
not mean that an agent could never improvise the action with shell commands.

## Execution, context and recovery

| Capability in the combined reference set | FRIDAY | Evidence and concrete gap |
| --- | --- | --- |
| Repository search, file reads, targeted/multi-file edits and test/debug loops ([Claude workflows](https://code.claude.com/docs/en/common-workflows), [Codex CLI](https://learn.chatgpt.com/docs/cli)) | **Verified** | Native OpenCode supplies tools; `agent/code.py`, `agent/pc.py`, and `tests/fixtures/coding_workflow.py` exercise explore → edit → failed repair → correction → tests. Small unscripted local-model repair also passed in the earlier assessment. Larger task competence remains unmeasured. |
| Plans and task lists; review a plan before edits ([Claude workflows](https://code.claude.com/docs/en/common-workflows)) | **Verified, bounded** | Code now has a dedicated read-only planning agent with shell, edits, subagents and external tools denied even for trusted projects. Follow-ups preserve planning mode. An explicit UI/API approval transitions to coding and saves a recovery checkpoint. Native OpenCode tests verify denied writes and the approved transition. This is tool policy, not OS isolation. |
| Test/build evidence and diagnosis ([Claude workflows](https://code.claude.com/docs/en/common-workflows)) | **Verified, bounded** | Durable receipts plus reviewed per-project required commands, working directories, runner type and minimum test counts. Missing/stale/inconclusive required checks cannot yield a passing summary. Profile changes require new evidence. Build/lint exit-only criteria are explicit operator choices. Profile capture is exercised through native OpenCode; selecting sufficient checks remains a human/model responsibility. |
| Context compaction and isolated research context ([Claude extensions](https://code.claude.com/docs/en/features-overview), [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)) | **Partial** | OpenCode compaction plus FRIDAY output bounds, saved logs, handoff instructions and local context-overflow adaptation already exist. Hook delivery is tested; fidelity across many compactions and restarts in a large refactor is not. |
| Persistent project instructions and learned project memory ([Claude memory](https://code.claude.com/docs/en/memory)) | **Partial** | Coding prompt directs reading repository conventions. FRIDAY also has a substantial separate memory/continuity subsystem (`memory/`). No inspected bridge automatically curates reviewed, source-linked coding lessons per repository into subsequent Code tasks. Do not confuse global chat memory with coding-project memory. |
| Symbol navigation, references and language-server diagnostics ([Claude extensions](https://code.claude.com/docs/en/features-overview)) | **Unverified** | OpenCode can have language-server capabilities; FRIDAY does not expose/manage language-server setup or verify symbol-level navigation in its fixtures. Grep/file reading is verified. Validate installed engine support before building a new index. |
| Resume named sessions, fork conversations, rewind checkpoints ([Claude checkpoints](https://code.claude.com/docs/en/checkpointing)) | **Verified, bounded** | Persistent tasks, working-file checkpoints and independent idle conversation forks now have shared UI/API controls. Forks copy tracked/nonignored working files, preserve a bounded handoff and expose paged source history even after source worktree discard. Native OpenCode/Git integration covers fork isolation and continuation. No combined transcript/Git rewind or selective restore. |
| Steer an active turn or queue the next message ([T3 shortcuts](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/keybindings.md)) | **Verified, bounded** | Durable FIFO follow-ups, queue pause/resume/removal, uncertain-dispatch recovery and stop-and-steer are implemented in `code_control.py` and both clients. Native tests cover queue delivery and cancelling a running command before steering. Completed side effects cannot be undone by cancellation. |
| Durable long-running goals and completion criteria ([Codex app capabilities](https://learn.chatgpt.com/docs/features)) | **Partial** | Code can execute many iterations and keep todos; sessions survive for follow-up. No durable coding-goal object with acceptance criteria, checkpointed progress and a verified completion gate. Idle is still an execution state, not success. |
| Parallel specialist agents ([Claude subagents](https://code.claude.com/docs/en/sub-agents), [Codex subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)) | **Partial** | Native `task` tool and a dedicated `friday-desktop` subagent exist; several Code tasks can use separate worktrees. No tested FRIDAY coding-team workflow with explicit ownership, child-result aggregation and conflict handling. Local GPU capacity should constrain concurrency; more agents alone is not an improvement. |

## Repository lifecycle, review and development workspace

| Capability in the combined reference set | FRIDAY | Evidence and concrete gap |
| --- | --- | --- |
| Isolated Git worktrees/branches and diff review ([Claude workflows](https://code.claude.com/docs/en/common-workflows), [T3 source control](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/source-control.md)) | **Verified** | Task worktrees, bounded diffs, first-turn trust and recovery snapshots. New checkpoints precede the first turn and human follow-ups, with manual save/restore in the shared UI/API. Restore first saves current files and survives restart. Git history/staging and ignored files are outside file restore; topology conflicts fail safely. Per-commit comparison, selective restore and recoverable archival UI remain open. |
| Open an existing local project or create one without a code host ([Codex CLI](https://learn.chatgpt.com/docs/cli), [T3 source control](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/source-control.md)) | **Verified, bounded** | Existing local Git registration plus a new-folder/Git-init wizard with empty, Python and Node templates. Templates use standard-library tests and no package downloads. Real temporary repositories verify initialization and required checks. Existing target folders and nested repositories are refused. |
| GitHub publish, PR creation and check status ([T3 source control](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/source-control.md)) | **Present** | Explicit push/PR actions, generated body from the agent summary, draft support and CI/review status already exist in `agent/code.py`. Do not flag PR creation or CI-status display as missing. |
| Multiple code hosts, stacked PRs, integrated review comments ([T3 source control](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/source-control.md)) | **Implemented, partly verified** | Generic Git and GitLab onboarding, parent-based task branches/stacked requests, GitHub/GitLab review threads and replies/resolution, bounded failed CI logs, agent repair handoff and explicit merge conflict controls. Temporary Git conflicts and fake forge APIs are tested. Live GitLab/real review posting remain unverified; glab is optional. No automatic stack rebasing, Forgejo review adapter or custom-port host support. |
| Dedicated independent code review and verified findings ([Claude Code Review](https://code.claude.com/docs/en/code-review), [Codex CLI review](https://learn.chatgpt.com/docs/cli)) | **Gap** | A coding agent can be asked to review. No fresh-reviewer pipeline with pinned revision, structured locations/severity, finding verification and review persistence. `agent/review.py` reviews outgoing account actions, not source code. |
| Security findings workflow and repeated scans ([Codex Security](https://learn.chatgpt.com/docs/security)) | **Gap** | Can run installed scanners through shell permissions. No dedicated repository threat model, finding lifecycle, triage or security review integration. This is separate from FRIDAY's own permission controls. |
| Editor integration, selection context and inline code review ([Codex IDE](https://learn.chatgpt.com/docs/codex/ide), [Claude overview](https://code.claude.com/docs/en/overview)) | **Gap** | Shared Code UI is a task/chat/diff surface, not an IDE extension or source editor. No open-editor selection/diagnostics integration found. |
| Interactive terminal and project run controls ([T3 architecture](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/internals/overview.md), [T3 project settings](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/project-settings.md)) | **Gap** | Shell tool execution/transcripts exist. No user-facing PTY terminal, attach/reconnect/interrupt UI, or saved per-project development scripts/preview controls in Code. |
| Browser-driven frontend testing and shared previews ([Codex app browser](https://learn.chatgpt.com/docs/browser), [T3 remote browser](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/remote-access.md)) | **Partial** | X11 screenshot/input helper can operate a browser when configured; approved MCP could add tools. No built-in Code browser/DOM/console/network testing workflow or preview handoff. Research Chromium previews explicitly disable JavaScript; they cannot validate an interactive app. |
| Visual desktop work ([Codex app capabilities](https://learn.chatgpt.com/docs/features), [Claude overview](https://code.claude.com/docs/en/overview)) | **Partial** | `agent/computer.py` and the restricted desktop subagent exist with approvals. Local coding provider is text-only; the configured vision helper uses available free vision models. Fully local visual testing is not established. |
| Reproducible development environment setup ([Claude sandbox options](https://code.claude.com/docs/en/sandboxing), [Codex sandbox](https://learn.chatgpt.com/docs/sandboxing)) | **Verified recipes; isolation remains a gap** | Reviewed per-project setup commands and private environment values are editable through both clients. Preparation uses existing approval-controlled tools; recipe/environment changes invalidate old verification. Native integration creates a temporary virtualenv and reruns required tests with reviewed environment variables. No devcontainer, container sandbox or dependency-cache guarantee. |

## Safety, extensibility and operations

| Capability in the combined reference set | FRIDAY | Evidence and concrete gap |
| --- | --- | --- |
| Tool/command approval policies ([Claude sandboxing](https://code.claude.com/docs/en/sandboxing), [Codex sandbox](https://learn.chatgpt.com/docs/sandboxing)) | **Present, tested paths** | Ask/ask-before-changes/full modes, custom allow/deny patterns, secret-read rules, project trust and remote approval cards exist. Denied native commands are tested not to execute. Trust is intentionally broad; it is not least-privilege isolation. |
| OS-enforced file/process/network sandbox for coding ([Claude sandboxing](https://code.claude.com/docs/en/sandboxing), [Codex sandbox](https://learn.chatgpt.com/docs/sandboxing)) | **High-impact gap** | Native Code executes with host-user permissions. FRIDAY's isolated research/script Docker subsystem does not isolate Code worktrees. Claude's optional Bash sandbox has documented exclusions; Codex also separates sandboxing from approvals. Neither comparator should be described as magically containing every integration. |
| Safe self-modification and recovery | **Partial** | Source worktrees, no-follow previews, approval rules and explicit no-deploy/restart instructions help. No enforced running-installation deny boundary, deploy staging/health-check transaction or automatic rollback for FRIDAY self-updates. Existing setup/restart scripts are not an autonomous safe-update pipeline. |
| MCP integrations ([Claude extensions](https://code.claude.com/docs/en/features-overview), [Codex CLI](https://learn.chatgpt.com/docs/cli)) | **Implemented, partly verified** | Reviewed configuration manager supports add/edit, exact-digest approval, enable/disable/remove, connect and OAuth start/callback/logout. Redacted secrets survive edits; revocation closes cached clients only after active tasks stop. Unit tests and installed native API schema cover routing; real external OAuth/provider behavior remains unverified. No remote marketplace discovery. |
| Reusable skills and plugin distribution ([Claude extensions](https://code.claude.com/docs/en/features-overview), [Codex CLI](https://learn.chatgpt.com/docs/cli)) | **Verified local library; distribution gap** | Shared UI/API supports local discovery/import, source review, immutable version history, disabled restoration and exact-revision approval. Native integration exercises reviewed skills and plugins. Project-supplied plugins stay disabled. No remote marketplace, dependency installer or project-specific library override. |
| User-configurable lifecycle hooks ([Claude extensions](https://code.claude.com/docs/en/features-overview)) | **Verified, bounded** | Reviewed local callbacks support before/after tool execution and compaction with versioned approval. Native integration exercises the after-tool callback. Plugins/hooks explicitly grant host-user access outside model approvals; they are not OS sandboxed. Broad third-party compatibility remains unverified. |
| Multi-engine/model control ([T3 README](https://raw.githubusercontent.com/pingdotgg/t3code/main/README.md)) | **Partial** | One OpenCode engine, explicit local or automatic free-cloud/local fallback, dynamic ranking and model history. No cross-harness adapters, arbitrary model/effort picker, or multi-account subscription manager. These are lower priority than local reliability for the stated goal; paid dependencies are not required. |
| Remote phone/web control and multi-host environments ([T3 remote access](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/remote-access.md)) | **Partial** | Android and web control the PC through the authenticated gateway, including approvals and results. No first-class multi-host inventory, SSH bootstrap/host handoff, route failover or workload balancing in Code. |
| Scheduled and event-triggered development tasks ([Codex schedules](https://learn.chatgpt.com/docs/automations), [T3 project settings](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/project-settings.md)) | **Partial** | Native PC schedules and the older approved research schedules already exist. Native schedules start general PC sessions, not a project-scoped Code worktree with verification criteria. No Code webhook/issue/CI-event trigger lifecycle found. |
| Headless automation and external orchestration ([Codex CLI](https://learn.chatgpt.com/docs/cli), [T3 outside agents](https://raw.githubusercontent.com/pingdotgg/t3code/main/docs/user/outside-agents.md)) | **Partial** | Authenticated Code/PC HTTP APIs and chat start/status/follow-up/stop tools exist. No documented FRIDAY coding CLI/SDK with stable event stream and an OAuth-scoped external-agent orchestration service. Existing internal computer MCP is a different service. |
| Usage, resource budgets and evaluation | **Partial** | Local GPU gate, free-provider constraints, timing/model history and deterministic integration tests exist. A two-case local repair evaluation now exists, but no broad repeatable coding benchmark with per-task pass rate, tool/token/time budget and regression tracking. Feature parity does not demonstrate reasoning parity. |
| Native desktop/iOS versus Android/browser surfaces ([Claude overview](https://code.claude.com/docs/en/overview), [T3 README](https://raw.githubusercontent.com/pingdotgg/t3code/main/README.md)) | **Partial** | Android and browser share Code models/screens. No standalone native desktop coding application or iOS host in the inspected module layout. A browser on desktop is useful existing coverage, not a missing desktop capability altogether. |

## FRIDAY capabilities to preserve

FRIDAY is also a personal assistant: local inference/GPU coordination, Android
voice and phone tools, shared web UI, local image generation, private memory and
conversation continuity, account tools, and daily briefing all already exist.
See `core/.../voice`, `core/.../tools`, `desktop/model_server`, `desktop/imagegen`,
`desktop/memory`, and `desktop/agent/accounts.py`/`briefing.py`. Those are useful
assets, but their presence outside Code does not prove integration into coding
tasks. In particular, do not trade away local operation for a subscription-based
multi-harness picker merely because a comparator offers one.

## Recommended order, with acceptance tests

1. **Broaden local coding evaluations and verification coverage.** Project verification profiles are implemented; continue beyond the small repair fixtures.
   The shared UI now stores reviewed commands and displays missing required checks. Extend beyond the normalizer and pagination fixtures to Python API changes,
   Kotlin shared models, JS UI, dependency failures and multi-step debugging.
   Success: the local model repeatedly completes these cases, and changed or
   untested code never receives a current-success summary by inference alone.
2. **An optional isolated execution mode for Code and an enforced self-update
   boundary.** Reuse existing permission UX; add scoped mounts, network policy and
   explicit escapes. Keep the live installation and credentials outside the
   writable workspace. Success: tests demonstrate denied out-of-worktree writes
   and network access while ordinary builds still run.
3. **Extend the implemented recovery and local-folder flows.** Working-file checkpoints and existing local Git folder onboarding are implemented and tested. The new-repository wizard and independent conversation forks are now implemented. Selective file restore and commit comparison remain open. Retain ignored-file and Git-history safeguards.
4. **Independent review and CI repair.** Pin reviews to a revision; fetch check logs
   and review comments (fetch/repair controls are now implemented); validate findings and recheck fixes. Never auto-merge or
   publish merely because tests passed. Success: an injected bug is found and a
   repaired CI failure gets a current verification receipt.
5. **Structured frontend testing and workspace controls.** Add a reviewed browser
   adapter, preview/console inspection, then terminal and project-run UI. Success:
   reproduce and repair a browser interaction bug with observable assertions.
6. **Expose existing engine features before rebuilding them.** Validate LSP,
   skills, plan mode, fork/restore and bounded subagents through FRIDAY; add only
   the missing client/permission lifecycle. Multi-host and multi-harness UX can
   follow demonstrated need.

The audit flags gaps; it does not authorize enabling paid providers, installing
third-party plugins, deploying FRIDAY, or changing the user's existing trust settings.

## Follow-up implementation

Reviewed verification profiles, existing local-folder onboarding, recoverable working-file checkpoints and enforced read-only planning are implemented. See [FRIDAY-GAP-CLOSURE.md](FRIDAY-GAP-CLOSURE.md) for scope, validation and remaining boundaries. The requested task control, project setup, collaboration and extension workflows are now implemented with bounded validation. See [FRIDAY-CODE-WORKSPACE.md](FRIDAY-CODE-WORKSPACE.md) for the current controls, evidence and limitations. Independent review, OS isolation, frontend testing, terminal/run controls, coding memory and broader local-model evaluations remain open; this is not a parity claim.
