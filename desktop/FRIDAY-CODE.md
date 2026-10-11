# FRIDAY Code

Code is FRIDAY's coding workspace, in the spirit of Codex cloud tasks, Claude Code on the web and Cursor's
background agents: pick a GitHub repository, describe a task, and a coding agent works on it in its own
branch while you follow along from the phone, review the diff and open a pull request.

The "cloud" is the user's own PC. Repositories are cloned there, and FRIDAY's OpenCode runs the agent with the
user's tools, sign-ins and permission settings.

The main chat stays as it is. Code is its own screen, reached from the drawer (like Codex inside the ChatGPT app);
chat only gains a card when the user asks it to start a coding task.

## On the phone

- **Drawer › Code** opens the workspace over the chat. Back returns to the conversation untouched.
- **Home**: a composer ("Describe a coding task") with a repository chip and a base-branch chip, then every task,
  newest first, with its state (Working, Needs you, Ready to review, Pull request…) and `+added −removed`.
- **Repositories**: your GitHub repositories (from `gh repo list`) with search, or any `owner/name` typed in.
  Per project: *Let commands run freely* (trusted) or *Ask before commands*, and *Remove*.
- **Task › Chat**: the request, the agent's replies (Markdown), its steps grouped into one block (each opens to
  its output), and permission and question cards to answer in place. A composer sends follow-ups; *Stop* ends a
  turn. When the agent is done and there are changes, *Create pull request* appears; afterwards a strip shows
  the PR's state, check results and review decision.
- **Task › Changes**: files changed against the base (committed, uncommitted and new files together), each
  opening to a colored unified diff with line numbers.
- **Menu**: Push branch, Open pull request, Discard task.
- **Notifications**: a coding task that finishes or waits for approval notifies like a PC task, and opens Code.
- **From chat**: `start_code_task {repo, prompt, base?}` (confirmed by the user) adds the repository if needed
  and starts a task; the result is a card with live state that opens the task. `get_code_task` reads a task's
  summary, files and PR into the conversation; `list_code_projects` lists repositories and recent tasks.
  None of them work in incognito.

## On the PC (`desktop/agent/code.py`)

| Route (`/workspace/code`) | What it does |
| --- | --- |
| `GET /repos` | The user's GitHub repositories (`gh repo list`, cached 5 minutes). |
| `GET/POST /projects` | Projects, or add one: `gh repo clone owner/name` into `$FRIDAY_CODE_ROOT/owner/name` (default `~/FRIDAY-code`). Adding twice reuses the clone. |
| `PUT /projects/{id}/trust`, `DELETE /projects/{id}` | Trust commands; forget a project (its clone stays). |
| `GET /projects/{id}/branches` | Remote branches, default first. |
| `GET/POST /tasks` | Tasks, or start one: `git fetch`, then `git worktree add -b friday/<slug>-<id> <root>/.worktrees/owner__name/<id> origin/<base>`, then an OpenCode session **in that directory** with the `friday-code` agent. |
| `GET /tasks/{id}` | Task, the agent session (transcript, approvals, questions), change stats and PR status. |
| `GET /tasks/{id}/diff`, `GET /tasks/{id}/file?path=` | Full per-file patches against the base commit (bounded); one file, confined to the worktree. |
| `POST /tasks/{id}/messages`, `/abort` | Follow-up; stop the turn. |
| `POST /tasks/{id}/push`, `/pr` | Commit leftovers, push the branch; then `gh pr create` (title, draft, the agent's summary as the body). |
| `DELETE /tasks/{id}` | Stop, remove the worktree, delete the local branch. Pushed branches and PRs stay on GitHub. |

### How it uses the PC agent

`PcAgent` (pc.py) learned two things for this:

- **Directory-scoped sessions.** OpenCode serves each project directory as its own instance; every request takes
  `?directory=`. Sessions record their directory and agent, and every call for a session (prompt, status,
  messages, abort, fallback, permission replies) goes to its instance. Pending requests are gathered from home
  plus the directories of working sessions only, since querying a directory starts an instance for it.
- **Pluggable agents.** `extra_agents` adds OpenCode agent definitions to FRIDAY's config, and `rule_builders`
  gives each agent its own permission rules, rebuilt before every follow-up.

Repositories are untrusted input: OpenCode runs with `OPENCODE_DISABLE_PROJECT_CONFIG`, so a repository's own
`opencode.json`, agents and `.opencode` plugins never load. The agent prompt treats repository content, issues
and logs as data.

### Permissions (`code_ruleset`)

| | Ask every time | Ask before changes (default) | Full access / trusted project |
| --- | --- | --- | --- |
| Reads, search | ask | allow (secret files ask) | allow |
| Edits in the worktree | ask | **allow** | allow |
| Read-only and local git commands (`git status/diff/log/add/commit/switch -c`…) | ask | allow | allow |
| Other commands (tests, installs, builds) | ask | ask | allow |
| `git push`, `gh pr create/merge`, `git remote`, `gh repo` | ask | ask | **ask** |
| Catastrophic commands | ask | ask | ask |
| User deny rules | deny | deny | deny |
| Desktop control | deny | deny | deny |

The user's own allow rules apply too, but never to pushes. Pushing and pull requests are the phone's buttons:
the agent commits, FRIDAY pushes.

## Parity notes

| Codex / Claude Code / Cursor | FRIDAY Code |
| --- | --- |
| Environment = repo + branch, task composer | Repository and base-branch chips on the composer |
| Each task in an isolated container/worktree on its own branch | Git worktree per task on `friday/<slug>-<id>` |
| Live log of the agent's steps; follow-ups in the same task | Chat tab with grouped steps; follow-ups continue the session |
| Approvals for risky commands | Same cards as PC tasks, answered in place; notifications |
| Diff review | Changes tab, per-file unified diffs |
| Create PR / push | Create pull request (draft optional), Push branch; PR state and checks in the task |
| Several tasks at once | Up to four working sessions across FRIDAY |
| Start from chat (ChatGPT → Codex) | `start_code_task` card in the main chat |
