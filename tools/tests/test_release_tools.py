"""Owner release boundary checks. All keys created by a test are disposable temporary fixtures."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
import warnings
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


common = load('release_tools', 'release_tools.py')
keys = load('thinkos_owner_keygen', 'gen_keys.py')
release = load('thinkos_owner_release', 'release.py')


class CatalogMirrorsTest(unittest.TestCase):
    def test_signed_mirror_list_is_https_per_channel_distinct_and_bounded(self):
        good = 'https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/ota-stable/stable.json'
        self.assertEqual(release.catalog_urls([good, 'https://github.com/o/r/releases/download/ota-stable/stable.json'], 'stable'),
                         [good, 'https://github.com/o/r/releases/download/ota-stable/stable.json'])
        self.assertEqual(release.catalog_urls(None, 'beta'), [])
        for bad in ('https://x.example/ota-beta/beta.json', 'http://x.example/stable.json', 'https://u:pw@x.example/stable.json',
                    'https://x.example/stable.json?token=1', 'https://x.example/stable.json#f', 'https://x.example/'):
            with self.assertRaises(ValueError, msg=bad):
                release.catalog_urls([bad], 'stable')
        with self.assertRaises(ValueError):
            release.catalog_urls([good, good], 'stable')
        with self.assertRaises(ValueError):
            release.catalog_urls(['https://m%d.example/stable.json' % i for i in range(9)], 'stable')
        document = {'schema': 1, 'channel': 'stable', 'seq': 1, 'published': 1, 'expires': 2, 'mirrors': [good],
                    'release': {'version': '0.1.31', 'code': 1031, 'api': 10, 'device': ['starfire'], 'sdk': 27, 'sha256': 'a' * 64,
                                'size': 10, 'signer': 'b' * 64, 'urls': [good.replace('stable.json', 'x.apk')], 'modules': [],
                                'notes': {'en': 'x'}, 'source': 'https://cnb.cool/kiwimoe/ThinkOS/-/tree/v0.1.31'}}
        self.assertEqual(release.validate_catalog(document)['mirrors'], [good])


class ReleaseToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos disposable release fixture ')
        self.work = Path(self.temp.name).resolve()

    def tearDown(self):
        self.temp.cleanup()

    def public(self, number):
        # Public-format fixture for parser/header tests, not an elliptic-curve test key.
        return common.public_key('test-' + str(number), common.P256_PREFIX + bytes([number]) * 64)

    def test_version_rule_and_integer_boundaries(self):
        self.assertEqual(1006, common.version_code('0.1.6'))
        self.assertEqual(2147483647, common.version_code('2147.483.647'))
        for value in ('0.0.0', '01.2.3', '1.1000.0', '2147.483.648', '9999.0.0', '1.0', '1.0.0-beta'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                common.version_code(value)

    def test_private_paths_refuse_repository_symlinks_overwrite_and_public_permissions(self):
        with self.assertRaises(ValueError):
            common.outside_repository(ROOT / 'test-private-keyring')
        private = common.create_directory(self.work / 'private', private=True)
        self.assertEqual(0o700, private.stat().st_mode & 0o777)
        with self.assertRaises(FileExistsError):
            common.create_directory(private, private=True)
        os.chmod(private, 0o755)
        with self.assertRaises(ValueError):
            common.private_directory(private)
        os.chmod(private, 0o700)
        link = self.work / 'link'; link.symlink_to(private, target_is_directory=True)
        with self.assertRaises(ValueError):
            common.no_symlink_path(link / 'secret')
        path = private / 'fixture'; path.write_bytes(b'fixture'); os.chmod(path, 0o644)
        with self.assertRaises(ValueError):
            common.private_file(path)
        os.chmod(path, 0o600); self.assertEqual(path, common.private_file(path))

    def test_public_header_is_exact_xy_with_two_distinct_roots(self):
        pair = {'active': self.public(1), 'backup': self.public(2)}
        header = common.system_header(pair)
        self.assertIn(b'#define UP_SYSTEM_KEY_COUNT 2\n', header)
        self.assertIn(b'up_system_keys[][64]', header)
        self.assertEqual(128, header.count(b'0x'))
        self.assertEqual(bytes([1]) * 64, common.p256_xy(pair['active']['spki']))
        pair['backup'] = pair['active']
        with self.assertRaises(ValueError):
            common.system_header(pair)
        for raw in (b'invalid', common.P256_PREFIX + bytes(63), bytes(91)):
            with self.assertRaises(ValueError):
                common.p256_xy(base64.b64encode(raw).decode())

    def test_apk_roles_cannot_reuse_a_key_or_identifier(self):
        document = {'schema': 1,
                    'channel': {'active': self.public(1), 'backup': self.public(2)},
                    'registry': {'active': self.public(3), 'backup': self.public(4)}}
        common.validate_keyring(document)
        document['registry']['backup'] = document['channel']['active']
        with self.assertRaises(ValueError):
            common.validate_keyring(document)

    def test_key_generation_commands_use_environment_and_write_only_public_exports(self):
        private, public = self.work / 'private', self.work / 'public'
        secret = 'disposable-fixture-passphrase'
        commands = []
        counter = 0
        def fake(command, **kwargs):
            nonlocal counter
            commands.append([str(item) for item in command])
            self.assertNotIn(secret, ' '.join(commands[-1]))
            self.assertEqual(secret, kwargs['env'][common.PRIVATE_PASSWORD_ENV])
            if command[1] == 'genpkey':
                self.assertIn('-aes-256-cbc', command)
                header = b'-----BEGIN ENCRYPTED '
                header += b'PRIVATE KEY-----\n'
                Path(command[command.index('-out') + 1]).write_bytes(header + b'encrypted fixture placeholder')
            elif command[1] == 'pkey':
                counter += 1
                return common.P256_PREFIX + bytes([counter]) * 64
            elif command[1] == '-genkeypair':
                self.assertIn('-storepass:env', command)
                Path(command[command.index('-keystore') + 1]).write_bytes(b'PKCS12 fixture placeholder')
            elif command[1] == '-exportcert':
                return b'public certificate fixture'
            return b''
        with patch.object(keys, 'run', side_effect=fake):
            result = keys.generate(private, public, secret, backups=[self.work/'cold-a', self.work/'cold-b'])
        self.assertEqual(6, counter)
        self.assertEqual(7, len(list(private.iterdir())))
        self.assertEqual(2, result['offline_backups_created'])
        for path in private.iterdir():
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(path.read_bytes(), (self.work/'cold-a'/path.name).read_bytes())
            self.assertEqual(path.read_bytes(), (self.work/'cold-b'/path.name).read_bytes())
        self.assertEqual(hashlib.sha256(b'public certificate fixture').hexdigest(), result['apk']['sha256'])
        for path in public.iterdir():
            self.assertNotIn(secret.encode(), path.read_bytes())
            self.assertNotIn(b'encrypted fixture placeholder', path.read_bytes())
        with patch.object(keys, 'run') as tool, self.assertRaises(ValueError):
            keys.generate(private, public, secret)
        tool.assert_not_called()

    def test_signing_is_refused_in_ci_and_child_errors_never_echo_secrets(self):
        with patch.dict(os.environ, {'CI': 'true'}), self.assertRaises(ValueError):
            common.require_owner_host()
        process = subprocess.CompletedProcess(['openssl'], 1, b'', b'disposable secret from child')
        with patch.object(common.subprocess, 'run', return_value=process), self.assertRaises(RuntimeError) as caught:
            common.run(['openssl', 'example'])
        self.assertNotIn('secret', str(caught.exception))
        self.assertEqual('Host tool failed: openssl', str(caught.exception))

    def test_password_descriptor_is_private_bounded_and_never_a_command_argument(self):
        path = self.work / 'passphrase'; path.write_text('disposable-fixture-passphrase\n'); os.chmod(path, 0o600)
        with path.open('rb') as stream:
            self.assertEqual('disposable-fixture-passphrase', common.read_password(stream.fileno()))
        os.chmod(path, 0o644)
        with path.open('rb') as stream, self.assertRaises(ValueError):
            common.read_password(stream.fileno())
        for fd in (0, 1, 2):
            with self.assertRaises(ValueError):
                common.read_password(fd)

    def test_module_manifest_matches_the_daemon_seven_line_protocol(self):
        expected = ('thinkos-module 1\nid=thinkos-updater\nkind=magisk\ncode=1\nversion=v1.0\n'
                    'sha256=' + 'a' * 64 + '\nsize=123\n').encode()
        self.assertEqual(expected, release.module_manifest('thinkos-updater', 'magisk', 1, 'v1.0', 'a' * 64, 123))
        for values in (('unknown', 'magisk', 1, 'v1', 'a' * 64, 1),
                       ('thinkos-updater', 'vector', 1, 'v1', 'a' * 64, 1),
                       ('thinkos-updater', 'magisk', 0, 'v1', 'a' * 64, 1),
                       ('thinkos-updater', 'magisk', 1, 'v1\nsize=2', 'a' * 64, 1),
                       ('thinkos-updater', 'magisk', 1, 'v1', 'A' * 64, 1),
                       ('thinkos-updater', 'magisk', 1, 'v1', 'a' * 64, 16777217)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                release.module_manifest(*values)

    def test_module_selection_cannot_smuggle_a_command_or_path(self):
        path = self.work / 'modules.json'
        for value in ([{'id': 'timesync', 'policy': 'always'}], [{'id': 'thinkos-certs', 'policy': 'installed', 'mode': 'replace'}]):
            path.write_text(json.dumps(value)); self.assertEqual(value, release.module_requests(path))
        for value in ([{'policy': 'always', 'mode': 'replace'}], [{'id': 'timesync', 'policy': 'always', 'command': 'anything'}],
                      [{'id': '../system', 'policy': 'always'}], [{'id': 'timesync', 'policy': 'always', 'mode': 'replace'}],
                      [{'id': 'timesync', 'policy': 'always'}] * 2):
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                release.module_requests(path)

    def test_distribution_urls_reject_credentials_cleartext_controls_and_ports(self):
        self.assertEqual('https://example.invalid/releases', common.distribution_base('https://example.invalid/releases/'))
        for value in ('http://example.invalid', 'https://user:secret@example.invalid', 'https://example.invalid/?token=x',
                      'https://example.invalid/#fragment', 'https://example.invalid/\npath', 'https://example.invalid:99999'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                common.distribution_base(value)

    def test_json_documents_refuse_duplicates_noninteger_numbers_and_limits(self):
        path = self.work / 'document.json'
        for raw in ('{"seq":1,"seq":2}', '{"seq":1e1}', '{"seq":NaN}'):
            path.write_text(raw)
            with self.assertRaises(ValueError):
                release.document(path)
        path.write_text('{"seq":1}')
        self.assertEqual({'seq': 1}, release.document(path))
        with self.assertRaises(ValueError):
            release.document(path, limit=1)

    def test_cli_exposes_only_local_prepare_not_an_implicit_publish_action(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            release.parser().parse_args(['publish'])

    def repository(self):
        repository = self.work / 'isolated-source'
        repository.mkdir()
        def git(*args):
            return subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                                   '-c', 'init.templateDir=', '-c', 'user.name=ThinkOS test fixture', '-c', 'user.email=fixture@example.invalid',
                                   *args], cwd=repository, check=True, capture_output=True).stdout
        git('init', '--quiet')
        (repository / 'source.txt').write_text('corresponding fixture source\n')
        git('add', 'source.txt'); git('commit', '--quiet', '-m', 'isolated fixture')
        return repository, git

    def test_clean_gate_and_tracked_secret_scan_use_the_actual_git_tree(self):
        repository, git = self.repository()
        self.assertEqual(git('rev-parse', 'HEAD').decode().strip(), release.check_clean(repository))
        pending = repository / 'untracked.txt'; pending.write_text('unreviewed')
        with self.assertRaises(ValueError):
            release.check_clean(repository)
        pending.unlink()
        marker = b'-----BEGIN ENCRYPTED '
        marker += b'PRIVATE KEY-----\nnot a key, fixture delimiter only\n'
        (repository / 'fixture.pem').write_bytes(marker)
        git('add', 'fixture.pem'); git('commit', '--quiet', '-m', 'isolated invalid fixture')
        with self.assertRaises(ValueError):
            release.scan_tracked_secrets(repository)

    def test_corresponding_source_is_exact_commit_and_excludes_untracked_material(self):
        repository, git = self.repository()
        commit = git('rev-parse', 'HEAD').decode().strip()
        (repository / 'owner-only.txt').write_text('must not be archived')
        first, second = self.work / 'out-a', self.work / 'out-b'; first.mkdir(); second.mkdir()
        with patch.object(release, 'ROOT', repository):
            a = release.source_archive(first, '0.1.6', commit)
            b = release.source_archive(second, '0.1.6', commit)
        self.assertEqual(a, b)
        self.assertEqual((first/a['path']).read_bytes(), (second/b['path']).read_bytes())
        with tarfile.open(first/a['path'], 'r:gz') as archive:
            self.assertIn('thinkos-v0.1.6/source.txt', archive.getnames())
            self.assertFalse(any(name.endswith('/owner-only.txt') for name in archive.getnames()))

    def test_corresponding_source_leaves_out_tracked_validation_evidence(self):
        repository, git = self.repository()
        (repository / 'artifacts/ui').mkdir(parents=True)
        (repository / 'artifacts/ui/shot.png').write_bytes(b'x' * 4096)
        (repository / 'artifacts-notes.md').write_text('a source file whose name only starts like the folder')
        git('add', '-A'); git('commit', '--quiet', '-m', 'evidence beside source')
        commit = git('rev-parse', 'HEAD').decode().strip()
        out = self.work / 'out-evidence'; out.mkdir()
        with patch.object(release, 'ROOT', repository):
            archive = release.source_archive(out, '0.1.6', commit)
        with tarfile.open(out / archive['path'], 'r:gz') as opened:
            names = opened.getnames()
        self.assertIn('thinkos-v0.1.6/source.txt', names)
        self.assertIn('thinkos-v0.1.6/artifacts-notes.md', names)
        self.assertFalse(any('/artifacts/' in name for name in names))

    def test_gpl_attachment_hashes_and_complete_build_sources_are_required(self):
        root = self.work / 'gpl-fixture'; (root/'tools').mkdir(parents=True)
        (root/'tools/ffmpeg-source.lock.json').write_text(json.dumps({'root':'fixture-ffmpeg-source'}))
        license_bytes = b'GPL fixture license\n'
        (root/'LICENSE-GPL-3.0.md').write_bytes(license_bytes)
        attachments = root/'attachments'; attachments.mkdir()
        archive = attachments/'fixture-ffmpeg-source.tar.gz'
        with tarfile.open(archive, 'w:gz') as target:
            for filename in ('fixture/ffmpeg/configure','fixture/media/libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh'):
                info=tarfile.TarInfo(filename); info.size=1; target.addfile(info,io.BytesIO(b'x'))
        archive_digest = common.digest(archive)
        (attachments/'LICENSE-GPL-3.0.md').write_bytes(license_bytes)
        (attachments/(archive.name+'.sha256')).write_text(archive_digest+'  '+archive.name+'\n')
        manifest = attachments/'release-attachments.json'
        manifest.write_text(json.dumps([{'path':archive.name,'sha256':archive_digest},
                                       {'path':archive.name+'.sha256'},
                                       {'path':'LICENSE-GPL-3.0.md','sha256':hashlib.sha256(license_bytes).hexdigest()}]))
        output = root/'prepared'; output.mkdir()
        with patch.object(release, 'ROOT', root):
            self.assertEqual(3,len(release.gpl_attachments(manifest,output)))
            (attachments/(archive.name+'.sha256')).write_text('0'*64+'  '+archive.name+'\n')
            second = root/'rejected'; second.mkdir()
            with self.assertRaises(ValueError):
                release.gpl_attachments(manifest,second)

    def test_public_artifacts_refuse_private_material_and_traversal(self):
        marker = b'-----BEGIN ENCRYPTED '
        marker += b'PRIVATE KEY-----\nfixture only\n'
        for filename, raw in (('assets/signing.p12',b'fixture'), ('assets/config.pem',marker), ('../outside',b'fixture')):
            archive_path = self.work/'invalid.zip'
            with zipfile.ZipFile(archive_path,'w') as archive:
                archive.writestr(filename,raw)
            with zipfile.ZipFile(archive_path) as archive, self.assertRaises(ValueError):
                release.public_archive(archive)
        with zipfile.ZipFile(self.work/'public.zip','w') as archive:
            archive.writestr('assets/roots.pem',b'public CA certificate fixture')
        with zipfile.ZipFile(self.work/'public.zip') as archive:
            release.public_archive(archive)

    def test_plaintext_signing_material_is_refused_before_invoking_openssl(self):
        path = self.work/'plain-fixture.pem'; path.write_bytes(b'unencrypted fixture placeholder'); os.chmod(path,0o600)
        with patch.object(release,'run') as tool, self.assertRaises(ValueError):
            release.sign(self.work/'unsigned-document',path,self.public(1),common.secret_environment('disposable-fixture-passphrase'))
        tool.assert_not_called()

    def trust_fixture(self):
        case = self.work / ('trust-case-' + str(len(list(self.work.iterdir()))))
        public = case / 'owner-public'
        source = case / 'source'
        public.mkdir(parents=True)
        assets = source / 'android/app/src/main/assets/update'
        assets.mkdir(parents=True)
        apk = {'schema': 1, 'channel': {'active': self.public(1), 'backup': self.public(2)},
               'registry': {'active': self.public(3), 'backup': self.public(4)}}
        system = {'schema': 1, 'active': self.public(5), 'backup': self.public(6)}
        self.write_trust(public, source, apk, system)
        return public, source, apk, system

    def write_trust(self, public, source, apk, system):
        for name, value in (('keys.json', apk), ('system-keys.json', system)):
            raw = common.json_bytes(value)
            (public / name).write_bytes(raw)
            (source / 'android/app/src/main/assets/update' / name).write_bytes(raw)
        if set(system) == {'schema', 'active', 'backup'}:
            (public / 'updater_keys.h').write_bytes(common.system_header({slot: system[slot] for slot in ('active', 'backup')}))

    def test_public_trust_reads_flat_system_roots_and_exact_assets_without_writes_or_tools(self):
        public, source, apk, system = self.trust_fixture()
        originals = {path: path.read_bytes() for path in public.iterdir()}
        with patch.object(release, 'run') as tool:
            result = release.public_trust(public, source)
        tool.assert_not_called()
        self.assertEqual(apk, result['keys'])
        self.assertEqual({'active': system['active'], 'backup': system['backup']}, result['system_pair'])
        self.assertEqual({'keys.json', 'system-keys.json', 'updater_keys.h'}, set(result['files']))
        self.assertEqual(originals, {path: path.read_bytes() for path in public.iterdir()})

    def test_public_trust_requires_each_owner_export_and_both_apk_source_assets(self):
        for name in ('keys.json', 'system-keys.json', 'updater_keys.h'):
            public, source, _, _ = self.trust_fixture()
            (public / name).unlink()
            with self.subTest(owner_export=name), self.assertRaises(OSError):
                release.public_trust(public, source)
        for name in ('keys.json', 'system-keys.json'):
            public, source, _, _ = self.trust_fixture()
            (source / 'android/app/src/main/assets/update' / name).unlink()
            with self.subTest(apk_asset=name), self.assertRaises(OSError):
                release.public_trust(public, source)

    def test_public_assets_must_be_byte_identical_without_automatic_repair(self):
        for name in ('keys.json', 'system-keys.json'):
            public, source, _, _ = self.trust_fixture()
            asset = source / 'android/app/src/main/assets/update' / name
            edited = asset.read_bytes().rstrip() + b'\n\n'
            asset.write_bytes(edited)
            with patch.object(release, 'run') as tool, self.assertRaises(ValueError):
                release.public_trust(public, source)
            tool.assert_not_called(); self.assertEqual(edited, asset.read_bytes())

    def test_variant_assets_cannot_override_reviewed_main_roots(self):
        for variant in ('debug', 'release'):
            public, source, _, _ = self.trust_fixture()
            asset = source / 'android/app/src' / variant / 'assets/update/system-keys.json'
            asset.parent.mkdir(parents=True)
            asset.write_bytes((public / 'system-keys.json').read_bytes())
            release.public_trust(public, source)
            asset.write_bytes((public / 'keys.json').read_bytes())
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                release.public_trust(public, source)

    def test_system_document_cannot_use_nested_legacy_bool_schema_or_extra_roots(self):
        for mode in ('nested', 'boolean', 'extra'):
            public, source, apk, system = self.trust_fixture()
            if mode == 'nested': system = {'schema': 1, 'system': {slot: system[slot] for slot in ('active', 'backup')}}
            elif mode == 'boolean': system['schema'] = True
            else: system['channel'] = apk['channel']
            self.write_trust(public, source, apk, system)
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                release.public_trust(public, source)

    def test_all_six_roles_refuse_cross_role_key_and_identifier_reuse(self):
        for field in ('spki', 'id'):
            for role in ('channel', 'registry'):
                public, source, apk, system = self.trust_fixture()
                system['backup'][field] = apk[role]['active'][field]
                self.write_trust(public, source, apk, system)
                with self.subTest(field=field, role=role), self.assertRaises(ValueError):
                    release.public_trust(public, source)
        public, source, apk, system = self.trust_fixture()
        system['backup'] = dict(system['active'])
        # Do not ask system_header to generate a duplicate key; malformed input
        # must be rejected independently by the release preflight.
        raw = common.json_bytes(system)
        (public / 'system-keys.json').write_bytes(raw)
        (source / 'android/app/src/main/assets/update/system-keys.json').write_bytes(raw)
        with self.assertRaises(ValueError):
            release.public_trust(public, source)

    def test_updater_header_requires_exact_reviewed_xy_order_count_and_bytes(self):
        for mode in ('wrong-count', 'swapped', 'extra-byte'):
            public, source, _, system = self.trust_fixture()
            header = public / 'updater_keys.h'
            raw = header.read_bytes()
            if mode == 'wrong-count': raw = raw.replace(b'UP_SYSTEM_KEY_COUNT 2', b'UP_SYSTEM_KEY_COUNT 0')
            elif mode == 'swapped': raw = common.system_header({'active': system['backup'], 'backup': system['active']})
            else: raw += b'\n'
            header.write_bytes(raw)
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                release.public_trust(public, source)

    def test_public_source_paths_refuse_symlinks_and_duplicate_json_members(self):
        public, source, _, _ = self.trust_fixture()
        asset = source / 'android/app/src/main/assets/update/system-keys.json'
        asset.unlink(); asset.symlink_to(public / 'system-keys.json')
        with self.assertRaises(ValueError):
            release.public_trust(public, source)
        public, source, _, _ = self.trust_fixture()
        path = public / 'system-keys.json'
        path.write_bytes(path.read_bytes().replace(b'"schema": 1', b'"schema": 1, "schema": 1'))
        with self.assertRaises(ValueError):
            release.public_trust(public, source)

    def test_bad_public_trust_stops_prepare_before_private_access_build_or_password(self):
        public, source, _, _ = self.trust_fixture()
        (source / 'android/app/src/main/assets/update/system-keys.json').unlink()
        args = type('FixtureArgs', (), {'public_dir': public})()
        with patch.object(release, 'ROOT', source), patch.object(release, 'require_owner_host'), patch.object(release, 'check_clean', return_value='a' * 40), patch.object(release, 'scan_tracked_secrets'), patch.object(release, 'private_directory') as private, patch.object(release, 'read_password') as password, patch.object(release, 'run') as tool, self.assertRaises(OSError):
            release.prepare(args)
        private.assert_not_called(); password.assert_not_called(); tool.assert_not_called()

    def test_public_snapshot_detects_coordinated_asset_and_header_changes(self):
        public, source, apk, system = self.trust_fixture()
        original = release.public_trust(public, source)
        with patch.object(release, 'ROOT', source):
            release.unchanged_public_trust(public, original)
            system['backup'] = self.public(7)
            self.write_trust(public, source, apk, system)
            with self.assertRaises(ValueError):
                release.unchanged_public_trust(public, original)

    def test_built_apk_requires_both_exact_assets_with_no_duplicate_or_fallback(self):
        public, source, _, _ = self.trust_fixture()
        expected = release.public_trust(public, source)
        for mode in ('valid', 'missing-system', 'wrong-system', 'duplicate-system', 'only-debug', 'oversized'):
            path = self.work / ('apk-' + mode + '.zip')
            with warnings.catch_warnings(), zipfile.ZipFile(path, 'w') as archive:
                warnings.simplefilter('ignore', UserWarning)
                if mode == 'only-debug':
                    archive.writestr('assets/update/debug-keys.json', expected['files']['keys.json'])
                else:
                    archive.writestr('assets/update/keys.json', expected['files']['keys.json'])
                    if mode != 'missing-system':
                        raw = expected['files']['system-keys.json']
                        if mode == 'wrong-system': raw = expected['files']['keys.json']
                        if mode == 'oversized': raw = b' ' * (64 * 1024 + 1)
                        archive.writestr('assets/update/system-keys.json', raw)
                        if mode == 'duplicate-system': archive.writestr('assets/update/system-keys.json', raw)
            with self.subTest(mode=mode):
                if mode == 'valid': release.packaged_public_trust(path, expected)
                else:
                    with self.assertRaises(ValueError): release.packaged_public_trust(path, expected)

    def test_raw_module_manifest_maps_vector_and_matches_production_c_limits(self):
        raw = release.module_manifest('thinkos-immersive', 'vector', 2147483647, 'v' * 48, 'a' * 64, 16777216)
        self.assertIn(b'kind=vector-apk\n', raw)
        self.assertEqual(7, raw.count(b'\n')); self.assertTrue(raw.endswith(b'\n'))
        for code, version, size in ((2147483648, 'v', 1), (True, 'v', 1), (1, 'v' * 49, 1),
                                    (1, 'v', 0), (1, 'v', True), (1, 'v', 16777217),
                                    (1, '-v', 1), (1, 'v/1', 1), (1, 'v\n1', 1)):
            with self.subTest(code=code, version=version, size=size), self.assertRaises(ValueError):
                release.module_manifest('thinkos-immersive', 'vector', code, version, 'a' * 64, size)


class ModuleManifestProducerCTest(unittest.TestCase):
    """Production Python producer -> real DER signature -> existing C driver.

    The shared driver creates disposable test-only keys in a TemporaryDirectory;
    no owner keyring, device, build artifact or network service is accessed.
    """
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('thinkos_release_c_fixture', ROOT / 'system/test/test_module_verify.py')
        fixture_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture_module)
        cls.driver_type = fixture_module.ModuleVerifier
        cls.driver_type.setUpClass()

    @classmethod
    def tearDownClass(cls):
        cls.driver_type.tearDownClass()

    def setUp(self):
        self.driver = self.driver_type()
        self.driver.setUp()

    def tearDown(self):
        self.driver.tearDown()

    def test_every_python_id_kind_pair_is_accepted_by_the_real_c_verifier(self):
        for identifier in release.MODULE_BUILDERS:
            kind = 'vector' if identifier == 'thinkos-immersive' else 'magisk'
            raw = release.module_manifest(identifier, kind, 2147483647, 'v' * 48, 'a' * 64, 16777216)
            with self.subTest(identifier=identifier):
                signature = self.driver.signed(raw, key=1)
                result, code, fields = self.driver.verify(raw, signature)
                self.assertEqual((0, 0), (result, code))
                self.assertEqual('vector-apk' if kind == 'vector' else 'magisk', fields['kind'])
                self.assertEqual('v' * 48, fields['version'])
                self.assertEqual('2147483647', fields['code'])
                self.assertEqual('16777216', fields['size'])

    def test_validly_signed_wire_mutations_fail_the_real_c_protocol(self):
        valid = release.module_manifest('thinkos-immersive', 'vector', 2147483647, 'v' * 48, 'a' * 64, 16777216)
        invalid = [valid.replace(b'kind=vector-apk', b'kind=vector'),
                   valid.replace(b'version=' + b'v' * 48, b'version=' + b'v' * 49),
                   valid.replace(b'code=2147483647', b'code=2147483648'),
                   valid.replace(b'size=16777216', b'size=16777217'), valid + b'\n', valid.replace(b'\n', b'\r\n')]
        for raw in invalid:
            with self.subTest(raw=raw):
                self.assertNotEqual(0, self.driver.verify(raw)[0])
        signature = self.driver.signed(valid)
        self.assertEqual(-1, self.driver.verify(valid.replace(b'code=2147483647', b'code=2147483646'), signature)[0])


if __name__ == '__main__':
    unittest.main()
