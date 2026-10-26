"""Print compact capacity results without expanding per-second telemetry."""
import argparse
import json
from pathlib import Path
import re


def summarize(directory):
    report = json.loads((directory / 'results.json').read_text())
    rows = list(report['cases'])
    for path in sorted(directory.glob('*.json')):
        if path.name == 'results.json' or path.name.endswith('.sampling-probe.json'):
            continue
        value = json.loads(path.read_text())
        if isinstance(value, dict) and 'name' in value and 'returncode' in value:
            if not any(row['name'] == value['name'] for row in rows):
                rows.append(value)
    result = []
    for row in rows:
        marker = directory / (row['name'] + '.sampling-probe.json')
        failure = directory / (row['name'] + '.failure.json')
        if marker.exists() and row['timings'].get('sampling_s'):
            status = 'denoising-pass'
        elif row['returncode'] == 0 and row.get('media'):
            status = 'full-render'
        elif (failure.exists() and json.loads(failure.read_text()).get('type') == 'gpu-oom') or \
                any('allocat' in error or 'out of memory' in error for error in row.get('errors', [])):
            status = 'allocation-failed'
        else:
            status = 'runtime-failed' if row['returncode'] not in (-15, 0) else 'interrupted'
        peak = {str(index): max((gpu['memory_mib'] for sample in row.get('samples', [])
                                for gpu in sample['gpus'] if gpu['index'] == index), default=0)
                for index in (0, 1)}
        devices = {match.group(1) for line in row.get('partition', [])
                   if (match := re.search(r'layer split: (\S+) <-', line))}
        result.append(dict(name=row['name'], size=f"{row['width']}x{row['height']}",
                           seconds=(row['frames'] - 1) / 24, steps=row.get('steps', 8),
                           status=status, split_devices=sorted(devices), peak_mib=peak,
                           wall_s=row['wall_s'], timings=row['timings']))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--last', type=int, default=0)
    args = parser.parse_args()
    rows = summarize(args.directory)
    if args.last:
        rows = rows[-args.last:]
    for row in rows:
        print(json.dumps(row))


if __name__ == '__main__':
    main()
