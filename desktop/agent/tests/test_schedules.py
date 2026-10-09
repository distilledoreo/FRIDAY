import concurrent.futures
from pathlib import Path
import tempfile
import time
import unittest
from desktop.agent.approvals import Approvals


class ScheduleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Approvals(Path(self.temp.name) / 'agent.sqlite')
        self.due = time.time() + 100

    def tearDown(self): self.temp.cleanup()

    def proposal(self, interval=900, count=3):
        return self.store.propose_task('Check public research', ['Read', 'Report'],
            {'run_at': self.due, 'interval_seconds': interval, 'max_runs': count, 'timezone': 'America/New_York'})

    def finish(self, run):
        self.store.start_task(run['id'], run['fingerprint'])
        self.store.finish_task(run['id'], run['fingerprint'])

    def test_nothing_runs_without_exact_schedule_approval(self):
        task = self.proposal()
        self.assertEqual(self.store.claim_due(self.due + 1), [])
        with self.assertRaises(ValueError): self.store.approve_task(task['id'], '0' * 64)
        self.store.approve_task(task['id'], task['fingerprint'])
        self.assertEqual(self.store.task(task['id'])['status'], 'scheduled')
        self.assertEqual(self.store.claim_due(self.due - 1), [])
        run = self.store.claim_due(self.due)[0]
        origin = self.store.task(run['id'])['proposal']['schedule_origin']
        self.assertEqual(origin['approved_fingerprint'], task['fingerprint'])
        self.assertEqual(self.store.runs(task['id'])[0]['id'], run['id'])
        self.assertEqual(self.store.claim_due(self.due), [])

    def test_concurrent_claimers_do_not_duplicate_runs(self):
        task = self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.store.claim_due(self.due), range(4)))
        self.assertEqual(sum(map(len, results)), 1)

    def test_missed_intervals_skip_bursts_and_run_count_is_bounded(self):
        task = self.proposal(count=2)
        self.store.approve_task(task['id'], task['fingerprint'])
        run = self.store.claim_due(self.due + 9050)[0]
        self.assertEqual(self.store.task(task['id'])['schedule_state']['next_run'], self.due + 9900)
        self.assertEqual(self.store.claim_due(self.due + 18000), [])  # No overlapping work.
        self.finish(run)
        run2 = self.store.claim_due(self.due + 18000)[0]
        self.assertEqual(self.store.task(task['id'])['status'], 'scheduled')
        self.finish(run2)
        self.assertEqual(self.store.claim_due(self.due + 20000), [])
        self.assertEqual(self.store.task(task['id'])['status'], 'done')

    def test_cancel_schedule_revokes_running_child_and_actions(self):
        task = self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        run = self.store.claim_due(self.due)[0]
        self.store.start_task(run['id'], run['fingerprint'])
        self.store.propose_action(run['id'], 'send', 'synthetic@example.com', {'text': 'Draft'})
        self.assertEqual(self.store.cancel_task(task['id']), [run['id']])
        self.assertEqual(self.store.task(run['id'])['status'], 'cancelled')
        self.assertEqual(self.store.actions(run['id'])[0]['status'], 'cancelled')
        self.assertEqual(self.store.claim_due(self.due + 9999), [])

    def test_restart_does_not_replay_uncertain_run(self):
        task = self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        run = self.store.claim_due(self.due)[0]
        self.store.start_task(run['id'], run['fingerprint'])
        restored = Approvals(self.store.path)
        restored.interrupt_running()
        self.assertEqual(restored.task(run['id'])['status'], 'interrupted')
        self.assertEqual(restored.task(task['id'])['status'], 'scheduled')
        self.assertEqual(restored.claim_due(self.due + 99999), [])

    def test_invalid_schedule_rejected(self):
        cases = [dict(run_at=float('nan')), dict(run_at=time.time()-1), dict(interval_seconds=30),
                 dict(max_runs=101), dict(timezone='invalid'), dict(interval_seconds=0, max_runs=2)]
        base = dict(run_at=self.due, interval_seconds=900, max_runs=3, timezone='UTC')
        for changes in cases:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.store.propose_task('Check', ['Read'], base | changes)
