"""LAN preparation fixtures: real disposable OpenSSL/PKCS12/APK signatures.

No device/network/module execution; the test APK is a tiny hasCode=false fixture
and its ARM ELF header is data that is never loaded or installed. These are not
the owner's formal keys or an OTA-5 device acceptance run.
"""
import argparse
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import warnings
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ota_lan
import release
import release_tools as common


class LanAddressTest(unittest.TestCase):
    def test_only_literal_rfc1918_and_ula_http_roots_without_url_escapes(self):
        for value in ('http://10.1.2.3:8765/ota/run1', 'http://172.16.0.1/lan',
                      'http://192.168.1.20/', 'http://[fd12:3456::20]:8765/ota'):
            self.assertEqual(value.rstrip('/'), ota_lan.lan_root(value))
        for value in ('https://192.168.1.20', 'http://example.com/ota', 'http://localhost', 'http://127.0.0.1',
                      'http://8.8.8.8', 'http://100.64.0.1', 'http://169.254.1.1', 'http://0.0.0.0',
                      'http://[::1]', 'http://[fe80::1]', 'http://[::ffff:192.168.1.20]',
                      'http://user:pass@192.168.1.20', 'http://192.168.1.20/?token=x',
                      'http://192.168.1.20/#fragment', 'http://192.168.1.20/a/../b',
                      'http://192.168.1.20/%2e%2e', 'http://192.168.1.20/a//b',
                      'http://192.168.1.20/a\\b', 'http://192.168.1.20:0', 'http://192.168.1.20:99999'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ota_lan.lan_root(value)


    def test_sdk_rows_accept_exact_old_or_new_aapt_min_field_and_refuse_ambiguous_substrings(self):
        for minimum in ("sdkVersion:'27'", "minSdkVersion:'27'"):
            self.assertEqual((27, 27), release.apk_sdk_versions(minimum + "\ntargetSdkVersion:'27'\n"))
        for value in ("label=sdkVersion:'27'\ntargetSdkVersion:'27'", "sdkVersion:'27' extra\ntargetSdkVersion:'27'",
                      "sdkVersion:'27'\nminSdkVersion:'27'\ntargetSdkVersion:'27'", "sdkVersion:'27'\nnot_targetSdkVersion:'27'"):
            with self.subTest(value=value), self.assertRaises(ValueError): release.apk_sdk_versions(value)
        self.assertEqual((26, 28), release.apk_sdk_versions("minSdkVersion:'26'\ntargetSdkVersion:'28'"))


    def test_regular_source_replaced_by_fifo_before_open_is_refused_without_blocking(self):
        with tempfile.TemporaryDirectory(prefix='thinkos LAN FIFO race ') as name:
            directory = Path(name).resolve()
            source = directory / 'artifact'
            source.write_bytes(b'fixture')
            descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            real_stat = os.stat
            changed = False
            def replacing_stat(path, *args, **kwargs):
                nonlocal changed
                value = real_stat(path, *args, **kwargs)
                if path == 'artifact' and kwargs.get('dir_fd') == descriptor and not changed:
                    changed = True
                    source.unlink()
                    os.mkfifo(source, 0o600)
                return value
            try:
                with patch.object(ota_lan.os, 'stat', side_effect=replacing_stat):
                    with self.assertRaises(ValueError):
                        ota_lan._copy_open(descriptor, 'artifact', directory / 'snapshot', 1024)
                self.assertTrue(changed)
                self.assertFalse((directory / 'snapshot').exists())
            finally:
                os.close(descriptor)


class OtaLanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.material = tempfile.TemporaryDirectory(prefix='thinkos disposable real LAN signatures ')
        cls.material_root = Path(cls.material.name).resolve()
        cls.password = 'disposable LAN fixture passphrase'
        cls.environment = common.secret_environment(cls.password)
        java = subprocess.run(['/usr/libexec/java_home', '-v', '17'], check=True, capture_output=True, text=True).stdout.strip()
        cls.environment.update(JAVA_HOME=java, PATH=str(Path(java) / 'bin') + os.pathsep + cls.environment['PATH'])
        cls.keytool = Path(java) / 'bin/keytool'
        cls.tools = release.sdk_tools()
        cls.aapt2 = Path(cls.tools[0]).parent / 'aapt2'
        cls.zipalign = Path(cls.tools[0]).parent / 'zipalign'
        compile_sdk = re.search(r'compileSdk = ([0-9]+)', (ROOT / 'android/app/build.gradle.kts').read_text())[1]
        cls.android_jar = Path(cls.tools[0]).parents[2] / 'platforms' / ('android-' + compile_sdk) / 'android.jar'
        for path in (cls.aapt2, cls.zipalign, cls.android_jar):
            if not path.is_file():
                raise RuntimeError('Local Android fixture tool unavailable; do not download a substitute')
        cls.private = cls.material_root / 'private'
        cls.private.mkdir(mode=0o700)
        cls.public = cls.material_root / 'public'; cls.public.mkdir()
        cls.fixture_source = cls.material_root / 'source'
        cls.assets = cls.fixture_source / 'android/app/src/main/assets/update'; cls.assets.mkdir(parents=True)
        pairs = {}
        for role in ('channel', 'registry', 'system'):
            pairs[role] = {}
            for slot in ('active', 'backup'):
                path = cls.private / (role + '-' + slot + '.key.pem')
                cls.run_fixture(['openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256',
                                 '-aes-256-cbc', '-pass', 'env:' + common.PRIVATE_PASSWORD_ENV, '-out', path])
                path.chmod(0o600)
                raw = cls.run_fixture(['openssl', 'pkey', '-in', path, '-passin', 'env:' + common.PRIVATE_PASSWORD_ENV,
                                       '-pubout', '-outform', 'DER'])
                pairs[role][slot] = common.public_key('fixture-' + role + '-' + slot, raw)
        cls.keys = {'schema': 1, 'channel': pairs['channel'], 'registry': pairs['registry']}
        cls.system = {'schema': 1, **pairs['system']}
        for filename, raw in (('keys.json', common.json_bytes(cls.keys)), ('system-keys.json', common.json_bytes(cls.system))):
            (cls.public / filename).write_bytes(raw); (cls.assets / filename).write_bytes(raw)
        (cls.public / 'updater_keys.h').write_bytes(common.system_header(pairs['system']))
        cls.keystore = cls.private / 'apk-signing.p12'
        cls.other_keystore = cls.private / 'other-apk-signing.p12'
        for path, common_name in ((cls.keystore, 'ThinkOS Disposable LAN Fixture'), (cls.other_keystore, 'Other Disposable Fixture')):
            cls.run_fixture([cls.keytool, '-genkeypair', '-storetype', 'PKCS12', '-keystore', path, '-alias', 'thinkos',
                             '-keyalg', 'EC', '-groupname', 'secp256r1', '-validity', '2', '-dname', 'CN=' + common_name,
                             '-storepass:env', common.PRIVATE_PASSWORD_ENV, '-keypass:env', common.PRIVATE_PASSWORD_ENV, '-noprompt'])
            path.chmod(0o600)
        certificate = cls.run_fixture([cls.keytool, '-exportcert', '-storetype', 'PKCS12', '-keystore', cls.keystore,
                                       '-alias', 'thinkos', '-storepass:env', common.PRIVATE_PASSWORD_ENV])
        cls.signer = hashlib.sha256(certificate).hexdigest()
        (cls.public / 'apk-certificate.der').write_bytes(certificate)
        (cls.public / 'key-inventory.json').write_bytes(common.json_bytes({'schema': 1, 'apk': {'alias': 'thinkos', 'sha256': cls.signer}}))
        cls.base_apk = cls.make_apk(cls.material_root / 'base-apk')

    @classmethod
    def tearDownClass(cls):
        cls.environment.pop(common.PRIVATE_PASSWORD_ENV, None)
        cls.material.cleanup()

    @classmethod
    def run_fixture(cls, command):
        # All secrets remain in the fixture environment, never command text/logs.
        result = subprocess.run([str(item) for item in command], check=True, capture_output=True,
                                env=cls.environment, timeout=60, stdin=subprocess.DEVNULL)
        return result.stdout

    @classmethod
    def make_apk(cls, work, *, package='moe.kiwi.thinkos', debuggable=False, sdk=27, abi='armeabi-v7a',
                 bad_elf=False, keystore=None, changed_roots=False, version='0.2.0', code=2000,
                 include_native=True, include_roots=True):
        work.mkdir()
        manifest = work / 'AndroidManifest.xml'
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="' + package + '" android:versionCode="' + str(code) + '" android:versionName="' + version + '">'
                            '<uses-sdk android:minSdkVersion="27" android:targetSdkVersion="' + str(sdk) + '"/>'
                            '<application android:hasCode="false" android:debuggable="' + str(debuggable).lower() + '"/>'
                            '</manifest>')
        base = work / 'base.apk'
        cls.run_fixture([cls.aapt2, 'link', '--manifest', manifest, '-I', cls.android_jar,
                         '--min-sdk-version', '27', '--target-sdk-version', str(sdk), '-o', base])
        unsigned = work / 'unsigned.apk'
        with zipfile.ZipFile(base) as source, zipfile.ZipFile(unsigned, 'w') as target:
            for info in source.infolist():
                target.writestr(info, source.read(info))
            for filename in (('keys.json', 'system-keys.json') if include_roots else ()):
                raw = (cls.public / filename).read_bytes()
                if changed_roots and filename == 'keys.json': raw += b'\n'
                target.writestr('assets/update/' + filename, raw)
            ident = b'\x7fELF' + bytes([2 if bad_elf else 1, 1, 1]) + bytes(9)
            # Header-only ARM32 data fixture: no native code and never executed.
            elf = struct.pack('<16sHHIIIIIHHHHHH', ident, 3, 183 if bad_elf else 40, 1, 0, 0, 0, 0, 52, 0, 0, 0, 0, 0)
            if include_native: target.writestr('lib/' + abi + '/libfixture.so', elf)
        aligned, signed = work / 'aligned.apk', work / 'signed.apk'
        cls.run_fixture([cls.zipalign, '-f', '4', unsigned, aligned])
        cls.run_fixture([cls.tools[1], 'sign', '--ks', keystore or cls.keystore, '--ks-key-alias', 'thinkos',
                         '--ks-pass', 'env:' + common.PRIVATE_PASSWORD_ENV, '--key-pass', 'env:' + common.PRIVATE_PASSWORD_ENV,
                         '--v4-signing-enabled', 'false', '--out', signed, aligned])
        cls.run_fixture([cls.tools[1], 'verify', '--verbose', '--print-certs', signed])
        return signed

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos signed LAN release input ')
        self.work = Path(self.temp.name).resolve()
        self.source = self.work / 'release'; self.source.mkdir()
        self.apk = self.source / 'thinkos-0.2.0.apk'; shutil.copyfile(self.base_apk, self.apk)
        self.module_name = 'thinkos-cast-v1.0.zip'
        with zipfile.ZipFile(self.source / self.module_name, 'w') as archive:
            archive.writestr('module.prop', 'id=thinkos-cast\nversion=v1.0\nversionCode=1\n')
            archive.writestr('service.sh', '#!/system/bin/sh\n# Fixture only. Never run.\n')
        module_path = self.source / self.module_name
        self.module = {'id': 'thinkos-cast', 'kind': 'magisk', 'version': 'v1.0', 'code': 1, 'policy': 'feature',
                       'sha256': common.digest(module_path), 'size': module_path.stat().st_size,
                       'urls': ['https://example.invalid/release/' + self.module_name],
                       'manifest_urls': ['https://example.invalid/release/thinkos-cast-1.manifest']}
        manifest = self.source / 'thinkos-cast-1.manifest'
        manifest.write_bytes(release.module_manifest('thinkos-cast', 'magisk', 1, 'v1.0', self.module['sha256'], self.module['size']))
        signature = release.sign(manifest, self.private / 'system-active.key.pem', self.system['active'], self.environment)
        (self.source / 'thinkos-cast-1.manifest.sig').write_bytes(signature)
        now = int(time.time() * 1000)
        self.catalog = {'schema': 1, 'channel': 'beta', 'seq': 1, 'published': now, 'expires': now + 86400000,
                        'release': {'version': '0.2.0', 'code': 2000, 'api': 10, 'device': ['starfire'], 'sdk': 27,
                                    'sha256': common.digest(self.apk), 'size': self.apk.stat().st_size, 'signer': self.signer,
                                    'urls': ['https://example.invalid/release/' + self.apk.name], 'modules': [copy.deepcopy(self.module)],
                                    'notes': {'en': 'Disposable signed LAN fixture'},
                                    'source': 'https://example.invalid/ThinkOS/tree/v0.2.0'}}
        self.owner_environment = patch.dict(os.environ, {'CI': '', 'GITHUB_ACTIONS': '', 'GITLAB_CI': '', 'BUILD_BUILDID': '',
                                                        'JAVA_HOME': self.environment['JAVA_HOME'], 'PATH': self.environment['PATH']})
        self.owner_environment.start(); self.addCleanup(self.owner_environment.stop)
        self.seal()

    def tearDown(self): self.temp.cleanup()

    def seal(self, *, role='channel'):
        catalog_path = self.source / 'beta.json'; catalog_path.write_bytes(common.json_bytes(self.catalog))
        pair = self.keys['channel'] if role == 'channel' else self.system
        (self.source / 'beta.json.sig').write_bytes(release.sign(catalog_path, self.private / (role + '-active.key.pem'),
                                                               pair['active'], self.environment))
        trust = release.public_trust(self.public, root=self.fixture_source)
        report = {'schema': 1, 'stage': 'prepared', 'source_commit': '1' * 40, 'version': '0.2.0', 'published': False,
                  'device_smoke': {'requested': False, 'complete': False},
                  'public_trust': {name: hashlib.sha256(raw).hexdigest() for name, raw in trust['files'].items()},
                  'catalog': {'path': 'beta.json', 'sha256': common.digest(catalog_path), 'seq': self.catalog['seq']},
                  'modules': copy.deepcopy(self.catalog['release']['modules']),
                  'attachments': [{'path': self.apk.name, 'sha256': common.digest(self.apk), 'size': self.apk.stat().st_size}],
                  'source_tag': 'v0.2.0', 'qualification': 'Disposable test fixture; never installed or published'}
        (self.source / 'release-preparation.json').write_bytes(common.json_bytes(report))
        (self.source / 'preparation-started.json').write_text('{"stage":"preparing"}\n')
        (self.source / 'check-1.log').write_text('Fixture-only public check output\n')
        (self.source / 'OWNER-PUBLISH.md').write_text('Fixture metadata; must not enter LAN root.\n')
        self.checksums()

    def checksums(self):
        files = sorted(path for path in self.source.iterdir() if path.name not in ('SHA256SUMS', 'release-preparation.json'))
        rows = [common.digest(path) + '  ' + path.name + '\n' for path in files if path.is_file()]
        rows.append(common.digest(self.source / 'release-preparation.json') + '  release-preparation.json\n')
        (self.source / 'SHA256SUMS').write_text(''.join(rows))

    def args(self, **changes):
        value = argparse.Namespace(release_dir=self.source, public_dir=self.public, output=self.work / 'lan',
                                   lan_root='http://192.168.1.20:8765/ota/run1', seq=2, initial=True, previous_catalog=None,
                                   private_key=self.private / 'channel-active.key.pem', slot='active', password_fd=None,
                                   reviewed_sha256=None, execute=False)
        for key, item in changes.items(): setattr(value, key, item)
        return value

    def prepare(self, args=None):
        return ota_lan.prepare(args or self.args(), source_root=self.fixture_source, tools=self.tools)

    def execute(self, args=None):
        args = args or self.args()
        plan = self.prepare(args)
        args.reviewed_sha256 = plan['input_sha256']; args.execute = True
        with patch.object(ota_lan, 'read_password', return_value=self.password):
            return self.prepare(args)

    def vector(self, **changes):
        values = dict(package='moe.kiwi.thinkos.immersive', version='1.2', code=3,
                      include_native=False, include_roots=False)
        values.update(changes)
        work = self.work / ('vector-fixture-' + str(len(list(self.work.glob('vector-fixture-*')))))
        apk = self.make_apk(work, **values)
        name = 'thinkos-immersive-1.2.apk'
        target = self.source / name; shutil.copyfile(apk, target)
        module = {'id': 'thinkos-immersive', 'kind': 'vector', 'version': '1.2', 'code': 3, 'policy': 'always',
                  'sha256': common.digest(target), 'size': target.stat().st_size,
                  'urls': ['https://example.invalid/release/' + name],
                  'manifest_urls': ['https://example.invalid/release/thinkos-immersive-3.manifest']}
        manifest = self.source / 'thinkos-immersive-3.manifest'
        manifest.write_bytes(release.module_manifest(module['id'], module['kind'], module['code'], module['version'],
                                                    module['sha256'], module['size']))
        (self.source / (manifest.name + '.sig')).write_bytes(release.sign(manifest, self.private / 'system-active.key.pem',
                                                                       self.system['active'], self.environment))
        self.catalog['release']['modules'] = [module]
        self.seal()
        return name

    def test_actual_vector_apk_and_system_manifest_are_preserved_with_a_new_channel_signature(self):
        name = self.vector()
        args = self.args(); result = self.execute(args)
        self.assertIn(name, result['files']); self.assertNotIn(self.module_name, result['files'])
        for filename in (name, 'thinkos-immersive-3.manifest', 'thinkos-immersive-3.manifest.sig'):
            self.assertEqual((self.source / filename).read_bytes(), (args.output / filename).read_bytes())
        release.verify(args.output / 'thinkos-immersive-3.manifest',
                       (args.output / 'thinkos-immersive-3.manifest.sig').read_bytes(),
                       [self.system['active'], self.system['backup']])

    def test_signed_vector_wrong_package_version_code_debug_sdk_signer_or_native_abi_are_rejected(self):
        cases = [{'package': 'moe.kiwi.thinkos.wrong'}, {'version': '1.3'}, {'code': 4}, {'debuggable': True},
                 {'sdk': 28}, {'keystore': self.other_keystore}, {'include_native': True, 'abi': 'arm64-v8a'}]
        for change in cases:
            with self.subTest(change=change):
                self.vector(**change)
                with self.assertRaises(ValueError): self.prepare()

    def test_original_apk_swap_during_real_aapt_cannot_mix_identity_and_digest(self):
        wrong = self.make_apk(self.work / 'swap-wrong-apk', package='moe.kiwi.thinkos.wrong')
        shutil.copyfile(wrong, self.apk)
        self.catalog['release'].update(sha256=common.digest(self.apk), size=self.apk.stat().st_size)
        self.seal()
        invalid_original = self.apk.read_bytes()
        real_run = release.run
        inspected = []
        def swapping_run(command, **kwargs):
            argv = [str(item) for item in command]
            if Path(argv[0]).name == 'aapt' and argv[1:3] == ['dump', 'badging']:
                inspected.append(Path(argv[3]))
                # On the old path-based implementation this supplied benign
                # badging, then restored the invalid bytes before digest/copy.
                # Snapshot validation must keep reading the original invalid APK.
                shutil.copyfile(self.base_apk, self.apk)
                try: return real_run(command, **kwargs)
                finally: self.apk.write_bytes(invalid_original)
            return real_run(command, **kwargs)
        with patch.object(release, 'run', side_effect=swapping_run):
            with self.assertRaises(ValueError): self.prepare()
        self.assertTrue(inspected)
        self.assertTrue(all(path != self.apk and path.parent != self.source for path in inspected))
        self.assertEqual(invalid_original, self.apk.read_bytes())

    def test_plan_uses_actual_signed_apk_without_opening_key_or_creating_output(self):
        args = self.args(private_key=self.work / 'missing-formal-private-key')
        with patch.object(ota_lan, 'private_file', side_effect=AssertionError('plan opened private material')), \
                patch.object(ota_lan, 'read_password', side_effect=AssertionError('plan requested a password')):
            result = self.prepare(args)
        self.assertEqual('planned', result['stage']); self.assertFalse(result['executed'])
        self.assertFalse(result['published']); self.assertFalse(result['device_changed'])
        self.assertFalse(args.output.exists())

    def test_execute_real_channel_signature_only_changes_urls_seq_and_copies_exact_whitelist(self):
        args = self.args(); result = self.execute(args)
        self.assertEqual('prepared', result['stage']); self.assertTrue(result['executed'])
        self.assertEqual(set(result['files']), {path.name for path in args.output.iterdir()})
        self.assertEqual({'beta.json', 'beta.json.sig', self.apk.name, self.module_name,
                          'thinkos-cast-1.manifest', 'thinkos-cast-1.manifest.sig'}, set(result['files']))
        release.verify(args.output / 'beta.json', (args.output / 'beta.json.sig').read_bytes(), list(self.keys['channel'].values()))
        rebased = release.document(args.output / 'beta.json')
        original = copy.deepcopy(self.catalog)
        self.assertEqual(2, rebased['seq'])
        self.assertEqual([args.lan_root + '/' + self.apk.name], rebased['release']['urls'])
        self.assertEqual([args.lan_root + '/' + self.module_name], rebased['release']['modules'][0]['urls'])
        rebased['seq'] = original['seq']; rebased['release']['urls'] = original['release']['urls']
        rebased['release']['modules'][0]['urls'] = original['release']['modules'][0]['urls']
        rebased['release']['modules'][0]['manifest_urls'] = original['release']['modules'][0]['manifest_urls']
        self.assertEqual(original, rebased)
        for name in (self.apk.name, self.module_name, 'thinkos-cast-1.manifest', 'thinkos-cast-1.manifest.sig'):
            self.assertEqual((self.source / name).read_bytes(), (args.output / name).read_bytes())
        self.assertEqual(self.catalog, release.document(self.source / 'beta.json'))

    def test_complete_cli_plan_then_execute_performs_real_verification_and_signing(self):
        argv = ['--release-dir', str(self.source), '--public-dir', str(self.public), '--output', str(self.work / 'cli-lan'),
                '--lan-root', 'http://192.168.1.20:8765/ota/cli', '--seq', '2', '--initial',
                '--private-key', str(self.private / 'channel-active.key.pem')]
        with patch.object(ota_lan, 'ROOT', self.fixture_source):
            output = io.StringIO()
            with contextlib.redirect_stdout(output): self.assertEqual(0, ota_lan.main(argv))
            plan = json.loads(output.getvalue())
            output = io.StringIO()
            with patch.object(ota_lan, 'read_password', return_value=self.password), contextlib.redirect_stdout(output):
                self.assertEqual(0, ota_lan.main(argv + ['--reviewed-sha256', plan['input_sha256'], '--execute']))
        result = json.loads(output.getvalue()); self.assertTrue(result['executed'])
        self.assertNotIn(self.password, output.getvalue())
        release.verify(self.work / 'cli-lan/beta.json', (self.work / 'cli-lan/beta.json.sig').read_bytes(), list(self.keys['channel'].values()))

    def test_old_seq_and_authenticated_previous_seq_are_not_reused(self):
        with self.assertRaises(ValueError): self.prepare(self.args(seq=1))
        first = self.execute()
        previous = self.work / 'lan/beta.json'
        for seq in (1, 2):
            with self.assertRaises(ValueError):
                self.prepare(self.args(output=self.work / 'next', initial=False, previous_catalog=previous, seq=seq))
        self.assertEqual(3, self.prepare(self.args(output=self.work / 'next', initial=False, previous_catalog=previous, seq=3))['seq'])
        self.assertEqual(2, first['seq'])
        previous.write_bytes(previous.read_bytes() + b'\n')
        with self.assertRaises(ValueError):
            self.prepare(self.args(output=self.work / 'next', initial=False, previous_catalog=previous, seq=3))

    def test_origin_channel_and_system_manifest_roles_cannot_be_interchanged(self):
        self.seal(role='system')
        with self.assertRaises(ValueError): self.prepare()
        self.seal()
        manifest = self.source / 'thinkos-cast-1.manifest'
        (self.source / 'thinkos-cast-1.manifest.sig').write_bytes(release.sign(manifest, self.private / 'channel-active.key.pem',
                                                                          self.keys['channel']['active'], self.environment))
        self.checksums()
        with self.assertRaises(ValueError): self.prepare()

    def test_expired_catalog_and_changed_apk_tuple_at_same_version_are_refused(self):
        now = int(time.time() * 1000)
        self.catalog['published'], self.catalog['expires'] = now - 2000, now - 1000
        self.seal()
        with self.assertRaises(ValueError): self.prepare()
        self.catalog['published'], self.catalog['expires'] = now, now + 86400000
        self.seal(); self.execute()
        previous = self.work / 'lan/beta.json'
        self.catalog['release']['sha256'] = '0' * 64
        self.seal()
        with self.assertRaises(ValueError):
            self.prepare(self.args(output=self.work / 'same-version-replaced', initial=False,
                                   previous_catalog=previous, seq=3))

    def test_signed_but_wrong_actual_apk_package_debug_sdk_abi_elf_roots_and_signer_are_rejected(self):
        cases = [{'package': 'moe.kiwi.thinkos.wrong'}, {'debuggable': True}, {'sdk': 28}, {'abi': 'arm64-v8a'},
                 {'bad_elf': True}, {'changed_roots': True}, {'keystore': self.other_keystore}]
        for index, change in enumerate(cases):
            with self.subTest(change=change):
                changed = self.make_apk(self.work / ('wrong-apk-' + str(index)), **change)
                shutil.copyfile(changed, self.apk)
                self.catalog['release'].update(sha256=common.digest(self.apk), size=self.apk.stat().st_size)
                self.seal()  # Valid role signature and checksums; rejection must be semantic.
                with self.assertRaises(ValueError): self.prepare()

    def test_modified_input_plan_or_missing_execute_review_never_produces_catalog(self):
        args = self.args(); plan = self.prepare(args)
        args.execute = True
        with self.assertRaises(ValueError): self.prepare(args)
        args.reviewed_sha256 = plan['input_sha256']
        (self.source / 'check-1.log').write_text('Changed after owner review\n'); self.checksums()
        with self.assertRaises(ValueError): self.prepare(args)
        self.assertFalse(args.output.exists())

    def test_manifest_and_payload_tuple_tampering_are_refused_even_when_input_sums_are_updated(self):
        manifest = self.source / 'thinkos-cast-1.manifest'
        manifest.write_bytes(manifest.read_bytes().replace(b'code=1\n', b'code=2\n'))
        (self.source / 'thinkos-cast-1.manifest.sig').write_bytes(release.sign(manifest, self.private / 'system-active.key.pem',
                                                                          self.system['active'], self.environment))
        self.checksums()
        with self.assertRaises(ValueError): self.prepare()

    def test_symlinks_traversal_uncovered_files_and_nonflat_input_are_rejected(self):
        for kind in ('symlink', 'traversal', 'uncovered', 'directory'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.work) as name:
                target = Path(name) / 'release'; shutil.copytree(self.source, target)
                if kind == 'symlink':
                    (target / self.apk.name).unlink(); (target / self.apk.name).symlink_to(self.apk)
                elif kind == 'traversal':
                    with (target / 'SHA256SUMS').open('a') as stream: stream.write('0' * 64 + '  ../outside\n')
                elif kind == 'uncovered': (target / 'extra.log').write_text('unlisted')
                else: (target / 'device-smoke').mkdir()
                with self.assertRaises(ValueError): self.prepare(self.args(release_dir=target))

    def test_wrong_signing_role_or_plaintext_private_key_never_leaves_a_catalog(self):
        plaintext = self.work / 'plaintext.key.pem'; plaintext.write_text('-----BEGIN PRIVATE KEY-----\nfixture only\n'); plaintext.chmod(0o600)
        for index, key in enumerate((self.private / 'system-active.key.pem', plaintext)):
            with self.subTest(key=key.name):
                args = self.args(private_key=key, output=self.work / ('wrong-key-' + str(index)))
                with self.assertRaises(ValueError): self.execute(args)
                self.assertFalse((args.output / 'beta.json').exists())

    def test_backup_channel_slot_signs_with_its_exact_independent_key(self):
        args = self.args(slot='backup', private_key=self.private / 'channel-backup.key.pem')
        result = self.execute(args)
        self.assertEqual(self.keys['channel']['backup']['id'], result['key_id'])
        release.verify(args.output / 'beta.json', (args.output / 'beta.json.sig').read_bytes(), [self.keys['channel']['backup']])

    def test_ci_refuses_before_sdk_keys_or_output_and_existing_output_is_never_overwritten(self):
        with patch.dict(os.environ, {'CI': 'true'}), patch.object(ota_lan, '_trusted_public', side_effect=AssertionError('CI read trust')):
            with self.assertRaises(ValueError): self.prepare()
        args = self.args(); args.output.mkdir(); (args.output / 'preserve').write_text('unchanged')
        with self.assertRaises(ValueError): self.prepare(args)
        self.assertEqual('unchanged', (args.output / 'preserve').read_text())

    def test_zip_duplicate_links_and_expanded_size_are_bounded_before_sdk_inspection(self):
        for kind in ('duplicate', 'link', 'expanded'):
            path = self.work / (kind + '.zip')
            with warnings.catch_warnings(), zipfile.ZipFile(path, 'w') as archive:
                warnings.simplefilter('ignore', UserWarning)
                archive.writestr('file', b'fixture')
                if kind == 'duplicate': archive.writestr('file', b'other')
                if kind == 'link':
                    info = zipfile.ZipInfo('link'); info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, b'outside')
            with self.subTest(kind=kind), patch.object(ota_lan, 'MAX_ZIP_EXPANDED', 1 if kind == 'expanded' else 256 * 1024 * 1024):
                with self.assertRaises(ValueError): ota_lan._archive(path)


if __name__ == '__main__': unittest.main()
