"""Publication boundary checks against fake CNB / gh endpoints on loopback. No network, no real token, no real key."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


tools = load('release_tools', 'release_tools.py')
publish = load('thinkos_publish', 'publish.py')

TOKEN = 'cnb-test-token'
REPO = 'kiwimoe/ThinkOS'
VERSION = '0.1.31'
COMMIT = 'c' * 40


class Fake:
    """One loopback server: /api (CNB API with bearer auth), /put (pre-signed storage), /web + /gh (anonymous downloads)."""

    def __init__(self, gh_root):
        self.gh_root = Path(gh_root)
        self.releases, self.assets, self.pending, self.events = {}, {}, {}, []
        self.corrupt, self.hostile_verify = set(), False
        self.lock = threading.Lock()
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def body(self):
                return self.rfile.read(int(self.headers.get('Content-Length') or 0))

            def send(self, code, raw=b'', headers=()):
                self.send_response(code)
                for key, value in headers:
                    self.send_header(key, value)
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def authorized(self):
                return self.headers.get('Authorization') == 'Bearer ' + TOKEN

            def do_GET(self):
                path = self.path.split('?')[0]
                if path.startswith('/api/'):
                    if not self.authorized():
                        return self.send(401)
                    prefix = '/api/%s/-/releases/tags/' % REPO
                    if path.startswith(prefix):
                        tag = path[len(prefix):]
                        if tag not in fake.releases:
                            return self.send(404, b'{"errmsg":"Not Found"}')
                        return self.send(200, json.dumps({'id': fake.releases[tag]['id']}).encode())
                    return self.send(404)
                if path.startswith('/web/%s/-/releases/download/' % REPO):
                    tag, name = path.split('/')[-2:]
                    return self.send(302, headers=[('Location', '/blob/%s/%s' % (tag, name))])
                if path.startswith('/blob/'):
                    tag, name = path.split('/')[-2:]
                    release = fake.releases.get(tag)
                    data = fake.assets.get((release['id'], name)) if release else None
                    if data is None:
                        return self.send(404)
                    if (tag, name) in fake.corrupt:
                        data = data[:-1] + bytes([data[-1] ^ 1])
                    wanted = self.headers.get('Range')
                    if wanted:
                        first, last = [int(part) for part in wanted.split('=')[1].split('-')]
                        return self.send(206, data[first:last + 1])
                    return self.send(200, data)
                if path.startswith('/gh/%s/releases/download/' % REPO):
                    tag, name = path.split('/')[-2:]
                    file = fake.gh_root / tag / name
                    return self.send(200, file.read_bytes()) if file.is_file() else self.send(404)
                self.send(404)

            def do_HEAD(self):
                path = self.path.split('?')[0]
                if path.startswith('/web/%s/-/releases/download/' % REPO):
                    tag, name = path.split('/')[-2:]
                    release = fake.releases.get(tag)
                    data = fake.assets.get((release['id'], name)) if release else None
                    return self.send_response(200) or self.send_header('Content-Length', str(len(data or b''))) or self.end_headers()
                self.send(404)

            def do_POST(self):
                path, raw = self.path.split('?')[0], self.body()
                if not self.authorized():
                    fake.events.append(('unauthorized-post', path))
                    return self.send(401)
                if path == '/api/%s/-/releases' % REPO:
                    document = json.loads(raw)
                    release = {'id': str(len(fake.releases) + 1), **document}
                    fake.releases[document['tag_name']] = release
                    fake.events.append(('create', document['tag_name'], document))
                    return self.send(201, json.dumps({'id': release['id']}).encode())
                parts = path.split('/')
                if path.endswith('/asset-upload-url'):
                    document = json.loads(raw)
                    release_id = parts[6]
                    number = str(len(fake.pending) + 1)
                    fake.pending[number] = [release_id, document['asset_name'], document, None]
                    verify = 'http://127.0.0.1:%d/%s/%s/-/releases/%s/asset-upload-confirmation/t%s/%s' % (
                        fake.port, 'web' if fake.hostile_verify else 'api', REPO, release_id, number, document['asset_name'])
                    fake.events.append(('upload-url', document['asset_name'], document))
                    return self.send(201, json.dumps({'upload_url': 'http://127.0.0.1:%d/put/%s' % (fake.port, number),
                                                      'verify_url': verify, 'expires_in_sec': 10}).encode())
                if '/asset-upload-confirmation/' in path:
                    number = parts[-2][1:]
                    release_id, name, _, data = fake.pending[number]
                    fake.assets[(release_id, name)] = data
                    fake.events.append(('confirm', name, self.path.split('?')[1] if '?' in self.path else ''))
                    return self.send(200)
                self.send(404)

            def do_PUT(self):
                raw = self.body()
                if self.headers.get('Authorization'):
                    fake.events.append(('token-leaked-to-storage', self.path))
                number = self.path.rsplit('/', 1)[1]
                fake.pending[number][3] = raw
                fake.events.append(('put', fake.pending[number][1]))
                self.send(200)

            def do_DELETE(self):
                release_id = self.path.rsplit('/', 1)[1]
                for tag, release in list(fake.releases.items()):
                    if release['id'] == release_id:
                        del fake.releases[tag]
                        fake.events.append(('delete', tag))
                return self.send(204)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def api(self):
        return 'http://127.0.0.1:%d/api' % self.port

    @property
    def web(self):
        return 'http://127.0.0.1:%d/web' % self.port

    @property
    def github_web(self):
        return 'http://127.0.0.1:%d/gh' % self.port

    def close(self):
        self.server.shutdown()
        self.server.server_close()


FAKE_GH = '''#!%(python)s
import json, os, shutil, sys
from pathlib import Path
root = Path(os.environ['FAKE_GH_ROOT'])
log = root / 'calls.jsonl'
args = sys.argv[1:]
with log.open('a') as stream:
    stream.write(json.dumps(args) + '\\n')
if args[:2] == ['release', 'view']:
    sys.exit(0 if (root / args[2]).is_dir() else 1)
if args[:2] == ['release', 'create']:
    (root / args[2]).mkdir(parents=True, exist_ok=True)
    sys.exit(0)
if args[:2] == ['release', 'upload']:
    shutil.copy(args[3], root / args[2] / Path(args[3]).name)
    sys.exit(0)
if args[:2] == ['release', 'delete']:
    shutil.rmtree(root / args[2], ignore_errors=True)
    sys.exit(0)
sys.exit(2)
'''


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos publish fixture ')
        self.work = Path(self.temp.name).resolve()
        self.gh_root = self.work / 'gh'
        self.gh_root.mkdir()
        self.fake = Fake(self.gh_root)
        self.addCleanup(self.fake.close)
        self.addCleanup(self.temp.cleanup)
        self.gh = self.work / 'fake-gh'
        self.gh.write_text(FAKE_GH % {'python': sys.executable})
        self.gh.chmod(self.gh.stat().st_mode | stat.S_IXUSR)
        env = patch.dict(os.environ, {'FAKE_GH_ROOT': str(self.gh_root), publish.CNB_TOKEN_ENV: TOKEN})
        env.start()
        self.addCleanup(env.stop)
        for key in ('CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'BUILD_BUILDID'):
            os.environ.pop(key, None)
        self.lines = []

    def cnb(self):
        return publish.CnbSite(REPO, TOKEN, self.fake.api, self.fake.web, sleep=lambda _: None)

    def github(self):
        return publish.GitHubSite(REPO, str(self.gh), self.fake.github_web)

    def prepare(self, sites=('cnb',), channel='beta', published=False, extra=None, suffix=''):
        directory = self.work / ('prepared-' + str(len(list(self.work.glob('prepared-*')))))
        directory.mkdir()
        apk = ('thinkos-%s.apk' % VERSION)
        files = {apk: os.urandom(5000), 'thinkos-source-v%s.tar.gz' % VERSION: os.urandom(3000), 'LICENSE-GPL-3.0.md': b'gpl\n'}
        files.update(extra or {})
        for name, raw in files.items():
            (directory / name).write_bytes(raw)
        bases = {'cnb': '%s/%s/-/releases/download/v%s%s' % (self.fake.web, REPO, VERSION, suffix),
                 'github': '%s/%s/releases/download/v%s%s' % (self.fake.github_web, REPO, VERSION, suffix)}
        catalog = {'schema': 1, 'channel': channel, 'seq': 4, 'published': 1, 'expires': 2,
                   'release': {'version': VERSION, 'code': 1031, 'urls': [bases[site] + '/' + apk for site in sites],
                               'modules': [], 'notes': {'en': 'Fixes.'}}}
        (directory / (channel + '.json')).write_bytes(json.dumps(catalog).encode())
        (directory / (channel + '.json.sig')).write_bytes(b'\x30\x06signat')
        report = {'schema': 1, 'stage': 'prepared', 'source_commit': COMMIT, 'version': VERSION, 'published': published,
                  'attachments': [{'path': name, 'sha256': hashlib.sha256(raw).hexdigest(), 'size': len(raw)} for name, raw in files.items()],
                  'modules': [], 'catalog': {'path': channel + '.json',
                                             'sha256': hashlib.sha256((directory / (channel + '.json')).read_bytes()).hexdigest(), 'seq': 4}}
        (directory / 'release-preparation.json').write_bytes(json.dumps(report).encode())
        names = sorted(path.name for path in directory.iterdir() if path.is_file())
        (directory / 'SHA256SUMS').write_text(''.join(hashlib.sha256((directory / n).read_bytes()).hexdigest() + '  ' + n + '\n' for n in names))
        return directory

    def out(self, line):
        self.lines.append(line)

    def run_publish(self, prepared, sites, **kwargs):
        return publish.publish(publish.Prepared(prepared), sites, out=self.out, sleep=lambda _: None, **kwargs)

    def test_a_dry_run_prints_the_plan_and_sends_nothing(self):
        prepared = self.prepare(('cnb', 'github'))
        self.run_publish(prepared, [self.cnb(), self.github()])
        self.assertEqual(self.fake.events, [])
        self.assertFalse((self.gh_root / 'calls.jsonl').exists())
        self.assertTrue(any('Dry run' in line for line in self.lines))

    def test_b_publish_to_cnb_orders_uploads_and_replaces_the_catalog_last(self):
        prepared = self.prepare()
        done = self.run_publish(prepared, [self.cnb()], execute=True)
        events = self.fake.events
        uploaded = [event[1] for event in events if event[0] == 'confirm']
        self.assertEqual(uploaded[-2:], ['beta.json', 'beta.json.sig'], 'the channel catalog is the last thing replaced')
        self.assertEqual(sorted(uploaded[:-2]), sorted(publish.Prepared(prepared).files))
        self.assertFalse([event for event in events if event[0] in ('token-leaked-to-storage', 'unauthorized-post')])
        for event in events:
            if event[0] == 'upload-url':
                self.assertEqual((event[2]['overwrite'], event[2]['ttl']), (True, 0), 'assets must be permanent and replaceable')
            if event[0] == 'confirm':
                self.assertEqual(event[2], 'ttl=0')
        created = {event[1]: event[2] for event in events if event[0] == 'create'}
        self.assertEqual(created['v' + VERSION]['target_commitish'], COMMIT)
        self.assertTrue(created['ota-beta']['prerelease'])
        self.assertEqual(created['ota-beta']['make_latest'], 'false')
        self.assertEqual(done['cnb'], '%s/%s/-/releases/download/ota-beta/beta.json' % (self.fake.web, REPO))
        # A second run is idempotent: existing releases are reused and assets overwritten.
        self.run_publish(prepared, [self.cnb()], execute=True)
        self.assertEqual(len([e for e in self.fake.events if e[0] == 'create']), 2)

    def test_c_a_corrupt_read_back_stops_before_the_catalog_is_touched(self):
        prepared = self.prepare()
        self.fake.corrupt.add(('v' + VERSION, 'thinkos-%s.apk' % VERSION))
        with self.assertRaises(publish.PublishError):
            self.run_publish(prepared, [self.cnb()], execute=True)
        self.assertNotIn('ota-beta', self.fake.releases, 'no device may be pointed at an undownloadable release')
        self.assertNotIn('beta.json', [event[1] for event in self.fake.events if event[0] == 'confirm'])

    def test_c2_very_large_assets_are_checked_by_length_and_both_ends(self):
        with patch.object(publish, 'LARGE', 2000), patch.object(publish, 'SAMPLE', 500):
            prepared = self.prepare()
            self.run_publish(prepared, [self.cnb()], execute=True)  # 5000-byte APK and 3000-byte source archive are now "large"
            self.assertIn('ota-beta', self.fake.releases)
            self.fake.corrupt.add(('v' + VERSION, 'thinkos-%s.apk' % VERSION))  # flips the last byte: the tail sample must catch it
            with self.assertRaises(publish.PublishError):
                self.run_publish(self.prepare(), [self.cnb()], execute=True)

    def test_d_the_token_is_never_sent_to_a_confirmation_url_outside_the_api(self):
        prepared = self.prepare()
        self.fake.hostile_verify = True
        with self.assertRaises(publish.PublishError):
            self.run_publish(prepared, [self.cnb()], execute=True)
        self.assertFalse([event for event in self.fake.events if event[0] == 'confirm'])
        self.assertFalse([event for event in self.fake.events if event[0] == 'token-leaked-to-storage'])

    def test_e_the_signed_catalog_must_list_every_site_that_is_published_to(self):
        prepared = self.prepare(('cnb',))
        with self.assertRaises(publish.PublishError) as caught:
            self.run_publish(prepared, [self.cnb(), self.github()], execute=True)
        self.assertIn('--url-base', str(caught.exception))
        self.assertEqual(self.fake.events, [])

    def test_e2_a_rehearsal_may_use_a_prepared_release_that_names_the_real_tags(self):
        prepared = self.prepare(('cnb',))
        self.run_publish(prepared, [self.cnb(), self.github()], suffix='-rehearsal', execute=False)
        self.assertEqual(self.fake.events, [])

    def test_f_files_must_match_sha256sums_and_unpublished_state(self):
        prepared = self.prepare()
        (prepared / 'LICENSE-GPL-3.0.md').write_bytes(b'tampered\n')
        with self.assertRaises(publish.PublishError):
            publish.Prepared(prepared)
        with self.assertRaises(publish.PublishError):
            publish.Prepared(self.prepare(published=True))
        stranger = self.prepare()
        (stranger / 'extra.bin').write_bytes(b'x')
        publish.Prepared(stranger)  # unlisted extras are ignored, never uploaded
        self.assertNotIn('extra.bin', publish.Prepared(stranger).files)

    def test_g_github_uses_gh_with_the_version_tag_owned_by_the_owner(self):
        prepared = self.prepare(('github',))
        self.run_publish(prepared, [self.github()], execute=True, target='main')
        calls = [json.loads(line) for line in (self.gh_root / 'calls.jsonl').read_text().splitlines()]
        creates = {call[2]: call for call in calls if call[:2] == ['release', 'create']}
        self.assertIn('--verify-tag', creates['v' + VERSION])
        self.assertNotIn('--target', creates['v' + VERSION])
        self.assertIn('--target', creates['ota-beta'])
        self.assertIn('--prerelease', creates['ota-beta'])
        uploads = [call for call in calls if call[:2] == ['release', 'upload']]
        self.assertTrue(all('--clobber' in call for call in uploads))
        self.assertEqual([Path(call[3]).name for call in uploads][-2:], ['beta.json', 'beta.json.sig'])

    def test_h_cleanup_only_removes_rehearsal_releases(self):
        with self.assertRaises(publish.PublishError):
            publish.cleanup(publish.Prepared(self.prepare()), [self.cnb()], '', out=self.out)
        self.fake.releases.update({'v%s-t1' % VERSION: {'id': '90'}, 'ota-beta-t1': {'id': '91'}, 'v' + VERSION: {'id': '92'}})
        publish.cleanup(publish.Prepared(self.prepare(suffix='-t1')), [self.cnb()], '-t1', out=self.out)
        self.assertEqual(sorted(self.fake.releases), ['v' + VERSION])

    def test_i_cli_needs_a_token_for_execution_and_refuses_ci(self):
        prepared = self.prepare()
        base = [str(prepared), '--site', 'cnb', '--cnb-api', self.fake.api, '--cnb-web', self.fake.web]
        self.assertEqual(publish.main(base, out=self.out), 0)
        with patch.dict(os.environ):
            os.environ.pop(publish.CNB_TOKEN_ENV)
            with self.assertRaises(publish.PublishError):
                publish.main(base + ['--execute', '--yes'], out=self.out)
        with patch.dict(os.environ, {'CI': 'true'}):
            with self.assertRaises(ValueError):
                publish.main(base + ['--execute', '--yes'], out=self.out)
        with self.assertRaises(publish.PublishError):
            publish.main(base + ['--tag-suffix', 'bad suffix'], out=self.out)

    def test_j_urls_with_credentials_or_plain_http_are_refused(self):
        for url in ('http://example.com/x', 'https://user:pw@example.com/x', 'ftp://example.com/x'):
            with self.assertRaises(publish.PublishError):
                publish.https_or_loopback(url)
        self.assertEqual(publish.https_or_loopback('http://127.0.0.1:8/x'), 'http://127.0.0.1:8/x')


if __name__ == '__main__':
    unittest.main()
