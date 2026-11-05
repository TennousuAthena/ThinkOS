"""Public export boundary checks on disposable git repositories. No network, no real key, no personal identity."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


load('release_tools', 'release_tools.py')
export = load('thinkos_export_public', 'export_public.py')
GIT_ENV = {**os.environ, 'GIT_AUTHOR_NAME': 'Dev', 'GIT_AUTHOR_EMAIL': 'dev@private.example', 'GIT_COMMITTER_NAME': 'Dev',
           'GIT_COMMITTER_EMAIL': 'dev@private.example'}


def git(cwd, *args):
    return subprocess.run(['git', '-c', 'commit.gpgsign=false', *args], cwd=cwd, env=GIT_ENV, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode()


class GitIgnoreTest(unittest.TestCase):
    def ignored(self, path):
        return subprocess.run(['git', 'check-ignore', '-q', path], cwd=ROOT).returncode == 0

    def test_bundled_public_roots_and_owner_key_assets_are_committable_but_private_material_is_not(self):
        # A clean checkout without roots.pem builds an APK whose update transport cannot start (no trust anchors).
        self.assertFalse(self.ignored('android/app/src/main/resources/moe/kiwi/thinkos/update/roots.pem'))
        self.assertFalse(self.ignored('android/app/src/main/assets/update/keys.json'))
        self.assertFalse(self.ignored('android/app/src/main/assets/update/system-keys.json'))
        for secret in ('android/keystore.properties', 'private/apk-signing.p12', 'private/channel-active.key.pem',
                       'somewhere/other.pem', 'a.key', 'a.jks'):
            self.assertTrue(self.ignored(secret), secret)


class ExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos export fixture ')
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name).resolve()
        self.dev = self.work / 'dev'
        self.dev.mkdir()
        git(self.dev, 'init', '-q', '-b', 'main')
        self.lines = []

    def commit(self, files):
        for name, content in files.items():
            path = self.dev / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        git(self.dev, 'add', '-A')
        git(self.dev, 'commit', '-q', '-m', 'dev commit by a private person')

    def run_export(self, output=None, **kwargs):
        return export.export('HEAD', output or self.work / 'public', 'ThinkOS', 'noreply@users.noreply.example',
                             'Source for v1', out=self.lines.append, source=self.dev, **kwargs)

    def tree(self, output):
        return sorted(git(output, 'ls-files').splitlines())

    def test_only_source_and_the_needed_artifacts_are_exported_under_the_given_identity(self):
        self.commit({'README.md': 'hello\n', 'android/app/Main.kt': 'class Main\n',
                     'artifacts/certs/CERT-1/source/ca.txt': 'x\n', 'artifacts/certs/CERT-0/device-store-2026-11-03.tsv': 'x\n',
                     'artifacts/app/APP-9/shot.png': 'png\n', 'artifacts/update/OTA-3/log.txt': 'log\n',
                     'android/local.properties': 'sdk.dir=x\n', 'android/app/build/out.txt': 'x\n'})
        head = self.run_export()
        self.assertTrue(head)
        self.assertEqual(self.tree(self.work / 'public'), ['README.md', 'android/app/Main.kt',
                                                           'artifacts/certs/CERT-0/device-store-2026-11-03.tsv', 'artifacts/certs/CERT-1/source/ca.txt'])
        self.assertEqual(git(self.work / 'public', 'log', '--format=%an <%ae> | %cn <%ce> | %s').strip(),
                         'ThinkOS <noreply@users.noreply.example> | ThinkOS <noreply@users.noreply.example> | Source for v1')
        self.assertNotIn('private', git(self.work / 'public', 'log', '--format=%B%an%ae'), 'no development history may leak')

    def test_personal_paths_and_private_keys_block_while_fixture_addresses_only_advise(self):
        self.commit({'docs/a.md': 'cd /Users' + '/someone/Work\n', 'docs/b.md': 'host 192.168.1.8 mail a@b.org\n'})
        with self.assertRaises(ValueError):
            self.run_export()
        self.assertFalse((self.work / 'public').exists(), 'nothing is written when blocked')
        self.assertTrue(any('BLOCKING docs/a.md:1' in line for line in self.lines))
        self.commit({'docs/a.md': 'cd <repository>\n', 'tools/key.txt': '-----BEGIN PRIVATE KEY-----\nplaceholder\n'})
        self.run_export()  # a fixture that merely names the header, with no key body, is not key material
        self.commit({'tools/key.txt': '-----BEGIN PRIVATE KEY-----\n' + 'A' * 64 + '\n-----END PRIVATE KEY-----\n'})
        with self.assertRaises(ValueError):
            self.run_export(self.work / 'public2')
        self.run_export(self.work / 'public2', allow_findings=True)

    def test_later_exports_append_and_unchanged_exports_do_nothing(self):
        self.commit({'a.txt': '1\n'})
        first = self.run_export()
        self.assertIsNone(self.run_export())
        self.commit({'a.txt': '2\n', 'b.txt': 'new\n'})
        second = self.run_export()
        self.assertNotEqual(first, second)
        self.assertEqual(git(self.work / 'public', 'rev-list', '--count', 'HEAD').strip(), '2')
        (self.dev / 'b.txt').unlink()
        git(self.dev, 'add', '-A'); git(self.dev, 'commit', '-q', '-m', 'remove')
        self.run_export()
        self.assertEqual(self.tree(self.work / 'public'), ['a.txt'], 'files removed in development disappear from the public tree')

    def test_unsafe_outputs_and_identities_are_refused(self):
        self.commit({'a.txt': '1\n'})
        with self.assertRaises(ValueError):
            self.run_export(self.dev / 'public')
        busy = self.work / 'busy'
        busy.mkdir()
        (busy / 'note').write_text('x')
        with self.assertRaises(ValueError):
            self.run_export(busy)
        with self.assertRaises(ValueError):
            export.export('HEAD', self.work / 'p', '', 'x', 'm', source=self.dev)
        self.run_export()
        (self.work / 'public' / 'stray.txt').write_text('uncommitted')
        with self.assertRaises(ValueError):
            self.run_export()


if __name__ == '__main__':
    unittest.main()
