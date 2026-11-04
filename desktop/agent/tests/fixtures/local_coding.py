"""Opt-in local-model smoke evaluation through an existing FRIDAY private proxy.

Run as a module with --proxy-config pointing to the running PC helper's opencode.json.
It reads only the local provider options, uses the existing GPU gate, and writes to
a temporary synthetic repository. No service restart, cloud model, push or deployment.
Model behavior is not scripted. This small case is not a general coding benchmark.
"""
import argparse
import asyncio
import contextlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from unittest.mock import patch

from desktop.agent.code import CodeProjects, StartTask, VerificationProfile
from desktop.agent.pc import LOCAL, PcAgent, Settings
from desktop.agent.tests.fixtures.coding_workflow import port
from desktop.agent.tests.test_code import FAKE_GH, IDENTITY, git


class LocalPc(PcAgent):
    def __init__(self, root, options):
        super().__init__(root, port=port())
        self.local_options = options

    async def models(self): return [LOCAL]

    def config(self, chain):
        config = super().config(chain)
        provider = config['provider'][LOCAL[0]]
        provider['options'] = self.local_options
        config['provider'] = {LOCAL[0]: provider}
        config['enabled_providers'] = [LOCAL[0]]
        config['mcp'] = {}
        # PcAgent expects this empty registry when starting the helper.
        config['provider']['friday-vision'] = {'models': {}}
        return config


def test_evidence(calls, source='normalizer.py', expected_tests=3):
    """Separate runner results from shell status and require checks around edits.

    Missing interpreters/runners are recovery attempts, not executed test suites.
    This fixture recognizes native file edits; opaque shell writes cannot establish
    the ordering evidence required for a pass.
    """
    checks, exits, indexes = [], [], []
    edits, source_edits = [], []
    for index, part in enumerate(calls):
        state = part.get('state', {})
        args = state.get('input', {})
        if part['tool'] in ('edit', 'write', 'apply_patch') and state.get('status') == 'completed':
            edits.append(index)
            if Path(args.get('filePath', '')).name == source: source_edits.append(index)
        if part['tool'] != 'bash' or 'unittest' not in args.get('command', ''): continue
        output = state.get('output', '')
        count = re.search(r'(?m)^Ran (\d+) tests? in ', output)
        verdict = 'failed' if re.search(r'(?m)^FAILED \(', output) else 'passed' if re.search(r'(?m)^OK$', output) else 'unknown'
        checks.append({'tests': int(count[1]) if count else None, 'result': verdict})
        exits.append(state.get('metadata', {}).get('exit'))
        indexes.append(index)
    baseline = any(check == {'tests': expected_tests, 'result': 'failed'} and source_edits and index < min(source_edits)
                   for check, index in zip(checks, indexes))
    verified = bool(checks and edits and checks[-1] == {'tests': expected_tests, 'result': 'passed'} and
                    exits[-1] == 0 and indexes[-1] > max(edits))
    return {'agent_test_exits': exits, 'agent_test_results': checks,
            'baseline_reproduced_before_edit': baseline, 'verified_after_last_edit': verified}


