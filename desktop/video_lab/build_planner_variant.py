"""Rebuild one planner object and relink a private CLI without editing the install."""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    source = args.output / 'layer_split_partition.cpp'
    shutil.copy2(args.source / 'src/core/layer_split_partition.cpp', source)
    patch = Path(__file__).resolve().parent / 'patches/reuse-resident-layer-placement.patch'
    subprocess.run(['patch', '-p3', '-d', str(args.output), '-i', str(patch)], check=True)
    lines = subprocess.check_output(['ninja', '-C', str(args.build), '-t', 'commands', 'sd-cli'], text=True).splitlines()
    compile_args = shlex.split(next(line for line in lines if ' -c ' in line and 'layer_split_partition.cpp' in line))
    object_file = args.output / 'layer_split_partition.cpp.o'
    compile_args[compile_args.index('-c') + 1] = str(source)
    compile_args[compile_args.index('-o') + 1] = str(object_file)
    for flag, value in [('-MF', str(object_file) + '.d'), ('-MT', str(object_file))]:
        if flag in compile_args:
            compile_args[compile_args.index(flag) + 1] = value
    compile_args += ['-I', str(args.source / 'src/core')]
    subprocess.run(compile_args, cwd=args.build, check=True)
    archive = args.output / 'libstable-diffusion.a'
    shutil.copy2(args.build / archive.name, archive)
    members = subprocess.check_output(['ar', 't', str(archive)], text=True).splitlines()
    if members.count(object_file.name) != 1:
        raise RuntimeError('Unexpected planner archive member layout')
    subprocess.run(['ar', 'rcs', str(archive), str(object_file)], check=True)
    link = shlex.split(lines[-1])
    start = next(i for i, value in enumerate(link) if value.endswith(('/c++', '/g++')))
    link = link[start:]
    if '&&' in link:
        link = link[:link.index('&&')]
    link[link.index('-o') + 1] = str(args.output / 'sd-cli')
    link[link.index('libstable-diffusion.a')] = str(archive)
    subprocess.run(link, cwd=args.build, check=True)
    print('Built isolated planner variant:', args.output / 'sd-cli')


if __name__ == '__main__':
    main()
