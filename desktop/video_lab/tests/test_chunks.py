from array import array
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from desktop.video_lab import run_chunks
from desktop.video_lab.chunk_state import audit_carry, read_state


def write_state(path, video, audio):
    with path.open('wb') as stream:
        stream.write(struct.pack('<8I', 0x46524156, 1, 1, 1, 13, 128, 102, 24))
        array('f', video + audio).tofile(stream)


class ChunkTests(unittest.TestCase):
    def test_frame_rate_cannot_be_overridden_outside_the_audio_grid_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            asset = Path(directory) / 'asset'; asset.touch()
            config = {key: str(asset) for key in ['binary', 'rpc_worker', 'transformer', 'video_vae', 'audio_vae', 'text_encoder']}
            config.update(width=640, height=352, window_frames=97, carry_frames=25, windows=3,
                          gpu_uuids=['main', 'aux'], extra_args=['--vae-tiling'])
            run_chunks.validate(config)
            config['extra_args'] = ['--fps', '30']
            with self.assertRaisesRegex(ValueError, 'cannot override'):
                run_chunks.validate(config)

    def test_initial_window_rejects_different_weights_before_gpu_handoff(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = root / 'weights'; asset.touch()
            video = root / 'window.webm'; video.touch()
            write_state(root / 'window.stage1.avc', [0.] * (128 * 13), [0.] * (8 * 102 * 16))
            flags = ['--diffusion-model', '--vae', '--audio-vae', '--llm']
            row = dict(returncode=0, frames=97, width=32, height=32, output=str(video),
                       command=[value for flag in flags for value in [flag, str(asset)]])
            path = root / 'window.json'; path.write_text(json.dumps(row))
            config = dict(initial_window=str(path), window_frames=97, width=32, height=32,
                          **{key: str(asset) for key in ['transformer', 'video_vae', 'audio_vae', 'text_encoder']})
            self.assertEqual(run_chunks.initial_window(config)['returncode'], 0)
            config['transformer'] = str(root / 'other-weights')
            with self.assertRaisesRegex(ValueError, 'different model'):
                run_chunks.initial_window(config)

    def test_audit_checks_all_video_and_audio_channels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a, b = root / 'a.avc', root / 'b.avc'
            video = [float(i) for i in range(128 * 13)]
            audio = [float(i) for i in range(8 * 102 * 16)]
            carried_video = [-1.] * len(video)
            carried_audio = [-1.] * len(audio)
            for c in range(128):
                carried_video[c * 13:c * 13 + 4] = video[c * 13 + 9:c * 13 + 13]
            for c in range(8):
                carried_audio[c * 102 * 16:c * 102 * 16 + 27 * 16] = audio[(c * 102 + 75) * 16:(c + 1) * 102 * 16]
            write_state(a, video, audio)
            write_state(b, carried_video, carried_audio)
            audit = audit_carry(a, b, 25)
            self.assertEqual(audit['video_max_abs_error'], 0)
            self.assertEqual(audit['audio_max_abs_error'], 0)
            carried_audio[7 * 102 * 16] += 1
            write_state(b, carried_video, carried_audio)
            with self.assertRaisesRegex(RuntimeError, 'context changed'):
                audit_carry(a, b, 25)
            b.write_bytes(b.read_bytes()[:-4])
            with self.assertRaisesRegex(ValueError, 'size mismatch'):
                read_state(b)

    def test_join_removes_context_without_video_blending_and_preserves_av_duration(self):
        command = run_chunks.stitch_command(['a.webm', 'b.webm', 'c.webm'], 'out.mp4', 97, 25)
        graph = command[command.index('-filter_complex') + 1]
        self.assertIn('trim=start_frame=25:end_frame=97', graph)
        self.assertIn('atrim=start_sample=50000:end_sample=194000', graph)
        self.assertIn('concat=n=3:v=1:a=1', graph)
        self.assertIn('acrossfade=ns=960:c1=tri:c2=tri', graph)
        self.assertIn('atrim=start_sample=49040:end_sample=50000', graph)
        self.assertNotIn('xfade=', graph)
        raw = run_chunks.stitch_command(['a.webm', 'b.webm'], 'out.mp4', 97, 25, 0)
        self.assertNotIn('acrossfade', raw[raw.index('-filter_complex') + 1])

    def test_failure_stops_worker_and_restores_chat(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = dict(gpu_uuids=['main', 'spare'], window_frames=97, carry_frames=25,
                          windows=3, width=640, height=352, rpc_worker='worker')
            with patch.object(run_chunks, 'validate'), patch.object(run_chunks, 'gpu_state', return_value=[]), \
                 patch.object(run_chunks, 'service_active', return_value=True), \
                 patch.object(run_chunks.subprocess, 'run') as service, \
                 patch.object(run_chunks.subprocess, 'check_output', return_value=''), \
                 patch.object(run_chunks, 'run_case', side_effect=RuntimeError('render failed')), \
                 patch.object(run_chunks, 'wait_ready', return_value=True):
                with self.assertRaisesRegex(RuntimeError, 'render failed'):
                    run_chunks.run(config, root)
            calls = [call.args[0] for call in service.call_args_list]
            self.assertEqual(calls[-2], ['systemctl', '--user', 'stop', 'friday-video-chunks-rpc.service'])
            self.assertEqual(calls[-1], ['systemctl', '--user', 'start', 'assistant-strata.service'])
            result = json.loads((root / 'results.json').read_text())
            self.assertEqual(result['status'], 'failed')
            self.assertTrue(result['chat_ready'])
