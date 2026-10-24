import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from desktop.video_lab import benchmark_placement as lab


class HandoffTests(unittest.TestCase):
    def test_layer_split_keeps_shard_placement_implicit_and_probe_schedule_explicit(self):
        config = {key: key for key in ['binary', 'transformer', 'video_vae', 'audio_vae', 'text_encoder', 'prompt', 'negative_prompt']}
        args = lab.command(config, dict(profile='dual-layer', width=640, height=352, frames=17, steps=1), 'probe.webm')
        self.assertIn('diffusion=cuda0&cuda1', args[args.index('--backend') + 1])
        # A global CPU/default diffusion parameter assignment would defeat resident sharding.
        self.assertEqual(args[args.index('--params-backend') + 1], 'te=cpu,vae=cpu')
        self.assertEqual(args[args.index('--sigmas') + 1], '1.0,0.0')
        self.assertEqual(args[args.index('--steps') + 1], '1')

    def test_open_queue_accepts_an_adaptive_followup_before_restoring_chat(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / 'asset'; asset.touch()
            config = {key: str(asset) for key in ['binary', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']}
            config.update(gpu_uuids=['fast', 'spare'], cases=[{'name': 'first'}], queue_open=True)
            path = root / 'config.json'
            import json
            path.write_text(json.dumps(config))
            def finish(_, case, directory):
                config.update(cases=[{'name': 'first'}, {'name': 'larger'}], queue_open=False)
                path.write_text(json.dumps(config))
                return dict(name=case['name'], returncode=0, wall_s=1, timings={}, errors=[])
            with patch.object(lab, 'gpu_state', return_value=[]), \
                 patch.object(lab, 'service_active', return_value=False), \
                 patch.object(lab.subprocess, 'check_output', return_value=''), \
                 patch.object(lab, 'run_case', side_effect=finish) as run:
                lab.benchmark(config, root / 'output', path)
                self.assertEqual([call.args[1]['name'] for call in run.call_args_list], ['first', 'larger'])

    def test_failed_render_restores_chat(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / 'asset'; asset.touch()
            config = {key: str(asset) for key in ['binary', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']}
            config.update(gpu_uuids=['fast', 'spare'], cases=[{'name': 'failed'}])
            with patch.object(lab, 'gpu_state', return_value=[]), \
                 patch.object(lab, 'service_active', return_value=True), \
                 patch.object(lab.subprocess, 'run') as service, \
                 patch.object(lab.subprocess, 'check_output', return_value=''), \
                 patch.object(lab, 'run_case', side_effect=RuntimeError('render failed')), \
                 patch.object(lab, 'wait_ready', return_value=True):
                with self.assertRaisesRegex(RuntimeError, 'render failed'):
                    lab.benchmark(config, root / 'output')
                self.assertEqual(service.call_args_list[-1].args[0],
                                 ['systemctl', '--user', 'start', 'assistant-strata.service'])
                self.assertTrue((root / 'output/results.json').exists())

    def test_stopped_chat_is_not_started_by_benchmark(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            asset = root / 'asset'; asset.touch()
            config = {key: str(asset) for key in ['binary', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']}
            config.update(gpu_uuids=['fast', 'spare'], cases=[])
            with patch.object(lab, 'gpu_state', return_value=[]), \
                 patch.object(lab, 'service_active', return_value=False), \
                 patch.object(lab.subprocess, 'run') as service, \
                 patch.object(lab.subprocess, 'check_output', return_value=''):
                lab.benchmark(config, root / 'output')
                service.assert_not_called()
