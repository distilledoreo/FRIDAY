import unittest

import httpx
import numpy as np

from desktop.voice_runtime import HybridAsr


class Cpu:
    def __init__(self):
        self.calls = []

    def recognize(self, samples, sample_rate):
        self.calls.append((samples, sample_rate))
        return "CPU fallback"


class HybridAsrTests(unittest.TestCase):
    def test_companion_failure_falls_back_then_recovers_without_api_restart(self):
        available = False

        def respond(request):
            self.assertEqual(request.url.path, "/v1/audio/transcriptions")
            self.assertIn(b'RIFF', request.content)
            if not available:
                raise httpx.ConnectError("Companion stopped for image generation", request=request)
            return httpx.Response(200, json={"text": "GPU result"})

        cpu = Cpu()
        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            asr = HybridAsr(cpu, "http://127.0.0.1:8999", client=client)
            samples = np.zeros(1600, dtype=np.float32)
            self.assertEqual(asr.recognize(samples), "CPU fallback")
            self.assertEqual(asr.backend, "cpu")
            self.assertIs(cpu.calls[0][0], samples)
            available = True
            self.assertEqual(asr.recognize(samples), "GPU result")
            self.assertEqual(asr.backend, "gpu")
            self.assertEqual(len(cpu.calls), 1)

    def test_bad_response_or_loading_model_keeps_cpu_available(self):
        for status, body in [(503, {}), (200, {}), (200, {"text": None})]:
            with self.subTest(status=status, body=body):
                cpu = Cpu()
                with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(status, json=body))) as client:
                    asr = HybridAsr(cpu, "http://localhost:8999", client=client)
                    self.assertEqual(asr.recognize(np.zeros(1600)), "CPU fallback")

    def test_audio_cannot_be_configured_to_leave_the_computer(self):
        for url in ["https://example.com", "http://192.0.2.1", "http://user:secret@localhost", "http://localhost?token=secret"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                HybridAsr(Cpu(), url)


if __name__ == "__main__":
    unittest.main()
