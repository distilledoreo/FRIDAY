import os
from pathlib import Path
import tempfile
import unittest

from desktop.agent.sandbox import IMAGE, Sandbox


class PolicyTests(unittest.TestCase):
    def test_fixed_boundary_has_no_network_devices_or_extra_mounts(self):
        with tempfile.TemporaryDirectory() as root:
            runner = Sandbox(root)
            command = runner.command('friday-test', root, 'print(1)')
            for flag in ('--network=none', '--cpus=4', '--memory=8g', '--memory-swap=8g',
                         '--pids-limit=128', '--cap-drop=ALL', '--read-only',
                         '--security-opt=no-new-privileges', '--pull=never'):
                self.assertIn(flag, command)
            self.assertNotIn('--mount', command)
            self.assertEqual(command.count('--tmpfs'), 2)
            self.assertFalse(any('docker.sock' in x or '--gpus' in x or '--privileged' in x for x in command))
            self.assertIn(IMAGE, command)

    def test_invalid_work_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as root:
            runner = Sandbox(root, docker=('nonexistent-command',))
            for script, timeout in [('', 1), ('x' * 65537, 1), ('print(1)', 901)]:
                with self.assertRaises(ValueError): runner.run(script, timeout)


@unittest.skipUnless(os.environ.get('FRIDAY_DOCKER_TEST') == '1', 'Opt-in CPU Docker smoke')
class DockerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runner = Sandbox(self.temp.name)

    def tearDown(self): self.temp.cleanup()

    def test_host_files_devices_network_and_resource_limits(self):
        result = self.runner.run('''import os, socket
assert not os.path.exists('/home/user/assistant-server/.env')
assert not os.path.exists('/var/run/docker.sock')
assert not any('nvidia' in x for x in os.listdir('/dev'))
assert open('/sys/fs/cgroup/memory.max').read().strip() == str(8 * 1024**3)
quota, period = map(int, open('/sys/fs/cgroup/cpu.max').read().split())
assert quota / period == 4
assert os.getuid() != 0
try:
    open('/etc/forbidden', 'w')
    raise AssertionError('root filesystem writable')
except OSError: pass
for target in ['1.1.1.1', '192.168.1.1', '100.64.0.1', '169.254.169.254']:
    try:
        socket.create_connection((target, 80), timeout=.2)
        raise AssertionError('network reachable')
    except OSError: pass
open('/work/result.txt', 'w').write('temporary')
print('boundaries verified')''')
        self.assertTrue(result['success'], result)
        self.assertIn('boundaries verified', result['output'])
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])

    def test_timeout_stops_container_and_removes_work(self):
        result = self.runner.run('import time; time.sleep(30)', timeout=1)
        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'Time limit exceeded')
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])

    def test_excess_output_is_bounded(self):
        result = self.runner.run('while True: print("x" * 4096, flush=True)')
        self.assertFalse(result['success'])
        self.assertEqual(result['error'], 'Output limit exceeded')
        self.assertLessEqual(len(result['output'].encode()), 1024 * 1024)
