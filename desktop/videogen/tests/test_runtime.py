from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from desktop.videogen.plan import Planner
from desktop.videogen.runtime import VideoRuntime, failure, tail

CONFIG = {
    'binary': {'path': '/opt/ltx/sd-cli'}, 'rpc_worker': {'path': '/opt/ltx/rpc-worker'},
    'files': [{'role': role, 'path': f'/models/{role}.gguf'} for role in ('transformer', 'text_encoder', 'video_vae', 'audio_vae')],
    'gpus': {'image': 'GPU-image', 'chat': 'GPU-chat'}, 'lora_dir': '/models/loras',
}
REQUEST = {'prompt': 'Waves at sunset', 'negative_prompt': None, 'seed': 7, 'loras': [], 'inputs': []}


def value(argv, flag):
    return argv[argv.index(flag) + 1]


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.runtime = VideoRuntime(CONFIG, '/tmp/video')
        self.planner = Planner()

    def test_one_card_keeps_the_transformer_resident_with_small_decode_tiles(self):
        plan = self.planner.resolve(4, '16:9', 0)
        argv = self.runtime.argv(REQUEST, plan, Path('/tmp/out.webm'))
        self.assertEqual(value(argv, '--backend'), 'diffusion=cuda0,te=cuda0,vae=cuda0')
        self.assertEqual(value(argv, '--params-backend'), 'te=cpu,vae=cpu')
        self.assertEqual(value(argv, '--vae-tile-size'), '10x11')
        self.assertNotIn('--rpc-servers', argv)
        self.assertEqual((value(argv, '-W'), value(argv, '-H'), value(argv, '--video-frames')), ('640', '352', '97'))
        self.assertEqual(value(argv, '--steps'), '8')
        self.assertEqual(value(argv, '-p'), 'Waves at sunset')
        self.assertEqual(value(argv, '-s'), '7')

    def test_two_cards_split_layers_through_the_worker(self):
        plan = self.planner.resolve(4, '16:9', 4)
        argv = self.runtime.argv(REQUEST, plan, Path('/tmp/out.webm'))
        self.assertEqual(value(argv, '--backend'), 'diffusion=cuda0&RPC0,te=cuda0,vae=cuda0')
        self.assertEqual(value(argv, '--rpc-servers'), '127.0.0.1:50053')
        self.assertEqual(value(argv, '--max-vram'), 'cuda0=6.5,RPC0=6.5')
        self.assertEqual(value(argv, '--split-mode'), 'layer')
        self.assertEqual((value(argv, '-W'), value(argv, '-H')), ('1600', '896'))

    def test_inputs_and_loras_map_to_renderer_options(self):
        plan = self.planner.resolve(4, '16:9', 0)
        request = dict(REQUEST, loras=[{'name': 'film', 'strength': 0.6}])
        start = {'kind': 'start_image', 'file_id': 'a' * 32}
        argv = self.runtime.argv(request, plan, Path('/tmp/out.webm'), [(start, Path('/files/a'))])
        self.assertEqual(value(argv, '--init-img'), '/files/a')
        self.assertEqual(value(argv, '--lora-model-dir'), '/models/loras')
        self.assertEqual(value(argv, '-p'), 'Waves at sunset <lora:film:0.6>')

    def test_config_needs_both_cards(self):
        with self.assertRaises(ValueError):
            VideoRuntime(dict(CONFIG, gpus={'image': 'GPU-image'}), '/tmp/video')

    def test_failure_messages(self):
        self.assertIn('ran out of memory', failure('[ERROR] ggml_extend.hpp:72   - cudaMalloc failed: out of memory'))
        self.assertEqual(failure('[ERROR] main.cpp:966  - generate failed'), 'Video render failed: generate failed')
        self.assertEqual(failure('nothing useful'), 'Video render failed')

    def test_tail_reads_progress_lines(self):
        with tempfile.NamedTemporaryFile('wb', delete=False) as f:
            f.write(b'loading\r|===  | 3/8 - 21.8s/it\r|==== | 4/8 - 21.9s/it')
        try:
            self.assertTrue(tail(f.name).endswith('4/8 - 21.9s/it'))
            self.assertEqual(tail('/nonexistent/log'), '')
        finally:
            Path(f.name).unlink()


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'needs ffmpeg')
class FinishTests(unittest.IsolatedAsyncioTestCase):
    async def test_makes_a_playable_mp4_and_poster(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            source = work / 'render.mkv'  # the renderer's WebM holds VP8 + PCM, which ffmpeg only muxes as Matroska
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x352:rate=24:duration=1',
                            '-f', 'lavfi', '-i', 'sine=duration=1', '-c:v', 'libvpx', '-c:a', 'pcm_s16le', '-shortest', str(source)], check=True)
            runtime = VideoRuntime(CONFIG, directory)
            video, poster, seconds = await runtime.finish(source, work, {'width': 640, 'height': 352})
            self.assertTrue(poster.is_file())
            self.assertAlmostEqual(seconds, 1.0, delta=0.1)
            probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name', '-of', 'csv=p=0', str(video)],
                                   capture_output=True, text=True).stdout.split()
            self.assertEqual(probe, ['h264', 'aac'])
            with self.assertRaises(RuntimeError):
                await runtime.finish(source, work, {'width': 1024, 'height': 576})


if __name__ == '__main__':
    unittest.main()
