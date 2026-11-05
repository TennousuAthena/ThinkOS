"""Source archive integrity checks, using tiny isolated Git object stores; no network."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / 'archive_ffmpeg_source.py'
SPEC = importlib.util.spec_from_file_location('thinkos_archive_ffmpeg', TOOL)
archive_tool = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = archive_tool
SPEC.loader.exec_module(archive_tool)


class FfmpegSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos-archive-test-')
        self.root = Path(self.temp.name)
        self.epoch = 1766993163
        self.repositories = {}
        records = []
        for name, files in (
            ('media', {'libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh': ('100755', b'fixture only\n')}),
            ('ffmpeg', {'configure': ('100755', b'fixture only\n'), 'COPYING.GPLv3': ('100644', b'fixture only\n')}),
            ('', {'build.sh': ('100755', b'fixture only\n'), 'build.gradle.kts': ('100644', b'fixture only\n'),
                  'gradlew': ('100755', b'fixture only\n'), 'LICENSE': ('100644', b'fixture only\n'),
                  'a file.txt': ('100644', b'path with spaces\n'), 'link': ('120000', b'a file.txt')}),
        ):
            repo = self.root / ((name or 'parent') + '.git')
            self.run_git(repo, 'init', '--bare', str(repo))
            tree = {}
            for path, (mode, content) in files.items():
                oid = self.run_git(repo, 'hash-object', '-w', '--stdin', data=content).strip()
                parts = path.split('/')
                folder = tree
                for part in parts[:-1]:
                    folder = folder.setdefault(part, {})
                folder[parts[-1]] = (mode, 'blob', oid)
            if not name:
                for record in records:
                    tree[record['path']] = ('160000', 'commit', record['commit'])

            def write_tree(entries):
                lines = []
                for key, entry in entries.items():
                    mode, kind, oid = ('040000', 'tree', write_tree(entry)) if isinstance(entry, dict) else entry
                    lines.append(f'{mode} {kind} {oid}\t{key}\0'.encode())
                return self.run_git(repo, 'mktree', '-z', '--missing', data=b''.join(lines)).strip()

            commit = self.run_git(repo, 'commit-tree', write_tree(tree), data=b'isolated fixture\n').strip()
            self.repositories[name] = repo
            records.append({'path': name, 'url': 'https://example.invalid/fixture.git', 'commit': commit})
        self.lock = {'version': 1, 'tag': 'fixture', 'root': 'fixture-source', 'epoch': self.epoch,
                     'repositories': [records[-1], *records[:-1]]}

    def tearDown(self):
        self.temp.cleanup()

    def run_git(self, repo, *args, data=None):
        env = dict(os.environ, GIT_AUTHOR_NAME='Archive fixture', GIT_COMMITTER_NAME='Archive fixture',
                   GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_EMAIL='fixture@example.invalid',
                   GIT_AUTHOR_DATE=f'{self.epoch} +0000', GIT_COMMITTER_DATE=f'{self.epoch} +0000')
        return subprocess.run(['git', '--git-dir', str(repo), *args], input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, env=env, check=True, timeout=10).stdout.decode().strip()

    def test_repeat_is_identical_and_contains_original_bytes_modes_links_and_metadata(self):
        first, second = self.root / 'one.tar.gz', self.root / 'two.tar.gz'
        archive_tool.archive(self.lock, self.repositories, first)
        archive_tool.archive(self.lock, self.repositories, second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(len(archive_tool.source_files(self.lock, self.repositories)), archive_tool.verify_archive(self.lock, self.repositories, first))
        with tarfile.open(first) as source:
            prefix = 'fixture-source/'
            self.assertEqual(b'path with spaces\n', source.extractfile(prefix + 'a file.txt').read())
            self.assertEqual(0o755, source.getmember(prefix + 'build.sh').mode)
            self.assertEqual('a file.txt', source.getmember(prefix + 'link').linkname)
            self.assertEqual(self.lock, json.load(source.extractfile(prefix + 'SOURCE-MANIFEST.json')))
            for member in source:
                self.assertEqual(self.epoch, member.mtime)
                self.assertEqual((0, 0, '', ''), (member.uid, member.gid, member.uname, member.gname))
                self.assertNotIn('.git', Path(member.name).parts)

    def test_wrong_submodule_or_epoch_and_incomplete_source_are_refused(self):
        altered = json.loads(json.dumps(self.lock))
        altered['repositories'][1]['commit'] = altered['repositories'][2]['commit']
        with self.assertRaisesRegex(ValueError, 'Submodule pins'):
            archive_tool.source_files(altered, {**self.repositories, 'media': self.repositories['ffmpeg']})
        with self.assertRaisesRegex(ValueError, 'epoch'):
            archive_tool.archive({**self.lock, 'epoch': 1}, self.repositories, self.root / 'bad.tar.gz')
        files = archive_tool.source_files(self.lock, self.repositories)
        from unittest.mock import patch
        files.pop('ffmpeg/configure')
        with patch.object(archive_tool, 'source_files', return_value=files), self.assertRaisesRegex(ValueError, 'Missing corresponding'):
            archive_tool.archive(self.lock, self.repositories, self.root / 'bad.tar.gz')

    def test_offline_never_fetches_missing_repositories(self):
        with self.assertRaisesRegex(RuntimeError, 'Offline cache'):
            archive_tool.obtain(self.root, self.lock['repositories'][0], True)

    def test_changed_source_in_an_archive_is_refused(self):
        import io
        original, changed = self.root / 'original.tar.gz', self.root / 'changed.tar.gz'
        archive_tool.archive(self.lock, self.repositories, original)
        with tarfile.open(original) as source, tarfile.open(changed, 'w:gz') as output:
            for member in source:
                content = source.extractfile(member).read() if member.isfile() else None
                if member.name.endswith('/build.sh'):
                    content = b'X' + content[1:]
                output.addfile(member, io.BytesIO(content) if content is not None else None)
        with self.assertRaisesRegex(ValueError, 'differs from pinned Git blob'):
            archive_tool.verify_archive(self.lock, self.repositories, changed)
