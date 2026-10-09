import fnmatch
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from fastapi import Depends, FastAPI, Header, HTTPException
import httpx

from desktop.agent.pc import PcAgent, Prompt, Reply, Settings, LOCAL, COMPUTER, ruleset, routes, PcSchedule


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
        self.created = 0

    async def models(self): return [('opencode', 'synthetic:free'), LOCAL]
    async def ensure(self): self.ensured += 1
    async def stop(self): self.stopped += 1

    async def call(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if path == '/permission': return self.permissions
        if path == '/question': return self.questions
        if path == '/session/status': return {}
        if path == '/session' and method == 'POST':
            self.created += 1
            return {'id': f'ses_created_{self.created}', 'title': kwargs['json']['title']}
        if path.endswith('/message'): return self.messages
        if path.startswith('/session/') and method == 'GET': return {'parentID': self.parents.get(path.split('/')[-1])}
        if self.failure: raise HTTPException(409, 'Synthetic upstream failure')
        return True

    def add(self, identifier='ses_owned'):
        with self.db() as db:
            db.execute('INSERT INTO sessions(id, source, title, created, model, state) VALUES(?,?,?,?,?,?)', (identifier, 'test', 'Synthetic task', time.time(), 0, 'busy'))
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

    def test_operator_mcp_servers_merge_and_fail_closed(self):
        self.assertEqual(self.pc.mcp_servers(), {})
        manifest = self.pc.root / 'mcp.json'
        manifest.write_text(json.dumps({'servers': {
            'github': {'type': 'local', 'command': ['synthetic-mcp', 'serve'], 'environment': {'GITHUB_TOKEN': 'synthetic'}, 'enabled': True},
            'notion': {'type': 'remote', 'url': 'https://mcp.notion.com/mcp', 'enabled': True},
            'off': {'type': 'local', 'command': ['synthetic-off'], 'enabled': False},
            'Bad Name!': {'type': 'local', 'command': ['synthetic-bad']},
            'friday-computer': {'type': 'local', 'command': ['synthetic-reserved']},
            'plain': {'type': 'remote', 'url': 'http://mcp.example.com/mcp'},
        }}))
        servers = self.pc.mcp_servers()
        self.assertEqual(set(servers), {'github', 'notion'})
        self.assertEqual(servers['github'], {'type': 'local', 'command': ['synthetic-mcp', 'serve'], 'environment': {'GITHUB_TOKEN': 'synthetic'}, 'enabled': True})
        self.assertEqual(servers['notion'], {'type': 'remote', 'url': 'https://mcp.notion.com/mcp', 'headers': {}, 'enabled': True})
        for bad in ('not json', '[]', '{}', '{"servers":[]}', '{"servers":{' + ','.join(f'"s{i}":{{"type":"local","command":["x"]}}' for i in range(11)) + '}}'):
            manifest.write_text(bad)
            self.assertEqual(self.pc.mcp_servers(), {}, bad[:40])
        manifest.unlink()
        self.assertEqual(self.pc.mcp_servers(), {})

    def test_operator_mcp_servers_merge_into_opencode_config(self):
        (self.pc.root / 'mcp.json').write_text(json.dumps({'servers': {'github': {'type': 'local', 'command': ['synthetic-mcp']}}}))
        config = self.pc.config([('opencode', 'synthetic:free'), LOCAL])
        self.assertIn('friday-computer', config['mcp'])
        self.assertEqual(config['mcp']['github'], {'type': 'local', 'command': ['synthetic-mcp'], 'environment': {}, 'enabled': True})
        self.assertEqual(decision(self.pc.settings(), 'mcp_github_list_repos', '*'), 'ask')

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

    def test_pc_schedule_validation_matches_agent_schedules(self):
        future = time.time() + 3600
        self.pc.schedule(PcSchedule(prompt='Owned nightly check', run_at=future))
        self.pc.schedule(PcSchedule(prompt='Owned hourly check', run_at=future, interval_seconds=3600, max_runs=5, timezone='America/New_York'))
        for body in (PcSchedule(prompt='Owned past', run_at=time.time() - 1),
                     PcSchedule(prompt='Owned quick', run_at=future, interval_seconds=30),
                     PcSchedule(prompt='Owned many', run_at=future, interval_seconds=900, max_runs=101),
                     PcSchedule(prompt='Owned once', run_at=future, max_runs=2)):
            with self.assertRaises(HTTPException): self.pc.schedule(body)
        with self.assertRaises(HTTPException):
            self.pc.schedule(PcSchedule(prompt='Owned zone', run_at=future, timezone='Invalid/Zone'))
        self.assertEqual(len(self.pc.scheduled()), 2)

    async def test_due_pc_schedule_runs_once_and_recurs_without_bursts(self):
        created = self.pc.schedule(PcSchedule(prompt='Owned recurring check', run_at=time.time() + 3600, interval_seconds=900, max_runs=3))
        due = time.time() - 2000
        with self.pc.db() as db: db.execute('UPDATE pc_schedules SET next_run=? WHERE id=?', (due, created['id']))
        started = await self.pc.run_due_schedules()
        self.assertEqual(len(started), 1)
        record = self.pc.record(started[0]['session'])
        self.assertEqual(record['source'], 'schedule')
        state = self.pc.scheduled()[0]
        self.assertEqual(state['remaining'], 2)
        self.assertGreater(state['next_run'], time.time())
        # A retry of the same due time reuses the same session and consumes one run, never two sessions.
        with self.pc.db() as db: db.execute('UPDATE pc_schedules SET next_run=?, remaining=2 WHERE id=?', (due, created['id']))
        before = [session['id'] for session in self.pc.sessions(200)]
        again = await self.pc.run_due_schedules()
        self.assertEqual([run['session'] for run in again], [started[0]['session']])
        self.assertEqual([session['id'] for session in self.pc.sessions(200)], before)
        self.assertEqual(self.pc.scheduled()[0]['remaining'], 1)
        one_shot = self.pc.schedule(PcSchedule(prompt='Owned once', run_at=time.time() + 3600))
        with self.pc.db() as db: db.execute('UPDATE pc_schedules SET next_run=? WHERE id=?', (time.time() - 1, one_shot['id']))
        await self.pc.run_due_schedules()
        self.assertNotIn(one_shot['id'], [row['id'] for row in self.pc.scheduled()])

    async def test_pc_schedule_cancel_and_disabled_skip(self):
        created = self.pc.schedule(PcSchedule(prompt='Owned cancellable', run_at=time.time() + 3600))
        self.assertEqual(self.pc.cancel_schedule(created['id']), {'id': created['id']})
        self.assertEqual(self.pc.scheduled(), [])
        with self.assertRaises(HTTPException): self.pc.cancel_schedule('f' * 32)
        with self.assertRaises(HTTPException): self.pc.cancel_schedule('../owned')
        kept = self.pc.schedule(PcSchedule(prompt='Owned paused', run_at=time.time() + 3600))
        self.pc.save_settings(Settings(enabled=False).model_dump())
        with self.pc.db() as db: db.execute('UPDATE pc_schedules SET next_run=? WHERE id=?', (time.time() - 1, kept['id']))
        self.assertEqual(await self.pc.run_due_schedules(), [])
        self.assertEqual(len(self.pc.scheduled()), 1)

    async def test_failed_schedule_run_refunds_one_repeat_capped_at_max(self):
        created = self.pc.schedule(PcSchedule(prompt='Owned flaky check', run_at=time.time() + 3600, interval_seconds=900, max_runs=3))
        with self.pc.db() as db: db.execute('UPDATE pc_schedules SET next_run=? WHERE id=?', (time.time() - 1, created['id']))
        started = await self.pc.run_due_schedules()
        self.assertEqual(self.pc.scheduled()[0]['remaining'], 2)
        with self.pc.db() as db: db.execute("UPDATE sessions SET state='error' WHERE id=?", (started[0]['session'],))
        await self.pc.run_due_schedules()
        self.assertEqual(self.pc.scheduled()[0]['remaining'], 3)
        # The same failure refunds only once, and never past max_runs.
        await self.pc.run_due_schedules()
        self.assertEqual(self.pc.scheduled()[0]['remaining'], 3)
        with self.pc.db() as db: db.execute("UPDATE sessions SET state='error', refunded=0, schedule=? WHERE id=?", (created['id'], self.session))
        await self.pc.run_due_schedules()
        self.assertEqual(self.pc.scheduled()[0]['remaining'], 3)

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

    async def test_catalog_requires_zero_pricing_and_tools_and_includes_strong_text_models(self):
        from desktop.agent.tests.test_model_ranking import model, metadata
        definitions = metadata([model('owned-free'), model('paid-free', price='1'),
                                model('noimage-free', vision=False), model('notools-free', tools=False)])
        async def respond(request):
            if request.url.host == 'models.dev': return httpx.Response(200, json=definitions)
            if request.url.host == 'opencode.ai': return httpx.Response(200, json={'data': [{'id': name} for name in definitions['opencode']['models']]})
            if request.url.path.endswith('/models'): return httpx.Response(200, json={'data': []})
            return httpx.Response(200, json={'data': [{'source': 'artificial-analysis', 'model_permaslug': 'owned-free', 'intelligence_index': 40}]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            self.pc.ranking.client = client
            self.pc.ranking.key = lambda: 'fixture'
            chain = await PcAgent.models(self.pc)
            self.assertEqual(chain, [('opencode','owned-free'),('opencode','noimage-free'),LOCAL])
            self.pc.save_settings(Settings(computer_use=False).model_dump())
            chain = await PcAgent.models(self.pc)
            self.assertEqual(chain, [('opencode','owned-free'),('opencode','noimage-free'),LOCAL])

    async def test_live_model_identity_and_fallback_order_are_pinned_until_human_followup(self):
        a, b, c = [('opencode', 'v/'+name+':free') for name in ('a','b','c')]
        self.pc.models = AsyncMock(return_value=[a,b,LOCAL])
        await self.pc.send(self.session, 'Owned first turn')
        self.pc.models.return_value = [c,a,b,LOCAL]
        self.assertEqual((await self.pc.view(self.session))['model'], a[1])
        self.assertTrue(await self.pc.fallback(self.session))
        self.assertEqual(self.pc.calls[-1][2]['json']['model']['modelID'], b[1])
        self.assertEqual((await self.pc.view(self.session))['model'], b[1])
        await self.pc.send(self.session, 'Owned human followup')
        self.assertEqual(self.pc.calls[-1][2]['json']['model']['modelID'], c[1])
        self.assertEqual(self.pc.record(self.session)['model'], 0)

    async def test_saved_fallback_cannot_reintroduce_removed_or_paid_model(self):
        a, b = [('opencode','v/'+name+':free') for name in ('a','b')]
        self.pc.models = AsyncMock(return_value=[a,b,LOCAL])
        await self.pc.send(self.session, 'Owned first turn')
        self.pc.models.return_value = [a,LOCAL]
        self.assertTrue(await self.pc.fallback(self.session))
        self.assertEqual(self.pc.calls[-1][2]['json']['model']['providerID'], LOCAL[0])
        self.assertEqual(self.pc.record(self.session)['model'], 2)

    async def test_upgrading_index_based_history_preserves_previous_model(self):
        self.pc.config_path.write_text(json.dumps({'provider':{'openrouter':{'models':{'v/old-first:free':{},'v/old-next:free':{}}}}}))
        with self.pc.db() as db: db.execute('UPDATE sessions SET model=1 WHERE id=?', (self.session,))
        restored = PcAgent(Path(self.tmp.name))
        self.assertEqual((await restored.session_chain(self.session))[1], ('openrouter','v/old-next:free'))
        self.assertEqual(restored.record(self.session)['model'], 1)

    async def test_daily_config_change_does_not_stop_active_work(self):
        self.pc.process = SimpleNamespace(returncode=None)
        self.pc.client = AsyncMock()
        self.pc.client.get.return_value = httpx.Response(200, json={'healthy':True})
        self.pc.config_digest = 'previous-day'
        self.assertIs(await PcAgent.ensure(self.pc), self.pc.client)
        self.assertEqual(self.pc.stopped, 0)

    async def test_uncertain_live_activity_defers_config_reload(self):
        with self.pc.db() as db: db.execute("UPDATE sessions SET state='idle'")
        self.pc.process = SimpleNamespace(returncode=None)
        self.pc.client = AsyncMock()
        self.pc.client.get.side_effect = [httpx.Response(200, json={'healthy':True}), RuntimeError('Owned activity query failure')]
        self.pc.config_digest = 'previous-day'
        self.assertIs(await PcAgent.ensure(self.pc), self.pc.client)
        self.assertEqual(self.pc.stopped, 0)

    async def test_new_catalog_entries_wait_for_registration_without_hiding_existing_rank_changes(self):
        self.pc.ranking.refresh = AsyncMock()
        self.pc.ranking.ranked = lambda **kwargs: [{'id':'v/new:free'},{'id':'v/registered:free'}]
        self.pc.model_registry = {'v/registered:free'}
        self.assertEqual(await PcAgent.models(self.pc), [('opencode','v/registered:free'),LOCAL])
        self.pc.model_registry = None
        self.assertEqual((await PcAgent.models(self.pc))[0], ('opencode','v/new:free'))

    def test_zen_config_uses_capabilities_whitelist_and_public_free_credentials(self):
        self.pc.ranking.catalog['v/text-preview'] = {'input_modalities':['text'],'context':65536,'output':8192}
        model = self.pc.config([('opencode','v/text-preview'),LOCAL])['provider']['opencode']['models']['v/text-preview']
        self.assertFalse(model['attachment'])
        self.assertEqual(model['modalities']['input'], ['text'])
        self.assertEqual(model['limit'], {'context':65536,'output':8192})
        self.assertEqual(model['cost']['input'], 0)
        config = self.pc.config([('opencode','v/text-preview'),LOCAL])
        self.assertEqual(config['provider']['opencode']['options']['apiKey'], 'public')
        self.assertEqual(config['provider']['opencode']['whitelist'], ['v/text-preview'])
        self.assertNotIn('openrouter', config['enabled_providers'])
        self.assertNotIn('openrouter', config['provider'])

    def test_desktop_helper_has_ranked_vision_only_failover_and_no_permission_grants(self):
        self.pc.ranking.ranked = lambda vision=False: [{'id':name} for name in (['v/best-vision:free','v/next-vision:free'] if vision else ['v/text-preview','v/best-vision:free','v/next-vision:free'])]
        config = self.pc.config([('opencode',name) for name in ('v/text-preview','v/best-vision:free','v/next-vision:free')] + [LOCAL])
        helper = config['agent']['friday-desktop']
        self.assertEqual(config['model'], 'opencode/v/text-preview')
        self.assertEqual(helper['model'], 'friday-vision/v/best-vision:free')
        self.assertEqual(list(config['provider']['friday-vision']['models']), ['v/best-vision:free','v/next-vision:free'])
        self.assertNotIn('permission', helper)
        self.assertFalse(config['agent']['friday']['tools'][COMPUTER+'_screenshot'])
        self.pc.save_settings(Settings(computer_use=False).model_dump())
        self.assertNotIn('friday-desktop', self.pc.config([LOCAL])['agent'])

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
