import fnmatch
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI, HTTPException
import httpx

from desktop.agent.code import CODE_AGENT, PLAN_AGENT, MAX_DIFF, MAX_FILE, MAX_PATCH, CodeProjects, PullRequest, StartTask, VerificationProfile, code_ruleset, plan_ruleset, routes, slug
from desktop.agent.pc import DEFAULTS, Reply
from desktop.agent.tests.test_pc import FakePc

IDENTITY = {'GIT_AUTHOR_NAME': 'Test', 'GIT_AUTHOR_EMAIL': 'test@example.invalid',
            'GIT_COMMITTER_NAME': 'Test', 'GIT_COMMITTER_EMAIL': 'test@example.invalid'}

# A stand-in for gh: clones come from a local bare repository and PRs are recorded in a log.
FAKE_GH = '''#!/bin/sh
echo "$@" >> "$GH_LOG"
case "$1 $2" in
  "api user") echo '{"login":"octo","id":123,"email":null}' ;;
  "repo list") echo '[{"nameWithOwner":"octo/demo","description":"Demo","isPrivate":false,"updatedAt":"2026-10-01T00:00:00Z","defaultBranchRef":{"name":"main"}}]' ;;
  "repo clone") git clone --quiet "$FAKE_ORIGIN" "$4" ;;
  "pr create")
    prior=""
    for arg in "$@"; do
      if [ "$prior" = "--body-file" ]; then cat "$arg" >> "$GH_LOG"; fi
      prior="$arg"
    done
    echo "https://github.com/octo/demo/pull/7" ;;
  "pr view") echo '{"state":"OPEN","isDraft":false,"mergedAt":null,"url":"https://github.com/octo/demo/pull/7","reviewDecision":"","statusCheckRollup":[{"conclusion":"SUCCESS"},{"conclusion":"FAILURE"},{"state":"PENDING"}]}' ;;
  *) echo "unexpected gh $*" >&2; exit 1 ;;
esac
'''


def git(*args, cwd):
    return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True, text=True, env=dict(os.environ, **IDENTITY)).stdout


def decision(rules, permission, value):
    result = 'ask'
    for rule in rules:
        if fnmatch.fnmatchcase(permission, rule['permission']) and fnmatch.fnmatchcase(value, rule['pattern']):
            result = rule['action']
    return result


class CodeRulesTest(unittest.TestCase):
    def test_edits_run_and_pushes_always_ask(self):
        rules = code_ruleset(dict(DEFAULTS), trusted=False)
        self.assertEqual(decision(rules, 'edit', 'src/app.py'), 'allow')
        self.assertEqual(decision(rules, 'read', 'src/app.py'), 'allow')
        self.assertEqual(decision(rules, 'read', 'config/.env'), 'ask')
        self.assertEqual(decision(rules, 'bash', 'git status'), 'allow')
        self.assertEqual(decision(rules, 'bash', 'set -o pipefail'), 'allow')
        self.assertEqual(decision(rules, 'bash', 'git commit -m "Fix"'), 'allow')
        self.assertEqual(decision(rules, 'bash', 'npm install'), 'ask')
        self.assertEqual(decision(rules, 'bash', 'git push origin HEAD'), 'ask')
        self.assertEqual(decision(rules, 'friday-computer_action', '*'), 'deny')
        trusted = code_ruleset(dict(DEFAULTS), trusted=True)
        self.assertEqual(decision(trusted, 'bash', 'npm install'), 'allow')
        self.assertEqual(decision(trusted, 'bash', 'git push origin HEAD'), 'ask')
        self.assertEqual(decision(trusted, 'bash', 'gh pr create --fill'), 'ask')
        self.assertEqual(decision(trusted, 'bash', 'rm -rf /'), 'ask')

    def test_user_allow_rules_never_cover_pushes(self):
        rules = code_ruleset({**DEFAULTS, 'allow': ['npm test*', 'git *', 'computer']}, trusted=False)
        self.assertEqual(decision(rules, 'bash', 'npm test -- --watch=false'), 'allow')
        self.assertEqual(decision(rules, 'bash', 'git rebase main'), 'allow')
        self.assertEqual(decision(rules, 'bash', 'git push --force'), 'ask')
        self.assertEqual(decision(rules, 'friday-computer_action', '*'), 'deny')

    def test_ask_mode_and_deny_rules(self):
        rules = code_ruleset({**DEFAULTS, 'mode': 'ask', 'deny': ['curl *']}, trusted=False)
        self.assertEqual(decision(rules, 'edit', 'a.py'), 'ask')
        self.assertEqual(decision(rules, 'bash', 'git status'), 'ask')
        self.assertEqual(decision(code_ruleset({**DEFAULTS, 'deny': ['curl *']}, trusted=True), 'bash', 'curl https://example.com'), 'deny')

    def test_slug(self):
        self.assertEqual(slug('Fix the login bug, please!'), 'fix-the-login-bug-please')
        self.assertEqual(slug('!!!'), 'task')

    def test_planning_denies_all_write_shell_delegate_and_mcp_routes(self):
        rules = plan_ruleset(dict(DEFAULTS))
        for permission in ('bash', 'edit', 'write', 'task', 'skill', 'external_directory', 'mcp_external_tool'):
            self.assertEqual(decision(rules, permission, '*'), 'deny')
        self.assertEqual(decision(rules, 'read', 'app.py'), 'allow')
        self.assertEqual(decision(rules, 'read', '.env'), 'ask')


class CodeProjectsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        patcher = patch.dict(os.environ, {**IDENTITY, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull})
        patcher.start()
        self.addCleanup(patcher.stop)
        # origin: a bare repository with one commit on main.
        seed = root / 'seed'
        seed.mkdir()
        git('init', '--quiet', '-b', 'main', cwd=seed)
        (seed / 'README.md').write_text('# Demo\n')
        (seed / 'app.py').write_text('print("hello")\n')
        git('add', '-A', cwd=seed)
        git('commit', '--quiet', '-m', 'Initial', cwd=seed)
        self.origin = root / 'origin.git'
        git('clone', '--quiet', '--bare', str(seed), str(self.origin), cwd=root)
        gh = root / 'gh'
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.log = root / 'gh.log'
        env = patch.dict(os.environ, {'FAKE_ORIGIN': str(self.origin), 'GH_LOG': str(self.log)})
        env.start()
        self.addCleanup(env.stop)
        self.pc = FakePc(root / 'state')
        self.started = []
        original = self.pc.start
        async def start(body, directory=None, agent='friday'):
            self.started.append((body, directory, agent))
            return await original(body, directory=directory, agent=agent)
        self.pc.start = start
        self.code = CodeProjects(root / 'state', self.pc, workspace=root / 'work', gh=str(gh))

    async def asyncTearDown(self):
        for watcher in list(self.pc.watchers.values()): watcher.cancel()

    def idle(self, task):
        with self.pc.db() as db: db.execute("UPDATE sessions SET state='idle' WHERE id=?", (self.code.task(task['id'])['session'],))

    async def task(self):
        project = await self.code.add('octo/demo')
        return project, await self.code.start(StartTask(project=project['id'], prompt='Add a greeting option\nMore detail.'))

    async def test_registers_the_coding_agent(self):
        self.assertIn(CODE_AGENT, self.pc.extra_agents)
        self.assertIn('worktree', self.pc.extra_agents[CODE_AGENT]['prompt'])
        self.assertNotIn('steps', self.pc.config([('friday-local', 'qwen3.8-27b')])['agent'][CODE_AGENT])
        self.assertIn(CODE_AGENT, self.pc.rule_builders)

    async def test_local_project_has_no_network_or_github_dependency_and_preserves_dirty_source(self):
        source = self.origin.parent / 'seed'
        (source / 'app.py').write_text('uncommitted source edit\n')
        project = await self.code.add_local(str(source))
        self.assertEqual(project['kind'], 'local')
        self.assertEqual(project['trusted'], 0)
        self.assertEqual((await self.code.add_local(str(source)))['id'], project['id'])
        self.assertIn('main', (await self.code.branches(project['id']))['branches'])
        task = await self.code.start(StartTask(project=project['id'], prompt='Work offline'))
        directory = self.code.task(task['id'])['worktree']
        self.assertEqual(Path(directory, 'app.py').read_text(), 'print("hello")\n')
        self.assertEqual((source / 'app.py').read_text(), 'uncommitted source edit\n')
        self.assertFalse(self.log.exists())
        with self.assertRaises(HTTPException): await self.code.push(task['id'])
        with self.assertRaises(HTTPException): await self.code.pull_request(task['id'], PullRequest())
        self.assertFalse(self.log.exists())
        with self.assertRaises(HTTPException): await self.code.add_local(str(source / 'missing'))
        with self.assertRaises(HTTPException): await self.code.add_local('relative/path')

    async def test_required_check_settings_are_persistent_scoped_and_reach_the_agent(self):
        project = await self.code.add('octo/demo')
        body = VerificationProfile(checks=[dict(name='Unit', command='python3 -m unittest', min_tests=3)])
        self.code.set_checks(project['id'], body)
        task = await self.code.start(StartTask(project=project['id'], prompt='Fix code'))
        self.assertIn('Reviewed required checks', self.started[-1][0].prompt)
        self.assertIn('at least 3 passing tests', self.started[-1][0].prompt)
        from desktop.agent.verification import profile_path
        saved = profile_path(self.pc.root / 'code-verification', self.code.task(task['id'])['worktree'])
        self.assertEqual(json.loads(saved.read_text())['checks'][0]['name'], 'Unit')
        view = await self.code.view(task['id'])
        self.assertEqual(view['verification']['required'][0]['state'], 'missing')
        self.assertEqual(self.code.projects()[0]['checks'][0]['min_tests'], 3)
        for cwd in ('../escape', '/outside'):
            with self.assertRaises(HTTPException):
                self.code.set_checks(project['id'], VerificationProfile(checks=[dict(name='Bad', command='test', cwd=cwd)]))
        self.code.set_checks(project['id'], VerificationProfile())
        self.assertEqual(json.loads(saved.read_text())['checks'], [])

    async def test_recovery_requires_idle_and_restores_without_discarding_task(self):
        _, task = await self.task()
        with self.assertRaises(HTTPException): await self.code.checkpoint(task['id'], 'Busy')
        self.idle(task)
        view = await self.code.view(task['id'])
        first = view['checkpoints'][0]
        directory = Path(self.code.task(task['id'])['worktree'])
        (directory / 'app.py').write_text('broken')
        result = await self.code.restore(task['id'], first['id'])
        self.assertEqual((directory / 'app.py').read_text(), 'print("hello")\n')
        self.assertIn(result['backup'], [c['id'] for c in (await self.code.view(task['id']))['checkpoints']])
        self.assertFalse(self.code.task(task['id'])['closed'])

    async def test_plan_stays_read_only_on_followup_until_explicit_transition(self):
        project = await self.code.add('octo/demo')
        task = await self.code.start(StartTask(project=project['id'], prompt='Plan a fix', mode='plan'))
        sid = self.code.task(task['id'])['session']
        self.assertEqual(task['mode'], 'plan')
        self.assertEqual(self.pc.agent_of(sid), PLAN_AGENT)
        self.idle(task)
        await self.code.follow_up(task['id'], 'Refine the plan')
        self.assertEqual(self.pc.agent_of(sid), PLAN_AGENT)
        self.idle(task)
        result = await self.code.implement_plan(task['id'])
        self.assertEqual(result['mode'], 'code')
        self.assertEqual(self.pc.agent_of(sid), CODE_AGENT)
        patches = [c for c in self.pc.calls if c[0] == 'PATCH' and c[1] == f'/session/{sid}']
        self.assertEqual(decision(patches[-2][2]['json']['permission'], 'edit', 'app.py'), 'deny')
        self.assertEqual(decision(patches[-1][2]['json']['permission'], 'edit', 'app.py'), 'allow')

    async def test_add_validates_and_clones_once(self):
        for bad in ('demo', 'octo/../x', 'a/b/c', 'octo/de mo'):
            with self.assertRaises(HTTPException): await self.code.add(bad)
        project = await self.code.add('https://github.com/octo/demo.git')
        self.assertEqual(project['repo'], 'octo/demo')
        self.assertEqual(project['default_branch'], 'main')
        self.assertTrue((Path(project['path']) / 'README.md').is_file())
        again = await self.code.add('octo/demo')
        self.assertEqual(again['id'], project['id'])
        self.assertEqual(self.log.read_text().count('repo clone'), 1)
        self.assertEqual(self.code.projects()[0]['repo'], 'octo/demo')
        self.assertEqual((await self.code.available())[0]['repo'], 'octo/demo')

    async def test_task_gets_its_own_worktree_and_branch(self):
        project, task = await self.task()
        self.assertTrue(task['branch'].startswith('friday/add-a-greeting-option-'))
        self.assertEqual(task['title'], 'Add a greeting option')
        body, directory, agent = self.started[0]
        self.assertEqual(agent, CODE_AGENT)
        self.assertEqual(Path(directory).name, task['id'])
        self.assertEqual(git('branch', '--show-current', cwd=directory).strip(), task['branch'])
        self.assertIn('Repository: octo/demo', body.prompt)
        self.assertIn(task['branch'], body.prompt)
        self.assertEqual(body.source, 'code')
        create = next(call for call in self.pc.calls if call[1] == '/session')
        self.assertEqual(create[2]['timeout'], 90)
        self.assertEqual(create[2]['params'], {'directory': directory})
        self.assertEqual(create[2]['json']['agent'], CODE_AGENT)
        self.assertEqual(self.pc.record(task_id := self.code.task(task['id'])['session'])['agent'], CODE_AGENT)
        self.assertEqual(self.pc.scope(task_id), {'params': {'directory': directory}})
        self.assertEqual([t['id'] for t in self.code.tasks(project['id'])], [task['id']])

    async def test_task_identity_is_available_to_agent_without_private_email(self):
        project, task = await self.task()
        directory = self.code.task(task['id'])['worktree']
        self.assertEqual(git('config', '--get', 'user.name', cwd=directory).strip(), 'octo')
        self.assertEqual(git('config', '--get', 'user.email', cwd=directory).strip(), '123+octo@users.noreply.github.com')
        self.assertEqual(git('config', '--get', 'user.email', cwd=project['path']).strip(), '123+octo@users.noreply.github.com')

    async def test_identity_preserves_existing_configuration(self):
        project = await self.code.add('octo/demo')
        git('config', 'user.name', 'Existing', cwd=project['path'])
        git('config', 'user.email', 'existing@users.noreply.github.com', cwd=project['path'])
        await self.code.ensure_git_identity(project['path'])
        self.assertNotIn('api user', self.log.read_text())
        git('config', '--unset', 'user.email', cwd=project['path'])
        await self.code.ensure_git_identity(project['path'])
        self.assertEqual(git('config', '--get', 'user.name', cwd=project['path']).strip(), 'Existing')
        self.assertEqual(git('config', '--get', 'user.email', cwd=project['path']).strip(), '123+octo@users.noreply.github.com')

    async def test_pr_repairs_existing_task_with_no_git_identity(self):
        _, task = await self.task()
        self.idle(task)
        directory = self.code.task(task['id'])['worktree']
        git('config', '--unset', 'user.name', cwd=directory)
        git('config', '--unset', 'user.email', cwd=directory)
        (Path(directory) / 'new.txt').write_text('Recovered task\n')
        # Neither system/global config nor author/committer environment can supply an identity.
        with patch.dict(os.environ):
            for key in IDENTITY: os.environ.pop(key, None)
            result = await self.code.pull_request(task['id'], PullRequest())
        self.assertEqual(result['pr_url'], 'https://github.com/octo/demo/pull/7')
        self.assertEqual(git('log', '-1', '--format=%ae|%ce', cwd=directory).strip(),
                         '123+octo@users.noreply.github.com|123+octo@users.noreply.github.com')

    async def test_follow_up_repairs_existing_task_identity(self):
        _, task = await self.task()
        self.idle(task)
        directory = self.code.task(task['id'])['worktree']
        git('config', '--unset', 'user.email', cwd=directory)
        await self.code.follow_up(task['id'], 'Continue the task')
        self.assertEqual(git('config', '--get', 'user.email', cwd=directory).strip(), '123+octo@users.noreply.github.com')

    async def test_local_selection_reaches_code_session_and_summary(self):
        project = await self.code.add('octo/demo')
        task = await self.code.start(StartTask(project=project['id'], prompt='Local task', model_choice='local'))
        self.assertEqual(task['model_choice'], 'local')
        identifier = self.code.task(task['id'])['session']
        self.assertEqual(await self.pc.session_chain(identifier), [('friday-local', 'qwen3.8-27b')])
        self.assertEqual(self.started[-1][0].model_choice, 'local')
        from pydantic import ValidationError
        with self.assertRaises(ValidationError):
            StartTask(project=project['id'], prompt='Task', model_choice='paid-cloud')

    async def test_trusted_projects_run_commands_freely(self):
        project, task = await self.task()
        directory = self.code.task(task['id'])['worktree']
        rules = self.pc.rule_builders[CODE_AGENT](None, directory)
        self.assertEqual(decision(rules, 'bash', 'pytest'), 'ask')
        self.code.set_trusted(project['id'], True)
        rules = self.pc.rule_builders[CODE_AGENT](None, directory)
        self.assertEqual(decision(rules, 'bash', 'pytest'), 'allow')

    async def test_trust_applies_before_the_initial_prompt(self):
        project = await self.code.add('octo/demo')
        self.code.set_trusted(project['id'], True)
        await self.code.start(StartTask(project=project['id'], prompt='Run the tests'))
        for method, route, kwargs in self.pc.calls:
            if route == '/session' or method == 'PATCH' and route.startswith('/session/'):
                rules = kwargs['json']['permission']
                self.assertEqual(decision(rules, 'bash', 'pytest'), 'allow', (method, route))
                self.assertEqual(decision(rules, 'bash', 'git push origin HEAD'), 'ask')

    async def test_changes_cover_commits_edits_and_new_files(self):
        _, task = await self.task()
        worktree = Path(self.code.task(task['id'])['worktree'])
        (worktree / 'app.py').write_text('print("hello")\nprint("hi")\n')
        git('commit', '--quiet', '-am', 'Greeting', cwd=worktree)
        (worktree / 'README.md').write_text('# Demo app\n')
        (worktree / 'notes.txt').write_text('one\ntwo\n')
        changes = await self.code.diff(task['id'])
        files = {f['path']: f for f in changes['files']}
        self.assertEqual(set(files), {'README.md', 'app.py', 'notes.txt'})
        self.assertEqual((files['app.py']['additions'], files['app.py']['deletions']), (1, 0))
        self.assertEqual(files['README.md']['status'], 'modified')
        self.assertEqual((files['notes.txt']['status'], files['notes.txt']['additions']), ('added', 2))
        self.assertIn('+print("hi")', files['app.py']['patch'])
        self.assertIn('+two', files['notes.txt']['patch'])
        self.assertEqual((changes['commits'], changes['uncommitted']), (1, 2))
        self.assertEqual(self.code.tasks()[0]['stats']['files'], 3)

    async def test_pull_request_commits_pushes_and_opens(self):
        _, task = await self.task()
        with self.assertRaises(HTTPException) as busy: await self.code.pull_request(task['id'], PullRequest())
        self.assertIn('still working', busy.exception.detail)
        self.idle(task)
        with self.assertRaises(HTTPException) as nothing: await self.code.pull_request(task['id'], PullRequest())
        self.assertIn('no changes', nothing.exception.detail)
        worktree = Path(self.code.task(task['id'])['worktree'])
        (worktree / 'app.py').write_text('print("hello, world")\n')
        self.pc.messages = [{'info': {'id': 'm1', 'role': 'assistant'}, 'parts': [{'id': 'p1', 'type': 'text', 'text': 'Changed the greeting.'}]}]
        result = await self.code.pull_request(task['id'], PullRequest(draft=True))
        self.assertEqual(result['pr_url'], 'https://github.com/octo/demo/pull/7')
        self.assertIn(task['branch'], git('branch', '--list', cwd=self.origin))
        log = self.log.read_text()
        self.assertIn(f'pr create --repo octo/demo --base main --head {task["branch"]} --title Add a greeting option --body-file ', log)
        self.assertIn('Changed the greeting.', log)
        self.assertIn('--draft', log)
        status = await self.code.pr_status(self.code.task(task['id']))
        self.assertEqual((status['state'], status['checks']), ('open', {'total': 3, 'failed': 1, 'pending': 1}))
        # A second request returns the same pull request.
        await self.code.pull_request(task['id'], PullRequest())
        self.assertEqual(self.log.read_text().count('pr create'), 1)

    async def test_push_waits_for_the_agent_then_commits_leftovers(self):
        _, task = await self.task()
        with self.assertRaises(HTTPException) as busy: await self.code.push(task['id'])
        self.assertEqual(busy.exception.status_code, 409)
        self.idle(task)
        (Path(self.code.task(task['id'])['worktree']) / 'new.txt').write_text('x\n')
        await self.code.push(task['id'])
        self.assertIn(task['branch'], git('branch', '--list', cwd=self.origin))
        self.assertIn('Add a greeting option', git('log', '-1', '--format=%s', task['branch'], cwd=self.origin))

    async def test_discard_removes_worktree_and_branch(self):
        project, task = await self.task()
        worktree = Path(self.code.task(task['id'])['worktree'])
        result = await self.code.discard(task['id'])
        self.assertEqual(result['state'], 'discarded')
        self.assertFalse(worktree.exists())
        self.assertNotIn(task['branch'], git('branch', '--list', cwd=project['path']))
        self.assertEqual(self.code.tasks(), [])
        with self.assertRaises(HTTPException): await self.code.follow_up(task['id'], 'more')
        self.code.remove(project['id'])
        self.assertEqual(self.code.projects(), [])

    async def test_files_stay_inside_the_worktree(self):
        _, task = await self.task()
        self.assertIn('hello', (await self.code.file(task['id'], 'app.py'))['text'])
        (Path(self.code.task(task['id'])['worktree']) / 'loop').symlink_to('loop')
        for path in ('../../origin.git/HEAD', '/etc/passwd', 'missing.py', 'loop', 'bad\0path'):
            with self.assertRaises(HTTPException): await self.code.file(task['id'], path)

    async def test_diff_does_not_follow_untracked_symlinks(self):
        _, task = await self.task()
        worktree = Path(self.code.task(task['id'])['worktree'])
        outside = Path(self.temp.name) / 'private.txt'
        outside.write_text('PRIVATE CONTENT MUST NOT APPEAR')
        (worktree / 'link.txt').symlink_to(outside)
        (worktree / 'broken.txt').symlink_to('does-not-exist')
        diff = await self.code.diff(task['id'])
        files = {f['path']: f for f in diff['files']}
        self.assertTrue(files['link.txt']['symlink'])
        self.assertNotIn(outside.read_text(), str(diff))
        self.assertIn(str(outside), files['link.txt']['patch'])
        self.assertIn('does-not-exist', files['broken.txt']['patch'])
        with self.assertRaises(HTTPException): await self.code.file(task['id'], 'link.txt')

    async def test_large_files_and_diff_previews_are_bounded(self):
        _, task = await self.task()
        worktree = Path(self.code.task(task['id'])['worktree'])
        for n in range(12): (worktree / f'new-{n:02}.txt').write_text('line\n' * 50000)
        # Include a large tracked change, and a tab in a tracked filename.
        (worktree / 'app.py').write_text('large tracked line\n' * 100000)
        tabbed = worktree / 'tab\tname.txt'
        tabbed.write_text('before\n')
        git('add', tabbed.name, cwd=worktree)
        tabbed.write_text('after\n')
        result = await self.code.file(task['id'], 'new-00.txt')
        self.assertEqual(result['size'], 250000)
        self.assertEqual(len(result['text']), MAX_FILE)
        self.assertTrue(result['truncated'])
        diff = await self.code.diff(task['id'])
        self.assertIn(tabbed.name, [f['path'] for f in diff['files']])
        self.assertLessEqual(sum(len(f['patch']) for f in diff['files']), MAX_DIFF)
        self.assertTrue(all(len(f['patch']) <= MAX_PATCH for f in diff['files']))
        self.assertTrue(next(f for f in diff['files'] if f['path'] == 'new-00.txt')['truncated'])

    async def test_file_preview_rejects_special_files_and_symlink_directories(self):
        from desktop.agent.code import read_worktree_file
        _, task = await self.task()
        worktree = Path(self.code.task(task['id'])['worktree'])
        os.mkfifo(worktree / 'pipe')
        (worktree / 'outside').symlink_to(self.temp.name, target_is_directory=True)
        for name in ('pipe', 'outside/private.txt', '../private.txt'):
            with self.assertRaises(OSError): read_worktree_file(worktree, name, 100)
        # Resolving a requested file must not authorize a replaced worktree root.
        (Path(self.temp.name) / 'private.txt').write_text('private content')
        relocated = worktree.with_name(worktree.name + '-original')
        worktree.rename(relocated)
        worktree.symlink_to(self.temp.name, target_is_directory=True)
        try:
            with self.assertRaises(HTTPException): await self.code.file(task['id'], 'private.txt')
        finally:
            worktree.unlink()
            relocated.rename(worktree)

    async def test_failed_session_leaves_nothing_behind(self):
        project = await self.code.add('octo/demo')
        async def broken(*args, **kwargs): raise HTTPException(409, 'Four PC tasks are already active')
        self.pc.start = broken
        with self.assertRaises(HTTPException): await self.code.start(StartTask(project=project['id'], prompt='Anything'))
        self.assertEqual(self.code.tasks(), [])
        self.assertEqual(self.code.starting_projects, {})
        self.assertNotIn('friday/', git('branch', '--list', cwd=project['path']))

    async def test_requests_in_a_task_worktree_reach_the_phone(self):
        _, task = await self.task()
        record = self.code.task(task['id'])
        request = {'id': 'per_1', 'sessionID': record['session'], 'permission': 'bash', 'patterns': ['npm install'],
                   'metadata': {'command': 'npm install'}, 'always': ['npm install *']}
        calls = []
        async def call(method, path, **kwargs):
            calls.append((method, path, kwargs))
            here = kwargs.get('params', {}).get('directory') == record['worktree']
            if path == '/permission': return [request] if here else []
            if path == '/question': return []
            return True
        self.pc.call = call
        pending = await self.pc.pending()
        self.assertEqual([p['detail'] for p in pending['permissions']], ['npm install'])
        self.assertEqual((await self.pc.pending(record['session']))['permissions'][0]['id'], 'per_1')
        await self.pc.reply('per_1', Reply(reply='once'))
        self.assertIn(('POST', '/permission/per_1/reply', {'json': {'reply': 'once'}, 'params': {'directory': record['worktree']}}), calls)
        # Finished sessions' worktrees are not polled.
        self.idle(task)
        calls.clear()
        await self.pc.pending()
        self.assertTrue(all('params' not in kwargs for _, _, kwargs in calls))

    async def test_routes(self):
        app = FastAPI()
        routes(app, [], self.code)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
            added = (await client.post('/workspace/code/projects', json={'repo': 'octo/demo'})).json()
            self.assertEqual((await client.post('/workspace/code/projects', json={'repo': 'octo/demo', 'path': '/tmp'})).status_code, 422)
            configured = await client.put(f'/workspace/code/projects/{added["id"]}/checks', json={'checks': [
                {'name': 'Unit', 'command': 'python3 -m unittest', 'min_tests': 3}]})
            self.assertEqual(configured.status_code, 200)
            self.assertEqual((await client.get('/workspace/code/projects')).json()[0]['id'], added['id'])
            self.assertEqual((await client.get(f'/workspace/code/projects/{added["id"]}/branches')).json()['branches'], ['main'])
            started = (await client.post('/workspace/code/tasks', json={'project': added['id'], 'prompt': 'Do it'})).json()
            view = (await client.get(f'/workspace/code/tasks/{started["id"]}')).json()
            self.assertEqual((view['branch'], view['changes']['files'], view['pr']), (started['branch'], [], None))
            self.assertEqual(view['session']['id'], self.code.task(started['id'])['session'])
            self.assertEqual(view['verification']['required'][0]['state'], 'missing')
            self.assertEqual((await client.post(f'/workspace/code/tasks/{started["id"]}/restore', json={'checkpoint': '../escape'})).status_code, 422)
            self.assertEqual((await client.post(f'/workspace/code/tasks/{started["id"]}/checkpoints', json={})).status_code, 409)
            self.idle(started)
            saved = await client.post(f'/workspace/code/tasks/{started["id"]}/checkpoints', json={'label': 'API checkpoint'})
            self.assertEqual(saved.status_code, 200)
            self.assertEqual((await client.post(f'/workspace/code/tasks/{started["id"]}/restore', json={'checkpoint': saved.json()['id']})).status_code, 200)
            self.assertEqual((await client.post('/workspace/code/tasks', json={'project': added['id'], 'prompt': 'x', 'extra': 1})).status_code, 422)
            self.assertEqual((await client.delete(f'/workspace/code/tasks/{started["id"]}')).json()['state'], 'discarded')


if __name__ == '__main__':
    unittest.main()
