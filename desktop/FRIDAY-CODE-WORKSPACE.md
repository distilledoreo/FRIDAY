# Code workspace feature implementation — 2026-11-04

This follow-up implements the requested task control, project setup, collaboration
and extensions workflows. It builds on the existing native OpenCode engine,
private SQLite state, Git worktrees, command approvals and shared Android/web UI.
It does not add a paid AI provider or deploy the repository changes.

## Task control

- **Queue** in the task composer saves a durable human follow-up. The task menu
  opens queue history, pause/resume and removal. Messages dispatch in order after
  the current turn and its approvals finish. Stop pauses the queue. A crashed or
  unacknowledged dispatch becomes uncertain and requires transcript inspection;
  it is not silently delivered again. Restart also pauses queues whose preceding
  task was interrupted, cancelled or failed.
- **Stop & steer** cancels the current native turn, waits for running tools to stop,
  checkpoints files and sends the new direction. It preserves the task's model,
  permissions and planning mode. Cancellation cannot undo an already completed
  side effect; the continuation explicitly requires inspecting actual state.
- **Fork task** creates an independent branch/worktree containing the source's
  tracked and nonignored working files. It starts idle, with a bounded conversation
  handoff supplied on the first follow-up. The full earlier native conversation is
  accessible through paged read-only history, including after the source worktree
  is discarded. Ignored dependencies, Git staging and verification are not copied.
  OpenCode's native fork was probed and retained the original directory; FRIDAY
  therefore uses its own independent-worktree fork instead of sharing writable files.
- Both Android and browser chat follow-up tools now support explicit send/queue/
  steer delivery and can start work in an existing saved project, including GitLab.
  Existing confirmation and incognito restrictions remain in place.

## Project setup

- **Repositories → New project** creates a previously nonexistent local folder,
  Git repository and initial bootstrap commit. Empty, Python/unittest and Node/
  built-in-test templates are available; the latter two include passing tests and
  required-check profiles. No dependencies are downloaded by scaffolding. Existing
  folders and nesting inside an existing Git repository are refused. Partial
  initialization is preserved for inspection instead of recursively deleted.
- **Repository menu → Project environment** stores reviewed setup steps and
  private environment variables. Step folders must stay within the worktree.
  Values support `${WORKTREE}` and `${PATH}` for project-local environments.
  Secrets are not copied into project listings or the model's setup brief.
- **Task menu → Prepare environment** asks the existing coding agent to run those
  exact commands through normal tool approvals, inspect exits and run required
  checks. It does not introduce an unrestricted second shell executor. A fresh
  verification epoch invalidates old check results even if installation only
  changes ignored dependency files. Changing configured environment values also
  requires fresh evidence.

Recipes can create virtual environments and install dependencies using available
host tools. They are operator-reviewed commands, not a hermetic devcontainer or a
guarantee that every toolchain is installed. Git commits after scaffolding still
need the user's configured identity. The bootstrap commit uses a clearly labeled
local FRIDAY identity without changing global Git settings.

## Collaboration

- **Other Git host** registers GitLab or generic Git clone URLs; GitHub retains its
  existing `gh` integration. GitLab supports nested groups and uses `glab` for API
  actions. Generic Git supports managed worktrees/branches and explicit push.
  URL credentials/query parameters and clone-path overlap/escape are rejected.
  Existing PC Git/CLI credentials are reused rather than copied into this repo.
- **Start stacked task** branches from an idle parent's committed state. The
  relationship is visible in task metadata; child review requests target the
  parent's branch. The parent request must be published before the child request.
  Planning mode is preserved by the shared UI when starting a stack.
- **Review and CI** shows a request-head snapshot, inline discussion paths/lines,
  comments, resolution state and CI jobs. Replies preserve exact multiline text;
  resolving/reopening and posting replies require explicit user actions. The head
  is rechecked before mutation. This is a sampled precondition, not an atomic
  provider-side compare-and-swap against a concurrent force push.
- **Ask FRIDAY to address review and CI** fetches bounded failed-job logs and open
  discussion context, labels it as untrusted evidence and sends a repair brief to
  the same task. It requires local reproduction and required checks; it never
  auto-posts, resolves, pushes or merges on the user's behalf.
- **Base branch and conflicts** supports fetching/merging the base, listing
  conflicted files, choosing the task/base version (including deletion), staging a
  manual resolution, completing or aborting the merge, and asking FRIDAY to resolve
  and test. File checkpoints precede merge actions. Ordinary uncommitted edits must
  be committed before a base merge; no automatic stash hides unrelated changes.

GitHub Actions failed logs and GitLab job traces are supported. Other CI providers
remain links. Review previews are bounded and flag additional discussions; this is
not a full code-host web client, independent reviewer, arbitrary line-comment editor
or automatic stacked-PR rebase/retarget service. GitLab requires an installed,
authenticated `glab`; other hosts do not yet have PR adapters. Nondefault clone
ports are explicitly rejected by this adapter.

