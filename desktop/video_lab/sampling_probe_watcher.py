"""Stop explicitly labeled capacity probes after their denoising graph succeeds."""
import argparse
import json
import os
from pathlib import Path
import signal
import time


def renderer_pids(output, unit, proc=Path('/proc')):
    matches = []
    for entry in proc.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            args = (entry / 'cmdline').read_bytes().decode(errors='replace').split('\0')
            cgroup = (entry / 'cgroup').read_text()
            if (args[0].endswith('/sd-cli') and '-o' in args and
                    args[args.index('-o') + 1] == str(output) and unit in cgroup):
                matches.append(int(entry.name))
        except (OSError, IndexError):
            pass
    return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--unit', default='friday-video-capacity.service')
    args = parser.parse_args()
    while True:
        queue = json.loads(args.config.read_text())
        for case in queue['cases']:
            if not (case.get('stop_after_sampling') and case.get('steps') == 1):
                continue
            marker = args.output / (case['name'] + '.sampling-probe.json')
            log = args.output / (case['name'] + '.log')
            if marker.exists() or not log.exists():
                continue
            with log.open('rb') as stream:
                stream.seek(0, 2)
                stream.seek(max(0, stream.tell() - 65536))
                tail = stream.read().decode(errors='replace')
            if 'sampling completed, taking ' not in tail:
                continue
            pids = renderer_pids(args.output / (case['name'] + '.webm'), args.unit)
            marker.write_text(json.dumps({'sampling_completed': True, 'full_decode_verified': False,
                                          'intentional_stop_pids': pids}, indent=2))
            for pid in pids:
                try:
                    os.killpg(os.getpgid(pid), signal.SIGTERM)
                except ProcessLookupError:
                    pass
            print('Denoising capacity passed; stopped before full decode: ' + case['name'], flush=True)
        if not queue.get('queue_open'):
            return
        time.sleep(.5)


if __name__ == '__main__':
    main()
