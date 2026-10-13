"""Saved browser chats use the Android tool set; confirmed tools wait for the user's answer on a card."""
import asyncio
import contextlib
import json
import tempfile
import unittest

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from desktop.workspace.workspace import install
from desktop.workspace.chat_tools import ChatTools, sources


def chat(text='Remember that I like tea'):
    return {'version': 1, 'summary': {'id': 'c0ffee00-0000-4000-8000-000000000001', 'title': 'Tea', 'createdAt': 1, 'updatedAt': 1},
            'messages': [{'role': 'user', 'content': text}]}


class ChatToolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = FastAPI()
        self.script = []
        self.inputs = []
        self.calls = []
        self.models = []
        async def auth(authorization: str = Header(default='')):
            if authorization != 'Bearer synthetic': raise HTTPException(401)
        async def model(messages, tools, model_id=None):
            self.inputs.append((json.loads(json.dumps(messages)), tools))
            self.models.append(model_id)
            return self.script.pop(0)
        async def search(query): return {'results': []}
        async def fetch(url): return {'markdown': ''}
        self.workspace = install(self.app, [Depends(auth)], self.temp.name, model, search, fetch)
        async def loopback(method, path, body=None):
            self.calls.append((method, path, body))
            if path == '/grounding':
                return {'evidence': [{'title': 'Tea facts', 'url': 'https://example.org/tea', 'status': 'retrieved', 'passages': ['Tea is a drink.']}]}
            return {'id': 'm1', 'text': (body or {}).get('text', '')}
        self.workspace.loopback = loopback
        self.life = self.app.router.lifespan_context(self.app)
        await self.life.__aenter__()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url='http://gateway', headers={'Authorization': 'Bearer synthetic'})

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.life.__aexit__(None, None, None)
        self.temp.cleanup()

    @staticmethod
    def call(name, args, call_id='call-1'):
        return {'role': 'assistant', 'content': '', 'tool_calls': [{'id': call_id, 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(args)}}]}

    async def start(self, text='Remember that I like tea'):
        response = await self.client.post('/workspace/items/' + chat()['summary']['id'] + '/continue', json={'revision': 0, 'payload': {'chat': json.dumps(chat(text))}})
        self.assertEqual(response.status_code, 200)
        return response.json()['id']

    async def until(self, job_id, *statuses):
        for _ in range(200):
            job = next(j for j in self.workspace.jobs() if j['id'] == job_id)
            if job['status'] in statuses: return job
            await asyncio.sleep(.05)
        self.fail(f'job stayed {job["status"]}')

    def saved(self):
        return json.loads(self.workspace.items()[0]['payload']['chat'])['messages']

    async def test_confirmed_tool_waits_for_approval_then_runs_once(self):
        self.script = [self.call('remember', {'text': 'User likes tea'}), {'role': 'assistant', 'content': 'Saved.'}]
        job_id = await self.start()
        job = await self.until(job_id, 'waiting')
        self.assertEqual(job['pending']['tool'], 'remember')
        self.assertEqual(job['pending']['call_id'], 'call-1')
        self.assertIn('User likes tea', job['pending']['prompt'])
        self.assertEqual(self.calls, [])
        # The chat model is offered the Android tool set.
        names = {t['function']['name'] for t in self.inputs[0][1]}
        self.assertTrue({'remember', 'run_on_pc', 'start_code_task', 'read_account_inbox', 'generate_image', 'web_search'} <= names)
        stale = await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'other', 'approve': True})
        self.assertEqual(stale.status_code, 409)
        answer = await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'call-1', 'approve': True})
        self.assertEqual(answer.status_code, 200)
        await self.until(job_id, 'completed')
        self.assertEqual(self.calls, [('POST', '/workspace/memory/memories', {'text': 'User likes tea'})])
        roles = [m['role'] for m in self.saved()]
        self.assertEqual(roles, ['user', 'tool_call', 'tool_result', 'assistant'])
        self.assertTrue(self.saved()[2]['success'])
        self.assertEqual(self.saved()[-1]['content'], 'Saved.')
        again = await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'call-1', 'approve': True})
        self.assertEqual(again.status_code, 409)

    async def test_denied_tool_never_runs_and_the_model_is_told(self):
        self.script = [self.call('run_on_pc', {'prompt': 'delete files'}), {'role': 'assistant', 'content': 'Okay, I will not.'}]
        job_id = await self.start('Clean my PC')
        await self.until(job_id, 'waiting')
        await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'call-1', 'approve': False})
        await self.until(job_id, 'completed')
        self.assertEqual(self.calls, [])
        result = self.saved()[2]
        self.assertFalse(result['success'])
        self.assertIn('declined', result['content'])
        self.assertEqual(self.inputs[-1][0][-1]['role'], 'tool')

    async def test_unconfirmed_tools_run_with_sources_and_stream_entries(self):
        self.script = [self.call('web_search', {'query': 'tea'}), {'role': 'assistant', 'content': 'Tea is a drink [1].'}]
        job_id = await self.start('What is tea?')
        await self.until(job_id, 'completed')
        result = self.saved()[2]
        self.assertEqual(result['sources'], [{'title': 'Tea facts', 'url': 'https://example.org/tea', 'contentRead': True}])
        # Display-only sources never reach the model.
        self.assertNotIn('sources', self.inputs[-1][0][-1])

    async def test_waiting_job_streams_its_steps_and_cancel_keeps_completed_ones(self):
        self.script = [self.call('search_memory', {'query': 'tea'}, 'a'), self.call('remember', {'text': 'tea'}, 'b')]
        job_id = await self.start()
        await self.until(job_id, 'waiting')
        # The in-process transport returns a stream only once it ends, so cancel after it has started.
        stream = asyncio.create_task(self.client.get(f'/workspace/jobs/{job_id}/events'))
        await asyncio.sleep(.5)
        await self.client.post(f'/workspace/jobs/{job_id}/cancel')
        events = [json.loads(line[6:]) for line in (await asyncio.wait_for(stream, 10)).text.splitlines() if line.startswith('data: ')]
        event = events[0]
        self.assertFalse(event['done'])
        self.assertEqual(event['status'], 'waiting')
        self.assertEqual(event['approval']['call_id'], 'b')
        self.assertEqual([e['role'] for e in event['entries']], ['tool_call', 'tool_result', 'tool_call'])
        self.assertTrue(events[-1]['done'])
        self.assertEqual([m['role'] for m in self.saved()], ['user', 'tool_call', 'tool_result'])
        self.assertEqual(len(self.calls), 1)

    async def test_restart_resumes_waiting_jobs_without_replaying_finished_tools(self):
        self.script = [{'role': 'assistant', 'content': '', 'tool_calls': [
            {'id': 'a', 'type': 'function', 'function': {'name': 'search_memory', 'arguments': '{"query":"tea"}'}},
            {'id': 'b', 'type': 'function', 'function': {'name': 'remember', 'arguments': '{"text":"tea"}'}}]},
            {'role': 'assistant', 'content': 'Done.'}]
        job_id = await self.start()
        await self.until(job_id, 'waiting')
        self.assertEqual(len(self.calls), 1)
        await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'b', 'approve': True})
        await self.until(job_id, 'completed')
        self.assertEqual([c[1] for c in self.calls], ['/workspace/memory/tool-search?kind=memory&q=tea', '/workspace/memory/memories'])
        self.assertEqual([m['role'] for m in self.saved()], ['user', 'tool_call', 'tool_call', 'tool_result', 'tool_result', 'assistant'])

    async def test_manual_image_settings_override_the_models_choices(self):
        self.script = [self.call('generate_image', {'prompt': 'a cat', 'width': 512, 'height': 512}), {'role': 'assistant', 'content': 'Here it is.'}]
        async def loopback(method, path, body=None):
            self.calls.append((method, path, body))
            return {'status': 'completed', 'artifact': {'id': 'f' * 32}} if method == 'GET' else {'status': 'queued'}
        self.workspace.loopback = loopback
        response = await self.client.post('/workspace/items/' + chat()['summary']['id'] + '/continue', json={
            'revision': 0, 'payload': {'chat': json.dumps(chat('Draw a cat'))},
            'image_settings': {'width': 1024, 'height': 576, 'steps': 6, 'seed': 7, 'transparent': False, 'reference_file_ids': []}})
        self.assertEqual(response.status_code, 200)
        await self.until(response.json()['id'], 'completed')
        submitted = next(body for method, path, body in self.calls if method == 'POST' and path == '/workspace/images')
        self.assertEqual((submitted['width'], submitted['height'], submitted['steps'], submitted['seed']), (1024, 576, 6, 7))
        self.assertEqual(submitted['prompt'], 'a cat')
        invalid = await self.client.post('/workspace/items/' + chat()['summary']['id'] + '/continue', json={
            'revision': 1, 'payload': {'chat': json.dumps(chat())}, 'image_settings': {'width': 100}})
        self.assertEqual(invalid.status_code, 422)

    async def test_web_calls_carrying_private_details_ask_first(self):
        class Memory:
            def context(self, *args, **kwargs):
                return {'context': '\n\nRetrieved personal context. Approved memories are user-reviewed facts.\nApproved memory [m1]: The user\'s sister Priya lives at 1420 Oak Hollow Dr.', 'sources': []}
        self.workspace.memory_store = Memory()
        self.script = [self.call('web_search', {'query': 'houses near 1420 Oak Hollow Dr'}), {'role': 'assistant', 'content': 'Done.'}]
        job_id = await self.start('Find houses near my sister')
        job = await self.until(job_id, 'waiting')
        self.assertIn('1420 Oak Hollow Dr', job['pending']['prompt'])
        self.assertEqual(job['pending']['title'], 'Send private details to the web?')
        self.assertEqual(self.calls, [])
        await self.client.post(f'/workspace/jobs/{job_id}/approval', json={'call_id': 'call-1', 'approve': False})
        await self.until(job_id, 'completed')
        self.assertEqual(self.calls, [])
        self.assertIn('declined', self.saved()[2]['content'])
        # Recalled memories sit last in the system prompt; project text is not mistaken for them.
        self.assertTrue(self.inputs[0][0][0]['content'].endswith('1420 Oak Hollow Dr.'))

    async def test_previous_private_results_still_require_web_approval(self):
        prior = chat('Find my contact')
        prior['messages'] += [
            {'role': 'tool_call', 'id': 'old', 'name': 'search_memory', 'arguments': '{}'},
            {'role': 'tool_result', 'id': 'old', 'name': 'search_memory', 'content': 'Contact: priya.k@example.org', 'success': True},
            {'role': 'assistant', 'content': 'Found your contact.'},
            {'role': 'user', 'content': 'Look her up'},
        ]
        for private in (False, True):
            with self.subTest(private=private):
                self.script = [self.call('web_search', {'query': 'priya.k@example.org'})]
                self.inputs.clear()
                if private:
                    stream = asyncio.create_task(self.client.post('/workspace/chat/ephemeral', json={'chat': json.dumps(prior)}))
                    try:
                        for _ in range(100):
                            await asyncio.sleep(.01)
                            if self.inputs: break
                        await asyncio.sleep(.1)
                        self.assertFalse(stream.done(), 'Private search should wait for approval')
                    finally:
                        stream.cancel()
                        with contextlib.suppress(asyncio.CancelledError): await stream
                else:
                    response = await self.client.post('/workspace/items/' + prior['summary']['id'] + '/continue', json={'revision': 0, 'payload': {'chat': json.dumps(prior)}})
                    job_id = response.json()['id']
                    job = await self.until(job_id, 'waiting')
                    self.assertEqual(job['pending']['title'], 'Send private details to the web?')
                    await self.client.post(f'/workspace/jobs/{job_id}/cancel')
                self.assertEqual(self.calls, [])
                self.assertNotIn('private_context', json.dumps(self.inputs))

    async def test_public_details_go_without_asking_after_the_model_judges_them(self):
        class Memory:
            def context(self, *args, **kwargs):
                return {'context': '\n\nRetrieved personal context. Approved memories are user-reviewed facts.\nApproved memory [m1]: The user lives in Plano.', 'sources': []}
        self.workspace.memory_store = Memory()
        self.script = [self.call('web_search', {'query': 'weather Plano'}), {'role': 'assistant', 'content': 'OK'}, {'role': 'assistant', 'content': 'Sunny.'}]
        job_id = await self.start("What's the weather?")
        await self.until(job_id, 'completed')
        self.assertEqual(self.calls[0][1], '/grounding')
        self.assertIn('IDENTIFYING or OK', self.inputs[1][0][0]['content'])

    async def test_no_search_requests_block_web_tools_and_turn_notes_are_never_saved(self):
        self.script = [self.call('web_search', {'query': 'tea'}), {'role': 'assistant', 'content': 'Offline answer.'}]
        response = await self.client.post('/workspace/items/' + chat()['summary']['id'] + '/continue', json={
            'revision': 0, 'payload': {'chat': json.dumps(chat("Don't search, what is tea?"))}, 'block_web': True,
            'turn_note': '\nPublic evidence retrieval was attempted for this question.', 'model': 'qwen-small'})
        await self.until(response.json()['id'], 'completed')
        self.assertEqual(self.calls, [])
        self.assertIn('disabled', self.saved()[2]['content'])
        self.assertTrue(self.inputs[0][0][1]['content'][-1]['text'].startswith('\nPublic evidence'))
        self.assertNotIn('Public evidence', json.dumps(self.saved()))
        self.assertEqual(self.models[0], 'qwen-small')
        bad = await self.client.post('/workspace/items/' + chat()['summary']['id'] + '/continue', json={
            'revision': 1, 'payload': {'chat': json.dumps(chat())}, 'model': 'bad model; rm'})
        self.assertEqual(bad.status_code, 422)

    async def test_private_chats_use_memories_when_asked_and_wait_for_approvals(self):
        class Memory:
            def context(self, query, scope, current, include_situations=True, include_history=True):
                assert not include_history and not include_situations
                return {'context': '\n\nRetrieved personal context. Approved memories are user-reviewed facts.\nApproved memory [m1]: Contact Priya at priya.k@example.org.', 'sources': []}
        self.workspace.memory_store = Memory()
        self.script = [self.call('web_search', {'query': 'priya.k@example.org'}), {'role': 'assistant', 'content': 'Kept it private.'}]
        private = {'version': 1, 'summary': {'id': 'p', 'title': 'P', 'createdAt': 1, 'updatedAt': 1}, 'messages': [{'role': 'user', 'content': 'Look her up'}]}
        stream = asyncio.create_task(self.client.post('/workspace/chat/ephemeral', json={'chat': json.dumps(private), 'use_memory': True}))
        for _ in range(100):
            await asyncio.sleep(.05)
            if self.inputs: break
        await asyncio.sleep(.3)
        names = {t['function']['name'] for t in self.inputs[0][1]}
        self.assertEqual(names, {'web_search', 'fetch_page', 'create_file', 'generate_image', 'search_memory'})
        self.assertIn('priya.k@example.org', self.inputs[0][0][0]['content'])
        # Only the waiting stream's own session can answer.
        denied = await self.client.post('/workspace/chat/ephemeral/unknown/approval', json={'call_id': 'call-1', 'approve': True})
        self.assertEqual(denied.status_code, 409)
        stream.cancel()
        with contextlib.suppress(asyncio.CancelledError): await stream
        self.assertEqual(self.calls, [])
        self.assertEqual(self.workspace.items(), [])


