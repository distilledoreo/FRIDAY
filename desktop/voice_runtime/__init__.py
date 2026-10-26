"""Optional local GPU recognition with the existing CPU model as a fallback."""

import io
from urllib.parse import urlsplit

import httpx


class HybridAsr:
    """The caller serializes recognition; an unavailable companion never disables voice."""
    def __init__(self, cpu, url, model="parakeet-gpu", client=None):
        parsed = urlsplit(url)
        if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
                or parsed.path not in ("", "/") or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("Voice ASR must use an HTTP loopback base URL")
        self.cpu, self.url, self.model = cpu, url.rstrip("/"), model
        self.client = client or httpx.Client(timeout=httpx.Timeout(15, connect=.25, pool=.25))
        self.backend = "gpu-preferred"

    def recognize(self, samples, sample_rate=16000):
        import soundfile as sf
        body = io.BytesIO()
        sf.write(body, samples, sample_rate, format="WAV", subtype="PCM_16")
        try:
            response = self.client.post(self.url + "/v1/audio/transcriptions", data={"model": self.model},
                                        files={"file": ("voice.wav", body.getvalue(), "audio/wav")})
            response.raise_for_status()
            text = response.json()["text"]
            if not isinstance(text, str):
                raise ValueError("ASR response text must be a string")
            self.backend = "gpu"
            return text
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            self.backend = "cpu"
            return self.cpu.recognize(samples, sample_rate=sample_rate)

    def close(self):
        self.client.close()
