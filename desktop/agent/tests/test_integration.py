from pathlib import Path
import tempfile
import unittest
import httpx
from fastapi import FastAPI

from desktop.agent.integration import enable


class IntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_bad_config_disables_cloud_agent_without_breaking_gateway(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'runtime.json').write_text('{bad json')
            app = FastAPI()
            async def search(**kwargs): return {}
            enable(app, [], root, search)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
                response = await client.get('/workspace/agent/health')
                self.assertEqual(response.status_code, 200)
                self.assertFalse(response.json()['ready'])
                self.assertNotIn('key', response.text.lower())
                proposed = await client.post('/workspace/agent/tasks', json={'prompt': 'Task', 'plan': ['Read']})
                self.assertEqual(proposed.status_code, 200)