class ToolSpecTests(unittest.IsolatedAsyncioTestCase):
    async def test_arguments_are_validated_before_any_request(self):
        made = []
        async def call(*args): made.append(args); return {}
        tools = ChatTools(None, call, None, None)
        for name, args in [('fetch_page', {'url': 'http://example.org'}), ('read_account_inbox', {'account_id': 'nope'}),
                           ('get_code_task', {'id': '../x'}), ('read_account_calendar', {'account_id': 'a' * 32, 'start': '2026-01-01T00:00:00+00:00', 'end': '2026-03-01T00:00:00+00:00'}),
                           ('start_code_task', {'repo': 'not a repo', 'prompt': 'x'})]:
            with self.assertRaises(Exception, msg=name):
                await tools.run(name, args)
        self.assertEqual(made, [])
        self.assertTrue(tools.needs_confirmation('read_account_inbox'))
        self.assertFalse(tools.needs_confirmation('list_tasks'))

    def test_sources_only_keep_public_https_links(self):
        self.assertEqual(sources('web_search', {'evidence': [{'url': 'javascript:alert(1)'}, {'url': 'https://a.example/x', 'title': 'A'}]}),
                         [{'title': 'A', 'url': 'https://a.example/x', 'contentRead': False}])
        self.assertEqual(sources('remember', {'url': 'https://a.example'}), [])


