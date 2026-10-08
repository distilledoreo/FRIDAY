import sqlite3,tempfile,unittest,os
from pathlib import Path
from unittest.mock import patch
from ..backup import backup

class BackupTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.src=self.root/'memory.sqlite';self.dst=self.root/'backups'
        self.db=sqlite3.connect(self.src);self.db.execute('PRAGMA journal_mode=WAL');self.db.execute('CREATE TABLE facts(text)');self.db.execute("INSERT INTO facts VALUES('test memory')");self.db.commit()
    def tearDown(self):self.db.close();self.tmp.cleanup()
    def test_snapshot_contains_committed_wal_and_private_permissions(self):
        target=backup(self.src,self.dst,min_free=0)
        with sqlite3.connect(target) as db:self.assertEqual(db.execute('SELECT text FROM facts').fetchone()[0],'test memory')
        self.assertEqual(os.stat(target).st_mode&0o777,0o600);self.assertEqual(os.stat(self.dst).st_mode&0o777,0o700)
    def test_rotation_preserves_other_files_and_latest(self):
        self.dst.mkdir();other=self.dst/'personal.sqlite';other.write_bytes(b'keep')
        first=backup(self.src,self.dst,keep=1,min_free=0);second=backup(self.src,self.dst,keep=1,min_free=0)
        self.assertFalse(first.exists());self.assertTrue(second.exists());self.assertTrue(other.exists())
    def test_insufficient_space_preserves_existing_backup(self):
        first=backup(self.src,self.dst,min_free=0)
        with patch('desktop.memory.backup.shutil.disk_usage',return_value=type('Usage',(),{'free':0})()):
            with self.assertRaises(ValueError):backup(self.src,self.dst)
        self.assertTrue(first.exists());self.assertFalse(list(self.dst.glob('.snapshot-*')))
    def test_too_small_budget_does_not_create_snapshot(self):
        with self.assertRaises(ValueError):backup(self.src,self.dst,max_bytes=1,min_free=0)
        self.assertFalse(list(self.dst.glob('friday-memory-*')))
