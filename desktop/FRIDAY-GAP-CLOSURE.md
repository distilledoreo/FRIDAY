# FRIDAY gap closure — 2026-11-03

This follow-up implements four practical gaps identified in
[the comparison audit](FRIDAY-FEATURE-AUDIT.md), using the existing OpenCode engine,
Git worktrees, authenticated API and shared Android/browser workspace. It adds no
paid service, runtime dependency or deployment action.

## Reviewed project verification profiles

The repository menu now has **Required checks**. Configure up to twelve named
commands, their working directories within the task, and either recognized test
results with a minimum count or an explicit exit-zero build/lint criterion.
Settings live in FRIDAY's private project database, rather than an automatically
trusted repository configuration. Saving a command does not run or approve it.

The agent receives these checks at task start and on follow-up. The coding hook
records custom configured commands as well as the previously recognized runners.
The current task view lists each required check as missing, stale, failed,
unconfirmed or passed. Every configured check must pass against the current tree
and current profile revision before the summary says passed. Changing a profile
requires fresh evidence; old ad hoc results cannot silently satisfy it. Exact
commands and resolved working directories must match, allowing only FRIDAY's
`pipefail` prefix and a terminal `2>&1` stderr merge in the comparison. The exact
executed command is retained. Pipelines, exit masks, other arguments and file
redirects are not treated as equivalent. Workspace paths containing
symlinks are normalized consistently between Python and Node.

Profiles specify required checks, not an exhaustive proof of correctness. Exit-only
criteria deliberately allow build/lint runners without recognized test summaries;
recognized failures and truncated output still prevent success. Ignored dependencies,
external services and runtime/environment changes remain outside file fingerprints.
The model can still choose poor additional tests or fail to follow the brief.

## Existing local Git folders

The repository picker can register an absolute existing Git root on the PC.
Local branches are listed without contacting a remote, and each task gets a separate
worktree from committed local branch content. Dirty source-folder edits stay intact
in their original folder. Both Android and browser chat tools accept a local path
and preserve the existing confirmation requirement. No GitHub sign-in is needed.

The folder must already have a commit and a checked-out branch. This does not
initialize a new repository or import uncommitted files. Git commits need the user's
existing Git identity. Local tasks intentionally leave publishing to the user's Git
tools; GitHub-only push/PR actions are rejected before committing or contacting GitHub.

## Recoverable working-file checkpoints

Automatic snapshots precede the first turn and human follow-ups. The task menu also
offers **Save checkpoint** and **Restore files**. A restore first saves current
working files as another checkpoint, so the restored repair can itself be undone.
These private snapshots survive API/helper restarts. They include tracked and
nonignored untracked files, deletions, ordinary permission bits and symlink text.
No symlink target contents are copied. Native OpenCode integration exercises
restoring the original fixture, observing stale verification, then recovering the repair.

Restore requires an idle task and shares its mutation lock with follow-ups and plan
approval. Saved content is checked before mutation; file writes use temporary files
and atomic replacement, walking parent directories without following symlinks.
Newly ignored-file collisions and file/directory topology conflicts are refused.
Ignored files, Git HEAD/history and the staging index stay unchanged. This is
working-file recovery, not a combined transcript/Git rewind or selective restore.

Snapshots use the existing fingerprint limits (20,000 files, 256 MiB and scan time
bounds); unsupported submodules/special files remain unsupported. Automatic snapshot
failure is reported in the agent brief instead of disabling otherwise usable code
tasks. Manual save/restore reports failures. There is no automatic retention cleanup
or whole-tree atomic filesystem transaction. An interrupted restore reports the
backup checkpoint; external concurrent writers and hostile same-user processes are
outside its guarantee. Do not discard the task to recover a failed edit.

## Read-only planning with explicit implementation approval

