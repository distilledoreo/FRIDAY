"""Shorter, cache-stable prompts for the local model.

OpenCode's built-in tool descriptions are about 90% of the fixed prefix the local model reads at the start
of every task. Each known description is replaced by a telegraphic rewrite that keeps every rule, and the
per-directory environment block moves from the system prompt to the first user message, so the system prompt
and tool definitions stay identical across tasks and can be reused from the model's prompt cache.

A description is only replaced when it matches the OpenCode text it was written from (by digest). After an
OpenCode update changes a description, that tool keeps OpenCode's own text until this file is refreshed.
"""
import copy
import hashlib
import os
import re

AGENTS_MARK = 'Available agent types and the tools they have access to:'
ENV_BLOCK = re.compile(r'Here is some useful information about the environment you are running in:\n<env>\n.*?\n</env>\n?', re.S)
UNBOUNDED = (9007199254740991, -9007199254740991)

# sha256 of the OpenCode 1.18.35 description each rewrite was made from (for task: the part before AGENTS_MARK).
ORIGINALS = {
    'bash': '1f835ce857c63c521b20709e0eba7828b877878fbfdbad02f4cd99be47313d25',
    'edit': '4426ccf60241fe41d01bbafc1e7450ea6538003f9fca863ab0210492a74647f8',
    'glob': '50b2d2c41d4b8d0286ab4542c6ec882421ac4ae5c0567ad213c3668ed973ed9a',
    'grep': '97fa2a9929353d20d3418041aae53ffea3aaf63e9a6e2fdc8cff6db61c3f4c5e',
    'question': 'c0a5776acd585b62292c16839a5486b8b69e176b2d7da0cf206c82fcbd929584',
    'read': '98ee843341c2dab2227add0019e48d4b2f0f00f9b042b853d1ee52bb34e6363d',
    'skill': '226f63ce9fd51e51431205c90ef98e53a20ba5af690fdad241eba416db49c339',
    'task': 'ba23a274627bef270a042d1b087f155c4b98884c3b28e10ce3baef26c8f35eaf',
    'todowrite': 'f214ea20cd870a9837cb30dd993aefbe5abe6d9e3319b47672c529961ba0c3ad',
    'webfetch': '0da3ec7c3f6bc47706553c1c522dacb2042d66a4c2bf09368e43ce4a72f3b7dc',
    'write': '8b7197b6e3a8ec1d129eeb6b82608e4cab759bfcc60ba890ecf36322a6e45180',
}

