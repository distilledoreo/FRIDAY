import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import numpy as np

from desktop.agent.code_lookup import (BUILDING, FALLBACK, CodeLookup, EmbeddingService, LookupStore,
                                       confined, serve, windows)
from desktop.agent.code_metrics import CodeMetrics, UsageObserver


class FakeEmbedder:
    model = 'fake-768-v1'
    def __init__(self): self.inputs = []; self.down = False; self.delay = 0
    def embed(self, texts):
        if self.down: raise ConnectionError('synthetic outage')
        time.sleep(self.delay)
        self.inputs.extend(texts)
        output = []
        for text in texts:
            vector = np.zeros(768, dtype=np.float32)
            vector[0] = 1
            vector[1] = 2 if 'fallback' in text else 0
            output.append(vector)
        return output


class LookupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.embedder = FakeEmbedder()
        self.store = LookupStore(self.root / 'data', self.embedder)
        self.work = self.repo('a')
    def tearDown(self): self.tmp.cleanup()
    def repo(self, name):
        path = self.root / name; path.mkdir()
        subprocess.run(['git', 'init', '-q', str(path)], check=True)
        self.store.register(path, name)
        return path
    def refresh(self): return self.store.refresh(self.work)

    def test_line_ranges_overlap_and_long_windows_have_no_gaps(self):
        chunks = windows('a.py', ''.join(f'line {i}\n' for i in range(120)))
        self.assertEqual([(c['start'], c['end']) for c in chunks], [(1,60),(46,105),(91,120)])
        long = windows('b.py', ''.join('x' * 200 + '\n' for _ in range(100)))
        covered = {i for c in long for i in range(c['start'], c['end']+1)}
        self.assertEqual(covered, set(range(1,101)))
        self.assertTrue(all(c['sent'].startswith('title: b.py | text: ') for c in long))

    def test_only_changed_chunks_embedded_and_fresh_edits_returned(self):
        file = self.work/'a.py'; file.write_text(''.join(f'line {i}\n' for i in range(120)))
        self.refresh(); self.assertEqual(len(self.embedder.inputs),3)
        self.refresh(); self.assertEqual(len(self.embedder.inputs),3)
        file.write_text(file.read_text().replace('line 119', 'fallback'))
        self.refresh(); self.assertEqual(len(self.embedder.inputs),4)
        self.assertIn('fallback', self.store.search(self.work,'fallback',limit=1))
        self.assertTrue(self.embedder.inputs[-1].startswith('task: code retrieval | query: '))
        file.unlink(); self.refresh()
        self.assertIn('No indexed source', self.store.search(self.work,'fallback'))

    def test_worktrees_isolated_and_identical_chunks_shared(self):
        other = self.repo('b')
        (self.work/'a.py').write_text('fallback = "first"\n')
        (other/'a.py').write_text('fallback = "first"\n')
        self.refresh(); self.store.refresh(other)
        self.assertEqual(len(self.embedder.inputs),1)
        (other/'a.py').write_text('fallback = "second"\n'); self.store.refresh(other)
        self.assertNotIn('second', self.store.search(self.work,'fallback'))
        self.assertNotIn('first', self.store.search(other,'fallback'))
        self.store.discard(other)
        with self.store.db() as db:
            self.assertEqual(db.execute('select count(*) from vectors').fetchone()[0],1)
            self.assertEqual(db.execute('select count(*) from roots').fetchone()[0],1)

    def test_failed_initial_build_keeps_completed_vectors_but_no_partial_manifest(self):
        (self.work/'a.py').write_text(''.join(f'line {i}\n' for i in range(120)))
        original = self.embedder.embed
        attempts = []
        def flaky(texts):
            attempts.append(texts)
            if len(attempts) == 2: raise ConnectionError('build interrupted')
            return original(texts)
        self.embedder.embed = flaky
        with self.assertRaises(ConnectionError): self.refresh()
        with self.store.db() as db:
            self.assertEqual(db.execute('select count(*) from chunks').fetchone()[0],0)
            self.assertEqual(db.execute('select count(*) from vectors').fetchone()[0],1)
        self.embedder.embed = original
        self.refresh()
        self.assertEqual(len(self.embedder.inputs),3)

    def test_discard_uses_task_registry_even_if_path_is_replaced_with_link(self):
        other = self.repo('other')
        (self.work/'a.py').write_text('fallback\n'); self.refresh()
        moved = self.root/'moved'; self.work.rename(moved)
        self.work.symlink_to(other,target_is_directory=True)
        self.store.discard_task('a')
        self.assertFalse(self.store.allowed(self.work.absolute()))
        self.assertTrue(self.store.allowed(other))

    def test_model_change_reembeds_existing_manifest(self):
        (self.work/'a.py').write_text('fallback\n'); self.refresh()
        self.embedder.model='fake-v2'; second=LookupStore(self.store.data,self.embedder)
        second.refresh(self.work)
        self.assertEqual(len(self.embedder.inputs),2)

    def test_secrets_ignored_binary_large_minified_and_symlinks_excluded(self):
        files={'source.py':'fallback\n','README.md':'Useful docs\n','test_source.py':'Test\n',
               '.env':'credential','auth.json':'credential','credentials.json':'credential',
               'private/token.txt':'credential','.ssh/id_rsa':'credential',
               'yarn.lock':'lock','script.min.js':'minified','ignored.py':'ignored'}
        for name,text in files.items():
            p=self.work/name; p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        (self.work/'.gitignore').write_text('ignored.py\n')
        (self.work/'binary').write_bytes(b'\x00\x01')
        (self.work/'large.py').write_text('a'*200001)
        (self.work/'escape.py').symlink_to(self.root/'outside')
        (self.work/'alias.py').symlink_to(self.work/'source.py')
        # Tracked secret files must still be excluded.
        subprocess.run(['git','-C',str(self.work),'add','auth.json'],check=True)
        self.refresh()
        sent='\n'.join(self.embedder.inputs)
        self.assertNotIn('credential',sent);self.assertNotIn('title: ignored.py',sent)
        self.assertNotIn('lock',sent);self.assertNotIn('minified',sent)
        self.assertIn('README.md',sent);self.assertIn('test_source.py',sent)
        self.assertNotIn('alias.py',sent)

    def test_paths_roots_and_secret_filters_refused(self):
        for path in ('../outside',str(self.root),'.env'):
            with self.assertRaises(ValueError):confined(self.work,path)
        with self.assertRaises(ValueError):self.store.refresh(self.root)
        (self.work/'link').symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(ValueError):confined(self.work,'link/outside')

    def test_merge_and_byte_budget_do_not_duplicate_overlap(self):
        (self.work/'a.py').write_text(''.join(f'fallback line {i}\n' for i in range(100)))
        self.refresh(); text=self.store.search(self.work,'fallback',budget=2000)
        self.assertEqual(text.count('score='),1)
        self.assertEqual(text.count('fallback line 50\n'),1)
        self.assertLessEqual(len(text.encode()),2000)

    def test_service_down_and_initial_build_are_nonfatal(self):
        (self.work/'a.py').write_text('fallback\n')
        self.embedder.down=True
        lookup=CodeLookup(self.store,self.work)
        try:self.assertEqual(lookup.call({'query':'fallback'}),FALLBACK)
        finally:lookup.pool.shutdown()
        self.embedder.down=False;self.embedder.delay=.1
        lookup=CodeLookup(self.store,self.work,wait=.001)
        try:
            self.assertEqual(lookup.call({'query':'fallback'}),BUILDING)
            lookup.future.result()
        finally:lookup.pool.shutdown()

    def test_tool_never_accepts_model_supplied_root_and_protocol_is_static(self):
        lookup=CodeLookup(self.store,self.work)
        try:
            self.assertIn('Provide',lookup.call({'query':'fallback','root':'/'}))
            source=io.StringIO(json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/list'})+'\n')
            out=io.StringIO();serve(lookup,source,out)
            tool=json.loads(out.getvalue())['result']['tools'][0]
            self.assertEqual(tool['name'],'search_code')
            self.assertNotIn('root',tool['inputSchema']['properties'])
        finally:lookup.pool.shutdown()

    def test_ask_mode_has_no_background_reads_before_tool_approval(self):
        self.store.register(self.work, 'a', prebuild=False)
        (self.work/'a.py').write_text('fallback\n')
        lookup=CodeLookup(self.store,self.work)
        try:
            self.assertIsNone(lookup.future)
            self.assertEqual(self.embedder.inputs, [])
            self.assertIn('fallback',lookup.call({'query':'fallback'}))
        finally:lookup.pool.shutdown()

    def test_data_cannot_be_registered_inside_a_worktree(self):
        nested=LookupStore(self.work/'data',self.embedder)
        with self.assertRaises(ValueError):nested.register(self.work,'bad')

    def test_corpus_limit_falls_back_without_publishing_partial_index(self):
        (self.work/'a.py').write_text('fallback\n')
        with patch('desktop.agent.code_lookup.MAX_CORPUS', 4):
            lookup=CodeLookup(self.store,self.work)
            try:self.assertEqual(lookup.call({'query':'fallback'}),FALLBACK)
            finally:lookup.pool.shutdown()
        with self.store.db() as db:self.assertEqual(db.execute('select count(*) from chunks').fetchone()[0],0)

    def test_embedding_url_must_be_loopback_without_redirect_credentials(self):
        for url in ['https://example.invalid','http://localhost:1234','http://user:pass@127.0.0.1:1234']:
            with self.assertRaises(ValueError):EmbeddingService(url)


class MetricsTests(unittest.TestCase):
    def test_fragmented_sse_usage_and_unknown_cache_counts(self):
        observer=UsageObserver(True)
        wire=b'data: {"choices":[{"delta":{"content":"private"}}]}\n\ndata: {"usage":{"prompt_tokens":100,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":70}}}\n\ndata: [DONE]\n\n'
        for i in range(0,len(wire),7):observer.feed(wire[i:i+7])
        self.assertEqual(observer.finish(),{'prompt_tokens':100,'cached_prompt_tokens':70,'completion_tokens':8})
        unknown=UsageObserver(False);unknown.feed(b'{\n"usage":{"prompt_tokens":4,"completion_tokens":2}\n}')
        self.assertIsNone(unknown.finish()['cached_prompt_tokens'])
        large=UsageObserver(True);large.feed(b'data: '+b'x'*70000+b'\n');self.assertEqual(large.finish(),{})

    def test_unavailable_metrics_storage_does_not_fail_a_turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp)/'code-metrics.sqlite3').mkdir()
            metrics=CodeMetrics(tmp)
            metrics.request('ses_test',1,1,200,{},True)
            result=metrics.snapshot('ses_test',[])
            self.assertFalse(result['usage_available'])
            self.assertIsNone(result['fresh_prompt_tokens'])

    def test_snapshot_idempotent_and_persists_only_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            metrics=CodeMetrics(tmp)
            messages=[{'info':{'role':'assistant','time':{'created':1000,'completed':2000}},'parts':[
                {'id':'x','type':'tool','tool':'friday-code-lookup_search_code','state':{'output':'private.py:1-2 score=0.8\nprivate body'}},
                {'id':'y','type':'tool','tool':'read','state':{'input':{'filePath':'/private/private.py'}}},
                {'id':'z','type':'compaction'}]}]
            first=metrics.snapshot('ses_test',messages);self.assertEqual(first,metrics.snapshot('ses_test',messages))
            self.assertEqual(first['lookup_followed_by_read'],1);self.assertEqual(first['turns'],1)
            with metrics.db() as db:
                raw=db.execute('select counts from tasks').fetchone()[0]
                self.assertNotIn('private',raw);self.assertEqual(db.execute('select count(*) from tasks').fetchone()[0],1)