## Extensions

**Code → Extensions** manages a versioned local source library:

- Discover Markdown skills or JavaScript modules in a selected PC folder, or paste
  instructions/source. Discovery/import does not execute it.
- Save a disabled revision; review the full source and SHA-256 before explicitly
  enabling it. Updating or restoring a revision revokes approval. Up to fifty
  revisions per extension can be restored as disabled drafts for fresh review.
- Approved skills are exposed through OpenCode's native skill paths. Approved
  plugin modules use the engine's native plugin mechanism. JavaScript syntax is
  checked without executing imports during review.
- Reviewed hook callbacks support before/after tool execution and compaction.
  The callback wrapper selects coding sessions; module initialization still has
  host access. The editor describes the expected default async callback export.
- The library supports disable/remove. Already loaded code cannot be revoked by
  changing a database flag: helper configuration reloads wait for idle, and the UI
  explains that boundary. Immutable source artifacts/history are retained in private
  state until its normal cleanup lifecycle.

Plugins and hooks are explicit host-user trust grants, outside model tool approval
policies. They are not sandboxed or safe merely because syntax validation passes.
Approved code and its imports may perform arbitrary host/network operations. There
is no automatic remote marketplace/package installation in the import/review flow.

The same screen manages **MCP connections**: add/edit reviewed local/remote configs,
preserve redacted stored secrets when editing, approve an exact configuration digest,
enable/disable/remove, connect, OAuth start/callback and sign out. Disabling/logout
closes the idle owned helper so authenticated clients cached in old worktrees cannot
remain connected. Authentication/reconnection changes wait for active tasks to stop.
These are native helper restarts, not API/model-service deployment or systemd restarts.

OAuth sign-in links must be opened on the PC for a loopback callback. A provider-
supplied authorization code can also be submitted. Connect refreshes Code clients
after sign-in. The native engine owns OAuth state/credential storage. No credentials
are copied into model prompts or source-control files by this management flow.

## Validation

- Backend agent suite: 270 tests run, 265 passed and five Docker-dependent tests
  skipped. A further targeted backend/browser-chat regression run passed 115 tests;
  these overlap the agent suite and are not an additional independent coverage count.
  The final category tests passed all fourteen cases after rejecting unsupported
  custom-port clone URLs instead of silently using the wrong forge endpoint.
- Real temporary Git repositories cover initialization, dirty-file fork isolation,
  stacked branch bases, merge conflict resolution (including deletion), continuation
  and abort. Fake GitHub/GitLab CLI adapters verify exact multiline replies, stale
  head rejection, discussions, failed jobs and repair briefs without external writes.
- Real native OpenCode integration with scripted model replies exercises approved
  skills/plugins/hooks, reviewed environment injection, a setup-created virtualenv,
  verification invalidation/reruns, queue delivery, cancelling a running command
  before steering, idle independent fork/continuation and history after source discard.
- Seven Code workspace Robolectric interaction tests pass, including queueing while
  approval is pending and exact-revision extension trust/approval. Captured queue and
  extension review screens were visually inspected. The extension editor uses a
  bounded full-screen layout after the initial alert-dialog layout failed to idle.
- The final combined Gradle run passed in 4m42s: `:app:testDebugUnitTest`,
  `:core:test`, `:app:assembleDebug`, `:core:jsNodeTest`, `:shared-ui:jsNodeTest`,
  `:shared-ui:testDebugUnitTest` and `:web:stageGateway`. Results contain 139 app
  tests, 332 core tests per Android build variant, 120 core JS tests and fourteen
  shared UI tests on each of JVM and JS, with zero failures. Variant/platform
  repetitions are not independent feature counts. Existing Gradle deprecation and
  webpack size warnings remain. `git diff --check` is clean.
- Physical Android device interaction and live browser/API deployment were not
  exercised. Shared controller tests, Robolectric interaction/render checks and
  both client builds establish the tested client coverage.

Native integration uses real OpenCode/Git tools with a scripted synthetic model.
It tests runtime wiring and permissions, not general model intelligence. Tests only
create temporary projects/connections; no real review replies, PRs or GitLab writes
are posted. External OAuth and live GitLab credentials require separate account-level
validation. No live server installation, model service, app installation, commit or
push was changed by this implementation work.

## Remaining priorities

These additions do not close every comparison gap. The next most important safety
improvement is an optional OS-enforced coding sandbox and a protected live-install
boundary for self-modification. Broader unscripted local-model evaluations are also
needed before treating successful tool wiring as reliable autonomous development.
Independent revision-pinned code review, automatic stack rebasing, a terminal/run
workspace, structured browser testing, project coding memory, remote extension
distribution and multi-host/team orchestration remain separate work.

## Release packaging

Version 0.29.0 (Android version code 85) packages these changes. The versioned
Android APK and web gateway build passed; APK signature verification confirms the
same signing certificate as the existing update channel. The validation notes
above describe pre-deployment testing, not a claim of live integration coverage.
