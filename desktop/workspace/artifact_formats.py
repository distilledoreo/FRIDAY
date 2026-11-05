"""Bounded offline import/export/preview jobs. No user credentials enter the renderer."""
import asyncio
import contextvars
import base64
import io
import json
import os
import re
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile

from fastapi import HTTPException, Request
from .artifacts import Mutation, digest, encoded, require, validate
from .artifact_worker import calculate

RENDERER = 'friday-artifacts-1'
_slots = threading.BoundedSemaphore(2)
_cancel = contextvars.ContextVar('artifact_conversion_cancel', default=None)


async def conversion(request, function, *args):
    stopped = threading.Event()
    token = _cancel.set(stopped)
    task = asyncio.create_task(asyncio.to_thread(function, *args))
    try:
        while not task.done():
            if await request.is_disconnected():
                stopped.set()
                # Retrieve the thread result/exception; its child process is killed by the worker loop.
                try: await task
                except Exception: pass
                raise HTTPException(499, 'Conversion cancelled')
            await asyncio.sleep(.15)
        return await task
    finally:
        stopped.set()
        _cancel.reset(token)


def office_root():
    # Installation chooses an explicit local executable. Never download software during a conversion.
    path = os.environ.get('FRIDAY_SOFFICE') or shutil.which('libreoffice') or str(Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'friday/libreoffice/program/soffice')
    exe = Path(path).resolve()
    return exe.parent.parent if exe.is_file() and exe.parent.name == 'program' else None


