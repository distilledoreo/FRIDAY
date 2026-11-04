# Coding reliability assessment and improvements

## Existing capabilities

FRIDAY already delegates software development to native OpenCode. `agent/code.py`
manages GitHub clones, a new branch/worktree per task, Git identity, change previews,
follow-ups, cancellation and explicit push/PR actions. `agent/pc.py` supplies the
model selection, persistent sessions, approval rules, questions, cancellation and
free-provider failover. Local selection retains the local model; the private proxy
uses FRIDAY's GPU gate. OpenCode supplies glob/grep/read, exact edits, multi-file
writes, shell execution, todos and context compaction. The isolated research agent
is a different execution path and is not the coding workspace.

Android chat's `CodeTools` and browser chat delegate to the same code API. Both
clients use the shared `ui/code` workspace for task conversations, approvals and
diff review. These changes preserve that API and require no new client feature.

The main gaps found were in orchestration and evidence, not missing basic tools:

1. Project trust was looked up before the new task's worktree was registered.
   A trusted project's first turn therefore still asked for build/test commands.
2. Coding had only a short general instruction to run quick tests. There was no
   FRIDAY-specific handling of silent shell failures, large coding outputs or
   verification details during compaction. Tool completion alone is not test success.
3. File previews read entire files before slicing. Untracked diff previews followed
   symlinks, potentially reading outside the worktree. Individual untracked patches
   could also accumulate past the intended total diff limit.
4. Live local-model evaluations found correct final implementations but inconsistent
   reproduction before editing and shell lists that masked failed checks with a
   trailing successful `echo`. Prompt instructions alone did not reliably fix these.

## Implemented

- Bind the pending worktree to its project before session creation and initial
  permission construction, without publishing a task before its session exists.
  Failed starts remove the pending binding, branch and worktree.
- Add coding-specific OpenCode hooks in `agent/code_workflow.mjs`. Default reads use
  250 lines; explicit ranges remain available. Text tool results above 16,000
  characters show their beginning/end and an output-file reference. Existing
  OpenCode artifacts are reused; otherwise the original tool result is saved under
  the private PC state directory's `code-output` folder (0700 directory, 0600 file).
  If saving fails, the original evidence is retained. These files follow the PC
  session data lifecycle; this change does not add automatic log deletion.
- Expose the actual shell exit code in the model-visible result and transcript.
  Unknown status is explicit. A zero exit still requires inspecting test counts
  and output; it does not prove that the relevant behavior was tested.
- Enable `pipefail` in coding shell invocations, so a failing test piped through
  `tail` or `tee` does not become exit 0. The shell option uses the same OpenCode
  execution and permission checks. In Ask before changes, only the added
  `set -o pipefail` builtin is preallowed; original commands keep their rules.
- Give compaction a coding handoff checklist: objective, constraints, branch,
  changed files, todos, actual commands/results, remaining failures, saved output
  paths and denied actions. The normal compaction prompt is retained.
- Strengthen the coding workflow: scoped exploration, reproduce, edit, test,
  diagnose failures, repair, rerun after the final edit, review the diff and report
  evidence. Self-modification instructions keep work in the source worktree and
  require explicit authorization for deployment or service restarts.
- Before the first native source edit/write in each human turn, give one actionable
  reminder if no common check command has been attempted. That first edit does not
  run; a retry is allowed for unavailable/inapplicable checks. Failed check attempts
  count as attempts, never as passing verification. Documentation and other agent
  types are unaffected; synthetic continuation messages preserve reminder state.
  This is workflow guidance, not a security gate or a shell parser, and cannot
  prevent edits through arbitrary shell commands.
- Bound file reads, reject special files, and walk directories with no-follow file
  descriptors, including rejecting a worktree root replaced with a symlink.
  Untracked symlinks render their link target, as Git does, instead of
  reading it. Git patch capture is bounded while its pipes keep draining; patch
  responses have per-file and aggregate limits with explicit truncation. Tabbed
  filenames remain in change statistics.

