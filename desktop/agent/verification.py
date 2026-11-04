"""Local coding check receipts. No repository code or shell is executed here.

Fingerprints cover HEAD, index entries, tracked files and nonignored untracked
files, including modes and symlink text. Ignored dependencies/environment are not
covered. This is evidence of checks on sampled content, not a correctness proof
or a sandbox against a process changing and restoring files during a check.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import uuid

MAX_BYTES = 256 * 1024 * 1024
MAX_FILES = 20000
MAX_OUTPUT = 2 * 1024 * 1024


def profile_revision(checks, environment=None, epoch=0):
    policy = {'checks': checks, 'environment': environment or {}, 'epoch': epoch} if environment or epoch else checks
    return hashlib.sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest()


def profile_path(directory, worktree):
    return Path(directory) / 'profiles' / (hashlib.sha256(str(Path(worktree).resolve()).encode()).hexdigest() + '.json')


def save_profile(directory, worktree, checks, *, environment=None, setup=None, epoch=0):
    target = profile_path(directory, worktree)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = target.with_suffix('.' + uuid.uuid4().hex + '.tmp')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        json.dump({'checks': checks, 'environment': environment or {}, 'setup': setup or [],
                   'epoch': epoch, 'revision': profile_revision(checks, environment, epoch)}, stream)
    os.replace(temporary, target)


def canonical_command(command):
    # A terminal stderr-to-stdout merge preserves execution and exit status.
    # Do not normalize pipelines, command chains, redirects to files or masks.
    return re.sub(r'\s+2>&1\s*$', '', command.removeprefix('set -o pipefail; ').strip())


def fingerprint(root):
    try:
        root = Path(root)
        if root.is_symlink(): raise ValueError('Worktree root is a symlink')
        def git(*args):
            result = subprocess.run(['git', '--no-optional-locks', '-c', 'core.fsmonitor=false', *args],
                                    cwd=root, stdin=subprocess.DEVNULL, capture_output=True, timeout=10)
            if result.returncode: raise ValueError('Git state unavailable')
            return result.stdout
        index = git('ls-files', '--stage', '-z')
        if any(row.startswith(b'160000 ') for row in index.split(b'\0')):
            raise ValueError('Submodule content is not fingerprinted')
        names = git('ls-files', '--cached', '--others', '--exclude-standard', '-z')
        paths = sorted(set(filter(None, names.split(b'\0'))))
        if len(paths) > MAX_FILES: raise ValueError('Fingerprint file limit exceeded')
        digest = hashlib.sha256()
        head = git('rev-parse', 'HEAD')
        for data in (head, index): digest.update(len(data).to_bytes(8, 'big') + data)
        total = 0
        started = time.monotonic()
        for name in paths:
            if time.monotonic() - started > 10: raise ValueError('Fingerprint time limit exceeded')
            parts = Path(os.fsdecode(name)).parts
            if not parts or '..' in parts or Path(os.fsdecode(name)).is_absolute(): raise ValueError('Invalid Git path')
            fds = []
            try:
                fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW); fds.append(fd)
                for part in parts[:-1]:
                    fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd); fds.append(fd)
                try: info = os.stat(parts[-1], dir_fd=fd, follow_symlinks=False)
                except FileNotFoundError:
                    content, mode = b'deleted', 0
                else:
                    mode = info.st_mode
                    if stat.S_ISLNK(mode): content = os.fsencode(os.readlink(parts[-1], dir_fd=fd))
                    elif stat.S_ISREG(mode):
                        total += info.st_size
                        if total > MAX_BYTES: raise ValueError('Fingerprint byte limit exceeded')
                        source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd); fds.append(source)
                        before = os.fstat(source)
                        if not stat.S_ISREG(before.st_mode): raise ValueError('File type changed')
                        file_hash = hashlib.sha256()
                        size = 0
                        while chunk := os.read(source, 65536):
                            size += len(chunk)
                            if size > info.st_size or time.monotonic() - started > 10: raise ValueError('File changed or scan timed out')
                            file_hash.update(chunk)
                        after = os.fstat(source)
                        if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                            raise ValueError('File changed while fingerprinting')
                        content = file_hash.digest()
                    else: raise ValueError('Special file is not fingerprinted')
            except FileNotFoundError:
                # A tracked deletion may remove its parent directories too.
                # This is still ordinary Git-visible deleted content.
                content, mode = b'deleted', 0
            finally:
                for fd in reversed(fds): os.close(fd)
            entry = name + b'\0' + str(mode).encode() + b'\0' + content
            digest.update(len(entry).to_bytes(8, 'big') + entry)
        if index != git('ls-files', '--stage', '-z') or names != git('ls-files', '--cached', '--others', '--exclude-standard', '-z') or head != git('rev-parse', 'HEAD'):
            raise ValueError('Git state changed while fingerprinting')
        return {'tree': digest.hexdigest(), 'head': head.decode().strip(), 'index_sha256': hashlib.sha256(index).hexdigest(),
                'files': len(paths), 'bytes': total, 'reason': None}
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        return {'tree': None, 'reason': str(error)[:200]}


def runner_result(output, exit_code, truncated=False):
    count, verdict = None, 'unconfirmed'
    skipped = 0
    match = re.search(r'(?m)^Ran (\d+) tests? in ', output)
    if match:
        count = int(match[1])
        skip = re.search(r'(?m)^OK \(skipped=(\d+)\)$', output)
        if skip: skipped = int(skip[1])
        if re.search(r'(?m)^FAILED \(', output): verdict = 'failed'
        elif re.search(r'(?m)^OK(?: \(skipped=\d+\))?$', output): verdict = 'passed'
    else:
        match = re.search(r'(\d+) passed(?:,| in |\s*=)', output)
        if match:
            count, verdict = int(match[1]), 'passed'
        match = re.search(r'(?m)^[#ℹ]\s*tests\s+(\d+)', output)
        if match and re.search(r'(?m)^[#ℹ]\s*fail\s+0\s*$', output): count, verdict = int(match[1]), 'passed'
        if re.search(r'\b[1-9]\d* (?:failed|errors?)\b|(?m:^FAIL\b)', output): verdict = 'failed'
    if exit_code is not None and exit_code != 0: verdict = 'failed'
    elif verdict == 'passed' and (exit_code != 0 or not count or count <= skipped or truncated): verdict = 'unconfirmed'
    return {'result': verdict, 'tests': count, 'skipped': skipped}


def session_directory(directory, session):
    return Path(directory) / hashlib.sha256(session.encode()).hexdigest()


def record(directory, data):
    Path(directory).mkdir(parents=True, exist_ok=True, mode=0o700)
    folder = session_directory(directory, data['session'])
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    identifier = f'{time.time_ns()}-{uuid.uuid4().hex}'
    output = data.get('output', '')
    truncated = bool(data.get('truncated')) or len(output.encode()) > MAX_OUTPUT
    output = output.encode()[:MAX_OUTPUT].decode(errors='ignore')
    after = fingerprint(data['worktree'])
    before = data.get('before') or {'tree': None, 'reason': 'No starting fingerprint'}
    cwd = Path(data.get('cwd') or data['worktree']).resolve()
    scope_valid = cwd.is_relative_to(Path(data['worktree']).resolve())
    receipt = {'id': identifier, 'command': data['command'], 'created': time.time(),
               'profile_revision': data.get('profile_revision'),
               'cwd': str(cwd), 'scope_valid': scope_valid,
               'exit': data.get('exit'), **runner_result(output, data.get('exit'), truncated),
               'before': before, 'after': after, 'stable': bool(before['tree'] and before['tree'] == after['tree']),
               'output_truncated': truncated, 'output_sha256': hashlib.sha256(output.encode()).hexdigest(),
               'artifact': str(folder / (identifier + '.txt'))}
    for suffix, content in (('.txt', output), ('.json', json.dumps(receipt))):
        target = folder / (identifier + suffix)
        temporary = folder / (identifier + suffix + '.tmp')
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    directory_fd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(directory_fd)
    finally: os.close(directory_fd)
    return receipt


def view(directory, session, worktree, checks=None, *, sampled=None, environment=None, epoch=0):
    folder = session_directory(directory, session)
    receipts = []
    damaged = False
    for path in sorted(folder.glob('*.json'), reverse=True)[:100]:
        try:
            if path.stat().st_size > 256000: raise ValueError('Oversized receipt')
            receipt = json.loads(path.read_text())
            if (not isinstance(receipt, dict) or receipt.get('id') != path.stem or
                not re.fullmatch(r'\d+-[a-f0-9]{32}', path.stem) or
                not isinstance(receipt.get('command'), str) or
                not isinstance(receipt.get('after'), dict) or 'tree' not in receipt['after'] or
                receipt.get('result') not in ('passed', 'failed', 'unconfirmed') or
                not isinstance(receipt.get('stable'), bool) or not isinstance(receipt.get('output_sha256'), str)):
                raise ValueError('Invalid receipt')
            receipts.append(receipt)
        except (OSError, ValueError): damaged = True
    current = (sampled or fingerprint(worktree)) if receipts else {'tree': None, 'reason': None}
    latest = {}
    for receipt in receipts:
        receipt['current'] = bool(current['tree'] and receipt['stable'] and receipt.get('scope_valid') and current['tree'] == receipt['after']['tree'])
        if environment is not None and (environment or epoch or receipt.get('profile_revision')):
            receipt['current'] = receipt['current'] and receipt.get('profile_revision') == profile_revision(checks or [], environment, epoch)
        try:
            artifact = folder / (receipt['id'] + '.txt')
            if not re.fullmatch(r'\d+-[a-f0-9]{32}', receipt['id']): raise ValueError('Invalid receipt')
            with os.fdopen(os.open(artifact, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
                if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode): raise ValueError('Not an output file')
                data = stream.read(MAX_OUTPUT + 1)
            if hashlib.sha256(data).hexdigest() != receipt['output_sha256']: raise ValueError('Output changed')
            receipt['output_excerpt'] = data.decode(errors='replace')[-4000:]
            receipt['artifact'] = str(artifact)
        except (OSError, ValueError):
            receipt['output_excerpt'] = 'Saved output unavailable or changed'
            receipt['current'] = False
            damaged = True
        latest.setdefault(receipt['command'], receipt)
    active = [r for r in latest.values() if r['current']]
    state = 'unverified'
    if any(r['result'] == 'failed' for r in active): state = 'failed'
    elif active and all(r['result'] == 'passed' for r in active): state = 'passed'
    elif current['tree'] and not active and any(r['stable'] and r.get('scope_valid') for r in receipts): state = 'stale'
    if damaged: state = 'unverified'
    required = []
    if checks:
        revision = profile_revision(checks, environment, epoch)
        for check in checks:
            cwd = str((Path(worktree) / check['cwd']).resolve())
            matching = next((r for r in receipts if canonical_command(r['command']) == canonical_command(check['command'])
                             and r.get('cwd') == cwd and r.get('profile_revision') == revision), None)
            result = 'missing'
            if matching:
                if not matching['current']: result = 'stale'
                elif matching['result'] == 'failed' or matching.get('exit') not in (0, None): result = 'failed'
                elif check['runner'] == 'exit' and matching.get('exit') == 0 and not matching.get('output_truncated'): result = 'passed'
                elif matching['result'] == 'passed' and (matching.get('tests') or 0) >= check['min_tests']: result = 'passed'
                else: result = 'unconfirmed'
            required.append({**check, 'state': result, 'receipt': matching['id'] if matching else None})
        states = [r['state'] for r in required]
        state = ('failed' if 'failed' in states else 'passed' if all(s == 'passed' for s in states)
                 else 'stale' if all(s in ('passed', 'stale') for s in states) else 'unverified')
        if damaged: state = 'unverified'
    return {'state': state, 'tree': current['tree'], 'reason': current['reason'], 'receipts': receipts[:10],
            'required': required, 'profile_revision': profile_revision(checks, environment, epoch) if checks else None,
            'scope': 'Tracked and nonignored files; ignored dependencies and environment are not covered.'}


if __name__ == '__main__':
    payload = json.load(sys.stdin)
    if sys.argv[1] == 'snapshot': result = fingerprint(payload['worktree'])
    else:
        result = record(payload['directory'], payload)
        try:
            profile = json.loads(profile_path(payload['directory'], payload['worktree']).read_text())
            result['required'] = view(payload['directory'], payload['session'], payload['worktree'], profile['checks'],
                                      sampled=result['after'], environment=profile.get('environment'), epoch=profile.get('epoch', 0))['required']
        except (OSError, ValueError, KeyError): result['required'] = []
    print(json.dumps(result))