SLIM = {
    'bash': '''Run bash command in persistent shell. OS linux, shell bash.
- Runs in current working dir. Other dir: use `workdir` param. NO `cd <dir> && <cmd>`.
- Temp work outside workspace: `/tmp/opencode` (exists, pre-approved).
- For terminal ops (git, npm, docker). NOT file ops (read, write, edit, search, find): use dedicated tools.
- Command makes new dir/file: first `ls` parent to check it exists and is right place. E.g. before `mkdir foo/bar`, `ls foo`.
- Paths with spaces: double quotes. `rm "path with spaces/file.txt"` good; `python /path/with spaces/script.py` bad, fails.
- `command` required. Optional `timeout` ms; default 120000.
- Output over 2000 lines or 51200 bytes: truncated, full output saved to file; use Read with offset/limit or Grep on it. NO `head`/`tail` to limit output.
- Avoid `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, `echo` unless told or truly needed. Use: Glob (not find/ls), Grep (not grep/rg), Read (not cat/head/tail), Edit (not sed/awk), Write (not echo >/cat <<EOF). Talk to user: plain text, not echo/printf.
- Independent commands: parallel bash calls in one message (e.g. `git status` and `git diff`).
- Dependent commands: one call chained with `&&` (e.g. `git add . && git commit -m "message" && git push`; mkdir before cp; Write before git; add before commit).
- `;` only when earlier failure does not matter.
- NO newlines between commands (ok inside quoted strings).
Good: workdir="/foo/bar", command: pytest tests. Bad: cd /foo/bar && pytest tests.

Git and GitHub:
- Commit, amend, push, PR only when asked.
- Before commit: check `git status`, `git diff`, `git log --oneline -10`; stage only intended files; never commit secrets.
- Short commit message, repo style.
- No git config change, hook skip, interactive `-i`, force-push, empty commit unless asked.
- Commit fails or hook rejects: fix, make new commit; do not amend failed one.
- Before PR: check status, diff, remote tracking, recent commits, diff from base branch.
- Review all commits in PR, not only latest.
- Use `gh` for GitHub (PRs, issues, checks, releases); return PR URL when done.''',
    'edit': '''Exact string replace in file.
- Must Read file at least once in conversation first, else error.
- Text from Read output: keep exact indentation (tabs/spaces) as after line-number prefix. Prefix is number + colon + space (e.g. `1: `); all after is file content. Never put prefix in oldString or newString.
- Prefer editing existing files. No new files unless required.
- Emojis only if user asks.
- FAILS if `oldString` not found: "oldString not found in content".
- FAILS if `oldString` found many times: "Found multiple matches for oldString". Fix: more surrounding context to make unique, or `replaceAll` for every instance.
- `replaceAll`: replace/rename string across file, e.g. rename variable.''',
    'glob': '''Fast file pattern match, any codebase size.
- Glob patterns like "**/*.js", "src/**/*.ts". Returns matching paths.
- Use to find files by name pattern.
- Open-ended search needing many glob/grep rounds: use Task tool.
- Can call many tools in one response; batch speculative searches.''',
    'grep': '''Fast content search, any codebase size.
- Regex over file contents, full syntax (e.g. "log.*Error", "function\\s+\\w+").
- `include` filters files (e.g. "*.js", "*.{ts,tsx}").
- Returns file paths, line numbers, matching lines.
- Use to find files containing pattern.
- To count matches in files: Bash with `rg`. NOT `grep`.
- Open-ended search needing many glob/grep rounds: use Task tool.''',
    'question': '''Ask user questions during work: gather preferences/requirements, clarify ambiguous instructions, get implementation decisions, offer direction choices.
- `custom` on (default): "Type your own answer" added automatically; no "Other"/catch-all option.
- Answers come back as arrays of labels; `multiple: true` allows more than one.
- Recommended option: put first, add "(Recommended)" to label end.''',
    'read': '''Read file or dir. Path missing = error.
- filePath absolute.
- Default: first 2000 lines. offset = start line, 1-indexed. Later part: call again, bigger offset.
- Find content in big file or long lines: grep. Unsure path: glob.
- Output `<line>: <content>`, e.g. "foo\\n" -> "1: foo\\n". Dir: one entry per line, no numbers, subdir ends `/`.
- Line over 2000 chars cut.
- Many files: call parallel.
- No tiny repeat slices (30 lines); read bigger window.
- Reads images, PDFs as attachments.''',
    'skill': '''Load skill when task matches one listed in system prompt. Injects skill instructions and resources into conversation; output may hold workflow guidance and references to scripts/files in skill dir. Name must match a listed skill.''',
    'task': '''Launch new agent for complex multistep task, autonomous. Must set `subagent_type`.

Do NOT use when:
- Reading known file path: use Read or Glob, faster.
- Searching specific class like "class Foo": use Grep, faster.
- Searching code in one file or 2-3 files: use Read, faster.
- No agent fits: use other tools directly.

Notes:
1. Launch many agents at once when possible: one message, many tool uses.
2. After delegating, do not redo that work. Do non-overlapping work or wait. Background tasks notify when ready.
3. Agent returns one message, not visible to user; send user short summary. Output has task_id to continue same subagent session later.
4. Each call starts fresh context unless task_id given (resumes with its earlier messages and tool outputs). Fresh: prompt needs highly detailed task and exactly what to return in its final, only message.
5. Agent output generally trusted.
6. Say clearly: write code or research only (search, reads, web fetches); it does not know user intent. Say how to verify (e.g. test commands).
7. Agent description says use proactively: do so without user asking. Use judgement.

''',
    'todowrite': '''Keep structured task list for coding session. Tracks progress, organizes multi-step work, shows status to user.

Use proactively when:
- Task needs 3+ distinct steps (not 3 tool calls for one step).
- Work non-trivial, benefits from planning.
- User gives many tasks (numbered, comma-separated) or asks for todo list.
- New instructions arrive: capture as todos.
- Starting task: mark `in_progress` (one at a time) before working.
- Finishing task: mark `completed`, add follow-ups found.

Skip when: single simple task (or <3 trivial steps); purely informational or chat; tracking adds no value.

States: `pending` not started; `in_progress` working (exactly ONE); `completed` done; `cancelled` not needed.

Rules:
- Update status live; no batching completions.
- `completed` only after work truly done, verification included. Never on intent.
- Exactly one `in_progress` while work remains.
- Blocked or partial: keep `in_progress`, add follow-up todo for blocker.
- Keep user commands verbatim (flags, args, order).
- Items specific, actionable; split big work.

Use: "Add a dark mode toggle and run the tests" (multi-step + verification); "Rename getCwd -> getCurrentWorkingDirectory across the repo" (15 occurrences, 8 files); "Implement registration, catalog, cart, checkout".
Skip: "How do I print Hello World in Python?"; "Add a comment to calculateTotal"; "Run npm install and tell me what happened".
In doubt: use it.''',
    'webfetch': '''Fetch URL content, convert to requested format, return it. Use to retrieve and analyze web content.
- Another tool with better web fetching, more targeted, or fewer limits: prefer it.
- URL must be full and valid. HTTP upgraded to HTTPS.
- Format: "markdown" (default), "text", "html".
- Read-only, changes no files.
- Very large content may be summarized.''',
    'write': '''Write file to local filesystem.
- Overwrites existing file at path.
- Existing file: MUST Read it first, else fails.
- Prefer editing existing files. No new files unless required.
- No proactive docs (*.md) or README; only if user asks.
- Emojis only if user asks.''',
}


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def _description(name, original):
    """The rewrite for a known OpenCode description, otherwise the original."""
    if name not in SLIM or not isinstance(original, str): return original
    if name == 'task':
        # The list of agents depends on FRIDAY's configuration, so it stays as OpenCode wrote it.
        head, mark, agents = original.partition(AGENTS_MARK)
        return SLIM[name] + mark + agents if mark and digest(head) == ORIGINALS.get(name) else original
    return SLIM[name] if digest(original) == ORIGINALS.get(name) else original


