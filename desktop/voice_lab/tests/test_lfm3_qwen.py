import asyncio
import base64
import json
import tempfile
import time
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace

from aiohttp import ClientSession, web
from aiohttp.test_utils import TestServer

from desktop.voice_lab.lfm3_qwen import Recording, lfm_response, qwen_query, speak_qwen, speech_text


class DelegationTests(unittest.TestCase):
    def test_speech_numbers_preserve_the_supplied_value_without_recalculating(self):
        self.assertEqual(speech_text('**33/95**'), 'thirty-three over ninety-five')
        self.assertEqual(speech_text('45.'), 'forty-five.')
        self.assertEqual(speech_text('It ends at 4:10 PM.'), 'It ends at four ten PM.')
        self.assertEqual(speech_text('4:05 and 4:00'), "four oh five and four o'clock")
        self.assertEqual(speech_text('The answer is 11/95.'), 'The answer is eleven over ninety-five.')
        self.assertEqual(speech_text('-3.5'), 'minus three point five')

    def test_accepts_only_one_allowlisted_call_and_preserves_numbers(self):
        query = 'Compute 17 + 28, then explain why.'
        call = '<tool_call><function=ask_qwen><parameter=query>"' + query + '"</parameter></function></tool_call>'
        self.assertEqual(qwen_query(call), query)
        self.assertEqual(qwen_query(call.replace('"' + query + '"', query)), query)
        self.assertIsNone(qwen_query('Take one small step at a time.'))

    def test_rejects_unknown_multiple_malformed_and_oversized_calls(self):
        call = '<tool_call><function=ask_qwen><parameter=query>Hello</parameter></function></tool_call>'
        for bad in [call.replace('ask_qwen', 'run_shell'), call + call,
                    call.replace('Hello', ''), call.replace('Hello', 'x' * 4001),
                    call.replace('query', 'command'), 'Sure. ' + call]:
            with self.subTest(bad=bad[:60]), self.assertRaises(ValueError):
                qwen_query(bad)


class StreamingTests(unittest.IsolatedAsyncioTestCase):
    async def test_audio_is_sent_at_microphone_speed_and_output_is_collected(self):
        received = bytearray()

        async def realtime(request):
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            await ws.send_json({'type': 'session.created', 'session': {
                'audio': {'input': {'format': {'rate': 16000}}}}})
            async for message in ws:
                event = json.loads(message.data)
                if event['type'] == 'session.update':
                    await ws.send_json({'type': 'session.updated'})
                elif event['type'] == 'input_audio_buffer.append':
                    received.extend(base64.b64decode(event['audio']))
                elif event['type'] == 'response.create':
                    await ws.send_json({'type': 'response.output_text.delta', 'delta': 'Hello.'})
                    await ws.send_json({'type': 'response.output_audio.delta',
                                        'delta': base64.b64encode(b'\0' * 4800).decode()})
                    await ws.send_json({'type': 'response.done'})
            return ws

        app = web.Application()
        app.router.add_get('/v1/realtime', realtime)
        async with TestServer(app) as server, ClientSession() as client:
            with tempfile.TemporaryDirectory() as tmp:
                source = Path(tmp) / 'question.wav'
                with wave.open(str(source), 'wb') as wav:
                    wav.setnchannels(1)
                    wav.setsampwidth(2)
                    wav.setframerate(24000)
                    wav.writeframes(b'\0' * 9600)
                start = time.perf_counter()
                text, first, recording = await lfm_response(client,
                    SimpleNamespace(lfm=str(server.make_url('')).rstrip('/')), source, start, 'Speak briefly.')
                self.assertGreaterEqual(first, .19)
                self.assertEqual(text, 'Hello.')
                self.assertEqual(len(received), 6400)
                self.assertEqual(len(recording.pcm), 4800)

    async def test_qwen_clauses_reach_tts_verbatim_before_qwen_finishes(self):
        tts_started = asyncio.Event()
        spoken = []

        async def chat(request):
            body = await request.json()
            self.assertEqual(body['messages'][-1]['content'], 'What is 17 + 28?')
            response = web.StreamResponse(headers={'Content-Type': 'text/event-stream'})
            await response.prepare(request)
            for text in ['Seventeen plus twenty-eight is forty-five. Next ', 'add the values.']:
                data = {'choices': [{'delta': {'content': text}}]}
                await response.write(('data: ' + json.dumps(data) + '\n\n').encode())
                if text.startswith('Seventeen'):
                    await asyncio.wait_for(tts_started.wait(), 2)
            await response.write(b'data: [DONE]\n\n')
            return response

        async def tts(request):
            body = await request.json()
            spoken.append(body['input'])
            tts_started.set()
            # Deliberately split in the middle of a float sample.
            response = web.StreamResponse(headers={'Content-Type': 'audio/pcm;rate=24000;encoding=float;bits=32'})
            await response.prepare(request)
            await response.write(b'\0' * 3)
            await response.write(b'\0' * 13)
            return response

        app = web.Application()
        app.router.add_post('/chat', chat)
        app.router.add_post('/tts', tts)
        async with TestServer(app) as server, ClientSession() as client:
            url = str(server.make_url('')).rstrip('/')
            recording = Recording(time.perf_counter())
            text, first, chunks = await speak_qwen(client, SimpleNamespace(api=url, lfm=url),
                                                    'What is 17 + 28?', {}, recording)
            self.assertEqual(text, 'Seventeen plus twenty-eight is forty-five. Next add the values.')
            self.assertEqual(spoken, ['Seventeen plus twenty-eight is forty-five.', 'Next add the values.'])
            self.assertEqual(len(recording.pcm), 16)
            self.assertEqual(len(chunks), 2)
            self.assertIsNotNone(first)


if __name__ == '__main__':
    unittest.main()
