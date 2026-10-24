"""Run one isolated case while a parent experiment owns the model-service handoff."""
import argparse
import json
from pathlib import Path
import signal

from .benchmark_placement import run_case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--case', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    case = next(case for case in config['cases'] if case['name'] == args.case)
    def stop(*_):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, stop)
    result = run_case(config, case, args.output)
    (args.output / (case['name'] + '.json')).write_text(json.dumps(result, indent=2))
    print(json.dumps({key: result[key] for key in ['name', 'returncode', 'wall_s', 'timings', 'errors']}, indent=2))


if __name__ == '__main__':
    main()
