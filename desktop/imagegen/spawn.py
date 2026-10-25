"""Start programs from the gateway without forking it.

Python forks the whole multithreaded gateway and then runs exec in the copy. A native library's fork handler can
deadlock in that window; the copy hangs before exec, and the parent, which waits for the exec, hangs with it: on
the event loop that froze every request. posix_spawn (glibc's clone with CLONE_VFORK, no fork handlers, no Python
in the child) has no such window. subprocess only uses it for a full program path with close_fds=False and no
cwd or new session, so those two are done by env -C and setsid in the child. Python opens files and sockets
close-on-exec, so close_fds=False leaks nothing.
"""
import shutil


def spawn_args(*command, cwd=None, new_session=False):
    """(argv, kwargs) for subprocess or asyncio.create_subprocess_exec that take the posix_spawn path."""
    argv = [shutil.which(command[0]) or command[0], *command[1:]]
    if cwd is not None:
        argv = ['/usr/bin/env', '-C', str(cwd), '--', *argv]
    if new_session:
        argv = ['/usr/bin/setsid', *argv]
    return argv, {'close_fds': False}
