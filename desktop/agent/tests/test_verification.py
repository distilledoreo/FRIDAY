import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from desktop.agent.tests.test_code import git
from desktop.agent.verification import fingerprint, record, runner_result, view, profile_revision


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'; self.root.mkdir()
        self.store = Path(self.tmp.name) / 'receipts'
        git('init', '--quiet', '-b', 'main', cwd=self.root)
        (self.root / 'app.py').write_text('before\n')
        (self.root / '.gitignore').write_text('cache/\n')
        git('add', '.', cwd=self.root)
        git('commit', '--quiet', '-m', 'Base', cwd=self.root)

    def receipt(self, **kwargs):
        data = dict(session='session/../../invalid', command='python3 -m unittest', worktree=str(self.root),
                    before=fingerprint(self.root), exit=0, output='Ran 3 tests in 0.001s\n\nOK\n')
        data.update(kwargs)
        return record(self.store, data)

    def state(self): return view(self.store, 'session/../../invalid', self.root)

    def test_persisted_receipt_becomes_stale_after_same_size_edit_and_recovers_on_rerun(self):
        receipt = self.receipt()
        self.assertEqual(self.state()['state'], 'passed')
        self.assertEqual(json.loads(Path(receipt['artifact']).with_suffix('.json').read_text())['tests'], 3)
        self.assertEqual(Path(receipt['artifact']).stat().st_mode & 0o777, 0o600)
        (self.root / 'app.py').write_text('after!\n')
        self.assertEqual(self.state()['state'], 'stale')
        self.receipt()
        self.assertEqual(self.state()['state'], 'passed')
        self.assertFalse(self.state()['receipts'][-1]['current'])

    def test_untracked_files_modes_deletion_index_and_head_change_identity(self):
        for change in [lambda: (self.root / 'new.py').write_text('new'),
                       lambda: (self.root / 'app.py').chmod(0o755),
                       lambda: git('add', '.', cwd=self.root),
                       lambda: git('commit', '--quiet', '-m', 'Changed', cwd=self.root),
                       lambda: (self.root / 'app.py').unlink()]:
            before = fingerprint(self.root)['tree']; change()
            self.assertIsNotNone(before)
            self.assertNotEqual(before, fingerprint(self.root)['tree'])

    def test_deleting_a_tracked_directory_keeps_fingerprinting_available(self):
        (self.root / 'nested').mkdir()
        (self.root / 'nested/source.py').write_text('source')
        git('add', '.', cwd=self.root)
        before = fingerprint(self.root)['tree']
        (self.root / 'nested/source.py').unlink()
        (self.root / 'nested').rmdir()
        self.assertIsNotNone(fingerprint(self.root)['tree'])
        self.assertNotEqual(fingerprint(self.root)['tree'], before)

    def test_changed_during_check_unknown_and_failed_results_never_pass(self):
        before = fingerprint(self.root)
        (self.root / 'app.py').write_text('changed')
        self.receipt(before=before)
        self.assertNotEqual(self.state()['state'], 'passed')
        self.receipt(exit=1, output='Ran 3 tests in 0.01s\nFAILED (failures=1)\n')
        self.assertEqual(self.state()['state'], 'failed')
        self.receipt(output='silent success')
        self.assertEqual(self.state()['state'], 'unverified')

    def test_ignored_environment_is_explicitly_outside_scope_and_special_files_fail_closed(self):
        before = fingerprint(self.root)['tree']
        (self.root / 'cache').mkdir(); (self.root / 'cache/data').write_text('ignored')
        self.assertEqual(before, fingerprint(self.root)['tree'])
        (self.root / 'app.py').unlink()
        os.mkfifo(self.root / 'app.py')
        self.assertIsNone(fingerprint(self.root)['tree'])
        (self.root / 'app.py').unlink()
        (self.root / 'app.py').write_text('before\n')
        (self.root / 'link').symlink_to('/does/not/exist')
        self.assertIsNotNone(fingerprint(self.root)['tree'])
        with patch('desktop.agent.verification.MAX_BYTES', 1): self.assertIsNone(fingerprint(self.root)['tree'])

    def test_runner_verdict_does_not_trust_exit_zero_alone(self):
        cases = [('Ran 3 tests in 0.1s\nFAILED (failures=3)\n', 0, 'failed'),
                 ('Ran 0 tests in 0.1s\nOK\n', 0, 'unconfirmed'),
                 ('Ran 3 tests in 0.1s\nOK (skipped=3)\n', 0, 'unconfirmed'),
                 ('3 passed in 0.1s', 0, 'passed'), ('2 failed, 3 passed in 0.1s', 0, 'failed'),
                 ('Ran 3 tests in 0.1s\nOK\n', None, 'unconfirmed')]
        for output, code, expected in cases:
            self.assertEqual(runner_result(output, code)['result'], expected)
        self.assertEqual(runner_result('3 passed in 0.1s', 0, True)['result'], 'unconfirmed')

    def test_missing_or_damaged_output_cannot_keep_a_passing_badge(self):
        receipt = self.receipt()
        Path(receipt['artifact']).write_text('different output')
        self.assertEqual(self.state()['state'], 'unverified')
        Path(receipt['artifact']).unlink()
        self.assertEqual(self.state()['state'], 'unverified')
        Path(receipt['artifact']).with_suffix('.json').write_text('{}')
        self.assertEqual(self.state()['state'], 'unverified')

    def test_check_in_another_working_directory_is_not_current_evidence(self):
        self.receipt(cwd=self.tmp.name)
        self.assertEqual(self.state()['state'], 'unverified')
        self.assertFalse(self.state()['receipts'][0]['current'])

    def test_required_checks_need_exact_command_directory_revision_and_minimum_tests(self):
        checks = [dict(name='Unit', command='python3 -m unittest', cwd='.', runner='tests', min_tests=3),
                  dict(name='Compile', command='python3 -m compileall -q .', cwd='.', runner='exit', min_tests=1)]
        def state(): return view(self.store, 'session/../../invalid', self.root, checks)
        self.receipt()  # An older ad hoc pass must not satisfy a reviewed profile.
        self.assertEqual(state()['required'][0]['state'], 'missing')
        revision = profile_revision(checks)
        self.receipt(profile_revision=revision)
        self.assertEqual(state()['state'], 'unverified')
        self.receipt(command=checks[1]['command'], output='', profile_revision=revision)
        self.assertEqual(state()['state'], 'passed')
        self.receipt(output='Ran 2 tests in 0.01s\nOK\n', profile_revision=revision)
        self.assertEqual(state()['required'][0]['state'], 'unconfirmed')
        self.receipt(profile_revision=revision)
        (self.root / 'app.py').write_text('after!\n')
        self.assertEqual(state()['state'], 'stale')
        checks[0]['min_tests'] = 4
        self.assertTrue(all(r['state'] == 'missing' for r in state()['required']))

    def test_profile_build_exit_does_not_override_recognized_failure_or_truncation(self):
        checks = [dict(name='Build', command='build-it', cwd='.', runner='exit', min_tests=1)]
        for output, truncated in [('2 failed, 3 passed in 0.1s', False), ('success', True)]:
            self.receipt(command='build-it', output=output, truncated=truncated, profile_revision=profile_revision(checks))
            self.assertNotEqual(view(self.store, 'session/../../invalid', self.root, checks)['state'], 'passed')

    def test_terminal_stderr_merge_matches_but_exit_masks_pipelines_and_arguments_do_not(self):
        checks = [dict(name='Unit', command='python3 -m unittest', cwd='.', runner='tests', min_tests=3)]
        revision = profile_revision(checks)
        for suffix in (' | tail -n 10', ' || true', '; echo done', ' > result.txt', ' -k easier'):
            self.receipt(command='set -o pipefail; python3 -m unittest' + suffix, profile_revision=revision)
            self.assertEqual(view(self.store, 'session/../../invalid', self.root, checks)['required'][0]['state'], 'missing')
        self.receipt(command='set -o pipefail; python3 -m unittest 2>&1', profile_revision=revision)
        self.assertEqual(view(self.store, 'session/../../invalid', self.root, checks)['state'], 'passed')

    def test_environment_changes_and_setup_epochs_require_new_evidence_even_with_identical_files(self):
        checks = [dict(name='Unit', command='python3 -m unittest', cwd='.', runner='tests', min_tests=3)]
        env = {'MODE': 'test'}
        self.receipt(profile_revision=profile_revision(checks, env))
        self.assertEqual(view(self.store, 'session/../../invalid', self.root, checks, environment=env)['state'], 'passed')
        self.assertNotEqual(view(self.store, 'session/../../invalid', self.root, checks, environment={'MODE': 'other'})['state'], 'passed')
        self.assertNotEqual(view(self.store, 'session/../../invalid', self.root, checks, environment=env, epoch=1)['state'], 'passed')
        self.receipt(profile_revision=profile_revision(checks, env, 1))
        self.assertEqual(view(self.store, 'session/../../invalid', self.root, checks, environment=env, epoch=1)['state'], 'passed')
