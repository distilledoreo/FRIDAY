from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest

from desktop.agent.approvals import Approvals


class ApprovalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'agent.sqlite'
        self.store = Approvals(self.path)
        self.task = self.store.propose_task('Research batteries', ['Read public sources', 'Report'])

    def tearDown(self): self.temp.cleanup()

    def running(self):
        self.store.approve_task(self.task['id'], self.task['fingerprint'])
        self.store.start_task(self.task['id'], self.task['fingerprint'])

    def action(self):
        return self.store.propose_action(self.task['id'], 'send', 'recipient@example.com', {'subject': 'Result', 'body': 'Reviewed'})

    def test_task_cannot_start_without_exact_explicit_approval(self):
        with self.assertRaises(ValueError): self.store.start_task(self.task['id'], self.task['fingerprint'])
        with self.assertRaises(ValueError): self.store.approve_task(self.task['id'], 'wrong')
        self.running()
        self.assertEqual(self.store.tasks()[0]['status'], 'running')

    def test_task_approval_does_not_approve_outgoing_action(self):
        self.running()
        action = self.action()
        with self.assertRaises(ValueError): self.store.claim_action(action['id'], action['action'])
        self.store.approve_action(action['id'], action['fingerprint'])
        altered = dict(action['action'], destination='different@example.com')
        with self.assertRaises(ValueError): self.store.claim_action(action['id'], altered)
        self.store.claim_action(action['id'], action['action'])
        with self.assertRaises(ValueError): self.store.claim_action(action['id'], action['action'])

    def test_restart_preserves_approval_and_cancellation_revokes_it(self):
        self.running()
        action = self.action()
        self.store.approve_action(action['id'], action['fingerprint'])
        restarted = Approvals(self.path)
        self.assertEqual(restarted.tasks()[0]['status'], 'running')
        restarted.cancel_task(self.task['id'])
        with self.assertRaises(ValueError): restarted.claim_action(action['id'], action['action'])

    def test_only_one_concurrent_claim_can_execute(self):
        self.running()
        action = self.action()
        self.store.approve_action(action['id'], action['fingerprint'])
        def claim(_):
            try:
                self.store.claim_action(action['id'], action['action'])
                return True
            except ValueError: return False
        with ThreadPoolExecutor(max_workers=4) as pool:
            self.assertEqual(sum(pool.map(claim, range(4))), 1)

    def test_events_are_paged_and_database_is_private(self):
        self.running()
        events = self.store.events(self.task['id'], limit=1)
        next_events = self.store.events(self.task['id'], after=events[0]['seq'])
        self.assertEqual(len(next_events), 2)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError): self.store.events(self.task['id'], limit=10000)
