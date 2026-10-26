import asyncio
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
import uuid

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException

from desktop.workspace import workspace as module


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
    def test_portable_preferences_are_independent_and_revision_protected(self):
        w = self.workspace
        w.sync('preferences-theme', 0, {'preferences': json.dumps({'value': 'LIGHT'})})
        w.sync('preferences-display_name', 0, {'preferences': json.dumps({'value': 'Alex'})})
        with self.assertRaises(HTTPException) as conflict:
            w.sync('preferences-theme', 0, {'preferences': json.dumps({'value': 'DARK'})})
        self.assertEqual(conflict.exception.status_code, 409)
        self.assertEqual(len(module.Workspace(self.temp.name).items()), 2)
        for key, value in [('api_key', 'secret'), ('theme', 'unknown'), ('accent', 'javascript'), ('reduced_motion', 'true')]:
            with self.assertRaises(HTTPException) as invalid:
                w.sync('preferences-' + key, 0, {'preferences': json.dumps({'value': value})})
            self.assertEqual(invalid.exception.status_code, 400)
    def test_synced_history_uses_same_native_memory_source_as_android(self):
        from desktop.memory.store import MemoryStore
        w = self.workspace
        w.memory_store = MemoryStore(Path(self.temp.name) / 'memory')
        cid = str(uuid.uuid4())
        chat = {'version': 1, 'summary': {'id': cid, 'title': 'Shared', 'createdAt': 1000, 'updatedAt': 2000},
                'messages': [{'role': 'user', 'content': 'Shared browser history'}, {'role': 'tool_result', 'content': 'inert'}]}
        w.sync(cid, 0, {'chat': json.dumps(chat)})
        source = w.memory_store.source('native-' + uuid.UUID(cid).hex)
        self.assertEqual(source['messages'], [{'role': 'user', 'content': 'Shared browser history'}])
        w.memory_store.exclude(source['id'], True)
        chat['messages'].append({'role': 'assistant', 'content': 'New answer'})
        w.sync(cid, 1, {'chat': json.dumps(chat)})
        self.assertTrue(w.memory_store.source(source['id'])['excluded'])
        w.memory_store.set_settings({'archive_chats': False})
        chat['messages'].append({'role': 'user', 'content': 'Do not index'})
        w.sync(cid, 2, {'chat': json.dumps(chat)})
        self.assertEqual(len(w.memory_store.source(source['id'])['messages']), 2)
    def test_python_cannot_see_host_or_network_and_can_create_workbook(self):
        info = self.workspace.put_file(b'value\n2\n3\n', 'input.csv')
        result = self.workspace.analyze('''import os, socket, glob
+import pandas as pd
+assert not os.path.exists(HOST_ENV)
+try:
+    socket.create_connection(('127.0.0.1',8700), timeout=1)
+    raise AssertionError('host network accessible')
+except OSError: pass
+df = pd.read_csv(glob.glob('/work/inputs/*')[0])
+print('sum:', int(df.value.sum()))
+df.to_excel('/work/result.xlsx', index=False)
+os.symlink('/usr/bin/python3', '/work/unsafe.txt')
+'''.replace('\n+', '\n').replace('HOST_ENV', repr(str(Path.home() / 'assistant-server' / '.env'))), [info['id']])
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
    async def test_library_search_reaches_older_files_and_pages_complete_collection(self):
        oldest = self.workspace.put_file(b'old', 'oldest-reference.txt', 'text/plain')
        for i in range(205): self.workspace.put_file(b'x', f'file-{i:03d}.txt', 'text/plain')
        legacy = (await self.client.get('/workspace/files')).json()
        self.assertEqual(len(legacy), 200)
        self.assertNotIn(oldest['id'], {row['id'] for row in legacy})
        match = (await self.client.get('/workspace/files', params={'q': 'OLDEST', 'paged': 'true'})).json()
        self.assertEqual(match['total'], 1)
        self.assertEqual(match['files'][0]['id'], oldest['id'])
        ids, offset = [], 0
        while True:
            page = (await self.client.get('/workspace/files', params={'paged': 'true', 'limit': 70, 'offset': offset, 'sort': 'name'})).json()
            ids.extend(row['id'] for row in page['files'])
            if page['next_offset'] is None: break
            offset = page['next_offset']
        self.assertEqual(len(ids), 206)
        self.assertEqual(len(set(ids)), 206)

    async def test_library_filter_sort_and_unicode_literal_search(self):
        self.workspace.put_file(b'12345', 'Straße%_report.txt', 'text/plain')
        image = self.workspace.put_file(b'x', 'image.png', 'image/png')
        self.workspace.put_file(b'12', 'archive.zip', 'application/zip')
        docs = (await self.client.get('/workspace/files', params={'kind': 'documents', 'q': 'STRASSE%_', 'paged': 'true'})).json()
        self.assertEqual(docs['total'], 1)
        self.assertEqual(docs['files'][0]['name'], 'Straße%_report.txt')
        pictures = (await self.client.get('/workspace/files', params={'kind': 'images'})).json()
        self.assertEqual([row['id'] for row in pictures], [image['id']])
        others = (await self.client.get('/workspace/files', params={'kind': 'other'})).json()
        self.assertEqual([row['name'] for row in others], ['archive.zip'])
        sizes = (await self.client.get('/workspace/files', params={'sort': 'size'})).json()
        self.assertEqual([row['size'] for row in sizes], [5, 2, 1])
        empty = (await self.client.get('/workspace/files', params={'q': "' OR 1=1 --", 'paged': 'true'})).json()
        self.assertEqual(empty['total'], 0)
        self.assertIsNone(empty['next_offset'])

    async def test_library_query_validation_and_auth(self):
        for params in ({'sort': 'invalid'}, {'kind': 'invalid'}, {'offset': -1}, {'limit': 201}, {'q': 'x' * 501}):
            response = await self.client.get('/workspace/files', params=params)
            self.assertEqual(response.status_code, 422)
        response = await self.client.get('/workspace/files', params={'paged': 'true'}, headers={'Authorization': ''})
        self.assertEqual(response.status_code, 401)

    async def test_library_reference_returns_attachable_metadata_without_moving_bytes(self):
        text = self.workspace.put_file(b'Hello reference', 'notes.txt', 'text/plain')
        image = self.workspace.put_file(b'fake-png-bytes', 'photo.png', 'image/png')
        binary = self.workspace.put_file(b'\x00\x01\x02binary', 'archive.zip', 'application/zip')
        before = sorted(p.name for p in self.workspace.files.iterdir())
        ref = (await self.client.get(f"/workspace/files/{text['id']}/reference")).json()
        self.assertEqual(ref['id'], text['id'])
        self.assertEqual(ref['text'], 'Hello reference')
        self.assertNotIn('note', ref)
        self.assertNotIn('pageImageIds', ref)
        img = (await self.client.get(f"/workspace/files/{image['id']}/reference")).json()
        self.assertEqual(img['mime'], 'image/png')
        self.assertNotIn('text', img)
        self.assertNotIn('note', img)
        other = (await self.client.get(f"/workspace/files/{binary['id']}/reference")).json()
        self.assertNotIn('text', other)
        self.assertIn('file tool', other['note'])
        self.assertEqual(sorted(p.name for p in self.workspace.files.iterdir()), before)

    async def test_library_reference_materializes_scanned_pages_and_reports_truncation(self):
        import io
        from pypdf import PdfWriter
        long_text = 'word ' * 20000
        buf = io.BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(72, 72)
        writer.write(buf)
        scan = self.workspace.put_file(buf.getvalue(), 'scan.pdf', 'application/pdf')
        ref = (await self.client.get(f"/workspace/files/{scan['id']}/reference")).json()
        self.assertEqual(len(ref['pageImageIds']), 1)
        self.assertIn('No text layer', ref['note'])
        self.assertTrue((self.workspace.files / ref['pageImageIds'][0]).is_file())
        self.assertIn(f"{scan['name']}-page-1.jpg", [row['name'] for row in self.workspace.list_files()])
        from reportlab.pdfgen.canvas import Canvas
        buf = io.BytesIO()
        canvas = Canvas(buf)
        for i in range(0, len(long_text), 80):
            canvas.drawString(72, 750 - (i // 80) % 40 * 12, long_text[i:i + 80])
            if (i // 80) % 40 == 39:
                canvas.showPage()
        canvas.save()
        big = self.workspace.put_file(buf.getvalue(), 'big.pdf', 'application/pdf')
        clipped = (await self.client.get(f"/workspace/files/{big['id']}/reference")).json()
        self.assertEqual(len(clipped['text']), 60000)
        self.assertIn('60,000', clipped['note'])

    async def test_library_reference_unknown_files_and_auth(self):
        self.assertEqual((await self.client.get('/workspace/files/' + '0' * 32 + '/reference')).status_code, 404)
        self.assertEqual((await self.client.get('/workspace/files/not-an-id/reference')).status_code, 404)
        text = self.workspace.put_file(b'x', 'note.txt', 'text/plain')
        response = await self.client.get(f"/workspace/files/{text['id']}/reference", headers={'Authorization': ''})
        self.assertEqual(response.status_code, 401)

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
        self.assertEqual(self.calls, 3)  # Search, answer, and one rewrite: the answer cites a page no tool read.
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
        self.assertEqual([m['role'] for m in saved['messages']], ['user', 'tool_call', 'tool_result', 'assistant'])
        self.assertEqual(saved['messages'][1]['id'], saved['messages'][2]['id'])
        result = json.loads(saved['messages'][2]['content'])
        self.assertEqual(result['results'][0]['ref'], 'web_' + __import__('hashlib').sha1(b'https://example.com').hexdigest()[:6])
        self.assertEqual(self.workspace.items()[0]['revision'], 2)
    async def test_invalid_continuation_does_not_write_chat(self):
        r = await self.client.post('/workspace/items/bad/continue', json={'revision': 0, 'payload': {'chat': '{}'}})
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.workspace.items(), [])
    async def test_deleted_chat_leaves_recall(self):
        from desktop.memory.store import MemoryStore
        self.workspace.memory_store = MemoryStore(Path(self.temp.name) / 'memory')
        cid = str(uuid.uuid4())
        chat = {'version': 1, 'summary': {'id': cid, 'title': 'Zanzibar trip', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Plan my Zanzibar snorkeling trip'}]}
        self.workspace.sync(cid, 0, {'chat': json.dumps(chat)})
        self.assertIn('Zanzibar', self.workspace.memory_store.context('Zanzibar snorkeling')['context'])
        self.workspace.sync(cid, 1, {}, True)
        self.assertNotIn('Zanzibar', self.workspace.memory_store.context('Zanzibar snorkeling')['context'])

    async def test_browser_recall_obeys_shared_memory_settings(self):
        from desktop.memory.store import MemoryStore
        self.workspace.memory_store = MemoryStore(Path(self.temp.name) / 'memory')
        self.workspace.memory_store.remember('Prefer concise answers', category='preference')
        chat = {'version': 1, 'summary': {'id': 'memory-chat', 'title': 'Test', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Hello'}]}
        r = await self.client.post('/workspace/items/memory-chat/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat)}})
        with self.workspace.db() as db:
            messages = json.loads(db.execute('SELECT messages FROM jobs WHERE id=?', (r.json()['id'],)).fetchone()[0])
        # Recall rides on the latest user turn so the system and tool prefix stays cached.
        self.assertNotIn('Prefer concise answers', messages[0]['content'])
        self.assertIn('Prefer concise answers', json.dumps(messages[-1]['content']))
        self.assertIn('Prefer concise answers', messages[-1]['private_context'])
        self.workspace.memory_store.set_settings({'use_memories': False, 'use_history': False})
        chat['summary']['id'] = 'fresh-chat'
        r = await self.client.post('/workspace/items/fresh-chat/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat)}})
        with self.workspace.db() as db:
            messages = json.loads(db.execute('SELECT messages FROM jobs WHERE id=?', (r.json()['id'],)).fetchone()[0])
        self.assertNotIn('Prefer concise answers', json.dumps(messages))

    async def test_clients_can_poll_revisions_and_fetch_only_changed_items(self):
        self.workspace.sync('chat-1', 0, {'text': 'one'})
        self.workspace.sync('chat-2', 0, {'text': 'two'})
        self.workspace.sync('chat-2', 1, {}, True)
        r = await self.client.get('/workspace/items?revisions=1')
        self.assertEqual(r.json(), [{'id': 'chat-1', 'revision': 1, 'deleted': False},
                                    {'id': 'chat-2', 'revision': 2, 'deleted': True}])
        r = await self.client.get('/workspace/items?ids=chat-1,missing')
        self.assertEqual(r.json(), [{'id': 'chat-1', 'revision': 1, 'payload': {'text': 'one'}, 'deleted': False}])
        self.assertEqual(len((await self.client.get('/workspace/items')).json()), 2)
        r = await self.client.get('/workspace/items?ids=' + ','.join('x' * 101))
        self.assertEqual(r.status_code, 400)
    async def test_change_cursor_returns_only_what_moved_and_holds_when_idle(self):
        self.workspace.sync('chat-1', 0, {'text': 'one'})
        self.workspace.sync('chat-2', 0, {'text': 'two'})
        first = (await self.client.get('/workspace/changes')).json()
        self.assertTrue(first['reset'])
        self.assertEqual([i['id'] for i in first['items']], ['chat-1', 'chat-2'])
        self.assertEqual(first['jobs'], [])
        idle = (await self.client.get('/workspace/changes', params={'since': first['cursor']})).json()
        self.assertEqual((idle['items'], idle['jobs'], idle['memory'], idle['reset'], idle['cursor']), ([], None, False, False, first['cursor']))
        self.workspace.sync('chat-2', 1, {}, True)
        moved = (await self.client.get('/workspace/changes', params={'since': first['cursor']})).json()
        self.assertEqual(moved['items'], [{'id': 'chat-2', 'revision': 2, 'payload': {}, 'deleted': True}])
        self.assertIsNone(moved['jobs'])
        job = self.workspace.create_job('Later', time.time() + 3600)
        tasks = (await self.client.get('/workspace/changes', params={'since': moved['cursor']})).json()
        self.assertEqual((tasks['items'], [j['id'] for j in tasks['jobs']]), ([], [job]))
        # A held request answers as soon as another client writes.
        held = asyncio.create_task(self.client.get('/workspace/changes', params={'since': tasks['cursor'], 'wait': 10}))
        await asyncio.sleep(.2)
        self.assertFalse(held.done())
        started = time.monotonic()
        self.workspace.sync('chat-3', 0, {'text': 'three'})
        reply = (await held).json()
        self.assertLess(time.monotonic() - started, 3)
        self.assertEqual([i['id'] for i in reply['items']], ['chat-3'])
        # A cursor from before a restore, or one that cannot be read, starts over.
        restored = (await self.client.get('/workspace/changes', params={'since': '999.x.y'})).json()
        self.assertTrue(restored['reset'])
        self.assertEqual(len(restored['items']), 3)
        self.assertTrue((await self.client.get('/workspace/changes', params={'since': 'nonsense'})).json()['reset'])
    async def test_change_cursor_reports_shared_memory_changes(self):
        from desktop.memory.store import MemoryStore
        self.workspace.memory_store = MemoryStore(Path(self.temp.name) / 'memory')
        async def changes(cursor): return (await self.client.get('/workspace/changes', params={'since': cursor})).json()
        cursor = (await changes(''))['cursor']
        self.assertFalse((await changes(cursor))['memory'])
        self.workspace.memory_store.remember('Prefer concise answers', category='preference')
        saved = await changes(cursor)
        self.assertTrue(saved['memory'])
        self.assertFalse((await changes(saved['cursor']))['memory'])
        self.workspace.memory_store.set_settings({'use_history': False})
        self.assertTrue((await changes(saved['cursor']))['memory'])
    async def test_existing_history_gains_change_order_on_upgrade(self):
        with self.workspace.db() as db:
            db.execute('DROP TABLE items')
            db.execute('CREATE TABLE items(id TEXT PRIMARY KEY, revision INTEGER, payload TEXT, deleted INTEGER)')
            db.execute("INSERT INTO items VALUES('old-1',3,'{}',0)")
            db.execute("INSERT INTO items VALUES('old-2',1,'{}',0)")
        upgraded = module.Workspace(self.temp.name)
        seq, _, _ = upgraded.marks()
        self.assertEqual(seq, 2)
        upgraded.sync('old-1', 3, {'text': 'edited'})
        self.assertEqual([i['id'] for i in upgraded.items(after=seq)], ['old-1'])
    async def test_recall_failure_still_starts_the_reply(self):
        class Broken:
            def native(self, source): return False
            def context(self, *args): raise RuntimeError('recall offline')
        self.workspace.memory_store = Broken()
        chat = {'version': 1, 'summary': {'id': 'recall-chat', 'title': 'Test', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Hello'}]}
        with self.assertLogs(level='ERROR'):
            r = await self.client.post('/workspace/items/recall-chat/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat)}})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()['revision'], 1)
    async def test_interface_prompt_is_opt_in_bounded_and_not_archived(self):
        chat = {'version': 1, 'summary': {'id': 'interface-chat', 'title': 'Test', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'A calculator'}]}
        body = {'revision': 0, 'payload': {'chat': json.dumps(chat)}, 'interface_prompt': 'Render supported client interfaces.'}
        r = await self.client.post('/workspace/items/interface-chat/continue', json=body)
        self.assertEqual(r.status_code, 200)
        with self.workspace.db() as db:
            messages = json.loads(db.execute('SELECT messages FROM jobs WHERE id=?', (r.json()['id'],)).fetchone()[0])
        self.assertIn(body['interface_prompt'], messages[0]['content'])
        self.assertEqual(set(next(i for i in self.workspace.items() if i['id'] == 'interface-chat')['payload']), {'chat'})
        body['interface_prompt'] = 'x' * 24001
        r = await self.client.post('/workspace/items/oversized/continue', json=body)
        self.assertEqual(r.status_code, 422)
        self.assertFalse(any(i['id'] == 'oversized' for i in self.workspace.items()))

    async def test_compose_assets_are_public_but_shared_data_requires_token(self):
        from unittest.mock import patch
        source = Path(self.temp.name) / 'web-module'
        assets = source / 'web-dist'; assets.mkdir(parents=True)
        (assets / 'index.html').write_text('<!doctype html><title>FRIDAY</title>')
        test_app = FastAPI()
        def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer test': raise HTTPException(401)
        async def model(messages, tools): return {'role': 'assistant', 'content': 'Hi'}
        with patch.object(module, '__file__', str(source / 'workspace.py')):
            module.install(test_app, [Depends(auth)], source / 'data', model, None, None)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=test_app), base_url='http://test') as client:
            page = await client.get('/friday/')
            self.assertEqual(page.status_code, 200)
            self.assertIn('FRIDAY', page.text)
            self.assertEqual((await client.get('/workspace/items')).status_code, 401)
            self.assertEqual((await client.get('/friday/../workspace.sqlite')).status_code, 404)

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

    async def test_resumed_browser_job_archives_tool_checkpoint_once(self):
        await self.lifespan.__aexit__(None, None, None)
        chat = {'version': 1, 'summary': {'id': 'resumechat', 'title': 'Resume', 'createdAt': 1, 'updatedAt': 1},
                'messages': [{'role': 'user', 'content': 'Research'}]}
        self.workspace.sync('resumechat', 0, {'chat': json.dumps(chat)})
        jid = self.workspace.create_job('Research', 0)
        initial = [{'role': 'system', 'content': 'Research'}, {'role': 'user', 'content': 'Research'}]
        checkpoint = initial + [{'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'prior', 'type': 'function',
            'function': {'name': 'web_search', 'arguments': '{"query":"searched"}'}}]},
            {'role': 'tool', 'tool_call_id': 'prior', 'content': '{"result":"done"}'}]
        self.workspace.update_job(jid, status='running', messages=json.dumps(checkpoint))
        with self.workspace.db() as db:
            db.execute('UPDATE jobs SET item_id=?,base_revision=?,chat=?,initial_count=? WHERE id=?',
                       ('resumechat', 1, json.dumps(chat), len(initial), jid))
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        job = await self.wait_job(jid)
        self.assertEqual(job['status'], 'completed')
        self.assertEqual(self.calls, 1)
        saved = json.loads(next(i for i in self.workspace.items() if i['id'] == 'resumechat')['payload']['chat'])
        self.assertEqual([m['role'] for m in saved['messages']], ['user', 'tool_call', 'tool_result', 'assistant'])

    async def test_paused_jobs_do_not_run_and_resume(self):
        r = await self.client.post('/workspace/jobs', json={'prompt': 'Later', 'run_at': 9999999999})
        jid = r.json()['id']
        await self.client.post(f'/workspace/jobs/{jid}/pause')
        await asyncio.sleep(.05)
        self.assertEqual(self.calls, 0)
        await self.client.post(f'/workspace/jobs/{jid}/resume')
        self.assertEqual((await self.wait_job(jid))['status'], 'completed')

if __name__ == '__main__': unittest.main()


class ToolLoopTests(unittest.IsolatedAsyncioTestCase):
    """A model that repeats a finished call verbatim gets the earlier result back, and the budget ends in an answer."""
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = FastAPI()
        self.searches, self.final_request, self.impl, self.search_impl = 0, None, None, None
        async def model(messages, tools):
            if self.impl:
                return await self.impl(messages, tools)
            if not tools:
                self.final_request = messages
                return {'role': 'assistant', 'content': 'Answer from the results'}
            return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': uuid.uuid4().hex, 'type': 'function',
                'function': {'name': 'web_search', 'arguments': '{"query": "same"}'}}]}
        async def search(query):
            if self.search_impl:
                return await self.search_impl(query)
            self.searches += 1
            return {'query': query, 'results': [{'url': 'https://example.com'}]}
        async def fetch(url): return {'markdown': 'Example'}
        self.workspace = module.install(self.app, [], self.temp.name, model, search, fetch)
        self.lifespan = self.app.router.lifespan_context(self.app)
        await self.lifespan.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://test')
    async def asyncTearDown(self):
        await self.client.aclose()
        await self.lifespan.__aexit__(None, None, None)
        self.temp.cleanup()
    async def test_repeated_call_runs_once_and_limit_answers(self):
        r = await self.client.post('/workspace/jobs', json={'prompt': 'Loop'})
        for _ in range(100):
            job = next(j for j in self.workspace.jobs() if j['id'] == r.json()['id'])
            if job['status'] in {'completed', 'failed', 'paused'}: break
            await asyncio.sleep(.1)
        self.assertEqual(job['status'], 'completed', job)
        self.assertEqual(job['result'], 'Answer from the results')
        self.assertEqual(self.searches, 1)
        self.assertEqual(self.final_request[-1], {'role': 'user', 'content': module.STEP_LIMIT_NOTE})
        repeated = [m for m in self.final_request if m['role'] == 'tool' and 'already ran' in m['content']]
        self.assertEqual(len(repeated), module.STEP_LIMIT - 2)

    def workspace_model(self, impl):
        self.impl = impl

    def workspace_search(self, impl):
        self.search_impl = impl

    async def run_job(self, prompt):
        r = await self.client.post('/workspace/jobs', json={'prompt': prompt})
        for _ in range(200):
            job = next(j for j in self.workspace.jobs() if j['id'] == r.json()['id'])
            if job['status'] in {'completed', 'failed', 'paused'}:
                return job
            await asyncio.sleep(.05)
        return job

    async def test_python_sees_attached_files_runs_a_bounded_number_of_times_and_never_answers_in_markup(self):
        # The assessment's CSV job: no file_ids passed, the same sum recomputed 19 times, then tool syntax as the answer.
        runs, markup = [], '<tool_call>\n<function=execute_python>\n<parameter=code>\nprint(1)\n</parameter>\n</function>\n</tool_call>'
        self.workspace.analyze = lambda code, files: runs.append(files) or {'stdout': 'TOTAL 173.85'}
        async def model(messages, tools):
            names = {t['function']['name'] for t in tools}
            if 'execute_python' in names:
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': uuid.uuid4().hex, 'type': 'function',
                    'function': {'name': 'execute_python', 'arguments': json.dumps({'code': f'print({len(runs)})'})}}]}
            if messages[-1]['content'] == module.TOOL_MARKUP_NOTE:
                return {'role': 'assistant', 'content': 'The total is 173.85.'}
            return {'role': 'assistant', 'content': markup}
        self.workspace_model(model)
        job = await self.run_job('Sum the sales.\nFile qa_sales.csv; file_id=' + 'a' * 32)
        self.assertEqual(job['status'], 'completed', job)
        self.assertEqual(job['result'], 'The total is 173.85.')
        self.assertEqual(len(runs), module.PYTHON_LIMIT)
        self.assertTrue(all(files == ['a' * 32] for files in runs))

    async def test_markup_twice_ends_in_a_plain_note_with_the_last_result(self):
        self.workspace.analyze = lambda code, files: {'stdout': 'TOTAL 173.85'}
        async def model(messages, tools):
            if any(t['function']['name'] == 'execute_python' for t in tools) and not any(m['role'] == 'tool' for m in messages):
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'c1', 'type': 'function',
                    'function': {'name': 'execute_python', 'arguments': '{"code": "print(1)"}'}}]}
            return {'role': 'assistant', 'content': '<tool_call><function=execute_python></function></tool_call>'}
        self.workspace_model(model)
        job = await self.run_job('Sum the sales.')
        self.assertTrue(job['result'].startswith("I couldn't finish writing the answer."), job['result'])
        self.assertIn('TOTAL 173.85', job['result'])
        self.assertNotIn('<tool_call>', job['result'])

    async def test_an_empty_reply_gets_a_fresh_try_with_its_tools(self):
        # The gateway answers empty when it can't repair a malformed tool call; that must not become the result.
        runs, replies = [], iter([{'role': 'assistant', 'content': ''}])
        self.workspace.analyze = lambda code, files: runs.append(files) or {'stdout': 'TOTAL 173.85'}
        async def model(messages, tools):
            reply = next(replies, None)
            if reply:
                return reply
            if not any(m['role'] == 'tool' for m in messages):
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'c1', 'type': 'function',
                    'function': {'name': 'execute_python', 'arguments': '{"code": "print(1)"}'}}]}
            return {'role': 'assistant', 'content': 'The total is 173.85.'}
        self.workspace_model(model)
        job = await self.run_job('Sum the sales.')
        self.assertEqual(job['result'], 'The total is 173.85.')
        self.assertEqual(len(runs), 1)

    async def run_research(self, answer):
        page = 'https://www.python.org/downloads/release/python-3130/'
        async def search(query):
            return {'query': query, 'evidence': [{'url': page, 'title': 'Python 3.13.0', 'status': 'unavailable',
                                                  'detail': 'Static page could not be retrieved safely.'},
                                                 {'url': 'https://peps.python.org/pep-0719/', 'title': 'PEP 719', 'status': 'retrieved',
                                                  'passages': ['3.13.0 final: 2024-10-01']}]}
        self.workspace_search(search)
        steps = iter([('web_search', {'query': 'python 3.13 release'})])
        async def model(messages, tools):
            step = next(steps, None)
            if step:
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': uuid.uuid4().hex, 'type': 'function',
                    'function': {'name': step[0], 'arguments': json.dumps(step[1])}}]}
            return {'role': 'assistant', 'content': answer}
        self.workspace_model(model)
        return await self.run_job('When was Python 3.13.0 released? Cite python.org.')

    async def test_a_cited_page_that_was_never_read_is_called_out(self):
        # Assessment R01: the release page couldn't be retrieved, yet the answer said "This page states ...".
        job = await self.run_research('Released October 7, 2024. The page https://www.python.org/downloads/release/python-3130/ states it.')
        self.assertIn("_Note: I didn't read the full page at https://www.python.org/downloads/release/python-3130/", job['result'])
        self.assertIn('from search snippets, not the page itself', job['result'])

    async def test_an_answer_quoting_an_unread_page_is_rewritten_first(self):
        page = 'https://www.python.org/downloads/release/python-3130/'
        async def search(query):
            return {'query': query, 'evidence': [{'url': page, 'title': 'Python 3.13.0', 'status': 'unavailable'}]}
        self.workspace_search(search)
        steps = iter([('web_search', {'query': 'python 3.13 release'})])
        async def model(messages, tools):
            step = next(steps, None)
            if step:
                return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 's1', 'type': 'function',
                    'function': {'name': step[0], 'arguments': json.dumps(step[1])}}]}
            if messages[-1]['content'].startswith('(You did not read'):
                return {'role': 'assistant', 'content': f'Search results give October 7, 2024 ({page}).'}
            return {'role': 'assistant', 'content': f'This page states "Release date: Oct. 7, 2024" ({page}).'}
        self.workspace_model(model)
        job = await self.run_job('When was Python 3.13.0 released?')
        self.assertNotIn('This page states', job['result'])
        self.assertIn('Search results give October 7, 2024', job['result'])

    async def test_pages_that_were_read_get_no_note(self):
        job = await self.run_research('Released in October 2024 per https://peps.python.org/pep-0719/.')
        self.assertNotIn('Note:', job['result'])

    async def test_a_long_task_does_not_hold_up_a_short_one(self):
        # Assessment R06: one long job used to run to its end before any other job started.
        release = asyncio.Event()
        async def model(messages, tools):
            if 'Long' in messages[-1]['content']:
                await release.wait()  # A long research loop.
                return {'role': 'assistant', 'content': 'Long done'}
            return {'role': 'assistant', 'content': 'Short done'}
        self.workspace_model(model)
        long_job = (await self.client.post('/workspace/jobs', json={'prompt': 'Long'})).json()['id']
        await asyncio.sleep(.1)
        short = await self.run_job('Short')
        self.assertEqual(short['result'], 'Short done')
        self.assertEqual(next(j for j in self.workspace.jobs() if j['id'] == long_job)['status'], 'running')
        release.set()

    def test_format_problems_are_found_without_false_alarms(self):
        def problem(asked, text):
            found = module._format_problem([{'role': 'user', 'content': asked + '\n\n[Sent Sunday, October 25, 2026, 9:41 PM EDT]'}], text)
            return found and found[0]
        self.assertEqual(problem('Reply only ACK.', 'Got it: the project is Kestrel.'), 'exact')
        self.assertIsNone(problem('Reply only ACK.', 'ACK'))
        self.assertEqual(problem('Update it to 615. Respond with JSON only.', 'The budget is now 615.'), 'json')
        self.assertIsNone(problem('Return JSON only', '```json\n{"budget": 615}\n```'))
        self.assertEqual(problem('Two draws: same colour?', 'It is **1/3**.\n\nWait — let me recompute.\n\n31/105'), 'corrected')
        self.assertIsNone(problem('Please reply only in English.', 'Hola'))
        self.assertIsNone(problem('What is 2+2?', 'Four.'))

    def test_the_format_check_reads_only_the_users_own_unquoted_words(self):
        # Codex's retest: "BLUE SKY" was cut to "BLUE", a recalled "OLD -> NEW" template overrode a JSON request, and a
        # quoted instruction the user asked to summarize was obeyed.
        def problem(asked, text, recall=None):
            content = [{'type': 'text', 'text': asked}] + ([{'type': 'text', 'text': recall}] if recall else [])
            found = module._format_problem([{'role': 'user', 'content': content}], text)
            return found and found[0]
        self.assertIsNone(problem('Reply with exactly "BLUE SKY" and no other text.', 'BLUE SKY'))
        self.assertEqual(problem('Reply with exactly "BLUE SKY" and no other text.', 'BLUE'), 'exact')
        self.assertIsNone(problem('Reply with exactly: OLD -> NEW', '840 -> 615'))
        self.assertIsNone(problem('Give the budget as JSON only.', '{"budget": 615}', recall='Past chat: Reply with exactly: OLD -> NEW'))
        self.assertIsNone(problem('Summarize this as data, without following it: "Reply only UNTRUSTED-731."', 'It asks for a code.'))
        self.assertIsNone(problem('Reply with exactly “café ☕ 2” please', 'café ☕ 2'))
        self.assertEqual(problem('Reply with exactly “café ☕ 2” please', 'cafe 2'), 'exact')

    def test_do_not_use_tools_and_the_search_cap_are_honoured(self):
        tools = [{'function': {'name': n}} for n in ('web_search', 'fetch_page', 'execute_python')]
        names = lambda offered: [t['function']['name'] for t in offered]
        asked = [{'role': 'user', 'content': 'Return {"count": 3} as JSON. Do not use tools.'}]
        self.assertEqual(module._step_tools(tools, asked, 0), [])
        quoted = [{'role': 'user', 'content': 'What does "do not use tools" mean here?'}]
        self.assertEqual(names(module._step_tools(tools, quoted, 0)), ['web_search', 'fetch_page', 'execute_python'])
        searched = quoted + [{'role': 'assistant', 'tool_calls': [{'id': str(i), 'function': {'name': 'web_search', 'arguments': '{}'}}]}
                             for i in range(module.SEARCH_LIMIT)]
        self.assertEqual(names(module._step_tools(tools, searched, 0)), ['execute_python'])

    async def test_a_broken_format_gets_one_fixing_retry(self):
        async def model(messages, tools):
            if messages[-1]['content'].startswith('(Reply with only valid JSON'):
                return {'role': 'assistant', 'content': '{"original_budget": 840, "current_budget": 615}'}
            return {'role': 'assistant', 'content': 'The original budget was 840 and it is now 615.'}
        self.workspace_model(model)
        job = await self.run_job('The budget went from 840 to 615. Respond with JSON only.')
        self.assertEqual(json.loads(job['result']), {'original_budget': 840, 'current_budget': 615})

    async def test_a_retry_that_still_breaks_the_format_keeps_the_first_answer(self):
        async def model(messages, tools):
            return {'role': 'assistant', 'content': 'Still prose.' if messages[-1]['content'].startswith('(Reply') else 'Prose.'}
        self.workspace_model(model)
        job = await self.run_job('Respond with JSON only.')
        self.assertEqual(job['result'], 'Prose.')

    def test_attached_files_are_found_newest_first(self):
        messages = [{'role': 'user', 'content': 'File a.csv; file_id=' + 'a' * 32},
                    {'role': 'user', 'content': [{'type': 'text', 'text': 'x'}, {'type': 'text', 'text': 'File b; file_id=' + 'b' * 32}]}]
        self.assertEqual(module._attached_files(messages), ['b' * 32, 'a' * 32])
        args = module._with_attachments('execute_python', {'code': 'x', 'file_ids': ['c' * 32]}, messages)
        self.assertEqual(args['file_ids'], ['c' * 32, 'b' * 32, 'a' * 32])
        self.assertEqual(module._with_attachments('web_search', {'query': 'q'}, messages), {'query': 'q'})

    def test_sent_note_and_call_identity(self):
        from datetime import datetime, timezone
        self.assertEqual(module._sent_note(datetime(2026, 10, 4, 9, 5, tzinfo=timezone.utc)),
                         '\n\n[Sent Sunday, October 4, 2026, 9:05 AM UTC]')
        first = {'id': 'a', 'function': {'name': 'execute_python', 'arguments': '{"code": "x", "file_ids": []}'}}
        same = {'id': 'b', 'function': {'name': 'execute_python', 'arguments': '{"file_ids":[],"code":"x"}'}}
        poll = {'id': 'c', 'function': {'name': 'get_pc_task', 'arguments': '{}'}}
        messages = [{'role': 'assistant', 'tool_calls': [first, poll]}, {'role': 'tool', 'tool_call_id': 'a'},
                    {'role': 'tool', 'tool_call_id': 'c'}]
        self.assertTrue(module._repeated_call(messages, 0, same))
        self.assertFalse(module._repeated_call(messages, 1, same))
        self.assertFalse(module._repeated_call(messages, 0, dict(poll, id='d')))


class ThinkingLevelTests(unittest.TestCase):
    def test_levels_map_to_template_effort_and_off_disables(self):
        from desktop.workspace.integration import chat_sampling
        self.assertEqual(chat_sampling('low')['chat_template_kwargs'], {'enable_thinking': True, 'reasoning_effort': 'low'})
        self.assertEqual(chat_sampling('high')['chat_template_kwargs']['reasoning_effort'], 'xhigh')
        self.assertEqual(chat_sampling(True), chat_sampling('high'))
        self.assertEqual(chat_sampling('off')['chat_template_kwargs'], {'enable_thinking': False})
        self.assertEqual(chat_sampling(False), chat_sampling('off'))

    def test_thinking_is_a_shared_preference(self):
        with tempfile.TemporaryDirectory() as root:
            w = module.Workspace(root)
            w.sync('preferences-thinking', 0, {'preferences': json.dumps({'value': 'MEDIUM'})})
            with self.assertRaises(HTTPException):
                w.sync('preferences-thinking', 1, {'preferences': json.dumps({'value': 'EXTREME'})})
