"""Exercise the actual JavaScript hooks without model inference or npm dependencies."""
from pathlib import Path
import shutil
import subprocess
import unittest

from desktop.agent.tests.fixtures.local_coding import test_evidence as coding_evidence


class LocalCodingEvidenceTests(unittest.TestCase):
    def test_recovery_and_check_order_are_verified_from_runner_output(self):
        def check(output):
            return {'tool': 'bash', 'state': {'status': 'completed', 'input': {'command': 'python3 -m unittest'},
                                             'metadata': {'exit': 0}, 'output': output}}
        missing = check('python: command not found')
        failed = check('Ran 3 tests in 0.001s\n\nFAILED (failures=3)\nEXIT: 1\n')
        passed = check('Ran 3 tests in 0.001s\n\nOK\nEXIT: 0\n')
        edit = {'tool': 'write', 'state': {'status': 'completed', 'input': {'filePath': '/repo/normalizer.py'}}}
        evidence = coding_evidence([missing, failed, edit, passed])
        self.assertTrue(evidence['baseline_reproduced_before_edit'])
        self.assertTrue(evidence['verified_after_last_edit'])
        self.assertEqual(evidence['agent_test_results'][0], {'tests': None, 'result': 'unknown'})
        for calls, baseline, final in (([edit, failed, passed], False, True),
                                        ([failed, edit, passed, edit], True, False),
                                        ([failed, edit, check('Ran 0 tests in 0.000s\n\nOK\n')], True, False),
                                        ([failed, edit, passed, missing], True, False)):
            with self.subTest(calls=calls):
                evidence = coding_evidence(calls)
                self.assertEqual(evidence['baseline_reproduced_before_edit'], baseline)
                self.assertEqual(evidence['verified_after_last_edit'], final)


@unittest.skipUnless(shutil.which('node'), 'Requires Node (also required by native OpenCode)')
class CodeWorkflowTests(unittest.TestCase):
    def test_hooks(self):
        fixture = Path(__file__).parent / 'fixtures/code_workflow.test.mjs'
        result = subprocess.run(['node', '--test', str(fixture)], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout[-5000:] + result.stderr[-1000:])
