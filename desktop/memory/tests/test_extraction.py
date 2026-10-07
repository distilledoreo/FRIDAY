import tempfile
import unittest
from pathlib import Path
from desktop.memory.fact_prompt import parse_reply, standalone
from desktop.memory.store import MemoryStore

class FactPromptTests(unittest.TestCase):
    def test_standalone_rejects_chat_references(self):
        self.assertTrue(standalone('The user writes songs in the style of Sleeping At Last'))
        self.assertTrue(standalone('This user prefers concise answers'))
        self.assertFalse(standalone('The user wants the macro to skip blank rows'))
        self.assertFalse(standalone('That woman is the user\'s coworker'))
    def test_parse_reply_closes_truncated_json(self):
        self.assertEqual(parse_reply('{"memories":[]}'),{'memories':[]})
        self.assertEqual(parse_reply('Sure: {"memories":[]'),{'memories':[]})
        self.assertEqual(parse_reply('{"memories":[]]}'),{'memories':[]})
        self.assertEqual(parse_reply('{"memories":[{"text":"Likes tea","quote":"I like tea')['memories'][0]['quote'],'I like tea')
        with self.assertRaises(ValueError):parse_reply('no json here')

class PagingTests(unittest.TestCase):
    def test_memories_page_without_gaps(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=MemoryStore(Path(tmp))
            for n in range(130):store.remember(f'The user owns plant number {n}','personal')
            pages=[store.memories('',offset,50) for offset in (0,50,100)]
            self.assertEqual([len(p) for p in pages],[50,50,30])
            self.assertEqual(len({m['id'] for p in pages for m in p}),130)
            self.assertEqual(len(store.memories('plant',100,50)),30)