def run_worker(workspace, job, files=()):
    require(shutil.which('bwrap'), 'Artifact rendering requires bubblewrap', 503)
    require(_slots.acquire(blocking=False), 'Two artifact conversions are already running; retry shortly', 429)
    try:
        with tempfile.TemporaryDirectory(prefix='friday-artifact-') as scratch:
            root = Path(scratch); work = root / 'work'; work.mkdir(); inputs = work / 'inputs'; inputs.mkdir()
            for fid in set(files):
                info = workspace.file(fid)
                require(info['size'] <= 25*1024*1024, 'Renderer input exceeds 25 MB', 413)
                target = inputs / fid
                shutil.copyfile(workspace.files / fid, target)
                # Office detects extensions; preserve the suffix without trusting the input filename.
                if job.get('file') == fid and job.get('suffix'):
                    target.rename(inputs / (fid + job['suffix'])); job = dict(job, file=fid + job['suffix'])
            (work / 'job.json').write_text(encoded(job))
            shutil.copyfile(Path(__file__).with_name('artifact_worker.py'), work / 'worker.py')
            cmd = ['bwrap', '--unshare-all', '--unshare-user', '--disable-userns', '--die-with-parent', '--new-session', '--clearenv',
                   '--ro-bind', '/usr', '/usr', '--ro-bind', '/bin', '/bin', '--ro-bind', '/lib', '/lib', '--proc', '/proc', '--dev', '/dev', '--tmpfs', '/tmp',
                   '--bind', str(work), '/work', '--setenv', 'HOME', '/tmp', '--setenv', 'PATH', '/usr/bin', '--setenv', 'LANG', 'C.UTF-8', '--chdir', '/work']
            if Path('/lib64').exists(): cmd += ['--ro-bind', '/lib64', '/lib64']
            if not str(Path(sys.prefix)).startswith('/usr'): cmd += ['--ro-bind', sys.prefix, sys.prefix]
            office = office_root()
            if office: cmd += ['--ro-bind', str(office), '/office']
            if job['action'] == 'export' and job['format'] == 'pdf' and job['item']['kind'] == 'document' or job['action'] == 'preview' and job['suffix'] in {'.docx', '.pptx'}:
                require(office, 'Install LibreOffice or configure FRIDAY_SOFFICE to preview DOCX or export document PDF', 503)
            cmd += [sys.executable, '-I', '/work/worker.py']
            log = root / 'log'
            with log.open('wb') as output:
                proc = subprocess.Popen(cmd, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                deadline = time.monotonic() + 65
                try:
                    while proc.poll() is None:
                        size = 0; count = 0
                        for folder, directories, names in os.walk(work):
                            count += len(directories)
                            for name in names:
                                p = Path(folder) / name
                                # Converters rename temporary outputs while this monitor runs.
                                # lstat also avoids following a converter-created symlink on the host.
                                try: entry = p.lstat()
                                except FileNotFoundError: continue
                                if stat.S_ISREG(entry.st_mode): size += entry.st_size
                                count += 1
                        if (_cancel.get() is not None and _cancel.get().is_set()) or time.monotonic() > deadline or size > 150*1024*1024 or count > 500 or log.stat().st_size > 1000000:
                            raise HTTPException(422, 'Conversion exceeded its time, file or output limit')
                        time.sleep(.1)
                finally:
                    if proc.poll() is None:
                        os.killpg(proc.pid, signal.SIGKILL); proc.wait()
            require(proc.returncode == 0, 'Conversion failed: ' + log.read_text(errors='replace')[-1600:], 422)
            result_path = work / 'out/result.json'
            require(result_path.is_file() and result_path.stat().st_size <= 2*1024*1024, 'Invalid converter result', 422)
            result = json.loads(result_path.read_text())
            if result.get('file'):
                path = work / 'out' / result['file']
                require(path.parent == work / 'out' and path.is_file() and not path.is_symlink() and path.stat().st_size <= 25*1024*1024, 'Invalid converter output', 422)
                result['bytes'] = path.read_bytes()
            return result
    finally: _slots.release()


def export(workspace, aid, body):
    with workspace._artifact_format_lock:
        return _export(workspace, aid, body)


def _export(workspace, aid, body):
    store = workspace.artifacts
    item = store.get(aid, body.get('revision'))
    fmt = body.get('format')
    require(fmt in {'md', 'docx', 'pdf', 'html', 'json', 'csv', 'xlsx', 'pptx'}, 'Unsupported export format')
    cache = digest([aid, item['contentRevision'], item['hash'], fmt, RENDERER])
    with workspace.db() as db:
        old = db.execute('SELECT * FROM artifact_exports WHERE key=?', (cache,)).fetchone()
        if old:
            try: return {'file': workspace.file(old['file']), 'revision': item['contentRevision'], 'warnings': json.loads(old['preview'])}
            except HTTPException: pass
    files = [b['file'] for b in item['content'].get('blocks', []) if b['type'] == 'image']
    result = run_worker(workspace, {'action': 'export', 'item': item, 'format': fmt}, files)
    info = workspace.put_file(result.pop('bytes'), item['title'] + '.' + fmt)
    with workspace.db() as db:
        db.execute('INSERT OR REPLACE INTO artifact_exports VALUES(?,?,?,?,?,?)', (cache, aid, item['contentRevision'], fmt, info['id'], encoded(result['warnings'])))
    return {'file': info, 'revision': item['contentRevision'], 'warnings': result['warnings']}


def preview(workspace, fid, page):
    with workspace._artifact_format_lock:
        return _preview(workspace, fid, page)


def _preview(workspace, fid, page):
    info = workspace.file(fid); suffix = Path(info['name']).suffix.lower()
    require(suffix in {'.pdf', '.docx', '.pptx', '.png', '.jpg', '.jpeg', '.webp'}, 'Preview supports PDF, DOCX and images')
    require(0 <= page <= 1000, 'Invalid page')
    cache = digest(['preview', fid, page, RENDERER])
    with workspace.db() as db:
        old = db.execute('SELECT * FROM artifact_exports WHERE key=?', (cache,)).fetchone()
        if old:
            try: return {'file': workspace.file(old['file']), **json.loads(old['preview'])}
            except HTTPException: pass
    result = run_worker(workspace, {'action': 'preview', 'file': fid, 'suffix': suffix, 'page': page}, [fid])
    file = workspace.put_file(result.pop('bytes'), 'preview.png', 'image/png')
    metadata = {'pages': result['pages'], 'page': page, 'source': fid, 'warnings': result['warnings']}
    with workspace.db() as db:
        db.execute('INSERT OR REPLACE INTO artifact_exports VALUES(?,?,?,?,?,?)', (cache, '', 0, 'preview', file['id'], encoded(metadata)))
    return {'file': file, **metadata}


def import_artifact(workspace, body):
    fid = body.get('file'); info = workspace.file(fid); suffix = Path(info['name']).suffix.lower()
    require(suffix in {'.docx', '.md', '.txt', '.xlsx', '.csv', '.tsv', '.pptx'}, 'Import DOCX, Markdown, text, XLSX, CSV or TSV; PDF stays a preview with annotations')
    result = run_worker(workspace, {'action': 'import', 'file': fid, 'suffix': suffix}, [fid])
    for index, block in enumerate(result['content'].get('blocks', [])):
        block['id'] = digest([fid, index])[:32]
    validate(result['kind'], result['content'])
    made = workspace.artifacts.create({'request': body['request'], 'kind': result['kind'], 'title': info['name'], 'content': result['content'], 'source': 'file:' + fid, 'warnings': result['warnings']})
    with workspace.db() as db: db.execute('INSERT OR IGNORE INTO artifact_links VALUES(?,?)', (made['id'], fid))
    return dict(made, warnings=result['warnings'])


def backup(workspace):
    # A consistent SQLite snapshot plus immutable referenced files. No settings, tokens or chats.
    with workspace.db() as db:
        db.execute('BEGIN')
        tables = {name: [dict(r) for r in db.execute('SELECT * FROM ' + name)] for name in
                  ['artifacts', 'artifact_revisions', 'artifact_comments', 'artifact_proposals', 'artifact_state', 'artifact_state_revisions', 'artifact_links']}
        ids = {r['file'] for r in tables['artifact_links']}
        files = [workspace.file(fid) for fid in ids]
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('manifest.json', encoded({'version': 1, 'tables': tables, 'files': files}))
            for file in files: archive.write(workspace.files / file['id'], 'files/' + file['id'])
    return workspace.put_file(output.getvalue(), 'friday-artifacts.zip', 'application/zip')


def restore_backup(workspace, body):
    """Restore as new copies, including all revisions and assets. Never overwrite existing work."""
    info = workspace.file(body.get('file'))
    raw = (workspace.files / info['id']).read_bytes()
    request = {'op': 'restore-backup', 'digest': digest(base64.b64encode(raw).decode())}
    with workspace.db() as db:
        old = db.execute('SELECT * FROM artifact_requests WHERE key=?', (body['request'],)).fetchone()
        if old:
            require(old['hash'] == digest(request), 'Request key reused', 409)
            return json.loads(old['response'])
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            require(len(entries) <= 2000 and sum(e.file_size for e in entries) <= 100*1024*1024, 'Backup exceeds restore limits', 413)
            require(len({e.filename for e in entries}) == len(entries), 'Duplicate archive entries')
            require(archive.getinfo('manifest.json').file_size <= 30*1024*1024, 'Oversized backup manifest', 413)
            manifest = json.loads(archive.read('manifest.json'))
            require(manifest.get('version') == 1, 'Unsupported backup version')
            tables = manifest['tables']; artifacts = tables['artifacts']; files = manifest['files']
            require(isinstance(artifacts, list) and len(artifacts) <= 500, 'Invalid artifact list')
            by_id = {a['id']: a for a in artifacts}
            require(len(by_id) == len(artifacts), 'Duplicate artifact IDs')
            file_ids = {f['id'] for f in files}
            for f in files:
                require(re.fullmatch('[a-f0-9]{32}', f['id']), 'Invalid backup asset ID')
                require(archive.getinfo('files/'+f['id']).file_size <= 25*1024*1024, 'Backup asset exceeds 25 MB', 413)
            revisions = tables['artifact_revisions']
            for revision in revisions:
                a = by_id[revision['artifact']]
                content = json.loads(revision['content']); validate(a['kind'], content)
                require(type(revision['revision']) is int and revision['revision'] > 0, 'Invalid revision')
                require(all(b['file'] in file_ids for b in content.get('blocks', []) if b['type'] == 'image'), 'Backup image missing')
            for a in artifacts:
                require(any(r['artifact'] == a['id'] and r['revision'] == a['revision'] for r in revisions), 'Current revision missing')
            # Parse/validate every file before publishing metadata. Paths are never extracted.
            uploaded = {}
            try:
                for f in files: uploaded[f['id']] = workspace.put_file(archive.read('files/'+f['id']), f['name'], f['mime'])['id']
                ids = {old: uuid.uuid4().hex for old in by_id}
                def remap(content):
                    for block in content.get('blocks', []):
                        if block['type'] == 'image': block['file'] = uploaded[block['file']]
                    return content
                def action(db):
                    for old, a in by_id.items():
                        source = a.get('source', '')
                        if source.startswith('file:'): source = 'file:' + uploaded[source[5:]]
                        elif source.startswith('file-notes:'): source = 'file-notes:' + uploaded[source[11:]]
                        db.execute('INSERT INTO artifacts(id,title,kind,revision,deleted,template,source,updated) VALUES(?,?,?,?,?,?,?,?)', (ids[old], a['title']+' (restored)', a['kind'], a['revision'], int(bool(a['deleted'])), int(bool(a['template'])), source, time.time()))
                        db.execute('UPDATE artifacts SET project=?,warnings=? WHERE id=?', (a.get('project', ''), a.get('warnings', ''), ids[old]))
                    for r in revisions:
                        content = remap(json.loads(r['content']))
                        db.execute('INSERT INTO artifact_revisions VALUES(?,?,?,?,?,?)', (ids[r['artifact']], r['revision'], encoded(content), digest(content), r['summary'], r['created']))
                    for c in tables['artifact_comments']:
                        db.execute('INSERT INTO artifact_comments VALUES(?,?,?,?,?,?,?)', (uuid.uuid4().hex, ids[c['artifact']], c['revision'], c['block'], c['quote'], c['text'], int(bool(c['resolved']))))
                    for p in tables['artifact_proposals']:
                        # Reviewed proposals are retained as historical notes; pending proposals need regeneration after remapping assets.
                        db.execute('INSERT INTO artifact_proposals VALUES(?,?,?,?,?,?,?)', (uuid.uuid4().hex, ids[p['artifact']], p['base'], p['operations'], p['summary'], p['status'] if p['status'] != 'pending' else 'restored-review-required', p['created']))
                    for s in tables['artifact_state']:
                        require(len(s['content'].encode()) <= 200000, 'Oversized app state')
                        json.loads(s['content'])
                        db.execute('INSERT INTO artifact_state VALUES(?,?,?)', (ids[s['artifact']], s['revision'], s['content']))
                    for s in tables.get('artifact_state_revisions', []):
                        require(len(s['content'].encode()) <= 200000, 'Oversized app data revision')
                        json.loads(s['content'])
                        db.execute('INSERT INTO artifact_state_revisions VALUES(?,?,?,?)', (ids[s['artifact']], s['revision'], s['content'], s['created']))
                    for link in tables['artifact_links']: db.execute('INSERT INTO artifact_links VALUES(?,?)', (ids[link['artifact']], uploaded[link['file']]))
                    for aid in ids.values(): workspace.artifacts._publish(db, aid)
                    return {'restored': len(ids), 'ids': list(ids.values())}
                return workspace.artifacts.mutation(body['request'], request, action)
            except Exception:
                with workspace.db() as db:
                    for fid in uploaded.values(): db.execute('DELETE FROM files WHERE id=?', (fid,)); (workspace.files / fid).unlink(missing_ok=True)
                raise
    except (KeyError, ValueError, TypeError, zipfile.BadZipFile) as error:
        raise HTTPException(400, 'Invalid artifact backup') from error


def enable_formats(router, workspace):
    @router.post('/artifacts/{aid}/export')
    async def exporting(aid: str, body: Mutation, request: Request): return await conversion(request, export, workspace, aid, body.model_dump())

    @router.post('/artifact-import')
    async def importing(body: Mutation, request: Request): return await conversion(request, import_artifact, workspace, body.model_dump())

    @router.get('/artifact-preview/{fid}')
    async def previewing(fid: str, request: Request, page: int = 0): return await conversion(request, preview, workspace, fid, page)

    @router.post('/artifact-backup')
    async def backing_up(): return await asyncio.to_thread(backup, workspace)

    @router.post('/artifact-restore')
    async def restoring(body: Mutation): return await asyncio.to_thread(restore_backup, workspace, body.model_dump())

    @router.post('/artifacts/{aid}/calculate')
    async def calculating(aid: str):
        item = workspace.artifacts.get(aid)
        require(item['kind'] == 'sheet', 'Not a spreadsheet')
        return {'revision': item['revision'], 'cells': calculate(item['content']['cells']),
                'supported': 'Arithmetic, cell references, ranges inside SUM/AVERAGE/MIN/MAX/COUNT. Other formulas are labeled unsupported.'}