No new Python/npm packages, cloud services, shell executors or permission bypasses
were added. The hooks use the installed OpenCode 1.18.35
[plugin interface](https://raw.githubusercontent.com/anomalyco/opencode/v1.18.35/packages/plugin/src/index.ts).

## Validation

- Baseline: 66 code/PC/local-proxy tests passed before edits.
- Final backend regression with native OpenCode enabled: 230 tests run, 225 passed,
  five optional Docker integrations skipped.
  The JavaScript hook wrapper additionally executes 11 Node tests: role isolation,
  explicit read ranges, exit statuses, bounded outputs, original artifact preservation,
  storage failures, baseline reminders/follow-ups and compaction context.
- Installed OpenCode integration: a temporary Git repository was searched and read,
  edited in two files, tested with exits **1 → 1 → 0** (including a failed test
  piped through `tail`), and checked independently
  (three tests passed). The model received a silent exit-7 failure. Large output was
  shortened and its original was readable. Compaction received the coding handoff.
  First-turn project trust worked; an untrusted rejected command created no file.
  A premature source edit was stopped; its reminder reached the model and the
  baseline still failed all three tests before any implementation edit ran.
  **Model decisions in this integration are scripted**, not reasoning-quality evidence.
- The existing installed-OpenCode routing/desktop/approval integration also passed.
- The pipefail and reminder changes passed 27 focused Python tests including the
  real coding tool loop. The later root-symlink guard and evaluator regression
  passed 27 code/hook/evaluator tests, including the 11 Node checks in their wrapper.
- Exploratory local-model runs produced correct changes to the normalizer and
  README; three independent tests and additional whitespace/empty-input checks
  passed. Untrusted runs correctly stopped at additional Python command approvals.
  Trusted runs exposed masked test statuses from `test | tail` and trailing
  `echo`, motivating pipefail and separate-check guidance. The live evaluator now
  records the runner's actual verdict/count separately from the shell exit code;
  it never treats exit 0 alone as a passing test run.
  One run skipped reproduction before editing; a subsequent run reproduced three
  failures before changing the source and passed all three afterward. It recovered
  from a missing `python` by using `python3`. A missing-interpreter attempt initially
  made the evaluator falsely reject that completed workflow; the evaluator now
  separates attempts that did not run tests from runner results and checks ordering
  explicitly. Its regression rejects premature edits, zero tests and stale checks.
- Final unscripted local-model evaluation: **passed in 87.6 seconds, nine tool
  calls**. Shell exits were **127 → 1 → 0**: missing `python`, three reproduced
  failures with `python3`, then three passing tests after the last edit. Only
  `normalizer.py` and `README.md` changed; there were no commits. Independent tests
  and additional tab/newline/empty-input checks passed. This validates one small
  unfamiliar-repository repair, not general coding reliability or a measured
  improvement in model reasoning quality across a benchmark.

Reproduce the deterministic checks with the server venv's Python:

```sh
python -m unittest discover -s desktop/agent/tests
FRIDAY_NATIVE_PC_TEST=1 python -m unittest discover -s desktop/agent/tests
```

`tests/fixtures/local_coding.py` is a separate opt-in evaluation using a running
FRIDAY private local proxy. It accepts `--proxy-config /path/to/pc/opencode.json`,
reads only the local provider options, and tests an unfamiliar synthetic repository
without scripted model decisions. It does not approve unexpected requests or deploy
anything. Run it as `python -m desktop.agent.tests.fixtures.local_coding` with that
argument. Add `--trusted-project` to grant the temporary synthetic project the normal
project trust setting; otherwise unexpected shell commands stop the evaluation at
their permission prompt. Its JSON report separates agent-observed test exits from
runner verdicts and independent artifact checks, and requires reproduction before
the source edit and passing tests after the last native file edit.

## Remaining limits and next priority

Native coding still runs with the user's host permissions; a worktree is not an OS
sandbox. Trusted projects intentionally authorize commands. The new output hooks
do not change those rules. Test commands execute repository code and still need the
existing trust/approval decision. Self-modification/deployment separation in the
coding prompt is guidance, not a new sandbox boundary.

The changes make execution evidence available but do not certify task correctness.
Idle status still means the agent stopped, and a passing command may be irrelevant
or precede a later edit. Shell lists ending in another successful command can still
exit 0 after a failed check; test output must be inspected as well. Compaction
content depends on the summarizing model.
Long ambiguous refactors, many compactions, restart recovery during mutation,
environment provisioning and merge-conflict recovery need broader live evaluation.
File/diff truncation is intentional; line counts for oversized untracked files are
previews. Unusual quoted diff headers can still require reading the file directly.
The initial backend-only pass did not rerun Android/device UX or client builds;
the subsequent shared-client implementation and checks are described below.
The installed server source was not changed or restarted; normal deployment is
still needed to enable these changes for everyday tasks.

The next priority identified in the initial assessment was durable verification receipts tied to the worktree revision:
record exact command, exit, test count, artifact and changed-tree identity; invalidate
successful checks after subsequent edits, and show unverified work clearly. Pair this
with a repeatable local-model evaluation set covering multi-module changes and
context recovery. Avoid introducing another agent framework before that evidence
shows it is needed.

## Follow-up: durable verification records

Implemented in `agent/verification.py` and the existing OpenCode coding hook.
Recognized test/check shell calls capture repository identity before execution and
again afterward. Receipts retain the exact command and working directory, timestamp,
actual shell exit, recognized runner verdict/count, HEAD/index identifiers, private
output and its checksum. Writes use private files, atomic replacement and fsync.
The files live under the existing PC state's `code-verification` directory and
survive helper/API restarts; no new service, database dependency or cloud is needed.

The fingerprint covers Git-visible tracked and nonignored untracked file contents,
file modes, symlink text, HEAD and index entries. The current API view recomputes it;
a same-size edit, new file, deletion, mode change, staging or commit invalidates the
old record. A check that changes the tree while running cannot verify its final tree.
Checks launched with a working directory outside the task are not current evidence.
Hashes do not read through symlinks; unsupported submodules and scan limits fail
closed. Limits: 20,000 files, 256 MiB of file content, and a ten-second file scan
budget plus bounded Git command timeouts. A cold scan of this repository took 4.2 s;
a warm scan took 0.065 s. No cache can silently hide a same-size content edit.

`CodeProjects.view` exposes current/stale/failed/unverified state and recent records.
The shared Android/browser task screen shows a verification label and an inspectable
history with commands, working directories, exits, counts and output excerpts.
Both Android and browser chat-result condensers preserve the structured state and
up to five records without copying saved logs into the chat context. Older servers
without the new field render as **Not verified**, rather than assuming success.

The summary means **recent recorded checks passed**, not “the task is correct.”
It examines the latest 100 records and displays ten; for repeated identical commands
the newest receipt supersedes older ones. Nonzero exits and recognizable failures
win over shell exit zero. Missing/changed saved evidence is unverified. Zero tests,
all-skipped unittest suites, unknown output and truncated success output cannot
produce a passing result. Unittest, common pytest/Jest summaries and Node test
summaries are recognized; unfamiliar build/lint/Gradle summaries retain their exit
and output but remain unconfirmed. Test discovery and selection are still the
model's responsibility until project verification profiles are added.

Limits: ignored dependencies, external fixtures/services, environment variables and
installed runtimes are not fingerprinted. Commands may explicitly change directories
or test unrelated code; evidence does not prove relevance. Before/after snapshots
are sampled, not an OS snapshot or a defense against a process changing and restoring
files during the test. Receipts run as the same host user as the coding process,
so they are not tamper-proof attestations. Output is capped at 2 MiB per receipt;
truncation is recorded. Retention follows the existing private PC data lifecycle.
If receipt storage fails, the hook reports that the attempt was not certified;
there is no durable pending-attempt journal to invalidate an earlier same-tree
receipt after a disk failure. This remains evidence history, not a completion gate.

The native integration exercises real OpenCode → helper subprocess → durable output
→ task API, then changes a file without changing its length and observes **stale**.
Focused regressions cover same-size edits, new files, modes, index/HEAD changes,
deleted files, unsupported file types, size limits, failed/unknown/zero/all-skipped
test results, changed-during-check state, damaged evidence and external working
directories. Shared wire tests cover missing fields, stale records and nullable
counts/exits on JVM and JavaScript; both chat paths test evidence propagation.

Validation of this follow-up:

- Full agent regression: 237 tests run, 232 passed, five optional Docker tests
  skipped. All 35 focused tests passed after the final receipt scope, metadata
  and fixture polling changes. Browser chat integration: 22 tests passed.
- Android/JVM and JavaScript tests passed for core/shared UI, Android app unit
  tests passed, and both the debug APK and staged web gateway built successfully.
  No device interaction or rendered UI inspection was performed.
- The deterministic native OpenCode fixture verified receipt persistence through
  the helper/API path and same-size edit invalidation.
  Final regression exposed a fixture polling race: the watcher could complete
  during a status read, briefly yielding `busy=false` with an older `state=busy`.
  The fixture now waits for the terminal state as well as the busy flag.
- The latest unscripted local-model run completed the two-file fixture repair in
  111.6 seconds and nine tool calls. It produced a current passing receipt for
  three tests; independent tests and edge cases also passed. However, its initial
  commands exited 127 and 120 without reproducing the intended failing baseline.
  The strict end-to-end evaluation therefore **failed**, despite the correct final
  repair. An earlier attempt ended in an error before any tool calls. These results
  demonstrate real-model use of the new records, not reliable baseline diagnosis
  or broad autonomous coding competence. The earlier successful baseline run is
  historical evidence, not a substitute for this follow-up result.

The broader comparison and prioritized remaining gaps are in
[FRIDAY-FEATURE-AUDIT.md](FRIDAY-FEATURE-AUDIT.md). Deployment is still separate from
these source changes; no live installation files or services were changed.
