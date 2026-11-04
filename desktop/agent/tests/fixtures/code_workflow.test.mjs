import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, stat, mkdir, writeFile, symlink } from "node:fs/promises";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { FridayCodeWorkflow } from "../../code_workflow.mjs";

test("custom reviewed commands record evidence through a symlinked workspace parent", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "friday-profile-hook-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const repo = path.join(root, "repo");
  await mkdir(repo);
  execFileSync("git", ["init", "-q", "-b", "main"], { cwd: repo });
  execFileSync("git", ["-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "Initial", "--allow-empty"], { cwd: repo });
  const alias = path.join(root, "alias");
  await symlink(root, alias);
  const worktree = path.join(alias, "repo");
  const directory = path.join(root, "evidence");
  await mkdir(path.join(directory, "profiles"), { recursive: true });
  const key = createHash("sha256").update(repo).digest("hex");
  await writeFile(path.join(directory, "profiles", key + ".json"), JSON.stringify({ revision: "reviewed-profile",
    checks: [{ name: "Version", command: "node --version", cwd: ".", runner: "exit", min_tests: 1 }] }));
  const hooks = await FridayCodeWorkflow({ client: {}, worktree }, {
    verificationPython: process.env.FRIDAY_TEST_PYTHON || "python3",
    verificationScript: fileURLToPath(new URL("../../verification.py", import.meta.url)), verificationDirectory: directory,
  });
  await hooks["chat.params"]({ agent: "friday-code", sessionID: "code" });
  const input = { tool: "bash", sessionID: "code", callID: "check" };
  const before = { args: { command: "node --version 2>&1", workdir: worktree } };
  await hooks["tool.execute.before"](input, before);
  const output = { output: "v24.0.0", metadata: { exit: 0 } };
  await hooks["tool.execute.after"]({ ...input, args: before.args }, output);
  assert.match(output.output, /saved verification/);
  assert.match(output.output, /Version: missing/);
  assert.match(output.output, /exact command=node --version/);
  const folder = path.join(directory, createHash("sha256").update("code").digest("hex"));
  const receipt = JSON.parse(await readFile(path.join(folder, (await readdir(folder)).find((name) => name.endsWith(".json"))), "utf8"));
  assert.equal(receipt.profile_revision, "reviewed-profile");
  assert.equal(receipt.cwd, repo);
  assert.equal(receipt.stable, true);
});

async function fixture(t, messages = []) {
  const root = await mkdtemp(path.join(os.tmpdir(), "friday-code-hooks-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const hooks = await FridayCodeWorkflow({ client: { session: {
    messages: async () => ({ data: messages }),
  } } }, { outputDirectory: path.join(root, "output") });
  await hooks["chat.params"]({ agent: "friday-code", sessionID: "code" });
  return { root, hooks };
}

test("default reads are bounded; explicit ranges and other agents are preserved", async (t) => {
  const { hooks } = await fixture(t);
  const value = { args: { filePath: "src/app.py" } };
  await hooks["tool.execute.before"]({ tool: "read", sessionID: "code" }, value);
  assert.equal(value.args.limit, 250);
  value.args.limit = 700;
  await hooks["tool.execute.before"]({ tool: "read", sessionID: "code" }, value);
  assert.equal(value.args.limit, 700);
  const ordinary = { args: {} };
  await hooks["tool.execute.before"]({ tool: "read", sessionID: "chat" }, ordinary);
  assert.deepEqual(ordinary, { args: {} });
});

test("nonzero and unknown exits cannot look like silent success", async (t) => {
  const { hooks } = await fixture(t);
  for (const exit of [0, 1, 137, null, undefined]) {
    const value = { output: "", metadata: { exit } };
    await hooks["tool.execute.after"]({ tool: "bash", sessionID: "code" }, value);
    assert.match(value.output, Number.isInteger(exit) ? new RegExp(`exit code: ${exit}`) : /unavailable/);
    if (exit === 1) assert.match(value.output, /failed/);
  }
  const ordinary = { output: "", metadata: { exit: 1 } };
  await hooks["tool.execute.after"]({ tool: "bash", sessionID: "chat" }, ordinary);
  assert.equal(ordinary.output, "");
});

test("coding shells enable pipefail once, without rewriting other agents", async (t) => {
  const { hooks } = await fixture(t);
  const value = { args: { command: "python3 -m unittest 2>&1 | tail -20" } };
  const input = { tool: "bash", sessionID: "code" };
  await hooks["tool.execute.before"](input, value);
  const command = value.args.command;
  assert.equal(command, "set -o pipefail; python3 -m unittest 2>&1 | tail -20");
  await hooks["tool.execute.before"](input, value);
  assert.equal(value.args.command, command);
  const ordinary = { args: { command: "false | cat" } };
  await hooks["tool.execute.before"]({ ...input, sessionID: "chat" }, ordinary);
  assert.equal(ordinary.args.command, "false | cat");
});

