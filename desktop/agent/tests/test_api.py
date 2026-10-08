import asyncio
from pathlib import Path
import tempfile
import unittest
import base64
import time

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from desktop.agent.api import install


class AgentApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = FastAPI()
        def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer test': raise HTTPException(401)
        self.calls = []
        self.release = asyncio.Event()
        async def engine(task, report):
            self.calls.append(task['id'])
            await report('step', {'text': 'Read source'})
            await self.release.wait()
            return {'text': 'Finished'}
        self.store = install(self.app, [Depends(auth)], self.temp.name, engine)
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://test', headers={'Authorization': 'Bearer test'})

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.lifespan.__aexit__(None, None, None)
        self.temp.cleanup()

    async def proposal(self):
        response = await self.client.post('/workspace/agent/tasks', json={'prompt': 'Research', 'plan': ['Read public sources', 'Report']})
        self.assertEqual(response.status_code, 200)
        return response.json()

    async def test_auth_required_and_proposal_does_not_execute(self):
        response = await self.client.get('/workspace/agent/tasks', headers={'Authorization': ''})
        self.assertEqual(response.status_code, 401)
        await self.proposal()
        self.assertEqual(self.calls, [])
        self.assertIsNone((await self.client.get('/workspace/agent/tasks/' + (await self.proposal())['id'] + '/report')).json()['result'])

    async def test_approval_executes_once_and_result_is_in_paged_activity(self):
        task = await self.proposal()
        path = '/workspace/agent/tasks/' + task['id']
        response = await self.client.post(path + '/approve', json={'fingerprint': task['fingerprint']})
        self.assertEqual(response.status_code, 200)
        await asyncio.sleep(.01)
        duplicate = await self.client.post(path + '/approve', json={'fingerprint': task['fingerprint']})
        self.assertEqual(duplicate.status_code, 409)
        self.assertEqual(len(self.calls), 1)
        self.release.set()
        await asyncio.sleep(.01)
        detail = (await self.client.get(path)).json()
        self.assertEqual(detail['status'], 'done')
        self.assertEqual(detail['result']['text'], 'Finished')
        report = (await self.client.get(path + '/report')).json()
        self.assertEqual(report['result']['text'], 'Finished')
        self.assertEqual((await self.client.get(path + '/report', headers={'Authorization': ''})).status_code, 401)
        self.assertEqual((await self.client.get('/workspace/agent/tasks/unknown/report')).status_code, 409)
        events = (await self.client.get(path + '/events')).json()
        self.assertIn('result', [event['kind'] for event in events])
        self.assertEqual((await self.client.get(path + '/events', params={'after': events[-1]['seq']})).json(), [])

    async def test_cancellation_reaches_running_engine(self):
        task = await self.proposal()
        path = '/workspace/agent/tasks/' + task['id']
        await self.client.post(path + '/approve', json={'fingerprint': task['fingerprint']})
        await asyncio.sleep(.01)
        await self.client.post(path + '/cancel')
        self.release.set()
        await asyncio.sleep(.01)
        self.assertEqual(self.store.task(task['id'])['status'], 'cancelled')
        self.assertNotIn('result', [e['kind'] for e in self.store.events(task['id'])])

    async def test_outgoing_review_is_read_only_authenticated_and_does_not_grant_approval(self):
        task = await self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        self.store.start_task(task['id'], task['fingerprint'])
        action = self.store.propose_action(task['id'], 'send', 'test@example.com', {'account_id':'a'*32,'to':'other@example.com','subject':'Draft','body':'Hello'})
        path = '/workspace/agent/actions/' + action['id']
        response = await self.client.get(path + '/review')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['fingerprint'], action['fingerprint'])
        self.assertFalse(response.json()['allowed'])
        self.assertFalse(response.json()['executable'])
        self.assertEqual((await self.client.get(path + '/review', headers={'Authorization':''})).status_code, 401)
        self.assertEqual((await self.client.post(path + '/approve', json={'fingerprint':action['fingerprint']})).status_code, 503)
        self.assertEqual(self.store.actions(task['id'])[0]['status'], 'proposed')
        accounts = (await self.client.get('/workspace/agent/accounts')).json()
        self.assertEqual(accounts['accounts'], [])

    async def test_approved_schedule_runs_without_granting_action_approval(self):
        other = FastAPI()
        calls = []
        async def engine(task, report):
            calls.append(task['id'])
            return {'text': 'Scheduled synthetic result'}
        store = install(other, [], Path(self.temp.name) / 'scheduled', engine, poll_seconds=.02)
        async with other.router.lifespan_context(other):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=other), base_url='http://test') as client:
                task = (await client.post('/workspace/agent/tasks', json={'prompt': 'Check', 'plan': ['Read'],
                    'schedule': {'run_at': time.time() + .1, 'timezone': 'UTC'}})).json()
                await client.post('/workspace/agent/tasks/' + task['id'] + '/approve', json={'fingerprint': task['fingerprint']})
                self.assertEqual(calls, [])
                await asyncio.sleep(.3)
                self.assertEqual(len(calls), 1)
                self.assertEqual(store.task(task['id'])['status'], 'done')
                self.assertEqual(store.result(calls[0])['text'], 'Scheduled synthetic result')
                self.assertEqual(store.actions(calls[0]), [])

    async def test_unconfigured_engine_never_records_approval(self):
        other = FastAPI()
        store = install(other, [], Path(self.temp.name) / 'disabled')
        task = store.propose_task('Task', ['Read'])
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=other), base_url='http://test') as client:
            response = await client.post('/workspace/agent/tasks/' + task['id'] + '/approve', json={'fingerprint': task['fingerprint']})
            self.assertEqual(response.status_code, 503)
        self.assertEqual(store.task(task['id'])['status'], 'proposed')

    async def test_restart_marks_uncertain_work_without_replay(self):
        task = self.store.propose_task('Task', ['Read'])
        self.store.approve_task(task['id'], task['fingerprint'])
        self.store.start_task(task['id'], task['fingerprint'])
        self.store.interrupt_running()
        self.assertEqual(self.store.task(task['id'])['status'], 'interrupted')
        self.assertEqual(self.calls, [])

    async def test_screenshots_are_authenticated_task_scoped_and_not_cached(self):
        task = await self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        self.store.start_task(task['id'], task['fingerprint'])
        encoded = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4WQAAAAASUVORK5CYII='
        shot = self.store.save_screenshot(task['id'], 'https://example.com', encoded)
        path = '/workspace/agent/tasks/' + task['id'] + '/screenshots/' + shot['id']
        response = await self.client.get(path)
        self.assertEqual(response.content, base64.b64decode(encoded))
        self.assertEqual(response.headers['cache-control'], 'no-store')
        self.assertEqual((await self.client.get(path, headers={'Authorization': ''})).status_code, 401)
        other = await self.proposal()
        self.assertEqual((await self.client.get('/workspace/agent/tasks/' + other['id'] + '/screenshots/' + shot['id'])).status_code, 409)
        with self.assertRaises(ValueError): self.store.save_screenshot(task['id'], 'https://example.com', base64.b64encode(b'not a PNG').decode())

    async def test_unconnected_outgoing_action_never_records_approval(self):
        task = await self.proposal()
        self.store.approve_task(task['id'], task['fingerprint'])
        self.store.start_task(task['id'], task['fingerprint'])
        action = self.store.propose_action(task['id'], 'send', 'person@example.com', {'text': 'Example draft'})
        response = await self.client.post('/workspace/agent/actions/' + action['id'] + '/approve', json={'fingerprint': action['fingerprint']})
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.store.actions(task['id'])[0]['status'], 'proposed')