def _schema(value):
    """Drop JSON Schema boilerplate that tells the model nothing: the dialect and no-op integer bounds."""
    if isinstance(value, list): return [_schema(item) for item in value]
    if not isinstance(value, dict): return value
    return {key: _schema(item) for key, item in value.items()
            if key != '$schema' and not (key in ('minimum', 'maximum') and item in UNBOUNDED)}


def _move_environment(messages):
    """Move OpenCode's environment block (working directory, date) to the first user message."""
    system = next((m for m in messages if m.get('role') == 'system' and isinstance(m.get('content'), str)), None)
    user = next((m for m in messages if m.get('role') == 'user'), None)
    if system is None or user is None: return
    found = ENV_BLOCK.search(system['content'])
    if not found: return
    block = found.group(0).rstrip('\n')
    if isinstance(user.get('content'), str): user['content'] = block + '\n\n' + user['content']
    elif isinstance(user.get('content'), list): user['content'] = [{'type': 'text', 'text': block}] + user['content']
    else: return
    system['content'] = system['content'][:found.start()] + system['content'][found.end():]


def slim(body):
    """A copy of an OpenAI chat request with shorter tool definitions and a task-independent system prompt."""
    if os.environ.get('FRIDAY_LOCAL_PROMPT_SLIM', '1') == '0': return body
    body = copy.deepcopy(body)
    for tool in body.get('tools') or []:
        function = tool.get('function') if isinstance(tool, dict) else None
        if not isinstance(function, dict): continue
        function['description'] = _description(function.get('name'), function.get('description'))
        if 'parameters' in function: function['parameters'] = _schema(function['parameters'])
    if isinstance(body.get('messages'), list): _move_environment(body['messages'])
    return body