test("source edits get one baseline reminder; documentation and other agents do not", async (t) => {
  const { hooks } = await fixture(t);
  const input = { tool: "edit", sessionID: "code" };
  await hooks["tool.execute.before"](input, { args: { filePath: "README.md" } });
  await hooks["tool.execute.before"]({ ...input, sessionID: "chat" }, { args: { filePath: "app.py" } });
  const source = { args: { filePath: "src/app.py", oldString: "old", newString: "new" } };
  await assert.rejects(hooks["tool.execute.before"](input, source), /This edit has not run/);
  assert.equal(source.args.newString, "new");
  // Exceptions are possible without granting any new command permissions.
  await hooks["tool.execute.before"](input, source);
  await hooks["tool.execute.before"]({ ...input, tool: "write" }, { args: { filePath: "src/other.ts" } });
});

test("common check attempts avoid the reminder, including failed baselines", async (t) => {
  const { hooks } = await fixture(t);
  const commands = [
    "set -o pipefail; python3 -m unittest discover -s tests -v 2>&1 | tail -30",
    "cd src && /usr/bin/python3.12 -m pytest -q", "pytest -q",
    "npm test", "pnpm run check", "yarn test", "bun run lint", "cargo test",
    "go test ./...", "dotnet test", "./gradlew :core:testDebugUnitTest",
    "make check", "mvn verify",
  ];
  for (const command of commands) {
    await hooks["chat.message"]({ agent: "friday-code", sessionID: "code" });
    await hooks["tool.execute.after"]({ tool: "bash", sessionID: "code", args: { command } },
      { output: "FAILED", metadata: { exit: 1 } });
    await assert.doesNotReject(hooks["tool.execute.before"]({ tool: "edit", sessionID: "code" },
      { args: { filePath: "app.py" } }), command);
  }
});

test("human followups reset the reminder; synthetic continuations preserve it", async (t) => {
  const { hooks } = await fixture(t);
  const input = { tool: "write", sessionID: "code" };
  const args = { args: { filePath: "src/app.kt" } };
  // Exploration is not a baseline check.
  await hooks["tool.execute.after"]({ tool: "bash", sessionID: "code", args: { command: "git status" } },
    { output: "", metadata: { exit: 0 } });
  await assert.rejects(hooks["tool.execute.before"](input, args), /baseline check/);
  await hooks["chat.message"]({ agent: "friday-code", sessionID: "code" },
    { parts: [{ type: "text", synthetic: true }] });
  await hooks["tool.execute.before"](input, args);
  await hooks["chat.message"]({ agent: "friday-code", sessionID: "code" },
    { parts: [{ type: "text" }] });
  await assert.rejects(hooks["tool.execute.before"](input, args), /baseline check/);
});

test("large outputs preserve full evidence and both ends without changing exit metadata", async (t) => {
  const { hooks } = await fixture(t);
  const original = "FIRST FAILURE\n" + "detail\n".repeat(8000) + "FINAL SUMMARY";
  const value = { output: original, metadata: { exit: 1 } };
  await hooks["tool.execute.after"]({ tool: "bash", sessionID: "code", callID: "../../unsafe" }, value);
  assert.equal(await readFile(value.metadata.outputPath, "utf8"), original);
  assert.equal((await stat(value.metadata.outputPath)).mode & 0o777, 0o600);
  assert.equal(value.metadata.exit, 1);
  assert.ok(value.metadata.truncated);
  assert.ok(value.output.length < 16000);
  assert.match(value.output, /FIRST FAILURE/);
  assert.match(value.output, /FINAL SUMMARY/);
  assert.match(value.output, /exit code: 1/);
});

test("existing OpenCode artifacts are reused", async (t) => {
  const { root, hooks } = await fixture(t);
  const value = { output: "x".repeat(20000), metadata: { outputPath: "/existing/output", truncated: true } };
  await hooks["tool.execute.after"]({ tool: "read", sessionID: "code" }, value);
  assert.match(value.output, /\/existing\/output/);
  assert.deepEqual(await readdir(root), []);
});

test("failed artifact storage retains the original output", async () => {
  const hooks = await FridayCodeWorkflow({ client: {} }, { outputDirectory: "/dev/null/impossible" });
  await hooks["chat.message"]({ agent: "friday-code", sessionID: "code" });
  const original = "evidence".repeat(3000);
  const value = { output: original, metadata: { exit: 1 } };
  await hooks["tool.execute.after"]({ tool: "bash", sessionID: "code" }, value);
  assert.ok(value.output.startsWith(original));
  assert.match(value.output, /exit code: 1/);
  assert.equal(value.metadata.truncated, undefined);
});

test("compaction retains verification and constraints, including after restart", async (t) => {
  const { hooks } = await fixture(t, [{ info: { role: "user", agent: "friday-code" } }]);
  const output = { context: ["existing context"] };
  await hooks["experimental.session.compacting"]({ sessionID: "restarted" }, output);
  assert.equal(output.context[0], "existing context");
  assert.match(output.context[1], /observed exit codes/);
  assert.match(output.context[1], /denied actions/);
  assert.match(output.context[1], /next concrete step/);
});

test("noncoding compaction is unchanged", async (t) => {
  const { hooks } = await fixture(t, [{ info: { role: "user", agent: "friday" } }]);
  const output = { context: [] };
  await hooks["experimental.session.compacting"]({ sessionID: "chat" }, output);
  assert.deepEqual(output, { context: [] });
});
