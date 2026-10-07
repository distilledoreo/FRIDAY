import asyncio
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException

spec = importlib.util.spec_from_file_location('workspace', Path(__file__).parents[1] / 'workspace.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.workspace = module.Workspace(self.temp.name)
    def tearDown(self):
        self.temp.cleanup()
    def test_revision_conflicts_and_tombstones_survive_restart(self):
        w = self.workspace
        w.sync('chat-1', 0, {'text': 'phone'})
        with self.assertRaises(HTTPException) as conflict:
            w.sync('chat-1', 0, {'text': 'stale'})
        self.assertEqual(conflict.exception.status_code, 409)
        self.assertEqual(w.items()[0]['payload']['text'], 'phone')
        w.sync('chat-1', 1, {}, True)
        item = module.Workspace(self.temp.name).items()[0]
        self.assertTrue(item['deleted'])
        self.assertEqual(item['revision'], 2)
    def test_upload_names_and_unknown_files(self):
        uploaded = self.workspace.put_file(b'hello', '../../my%20file.txt')
        self.assertEqual(uploaded['name'], 'my file.txt')
        with self.assertRaises(HTTPException): self.workspace.file('../workspace.sqlite')
    def test_python_cannot_see_host_or_network_and_can_create_workbook(self):
        info = self.workspace.put_file(b'value\n2\n3\n', 'input.csv')
        result = self.workspace.analyze('''import os, socket, glob
+import pandas as pd
+assert not os.path.exists('/home/user/assistant-server/.env')
+try:
+    socket.create_connection(('127.0.0.1',8700), timeout=1)
+    raise AssertionError('host network accessible')
+except OSError: pass
+df = pd.read_csv(glob.glob('/work/inputs/*')[0])
+print('sum:', int(df.value.sum()))
+df.to_excel('/work/result.xlsx', index=False)
+os.symlink('/usr/bin/python3', '/work/unsafe.txt')
+'''.replace('\n+', '\n'), [info['id']])
        self.assertTrue(result['success'], result['output'])
        self.assertIn('sum: 5', result['output'])
        self.assertEqual([f['name'] for f in result['artifacts']], ['result.xlsx'])
        self.assertGreater(self.workspace.file(result['artifacts'][0]['id'])['size'], 100)


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = FastAPI()
        def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer test': raise HTTPException(401)
        self.calls = 0
        async def model(messages, tools):
            self.calls += 1
            if not any(m['role'] == 'tool' for m in messages):
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'call1', 'type': 'function',
                    'function': {'name': 'web_search', 'arguments': '{"query":"test"}'}}]}
            return {'role': 'assistant', 'content': 'Verified result https://example.com'}
        async def search(query): return {'query': query, 'results': [{'url': 'https://example.com'}]}
        async def fetch(url): return {'markdown': 'Example'}
        self.workspace = module.install(self.app, [Depends(auth)], self.temp.name, model, search, fetch)
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://test', headers={'Authorization': 'Bearer test'})
    async def asyncTearDown(self):
        await self.client.aclose()
        await self.lifespan.__aexit__(None, None, None)
        self.temp.cleanup()
    async def wait_job(self, job_id):
        for _ in range(80):
            job = next(j for j in self.workspace.jobs() if j['id'] == job_id)
            if job['status'] in {'completed', 'failed', 'paused'}: return job
            await asyncio.sleep(.1)
        self.fail('Job did not finish')
    async def test_auth_required_for_workspace_and_downloads(self):
        r = await self.client.get('/workspace/items', headers={'Authorization': ''})
        self.assertEqual(r.status_code, 401)
        r = await self.client.get('/workspace/files/missing', headers={'Authorization': ''})
        self.assertEqual(r.status_code, 401)
    async def test_scheduler_and_checkpointed_research(self):
        r = await self.client.post('/workspace/jobs', json={'prompt': 'Research test'})
        job = await self.wait_job(r.json()['id'])
        self.assertEqual(job['status'], 'completed', job)
        self.assertIn('Verified result', job['result'])
        self.assertEqual(self.calls, 2)
        r = await self.client.post('/workspace/jobs', json={'prompt': 'repeat', 'interval_seconds': 5})
        self.assertEqual(r.status_code, 400)
    async def test_browser_continuation_updates_chat_without_overwriting_conflicts(self):
        chat = {'version': 1, 'summary': {'id': 'testchat', 'title': 'Test', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Hello'}]}
        r = await self.client.post('/workspace/items/testchat/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat)}})
        self.assertEqual(r.status_code, 200, r.text)
        job = await self.wait_job(r.json()['id'])
        self.assertEqual(job['status'], 'completed')
        saved = json.loads(self.workspace.items()[0]['payload']['chat'])
        self.assertEqual(saved['messages'][-1]['role'], 'assistant')
        self.assertEqual(self.workspace.items()[0]['revision'], 2)
    async def test_invalid_continuation_does_not_write_chat(self):
        r = await self.client.post('/workspace/items/bad/continue', json={'revision': 0, 'payload': {'chat': '{}'}})
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.workspace.items(), [])

    async def test_completed_reply_does_not_overwrite_concurrent_edit(self):
        chat = {'version': 1, 'summary': {'id': 'racechat', 'title': 'Test', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Hello'}]}
        r = await self.client.post('/workspace/items/racechat/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat)}})
        edited = dict(chat, messages=[{'role': 'user', 'content': 'Phone edit'}])
        self.workspace.sync('racechat', 1, {'chat': json.dumps(edited)})
        job = await self.wait_job(r.json()['id'])
        self.assertEqual(job['status'], 'completed')
        self.assertIn('no edits overwritten', job['error'])
        self.assertEqual(json.loads(self.workspace.items()[0]['payload']['chat'])['messages'][0]['content'], 'Phone edit')

    async def test_restart_resumes_checkpoint_without_replaying_tools(self):
        await self.lifespan.__aexit__(None, None, None)
        jid = self.workspace.create_job('Resume', 0)
        checkpoint = [{'role': 'system', 'content': 'Research'}, {'role': 'user', 'content': 'Resume'},
            {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'prior', 'type': 'function',
                'function': {'name': 'web_search', 'arguments': '{"query":"already searched"}'}}]},
            {'role': 'tool', 'tool_call_id': 'prior', 'content': '{"result":"done"}'}]
        self.workspace.update_job(jid, status='running', messages=json.dumps(checkpoint))
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        job = await self.wait_job(jid)
        self.assertEqual(job['status'], 'completed')
        self.assertEqual(self.calls, 1)

    async def test_paused_jobs_do_not_run_and_resume(self):
        r = await self.client.post('/workspace/jobs', json={'prompt': 'Later', 'run_at': 9999999999})
        jid = r.json()['id']
        await self.client.post(f'/workspace/jobs/{jid}/pause')
        await asyncio.sleep(.05)
        self.assertEqual(self.calls, 0)
        await self.client.post(f'/workspace/jobs/{jid}/resume')
        self.assertEqual((await self.wait_job(jid))['status'], 'completed')

if __name__ == '__main__': unittest.main()
