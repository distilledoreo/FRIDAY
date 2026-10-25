"""Programs start through posix_spawn, never by forking the gateway (see spawn.py)."""
import asyncio
import os
import subprocess
import tempfile
import unittest
from unittest import mock

from desktop.imagegen.spawn import spawn_args


class SpawnTests(unittest.IsolatedAsyncioTestCase):
    async def run_recorded(self, argv, spawn):
        used = []
        original = subprocess.Popen._posix_spawn
        def record(self, *args, **kwargs):
            used.append(True)
            return original(self, *args, **kwargs)
        with mock.patch.object(subprocess.Popen, '_posix_spawn', record):
            process = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE, **spawn)
            out, _ = await process.communicate()
        return used, process, out.decode().strip()

    async def test_plain_command_uses_posix_spawn(self):
        used, process, out = await self.run_recorded(*spawn_args('echo', 'hello'))
        self.assertEqual(used, [True]); self.assertEqual(out, 'hello')

    async def test_working_directory_without_fork(self):
        with tempfile.TemporaryDirectory() as directory:
            used, _, out = await self.run_recorded(*spawn_args('pwd', cwd=directory))
            self.assertEqual(used, [True]); self.assertEqual(os.path.realpath(out), os.path.realpath(directory))

    async def test_new_session_keeps_the_pid(self):
        used, process, out = await self.run_recorded(*spawn_args('sh', '-c', 'echo $$ $(ps -o sid= -p $$)', new_session=True))
        pid, sid = out.split()
        self.assertEqual(used, [True])
        self.assertEqual(int(pid), process.pid)        # setsid execs in place: stopping the process stops the session
        self.assertEqual(int(sid), process.pid)
