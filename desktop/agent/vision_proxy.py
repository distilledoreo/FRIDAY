"""Private desktop broker: public OpenCode Zen, ranked free models, no paid credentials."""
import json
import secrets

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse, JSONResponse

from .model_ranking import ENDPOINT


def vision_payload(value, agent):
    if not isinstance(value, dict) or not isinstance(value.get('messages'), list):
        raise HTTPException(422, 'Invalid vision request')
    allowed = {row['id'] for row in agent.ranking.ranked(vision=True)}
    chain = [name for name in agent.vision_chain if name in allowed]
    if value.get('model') not in agent.vision_chain or not chain:
        raise HTTPException(400, 'Free desktop vision is unavailable')
    # Never forward caller routing, billing, plugins, endpoints or fallback overrides.
    body = {key: value[key] for key in ('messages', 'stream', 'tools', 'tool_choice', 'temperature', 'top_p',
                                       'max_tokens', 'max_completion_tokens', 'stop', 'parallel_tool_calls',
                                       'response_format', 'reasoning_effort') if key in value}
    body['model'] = chain[0]
    return body, chain


def responses_payload(body):
    """Translate ordinary chat/tool/image history for Zen's Responses-protocol models."""
    items = []
    for message in body['messages']:
        role, content = message['role'], message.get('content')
        if role == 'tool':
            items.append({'type': 'function_call_output', 'call_id': message['tool_call_id'],
                          'output': content if isinstance(content, str) else json.dumps(content)})
            continue
        if isinstance(content, list):
            converted = []
            for part in content:
                if part['type'] == 'text': converted.append({'type': 'output_text' if role == 'assistant' else 'input_text', 'text': part['text']})
                elif part['type'] == 'image_url':
                    image = part['image_url']
                    converted.append({'type': 'input_image', 'image_url': image['url'], **({'detail': image['detail']} if 'detail' in image else {})})
                else: raise ValueError('Unsupported vision content')
            content = converted
        if content: items.append({'role': role, 'content': content})
        for call in message.get('tool_calls', []):
            items.append({'type': 'function_call', 'call_id': call['id'], **call['function']})
    result = {'model': body['model'], 'input': items, 'stream': body.get('stream', False), 'store': False}
    for key in ('temperature', 'top_p', 'parallel_tool_calls'):
        if key in body: result[key] = body[key]
    if 'tools' in body:
        result['tools'] = [{'type': 'function', **tool['function']} for tool in body['tools'] if tool.get('type') == 'function']
    if 'tool_choice' in body:
        choice = body['tool_choice']
        result['tool_choice'] = {'type': 'function', 'name': choice['function']['name']} if isinstance(choice, dict) else choice
    if body.get('reasoning_effort'): result['reasoning'] = {'effort': body['reasoning_effort']}
    if body.get('max_completion_tokens') or body.get('max_tokens'):
        result['max_output_tokens'] = body.get('max_completion_tokens') or body['max_tokens']
    return result


def chat_result(value, model):
    text, calls = [], []
    for item in value.get('output', []):
        if item.get('type') == 'message': text.extend(part['text'] for part in item.get('content', []) if part.get('type') == 'output_text')
        if item.get('type') == 'function_call':
            calls.append({'id': item['call_id'], 'type': 'function', 'function': {'name': item['name'], 'arguments': item['arguments']}})
    return {'id': value.get('id', 'friday-vision'), 'object': 'chat.completion', 'model': model,
            'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': ''.join(text), **({'tool_calls': calls} if calls else {})},
                         'finish_reason': 'tool_calls' if calls else 'stop'}]}


