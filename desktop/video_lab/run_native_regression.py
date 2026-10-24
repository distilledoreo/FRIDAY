"""Reproduce the resident-placement bug, then verify the isolated planner variant."""
import argparse
from pathlib import Path
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--variant', type=Path, required=True)
    args = parser.parse_args()
    obj = args.variant / 'resident_placement.o'
    test = Path(__file__).resolve().parent / 'native_tests/resident_placement.cpp'
    subprocess.run(['c++', '-O2', '-std=c++17', '-DGGML_MAX_NAME=160', '-I', str(args.source / 'src'),
                    '-I', str(args.source / 'ggml/include'), '-c', str(test), '-o', str(obj)], check=True)
    lines = subprocess.check_output(['ninja', '-C', str(args.build), '-t', 'commands', 'sd-cli'], text=True).splitlines()
    link = shlex.split(lines[-1])
    link = link[next(i for i, value in enumerate(link) if value.endswith('/c++')):]
    if '&&' in link:
        link = link[:link.index('&&')]
    link = [value for value in link if not value.endswith('.o')]
    link.insert(link.index('libstable-diffusion.a'), str(obj))
    link.append('-Wl,--wrap=ggml_backend_dev_memory')
    for name, archive, expected in [('original', args.build / 'libstable-diffusion.a', 2),
                                    ('patched', args.variant / 'libstable-diffusion.a', 0)]:
        command = list(link)
        binary = args.variant / ('regression-' + name)
        command[command.index('-o') + 1] = str(binary)
        command[command.index('libstable-diffusion.a')] = str(archive)
        subprocess.run(command, cwd=args.build, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True)
        print(name, 'exit', result.returncode, result.stdout.strip(), result.stderr.strip(), flush=True)
        if result.returncode != expected:
            raise RuntimeError('Unexpected regression result for ' + name)


if __name__ == '__main__':
    main()
