"""OpenCode inside a constrained Docker container, using a task-bound IPC broker."""
import asyncio
import contextlib
import grp
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
import uuid

from .ipc import TaskBroker, UnixBroker

MAX_LOG = 2 * 1024 * 1024


def docker_command(arguments):
    command = ['/usr/bin/docker', *arguments]
    if grp.getgrnam('docker').gr_gid not in os.getgroups():
        return ['/usr/bin/sg', 'docker', '-c', shlex.join(command)]
    return command


class OpenCodeEngine:
    def __init__(self, image, cloud_factory, timeout=900, reader=None, search=None):
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', image): raise ValueError('Runtime image must be pinned by local digest')
        if not 1 <= timeout <= 900: raise ValueError('Task timeout must be 1–900 seconds')
        self.image, self.cloud_factory, self.timeout, self.reader = image, cloud_factory, timeout, reader
        self.search = search
        self.store = None
        self.slots = asyncio.Semaphore(1)

    def bind(self, store): self.store = store

    async def available(self):
        process = await asyncio.create_subprocess_exec(*docker_command(['image', 'inspect', self.image, '--format', '{{.Id}}']),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        try:
            output, _ = await asyncio.wait_for(process.communicate(), timeout=5)
            return process.returncode == 0 and output.decode().strip() == self.image
        except asyncio.TimeoutError:
            process.kill()
            await process.wait()
            return False

    def command(self, name, socket):
        return docker_command(['run', '--rm', '-i', '--pull=never', '--name', name,
                               '--runtime=runc', '--log-driver=none', '--ipc=private', '--cgroupns=private',
                               '--network=none', '--cpus=4', '--memory=8g', '--memory-swap=8g',
                               '--cpuset-cpus=' + ','.join(map(str, sorted(os.sched_getaffinity(0))[:4])),
                               '--env', 'UV_THREADPOOL_SIZE=4',
                               '--pids-limit=256', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                               '--read-only', '--user', f'{os.getuid()}:{os.getgid()}',
                               '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=67108864',
                               '--tmpfs', f'/work:rw,noexec,nosuid,nodev,size=536870912,uid={os.getuid()},gid={os.getgid()},mode=0700',
                               '--mount', f'type=bind,src={socket},dst=/broker.sock,readonly',
                               self.image])

    async def __call__(self, task, report):
        if self.store is None: raise RuntimeError('Engine is not bound to its task store')
        async with self.slots:
            if self.store.task(task['id'])['status'] != 'running': raise asyncio.CancelledError()
            cloud = self.cloud_factory()
            try:
                return await self.run(task, report, cloud)
            finally:
                await cloud.close()

    async def run(self, task, report, cloud):
        name = 'friday-agent-' + uuid.uuid4().hex
        kwargs = {'reader': self.reader} if self.reader is not None else {}
        kwargs['search'] = self.search
        broker = TaskBroker(self.store, task['id'], cloud, **kwargs)
        # Short tmpfs path avoids the Unix socket path-length limit and never
        # exposes the user's home to the container; only this socket is mounted.
        with tempfile.TemporaryDirectory(prefix='friday-agent-', dir='/dev/shm') as stage:
            async with UnixBroker(Path(stage) / 'broker.sock', broker):
                process = await asyncio.create_subprocess_exec(*self.command(name, Path(stage) / 'broker.sock'),
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
                    limit=128 * 1024)
                text = []
                async def consume():
                    payload = json.dumps({'approved_task': task['proposal']}, ensure_ascii=False).encode()
                    process.stdin.write(payload)
                    await process.stdin.drain()
                    process.stdin.close()
                    total = 0
                    await report('step', {'text': 'OpenCode started in CPU-only isolated sandbox'})
                    while True:
                        line = await process.stdout.readline()
                        if not line: break
                        total += len(line)
                        if total > MAX_LOG: raise RuntimeError('Task output limit exceeded')
                        if self.store.task(task['id'])['status'] != 'running': raise asyncio.CancelledError()
                        try: event = json.loads(line)
                        except (ValueError, UnicodeError):
                            # Raw startup/provider diagnostics may contain secrets
                            # from a dependency; only structured events are kept.
                            continue
                        kind = event.get('type', 'event')
                        part = event.get('part', {})
                        if kind == 'text' and isinstance(part, dict): text.append(part.get('text', ''))
                        if len(line) > 60000:
                            await report('opencode_event', {'type': kind, 'truncated': True})
                        else:
                            await report('opencode_event', event)
                    status = await process.wait()
                    if status != 0: raise RuntimeError('OpenCode exited unsuccessfully')
                    if not text: raise RuntimeError('OpenCode returned no report')
                    return {'text': '\n'.join(text)[:50000], 'engine': 'OpenCode', 'gpu': False}
                try:
                    return await asyncio.wait_for(consume(), timeout=self.timeout)
                finally:
                    cleanup = await asyncio.create_subprocess_exec(*docker_command(['rm', '-f', name]),
                        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
                    try: await asyncio.wait_for(cleanup.wait(), timeout=15)
                    except asyncio.TimeoutError: cleanup.kill(); await cleanup.wait()
                    if process.returncode is None: process.kill()
                    with contextlib.suppress(ProcessLookupError): await process.wait()
