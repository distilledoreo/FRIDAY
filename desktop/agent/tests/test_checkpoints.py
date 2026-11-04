import json
from pathlib import Path
import tempfile
import unittest

from desktop.agent.checkpoints import Checkpoints
from desktop.agent.tests.test_code import git


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'; self.root.mkdir()
        self.store = Checkpoints(Path(self.tmp.name) / 'private')
        git('init', '-q', '-b', 'main', cwd=self.root)
        (self.root / 'app.py').write_text('original\n')
        (self.root / '.gitignore').write_text('cache/\n')
        git('add', '.', cwd=self.root)
        git('commit', '-qm', 'Initial', cwd=self.root)

    def test_restore_and_restore_backup_preserve_files_modes_untracked_and_git_state(self):
        (self.root / 'notes').write_text('untracked original')
        (self.root / 'app.py').chmod(0o755)
        original = self.store.save('task', self.root, 'Original')
        (self.root / 'app.py').write_text('broken\n')
        (self.root / 'notes').unlink()
        (self.root / 'added').write_text('keep in backup')
        (self.root / 'cache').mkdir(); (self.root / 'cache/data').write_text('ignored')
        git('add', 'app.py', cwd=self.root)
        head = git('rev-parse', 'HEAD', cwd=self.root)
        index = git('ls-files', '--stage', cwd=self.root)
        result = self.store.restore('task', self.root, original['id'])
        self.assertEqual((self.root / 'app.py').read_text(), 'original\n')
        self.assertEqual((self.root / 'notes').read_text(), 'untracked original')
        self.assertFalse((self.root / 'added').exists())
        self.assertEqual((self.root / 'app.py').stat().st_mode & 0o777, 0o755)
        self.assertEqual((self.root / 'cache/data').read_text(), 'ignored')
        self.assertEqual(git('rev-parse', 'HEAD', cwd=self.root), head)
        self.assertEqual(git('ls-files', '--stage', cwd=self.root), index)
        # A fresh instance can recover the state from before the restore.
        self.store = Checkpoints(self.store.directory)
        self.store.restore('task', self.root, result['backup'])
        self.assertEqual((self.root / 'app.py').read_text(), 'broken\n')
        self.assertEqual((self.root / 'added').read_text(), 'keep in backup')
        self.assertFalse((self.root / 'notes').exists())

    def test_symlinks_restore_link_text_without_touching_targets(self):
        outside = Path(self.tmp.name) / 'outside'; outside.write_text('secret')
        (self.root / 'link').symlink_to(outside)
        checkpoint = self.store.save('task', self.root, 'Link')
        (self.root / 'link').unlink(); (self.root / 'link').write_text('ordinary')
        self.store.restore('task', self.root, checkpoint['id'])
        self.assertTrue((self.root / 'link').is_symlink())
        self.assertEqual(outside.read_text(), 'secret')

    def test_corrupt_or_missing_blobs_do_not_modify_the_worktree(self):
        checkpoint = self.store.save('task', self.root, 'Saved')
        data = self.store.load('task', checkpoint['id'])
        blob = self.store.folder('task') / 'blobs' / data['entries']['app.py']['blob']
        blob.write_bytes(b'corrupt')
        (self.root / 'app.py').write_text('current')
        with self.assertRaises(ValueError): self.store.restore('task', self.root, checkpoint['id'])
        self.assertEqual((self.root / 'app.py').read_text(), 'current')

    def test_restore_refuses_newly_ignored_files_and_keeps_a_backup(self):
        (self.root / 'note').write_text('old note')
        checkpoint = self.store.save('task', self.root, 'Saved')
        (self.root / '.gitignore').write_text('cache/\nnote\n')
        (self.root / 'note').write_text('ignored private content')
        with self.assertRaisesRegex(ValueError, 'ignored'):
            self.store.restore('task', self.root, checkpoint['id'])
        self.assertEqual((self.root / 'note').read_text(), 'ignored private content')
        self.assertEqual(len(self.store.list('task')), 2)

    def test_deleted_tracked_files_and_new_parent_directories_restore(self):
        (self.root / 'app.py').unlink()
        (self.root / 'src').mkdir(); (self.root / 'src/new.py').write_text('source')
        checkpoint = self.store.save('task', self.root, 'Deleted')
        (self.root / 'src/new.py').unlink(); (self.root / 'src').rmdir()
        (self.root / 'app.py').write_text('recreated')
        self.store.restore('task', self.root, checkpoint['id'])
        self.assertFalse((self.root / 'app.py').exists())
        self.assertEqual((self.root / 'src/new.py').read_text(), 'source')

    def test_unsafe_snapshot_path_or_symlink_parent_is_refused(self):
        checkpoint = self.store.save('task', self.root, 'Saved')
        path = self.store.folder('task') / (checkpoint['id'] + '.json')
        data = json.loads(path.read_text())
        data['entries']['../outside'] = {'kind': 'absent'}
        path.write_text(json.dumps(data))
        with self.assertRaises(ValueError): self.store.restore('task', self.root, checkpoint['id'])
        self.assertEqual((self.root / 'app.py').read_text(), 'original\n')
