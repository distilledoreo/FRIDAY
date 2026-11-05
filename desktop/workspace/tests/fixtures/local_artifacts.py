"""Opt-in local-model artifact workflow evaluation; uses only disposable synthetic data.

Run as a module with --model-url pointing to a local OpenAI-compatible server.
Writes a JSON report to --report. No gateway credentials or real documents are used.
"""
import argparse
import asyncio
import json
import re
from pathlib import Path
import tempfile
import time
import uuid
from urllib.parse import urlsplit

import httpx
from fastapi import APIRouter, FastAPI
from desktop.workspace.workspace import Workspace
from desktop.workspace.artifacts import enable
from desktop.workspace.artifact_formats import export, preview

CASES = [
    ('grammar', 'This are working.', 'Correct only the grammar. Return exactly the corrected sentence.', ['This is working.']),
    ('concise', 'At this point in time', 'Replace this phrase with the single word Now.', ['Now']),
    ('polite', 'Send the report.', 'Make this a polite request using please; keep the request about the report.', ['please', 'report']),
    ('citation', 'The rollout starts on 12 May [1].', 'Make the sentence more concise. Preserve the date and citation exactly.', ['12 May', '[1]']),
    ('terminology', 'master branch', 'Change master to main, keeping branch.', ['main branch']),
    ('unicode', 'café is open.', 'Capitalize the first letter and preserve all other text.', ['Café is open.']),
    ('emoji', '🐈', 'Replace the emoji with the single English word cat.', ['cat']),
    ('typo', 'teh', 'Correct the typo. Return the single word.', ['the']),
    ('numbers', 'We tested 24 cases and 2 failed.', 'Rephrase clearly while keeping the numbers 24 and 2 unchanged.', ['24', '2']),
    ('agreement', 'Each tests pass.', 'Correct only the grammar.', ['Each test passes.']),
]

async def evaluate(options):
    host = urlsplit(options.model_url).hostname
    if host not in {'127.0.0.1', 'localhost', '::1'}: raise ValueError('This fixture only permits a loopback local model')
    report = {'scope': 'Controlled selected-text edits, proposal review, reopen, revision restore and real DOCX/PDF export', 'attempts': []}
    async with httpx.AsyncClient(base_url=options.model_url, timeout=120) as model_client:
        models = (await model_client.get('/v1/models')).json()['data']
        model_id = models[0]['id']; report['model'] = model_id
        async def model(messages, tools):
            response = await model_client.post('/v1/chat/completions', json={'model': model_id, 'messages': messages, 'tools': tools, 'stream': False, 'temperature': .3, 'max_tokens': 2048, 'chat_template_kwargs': {'enable_thinking': False}, 'id_slot': 1, 'cache_prompt': False})
            response.raise_for_status(); return response.json()['choices'][0]['message']
        with tempfile.TemporaryDirectory(prefix='friday-artifact-eval-') as root:
            workspace = Workspace(root); app = FastAPI(); router = APIRouter(prefix='/workspace')
            enable(router, workspace, model); app.include_router(router)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://fixture', timeout=140) as client:
                for repeat in range(options.repeats):
                    for name, selected, instruction, expected in CASES:
                        started = time.monotonic(); entry = {'case': name, 'repeat': repeat + 1}
                        try:
                            made = workspace.artifacts.create({'request': uuid.uuid4().hex, 'title': 'Synthetic evaluation', 'text': '# Evaluation\n\n' + selected + '\n\nUnchanged neighboring paragraph.'})
                            aid = made['id']; original = workspace.artifacts.get(aid); block = original['content']['blocks'][1]
                            request = {'request': uuid.uuid4().hex, 'base': 1, 'block': block['id'], 'from': 0, 'to': len(selected), 'instruction': instruction}
                            response = await client.post('/workspace/artifacts/'+aid+'/suggest', json=request)
                            response.raise_for_status(); proposal = response.json()
                            assert workspace.artifacts.get(aid)['revision'] == 1, 'Suggestion changed the document before review'
                            workspace.artifacts.decide(aid, proposal['id'], {'request': uuid.uuid4().hex, 'accept': True})
                            reopened = Workspace(root).artifacts.get(aid)
                            replacement = reopened['content']['blocks'][1]['text']
                            assert reopened['content']['blocks'][0] == original['content']['blocks'][0]
                            assert reopened['content']['blocks'][2] == original['content']['blocks'][2]
                            entry.update(replacement=replacement, passed=all(bool(re.search(r'(?<!\d)'+re.escape(term)+r'(?!\d)', replacement)) if term.isdigit() else term.casefold() in replacement.casefold() for term in expected))
                            if name == 'citation':
                                word = await asyncio.to_thread(export, workspace, aid, {'revision': 2, 'format': 'docx'})
                                rendered = await asyncio.to_thread(preview, workspace, word['file']['id'], 0)
                                assert rendered['pages'] >= 1
                                pdf = await asyncio.to_thread(export, workspace, aid, {'revision': 2, 'format': 'pdf'})
                                assert (workspace.files / pdf['file']['id']).read_bytes().startswith(b'%PDF')
                                entry['rendered_exports'] = True
                            workspace.artifacts.save(aid, {'request': uuid.uuid4().hex, 'base': 2, 'restore': 1})
                            assert workspace.artifacts.get(aid)['content'] == original['content']
                        except Exception as error: entry.update(passed=False, error=str(error)[:800])
                        entry['seconds'] = round(time.monotonic() - started, 2); report['attempts'].append(entry)
                        Path(options.report).write_text(json.dumps(report, indent=2, ensure_ascii=False))
    report['passed'] = sum(e['passed'] for e in report['attempts']); report['total'] = len(report['attempts'])
    Path(options.report).write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps({'passed': report['passed'], 'total': report['total'], 'seconds': round(sum(e['seconds'] for e in report['attempts']), 1)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--model-url', default='http://127.0.0.1:8080'); parser.add_argument('--repeats', type=int, default=3); parser.add_argument('--report', required=True)
    asyncio.run(evaluate(parser.parse_args()))
