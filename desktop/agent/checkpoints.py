"""Private, bounded working-file recovery. Never runs repository code or resets Git.

Ignored files, Git history and staging are deliberately outside restore scope.
Every restore first saves a new checkpoint, including nonignored untracked files.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import uuid

from .verification import fingerprint, MAX_BYTES, MAX_FILES


def atomic_write(path, data):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def safe_parts(name):
    parts = Path(name).parts
    if not parts or Path(name).is_absolute() or '..' in parts or '.git' in parts or '\0' in name:
        raise ValueError('Invalid checkpoint path')
    return parts


def parent_fd(root, name, create=False):
    parts = safe_parts(name)
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            if create:
                try: os.mkdir(part, dir_fd=fd)
                except FileExistsError: pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd, parts[-1]
    except BaseException:
        os.close(fd)
        raise


class Checkpoints:
    def __init__(self, directory):
        self.directory = Path(directory)

    def folder(self, task):
        return self.directory / hashlib.sha256(task.encode()).hexdigest()

    def copy_to(self, task, identifier, destination):
        data = self.load(task, identifier)
        folder = self.folder(destination)
        (folder / 'blobs').mkdir(parents=True, exist_ok=True, mode=0o700)
        total = 0
        for entry in data['entries'].values():
            if entry['kind'] != 'file': continue
            source = self.folder(task) / 'blobs' / entry['blob']
            with os.fdopen(os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
                if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode): raise ValueError('Invalid checkpoint content')
                content = stream.read(MAX_BYTES + 1)
            total += len(content)
            if total > MAX_BYTES or hashlib.sha256(content).hexdigest() != entry['blob']: raise ValueError('Checkpoint content damaged')
            atomic_write(folder / 'blobs' / entry['blob'], content)
        atomic_write(folder / (identifier + '.json'), json.dumps(data).encode())

    def list(self, task):
        result = []
        for path in sorted(self.folder(task).glob('*.json'), reverse=True)[:100]:
            try:
                data = self.load(task, path.stem)
                result.append({k: data[k] for k in ('id', 'label', 'created', 'head', 'files')})
            except (OSError, ValueError, KeyError): continue
        return result

    def load(self, task, identifier):
        if not re.fullmatch(r'\d+-[a-f0-9]{32}', identifier): raise ValueError('Invalid checkpoint ID')
        path = self.folder(task) / (identifier + '.json')
        if path.is_symlink() or path.stat().st_size > 16 * 1024 * 1024: raise ValueError('Invalid checkpoint')
        data = json.loads(path.read_text())
        if (not isinstance(data, dict) or data.get('id') != identifier or not isinstance(data.get('entries'), dict)
            or len(data['entries']) > MAX_FILES or not isinstance(data.get('label'), str)
            or not isinstance(data.get('head'), str) or not isinstance(data.get('files'), int)
            or not isinstance(data.get('created'), (float, int))): raise ValueError('Invalid checkpoint')
        for name, entry in data['entries'].items():
            safe_parts(name)
            if not isinstance(entry, dict) or entry.get('kind') not in ('file', 'link', 'absent'): raise ValueError('Invalid checkpoint entry')
            if entry['kind'] == 'file':
                if not isinstance(entry.get('blob'), str) or not re.fullmatch(r'[a-f0-9]{64}', entry['blob']): raise ValueError('Invalid blob')
                if type(entry.get('mode')) is not int or not 0 <= entry['mode'] <= 0o7777: raise ValueError('Invalid file mode')
            if entry['kind'] == 'link' and (not isinstance(entry.get('target'), str) or '\0' in entry['target']): raise ValueError('Invalid link target')
        return data

    def save(self, task, root, label):
        from .code import read_worktree_file
        before = fingerprint(root)
        if not before['tree']: raise ValueError(before['reason'])
        result = subprocess.run(['git', '--no-optional-locks', '-c', 'core.fsmonitor=false', 'ls-files',
                                 '--cached', '--others', '--exclude-standard', '-z'], cwd=root,
                                stdin=subprocess.DEVNULL, capture_output=True, check=True, timeout=10)
        names = sorted(set(os.fsdecode(n) for n in result.stdout.split(b'\0') if n))
        if len(names) > MAX_FILES: raise ValueError('Checkpoint file limit exceeded')
        folder = self.folder(task)
        blobs = folder / 'blobs'
        blobs.mkdir(parents=True, exist_ok=True, mode=0o700)
        entries, total = {}, 0
        for name in names:
            safe_parts(name)
            try:
                fd, leaf = parent_fd(root, name)
                try: mode = os.stat(leaf, dir_fd=fd, follow_symlinks=False).st_mode
                finally: os.close(fd)
            except FileNotFoundError:
                entries[name] = {'kind': 'absent'}
                continue
            data, size, link = read_worktree_file(root, name, MAX_BYTES, read_link=True)
            total += size
            if total > MAX_BYTES or size != len(data): raise ValueError('Checkpoint byte limit exceeded')
            if link: entries[name] = {'kind': 'link', 'target': os.fsdecode(data)}
            else:
                digest = hashlib.sha256(data).hexdigest()
                target = blobs / digest
                if not target.exists(): atomic_write(target, data)
                entries[name] = {'kind': 'file', 'blob': digest, 'mode': stat.S_IMODE(mode)}
        if fingerprint(root)['tree'] != before['tree']: raise ValueError('Files changed during checkpoint; retry when idle')
        identifier = f'{time.time_ns()}-{uuid.uuid4().hex}'
        data = dict(id=identifier, label=label[:120], created=time.time(), head=before['head'], files=len(entries),
                    tree=before['tree'], entries=entries)
        atomic_write(folder / (identifier + '.json'), json.dumps(data).encode())
        return {k: data[k] for k in ('id', 'label', 'created', 'head', 'files')}

    def restore(self, task, root, identifier):
        target = self.load(task, identifier)
        # Verify all saved contents before changing any files. No symlink targets
        # are read, and malformed/missing evidence cannot initiate a restore.
        contents, total = {}, 0
        for name, entry in target['entries'].items():
            if entry['kind'] == 'file':
                path = self.folder(task) / 'blobs' / entry['blob']
                with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
                    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode): raise ValueError('Invalid checkpoint content')
                    data = stream.read(MAX_BYTES + 1)
                total += len(data)
                if total > MAX_BYTES or hashlib.sha256(data).hexdigest() != entry['blob']: raise ValueError('Checkpoint content damaged')
                contents[name] = data
        backup = self.save(task, root, 'Before restore of ' + target['label'])
        current = self.load(task, backup['id'])
        if fingerprint(root)['tree'] != current['tree']: raise ValueError('Files changed before restore')
        # A path that has become ignored since the snapshot must never be
        # overwritten. Also reject directory/file topology changes up front;
        # these need a manual recovery rather than a recursive deletion.
        for name, entry in target['entries'].items():
            if entry['kind'] == 'absent': continue
            try:
                fd, leaf = parent_fd(root, name)
                try:
                    try: info = os.stat(leaf, dir_fd=fd, follow_symlinks=False)
                    except FileNotFoundError: info = None
                    if info and (name not in current['entries'] or stat.S_ISDIR(info.st_mode)):
                        raise ValueError('Restore would overwrite ignored files or a directory: ' + name)
                finally: os.close(fd)
            except FileNotFoundError: pass
        try:
            names = set(current['entries']) | set(target['entries'])
            for name in sorted(names, key=lambda n: (-len(Path(n).parts), n)):
                entry = target['entries'].get(name, {'kind': 'absent'})
                try: fd, leaf = parent_fd(root, name, create=entry['kind'] != 'absent')
                except FileNotFoundError: continue
                try:
                    if entry['kind'] == 'absent':
                        try: os.unlink(leaf, dir_fd=fd)
                        except FileNotFoundError: pass
                    else:
                        temporary = '.friday-restore-' + uuid.uuid4().hex
                        try:
                            if entry['kind'] == 'link': os.symlink(entry['target'], temporary, dir_fd=fd)
                            else:
                                with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd), 'wb') as stream:
                                    stream.write(contents[name])
                                    os.fchmod(stream.fileno(), entry['mode'])
                                    stream.flush()
                                    os.fsync(stream.fileno())
                            os.replace(temporary, leaf, src_dir_fd=fd, dst_dir_fd=fd)
                        finally:
                            try: os.unlink(temporary, dir_fd=fd)
                            except FileNotFoundError: pass
                finally: os.close(fd)
        except OSError as error:
            raise ValueError(f'Restore interrupted; recover using checkpoint {backup["id"]}: {error.strerror}') from error
        return {'restored': identifier, 'backup': backup['id'], 'scope': 'Working files only; Git history, staging and ignored files stay unchanged.'}
