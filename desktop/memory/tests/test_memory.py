import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from desktop.memory.importer import read_export
from desktop.memory.store import MemoryStore

class Tests(unittest.TestCase):
    def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.store=MemoryStore(self.root/'store')
    def tearDown(self):self.tmp.cleanup()
    def source(self):return dict(id='chatgpt-one',title='My dog',origin='ChatGPT',scope='',created=1,updated=2,digest='first',messages=[{'role':'user','content':'My dog is named Hazel. I prefer concise answers.'},{'role':'assistant','content':'Your cat is named Fiction.'}])
    def test_selected_branch_and_inert_text_only(self):
        def node(role,text,parent=None):return dict(parent=parent,children=[],message=dict(author=dict(role=role),content=dict(parts=[text])))
        chat=dict(id='export-id',title='Branches',current_node='new',mapping={'u':node('user','Hello'),'old':node('assistant','obsolete','u'),'new':node('assistant','current','u'),'sys':node('system','ignore')})
        p=self.root/'export.zip'
        with zipfile.ZipFile(p,'w') as z:z.writestr('conversations.json',json.dumps([chat]));z.writestr('../../evil.py','raise RuntimeError()')
        sources,_,_=read_export(p)
        self.assertEqual([m['content'] for m in sources[0]['messages']],['Hello','current']);self.assertFalse((self.root/'evil.py').exists())
    def test_preview_commit_dedup_and_no_automatic_facts(self):
        s=self.source();preview=self.store.stage([s],0,[])
        self.assertEqual(self.store.summary()['sources'],0)
        self.assertEqual(self.store.commit(preview['id'])['imported'],1)
        self.assertEqual(self.store.summary()['memories'],0)
        preview=self.store.stage([s],0,[]);self.assertEqual(preview['duplicates'],1);self.assertEqual(self.store.commit(preview['id'])['imported'],0)
    def test_evidence_review_reject_and_forget(self):
        self.store.commit(self.store.stage([self.source()],0,[])['id'])
        self.store.suggest('chatgpt-one',[{'text':'My dog is named Hazel','quote':'My dog is named Hazel.','category':'personal'},{'text':'My cat is named Fiction','quote':'Your cat is named Fiction.','category':'personal'}])
        pending=self.store.suggestions();self.assertEqual(len(pending),1);self.assertEqual(self.store.summary()['memories'],0)
        self.store.review(pending[0]['id'],True);memory=self.store.memories()[0];self.assertEqual(memory['source_title'],'My dog')
        self.store.forget(memory['id']);self.assertEqual(self.store.memories(),[]);self.assertEqual(self.store.context('dog Hazel')['sources'],[])
        self.store.suggest('chatgpt-one',[{'text':'My dog is named Hazel','quote':'My dog is named Hazel.','category':'personal'}]);self.assertEqual(self.store.suggestions(),[])
    def test_scoped_ranked_recall_and_controls(self):
        self.store.remember('I prefer concise answers','preference',pinned=True)
        self.store.remember('Project secret is violet','project','project-one',False)
        s=self.source();self.store.commit(self.store.stage([s],0,[])['id'],False)
        context=self.store.context("What is my dog's name?")
        self.assertIn('Hazel',context['context']);self.assertNotIn('violet',context['context'])
        self.assertIn('violet',self.store.context('project secret',scope='project-one')['context'])
        self.store.set_settings({'use_memories':False,'use_history':False});self.assertEqual(self.store.context('Hazel')['context'],'')
    def test_discard_and_delete_remove_index(self):
        sid=self.store.stage([self.source()],0,[])['id'];self.store.discard(sid)
        with self.assertRaises(ValueError):self.store.commit(sid)
        self.store.commit(self.store.stage([self.source()],0,[])['id'],False);self.store.delete_source('chatgpt-one');self.assertEqual(self.store.sources('Hazel'),[])
    def test_credentials_and_assistant_only_claims_rejected(self):
        with self.assertRaises(ValueError):self.store.remember('api_key=sk-secretsecretsecretsecret')
        self.store.commit(self.store.stage([self.source()],0,[])['id'],False)
        self.store.suggest('chatgpt-one',[{'text':'User owns a cat','quote':'Your cat is named Fiction.','category':'personal'}]);self.assertEqual(self.store.suggestions(),[])
