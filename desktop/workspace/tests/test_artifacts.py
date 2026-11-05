import json
import tempfile
import unittest
import uuid
from pathlib import Path
from fastapi import HTTPException
from desktop.workspace.workspace import Workspace
from desktop.workspace.artifacts import document
from desktop.workspace.artifact_worker import calculate
from desktop.workspace.artifact_formats import export, preview, import_artifact


def key(): return uuid.uuid4().hex


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.w = Workspace(self.temp.name)
        self.s = self.w.artifacts
        self.body = {'request': key(), 'title': 'A report', 'kind': 'document', 'content': document('A 🐈 is nice.\n\nSecond block')}
        self.a = self.s.create(self.body)['id']

    def test_persistence_idempotency_and_conflicts(self):
        self.assertEqual(self.s.create(self.body)['id'], self.a)
        self.assertEqual(Workspace(self.temp.name).artifacts.get(self.a)['title'], 'A report')
        with self.assertRaises(HTTPException): self.s.create(dict(self.body, title='Other'))
        self.s.save(self.a, {'request': key(), 'base': 1, 'title': 'Changed'})
        with self.assertRaises(HTTPException) as caught: self.s.save(self.a, {'request': key(), 'base': 1, 'content': document('stale')})
        self.assertEqual(caught.exception.status_code, 409)
        self.assertEqual(self.s.get(self.a)['title'], 'Changed')
        self.assertEqual(self.w.items()[0]['payload']['artifact']['revision'], 2)
        with self.assertRaises(HTTPException): self.w.sync('artifact-'+self.a, 2, {})

    def test_unicode_selection_proposal_review_stale_restore(self):
        content = self.s.get(self.a)['content']; block = content['blocks'][0]
        operations = [{'type': 'replace_text', 'block': block['id'], 'from': 2, 'to': 3, 'expected': '🐈', 'text': 'dog'}]
        proposal = self.s.propose(self.a, {'request': key(), 'base': 1, 'operations': operations})
        self.assertEqual(self.s.get(self.a)['revision'], 1)
        request = {'request': key(), 'accept': True}
        self.s.decide(self.a, proposal['id'], request)
        self.s.decide(self.a, proposal['id'], request)
        self.assertEqual(self.s.get(self.a)['content']['blocks'][0]['text'], 'A dog is nice.')
        proposal = self.s.propose(self.a, {'request': key(), 'base': 2, 'operations': [{'type': 'replace_text', 'block': block['id'], 'from': 2, 'to': 5, 'expected': 'dog', 'text': 'bird'}]})
        self.s.save(self.a, {'request': key(), 'base': 2, 'restore': 1})
        with self.assertRaises(HTTPException): self.s.decide(self.a, proposal['id'], {'request': key(), 'accept': True})
        self.assertEqual(self.s.get(self.a)['content'], content)
        self.assertEqual(len(self.s.history(self.a)), 3)

    def test_app_state_separate_from_source_and_cas(self):
        aid = self.s.create({'request': key(), 'kind': 'app', 'content': {'version': 1, 'format': 'html', 'source': '<b>App</b>'}})['id']
        self.s.state(aid, {'request': key(), 'base': 0, 'content': {'count': 2}})
        self.s.save(aid, {'request': key(), 'base': 1, 'content': {'version': 1, 'format': 'html', 'source': '<i>App</i>'}})
        self.assertEqual(self.s.state(aid)['content']['count'], 2)
        with self.assertRaises(HTTPException): self.s.state(aid, {'request': key(), 'base': 0, 'content': {}})

    def test_trash_comments_and_validation(self):
        c = self.s.comment(self.a, {'request': key(), 'text': 'Please check'})
        self.s.comment(self.a, {'request': key(), 'id': c['id'], 'resolved': True})
        self.assertTrue(self.s.comments(self.a)[0]['resolved'])
        self.s.trash(self.a, {'request': key(), 'base': 1, 'deleted': True})
        self.assertEqual(self.s.list(), [])
        with self.assertRaises(HTTPException): self.s.save(self.a, {'request': key(), 'base': 2})
        self.s.trash(self.a, {'request': key(), 'base': 2, 'deleted': False})
        self.assertEqual(len(self.s.list()), 1)
        for content in [{'version': 2, 'blocks': []}, {'version': 1, 'blocks': [{'id': 'b', 'type': 'paragraph', 'text': 'x', 'marks': [{'from': 0, 'to': 1, 'type': 'link', 'href': 'javascript:alert(1)'}]}]}]:
            with self.assertRaises(HTTPException): self.s.save(self.a, {'request': key(), 'base': 3, 'content': content})

    def test_formula_engine(self):
        self.assertEqual(calculate([[2, 3, '=SUM(A1:B1)'], [4, '=A1*2', '=AVERAGE(A1:B2)']]), [[2,3,5],[4,4,3.25]])
        self.assertEqual(calculate([['=B1', '=A1', '=1/0', '=WEBSERVICE("https://example.com")']])[0], ['#CYCLE!', '#CYCLE!', '#DIV/0!', '#UNSUPPORTED!'])

    def test_real_document_export_import_preview_workflow(self):
        result = export(self.w, self.a, {'revision': 1, 'format': 'docx'})
        fid = result['file']['id']
        self.assertEqual(export(self.w, self.a, {'revision': 1, 'format': 'docx'})['file']['id'], fid)
        imported = import_artifact(self.w, {'request': key(), 'file': fid})
        self.assertEqual(self.s.get(imported['id'])['content']['blocks'][0]['text'], 'A 🐈 is nice.')
        from desktop.workspace.artifact_formats import office_root
        if office_root():
            rendered = preview(self.w, fid, 0)
            self.assertEqual(rendered['pages'], 1)
            self.assertGreater(rendered['file']['size'], 1000)
            pdf = export(self.w, self.a, {'revision': 1, 'format': 'pdf'})
            self.assertTrue((self.w.files/pdf['file']['id']).read_bytes().startswith(b'%PDF'))

    def test_word_round_trip_keeps_links_and_following_emphasis_offsets(self):
        content = document('Read the [source](https://example.com/report) then **verify** it.\n\n1. Check\n2. Export')
        aid = self.s.create({'request': key(), 'title': 'Linked report', 'content': content})['id']
        exported = export(self.w, aid, {'revision': 1, 'format': 'docx'})
        imported = import_artifact(self.w, {'request': key(), 'file': exported['file']['id']})
        blocks = self.s.get(imported['id'])['content']['blocks']
        self.assertEqual(blocks[0]['text'], content['blocks'][0]['text'])
        self.assertEqual(blocks[0]['marks'], content['blocks'][0]['marks'])
        self.assertEqual([b['type'] for b in blocks[1:]], ['ordered', 'ordered'])

    def test_formula_intermediate_values_are_bounded(self):
        result = calculate([['=(((10^10)^10)^10)^10', '=(-1)^0.5', '=SUM(A0:A1)']])
        self.assertEqual(result, [['#NUM!', '#VALUE!', '#REF!']])

    def test_export_survives_temporary_file_rename_during_resource_monitoring(self):
        import os
        from unittest.mock import patch
        original = os.walk
        def changing_tree(root):
            # A name returned by readdir may disappear before the monitor stats it.
            yield str(root), [], ['converter-output-already-renamed.tmp']
            yield from original(root)
        with patch('desktop.workspace.artifact_formats.os.walk', side_effect=changing_tree) as monitor:
            result = export(self.w, self.a, {'revision': 1, 'format': 'docx'})
        self.assertTrue(monitor.called)
        self.assertTrue((self.w.files / result['file']['id']).read_bytes().startswith(b'PK'))

    def test_restore_backup_preserves_revisions_assets_comments_and_data(self):
        from desktop.workspace.artifact_formats import backup, restore_backup
        self.s.comment(self.a, {'request': key(), 'text': 'Review'})
        self.s.save(self.a, {'request': key(), 'base': 1, 'title': 'Version two'})
        archive = backup(self.w)
        request = {'request': key(), 'file': archive['id']}
        restored = restore_backup(self.w, request)
        self.assertEqual(restore_backup(self.w, request), restored)
        self.assertEqual(restored['restored'], 1)
        copied = restored['ids'][0]
        self.assertNotEqual(copied, self.a)
        self.assertEqual(len(self.s.history(copied)), 2)
        self.assertEqual(self.s.get(copied)['content'], self.s.get(self.a)['content'])
        self.assertEqual(self.s.comments(copied)[0]['text'], 'Review')

    def test_import_retries_annotations_and_original_retention(self):
        info = self.w.put_file(b'# Heading\n\nBody', 'notes.md')
        body = {'request': key(), 'file': info['id']}
        first = import_artifact(self.w, body)
        self.assertEqual(import_artifact(self.w, body)['id'], first['id'])
        note = self.s.annotate(info['id'], {'request': key(), 'page': 1, 'text': 'Check source'})
        self.assertEqual(self.s.comments(note['artifact'])[0]['quote'], 'Page 2')
        from desktop.workspace.artifact_formats import backup, restore_backup
        copies = restore_backup(self.w, {'request': key(), 'file': backup(self.w)['id']})
        self.assertEqual(copies['restored'], 3)

    def test_specialized_exports_and_formula_round_trip(self):
        from openpyxl import load_workbook
        import io
        sheet = self.s.create({'request': key(), 'kind': 'sheet', 'content': {'version': 1, 'cells': [['Cost', 2], ['Total', '=SUM(B1:B1)']]}})['id']
        file = export(self.w, sheet, {'format': 'xlsx'})['file']
        book = load_workbook(io.BytesIO((self.w.files/file['id']).read_bytes()))
        self.assertEqual(book.active['B2'].value, '=SUM(B1:B1)')
        slide = self.s.create({'request': key(), 'kind': 'slides', 'content': {'version': 1, 'theme': 'dark', 'slides': [{'title': 'A café report', 'body': 'Verified local output.'}]}})['id']
        pdf = export(self.w, slide, {'format': 'pdf'})['file']
        self.assertEqual(preview(self.w, pdf['id'], 0)['pages'], 1)
        try: import pptx
        except ImportError: self.skipTest('python-pptx absent from this test runtime')
        presentation = export(self.w, slide, {'format': 'pptx'})['file']
        deck = pptx.Presentation(io.BytesIO((self.w.files/presentation['id']).read_bytes()))
        self.assertEqual(len(deck.slides), 1)
        self.assertIn('café', deck.slides[0].shapes[0].text)
        imported = import_artifact(self.w, {'request': key(), 'file': presentation['id']})
        self.assertEqual(self.s.get(imported['id'])['kind'], 'slides')

    def test_markdown_import_and_safe_export_preserve_supported_semantics(self):
        from desktop.workspace.artifact_worker import markdown, markdown_document
        value = markdown_document('# Heading\n\nA **bold** café and [source](https://example.com).\n\n- First\n- Second\n\n```python\nprint(1)\n```')
        self.assertEqual([b['type'] for b in value['blocks']], ['heading','paragraph','bullet','bullet','code'])
        self.assertEqual(value['blocks'][1]['marks'][0]['type'], 'bold')
        back = markdown_document(markdown(value))
        self.assertEqual([b['text'] for b in back['blocks']], [b['text'] for b in value['blocks']])
        self.assertEqual(back['blocks'][1]['marks'], value['blocks'][1]['marks'])

    def test_app_data_history_restores_as_new_revision(self):
        aid = self.s.create({'request': key(), 'kind': 'app', 'content': {'version': 1, 'format': 'html', 'source': '<p>App</p>'}})['id']
        self.s.state(aid, {'request': key(), 'base': 0, 'content': {'counter': 3}})
        self.s.state(aid, {'request': key(), 'base': 1, 'content': {}})
        self.s.state(aid, {'request': key(), 'base': 2, 'restore': 1})
        self.assertEqual(self.s.state(aid), {'revision': 3, 'content': {'counter': 3}})
        self.assertEqual(self.s.get(aid)['revision'], 1)

    def test_permanent_delete_requires_trash_current_revision_and_title(self):
        body = {'request': key(), 'base': 1, 'confirmTitle': 'A report'}
        with self.assertRaises(HTTPException): self.s.purge(self.a, body)
        self.s.trash(self.a, {'request': key(), 'base': 1, 'deleted': True})
        with self.assertRaises(HTTPException): self.s.purge(self.a, body)
        self.s.purge(self.a, dict(body, base=2))
        with self.assertRaises(HTTPException): self.s.get(self.a)
        self.assertEqual(self.w.items()[0]['payload'], {})
        self.assertTrue(self.w.items()[0]['deleted'])

    def test_renderer_cannot_read_host_files_credentials_or_network(self):
        import os
        from unittest.mock import patch
        from desktop.workspace.artifact_formats import run_worker
        import shutil
        real_copy = shutil.copyfile
        secret = Path(self.temp.name) / 'private-test-file'; secret.write_text('fixture')
        probe = """import json,os,socket
from pathlib import Path
try:
 socket.create_connection(('127.0.0.1',8700),timeout=1); network=True
except OSError: network=False
Path('/work/out').mkdir()
Path('/work/out/result.json').write_text(json.dumps({'network':network,'secret':os.environ.get('ARTIFACT_TEST_SECRET'),'host':Path(HOST_PATH).exists()}))
""".replace('HOST_PATH', repr(str(secret)))
        def copy(source, target):
            if Path(source).name == 'artifact_worker.py': Path(target).write_text(probe); return target
            return real_copy(source, target)
        with patch.dict(os.environ, {'ARTIFACT_TEST_SECRET':'synthetic'}), patch('desktop.workspace.artifact_formats.shutil.copyfile', side_effect=copy):
            result = run_worker(self.w, {'action': 'probe'})
        self.assertEqual(result, {'network': False, 'secret': None, 'host': False})


class ArtifactApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import httpx
        from fastapi import FastAPI, APIRouter, Depends, Header
        from desktop.workspace.artifacts import enable
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.w = Workspace(self.temp.name)
        app = FastAPI()
        def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer fixture': raise HTTPException(401)
        router = APIRouter(prefix='/workspace', dependencies=[Depends(auth)])
        self.calls = 0
        async def model(messages, tools):
            self.calls += 1
            self.assertEqual(tools, [])
            self.assertIn('selection', messages[-1]['content'])
            return {'content': 'a friendly cat'}
        enable(router, self.w, model); app.include_router(router)
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test', headers={'Authorization':'Bearer fixture'})
        self.addAsyncCleanup(self.client.aclose)

    async def test_authenticated_selection_review_and_retry(self):
        response = await self.client.get('/workspace/artifacts', headers={'Authorization': ''})
        self.assertEqual(response.status_code, 401)
        created = (await self.client.post('/workspace/artifacts', json={'request': key(), 'title': 'Report', 'text': 'A 🐈 is nice.'})).json()
        path = '/workspace/artifacts/' + created['id']
        original = (await self.client.get(path)).json()
        body = {'request': key(), 'base': 1, 'block': original['content']['blocks'][0]['id'], 'from': 2, 'to': 3, 'instruction': 'Describe the cat'}
        suggested = await self.client.post(path+'/suggest', json=body)
        self.assertEqual(suggested.status_code, 200, suggested.text)
        duplicate = await self.client.post(path+'/suggest', json=body)
        self.assertEqual(duplicate.json(), suggested.json()); self.assertEqual(self.calls, 1)
        self.assertEqual((await self.client.get(path)).json()['revision'], 1)
        accepted = await self.client.post(path+'/proposals/'+suggested.json()['id'], json={'request': key(), 'accept': True})
        self.assertEqual(accepted.status_code, 200)
        self.assertEqual((await self.client.get(path)).json()['content']['blocks'][0]['text'], 'A a friendly cat is nice.')
        self.assertEqual((await self.client.get(path+'/context')).json()['revision'], 2)
