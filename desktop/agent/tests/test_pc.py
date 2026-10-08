import fnmatch
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from fastapi import Depends, FastAPI, Header, HTTPException
import httpx

from desktop.agent.pc import PcAgent, Prompt, Reply, Settings, LOCAL, COMPUTER, ruleset, routes


def decision(settings, permission, value):
    result = 'ask'
    for rule in ruleset(settings):
        if fnmatch.fnmatchcase(permission, rule['permission']) and fnmatch.fnmatchcase(value, rule['pattern']):
            result = rule['action']
    return result


class FakePc(PcAgent):
    def __init__(self, root):
        super().__init__(root)
        self.calls = []
        self.permissions = []
        self.questions = []
        self.messages = []
        self.parents = {}
        self.failure = False
        self.stopped = 0
        self.ensured = 0

    async def models(self): return [('openrouter', 'synthetic:free'), LOCAL]
    async def ensure(self): self.ensured += 1
    async def stop(self): self.stopped += 1

    async def call(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if path == '/permission': return self.permissions
        if path == '/question': return self.questions
        if path == '/session/status': return {}
        if path == '/session' and method == 'POST': return {'id': 'ses_created', 'title': kwargs['json']['title']}
        if path.endswith('/message'): return self.messages
        if path.startswith('/session/') and method == 'GET': return {'parentID': self.parents.get(path.split('/')[-1])}
        if self.failure: raise HTTPException(409, 'Synthetic upstream failure')
        return True

    def add(self, identifier='ses_owned'):
        with self.db() as db:
            db.execute('INSERT INTO sessions VALUES(?,?,?,?,?,?)', (identifier, 'test', 'Synthetic task', time.time(), 0, 'busy'))
        return identifier


class PcTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pc = FakePc(Path(self.tmp.name))
        self.session = self.pc.add()

    async def asyncTearDown(self):
        for task in self.pc.watchers.values():
            task.cancel()
        if self.pc.watchers: await __import__('asyncio').gather(*self.pc.watchers.values(), return_exceptions=True)
        self.tmp.cleanup()

    def test_modes_and_custom_denials_preserve_permission_order(self):
        settings = Settings().model_dump()
        self.assertEqual(decision(settings, 'bash', 'gh pr list --limit 5'), 'allow')
        for command in ('sudo apt install package', 'find . -delete', 'nvidia-smi --gpu-reset', 'date --set tomorrow', 'ip route add default via 192.0.2.1', 'git fetch origin', 'rg --pre=command word', 'sort -o/tmp/owned file'):
            self.assertEqual(decision(settings, 'bash', command), 'ask', command)
        self.assertEqual(decision(settings, 'unrecognized_mcp', 'anything'), 'ask')
        settings.update(mode='ask')
        self.assertEqual(decision(settings, 'bash', 'git status'), 'ask')
        self.assertEqual(decision(settings, 'read', '/tmp/file'), 'ask')
        settings.update(mode='full', allow=['git *'], deny=['git push *'])
        self.assertEqual(decision(settings, 'bash', 'sudo apt install package'), 'allow')
        self.assertEqual(decision(settings, 'bash', 'git push origin main'), 'deny')
        self.assertEqual(decision(settings, 'bash', 'sudo mkfs.ext4 /dev/synthetic'), 'ask')

    def test_computer_off_overrides_full_mode(self):
        settings = Settings(mode='full', computer_use=False, allow=['computer']).model_dump()
        for tool in (COMPUTER + '_screenshot', COMPUTER + '_action'):
            self.assertEqual(decision(settings, tool, '*'), 'deny')

    def test_settings_are_persisted_and_invalid_modes_rejected(self):
        self.pc.save_settings(Settings(allow=[' git status ', 'git status'], deny=['git push *']).model_dump())
        self.assertEqual(self.pc.settings()['allow'], ['git status'])
        self.assertEqual(self.pc.settings_path.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(ValueError): Settings(mode='invented')

    async def test_disabled_access_blocks_new_and_existing_tasks(self):
        self.pc.save_settings(Settings(enabled=False).model_dump())
        with self.assertRaises(HTTPException): await self.pc.start(Prompt(prompt='Synthetic task'))
        with self.assertRaises(HTTPException): await self.pc.send(self.session, 'Continue')
        self.assertEqual(self.pc.calls, [])

    async def test_idempotent_start_does_not_repeat_work_and_conflicting_payload_fails(self):
        prompt = Prompt(prompt='Owned fixture', request_id='owned-1')
        first = await self.pc.start(prompt)
        second = await self.pc.start(prompt)
        self.assertEqual(first, second)
        self.assertEqual(sum(c[1] == '/session' for c in self.pc.calls), 1)
        self.assertEqual(sum(c[1].endswith('/prompt_async') for c in self.pc.calls), 1)
        with self.assertRaises(HTTPException): await self.pc.start(Prompt(prompt='Different task', request_id='owned-1'))

    async def test_restart_marks_busy_tasks_interrupted_without_replaying(self):
        restored = PcAgent(Path(self.tmp.name))
        self.assertEqual(restored.record(self.session)['state'], 'interrupted')
        self.assertIsNone(restored.process)
        self.assertEqual(restored.watchers, {})

    async def test_catalog_requires_zero_pricing_vision_and_tools(self):
        def model(identifier, price='0', image=True, tools=True):
            return {'id':identifier,'pricing':{'prompt':price,'completion':'0'},'architecture':{'input_modalities':['text','image'] if image else ['text']},'supported_parameters':['tools'] if tools else []}
        response = httpx.Response(200, json={'data':[model('owned:free'),model('paid:free',price='1'),model('noimage:free',image=False),model('notools:free',tools=False),model('notmarked')]})
        client = AsyncMock()
        client.__aenter__.return_value = client
        client.get.return_value = response
        with patch('desktop.agent.pc.httpx.AsyncClient', return_value=client):
            chain = await PcAgent.models(self.pc)
        self.assertEqual(chain, [('openrouter','owned:free'),LOCAL])

    def test_incomplete_desktop_inputs_rejected_before_touching_display(self):
        from desktop.agent.computer import Input
        for body in ({'action':'drag','x':1,'y':2},{'action':'move'},{'action':'key','keys':'+'},{'action':'click','x':float('nan'),'y':1},{'action':'type'}):
            with self.assertRaises(ValueError): Input(**body)

    async def test_child_requests_are_visible_and_foreign_requests_are_excluded(self):
        self.pc.parents['ses_child'] = self.session
        self.pc.permissions = [dict(id='p_child', sessionID='ses_child', permission='bash', patterns=['echo synthetic'], always=['echo *'], metadata={}),
                               dict(id='p_foreign', sessionID='ses_foreign', permission='bash', patterns=['echo foreign'], always=[], metadata={})]
        self.pc.questions = [dict(id='q_child', sessionID='ses_child', questions=[])]
        pending = await self.pc.pending(self.session)
        self.assertEqual([p['id'] for p in pending['permissions']], ['p_child'])
        self.assertEqual([q['id'] for q in pending['questions']], ['q_child'])
        await self.pc.reply('p_child', Reply(reply='once'))
        with self.assertRaises(HTTPException): await self.pc.reply('p_foreign', Reply(reply='always', remember=True))
        self.assertEqual(self.pc.settings()['allow'], [])

    async def test_failed_approval_never_persists_an_allow_rule(self):
        self.pc.permissions = [dict(id='p', sessionID=self.session, permission='bash', patterns=['echo synthetic'], always=['echo *'], metadata={})]
        self.pc.failure = True
        with self.assertRaises(HTTPException): await self.pc.reply('p', Reply(reply='always', remember=True))
        self.assertEqual(self.pc.settings()['allow'], [])
        self.pc.failure = False
        await self.pc.reply('p', Reply(reply='always', remember=True))
        self.assertEqual(self.pc.settings()['allow'], ['echo *'])

    async def test_cancel_prevents_automatic_fallback(self):
        await self.pc.cancel(self.session)
        self.assertEqual(self.pc.record(self.session)['state'], 'cancelled')
        self.assertFalse(await self.pc.fallback(self.session))
        self.assertEqual([c[1] for c in self.pc.calls], [f'/session/{self.session}/abort'])

    async def test_pending_approval_and_running_tools_prevent_fallback(self):
        self.pc.permissions = [dict(id='p', sessionID=self.session, permission='bash', patterns=['echo synthetic'], always=[], metadata={})]
        self.assertFalse(await self.pc.fallback(self.session))
        self.pc.permissions = []
        self.pc.messages = [{'parts': [{'type': 'tool', 'state': {'status': 'running'}}]}]
        self.assertFalse(await self.pc.fallback(self.session))
        self.assertFalse(any(c[1].endswith('/prompt_async') for c in self.pc.calls))

    async def test_local_fallback_is_text_only_and_keeps_recorded_results(self):
        self.assertTrue(await self.pc.fallback(self.session))
        request = self.pc.calls[-1][2]['json']
        self.assertEqual(request['model']['providerID'], LOCAL[0])
        self.assertIn('text-only', request['parts'][0]['text'])
        self.assertIn('never repeat completed actions', request['parts'][0]['text'])
        model = self.pc.config(await self.pc.models())['provider'][LOCAL[0]]['models'][LOCAL[1]]
        self.assertFalse(model['attachment'])

    async def test_question_ownership_is_required(self):
        self.pc.questions = [dict(id='q', sessionID='ses_foreign')]
        with self.assertRaises(HTTPException): await self.pc.question('q')
        with self.assertRaises(HTTPException): await self.pc.question('missing')

    async def test_settings_off_stops_active_tasks_and_does_not_restart_server(self):
        async def auth(authorization: str | None = Header(default=None)):
            if authorization != 'Bearer synthetic': raise HTTPException(401)
        app = FastAPI()
        routes(app, [Depends(auth)], self.pc)
        sudo = SimpleNamespace(wait=AsyncMock(return_value=1))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
            self.assertEqual((await client.get('/workspace/pc/sessions')).status_code, 401)
            with patch('desktop.agent.pc.asyncio.create_subprocess_exec', AsyncMock(return_value=sudo)):
                response = await client.put('/workspace/pc/settings', headers={'Authorization': 'Bearer synthetic'}, json=Settings(enabled=False).model_dump())
            self.assertEqual(response.status_code, 200)
            self.assertFalse(response.json()['ready'])
        self.assertEqual(self.pc.record(self.session)['state'], 'cancelled')
        self.assertEqual(self.pc.stopped, 1)
        self.assertEqual(self.pc.ensured, 0)
