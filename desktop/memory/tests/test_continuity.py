import tempfile,time,unittest
from pathlib import Path
from ..store import MemoryStore
from ..continuity import apply_updates,recall,list_situations,resolve,remove,DAY

class ContinuityTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=MemoryStore(Path(self.temp.name));self.now=time.time()
    def tearDown(self):self.temp.cleanup()
    def source(self,text,ident='native-fixture',scope='',updated=None):
        self.store.native({'id':ident,'title':'Deployment','origin':'Local assistant','scope':scope,'messages':[{'role':'user','content':text}],'digest':text,'created':self.now,'updated':updated or self.now});return ident
    def update(self,quote,**kwargs):
        return dict(topic='Deployment',summary='Deployment is blocked and frustrating.',status='open',quote=quote,**kwargs)
    def test_selective_recall_cooldown_resolution_and_expiry(self):
        quote='My deployment is blocked and I feel frustrated.';sid=self.source(quote);apply_updates(self.store,sid,[self.update(quote)],self.now)
        self.assertEqual(recall(self.store,'What is 2 plus 2?','','',self.now),([],[]))
        notes,_=recall(self.store,'How should I debug my deployment?','','',self.now)
        self.assertIn('may briefly ask',notes[0]);self.assertIn('Do not assume',notes[0])
        notes,_=recall(self.store,'hello','','',self.now+DAY);self.assertIn('do not initiate',notes[0])
        notes,_=recall(self.store,'hello','','',self.now+4*DAY);self.assertIn('may briefly ask',notes[0])
        ident=list_situations(self.store)[0]['id'];resolve(self.store,ident,'resolved')
        self.assertEqual(recall(self.store,'hello','','',self.now+DAY),([],[]))
        self.assertEqual(recall(self.store,'deployment','','',self.now+40*DAY),([],[]))
    def test_new_evidence_updates_known_subject_no_resurrection(self):
        quote='My deployment is blocked and I feel frustrated.';sid=self.source(quote);apply_updates(self.store,sid,[self.update(quote)],self.now);ident=list_situations(self.store)[0]['id']
        new='My deployment is fixed and running now.';sid=self.source(new,'native-second',updated=self.now+10);apply_updates(self.store,sid,[dict(topic='Deployment',summary='Deployment is fixed.',status='resolved',quote=new,id=ident)],self.now+10)
        self.assertEqual(list_situations(self.store,now=self.now+10)[0]['status'],'resolved')
        apply_updates(self.store,'native-fixture',[self.update(quote,id=ident)],self.now+20)
        self.assertEqual(list_situations(self.store,now=self.now+20)[0]['status'],'resolved')
        with self.store.db() as db:self.assertEqual(db.execute('SELECT count(*) FROM situation_events').fetchone()[0],2)
    def test_scopes_evidence_archives_and_controls(self):
        quote='My deployment is blocked and I feel frustrated.';sid=self.source(quote,scope='orchid')
        apply_updates(self.store,sid,[self.update('The assistant invented this quote.')],self.now);self.assertEqual(list_situations(self.store),[])
        apply_updates(self.store,sid,[self.update(quote)],self.now)
        self.assertEqual(recall(self.store,'deployment','other','',self.now),([],[]))
        self.assertEqual(recall(self.store,'deployment','orchid',sid,self.now),([],[]))
        self.store.set_settings({'allow_checkins':False});notes,_=recall(self.store,'deployment','orchid','',self.now);self.assertIn('do not initiate',notes[0])
        self.store.set_settings({'track_situations':False});self.assertEqual(self.store.context('deployment','orchid')['sources'][-1]['kind'],'history')
        ident=list_situations(self.store)[0]['id'];remove(self.store,ident);self.assertEqual(list_situations(self.store),[]);self.assertTrue(self.store.source(sid)['excluded'])
        self.store.set_settings({'track_situations':True});sid=self.source(quote,'chatgpt-old');apply_updates(self.store,sid,[self.update(quote)],self.now);self.assertEqual(list_situations(self.store),[])
