#!/usr/bin/env python3
"""Publish a verified APK and its update manifest to the existing server install."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def main():
    apk = Path(sys.argv[1]).resolve()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    tools = sdk / 'build-tools/35.0.0'
    subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], check=True)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != 'com.localfirst.assistant':
        raise ValueError('Expected a FRIDAY APK')
    code, version = int(package[2]), package[3]
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', version):
        raise ValueError('Invalid version name')
    dest = Path(os.environ.get('SERVER_DIR', str(Path.home() / 'assistant-server'))) / 'apk'
    dest.mkdir(parents=True, exist_ok=True)
    manifest = dest / 'release.json'
    if manifest.exists() and code <= json.loads(manifest.read_text())['versionCode']:
        raise ValueError('The published version must increase')
    filename = f'local-assistant-{version}.apk'
    target = dest / filename
    shutil.copyfile(apk, target.with_suffix('.apk.tmp'))
    target.with_suffix('.apk.tmp').replace(target)
    release = dict(filename=filename, versionCode=code, versionName=version,
                   sha256=hashlib.sha256(target.read_bytes()).hexdigest())
    temporary = manifest.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(release, indent=2) + '\n')
    temporary.replace(manifest)
    print(f'Published FRIDAY {version} (version code {code})')


if __name__ == '__main__':
    main()
