"""REG fixture asset boundary tests; generated test keys are disposable, never owner keys."""
import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
spec = importlib.util.spec_from_file_location('thinkos_registry_fixture_assets', ROOT / 'tools/registry_fixture_assets.py')
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


@unittest.skipUnless(shutil.which('openssl'), 'OpenSSL required for real disposable P-256 fixtures')
class RegistryFixtureAssetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.material = tempfile.TemporaryDirectory(prefix='thinkos-regfixture-disposable-keys-')
        private = Path(cls.material.name).resolve()
        cls.run_id = '1234567890abcdef1234567890abcdef'
        cls.document = {'schema': 1}
        for role in assets.ROLES:
            cls.document[role] = {}
            for slot in assets.SLOTS:
                key = private / (role + '-' + slot + '.pem')
                subprocess.run(['openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', str(key)],
                               check=True, capture_output=True, timeout=20)
                os.chmod(key, 0o600)
                public = subprocess.run(['openssl', 'pkey', '-in', str(key), '-pubout', '-outform', 'DER'],
                                        check=True, capture_output=True, timeout=20).stdout
                cls.document[role][slot] = {'id': 'reg2b-' + cls.run_id + '-' + role + '-' + slot,
                    'algorithm': 'SHA256withECDSA', 'spki': base64.b64encode(public).decode('ascii')}

    @classmethod
    def tearDownClass(cls):
        cls.material.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='thinkos-regfixture-assets-test-')
        self.work = Path(self.temporary.name).resolve()
        self.repo = self.work / 'source'; self.repo.mkdir()
        self.public = self.work / 'public.json'
        self.raw = (json.dumps(self.document, ensure_ascii=False, indent=3) + '\n\n').encode()
        self.public.write_bytes(self.raw)
        self.patch = patch.object(assets, 'ROOT', self.repo)
        self.patch.start()
        self.output = self.repo / assets.ASSETS

    def tearDown(self):
        self.patch.stop(); self.temporary.cleanup()

    def changed(self):
        return json.loads(self.raw)

    def test_cli_copies_exact_public_bytes_only_to_the_fixture_assets_and_is_repeatable(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, assets.main(['--public', str(self.public), '--output', str(self.output)]))
        report = json.loads(output.getvalue())
        self.assertEqual(self.run_id, report['run_id'])
        self.assertNotIn('spki', output.getvalue())
        self.assertEqual(self.raw, (self.output / 'update/keys.json').read_bytes())
        self.assertEqual(report, assets.generate(self.public, self.output))
        self.assertFalse((self.repo / 'android/app/src/main/assets/update/keys.json').exists())

    def test_json_shape_duplicate_boolean_and_noninteger_schema_are_refused(self):
        for raw in (b'{"schema":1,"schema":1}', b'{"schema":true}', b'{"schema":1.0}', b'{"schema":1e0}', b'[]', b'-----BEGIN PRIVATE KEY-----'):
            with self.subTest(raw=raw), self.assertRaises(ValueError): assets.validate_public(raw)
        value = self.changed(); value['extra'] = 'not allowed'
        with self.assertRaises(ValueError): assets.validate_public(json.dumps(value).encode())

    def test_role_slot_runid_and_four_key_distinctness_are_bound(self):
        for mutation in ('run', 'slot', 'duplicate', 'algorithm'):
            value = self.changed()
            if mutation == 'run': value['registry']['backup']['id'] = 'reg2b-' + 'f' * 32 + '-registry-backup'
            if mutation == 'slot': value['registry']['backup']['id'] = 'reg2b-' + self.run_id + '-registry-active'
            if mutation == 'duplicate': value['registry']['backup']['spki'] = value['channel']['active']['spki']
            if mutation == 'algorithm': value['registry']['backup']['algorithm'] = 'RSA'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): assets.validate_public(json.dumps(value).encode())

    def test_an_spki_prefix_with_a_noncurve_point_is_not_a_p256_key(self):
        value = self.changed()
        # Real DER header, but x=y=0 is not a P-256 point.
        raw = base64.b64decode(value['channel']['active']['spki'])
        value['channel']['active']['spki'] = base64.b64encode(raw[:-64] + bytes(64)).decode()
        with self.assertRaises(ValueError): assets.validate_public(json.dumps(value).encode())

    def test_input_bounds_regular_file_and_all_symlink_segments_are_checked_before_output(self):
        link = self.work / 'link.json'; link.symlink_to(self.public)
        parent = self.work / 'linked'; parent.symlink_to(self.work, target_is_directory=True)
        large = self.work / 'large.json'; large.write_bytes(b' ' * (assets.LIMIT + 1))
        fifo = self.work / 'pipe.json'; os.mkfifo(fifo)
        for candidate in (link, parent / 'public.json', large, fifo, Path('relative.json')):
            with self.subTest(candidate=candidate), self.assertRaises((ValueError, OSError)):
                assets.generate(candidate, self.output)
            self.assertFalse(self.output.exists())
        inside = self.repo / 'public.json'; inside.write_bytes(self.raw)
        with self.assertRaises(ValueError): assets.generate(inside, self.output)

    def test_main_debug_and_release_trust_assets_cannot_contain_fixture_roots(self):
        for source_set in ('main', 'debug', 'release'):
            for filename in ('keys.json', 'debug-keys.json', 'system-keys.json'):
                path = self.repo / 'android/app/src' / source_set / 'assets/update' / filename
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(self.raw)
                with self.subTest(source_set=source_set, filename=filename), self.assertRaises(ValueError):
                    assets.check_production_assets()
                path.unlink()
        self.assertFalse(self.output.exists())

    def test_check_only_preserves_empty_existing_assets_and_never_creates_output(self):
        path = self.repo / 'android/app/src/main/assets/update/keys.json'
        path.parent.mkdir(parents=True); path.write_bytes(b'')
        self.assertEqual(0, assets.main(['--check-production']))
        self.assertEqual(b'', path.read_bytes()); self.assertFalse(self.output.exists())

    def test_output_cannot_be_redirected_to_a_production_or_symlink_directory(self):
        for output in (self.repo / 'android/app/src/main/assets', self.work / 'other'):
            with self.assertRaises(ValueError): assets.generate(self.public, output)
            self.assertFalse(output.exists())
        self.output.parent.mkdir(parents=True)
        self.output.symlink_to(self.work, target_is_directory=True)
        with self.assertRaises(ValueError): assets.generate(self.public, self.output)


if __name__ == '__main__':
    unittest.main()