async def evaluate(options, timeout, trusted=False, case='normalizer'):
    with tempfile.TemporaryDirectory(prefix='friday-local-coding-') as tmp:
        root = Path(tmp)
        seed = root / 'seed'
        seed.mkdir()
        git('init', '--quiet', '-b', 'main', cwd=seed)
        (seed / 'normalizer.py').write_text('def normalize(value):\n    return value\n')
        (seed / 'README.md').write_text('# Normalizer\nNormalize text.\n')
        (seed / '.gitignore').write_text('__pycache__/\n')
        (seed / 'tests').mkdir()
        (seed / 'tests/test_normalizer.py').write_text(
            'import unittest\nfrom normalizer import normalize\n\nclass NormalizeTest(unittest.TestCase):\n'
            '    def test_case(self): self.assertEqual(normalize("FOO"), "foo")\n'
            '    def test_space(self): self.assertEqual(normalize("  Foo  BAR "), "foo bar")\n'
            '    def test_empty(self): self.assertEqual(normalize("  "), "")\n')
        source, count = 'normalizer.py', 3
        task_prompt = ('Fix the normalize function so it lowercases text, trims leading/trailing whitespace and collapses '
                       'all internal whitespace runs to one space. Empty or whitespace-only input must return an empty string. ')
        edge_command = 'from normalizer import normalize; assert normalize("\\tA\\n B\\rC ") == "a b c"; assert normalize("") == ""'
        if case == 'pagination':
            (seed / 'normalizer.py').unlink()
            (seed / 'tests/test_normalizer.py').unlink()
            source, count = 'pagination.py', 5
            (seed / source).write_text('def page(items, number, size):\n    return list(items)[:size]\n')
            (seed / 'README.md').write_text('# Pagination\nPage a sequence.\n')
            (seed / 'tests/test_pagination.py').write_text(
                'import unittest\nfrom pagination import page\n\nclass PageTest(unittest.TestCase):\n'
                '    def test_first(self): self.assertEqual(page([1,2,3,4], 1, 2), [1,2])\n'
                '    def test_second(self): self.assertEqual(page([1,2,3,4], 2, 2), [3,4])\n'
                '    def test_beyond(self): self.assertEqual(page([1,2], 3, 2), [])\n'
                '    def test_invalid_number(self):\n        with self.assertRaises(ValueError): page([1], 0, 2)\n'
                '    def test_invalid_size(self):\n        with self.assertRaises(ValueError): page([1], 1, 0)\n')
            task_prompt = ('Fix page(items, number, size): use one-based page numbers and return the requested page as a list. '
                           'Require positive integer number and size, raising ValueError for invalid values. Return [] beyond the end. ')
            edge_command = 'from pagination import page; assert page(range(8), 2, 3) == [3,4,5]; assert page([], 1, 2) == []'
        git('add', '-A', cwd=seed)
        git('commit', '--quiet', '-m', 'Fixture base', cwd=seed)
        origin = root / 'origin.git'
        git('clone', '--quiet', '--bare', str(seed), str(origin), cwd=root)
        gh = root / 'gh'
        gh.write_text(FAKE_GH)
        gh.chmod(0o700)
        pc = LocalPc(root / 'state', options)
        pc.save_settings(Settings(computer_use=False, allow=['python3 -m unittest', 'python3 -m unittest *',
                                                          'python -m unittest', 'python -m unittest *']).model_dump())
        code = CodeProjects(root / 'state', pc, workspace=root / 'work', gh=str(gh))
        started = time.monotonic()
        sid = None
        try:
            with patch.dict(os.environ, {**IDENTITY, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                                        'FAKE_ORIGIN': str(origin), 'GH_LOG': str(root / 'gh.log')}):
                project = await code.add_local(str(seed)) if case == 'pagination' else await code.add('octo/demo')
                code.set_checks(project['id'], VerificationProfile(checks=[dict(name='Unit',
                    command='python3 -m unittest discover -s tests -v', min_tests=count)]))
                if trusted: code.set_trusted(project['id'], True)
                task = await code.start(StartTask(project=project['id'], model_choice='local', prompt=task_prompt +
                    'Inspect this unfamiliar repository and its tests, reproduce the failure, implement the fix, document '
                    'the behavior in README.md, and run the tests. Use existing tools and the standard library. Do not '
                    'change the existing tests, install anything, commit, push, or access files outside this worktree. '
                    'Finish with the test command and observed result.'))
                record = code.task(task['id'])
                sid = record['session']
                while True:
                    view = await pc.view(sid)
                    if view['permissions'] or view['questions']:
                        return {'passed': False, 'state': 'waiting', 'seconds': round(time.monotonic() - started, 1),
                                'permissions': [{key: item.get(key) for key in ('kind', 'detail', 'patterns')} for item in view['permissions']],
                                'questions': len(view['questions'])}
                    if not view['busy'] and view['state'] != 'busy': break
                    if time.monotonic() - started > timeout: raise TimeoutError('Local coding evaluation deadline exceeded')
                    await asyncio.sleep(1)
                messages = await pc.call('GET', f'/session/{sid}/message', **pc.scope(sid))
                calls = [p for m in messages for p in m['parts'] if p['type'] == 'tool']
                changes = await code.diff(task['id'])
                verification = (await code.view(task['id']))['verification']
                result, out, err = await code.run('python3', '-m', 'unittest', 'discover', '-s', 'tests', '-v', cwd=record['worktree'], check=False)
                edge, _, _ = await code.run('python3', '-c', edge_command,
                    cwd=record['worktree'], check=False)
                report = {'case': case, 'state': view['state'], 'seconds': round(time.monotonic() - started, 1),
                    'trusted_project': trusted,
                    'verification_state': verification['state'],
                    'required_checks': verification['required'],
                    'recorded_commands': [dict(command=r['command'], result=r['result'], current=r['current']) for r in verification['receipts']],
                    'current_receipt_tests': [r['tests'] for r in verification['receipts'] if r['current']],
                    'model_choice': view['model_choice'], 'tool_calls': len(calls),
                    'errors': [item['text'][-500:] for item in view['items'] if item['kind'] == 'error'],
                    **test_evidence(calls, source, count), 'independent_tests_passed': result == 0 and f'Ran {count} tests' in out + err,
                    'independent_edge_cases_passed': edge == 0,
                    'changed_files': sorted(f['path'] for f in changes['files']), 'commits': changes['commits']}
                report['passed'] = (report['state'] == 'idle' and verification['state'] == 'passed' and report['independent_tests_passed'] and edge == 0 and
                    report['baseline_reproduced_before_edit'] and report['verified_after_last_edit'] and
                    report['changed_files'] == ['README.md', source] and report['commits'] == 0)
                return report
        finally:
            if sid:
                with contextlib.suppress(Exception): await pc.cancel(sid)
            await pc.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proxy-config', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--trusted-project', action='store_true', help='Trust only this temporary synthetic project for build/test and edge-case commands')
    parser.add_argument('--case', choices=['normalizer', 'pagination', 'all'], default='normalizer')
    args = parser.parse_args()
    config = json.loads(args.proxy_config.read_text())
    options = config['provider'][LOCAL[0]]['options']
    if options.get('baseURL') != 'http://127.0.0.1:8700/workspace/pc/internal/v1':
        raise SystemExit('Expected the existing loopback FRIDAY private proxy')
    async def run_cases():
        results = []
        for case in (['normalizer', 'pagination'] if args.case == 'all' else [args.case]):
            try: result = await evaluate({'baseURL': options['baseURL'], 'apiKey': options['apiKey']}, args.timeout, args.trusted_project, case)
            except Exception as error: result = {'case': case, 'passed': False, 'error': type(error).__name__}
            results.append(result)
            print(json.dumps({'completed_case': case, 'passed': result['passed']}), flush=True)
        return {'cases': results, 'passed': all(result['passed'] for result in results)}
    result = asyncio.run(run_cases())
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
