"""Pinned CCADB fixture and strict interpretation of actual Android TLS results."""
import copy
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('thinkos_certs_tls_device', ROOT/'tools/tests/certs_tls_device.py')
tls = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(tls)


class CertTlsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((ROOT/'tools/tests/fixtures/certs-tls/sites.json').read_text())
        self.sites = self.fixture['sites']

    def test_sites_and_fingerprints_match_pinned_ccadb_and_both_local_stores(self):
        path = ROOT/'artifacts/certs/CERT-3/tls-sources/included-ca-20261104.csv'
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), self.fixture['source_sha256'])
        with path.open() as stream:
            rows = list(csv.DictReader(stream))
        module = json.loads((ROOT/'system/thinkos-certs/certs-source.json').read_text())
        new = {c['der_sha256'] for c in module['certificates']}
        old = {line.split('\t')[1] for line in (ROOT/'artifacts/certs/CERT-0/device-store-2026-11-03.tsv').read_text().splitlines() if not line.startswith('#')}
        self.assertEqual({site['id'] for site in self.sites}, {'isrg-x1', 'isrg-x2', 'gts-r4'})
        for site in self.sites:
            row = next(row for row in rows if row['SHA-256 Fingerprint'].lower() == site['root_sha256'])
            self.assertEqual(row['Common Name or Certificate Name'], site['root'])
            self.assertEqual(row['Test Website - Valid'].rstrip('/')+'/', site['url'])
            url = urlsplit(site['url'])
            self.assertEqual(url.scheme, 'https'); self.assertIsNone(url.username); self.assertIsNone(url.port)
            self.assertIn(site['root_sha256'], new)
            self.assertEqual(site['root_sha256'] not in old, site['absent_in_original_store'])

    def accepted(self):
        return [{'id': site['id'], 'outcome': 'accepted', 'expected_root_sha256': site['root_sha256'],
                 'anchor_sha256': site['root_sha256'], 'expected_root_is_anchor': True} for site in self.sites]

    def test_baseline_observes_new_root_rejection_without_claiming_overlay_acceptance(self):
        observations = self.accepted()
        for item in observations[1:]:
            item['outcome'] = 'tls_rejected'; item.pop('anchor_sha256'); item.pop('expected_root_is_anchor')
        self.assertFalse(tls.validate_samples('baseline', observations, self.sites))
        for mode in ('add', 'replace'):
            with self.assertRaises(AssertionError): tls.validate_samples(mode, observations, self.sites)

    def test_a_cross_signed_old_anchor_cannot_pass_new_root_acceptance(self):
        observations = self.accepted()
        observations[1]['anchor_sha256'] = self.sites[0]['root_sha256']
        observations[1]['expected_root_is_anchor'] = False
        self.assertFalse(tls.validate_samples('baseline', observations, self.sites))
        with self.assertRaises(AssertionError): tls.validate_samples('replace', observations, self.sites)
        for mode in ('add', 'replace'):
            self.assertTrue(tls.validate_samples(mode, self.accepted(), self.sites))

    def test_missing_duplicate_or_wrong_root_and_failed_control_are_rejected(self):
        invalid = [self.accepted()[:-1], self.accepted()+[self.accepted()[0]]]
        bad = self.accepted(); bad[1]['expected_root_sha256'] = '0'*64; invalid.append(bad)
        bad = self.accepted(); bad[0]['outcome'] = 'network_error'; invalid.append(bad)
        for observations in invalid:
            for mode in ('baseline', 'add', 'replace'):
                with self.assertRaises(AssertionError): tls.validate_samples(mode, copy.deepcopy(observations), self.sites)
