"""Add the optional cloud agent to the existing gateway without model changes.

Requires an idle gateway with background inference paused. Backs up the fresh
gateway source and adds a small hook instead of overwriting Claude's adapter.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server-root', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--restart', action='store_true')
    args = parser.parse_args()
    server = Path(args.server_root).resolve()
    environment = {}
    for line in (server / '.env').read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            environment[key] = value.strip().strip('\"\'')
    headers = {'Authorization': 'Bearer ' + environment['ASSISTANT_API_TOKEN']}
    with httpx.Client(base_url='http://127.0.0.1:8700', headers=headers, timeout=5) as client:
        state = client.get('/workspace/images/health')
        state.raise_for_status()
        state = state.json()
        if state['phase'] != 'chat_ready' or state['active_chat_requests'] != 0 or not state['maintenance_paused']:
            raise RuntimeError('Gateway must be idle with background inference paused')
        if not (server / 'workspace-data/background-inference.paused').is_file():
            raise RuntimeError('Persistent GPU maintenance pause is required')
        agent_health=client.get('/workspace/agent/health')
        if agent_health.status_code==200 and agent_health.json().get('active_outgoing',0):
            raise RuntimeError('Account/outgoing host work is active; defer deployment until it finishes')
        agent_db = server / 'workspace-data/agent/agent.sqlite'
        if agent_db.exists():
            with sqlite3.connect(f'file:{agent_db}?mode=ro', uri=True) as db:
                if db.execute("SELECT COUNT(*) FROM tasks WHERE status IN ('approved','running')").fetchone()[0]:
                    raise RuntimeError('Agent work is active; defer deployment until it finishes')
                if db.execute("SELECT COUNT(*) FROM actions WHERE status IN ('approved','claimed')").fetchone()[0]:
                    raise RuntimeError('Outgoing approval/submission is active; defer deployment until it finishes')
        # Validate the pinned image and credential configuration before gateway edits.
        from .engine import OpenCodeEngine, docker_command
        engine = OpenCodeEngine(args.image, lambda: None)
        response = subprocess.check_output(docker_command(['image', 'inspect', engine.image, '--format', '{{.Id}}']), text=True).strip()
        if response != args.image: raise RuntimeError('Pinned agent image is unavailable')
        credential = json.loads((Path.home() / '.local/share/opencode/auth.json').read_text()).get('openrouter', {})
        if not credential.get('key'): raise RuntimeError('OpenRouter credential is missing')
        gateway = server / 'api/app.py'
        old = gateway.read_bytes()
        source = old.decode()
        marker = '# ---- FRIDAY isolated cloud agent ----'
        if marker not in source:
            source += '''\n\n# ---- FRIDAY isolated cloud agent ----
from agent.integration import enable as enable_friday_agent
from pathlib import Path as FridayAgentPath
friday_agent_store = enable_friday_agent(app, auth,
    FridayAgentPath(__file__).resolve().parent.parent / "workspace-data" / "agent", search)
'''
        ast.parse(source)
        backup = server / 'api' / ('before-cloud-agent-' + str(time.time_ns()))
        backup.mkdir(mode=0o700)
        shutil.copy2(gateway, backup / 'app.py')
        target = server / 'api/agent'
        if target.exists(): shutil.copytree(target, backup / 'agent')
        target.mkdir(exist_ok=True, mode=0o700)
        for file in Path(__file__).parent.glob('*.py'):
            if file.name not in ('build_image.py', 'deploy.py'): shutil.copy2(file, target / file.name)
        data = server / 'workspace-data/agent'
        data.mkdir(exist_ok=True, mode=0o700)
        runtime = data / 'runtime.json'
        if runtime.exists(): shutil.copy2(runtime, backup / 'runtime.json')
        temporary = runtime.with_suffix('.tmp')
        temporary.write_text(json.dumps({'image': args.image, 'model': 'openrouter/free'}, indent=2) + '\n')
        temporary.chmod(0o600)
        temporary.replace(runtime)
        if hashlib.sha256(gateway.read_bytes()).digest() != hashlib.sha256(old).digest():
            raise RuntimeError('Gateway changed concurrently; hook was not written')
        temporary = gateway.with_suffix('.agent-tmp')
        temporary.write_text(source)
        temporary.chmod(gateway.stat().st_mode & 0o777)
        temporary.replace(gateway)
        if args.restart:
            subprocess.run(['systemctl', '--user', 'restart', 'assistant-api.service'], check=True)
            for _ in range(40):
                try:
                    response = client.get('/workspace/agent/health')
                    if response.status_code == 200:
                        if not response.json()['ready']: raise RuntimeError('Agent runtime is not ready')
                        print('Authenticated cloud agent is ready; API service restarted only')
                        break
                except httpx.TransportError: pass
                time.sleep(.25)
            else: raise RuntimeError('Gateway did not become ready after restart')
        print('Fresh gateway backed up; model/prompt-cache files unchanged')


if __name__ == '__main__': main()
