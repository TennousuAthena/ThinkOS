"""Focused real-OpenSSL/local-HTTP fixtures; no device, internet, formal root or Lua execution."""
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
import registry_fixture_origin as fixture


class RegistryFixtureOriginTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which('openssl') is None:
            raise unittest.SkipTest('Local OpenSSL is required')
        cls.work = tempfile.TemporaryDirectory(prefix='thinkos-reg2b-origin-test-')
        cls.base = Path(cls.work.name).resolve()
        cls.run_id = '0123456789abcdef' * 2
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1', 0)); cls.port = reserve.getsockname()[1]
        cls.url = 'http://127.0.0.1:%d/reg2b/%s/' % (cls.port, cls.run_id)
        cls.original = cls.base / ('reg2b-' + cls.run_id)
        fixture.prepare(cls.original, cls.run_id, cls.url)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.base, prefix='case-')
        self.root = Path(self.temp.name) / self.original.name
        shutil.copytree(self.original, self.root)
        self.material = fixture.Material.open(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def catalog(self):
        raw = self.material.response(self.material.prefix + 'registry/index.json')
        sig = self.material.response(self.material.prefix + 'registry/index.json.sig')
        return raw, sig, json.loads(raw)

    def test_four_p256_roles_are_unique_and_only_public_document_is_apk_input(self):
        value = json.loads((self.root / 'public/keys.json').read_bytes())
        self.assertEqual({'schema', 'channel', 'registry'}, set(value))
        ids, keys = [], []
        for role in fixture.ROLES:
            for slot in fixture.SLOTS:
                item = value[role][slot]
                self.assertEqual('reg2b-' + self.run_id + '-' + role + '-' + slot, item['id'])
                self.assertEqual('SHA256withECDSA', item['algorithm'])
                self.assertLessEqual(len(item['id']), 64)
                ids.append(item['id']); keys.append(item['spki'])
                self.assertEqual(0o600, (self.root / 'private' / (role + '-' + slot + '.pem')).stat().st_mode & 0o777)
        self.assertEqual(4, len(set(ids))); self.assertEqual(4, len(set(keys)))
        self.assertEqual(0o700, self.root.stat().st_mode & 0o777)
        self.assertEqual(600, self.material.meta['trial_seconds'])

    def test_real_der_signature_rejects_byte_changes_wrong_role_and_malformed_signature(self):
        raw, sig, _ = self.catalog()
        self.assertEqual(0x30, sig[0]); self.assertTrue(self.material.verify(raw, sig))
        self.assertFalse(self.material.verify(raw + b' ', sig))
        self.assertFalse(self.material.verify(raw, sig, 'channel'))
        self.assertFalse(self.material.verify(raw, b'broken'))
        self.material.publish(bad_signature=True)
        raw, sig, _ = self.catalog()
        self.assertFalse(self.material.verify(raw, sig)); self.assertTrue(self.material.verify(raw, sig, 'channel'))

    def test_v1_to_v5_have_real_canonical_indexes_and_fixed_lua_event_behavior(self):
        for version, value in self.material.meta['versions'].items():
            index_raw = base64.b64decode(value['index_base64'])
            self.assertEqual(value['index_sha256'], hashlib.sha256(index_raw).hexdigest())
            index = json.loads(index_raw)
            canonical = b''.join((row['path'] + '\0' + row['sha256'] + '\n').encode() for row in index['files'])
            self.assertEqual(index['digest'], hashlib.sha256(canonical).hexdigest())
            for row in index['files']:
                body = self.material.response(self.material.prefix + 'blobs/' + row['sha256'][:2] + '/' + row['sha256'])
                self.assertEqual(row['size'], len(body)); self.assertEqual(row['sha256'], hashlib.sha256(body).hexdigest())
                if row['path'] == 'main.lua':
                    self.assertIn(b'REGISTRY_STARTED', body); self.assertIn(b'REGISTRY_DENIED', body)
                    self.assertEqual(version == '1.0.1', b'Deliberate registry startup failure' in body)
                    self.assertEqual(version == '1.0.4', b'Deliberate registry runtime failure' in body)
                if row['path'] == 'LICENSE':
                    self.assertEqual((ROOT / 'LICENSE').read_bytes(), body)
        self.assertEqual(['network'], self.material.meta['versions']['1.0.0']['permissions'])
        self.assertEqual(['network', 'media', 'location'], self.material.meta['versions']['1.0.2']['permissions'])
        self.material.publish(level=5)
        raw, sig, doc = self.catalog(); self.assertTrue(self.material.verify(raw, sig))
        self.assertEqual(['1.0.4', '1.0.3', '1.0.2'], [r['version'] for r in doc['plugins'][0]['releases']])

    def test_cold_host_reload_keeps_roots_objects_and_monotonic_sequence(self):
        previous = self.material.publish(level=3)
        reloaded = fixture.Material.open(self.root)
        self.assertEqual(self.material.meta, reloaded.meta)
        self.assertEqual(previous['catalog_sha256'], fixture.digest(reloaded.response(reloaded.prefix + 'registry/index.json')))
        after = reloaded.publish(level=4)
        self.assertGreater(after['seq'], previous['seq'])
        self.assertTrue((self.root / 'publications' / (previous['stem'] + '.json')).is_file())

    def test_negative_roles_permissions_blob_and_seventeen_packages_are_real_material(self):
        for sample in ('home', 'settings', 'system', 'tampered-blob', 'limits'):
            self.material.publish(sample=sample)
            raw, sig, doc = self.catalog(); self.assertTrue(self.material.verify(raw, sig))
            fixture.schema(doc, 'catalog.json')
            if sample == 'limits':
                self.assertEqual(17, len(doc['plugins'])); continue
            value = self.material.meta['samples'][sample]
            index = self.material.meta['pack_indexes'][value['digest']]['index']
            manifest_row = next(row for row in index['files'] if row['path'] == 'manifest.json')
            manifest = json.loads(self.material.response(self.material.prefix + 'blobs/' + manifest_row['sha256'][:2] + '/' + manifest_row['sha256']))
            if sample in ('home', 'settings'):
                self.assertEqual([sample], manifest['pages'][0]['roles'])
            if sample == 'system':
                self.assertIn('system.settings', manifest['permissions'])
                self.assertNotIn('system.settings', doc['plugins'][0]['releases'][0]['permissions'])
            if sample == 'tampered-blob':
                sha = self.material.meta['tampered_blob_sha256']
                self.assertNotEqual(sha, fixture.digest(self.material.response(self.material.prefix + 'blobs/' + sha[:2] + '/' + sha)))

    def test_previous_only_revocation_and_community_do_not_revoke_latest(self):
        self.material.publish(level=4, tier='community', revoke_previous=True)
        raw, sig, doc = self.catalog(); self.assertTrue(self.material.verify(raw, sig))
        self.assertEqual('community', doc['plugins'][0]['tier'])
        self.assertEqual(self.material.meta['digests']['1.0.2'], doc['revoked'][0]['digest'])
        self.assertNotEqual(doc['plugins'][0]['releases'][0]['digest'], doc['revoked'][0]['digest'])
        old = self.material.publish(old_sequence=True)
        self.assertEqual(1, old['seq']); self.assertGreater(old['allocated_seq'], 1)
        self.assertGreater(self.material.publish()['seq'], old['allocated_seq'])

    def test_invalid_origin_existing_material_and_changed_public_root_are_refused(self):
        for url in ('http://0.0.0.0:8000/', 'http://8.8.8.8:8000/', 'http://localhost:8000/',
                    'http://127.0.0.1/reg2b/' + self.run_id + '/', self.url + '?x=1', self.url.replace('http://', 'http://@')):
            with self.assertRaises(ValueError): fixture.endpoint(url, self.run_id)
        with self.assertRaises(ValueError): fixture.prepare(self.root, self.run_id, self.url)
        changed = copy.deepcopy(self.material.keys); changed['registry']['active'] = changed['channel']['active']
        (self.root / 'public/keys.json').write_bytes(fixture.encoded(changed))
        with self.assertRaises(ValueError): fixture.Material.open(self.root)

    def test_reads_refuse_fifo_symlink_growth_and_inode_replacement(self):
        path = self.root / 'read-fixture'
        os.mkfifo(path, 0o600)
        with self.assertRaises(ValueError): fixture.regular(path)
        path.unlink(); path.symlink_to(self.root / 'material.json')
        with self.assertRaises(ValueError): fixture.regular(path)
        path.unlink(); fixture.write_new(path, b'ab')
        original = fixture.os.read
        def grow(fd, size):
            value = original(fd, size)
            with path.open('ab') as out: out.write(b'grow')
            return value
        with patch.object(fixture.os, 'read', side_effect=grow):
            with self.assertRaises(ValueError): fixture.regular(path, 8)
        path.unlink(); fixture.write_new(path, b'ab')
        replaced = False
        def replace(fd, size):
            nonlocal replaced
            value = original(fd, size)
            if not replaced:
                replaced = True; path.unlink(); fixture.write_new(path, b'ab')
            return value
        with patch.object(fixture.os, 'read', side_effect=replace):
            with self.assertRaises(ValueError): fixture.regular(path, 8)

    def test_http_only_fixed_gets_no_private_control_upload_query_or_overlong_request(self):
        def request(raw):
            with socket.create_connection(('127.0.0.1', self.port), timeout=3) as client:
                client.sendall(raw); client.shutdown(socket.SHUT_WR)
                result = bytearray()
                while True:
                    part = client.recv(65536)
                    if not part: break
                    result.extend(part)
                return bytes(result)
        with fixture.Origin(self.material):
            prefix = self.material.prefix.encode()
            response = request(b'GET ' + prefix + b'registry/index.json HTTP/1.0\r\n\r\n')
            self.assertTrue(response.startswith(b'HTTP/1.0 200 '))
            self.assertEqual(self.catalog()[0], response.split(b'\r\n\r\n', 1)[1])
            for raw, status in (
                (b'GET ' + prefix + b'private/registry-active.pem HTTP/1.0\r\n\r\n', 404),
                (b'POST /sign HTTP/1.0\r\nContent-Length: 2\r\n\r\n{}', 405),
                (b'GET ' + prefix + b'registry/index.json?x=1 HTTP/1.0\r\n\r\n', 400),
                (b'GET /' + b'a' * 1100 + b' HTTP/1.0\r\n\r\n', 414),
                (b'GET ' + prefix + b'registry/index.json HTTP/1.0\r\nX: ' + b'a' * 1100 + b'\r\n\r\n', 431)):
                self.assertTrue(request(raw).startswith(('HTTP/1.0 %d ' % status).encode()))


if __name__ == '__main__':
    unittest.main()

    def test_fixed_recovery_append_preserves_roots_old_objects_and_publications(self):
        self.assertEqual(['1.0.'+str(n) for n in range(5)],list(self.material.meta['versions']))
        previous=self.material.publish(level=4)
        pointer=(self.root/'current.json').read_bytes()
        keys=(self.root/'public/keys.json').read_bytes()
        private_hashes={p.name:fixture.digest(p.read_bytes()) for p in (self.root/'private').glob('*.pem')}
        old_objects={p.name:fixture.digest(p.read_bytes()) for p in (self.root/'objects').iterdir()}
        old_publications={p.name:fixture.digest(p.read_bytes()) for p in (self.root/'publications').iterdir()}
        old_document=copy.deepcopy(self.material.document)
        after=self.material.append_recovery()
        self.assertEqual(self.material.meta['versions']['1.0.2']['permissions'],after['versions']['1.0.5']['permissions'])
        self.assertEqual(pointer,(self.root/'current.json').read_bytes())
        self.assertEqual(keys,(self.root/'public/keys.json').read_bytes())
        self.assertEqual(private_hashes,{p.name:fixture.digest(p.read_bytes()) for p in (self.root/'private').glob('*.pem')})
        self.assertEqual(old_document['packages'],self.material.document['packages'][:-1])
        for route,value in old_document['responses'].items(): self.assertEqual(value,self.material.document['responses'][route])
        for name,sha in old_objects.items(): self.assertEqual(sha,fixture.digest((self.root/'objects'/name).read_bytes()))
        for name,sha in old_publications.items(): self.assertEqual(sha,fixture.digest((self.root/'publications'/name).read_bytes()))
        new_index=after['versions']['1.0.5']['index']
        canonical=b''.join((row['path']+'\0'+row['sha256']+'\n').encode() for row in new_index['files'])
        self.assertEqual(after['digests']['1.0.5'],fixture.digest(canonical))
        lua_row=next(row for row in new_index['files'] if row['path']=='main.lua')
        lua=self.material.response(self.material.prefix+'blobs/'+lua_row['sha256'][:2]+'/'+lua_row['sha256'])
        self.assertIn(b'REGISTRY_STARTED',lua); self.assertIn(b'1.0.5',lua)
        self.assertNotIn(b'Deliberate registry',lua); self.assertNotIn(b'tos.media.load',lua)
        publication=self.material.publish(recovery=True)
        raw,sig,doc=self.catalog()
        self.assertTrue(self.material.verify(raw,sig));fixture.schema(doc,'catalog.json')
        self.assertGreater(publication['seq'],previous['seq'])
        self.assertEqual(['1.0.5','1.0.3','1.0.2'],[r['version'] for r in doc['plugins'][0]['releases']])
        self.assertEqual(after['digests']['1.0.2'],doc['plugins'][0]['releases'][2]['digest'])
        self.assertEqual(after['digests'],fixture.Material.open(self.root).meta['digests'])

    def test_recovery_cli_is_explicit_one_time_and_cannot_use_rollback_or_fault_modes(self):
        import contextlib
        import io
        pointer=(self.root/'current.json').read_bytes()
        with self.assertRaises(ValueError):self.material.publish(recovery=True)
        self.assertEqual(pointer,(self.root/'current.json').read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,fixture.main(['append-recovery','--directory',str(self.root)]))
        self.material=fixture.Material.open(self.root)
        material_bytes=(self.root/'material.json').read_bytes()
        with self.assertRaises(ValueError):self.material.append_recovery()
        self.assertEqual(material_bytes,(self.root/'material.json').read_bytes())
        self.assertEqual(pointer,(self.root/'current.json').read_bytes())
        for mode in ({'old_sequence':True},{'bad_signature':True},{'revoked':True},{'level':4},{'tier':'community'}):
            with self.assertRaises(ValueError):self.material.publish(recovery=True,**mode)
        self.assertEqual(pointer,(self.root/'current.json').read_bytes())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,fixture.main(['publish-recovery','--directory',str(self.root)]))
        raw,sig,doc=self.catalog()
        self.assertTrue(self.material.verify(raw,sig))
        self.assertEqual(['1.0.5','1.0.3','1.0.2'],[r['version'] for r in doc['plugins'][0]['releases']])
        self.assertGreater(doc['seq'],json.loads(pointer)['seq'])
