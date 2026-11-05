"""Real loopback HTTP tests for the narrow OTA-5 responder (never device tests).

Payloads are inert bytes, not signed APK fixtures: authentication belongs to the
already-tested upstream ota_lan producer. No socket or HTTP transport is mocked.
"""
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import tempfile
import time
import unittest
from urllib.parse import urlsplit

from ota5_origin import DROP_BYTES, Fault, MAX_CATALOG, MAX_EVENTS, MAX_SIGNATURE, Ota5Origin


def digest(data):
    return hashlib.sha256(data).hexdigest()


class OriginTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='thinkos OTA5 origin inert bytes ')
        self.root = Path(self.temporary.name).resolve()
        self.directory = self.root / 'prepared'
        self.directory.mkdir(mode=0o700)
        self.apk_name = 'thinkos-0.2.0.apk'
        self.apk = bytes(range(256)) * 1024
        self.base = 'http://192.168.1.20:8765/ota5/run/'
        self.catalog = dict(schema=1, channel='stable', seq=101, published=1760000000000, expires=1900000000000,
                            release=dict(version='0.2.0', code=2000, size=len(self.apk), sha256=digest(self.apk),
                                         signer='1' * 64, sdk=27, notes={}, source='https://example.invalid/tree/fixture',
                                         urls=[self.base + self.apk_name], modules=[]))
        self.files = {'stable.json': b'', 'stable.json.sig': b'opaque upstream approved signature bytes', self.apk_name: self.apk}
        self.manifest = dict(schema=1, stage='prepared', artifact_kind='release', executed=True, published=False,
                             device_changed=False, seq=101, channel='stable', mirror=self.base + 'stable.json')
        self.publish()
        self.origins = []

    def publish(self):
        self.files['stable.json'] = json.dumps(self.catalog, sort_keys=True, separators=(',', ':')).encode()
        for name, data in self.files.items():
            (self.directory / name).write_bytes(data)
        self.manifest.update(files=sorted(self.files), output_sha256={name: digest(data) for name, data in self.files.items()},
                             catalog_sha256=digest(self.files['stable.json']))

    def origin(self):
        origin = Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')
        self.origins.append(origin)
        return origin.start()

    def tearDown(self):
        for origin in self.origins:
            origin.close()
        self.temporary.cleanup()

    def request(self, origin, name='stable.json', *, method='GET', headers=None):
        url = urlsplit(origin.url(name))
        connection = http.client.HTTPConnection(url.hostname, url.port, timeout=3)
        request_headers = {'User-Agent': 'ThinkOS/0.1.25', **(headers or {})}
        try:
            connection.request(method, url.path, headers=request_headers)
            response = connection.getresponse()
            status, response_headers = response.status, dict(response.getheaders())
            body = response.read()
            return status, response_headers, body
        finally:
            connection.close()

    def raw(self, origin, request):
        url = urlsplit(origin.url('stable.json'))
        with socket.create_connection((url.hostname, url.port), timeout=3) as connection:
            connection.settimeout(3)
            connection.sendall(request)
            connection.shutdown(socket.SHUT_WR)
            chunks = []
            while True:
                data = connection.recv(65536)
                if not data:
                    return b''.join(chunks)
                chunks.append(data)

    def complete_events(self, origin, count):
        end = time.monotonic() + 2
        while time.monotonic() < end:
            value = origin.events()
            if value['total'] >= count:
                return value
            time.sleep(.005)
        self.fail('Origin did not finish recording requests')

    def test_actual_get_head_etag_and_one_range_match_approved_snapshot(self):
        origin = self.origin()
        status, headers, body = self.request(origin)
        self.assertEqual(200, status)
        self.assertEqual(self.files['stable.json'], body)
        self.assertEqual('"' + digest(body) + '"', headers['ETag'])
        status, head, body = self.request(origin, self.apk_name, method='HEAD')
        self.assertEqual((200, str(len(self.apk)), b''), (status, head['Content-Length'], body))
        status, _, body = self.request(origin, headers={'If-None-Match': headers['ETag']})
        self.assertEqual((304, b''), (status, body))
        status, headers, body = self.request(origin, self.apk_name, headers={'Range': 'bytes=65536-131071'})
        self.assertEqual(206, status)
        self.assertEqual('bytes 65536-131071/' + str(len(self.apk)), headers['Content-Range'])
        self.assertEqual(self.apk[65536:131072], body)
        status, _, body = self.request(origin, self.apk_name, headers={'Range': 'bytes=131072-'})
        self.assertEqual((206, self.apk[131072:]), (status, body))
        event = self.complete_events(origin, 5)['events'][-1]
        self.assertFalse(any(event[name] for name in ('serial_found', 'android_id_found', 'mac_found', 'sensitive_header_found')))
        self.assertEqual('ThinkOS/0.1.25', event['user_agent'])
        self.assertEqual('bytes=131072-', event['range'])

    def test_post_snapshot_source_changes_never_change_served_bytes_or_routes(self):
        origin = self.origin()
        for name in list(self.files):
            (self.directory / name).unlink()
        (self.directory / self.apk_name).symlink_to('/etc/passwd')
        (self.directory / 'private.key.pem').write_text('not a served file')
        self.assertEqual(self.apk, self.request(origin, self.apk_name)[2])
        self.assertEqual(self.files['stable.json'], self.request(origin)[2])
        result = self.raw(origin, ('GET /ota5/run/private.key.pem HTTP/1.1\r\nHost: ' + origin.authority + '\r\n\r\n').encode())
        self.assertTrue(result.startswith(b'HTTP/1.1 404'))

    def test_prepared_output_checks_hashes_exact_files_and_no_symlinks(self):
        for name in ('stage', 'executed', 'artifact_kind'):
            invalid = copy.deepcopy(self.manifest)
            invalid[name] = {'stage': 'planned', 'executed': False, 'artifact_kind': 'debug'}[name]
            with self.subTest(name=name), self.assertRaises(ValueError):
                Ota5Origin(self.directory, invalid, bind='127.0.0.1')
        (self.directory / self.apk_name).write_bytes(b'changed after preparation')
        with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')
        self.publish()
        (self.directory / 'report.json').write_text('{}')
        with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')
        (self.directory / 'report.json').unlink()
        target = self.root / 'payload'
        target.write_bytes(self.apk)
        (self.directory / self.apk_name).unlink()
        (self.directory / self.apk_name).symlink_to(target)
        with self.assertRaises((ValueError, OSError)): Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')

    def test_parent_symlinks_and_special_files_are_refused_before_read(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaises(OSError): Ota5Origin(alias, self.manifest, bind='127.0.0.1')
        (self.directory / self.apk_name).unlink()
        os.mkfifo(self.directory / self.apk_name)
        with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')

    def test_approved_hash_list_cannot_add_source_report_or_key(self):
        for name in ('report.json', 'source.tar.gz', 'keys.json', 'channel-active.key.pem'):
            with self.subTest(name=name):
                self.files[name] = b'approved hash alone is not publication authority'
                self.publish()
                with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')
                del self.files[name]
                (self.directory / name).unlink()
        self.publish()

    def test_catalog_tuple_and_rebased_url_must_match_exact_output(self):
        original = copy.deepcopy(self.catalog)
        for change in ('hash', 'size', 'host', 'path', 'many', 'seq'):
            self.catalog = copy.deepcopy(original)
            if change == 'hash': self.catalog['release']['sha256'] = 'f' * 64
            elif change == 'size': self.catalog['release']['size'] += 1
            elif change == 'host': self.catalog['release']['urls'] = ['http://8.8.8.8/' + self.apk_name]
            elif change == 'path': self.catalog['release']['urls'] = [self.base + '../' + self.apk_name]
            elif change == 'many': self.catalog['release']['urls'] *= 2
            else: self.catalog['seq'] += 1
            self.publish()
            with self.subTest(change=change), self.assertRaises(ValueError):
                Ota5Origin(self.directory, self.manifest, bind='127.0.0.1')

    def test_catalog_referenced_modules_and_original_manifests_are_served_unchanged(self):
        payload, document = 'thinkos-cast-3.zip', 'thinkos-cast-3.manifest'
        self.files[payload] = b'inert module archive fixture'
        self.files[document] = b'THINKOS-MODULE-V1\nthinkos-cast\nmagisk\n3\n0.0.3\n' + b'1' * 64 + b'\n27\n'
        self.files[document + '.sig'] = b'opaque approved system signature'
        self.catalog['release']['modules'] = [dict(id='thinkos-cast', kind='magisk', version='0.0.3', code=3,
                                                   size=len(self.files[payload]), sha256=digest(self.files[payload]),
                                                   urls=[self.base + payload], manifest_urls=[self.base + document])]
        self.publish()
        origin = self.origin()
        for name in (payload, document, document + '.sig'):
            self.assertEqual(self.files[name], self.request(origin, name)[2])
        origin.set_fault(Fault.CATALOG_BYTE)
        self.assertEqual(self.files[document], self.request(origin, document)[2])

    def test_bind_refuses_public_dns_wildcard_mapped_ipv6_and_implicit_lan_port(self):
        for address in ('0.0.0.0', '8.8.8.8', 'example.com', 'localhost', '169.254.1.1', '100.64.0.1', '::', 'fe80::1', 'fd00::1', '::ffff:127.0.0.1'):
            with self.subTest(address=address), self.assertRaises(ValueError):
                Ota5Origin(self.directory, self.manifest, bind=address)
        with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind='192.168.1.20')
        for address in ('192.168.1.20', '127.0.0.1', '::1'):
            # Construct only: never bind an address not present on this host.
            Ota5Origin(self.directory, self.manifest, bind=address, port=8765)
        for address, port in (('10.0.0.1', 8765), ('172.16.0.1', 8765), ('192.168.1.20', 8766)):
            with self.assertRaises(ValueError): Ota5Origin(self.directory, self.manifest, bind=address, port=port)

    def test_one_byte_fault_mutates_only_selected_document_and_never_disk(self):
        origin = self.origin()
        for fault, selected in ((Fault.CATALOG_BYTE, 'stable.json'), (Fault.SIGNATURE_BYTE, 'stable.json.sig')):
            origin.set_fault(fault)
            for name, before in self.files.items():
                status, _, after = self.request(origin, name)
                self.assertEqual(200, status)
                if name == selected:
                    self.assertEqual(len(before), len(after))
                    self.assertEqual(1, sum(left != right for left, right in zip(before, after)))
                else:
                    self.assertEqual(before, after)
                self.assertEqual(before, (self.directory / name).read_bytes())
        origin.set_fault(Fault.NORMAL)
        self.assertEqual(self.files['stable.json.sig'], self.request(origin, 'stable.json.sig')[2])
        with self.assertRaises(ValueError): origin.set_fault('catalog-byte')

    def test_oversize_fault_is_only_declared_for_named_document_not_allocated(self):
        origin = self.origin()
        for fault, name, limit in ((Fault.CATALOG_OVERSIZE, 'stable.json', MAX_CATALOG),
                                   (Fault.SIGNATURE_OVERSIZE, 'stable.json.sig', MAX_SIGNATURE)):
            origin.set_fault(fault)
            status, headers, body = self.request(origin, name, method='HEAD')
            self.assertEqual((200, str(limit + 1), b''), (status, headers['Content-Length'], body))
            with self.assertRaises(http.client.IncompleteRead) as failure:
                self.request(origin, name)
            self.assertEqual(b'', failure.exception.partial)
            self.assertEqual(self.apk, self.request(origin, self.apk_name)[2])

    def test_first_apk_disconnect_is_exact_64k_then_true_range_resume(self):
        origin = self.origin()
        origin.set_fault(Fault.APK_DISCONNECT_ONCE)
        self.assertEqual(self.files['stable.json'], self.request(origin)[2])
        self.assertEqual(b'', self.request(origin, self.apk_name, method='HEAD')[2])
        with self.assertRaises(http.client.IncompleteRead) as failure:
            self.request(origin, self.apk_name, headers={'Range': 'bytes=0-' + str(len(self.apk) - 1)})
        partial = failure.exception.partial
        self.assertEqual(self.apk[:DROP_BYTES], partial)
        status, headers, suffix = self.request(origin, self.apk_name, headers={'Range': 'bytes=' + str(len(partial)) + '-'})
        self.assertEqual((206, 'bytes 65536-262143/262144'), (status, headers['Content-Range']))
        self.assertEqual(digest(self.apk), digest(partial + suffix))
        self.assertEqual(self.apk, self.request(origin, self.apk_name)[2])
        events = self.complete_events(origin, 5)['events']
        self.assertEqual(1, sum(event['fault'] == Fault.APK_DISCONNECT_ONCE.value for event in events))
        self.assertEqual(DROP_BYTES, [event for event in events if event['fault'] == Fault.APK_DISCONNECT_ONCE.value][0]['sent'])

    def test_ranges_are_single_bounded_and_have_no_out_of_file_reads(self):
        origin = self.origin()
        for value in ('bytes=-4', 'bytes=0-3,5-6', 'items=0-3', 'bytes=3-2', 'bytes=262144-', 'bytes=0-262144', 'bytes=999999999999999999-'):
            with self.subTest(value=value):
                status, _, body = self.request(origin, self.apk_name, headers={'Range': value})
                self.assertEqual((416, b''), (status, body))

    def test_http_has_no_control_or_unsafe_paths_and_never_echoes_unknown_targets(self):
        origin = self.origin()
        for target in ('/ota5/run/../stable.json', '/ota5/run/%73table.json', '/ota5/run/stable.json?control=normal',
                       '/ota5/run/stable.json#x', '/ota5//run/stable.json', '/ota5/run\\stable.json',
                       'http://127.0.0.1/ota5/run/stable.json', '/control/normal', '/ota5/run/secret-SERIALVALUE'):
            request = ('GET ' + target + ' HTTP/1.1\r\nHost: ' + origin.authority + '\r\n\r\n').encode()
            self.assertTrue(self.raw(origin, request).startswith(b'HTTP/1.1 404'))
        for method in ('POST', 'PUT', 'DELETE', 'OPTIONS'):
            request = (method + ' /ota5/run/stable.json HTTP/1.1\r\nHost: ' + origin.authority + '\r\n\r\n').encode()
            self.assertTrue(self.raw(origin, request).startswith(b'HTTP/1.1 405'))
        events = self.complete_events(origin, 13)
        self.assertNotIn('SERIALVALUE', json.dumps(events))
        self.assertTrue(all(event['path'] is None for event in events['events']))

    def test_sensitive_headers_are_boolean_only_and_generic_headers_are_bounded(self):
        origin = self.origin()
        headers = {'X-Device-Serial': 'HUA0BWUT', 'X-Android-ID': 'deadbeef01234567',
                   'X-Wifi-Mac': 'aa:bb:cc:dd:ee:ff', 'Authorization': 'Bearer private-token',
                   'Cookie': 'secret=value', 'User-Agent': 'ThinkOS/0.1.25 HUA0BWUT'}
        self.assertEqual(200, self.request(origin, headers=headers)[0])
        event = self.complete_events(origin, 1)['events'][0]
        self.assertTrue(all(event[key] for key in ('serial_found', 'android_id_found', 'mac_found', 'sensitive_header_found')))
        encoded = json.dumps(origin.events())
        for private in ('HUA0BWUT', 'deadbeef01234567', 'aa:bb:cc:dd:ee:ff', 'private-token', 'secret=value', 'Authorization', 'Cookie'):
            self.assertNotIn(private, encoded)
        self.assertNotIn('user_agent', event)
        self.assertNotIn('127.0.0.1', encoded)
        self.request(origin, headers={'User-Agent': 'ThinkOS/0.1.25-HUA0BWUT'})
        event = self.complete_events(origin, 2)['events'][-1]
        self.assertTrue(event['sensitive_header_found'])
        self.assertNotIn('HUA0BWUT', json.dumps(event))

    def test_duplicate_headers_body_smuggling_and_header_limits_fail_closed(self):
        origin = self.origin()
        prefix = 'GET /ota5/run/stable.json HTTP/1.1\r\nHost: ' + origin.authority + '\r\n'
        for extra in ('Range: bytes=0-1\r\nRange: bytes=2-3\r\n', 'Transfer-Encoding: chunked\r\n',
                      'Content-Length: 1\r\n', 'Host: bad\r\n', ' bad: folded\r\n'):
            with self.subTest(extra=extra):
                self.assertTrue(self.raw(origin, (prefix + extra + '\r\n').encode()).startswith(b'HTTP/1.1 400'))
        for extra in ('X: ' + 'x' * 2048 + '\r\n', ''.join('X-' + str(i) + ': v\r\n' for i in range(33)),
                      ''.join('X-' + str(i) + ': ' + 'v' * 500 + '\r\n' for i in range(20))):
            self.assertTrue(self.raw(origin, (prefix + extra + '\r\n').encode()).startswith(b'HTTP/1.1 431'))

    def test_evidence_ring_is_bounded_and_returned_copies_cannot_modify_it(self):
        origin = self.origin()
        for _ in range(MAX_EVENTS + 2):
            self.request(origin, method='HEAD')
        value = self.complete_events(origin, MAX_EVENTS + 2)
        self.assertEqual((MAX_EVENTS + 2, 2, MAX_EVENTS), (value['total'], value['dropped'], len(value['events'])))
        value['events'][0]['path'] = 'tampered'
        self.assertNotEqual('tampered', origin.events()['events'][0]['path'])


if __name__ == '__main__':
    unittest.main()
