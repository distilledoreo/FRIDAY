"""CPU-only, offline Docker execution. Work files use a bounded private tmpfs.

No Docker socket, account credentials, home directories or GPU devices enter the
container. Public browsing needs a separate constrained broker before enabling it.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

IMAGE = 'python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4'
MAX_OUTPUT = 1024 * 1024


class Sandbox:
    def __init__(self, root, docker=('docker',)):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.docker = tuple(docker)

    def command(self, name, work, script):
        return [*self.docker, 'run', '--rm', '--pull=never', '--name', name,
                '--runtime=runc', '--log-driver=none', '--ipc=private', '--cgroupns=private',
                '--network=none', '--cpus=4', '--memory=8g', '--memory-swap=8g',
                '--pids-limit=128', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--read-only', '--user', f'{os.getuid()}:{os.getgid()}',
                '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=67108864',
                '--tmpfs', f'/work:rw,noexec,nosuid,nodev,size=268435456,uid={os.getuid()},gid={os.getgid()},mode=0700',
                '--workdir=/work',
                '--env', 'HOME=/tmp', '--env', 'PYTHONDONTWRITEBYTECODE=1',
                '--ulimit', 'fsize=65536:65536', IMAGE, 'python', '-I', '-c', script]

    def run(self, script, timeout=60):
        if not isinstance(script, str) or not 1 <= len(script.encode()) <= 65536:
            raise ValueError('Script must contain 1–65536 bytes')
        if not 1 <= timeout <= 900:
            raise ValueError('Timeout must be 1–900 seconds')
        if shutil.disk_usage(self.root).free < 2 * 1024**3:
            raise RuntimeError('Sandbox requires 2 GB of free storage')
        name = 'friday-task-' + uuid.uuid4().hex
        # Temporary workspaces are removed, including failed and cancelled work.
        # Do not mount any caller-supplied path or preserve arbitrary artifacts yet.
        with tempfile.TemporaryDirectory(prefix=name + '-', dir=self.root) as work:
            with tempfile.TemporaryFile() as output:
                process = subprocess.Popen(self.command(name, work, script),
                                           stdin=subprocess.DEVNULL, stdout=output,
                                           stderr=subprocess.STDOUT)
                reason = None
                deadline = time.monotonic() + timeout
                try:
                    while process.poll() is None:
                        if os.fstat(output.fileno()).st_size > MAX_OUTPUT:
                            reason = 'Output limit exceeded'
                            break
                        if time.monotonic() >= deadline:
                            reason = 'Time limit exceeded'
                            break
                        time.sleep(.05)
                finally:
                    # Explicit removal also covers a disconnected Docker client.
                    try:
                        subprocess.run([*self.docker, 'rm', '-f', name],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       timeout=15, check=False)
                    finally:
                        if process.poll() is None:
                            process.kill()
                        process.wait(timeout=5)
                output.seek(0)
                raw = output.read(MAX_OUTPUT + 1)
                if len(raw) > MAX_OUTPUT:
                    reason = reason or 'Output limit exceeded'
                return {'success': process.returncode == 0 and reason is None,
                        'exit_code': process.returncode, 'error': reason,
                        'output': raw[:MAX_OUTPUT].decode('utf-8', errors='replace')}