class PrivateLeakTests(unittest.TestCase):
    """The same cases as PrivateLeakTest.kt, so the server and Android decide alike."""
    memories = ["The user's sister Priya works at Baylor Scott & White. Contact: priya.k@example.org, 214-555-0182."]

    def test_everyday_words_and_the_users_own_words_are_fine(self):
        from desktop.workspace.private_leak import details
        self.assertEqual(details('best hospitals near me 2026', self.memories, 'Which hospitals are best near me?'), [])
        self.assertEqual(details('Baylor Scott White reviews', self.memories, 'Is Baylor Scott & White any good?'), [])
        self.assertEqual(details('anything at all', [], ''), [])

    def test_details_from_private_context_are_flagged(self):
        from desktop.workspace.private_leak import details
        self.assertEqual(details('Priya nurse schedule', self.memories, 'When does my sister work?'), ['priya'])
        self.assertEqual(details('priya.k@example.org', self.memories, "Look up my sister's email"), ['priya.k@example.org'])
        self.assertIn('214-555-0182', details('who owns 214-555-0182', self.memories, 'whose number is that'))

    def test_contact_addresses_and_ids_are_identifying_on_their_own(self):
        from desktop.workspace.private_leak import find
        inbox = ['Order 88231447 ships to 1420 Oak Hollow Dr. Questions: priya.k@example.org']
        identifying, _, _ = find('track 88231447 1420 Oak Hollow Dr priya.k@example.org', inbox, 'Where is my package?')
        self.assertEqual(identifying, ['priya.k@example.org', '1420 Oak Hollow Dr', '88231447'])
        identifying, other, context = find('weather Plano', ['The user lives in Plano.'], "What's the weather?")
        self.assertEqual((identifying, other, context), ([], ['plano'], ['The user lives in Plano.']))

    def test_only_a_clear_ok_lets_details_through_and_recall_skips_its_introduction(self):
        from desktop.workspace.private_leak import judged_identifying, recalled
        self.assertEqual([judged_identifying(r) for r in ('OK', 'IDENTIFYING', 'Not sure', None)], [False, True, True, True])
        prompt = 'System prompt.\n\nRetrieved personal context. Approved memories are user-reviewed facts.\n\nThe user lives in Plano.'
        self.assertEqual(recalled(prompt), 'The user lives in Plano.')
        self.assertIsNone(recalled('No memories here.'))


if __name__ == '__main__':
    unittest.main()
