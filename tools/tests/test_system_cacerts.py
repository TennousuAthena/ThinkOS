"""CA data/provenance and real module installer checks, entirely on the host."""
import base64
import contextlib
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('thinkos_system_cacerts', ROOT/'tools/vendor_system_cacerts.py')
ca = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ca)
SOURCE = ROOT/'artifacts/certs/CERT-1/source'
PIN = '796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe'
AT = datetime(2026, 11, 4, 12, tzinfo=timezone.utc)


def octal(value):
    return ''.join(f'\\{byte:03o}' for byte in value)


def fixture(entries):
    """Small NSS + curl pair from ephemeral test roots, never deployment keys."""
    nss, pems = [], []
    for name, der, trust, distrust in entries:
        nss.append('CKA_CLASS CK_OBJECT_CLASS CKO_CERTIFICATE\nCKA_LABEL UTF8 ' + json.dumps(name)
                   + '\nCKA_VALUE MULTILINE_OCTAL\n' + octal(der) + '\nEND\n'
                   + ('CKA_NSS_SERVER_DISTRUST_AFTER MULTILINE_OCTAL\n' + octal(distrust) + '\nEND\n'
                      if distrust else 'CKA_NSS_SERVER_DISTRUST_AFTER CK_BBOOL CK_FALSE\n'))
        nss.append('CKA_CLASS CK_OBJECT_CLASS CKO_NSS_TRUST\nCKA_CERT_SHA1_HASH MULTILINE_OCTAL\n'
                   + octal(hashlib.sha1(der).digest()) + '\nEND\nCKA_TRUST_SERVER_AUTH CK_TRUST ' + trust + '\n')
        pems.append(name + '\n=======\n' + ca.pem_bytes(der).decode())
    nss = '\n'.join(nss).encode()
    bundle = ('## Certificate data from Mozilla as of: Tue Oct 20 13:32:40 2026 GMT\n## SHA256: '
              + hashlib.sha256(nss).hexdigest() + '\n\n' + '\n'.join(pems)).encode()
    return bundle, nss


class SystemCacertsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = (SOURCE/'cacert-2026-10-20.crt').read_bytes()
        cls.nss = (SOURCE/'certdata.txt').read_bytes()
        cls.files, cls.metadata = ca.render(cls.bundle, cls.nss, PIN, '2026-11-04', AT)
        cls.temp = tempfile.TemporaryDirectory(prefix='thinkos CA host ')
        cls.work = Path(cls.temp.name)
        cls.roots = []
        for index, is_ca in enumerate((True, True, False)):
            key, cert = cls.work/f'key{index}', cls.work/f'cert{index}'
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256',
                            '-nodes', '-keyout', str(key), '-out', str(cert), '-days', '365',
                            '-subj', '/CN=ThinkOS collision fixture', '-set_serial', str(index+1),
                            '-addext', 'basicConstraints=critical,CA:' + ('TRUE' if is_ca else 'FALSE')],
                           check=True, capture_output=True, timeout=15)
            cls.roots.append(base64.b64decode(ca.PEM.fullmatch(cert.read_text().strip())[1].replace('\n', '')))
        cls.fake_at = datetime.now(timezone.utc) + timedelta(minutes=1)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_pinned_data_produces_exact_committed_files_reproducibly(self):
        self.assertEqual(len(self.files), 117)
        self.assertEqual(self.metadata['certificate_count'], 117)
        ca.write_output(ROOT/'system/thinkos-certs', self.files, self.metadata, check=True)
        with tempfile.TemporaryDirectory() as name:
            target = Path(name)
            ca.write_output(target, self.files, self.metadata)
            again = ca.render(self.bundle, self.nss, PIN, '2026-11-04', AT)
            ca.write_output(target, *again, check=True)

    def test_all_certificates_have_native_old_hash_and_verified_ca_self_signature(self):
        fingerprints = set()
        for name, pem in self.files.items():
            der = base64.b64decode(ca.PEM.fullmatch(pem.decode().strip())[1].replace('\n', ''))
            old_hash, _, after = ca.inspect_certificate(der, AT)
            self.assertTrue(name.startswith(old_hash + '.'))
            self.assertNotIn(hashlib.sha256(der).hexdigest(), fingerprints)
            fingerprints.add(hashlib.sha256(der).hexdigest())
            self.assertGreater(datetime.fromisoformat(after), AT)
            self.assertEqual(pem.count(b'-----BEGIN CERTIFICATE-----'), 1)

    def test_all_48_original_common_roots_match_device_old_hash_and_47_are_emitted(self):
        policies = ca.nss_policy(self.nss)
        ders = {hashlib.sha256(o['CKA_VALUE']).hexdigest(): o['CKA_VALUE']
                for o in ca.nss_objects(self.nss) if o['CKA_CLASS'] == 'CKO_CERTIFICATE'}
        emitted = {c['der_sha256']: c for c in self.metadata['certificates']}
        common = [line.split('\t') for line in (ROOT/'artifacts/certs/CERT-0/device-store-2026-11-03.tsv').read_text().splitlines()
                  if not line.startswith('#') and line.endswith('\tyes')]
        self.assertEqual(len(common), 48)
        for name, digest, *_ in common:
            self.assertEqual(ca.inspect_certificate(ders[digest], AT)[0] + '.0', name)
            if policies[digest]['name'] != 'Izenpe.com':
                self.assertEqual(emitted[digest]['file'], name)
        self.assertEqual(sum(c[1] in emitted for c in common), 47)

    def test_distrust_after_and_untrusted_roots_are_excluded_and_reported(self):
        excluded = self.metadata['excluded']
        dates = [c for c in excluded if c['server_distrust_after']]
        self.assertEqual(len(dates), 4)
        self.assertEqual([c['name'] for c in excluded if c['in_bundle']], ['Izenpe.com'])
        self.assertNotIn('Izenpe.com', {c['name'] for c in self.metadata['certificates']})
        entries = [('allowed', self.roots[0], 'CKT_NSS_TRUSTED_DELEGATOR', None),
                   ('distrusted', self.roots[1], 'CKT_NSS_TRUSTED_DELEGATOR', b'260415235959Z')]
        bundle, nss = fixture(entries)
        files, meta = ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)
        self.assertEqual(len(files), 1)
        self.assertEqual(meta['excluded'][0]['name'], 'distrusted')
        bundle, nss = fixture([('untrusted', self.roots[0], 'CKT_NSS_MUST_VERIFY_TRUST', None)])
        self.assertEqual(ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)[0], {})

    def test_required_and_forbidden_root_families(self):
        names = {c['name'] for c in self.metadata['certificates']}
        for name in ('ISRG Root X1', 'ISRG Root X2', 'GTS Root R1', 'GTS Root R3', 'GTS Root R4',
                     'DigiCert Global Root G2', 'DigiCert Global Root G3', 'DigiCert TLS RSA4096 Root G5',
                     'DigiCert TLS ECC P384 Root G5', 'Microsoft RSA Root Certificate Authority 2017',
                     'Microsoft ECC Root Certificate Authority 2017', 'GlobalSign Root CA - R3',
                     'GlobalSign Root R46', 'GlobalSign Root E46', 'Sectigo Public Server Authentication Root R46',
                     'Sectigo Public Server Authentication Root E46', 'USERTrust RSA Certification Authority',
                     'USERTrust ECC Certification Authority', 'CFCA EV ROOT',
                     *(f'Amazon Root CA {n}' for n in range(1, 5)),
                     'TrustAsia Global Root CA G3', 'TrustAsia Global Root CA G4', 'TrustAsia TLS ECC Root CA', 'TrustAsia TLS RSA Root CA'):
            self.assertIn(name, names)
        for family in ('TrustCor', 'Chunghwa', 'Camerfirma', 'E-Tugra', 'VeriSign', 'GeoTrust', 'thawte', 'Izenpe', 'Entrust'):
            self.assertFalse(any(re.search(r'(?i)\b' + re.escape(family), name) for name in names), family)

    def test_rejects_bundle_or_nss_pin_mismatch_and_missing_header(self):
        for bundle, nss, pin in ((self.bundle, self.nss, '0'*64), (self.bundle, self.nss + b'\n', PIN),
                                 (self.bundle.replace(b'## SHA256:', b'## removed:'), self.nss, None)):
            with self.assertRaises(ValueError):
                ca.render(bundle, nss, pin or hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)

    def test_rejects_duplicate_malformed_non_ca_expired_and_forged_signature(self):
        for entries, at in (([('duplicate', self.roots[0], 'CKT_NSS_TRUSTED_DELEGATOR', None)]*2, self.fake_at),
                            ([('not CA', self.roots[2], 'CKT_NSS_TRUSTED_DELEGATOR', None)], self.fake_at),
                            ([('expired', self.roots[0], 'CKT_NSS_TRUSTED_DELEGATOR', None)], self.fake_at + timedelta(days=730)),
                            ([('bad DER', b'not a certificate', 'CKT_NSS_TRUSTED_DELEGATOR', None)], self.fake_at),
                            ([('bad signature', self.roots[0][:-1] + bytes([self.roots[0][-1] ^ 1]), 'CKT_NSS_TRUSTED_DELEGATOR', None)], self.fake_at)):
            with self.subTest(name=entries[0][0]), self.assertRaises(ValueError):
                bundle, nss = fixture(entries)
                ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', at)

    def test_same_subject_collision_suffixes_are_contiguous_and_input_order_independent(self):
        entries = [(str(n), der, 'CKT_NSS_TRUSTED_DELEGATOR', None) for n, der in enumerate(self.roots[:2])]
        bundle, nss = fixture(entries)
        files, _ = ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)
        old_hash = ca.inspect_certificate(self.roots[0], self.fake_at)[0]
        self.assertEqual(set(files), {old_hash+'.0', old_hash+'.1'})
        bundle, nss = fixture(list(reversed(entries)))
        self.assertEqual(ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)[0], files)

    def test_fake_refresh_diff_reports_add_remove_and_expiry(self):
        old_entries = [('old', self.roots[0], 'CKT_NSS_TRUSTED_DELEGATOR', None)]
        new_entries = [('new', self.roots[1], 'CKT_NSS_TRUSTED_DELEGATOR', None)]
        metadata = []
        for entries in (old_entries, new_entries):
            bundle, nss = fixture(entries)
            metadata.append(ca.render(bundle, nss, hashlib.sha256(bundle).hexdigest(), '2026-11-04', self.fake_at)[1])
        diff = ca.differences(*metadata, self.fake_at)
        self.assertEqual(diff['added'], ['new'])
        self.assertEqual(diff['removed'], ['old'])
        self.assertEqual(diff['expiring_within_one_year'], ['new'])
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name)
            (folder/'old.json').write_text(json.dumps(metadata[0]))
            bundle, nss = fixture(new_entries)
            (folder/'bundle.crt').write_bytes(bundle); (folder/'certdata.txt').write_bytes(nss)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                ca.main([str(folder/'bundle.crt'), '--sha256', hashlib.sha256(bundle).hexdigest(),
                         '--retrieved-on', '2026-11-04', '--output', str(folder/'module'),
                         '--diff', str(folder/'old.json')])
            result = json.loads(output.getvalue())
            self.assertEqual(result['added'], ['new']); self.assertEqual(result['removed'], ['old'])

    def test_nss_parser_rejects_duplicates_and_unterminated_octal(self):
        for raw in (b'CKA_CLASS CK_OBJECT_CLASS CKO_CERTIFICATE\nCKA_CLASS UTF8 "again"\nCKA_CLASS UTF8 "again"\n',
                    b'CKA_CLASS CK_OBJECT_CLASS CKO_CERTIFICATE\nCKA_VALUE MULTILINE_OCTAL\n\\300\n',
                    b'CKA_CLASS CK_OBJECT_CLASS CKO_CERTIFICATE\nCKA_VALUE MULTILINE_OCTAL\n\\999\nEND\n'):
            with self.assertRaises((ValueError, KeyError)):
                ca.nss_policy(raw)

    def test_output_refuses_symlinks_and_unowned_files(self):
        with tempfile.TemporaryDirectory() as name:
            target = Path(name)
            ca.write_output(target, self.files, self.metadata)
            folder = target/'system/etc/security/cacerts'
            (folder/'unowned').write_text('keep me')
            with self.assertRaises(ValueError):
                ca.write_output(target, self.files, self.metadata)
            self.assertEqual((folder/'unowned').read_text(), 'keep me')
            (folder/'unowned').unlink()
            entry = folder/next(iter(self.files)); entry.unlink(); entry.symlink_to(target/'no target')
            with self.assertRaises(ValueError):
                ca.write_output(target, self.files, self.metadata)

    def test_both_module_modes_are_reproducible_and_contain_unchanged_licenses(self):
        for mode in ('add', 'replace'):
            command = ['bash', str(ROOT/'system/build-certs.sh'), '--mode', mode]
            subprocess.run(command, check=True, capture_output=True, timeout=30)
            path = ROOT/f'system/build/thinkos-certs-v2026.10.20-{mode}.zip'
            first = path.read_bytes()
            subprocess.run(command, check=True, capture_output=True, timeout=30)
            self.assertEqual(first, path.read_bytes())
            self.assertLess(len(first), 16*1024*1024)
            with zipfile.ZipFile(path) as archive:
                prefix = 'system/etc/security/cacerts/'
                self.assertEqual(prefix+'.replace' in archive.namelist(), mode == 'replace')
                layout = json.loads(archive.read('install-layout.json'))
                self.assertEqual(layout['expected_total_count'], 205 if mode == 'add' else 117)
                self.assertEqual({n.removeprefix(prefix) for n in archive.namelist() if n.startswith(prefix)} - {'.replace'},
                                 {item['file'] for item in layout['files']})
                for item in layout['files']:
                    self.assertEqual(archive.read(prefix+item['file']), self.files[item['source_file']])
                for name in ('LICENSE-MPL-2.0.txt', 'LICENSE-GPL-3.0.md', 'LICENSE'):
                    self.assertEqual(archive.read(name), (ROOT/name).read_bytes())
                for item in archive.infolist():
                    if item.filename.startswith(prefix):
                        self.assertEqual(item.external_attr >> 16 & 0o777, 0o644)
                self.assertFalse(any(n.endswith(('.so', '.rule')) or n in ('service.sh', 'post-fs-data.sh') for n in archive.namelist()))
                with tempfile.TemporaryDirectory() as name:
                    target = Path(name); archive.extractall(target)
                    self.assertEqual(self.run_customizer(target, 27), 0)

    def run_customizer(self, target, api, baseline_matches=True):
        script = '''abort() { echo "ABORT:$*"; exit 71; }
ui_print() { :; }
set_perm_recursive() { [ "$*" = "$MODPATH 0 0 0755 0644 u:object_r:system_file:s0" ] || exit 72; }
sha256sum() { [ "$*" = "-c $MODPATH/baseline-sha256.txt" ] || exit 73; return "$THINKOS_TEST_BASELINE_RESULT"; }
. "$MODPATH/customize.sh"
'''
        import os
        return subprocess.run(['sh', '-c', script], env=dict(os.environ, MODPATH=str(target), API=str(api),
                              THINKOS_TEST_BASELINE_RESULT='0' if baseline_matches else '1'),
                              capture_output=True, text=True, timeout=10).returncode

    def test_add_customizer_aborts_when_original_rom_store_was_changed(self):
        subprocess.run(['bash', str(ROOT/'system/build-certs.sh'), '--mode', 'add'],
                       check=True, capture_output=True, timeout=30)
        with tempfile.TemporaryDirectory() as name:
            target = Path(name)
            with zipfile.ZipFile(ROOT/'system/build/thinkos-certs-v2026.10.20-add.zip') as archive:
                archive.extractall(target)
            self.assertEqual(self.run_customizer(target, 27, baseline_matches=False), 71)

    def test_real_customizer_aborts_bad_packages_and_old_api(self):
        for mutation in ('empty', 'few', 'name', 'PEM', 'symlink', 'nonempty replace', 'old API'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory(prefix='thinkos CA bad ') as name:
                target = Path(name)
                ca.write_output(target, self.files, self.metadata)
                shutil.copyfile(ROOT/'system/thinkos-certs/customize.sh', target/'customize.sh')
                directory = target/'system/etc/security/cacerts'
                (target/'install-mode').write_text('replace\n')
                (directory/'.replace').touch()
                if mutation in ('empty', 'few'):
                    for item in list(directory.iterdir())[0 if mutation == 'empty' else 99:]: item.unlink()
                elif mutation == 'name': (directory/'not-a-hash').write_text('-----BEGIN CERTIFICATE-----\n')
                elif mutation == 'PEM': (directory/next(iter(self.files))).write_text('not PEM\n')
                elif mutation == 'symlink': (directory/'12345678.0').symlink_to(directory/next(iter(self.files)))
                elif mutation == 'nonempty replace': (directory/'.replace').write_text('unexpected')
                self.assertEqual(self.run_customizer(target, 26 if mutation == 'old API' else 27), 71)

    def test_device_expectations_preserve_all_old_roots_by_renaming_colliding_new_roots(self):
        spec = importlib.util.spec_from_file_location('thinkos_certs_device', ROOT/'tools/tests/certs_device.py')
        device = importlib.util.module_from_spec(spec); spec.loader.exec_module(device)
        self.assertEqual(len(device.expected_files('baseline', self.metadata['certificates'])), 135)
        self.assertEqual(len(device.expected_files('add', self.metadata['certificates'])), 205)
        self.assertEqual(len(device.expected_files('replace', self.metadata['certificates'])), 117)
        layout = ca.installation_layout(self.metadata['certificates'], device.baseline_files())
        mapping = {item['source_file']: item['file'] for item in layout}
        self.assertEqual(mapping['0d69c7e1.0'], '0d69c7e1.1')
        self.assertEqual(mapping['d16a5865.0'], 'd16a5865.1')
        with self.assertRaises(ValueError):
            ca.installation_layout(self.metadata['certificates'], {'12345678.1': '0'*64})

    def test_device_listing_requires_original_permission_owner_and_context(self):
        spec = importlib.util.spec_from_file_location('thinkos_certs_device_listing', ROOT/'tools/tests/certs_device.py')
        device = importlib.util.module_from_spec(spec); spec.loader.exec_module(device)
        line = '-rw-r--r-- 1 0 0 u:object_r:system_file:s0 1234 2009-01-01 14:00 04f60c28.0'
        self.assertEqual(set(device.parse_listing(line)), {'04f60c28.0'})
        for bad in (line.replace('-rw-r--r--', '-rw-rw-rw-'), line.replace('1 0 0', '1 0 2000'),
                    line.replace('system_file', 'shell_data_file'), line.replace('04f60c28.0', 'unexpected'), line+'\n'+line):
            with self.assertRaises(ValueError): device.parse_listing(bad)
