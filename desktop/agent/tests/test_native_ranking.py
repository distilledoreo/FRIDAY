"""Opt-in installed OpenCode integration: owned models/desktop, no actual inference/input."""
import os
from pathlib import Path
import subprocess
import sys
import unittest


@unittest.skipUnless(os.environ.get('FRIDAY_NATIVE_PC_TEST') == '1', 'Requires installed native OpenCode')
class NativeRankingTests(unittest.TestCase):
    def test_ranked_text_vision_routing_permissions_and_safe_refresh(self):
        fixture = Path(__file__).parent / 'fixtures/model_routing.py'
        repo = Path(__file__).resolve().parents[3]
        environment = dict(os.environ, PYTHONPATH=str(repo))
        result = subprocess.run([sys.executable,str(fixture)],cwd=repo,env=environment,capture_output=True,text=True,timeout=200)
        self.assertEqual(result.returncode,0,result.stderr[-3000:])
        self.assertIn('"rejected_input_absent": true',result.stdout)
        self.assertIn('"active_refresh_deferred": true',result.stdout)
