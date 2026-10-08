"""Build runtime from only trusted adapter code and the installed OpenCode binary.

No user config, keys, project files or home-directory context enters the image.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--opencode', required=True)
    parser.add_argument('--tag', default='friday-agent:local')
    args = parser.parse_args()
    binary = Path(args.opencode).resolve(strict=True)
    source = Path(__file__).parent
    with tempfile.TemporaryDirectory(prefix='friday-image-') as stage:
        root = Path(stage)
        shutil.copy2(binary, root / 'opencode')
        for name in ('Dockerfile', 'container_bridge.py'): shutil.copy2(source / name, root / name)
        subprocess.run(['docker', 'build', '--pull=false', '-t', args.tag, stage], check=True)
        subprocess.run(['docker', 'image', 'inspect', args.tag, '--format', '{{.Id}}'], check=True)


if __name__ == '__main__': main()
