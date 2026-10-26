from pathlib import Path
import tempfile
import unittest

from desktop.video_lab.sampling_probe_watcher import renderer_pids


class ProbeTargetTests(unittest.TestCase):
    def test_only_exact_output_in_benchmark_cgroup_can_be_stopped(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            for pid, binary, output, cgroup in [
                    ('101', '/runtime/sd-cli', '/results/probe.webm', 'friday-video-capacity.service'),
                    ('102', '/runtime/sd-cli', '/results/other.webm', 'friday-video-capacity.service'),
                    ('103', '/runtime/sd-cli', '/results/probe.webm', 'unrelated.service'),
                    ('104', '/runtime/other', '/results/probe.webm', 'friday-video-capacity.service')]:
                directory = proc / pid; directory.mkdir()
                (directory / 'cmdline').write_bytes(('\0'.join([binary, '-o', output]) + '\0').encode())
                (directory / 'cgroup').write_text('0::/user.slice/' + cgroup)
            self.assertEqual(renderer_pids(Path('/results/probe.webm'), 'friday-video-capacity.service', proc), [101])
