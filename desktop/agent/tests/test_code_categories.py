import asyncio
import json
import os
from pathlib import Path
import shutil
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

from desktop.agent.code import StartTask
from desktop.agent.code_control import TaskControl
from desktop.agent.code_setup import NewProject, EnvironmentRecipe
from desktop.agent.code_extensions import ExtensionDraft, McpDraft
from desktop.agent.code_collaboration import RemoteProject, MergeAction, ReviewAction
from desktop.agent.tests import test_code as fixtures
from desktop.agent.tests.test_code import git


class CategoryTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixtures.CodeProjectsTest.asyncSetUp
    asyncTearDown = fixtures.CodeProjectsTest.asyncTearDown
    task = fixtures.CodeProjectsTest.task
    idle = fixtures.CodeProjectsTest.idle

    async def test_queue_dispatch_order_pause_cancel_and_ambiguous_delivery(self):
        _, task = await self.task()
        control = self.code.control
        control.enqueue(task['id'], 'First')
        control.enqueue(task['id'], 'Second')
        await control.pump_once()
        self.assertEqual([m['state'] for m in control.view(task['id'])['messages']], ['queued', 'queued'])
        self.idle(task)
        await control.pump_once()
        self.assertEqual([m['state'] for m in control.view(task['id'])['messages']], ['sent', 'queued'])
        await self.code.cancel(task['id'])
        self.assertTrue(control.view(task['id'])['paused'])
        await control.pump_once()
        self.assertEqual(control.view(task['id'])['messages'][-1]['state'], 'queued')
        control.pause(task['id'], False)
        with patch.object(self.code, 'follow_up', new=AsyncMock(side_effect=TimeoutError())):
            await control.pump_once()
        state = control.view(task['id'])
        self.assertTrue(state['paused'])
        self.assertEqual(state['messages'][-1]['state'], 'uncertain')
        control.remove(task['id'], state['messages'][-1]['id'])
        with self.code.db() as db:
            db.execute("INSERT INTO code_queue(task,prompt,state,created) VALUES(?,?,'dispatching',1)", (task['id'], 'Crash probe'))
        reopened = TaskControl(self.code)
        self.assertEqual(reopened.view(task['id'])['messages'][-1]['state'], 'uncertain')

    async def test_steer_stops_then_sends_new_direction_without_changing_plan_mode(self):
        project = await self.code.add('octo/demo')
        task = await self.code.start(StartTask(project=project['id'], prompt='Plan it', mode='plan'))
        sid = self.code.task(task['id'])['session']
        await self.code.control.steer(task['id'], 'Focus on validation instead')
        self.assertEqual(self.pc.agent_of(sid), 'friday-code-plan')
        calls = [c[1] for c in self.pc.calls]
        self.assertLess(calls.index(f'/session/{sid}/abort'), len(calls) - 1)
        prompts = [c[2]['json']['parts'][0]['text'] for c in self.pc.calls if c[1].endswith('/prompt_async')]
        self.assertIn('Focus on validation instead', prompts[-1])
        self.assertIn('partially completed', prompts[-1])

    async def test_task_fork_preserves_dirty_files_and_history_link_without_starting_a_turn(self):
        _, task = await self.task()
        self.idle(task)
        source = Path(self.code.task(task['id'])['worktree'])
        (source / 'app.py').write_text('current work\n')
        (source / 'notes.txt').write_text('untracked work\n')
        prompts_before = len([c for c in self.pc.calls if c[1].endswith('/prompt_async')])
        fork = await self.code.control.fork(task['id'])
        child = self.code.task(fork['id'])
        self.assertEqual(fork['state'], 'idle')
        self.assertEqual(fork['parent'], task['id'])
        self.assertEqual(fork['relation'], 'fork')
        self.assertNotEqual(child['branch'], task['branch'])
        self.assertEqual(Path(child['worktree'], 'app.py').read_text(), 'current work\n')
        self.assertEqual(Path(child['worktree'], 'notes.txt').read_text(), 'untracked work\n')
        self.assertEqual(len([c for c in self.pc.calls if c[1].endswith('/prompt_async')]), prompts_before)
        await self.code.follow_up(fork['id'], 'Continue with tests')
        text = [c[2]['json']['parts'][0]['text'] for c in self.pc.calls if c[1].endswith('/prompt_async')][-1]
        self.assertIn('Forked conversation handoff', text)
        self.assertIn('Continue with tests', text)
        self.assertEqual((source / 'app.py').read_text(), 'current work\n')

    async def test_project_templates_have_real_passing_tests_and_refuse_existing_folders(self):
        for template in ('python', 'node'):
            if template == 'node' and not shutil.which('node'): continue
            target = self.origin.parent / ('new-' + template)
            project = await self.code.setup.create(NewProject(path=str(target), template=template))
            self.assertEqual(project['kind'], 'local')
            command = ['python3', '-m', 'unittest', 'discover', '-s', 'tests', '-v'] if template == 'python' else ['node', '--test']
            result, out, error = await self.code.run(*command, cwd=target)
            self.assertEqual(result, 0)
            self.assertEqual(project['checks'][0]['min_tests'], 1)
            with self.assertRaises(HTTPException): await self.code.setup.create(NewProject(path=str(target)))
            self.assertTrue((target / 'README.md').is_file())

    async def test_environment_recipe_is_private_scoped_and_sent_through_normal_agent_permissions(self):
        project, task = await self.task()
        self.idle(task)
        self.code.setup.set(project['id'], EnvironmentRecipe(steps=[dict(name='Venv', command='python3 -m venv .venv')],
            environment={'PATH': '${WORKTREE}/.venv/bin:${PATH}', 'PRIVATE_SETTING': 'synthetic-value'}))
        self.assertNotIn('synthetic-value', json.dumps(self.code.projects()))
        await self.code.setup.prepare(task['id'])
        text = [c[2]['json']['parts'][0]['text'] for c in self.pc.calls if c[1].endswith('/prompt_async')][-1]
        self.assertIn('python3 -m venv .venv', text)
        self.assertNotIn('synthetic-value', text)
        with self.assertRaises(HTTPException):
            self.code.setup.set(project['id'], EnvironmentRecipe(steps=[dict(name='Bad', command='true', cwd='../outside')]))

    async def test_extensions_are_disabled_until_exact_revision_review_and_updates_revoke_approval(self):
        extension = self.code.extensions.save(ExtensionDraft(name='review-python', kind='skill', content='Inspect tests before editing.'))
        self.assertFalse(extension['enabled'])
        self.assertEqual(self.code.extensions.runtime()['skills'], [])
        await self.code.extensions.approve(extension['id'], extension['digest'])
        self.assertTrue(self.code.extensions.runtime()['skills'])
        self.assertTrue(self.pc.config([('friday-local', 'qwen3.8-27b')])['skills']['paths'])
        updated = self.code.extensions.save(ExtensionDraft(name='review-python', kind='skill', version='2', content='Also inspect error paths.'), extension['id'])
        self.assertFalse(updated['enabled'])
        self.assertEqual(len(updated['versions']), 2)
        with self.assertRaises(HTTPException): await self.code.extensions.approve(updated['id'], extension['digest'])
        restored = self.code.extensions.restore(extension['id'], extension['digest'])
        self.assertEqual(restored['version'], '1')
        self.assertFalse(restored['enabled'])
        self.code.extensions.remove(extension['id'])
        self.assertEqual(self.code.extensions.list(), [])

    async def test_plugin_import_and_syntax_check_do_not_execute_its_code(self):
        sentinel = self.origin.parent / 'plugin-executed'
        source = 'import {writeFileSync} from "node:fs"; writeFileSync(' + json.dumps(str(sentinel)) + ', "ran"); export const Example = async () => ({});'
        extension = self.code.extensions.save(ExtensionDraft(name='example-plugin', kind='plugin', content=source))
        self.assertFalse(sentinel.exists())
        await self.code.extensions.approve(extension['id'], extension['digest'])
        self.assertFalse(sentinel.exists())
        self.assertTrue(self.code.extensions.runtime()['plugins'])
        self.code.extensions.disable(extension['id'])
        self.assertEqual(self.code.extensions.runtime()['plugins'], [])

    async def test_mcp_configuration_review_redacts_credentials_and_requires_current_digest(self):
        item = self.code.extensions.save_mcp('example', McpDraft(entry={'type': 'remote', 'url': 'https://mcp.example.invalid/api',
            'headers': {'Authorization': 'synthetic-secret'}, 'oauth': {'clientId': 'test-client'}}))
        self.assertNotIn('synthetic-secret', json.dumps(item))
        self.assertEqual(self.pc.mcp_servers(), {})
        with self.assertRaises(HTTPException): await self.code.extensions.mcp_action('example', 'auth')
        self.code.extensions.approve_mcp('example', item['digest'])
        self.assertIn('example', self.pc.mcp_servers())
        preserved = self.code.extensions.save_mcp('example', McpDraft(entry=item['entry']))
        self.code.extensions.approve_mcp('example', preserved['digest'])
        self.assertEqual(self.pc.mcp_servers()['example']['headers']['Authorization'], 'synthetic-secret')
        await self.code.extensions.mcp_action('example', 'auth')
        self.assertEqual(self.pc.calls[-1][1], '/mcp/example/auth')
        changed = self.code.extensions.save_mcp('example', McpDraft(entry={'type': 'remote', 'url': 'https://mcp.example.invalid/changed'}))
        with self.assertRaises(HTTPException): self.code.extensions.approve_mcp('example', item['digest'])
        self.assertNotEqual(item['digest'], changed['digest'])
        await self.code.extensions.disable_mcp('example', remove=True)
        self.assertEqual(self.code.extensions.mcp_list(), [])

    async def test_stack_uses_local_parent_commit_and_requires_parent_publication(self):
        project, parent = await self.task()
        self.idle(parent)
        directory = self.code.task(parent['id'])['worktree']
        Path(directory, 'app.py').write_text('parent implementation\n')
        with self.assertRaises(HTTPException): await self.code.start(StartTask(project=project['id'], parent=parent['id'], prompt='Stack'))
        git('add', '.', cwd=directory); git('commit', '-qm', 'Parent', cwd=directory)
        child = await self.code.start(StartTask(project=project['id'], parent=parent['id'], prompt='Stack'))
        self.assertEqual(child['relation'], 'stack')
        self.assertEqual(child['base'], parent['branch'])
        self.assertEqual(Path(self.code.task(child['id'])['worktree'], 'app.py').read_text(), 'parent implementation\n')

    async def make_conflict(self):
        project, task = await self.task()
        self.idle(task)
        directory = self.code.task(task['id'])['worktree']
        Path(directory, 'app.py').write_text('task change\n')
        git('add', '.', cwd=directory); git('commit', '-qm', 'Task change', cwd=directory)
        seed = self.origin.parent / 'seed'
        Path(seed, 'app.py').write_text('base change\n')
        git('add', '.', cwd=seed); git('commit', '-qm', 'Base change', cwd=seed)
        git('push', str(self.origin), 'main', cwd=seed)
        return task, directory

    async def test_conflicts_are_visible_and_explicit_resolution_completes_local_merge(self):
        task, directory = await self.make_conflict()
        state = await self.code.collaboration.merge(task['id'], MergeAction(action='sync'))
        self.assertTrue(state['merging']); self.assertEqual(state['files'], ['app.py'])
        state = await self.code.collaboration.merge(task['id'], MergeAction(action='ours', path='app.py'))
        self.assertEqual(state['files'], [])
        state = await self.code.collaboration.merge(task['id'], MergeAction(action='continue'))
        self.assertFalse(state['merging'])
        self.assertEqual(Path(directory, 'app.py').read_text(), 'task change\n')

    async def test_merge_abort_keeps_a_recovery_checkpoint_and_restores_head(self):
        task, directory = await self.make_conflict()
        before = git('rev-parse', 'HEAD', cwd=directory)
        await self.code.collaboration.merge(task['id'], MergeAction(action='sync'))
        await self.code.collaboration.merge(task['id'], MergeAction(action='abort'))
        self.assertEqual(git('rev-parse', 'HEAD', cwd=directory), before)
        self.assertIn('Before merge action: abort', [c['label'] for c in self.code.checkpoints.list(task['id'])])

    async def test_gitlab_and_generic_remote_projects_use_git_without_github_signin(self):
        original = self.code.run
        async def run(*args, **kwargs):
            if args[0] == self.code.git and args[1:3] == ('clone', '--quiet'):
                args = (*args[:4], str(self.origin), args[-1])
            return await original(*args, **kwargs)
        with patch.object(self.code, 'run', side_effect=run):
            project = await self.code.collaboration.add_remote(RemoteProject(url='https://gitlab.example.invalid/group/sub/demo.git', kind='gitlab'))
        self.assertEqual(project['forge_repo'], 'group/sub/demo')
        self.assertEqual(project['host'], 'gitlab.example.invalid')
        self.assertFalse(self.log.exists())
        task = await self.code.start(StartTask(project=project['id'], prompt='Work'))
        self.assertEqual(task['kind'], 'gitlab')
        self.assertFalse(self.log.exists())
        for bad in ('file:///tmp/repo', 'https://user:token@host.invalid/group/repo', 'ssh://git@host.invalid/group/../repo', '--upload-pack=bad', 'https://host.invalid:8443/group/repo', 'ssh://git@host.invalid:2222/group/repo', 'https://host.invalid:bad/group/repo'):
            with self.assertRaises(HTTPException): await self.code.collaboration.add_remote(RemoteProject(url=bad))

    async def forge_fixture(self, kind='github'):
        project, task = await self.task()
        self.idle(task)
        directory = self.code.task(task['id'])['worktree']
        head = git('rev-parse', 'HEAD', cwd=directory).strip()
        helper = self.origin.parent / 'forge-helper'
        self.forge_log = self.origin.parent / 'forge.jsonl'
        script = '''#!/usr/bin/env python3
import json,sys
args=sys.argv[1:]
if args[:2]==['run','view']:
    print('Synthetic CI failure: assertion in test_api');sys.exit(0)
endpoint=args[1]; method=args[args.index('--method')+1] if '--method' in args else 'GET'
body=json.load(open(args[args.index('--input')+1])) if '--input' in args else None
with open(LOG,'a') as f:f.write(json.dumps({'endpoint':endpoint,'method':method,'body':body})+'\\n')
if endpoint=='graphql':
    if body['query'].startswith('mutation'):data={'data':{'thread':{'isResolved':True}}}
    else:data={'data':{'repository':{'pullRequest':{'reviewThreads':{'pageInfo':{'hasNextPage':False},'nodes':[{'id':'PRRT_synthetic','isResolved':False,'path':'app.py','line':1,'comments':{'nodes':[{'databaseId':17,'body':'Handle empty input','author':{'login':'reviewer'}}]}}]}}}}}
elif endpoint.endswith('/check-runs?per_page=50'):data={'check_runs':[{'id':1,'name':'Unit','conclusion':'failure','details_url':'https://github.com/octo/demo/actions/runs/9/job/10'}]}
elif endpoint.endswith('/discussions?per_page=50'):data=[{'id':'abc123','notes':[{'body':'Handle empty input','resolvable':True,'resolved':False,'position':{'new_path':'app.py','new_line':1},'author':{'username':'reviewer'}}]}]
elif '/pipelines/' in endpoint:data=[{'id':10,'name':'Unit','status':'failed','web_url':'https://gitlab.example.invalid/group/demo/-/jobs/10'}]
elif endpoint.endswith('/trace'):print('Synthetic GitLab CI failure');sys.exit(0)
elif method!='GET':data={'web_url':'https://gitlab.example.invalid/group/demo/-/merge_requests/7','ok':True}
elif '/pulls/' in endpoint:data={'head':{'sha':HEAD}}
else:data={'sha':HEAD,'head_pipeline':{'id':9,'status':'failed'},'state':'opened'}
print(json.dumps(data))
'''
        helper.write_text(script.replace('LOG', repr(str(self.forge_log))).replace('HEAD', repr(head)))
        helper.chmod(0o700)
        if kind == 'github': self.code.gh = str(helper)
        else:
            self.code.collaboration.glab = str(helper)
            with self.code.db() as db:
                db.execute("UPDATE projects SET kind='gitlab',host='gitlab.example.invalid',forge_repo='group/demo' WHERE id=?", (project['id'],))
        with self.code.db() as db: db.execute('UPDATE tasks SET pr_url=? WHERE id=?',
            ('https://github.com/octo/demo/pull/7' if kind == 'github' else 'https://gitlab.example.invalid/group/demo/-/merge_requests/7', task['id']))
        return task, head

    async def test_github_review_posts_exact_multiline_reply_and_rejects_stale_heads(self):
        task, head = await self.forge_fixture()
        details = await self.code.collaboration.details(task['id'])
        self.assertEqual(details['threads'][0]['path'], 'app.py')
        reply = 'Fixed empty input.\n\nVerified with tests; literal $(do-not-run).'
        await self.code.collaboration.review_action(task['id'], ReviewAction(head=head, thread='PRRT_synthetic', action='reply', body=reply))
        log = [json.loads(line) for line in self.forge_log.read_text().splitlines()]
        self.assertEqual(log[-1]['body']['body'], reply)
        self.assertTrue(log[-1]['endpoint'].endswith('/comments/17/replies'))
        before = len([r for r in log if r['endpoint'].endswith('/replies')])
        with self.assertRaises(HTTPException):
            await self.code.collaboration.review_action(task['id'], ReviewAction(head='f'*40, thread='PRRT_synthetic', action='reply', body='stale'))
        log = [json.loads(line) for line in self.forge_log.read_text().splitlines()]
        self.assertEqual(len([r for r in log if r['endpoint'].endswith('/replies')]), before)
        await self.code.collaboration.repair(task['id'], head)
        prompt = [c[2]['json']['parts'][0]['text'] for c in self.pc.calls if c[1].endswith('/prompt_async')][-1]
        self.assertIn('Synthetic CI failure', prompt)
        self.assertIn('<untrusted-review-ci>', prompt)

    async def test_gitlab_discussions_ci_trace_and_resolution_use_the_configured_host(self):
        task, head = await self.forge_fixture('gitlab')
        details = await self.code.collaboration.details(task['id'], logs=True)
        self.assertIn('Synthetic GitLab CI failure', details['logs'])
        self.assertEqual(details['threads'][0]['id'], 'abc123')
        await self.code.collaboration.review_action(task['id'], ReviewAction(head=head, thread='abc123', action='resolve'))
        log = [json.loads(line) for line in self.forge_log.read_text().splitlines()]
        self.assertEqual(log[-1]['method'], 'PUT')
        self.assertEqual(log[-1]['body'], {'resolved': True})
