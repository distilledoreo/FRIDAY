import json
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

    def test_loras_join_the_prompt(self):
        plan = self.planner.resolve(4, '16:9', 0)
        argv = self.runtime.argv(dict(REQUEST, loras=[{'name': 'film', 'strength': 0.6}]), plan, Path('/tmp/out.webm'), ['--init-img', '/x.png'])
        self.assertEqual(value(argv, '--init-img'), '/x.png')
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
class InputTests(unittest.IsolatedAsyncioTestCase):
    async def test_inputs_become_fitted_files_and_renderer_options(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            image, clip = work / 'photo.jpg', work / 'clip.mp4'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=800x800', '-frames:v', '1', str(image)], check=True)
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x240:rate=30:duration=2', str(clip)], check=True)
            runtime = VideoRuntime(CONFIG, directory)
            plan = Planner().resolve(4, '16:9', 0)
            inputs = [({'kind': 'keyframe', 'file_id': 'a', 'at_seconds': 3.0}, image),
                      ({'kind': 'end_image', 'file_id': 'b'}, image),
                      ({'kind': 'keyframe', 'file_id': 'c', 'at_seconds': 1.0, 'strength': 0.8}, image),
                      ({'kind': 'source_video', 'file_id': 'd'}, clip)]
            args, env = await runtime.prepare_inputs(REQUEST, plan, work, inputs)
            # Keyframes go in time order, with their frames for the renderer.
            self.assertEqual(env['FRIDAY_LTX_KEYFRAME_FRAMES'], '24,72')
            refs = [args[i + 1] for i, a in enumerate(args) if a == '-r']
            self.assertEqual([Path(r).name for r in refs], ['keyframe-2.png', 'keyframe-0.png'])
            self.assertTrue(Path(value(args, '--end-img')).is_file())
            self.assertEqual(value(args, '--strength'), '0.8')
            # The clip's last 33 frames (of 48 at 24 fps) open the video; the first 15 are joined back on afterwards.
            frames = sorted(Path(value(args, '--control-video')).glob('*.png'))
            self.assertEqual(len(frames), 33)
            self.assertEqual(json.loads((work / 'join.json').read_text())['keep_frames'], 15)
            size = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', str(frames[0])],
                                  capture_output=True, text=True).stdout.strip()
            self.assertEqual(size, '640,352')

    async def test_audio_needs_its_encoder(self):
        runtime = VideoRuntime(CONFIG, '/tmp/video')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RuntimeError):
                await runtime.prepare_inputs(REQUEST, Planner().resolve(4, '16:9', 0), Path(directory),
                                             [({'kind': 'audio', 'file_id': 'a'}, Path(directory) / 'song.mp3')])


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

    async def test_a_continued_clip_is_joined_after_the_original(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            original, render = work / 'original.mp4', work / 'render.mkv'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=30:duration=2',
                            '-f', 'lavfi', '-i', 'sine=duration=2', '-shortest', str(original)], check=True)
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x352:rate=24:duration=1',
                            '-f', 'lavfi', '-i', 'sine=duration=1', '-c:v', 'libvpx', '-c:a', 'pcm_s16le', '-shortest', str(render)], check=True)
            (work / 'join.json').write_text(json.dumps({'path': str(original), 'keep_frames': 15}))
            _, _, seconds = await VideoRuntime(CONFIG, directory).finish(render, work, {'width': 640, 'height': 352})
            self.assertAlmostEqual(seconds, 15 / 24 + 1, delta=0.1)


if __name__ == '__main__':
    unittest.main()
