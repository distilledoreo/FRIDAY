"""Installed OpenCode + FRIDAY + real git/files/tests; only model decisions and gh are fixtures.

No cloud inference, account access, deployment, or edits outside the temporary repository.
"""
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
from unittest.mock import patch

from desktop.agent.code import CodeProjects, StartTask
from desktop.agent.pc import LOCAL, PcAgent, Reply, Settings
from desktop.agent.tests.test_code import FAKE_GH, IDENTITY, git


class Model(BaseHTTPRequestHandler):
    steps = []
    seen = []
    index = 0
    directory = None

    def log_message(self, *args): pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        type(self).seen.append(body)
        if not body.get('tools'):
            delta = {'content': 'Coding handoff: normalize whitespace and case. All three tests passed after repair; review the diff.'}
        elif type(self).index < len(self.steps):
            name, args = self.steps[type(self).index]
            type(self).index += 1
            args = {key: value.replace('$WORKTREE', self.directory) if isinstance(value, str) else value for key, value in args.items()}
            delta = {'tool_calls': [{'index': 0, 'id': f'call_owned_{type(self).index}', 'type': 'function',
                'function': {'name': name, 'arguments': json.dumps(args)}}]}
        else:
            delta = {'content': 'Owned workflow finished. The recorded test results are the verification evidence.'}
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for value, finish in ((delta, None), ({}, 'tool_calls' if 'tool_calls' in delta else 'stop')):
            event = {'id': 'owned_coding', 'object': 'chat.completion.chunk', 'created': int(time.time()),
                     'model': LOCAL[1], 'choices': [{'index': 0, 'delta': value, 'finish_reason': finish}]}
            self.wfile.write(('data: ' + json.dumps(event) + '\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


class OwnedPc(PcAgent):
    async def models(self): return [LOCAL]

    async def start(self, body, directory=None, agent='friday'):
        Model.directory = directory
        return await super().start(body, directory=directory, agent=agent)

    def config(self, chain):
        config = super().config(chain)
        provider = config['provider'][LOCAL[0]]
        provider['options']['baseURL'] = f'http://127.0.0.1:{server.server_port}/v1'
        config['mcp'] = {}
        return config


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


async def wait(pc, session, predicate):
    deadline = time.monotonic() + 70
    while time.monotonic() < deadline:
        view = await pc.view(session)
        if predicate(view): return view
        if view['permissions'] and not predicate(view):
            raise AssertionError('Unexpected permission: ' + json.dumps(view['permissions']))
        await asyncio.sleep(.2)
    raise AssertionError('Workflow timed out: ' + json.dumps(view)[-2500:])


async def main():
    with tempfile.TemporaryDirectory(prefix='friday-coding-workflow-') as tmp:
        root = Path(tmp)
        seed = root / 'seed'
        seed.mkdir()
        git('init', '--quiet', '-b', 'main', cwd=seed)
        (seed / 'normalizer.py').write_text('def normalize(value):\n    return value\n')
        (seed / 'README.md').write_text('# Normalizer\nNormalize text.\n')
        (seed / 'tests').mkdir()
        (seed / 'tests/test_normalizer.py').write_text(
            'import unittest\nfrom normalizer import normalize\n\nclass NormalizeTest(unittest.TestCase):\n'
            '    def test_case(self): self.assertEqual(normalize("FOO"), "foo")\n'
            '    def test_space(self): self.assertEqual(normalize("  Foo  BAR "), "foo bar")\n'
            '    def test_empty(self): self.assertEqual(normalize("  "), "")\n')
        (seed / '.gitignore').write_text('__pycache__/\n.venv/\n')
        git('add', '-A', cwd=seed)
        git('commit', '--quiet', '-m', 'Fixture base', cwd=seed)
        origin = root / 'origin.git'
        git('clone', '--quiet', '--bare', str(seed), str(origin), cwd=root)
        gh = root / 'gh'
        gh.write_text(FAKE_GH)
        gh.chmod(0o700)
        pc = OwnedPc(root / 'state', port=port())
        pc.save_settings(Settings(computer_use=False).model_dump())
        code = CodeProjects(root / 'state', pc, workspace=root / 'work', gh=str(gh))
        Model.steps = [
            ('skill', {'name': 'fixture-workflow'}),
            ('glob', {'pattern': '**/*.py'}),
            ('grep', {'pattern': 'def normalize', 'include': '*.py'}),
            ('read', {'filePath': '$WORKTREE/normalizer.py'}),
            ('edit', {'filePath': '$WORKTREE/normalizer.py', 'oldString': 'return value', 'newString': 'return value.strip().lower()'}),
            ('bash', {'command': 'python3 -m unittest discover -s tests -v 2>&1 | tail -30', 'description': 'Reproduce the failure even through a pipeline'}),
            ('edit', {'filePath': '$WORKTREE/normalizer.py', 'oldString': 'return value', 'newString': 'return value.strip().lower()'}),
            ('bash', {'command': 'python3 -m unittest discover -s tests -v', 'description': 'Check the first repair'}),
            ('edit', {'filePath': '$WORKTREE/normalizer.py', 'oldString': 'return value.strip().lower()', 'newString': 'return " ".join(value.split()).lower()'}),
            ('read', {'filePath': '$WORKTREE/README.md'}),
            ('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Normalize text.', 'newString': 'Normalize case and collapse whitespace.'}),
            ('bash', {'command': 'python3 -m unittest discover -s tests -v', 'description': 'Verify the final repair'}),
            ('bash', {'command': 'python3 -c "print(\'diagnostic line\\n\' * 2500); print(\'FINAL DIAGNOSTIC\')"', 'description': 'Exercise a large build log'}),
            ('bash', {'command': 'python3 -c "import os; print(os.environ.get(\'FRIDAY_ENV_TEST\') + \'/\' + os.environ.get(\'FRIDAY_PLUGIN_TEST\'))"', 'description': 'Verify project and plugin environment'}),
            ('bash', {'command': 'python3 -c "raise SystemExit(7)"', 'description': 'Exercise a silent failure'}),
        ]
        try:
            with patch.dict(os.environ, {**IDENTITY, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                                        'FAKE_ORIGIN': str(origin), 'GH_LOG': str(root / 'gh.log')}):
                project = await code.add('octo/demo')
                from desktop.agent.code_setup import EnvironmentRecipe
                from desktop.agent.code_extensions import ExtensionDraft
                code.setup.set(project['id'], EnvironmentRecipe(steps=[{'name': 'Local Python environment', 'command': 'python3 -m venv --without-pip .venv'}],
                    environment={'FRIDAY_ENV_TEST': 'recipe', 'PATH': '${WORKTREE}/.venv/bin:${PATH}'}))
                for extension in (
                    ExtensionDraft(name='fixture-workflow', kind='skill', description='Synthetic workflow instructions', content='Inspect tests before changing code.'),
                    ExtensionDraft(name='fixture-hook', kind='hook', content='export default async function(input, output) { if (typeof output.output === "string") output.output += "\\n[reviewed hook executed]"; }'),
                    ExtensionDraft(name='fixture-plugin', kind='plugin', content='export const Fixture = async () => ({"shell.env": async (input, output) => { output.env.FRIDAY_PLUGIN_TEST = "plugin"; }});'),
                ):
                    saved_extension = code.extensions.save(extension)
                    await code.extensions.approve(saved_extension['id'], saved_extension['digest'])
                from desktop.agent.code import VerificationProfile
                code.set_checks(project['id'], VerificationProfile(checks=[{
                    'name': 'Unit', 'command': 'python3 -m unittest discover -s tests -v', 'min_tests': 3}]))
                code.set_trusted(project['id'], True)
                task = await code.start(StartTask(project=project['id'], prompt='Fix normalization and document it; run the tests.', model_choice='local'))
                record = code.task(task['id'])
                sid = record['session']
                # The watcher can finish while view() awaits native messages,
                # leaving its earlier database snapshot briefly marked busy.
                view = await wait(pc, sid, lambda view: not view['busy'] and view['state'] != 'busy')
                assert view['state'] == 'idle', view
                messages = await pc.call('GET', f'/session/{sid}/message', **pc.scope(sid))
                calls = [part for message in messages for part in message['parts'] if part['type'] == 'tool']
                reminders = [part for part in calls if part['state']['status'] == 'error']
                assert len(reminders) == 1 and 'This edit has not run' in reminders[0]['state']['error'], reminders
                assert all(part['state']['status'] == 'completed' for part in calls if part not in reminders), calls
                assert any(p['tool'] == 'skill' and 'Inspect tests' in p['state']['output'] for p in calls), calls
                assert any('recipe/plugin' in p['state'].get('output', '') for p in calls), calls
                assert any('[reviewed hook executed]' in p['state'].get('output', '') for p in calls), calls
                checks = [p for p in calls if p['tool'] == 'bash' and 'unittest' in p['state']['input']['command']]
                assert [p['state']['metadata']['exit'] for p in checks] == [1, 1, 0], checks
                assert 'failures=3' in checks[0]['state']['output'], 'The premature edit must not have run'
                assert all('FRIDAY command exit code:' in p['state']['output'] for p in checks)
                assert 'Ran 3 tests' in checks[-1]['state']['output'], checks[-1]
                large = next(p for p in calls if p['tool'] == 'bash' and 'diagnostic line' in p['state']['input']['command'])
                assert len(large['state']['output']) < 16000, len(large['state']['output'])
                saved = Path(large['state']['metadata']['outputPath'])
                assert 'FINAL DIAGNOSTIC' in saved.read_text(), large
                assert 'exit code: 7' in calls[-1]['state']['output'], calls[-1]
                model_results = [str(m.get('content')) for body in Model.seen for m in body.get('messages', []) if m.get('role') == 'tool']
                assert any('exit code: 7' in result for result in model_results), 'Exit evidence did not reach the model'
                assert any('This edit has not run' in result for result in model_results), 'Baseline reminder did not reach the model'
                diff = await code.diff(task['id'])
                assert {f['path'] for f in diff['files']} == {'normalizer.py', 'README.md'}, diff
                # Independent execution validates the artifacts, not the fixture's closing prose.
                result, out, err = await code.run('python3', '-m', 'unittest', 'discover', '-s', 'tests', '-v', cwd=record['worktree'])
                assert result == 0 and 'Ran 3 tests' in out + err
                verified = (await code.view(task['id']))['verification']
                assert verified['required'][0]['state'] == 'passed', verified
                assert verified['state'] == 'passed', verified
                assert verified['receipts'][0]['tests'] == 3 and verified['receipts'][0]['current'], verified
                assert Path(verified['receipts'][0]['artifact']).is_file()
                # A same-size edit must invalidate persisted evidence without a new agent turn.
                readme = Path(record['worktree']) / 'README.md'
                original_readme = readme.read_text()
                readme.write_text(original_readme.replace('case', 'Case'))
                assert (await code.view(task['id']))['verification']['state'] == 'stale'
                readme.write_text(original_readme)
                checkpoint = (await code.view(task['id']))['checkpoints'][0]
                restored = await code.restore(task['id'], checkpoint['id'])
                assert 'return value\n' in Path(record['worktree'], 'normalizer.py').read_text()
                assert (await code.view(task['id']))['verification']['state'] == 'stale'
                await code.restore(task['id'], restored['backup'])
                assert (await code.view(task['id']))['verification']['state'] == 'passed'
                await pc.call('POST', f'/session/{sid}/summarize', json={'providerID': LOCAL[0], 'modelID': LOCAL[1]}, **pc.scope(sid))
                assert any('exact verification commands with observed exit codes' in json.dumps(body) for body in Model.seen), 'Coding handoff hook did not reach compaction'

                Model.steps = [
                    ('bash', {'command': 'python3 -m venv --without-pip .venv', 'description': 'Create a dependency-free local Python environment'}),
                    ('bash', {'command': 'python3 -m unittest discover -s tests -v', 'description': 'Verify after environment setup'})]
                Model.index = 0
                await code.setup.prepare(task['id'])
                await wait(pc, sid, lambda view: not view['busy'] and view['state'] != 'busy')
                assert Path(record['worktree'], '.venv/bin/python3').exists()
                assert code.task(task['id'])['verification_epoch'] == 1
                assert (await code.view(task['id']))['verification']['state'] == 'passed'

                # The same shell still asks in an untrusted project, and denial runs nothing.
                code.set_trusted(project['id'], False)
                Model.steps = [('bash', {'command': 'python3 -c "from pathlib import Path; Path(\'should-not-exist\').touch()"', 'description': 'Permission probe'})]
                Model.index = 0
                second = await code.start(StartTask(project=project['id'], prompt='Owned permission probe', model_choice='local'))
                second_record = code.task(second['id'])
                waiting = await wait(pc, second_record['session'], lambda view: bool(view['permissions']))
                await pc.reply(waiting['permissions'][0]['id'], Reply(reply='reject'))
                await wait(pc, second_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert not (Path(second_record['worktree']) / 'should-not-exist').exists()
                # A trusted project still cannot execute commands or edit files
                # while its task is explicitly in planning mode.
                code.set_trusted(project['id'], True)
                Model.steps = [('read', {'filePath': '$WORKTREE/README.md'}),
                    ('bash', {'command': 'touch plan-should-not-exist', 'description': 'Plan shell probe'}),
                    ('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Normalize text.', 'newString': 'Unauthorized plan edit.'})]
                Model.index = 0
                planned = await code.start(StartTask(project=project['id'], prompt='Inspect and plan only', model_choice='local', mode='plan'))
                planned_record = code.task(planned['id'])
                await wait(pc, planned_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert not Path(planned_record['worktree'], 'plan-should-not-exist').exists()
                assert 'Normalize text.' in Path(planned_record['worktree'], 'README.md').read_text()
                Model.steps = [('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Normalize text.', 'newString': 'Approved implementation.'})]
                Model.index = 0
                Model.directory = planned_record['worktree']
                await code.implement_plan(planned['id'])
                await wait(pc, planned_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert 'Approved implementation.' in Path(planned_record['worktree'], 'README.md').read_text()

                # Queue waits for an actual native turn, then dispatches once.
                Model.directory = planned_record['worktree']
                Model.steps = [('bash', {'command': 'sleep 1', 'description': 'Owned queue wait'})]
                Model.index = 0
                await code.follow_up(planned['id'], 'Run the queue wait fixture')
                code.control.enqueue(planned['id'], 'Apply the queued documentation change')
                await code.control.pump_once()
                assert code.control.view(planned['id'])['messages'][0]['state'] == 'queued'
                await wait(pc, planned_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                Model.steps = [('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Approved implementation.', 'newString': 'Queued implementation.'})]
                Model.index = 0
                await code.control.pump_once()
                await wait(pc, planned_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert code.control.view(planned['id'])['messages'][0]['state'] == 'sent'
                assert 'Queued implementation.' in Path(planned_record['worktree'], 'README.md').read_text()

                # Steer cancels an in-flight native command before sending a new turn.
                Model.steps = [('bash', {'command': 'sleep 10; touch cancelled-should-not-exist', 'description': 'Owned steer wait'})]
                Model.index = 0
                await code.follow_up(planned['id'], 'Run the steer wait fixture')
                await wait(pc, planned_record['session'], lambda view: any(i.get('kind') == 'tool' and i.get('status') == 'running' for i in view['items']))
                Model.steps = [('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Queued implementation.', 'newString': 'Steered implementation.'})]
                Model.index = 0
                await code.control.steer(planned['id'], 'Change direction to the owned documentation edit')
                await wait(pc, planned_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert not Path(planned_record['worktree'], 'cancelled-should-not-exist').exists()
                assert 'Steered implementation.' in Path(planned_record['worktree'], 'README.md').read_text()
                fork = await code.control.fork(planned['id'])
                assert fork['state'] == 'idle' and fork['parent'] == planned['id']
                fork_record = code.task(fork['id'])
                Model.directory = fork_record['worktree']
                Model.steps = [('edit', {'filePath': '$WORKTREE/README.md', 'oldString': 'Steered implementation.', 'newString': 'Forked implementation.'})]
                Model.index = 0
                await code.follow_up(fork['id'], 'Continue only in the fork')
                await wait(pc, fork_record['session'], lambda view: not view['busy'] and view['state'] != 'busy')
                assert 'Forked implementation.' in Path(fork_record['worktree'], 'README.md').read_text()
                assert 'Steered implementation.' in Path(planned_record['worktree'], 'README.md').read_text()
                await code.discard(planned['id'])
                earlier = await code.control.history(planned['id'])
                assert earlier['items'], 'Fork source history must survive discarding its worktree'
                print(json.dumps({'real_tool_loop': True, 'test_exits': [1, 1, 0], 'independent_tests': 3,
                    'multi_file_diff': True, 'bounded_output_preserved': True, 'silent_failure_visible_to_model': True,
                    'coding_compaction_context': True, 'first_turn_trust': True, 'denied_command_not_run': True,
                    'baseline_reminder_before_edit': True,
                    'durable_verification_and_invalidation': True,
                    'reviewed_profile_checks': True, 'recoverable_restore': True, 'plan_denies_writes_until_approved': True,
                    'skills_plugins_hooks_environment': True, 'setup_recipe_and_fresh_checks': True, 'durable_queue': True, 'stop_and_steer': True, 'independent_fork': True,
                    'model_decisions': 'scripted; no reasoning-quality claim'}))
        finally:
            await pc.stop()


if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try: asyncio.run(main())
    finally:
        server.shutdown()
        server.server_close()
