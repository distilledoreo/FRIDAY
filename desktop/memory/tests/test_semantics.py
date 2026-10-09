import os,tempfile,unittest
from pathlib import Path
from ..store import MemoryStore

class SemanticTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("MEMORY_TEST_MODEL"), "Set MEMORY_TEST_MODEL to a verified CPU encoder directory")
    def test_paraphrase_and_unrelated_miss(self):
        model=Path(os.environ["MEMORY_TEST_MODEL"])
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'model').symlink_to(str(model))
            store=MemoryStore(root)
            sources=[]
            for sid,title,text in [('dog','Pet background','My dog is named Hazel.'),('orchid','Orchid engineering','The Orchid project preserves context during long conversations.'),('dinner','Dinner','I enjoy Thai food for dinner.')]:
                sources.append(dict(id=sid,title=title,origin='Test',scope='',messages=[dict(role='user',content=text)],created=1,updated=1,digest=sid))
            store.commit(store.stage(sources,0,[])['id'],False)
            while store.index_batch():pass
            for query,expected in [('What is my canine companion called?','dog'),('Which project keeps lengthy discussions from losing track?','orchid')]:
                result=store.context(query);ids=[s['id'] for s in result['sources']]
                assert expected in ids,(query,ids)
                print('Meaning-based recall:',expected,'passed')
            result=store.context('How do I replace a bicycle tire?')
            assert result['sources']==[],result['sources']
            print('Unrelated-query rejection passed; CPU execution only.')
