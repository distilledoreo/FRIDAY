import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from desktop.imagegen.configure_dedicated import main, verified


class DedicatedProfileTests(unittest.TestCase):
    def test_placement_migration_preserves_four_step_lightning_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {}
            for name in ('binary', 'transformer', 'encoder', 'vision', 'vae', 'adapter.safetensors'):
                paths[name] = root / name
                paths[name].write_bytes(name.encode())
            original = {'command': ['old-server', '--steps', '4', '--cfg-scale', '1',
                                     '--lora-model-dir', str(root)],
                        'files': [verified(paths['adapter.safetensors'])],
                        'sampling_profile': 'qwen-image-2.1-lightning',
                        'sample_params': {'sample_method': 'euler', 'sample_steps': 4,
                                           'guidance': {'txt_cfg': 1}},
                        'lora': [{'path': 'adapter.safetensors', 'multiplier': 1.0}]}
            base, output = root / 'base.json', root / 'dedicated.json'
            base.write_text(json.dumps(original))
            argv = ['configure', '--base-config', str(base), '--output', str(output),
                    '--gpu-uuid', 'GPU-spare']
            for name in ('binary', 'transformer', 'encoder', 'vision', 'vae'):
                argv += ['--' + name, str(paths[name])]
            with patch('sys.argv', argv):
                main()
            result = json.loads(output.read_text())
            for key in ('sampling_profile', 'sample_params', 'lora'):
                self.assertEqual(result[key], original[key])
            self.assertEqual(result['command'][result['command'].index('--steps') + 1], '4')
            self.assertNotIn('--sigmas', result['command'])  # Dynamic Lightning schedule stays per-image.
            self.assertEqual(result['environment']['CUDA_VISIBLE_DEVICES'], 'GPU-spare')
            self.assertEqual(result['placement'], 'dedicated')
            self.assertEqual(len(result['files']), 5)
            self.assertIn('te=cpu', result['command'][result['command'].index('--backend') + 1])

    def test_encoder_can_move_to_a_vulkan_card(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('binary', 'transformer', 'encoder', 'vision', 'vae'):
                (root / name).write_bytes(name.encode())
            base, output = root / 'base.json', root / 'dedicated.json'
            base.write_text(json.dumps({'command': ['old-server'], 'files': []}))
            argv = ['configure', '--base-config', str(base), '--output', str(output),
                    '--gpu-uuid', 'GPU-spare', '--encoder-device', 'vulkan0']
            for name in ('binary', 'transformer', 'encoder', 'vision', 'vae'):
                argv += ['--' + name, str(root / name)]
            with patch('sys.argv', argv):
                main()
            command = json.loads(output.read_text())['command']
            for flag in ('--backend', '--params-backend'):
                self.assertEqual(command[command.index(flag) + 1], 'diffusion=cuda0,te=vulkan0,vae=cuda0')
            self.assertEqual(command[command.index('--max-vram') + 1], 'cuda0=10,vulkan0=7')

    def test_encoder_device_rejects_a_second_cuda_card(self):
        with patch('sys.argv', ['configure', '--base-config', 'b', '--output', 'o', '--gpu-uuid', 'GPU-x',
                                '--binary', 'b', '--transformer', 't', '--encoder', 'e', '--vision', 'v',
                                '--vae', 'v', '--encoder-device', 'cuda1']), \
                patch('sys.stderr'), self.assertRaises(SystemExit):
            main()