**Plan first · read only** starts a dedicated agent with file exploration tools.
Shell commands, edits, subagents, skills and external tools are denied even in a
trusted project. Human follow-ups preserve this mode. **Approve plan and implement**
explicitly switches the existing session to the coding agent, saves a checkpoint
and refreshes the ordinary coding permission policy before dispatch. The transcript
and worktree remain available. Native integration verifies attempted plan writes do
not run and the approved implementation can subsequently edit.

This enforces tool permissions; it does not supply an OS sandbox. Trusted coding
sessions still execute as the host user. Full isolated coding execution and an
enforced running-installation boundary remain the largest safety gaps.

## Additional reliability work

Tracked deletions whose parent directories also disappear now remain fingerprintable.
Recovery metadata validates file modes, symlink targets and blob identifiers before
restoring. Native session creation has a 90-second initialization deadline instead
of the ordinary 30-second request deadline; a real evaluation timed out at this
boundary during a client build. No automatic creation retry is performed because
a timed-out request may have succeeded.

Native retry exhaustion now retains a bounded failure reason in the private session
record and exposes it in the task transcript; human follow-up clears the old reason.
This addresses a previously opaque error state without silently changing models or
claiming recovery from an unavailable local model.

Required-check feedback is appended to tool results, naming missing/stale/failed
checks and up to three exact next commands. This was added after real evaluation
showed that a correct repair could finish without satisfying the configured profile.
That evaluation also exposed the harmless stderr merge mismatch fixed above.

The local evaluation now includes normalization and pagination repair cases, the
latter through local-folder onboarding. Both require failing baseline reproduction,
unchanged tests, a documented two-file repair, current configured verification,
independent tests/edge cases and no commits. Infrastructure failures are reported
per case instead of destroying the aggregate report. These are small Python cases,
not a broad benchmark of Kotlin, browser, dependency or large-project competence.

## Validation and remaining work

- Full backend regression: 251 tests run, 246 passed and five optional Docker
  tests skipped. Subsequent targeted checks passed: 80 PC/coding/verification/
  recovery tests, 46 review regressions, and 30 final Code/API tests.
- All 12 Node hook tests passed, including an actual helper subprocess recording
  a custom command through a symlinked workspace path.
- Browser chat integration: 23 tests passed. Android chat-tool tests, shared wire
  tests and other relevant core/shared tests passed on JVM and JavaScript.
- The complete Android unit-test/debug-APK and web staging build passed. A later
  focused Android test run also passed after adding local/plan tool validation.
  No device interaction or rendered UI inspection was performed.
- The deterministic native OpenCode fixture passed with real repository tools,
  durable profile evidence, restore/recover, denied commands and the planning
  approval transition. Model decisions in this fixture are scripted; it tests
  integration and permissions, not reasoning quality.
- The first expanded local-model run failed both strict cases. Normalization
  ended in an error before tool use. Pagination reproduced the failure and passed
  five tests plus independent edge cases, but its terminal `2>&1` caused the
  configured command to be marked missing. A feedback-only follow-up also exposed
  this mismatch. The matcher now normalizes only that output merge, with regression
  tests ensuring pipelines, exit masks, argument changes and file redirects do not
  satisfy a required command.
- Final unscripted pagination validation **passed**: 104.0 seconds, nine tool calls,
  five failing baseline tests before the source edit, the correct two-file repair,
  five passing tests after the final edit, current configured verification, passing
  independent tests/edge cases and zero commits. The final command omitted the
  stderr merge; separate hook/receipt regressions verify the normalized merge.
  This single pass does not erase earlier failures or establish a general pass rate.
- The final verification/hook/native integration regression passed all 14 tests
  after adding required-check feedback and stderr-merge normalization. The final
  PC/coding/verification/recovery suite passed 80 tests after durable retry diagnostics.

The audit still flags OS isolation/self-update protection, independent review and
CI repair, browser/terminal/editor integration, active-turn steering/queueing,
project memory integration, new-repository onboarding and multi-host capabilities.
These improvements close the four flows above; they do not establish feature or
reasoning parity with the combined reference products. No live server files were
changed, services restarted, app installed, commit made or branch published.
