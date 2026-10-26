#!/usr/bin/env python3
"""Restore the gateway when it stops answering.

A child forked by the API that hangs before it starts its program (a native library's fork handler
holding a lock) leaves the parent blocked inside subprocess start-up, so every request, including
the app's update check, times out. Any HTTP answer counts as alive: /health returns 503 quickly
while the model loads, and that must not cause a restart. Without an answer, stuck forks of the API
(children still running uvicorn itself) are killed first, then the service is restarted if needed.
"""
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

URL = os.environ.get('FRIDAY_WATCHDOG_URL', 'http://127.0.0.1:8700/health')
UNIT = 'assistant-api.service'


def answers(timeout=20):
    try:
        urllib.request.urlopen(URL, timeout=timeout).close()
        return True
    except urllib.error.HTTPError:
        return True
    except Exception:
        return False


def main_pid():
    out = subprocess.run(['systemctl', '--user', 'show', UNIT, '--property=MainPID', '--value'],
                         capture_output=True, text=True).stdout.strip()
    return int(out) if out.isdigit() and out != '0' else None


def stuck_forks(parent):
    """Children of the API whose command line is still the API's own: forks that never ran a program."""
    try:
        with open(f'/proc/{parent}/cmdline', 'rb') as f:
            own = f.read()
    except OSError:
        return []
    found = []
    for pid in filter(str.isdigit, os.listdir('/proc')):
        try:
            with open(f'/proc/{pid}/stat') as f:
                ppid = int(f.read().rsplit(')', 1)[1].split()[1])
            with open(f'/proc/{pid}/cmdline', 'rb') as f:
                cmd = f.read()
        except (OSError, ValueError, IndexError):
            continue
        if ppid == parent and cmd == own:
            found.append(int(pid))
    return found


def main():
    if answers():
        return 0
    parent = main_pid()
    if parent is None:
        return 0  # Not running: systemd's own restart policy applies.
    forks = stuck_forks(parent)
    for pid in forks:
        print(f'API not answering; killing stuck fork {pid}', flush=True)
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if forks:
        time.sleep(3)
        if answers():
            return 0
    print('API still not answering; restarting it', flush=True)
    subprocess.run(['systemctl', '--user', 'restart', UNIT], check=False)
    return 1


if __name__ == '__main__':
    sys.exit(main())