async def chat_stream(response, model):
    """Adapt Responses SSE without replaying an inference or tool after streaming starts."""
    calls, arguments = {}, set()
    async for line in response.aiter_lines():
        if not line.startswith('data: '): continue
        raw = line[6:]
        if raw == '[DONE]': break
        event = json.loads(raw)
        kind, delta, finish = event.get('type'), {}, None
        if kind == 'response.output_text.delta': delta = {'content': event['delta']}
        elif kind == 'response.output_item.added' and event['item'].get('type') == 'function_call':
            item, output_index = event['item'], event['output_index']
            calls[output_index] = len(calls)
            initial = item.get('arguments', '')
            if initial: arguments.add(output_index)
            delta = {'tool_calls': [{'index': calls[output_index], 'id': item['call_id'], 'type': 'function',
                                    'function': {'name': item['name'], 'arguments': initial}}]}
        elif kind == 'response.function_call_arguments.delta':
            output_index = event['output_index']; arguments.add(output_index)
            delta = {'tool_calls': [{'index': calls[output_index], 'function': {'arguments': event['delta']}}]}
        elif kind == 'response.output_item.done' and event['item'].get('type') == 'function_call':
            output_index = event['output_index']
            if output_index not in arguments:
                delta = {'tool_calls': [{'index': calls[output_index], 'function': {'arguments': event['item']['arguments']}}]}
        elif kind in ('response.completed', 'response.incomplete'):
            finish = 'length' if kind == 'response.incomplete' else 'tool_calls' if calls else 'stop'
        elif kind in ('error', 'response.failed'): raise RuntimeError('Free vision stream failed')
        if delta or finish:
            yield ('data: ' + json.dumps({'id': 'friday-vision', 'object': 'chat.completion.chunk', 'model': model,
                'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}) + '\n\n').encode()
    yield b'data: [DONE]\n\n'


def install(app, agent, client_factory=None):
    router = APIRouter(prefix='/workspace/pc/vision/v1')

    @router.post('/chat/completions')
    async def completions(request: Request):
        if not request.client or request.client.host not in ('127.0.0.1', '::1') or not secrets.compare_digest(
                request.headers.get('authorization', ''), 'Bearer ' + agent.password):
            raise HTTPException(401, 'Private desktop vision')
        settings = agent.settings()
        if not settings['enabled'] or not settings['computer_use']: raise HTTPException(403, 'Desktop access is off')
        raw = await request.body()
        if len(raw) > 16 * 1024 * 1024: raise HTTPException(413, 'Vision request too large')
        try: body, chain = vision_payload(json.loads(raw), agent)
        except (ValueError, TypeError, AttributeError): raise HTTPException(422, 'Invalid vision request') from None
        client = response = None
        try:
            client = (client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(180, connect=10), follow_redirects=False)))()
            for name in chain:
                body['model'] = name
                native_responses = agent.ranking.catalog[name].get('npm') == '@ai-sdk/openai'
                try: payload = responses_payload(body) if native_responses else body
                except (ValueError, TypeError, KeyError): raise HTTPException(422, 'Unsupported vision request') from None
                try:
                    response = await client.send(client.build_request('POST', ENDPOINT + ('/responses' if native_responses else '/chat/completions'),
                        json=payload, headers={'Authorization': 'Bearer public', 'X-Title': 'FRIDAY desktop'}), stream=True)
                except httpx.HTTPError: continue
                if response.status_code == 200: break
                # Retry only before any output. No model receives already executed tools again.
                await response.aclose(); response = None
            if response is None or response.status_code != 200:
                raise HTTPException(400, 'Free vision models are unavailable or rate-limited; use the CLI or try later')
            if not body.get('stream') and native_responses:
                await response.aread()
                result = chat_result(response.json(), name)
                await response.aclose(); await client.aclose()
                return JSONResponse(result)
        except BaseException:
            if response is not None: await response.aclose()
            if client is not None: await client.aclose()
            raise

        async def stream():
            try:
                chunks = chat_stream(response, name) if native_responses else response.aiter_bytes()
                async for chunk in chunks: yield chunk
            finally:
                await response.aclose()
                await client.aclose()
        return StreamingResponse(stream(), media_type='text/event-stream' if body.get('stream') else 'application/json', headers={'Cache-Control': 'no-store'})

    app.include_router(router)
