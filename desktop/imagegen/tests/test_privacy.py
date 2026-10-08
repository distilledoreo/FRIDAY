import unittest
from ..privacy import prepare_chat

class Cache:
    def __init__(self):self.calls=0
    def stabilize(self,body):self.calls+=1;return dict(body,stabilized=True)
class Openings:
    def __init__(self):self.calls=0
    async def before_chat(self,body):self.calls+=1
class CachePrivacyTests(unittest.IsolatedAsyncioTestCase):
    async def test_private_request_bypasses_all_shared_cache_and_snapshot_hooks(self):
        cache=Cache();openings=Openings();body={'messages':[{'role':'user','content':'private fixture'}],'cache_prompt':True}
        result=await prepare_chat(body,True,cache,openings)
        self.assertFalse(result['cache_prompt']);self.assertEqual(cache.calls,0);self.assertEqual(openings.calls,0)
        self.assertTrue(body['cache_prompt']);self.assertEqual(result['messages'],body['messages'])
    async def test_normal_requests_keep_claude_cache_behavior(self):
        cache=Cache();openings=Openings();result=await prepare_chat({'messages':[]},False,cache,openings)
        self.assertTrue(result['stabilized']);self.assertEqual(cache.calls,1);self.assertEqual(openings.calls,1)
