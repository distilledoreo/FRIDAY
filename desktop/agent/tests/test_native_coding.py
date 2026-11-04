"""Opt-in real OpenCode coding workflow, with deterministic local model responses."""
import os
from pathlib import Path
import subprocess
import sys
import unittest


@unittest.skipUnless(os.environ.get('FRIDAY_NATIVE_PC_TEST') == '1', 'Requires installed native OpenCode')
class NativeCodingTests(unittest.TestCase):
    def test_explore_edit_debug_verify_and_permissions(self):
        fixture = Path(__file__).parent / 'fixtures/coding_workflow.py'
        repo = Path(__file__).resolve().parents[3]
        result = subprocess.run([sys.executable, str(fixture)], cwd=repo, env=dict(os.environ, PYTHONPATH=str(repo)),
                                capture_output=True, text=True, timeout=220)
        self.assertEqual(result.returncode, 0, result.stdout[-1000:] + result.stderr[:5000])
        self.assertIn('"test_exits": [1, 1, 0]', result.stdout)
        self.assertIn('"denied_command_not_run": true', result.stdout)
