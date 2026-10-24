"""Link llama.cpp's RPC worker against an existing matching sd.cpp GGML build."""
import argparse
from pathlib import Path
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sd-source', type=Path, required=True)
    parser.add_argument('--sd-build', type=Path, required=True)
    parser.add_argument('--rpc-source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lines = subprocess.check_output(['ninja', '-C', str(args.sd_build), '-t', 'commands', 'sd-cli'], text=True)
    tokens = shlex.split(lines.splitlines()[-1])
    archives = [str(args.sd_build / token) for token in tokens if token.startswith('ggml/') and token.endswith('.a')]
    dependencies = [token for token in tokens if token.endswith(('.so', '.a')) and token.startswith('/')]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = ['c++', '-O3', '-std=c++17', '-DGGML_MAX_NAME=160', '-I', str(args.sd_source / 'ggml/include'),
               str(args.rpc_source), '-o', str(args.output), '-Wl,--start-group', *archives, '-Wl,--end-group',
               *dependencies, '-ldl', '-lm', '-pthread']
    command += [token for token in tokens if token.startswith('-Wl,-rpath,')]
    subprocess.run(command, check=True)
    print('Built matching CUDA RPC worker:', args.output)


if __name__ == '__main__':
    main()
