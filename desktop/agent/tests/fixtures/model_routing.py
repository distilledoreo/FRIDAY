"""Real OpenCode, owned fake cloud + fake desktop. No real account, screen, GPU or input."""
import asyncio, base64, json, socket, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import AsyncMock
from desktop.agent.pc import PcAgent, Prompt, Reply, Settings, COMPUTER, LOCAL, VISION_PROVIDER
from desktop.agent.vision_proxy import vision_payload

TEXT, VISION, NEXT = 'owned/text-preview', 'owned/vision:free', 'owned/next-vision:free'

class Model(BaseHTTPRequestHandler):
    bodies = []
    def log_message(self, *args): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.startswith('/vision/'):
            body = vision_payload(body, current_agent)
        self.bodies.append(body)
        names = [t['function']['name'] for t in body.get('tools', [])]
        used = any(m.get('role') == 'tool' for m in body['messages'])
        model = body['model']
        if model == TEXT:
            results = [str(m.get('content')) for m in body['messages'] if m.get('role')=='tool']
            if any('Owned fake desktop result checked' in text or 'Subagent failed' in text for text in results): delta = {'content': 'Owned desktop helper completed or reported rejection.'}
            elif used: delta = {'tool_calls': [{'index':0, 'id':'call_owned_task', 'type':'function', 'function': {
                'name':'task', 'arguments':json.dumps({'description':'Owned fake desktop', 'prompt':'Use only the owned fake desktop action.', 'subagent_type':'friday-desktop'})}}]}
            else: delta = {'tool_calls': [{'index':0, 'id':'call_owned_blocked', 'type':'function', 'function': {
                'name':COMPUTER+'_action', 'arguments':json.dumps({'action':'click','x':1,'y':1})}}]}
        else:
            assert model == VISION, model
            assert body.get('models') == [VISION, NEXT], 'Vision fallback list was not transmitted'
            assert body.get('provider',{}).get('max_price') == {'prompt':0,'completion':0}, 'Price cap absent'
            results = [m for m in body['messages'] if m.get('role')=='tool']
            if len(results) >= 2: delta = {'content': 'Owned fake desktop result checked.'}
            elif used: delta = {'tool_calls': [{'index':0, 'id':'call_owned_input', 'type':'function', 'function': {
                'name':COMPUTER+'_action', 'arguments':json.dumps({'action':'click','x':1,'y':1})}}]}
            else: delta = {'tool_calls': [{'index':0, 'id':'call_owned_screenshot', 'type':'function', 'function': {
                'name':COMPUTER+'_screenshot', 'arguments':'{}'}}]}
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        for value, finish in ((delta, None), ({}, 'stop' if used else 'tool_calls')):
            event = {'id':'owned_fixture', 'object':'chat.completion.chunk', 'created':int(time.time()),
                     'model': NEXT if model == VISION else TEXT, 'choices':[{'index':0,'delta':value,'finish_reason':finish}]}
            self.wfile.write(('data: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
        self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()

class OwnedPc(PcAgent):
    def config(self, chain):
        value = super().config(chain)
        value['provider']['openrouter']['options'] = {'baseURL':f'http://127.0.0.1:{server.server_port}/v1','apiKey':'owned-fixture'}
        value['provider'][VISION_PROVIDER]['options'] = {'baseURL':f'http://127.0.0.1:{server.server_port}/vision','apiKey':'owned-fixture'}
        value['mcp'][COMPUTER]['command'] = [self.python, str(self.root/'fake_desktop.py')]
        value['provider'][LOCAL[0]]['options']['baseURL'] = f'http://127.0.0.1:{server.server_port}/unused'
        return value

def port():
    with socket.socket() as s: s.bind(('127.0.0.1',0)); return s.getsockname()[1]

async def wait(agent, sid, check):
    until = time.monotonic()+50
    while time.monotonic() < until:
        task = await agent.view(sid)
        if check(task): return task
        await asyncio.sleep(.25)
    raise AssertionError('Owned routing fixture timed out: '+json.dumps(task))

async def main():
    global current_agent
    with tempfile.TemporaryDirectory(prefix='friday-owned-routing-') as tmp:
        agent = OwnedPc(tmp, port=port())
        current_agent = agent
        marker = agent.root/'fake-input.txt'
        fake = '''from pathlib import Path
import base64
from agent.computer import serve
class Owned:
 def screenshot(self):
  return base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='), 1, 1
 def act(self, body):
  Path(%r).write_text('OWNED INPUT ONLY')
  return 'Owned fake input'
serve(Owned())
''' % str(marker)
        (agent.root/'fake_desktop.py').write_text(fake)
        agent.ranking.refresh = AsyncMock()
        agent.ranking.catalog_checked = agent.ranking.scores_checked = time.time()
        agent.ranking.catalog = {name:{'id':name,'canonical_slug':name,'context':65536,'output':8192,
            'input_modalities':['text'] if name==TEXT else ['text','image']} for name in (TEXT,VISION,NEXT)}
        agent.ranking.scores = {name:{'score':score,'benchmark_name':'Owned fixture'} for name,score in ((TEXT,60),(VISION,50),(NEXT,40))}
        agent.save_settings(Settings(mode='ask_changes',computer_use=True).model_dump())
        try:
            record = await agent.start(Prompt(prompt='Exercise only the owned fake desktop.', title='Owned routing fixture'))
            sid = record['id']
            task = await wait(agent,sid,lambda t:bool(t['permissions']))
            permission = task['permissions'][0]
            assert permission['kind'] == COMPUTER+'_action', permission
            assert permission['session'] != sid, 'Vision work did not run in a child session'
            assert any('Delegate visual work' in i.get('output','') for i in task['items']), 'Parent desktop input was not blocked'
            assert not marker.exists(), 'Child input bypassed human review'
            active_process = agent.process.pid
            agent.ranking.scores[NEXT]['score'] = 70
            await agent.ensure()
            assert agent.process.pid == active_process, 'Refresh interrupted a live approval'
            assert agent.vision_chain == [VISION,NEXT], 'Live vision priority was reordered'
            agent.ranking.scores[NEXT]['score'] = 40
            await agent.reply(permission['id'], Reply(reply='once'))
            task = await wait(agent,sid,lambda t:not t['busy'] and not t['permissions'])
            assert marker.read_text() == 'OWNED INPUT ONLY'
            assert task['model'] == TEXT
            assert any('Owned desktop helper completed' in i.get('text','') for i in task['items']), task
            old_process = agent.process.pid
            # A daily reordering waits during busy work, and updates the idle helper.
            agent.ranking.scores[NEXT]['score'] = 70
            await agent.ensure()
            assert agent.process.pid != old_process, 'Idle helper did not reload its default model'
            assert json.loads(agent.config_path.read_text())['model'] == 'openrouter/'+NEXT
            assert (await agent.view(sid))['model'] == TEXT, 'History was relabeled after refresh'
            await agent.stop()
            agent.ranking.scores[NEXT]['score'] = 40
            marker.unlink()
            agent.save_settings(Settings(mode='full',computer_use=True).model_dump())
            full = await agent.start(Prompt(prompt='Exercise only the owned fake desktop.',title='Owned full access fixture'))
            task = await wait(agent,full['id'],lambda t:not t['busy'])
            assert not task['permissions'] and marker.exists(), 'Full access was not inherited by the vision helper'
            await agent.stop()
            marker.unlink()
            agent.save_settings(Settings(mode='ask',computer_use=True).model_dump())
            ask = await agent.start(Prompt(prompt='Exercise only the owned fake desktop.',title='Owned ask fixture'))
            task = await wait(agent,ask['id'],lambda t:bool(t['permissions']))
            assert task['permissions'][0]['kind'] == 'task', task['permissions']
            await agent.reply(task['permissions'][0]['id'],Reply(reply='once'))
            task = await wait(agent,ask['id'],lambda t:bool(t['permissions']))
            assert task['permissions'][0]['kind'] == COMPUTER+'_screenshot'
            await agent.reply(task['permissions'][0]['id'],Reply(reply='once'))
            task = await wait(agent,ask['id'],lambda t:bool(t['permissions']))
            assert task['permissions'][0]['kind'] == COMPUTER+'_action'
            assert not marker.exists()
            await agent.reply(task['permissions'][0]['id'],Reply(reply='reject'))
            task = await wait(agent,ask['id'],lambda t:not t['busy'] and not t['permissions'])
            assert not marker.exists(), 'Rejected child input executed'
            print(json.dumps({'strong_text_default':True,'vision_helper_used':True,'ranked_vision_fallback_transmitted':True,
                'zero_price_cap_transmitted':True,'child_input_required_human_approval':True,'idle_refresh_applied':True,
                'history_model_stable':True,'parent_desktop_input_blocked':True,'active_refresh_deferred':True,
                'full_access_inherited':True,'ask_mode_inherited':True,'rejected_input_absent':True,'owned_model_calls':len(Model.bodies)}))
        finally: await agent.stop()

server = ThreadingHTTPServer(('127.0.0.1',0), Model)
threading.Thread(target=server.serve_forever, daemon=True).start()
try: asyncio.run(main())
finally: server.shutdown(); server.server_close()
