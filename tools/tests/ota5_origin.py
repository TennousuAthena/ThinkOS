"""MIT. Fixed OTA-5 LAN fixture origin; no signing, device or HTTP control API.

The caller supplies the *executed* ota_lan result and its flat output directory.
This helper verifies those approved output hashes; upstream ota_lan authenticates
the release. It does not replace that authentication or implement an OTA runner.
Only immutable snapshots of catalog-referenced files can leave this process.
"""
from collections import deque
from enum import Enum
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import socketserver
import stat
import threading
import time
from types import MappingProxyType
from urllib.parse import urlsplit


NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,159}')
SHA = re.compile(r'[a-f0-9]{64}')
PRIVATE_V4 = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
MODULES = frozenset(('thinkos-updater', 'thinkos-cast', 'thinkos-immersive', 'thinkos-shell',
                     'thinkos-certs', 'thinkos-power', 'timesync'))
MAX_CATALOG, MAX_SIGNATURE, MAX_MANIFEST = 1024 * 1024, 1024, 64 * 1024
MAX_APK, MAX_MODULE, MAX_TOTAL = 64 * 1024 * 1024, 16 * 1024 * 1024, 192 * 1024 * 1024
DROP_BYTES = 64 * 1024
MAX_REQUEST, MAX_LINE, MAX_HEADERS, MAX_EVENTS = 8192, 2048, 32, 256
REQUEST_SECONDS = 2.0
UA = re.compile(r'(?:ThinkOS/[0-9]{1,4}\.[0-9]{1,3}\.[0-9]{1,3}|Python-urllib/[0-9]\.[0-9]{1,2})')
RANGE = re.compile(r'bytes=([0-9]{1,10})-([0-9]{0,10})')
ETAG = re.compile(r'"[a-f0-9]{64}"')
MAC = re.compile(r'(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])')
HEADER_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")


class Fault(Enum):
    NORMAL = 'normal'
    CATALOG_BYTE = 'catalog-byte'
    SIGNATURE_BYTE = 'signature-byte'
    CATALOG_OVERSIZE = 'catalog-oversize'
    SIGNATURE_OVERSIZE = 'signature-oversize'
    APK_DISCONNECT_ONCE = 'apk-disconnect-once'


def _name(value):
    if not isinstance(value, str) or not NAME.fullmatch(value) or value in ('.', '..'):
        raise ValueError('Unsafe prepared basename')
    return value


def _address(value):
    if not isinstance(value, str):
        raise ValueError('Explicit numeric private/loopback bind required')
    address = ipaddress.ip_address(value)
    if not (address.is_loopback or address.version == 4 and any(address in network for network in PRIVATE_V4)):
        raise ValueError('Bind must be numeric RFC1918 or loopback, never wildcard/public')
    if address.version == 6 and address != ipaddress.ip_address('::1'):
        raise ValueError('Only IPv6 loopback is supported')
    return address


def _directory(path):
    """Walk the entire absolute parent chain without following symlinks."""
    if '..' in Path(path).parts:
        raise ValueError('Prepared directory may not traverse parents')
    absolute = Path(os.path.abspath(os.fspath(path)))
    descriptor = os.open(absolute.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in absolute.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _read(descriptor, name, limit):
    before = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= limit:
        raise ValueError('Prepared input is not a bounded regular file')
    source = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
    try:
        opened = os.fstat(source)
        if not stat.S_ISREG(opened.st_mode) or (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            raise ValueError('Prepared input changed before snapshot')
        chunks, count = [], 0
        while True:
            block = os.read(source, min(65536, limit + 1 - count))
            if not block:
                break
            count += len(block)
            if count > limit:
                raise ValueError('Prepared file grew beyond its bound')
            chunks.append(block)
        after = os.fstat(source)
        if ((opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) !=
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns) or count != opened.st_size):
            raise ValueError('Prepared input changed during snapshot')
        return b''.join(chunks)
    finally:
        os.close(source)


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate catalog member')
        result[key] = value
    return result


def _materials(directory, manifest):
    if (not isinstance(manifest, dict) or manifest.get('schema') != 1 or manifest.get('stage') != 'prepared' or
            manifest.get('artifact_kind') != 'release' or manifest.get('executed') is not True or
            manifest.get('published') is not False or manifest.get('device_changed') is not False):
        raise ValueError('An executed ota_lan release result is required')
    channel = manifest.get('channel')
    if channel not in ('stable', 'beta'):
        raise ValueError('Invalid prepared channel')
    catalog_name = channel + '.json'
    names, hashes = manifest.get('files'), manifest.get('output_sha256')
    if (not isinstance(names, list) or not 3 <= len(names) <= 24 or any(not isinstance(name, str) for name in names) or len(set(names)) != len(names) or
            not isinstance(hashes, dict) or set(names) != set(hashes)):
        raise ValueError('Exact bounded output file/hash whitelist is required')
    for name in names:
        _name(name)
        if not isinstance(hashes[name], str) or not SHA.fullmatch(hashes[name]):
            raise ValueError('Invalid approved output hash')
    if manifest.get('catalog_sha256') != hashes.get(catalog_name):
        raise ValueError('Prepared catalog hash is not approved')
    mirror = manifest.get('mirror')
    if not isinstance(mirror, str) or len(mirror) > 2048 or re.search(r'[\x00-\x20\x7f%\\]', mirror):
        raise ValueError('Invalid prepared mirror')
    url = urlsplit(mirror)
    address = ipaddress.ip_address(url.hostname or '')
    allowed = address.version == 4 and any(address in network for network in PRIVATE_V4)
    allowed = allowed or address.version == 6 and address in ipaddress.ip_network('fc00::/7')
    if (url.scheme != 'http' or not allowed or url.username is not None or url.password is not None or
            url.query or url.fragment or url.port == 0 or not url.path.endswith('/' + catalog_name)):
        raise ValueError('Expected the prepared numeric private-LAN catalog URL')
    pieces = url.path[1:].split('/')
    if any(_name(part) != part for part in pieces):
        raise ValueError('Unsafe prepared URL path')
    prefix = url.path[:-len(catalog_name)]
    base = mirror[:-len(catalog_name)]
    descriptor = _directory(directory)
    try:
        if set(os.listdir(descriptor)) != set(names):
            raise ValueError('Directory differs from the approved output whitelist')
        raw = _read(descriptor, catalog_name, MAX_CATALOG)
        if hashlib.sha256(raw).hexdigest() != hashes[catalog_name]:
            raise ValueError('Catalog differs from approved bytes')
        catalog = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique)
        if (catalog.get('schema') != 1 or catalog.get('channel') != channel or
                type(catalog.get('seq')) is not int or catalog['seq'] != manifest.get('seq')):
            raise ValueError('Catalog/result identity differs')
        release = catalog['release']
        modules = release['modules']
        if not isinstance(modules, list) or len(modules) > 7:
            raise ValueError('Invalid prepared module list')
        expected = {catalog_name: MAX_CATALOG, catalog_name + '.sig': MAX_SIGNATURE}
        identities = {}

        def artifact(row, key, maximum):
            urls = row[key]
            if not isinstance(urls, list) or len(urls) != 1 or not isinstance(urls[0], str):
                raise ValueError('Expected one rebased prepared artifact URL')
            name = _name(urls[0].rsplit('/', 1)[-1])
            if urls != [base + name] or name in expected:
                raise ValueError('Artifact is not a unique child of the prepared root')
            expected[name] = maximum
            return name

        apk = artifact(release, 'urls', MAX_APK)
        if apk != 'thinkos-' + release['version'] + '.apk':
            raise ValueError('Unexpected prepared APK name')
        identities[apk] = release
        ids = set()
        for module in modules:
            identity, kind, code = module['id'], module['kind'], module['code']
            if identity not in MODULES or identity in ids or kind not in ('magisk', 'vector') or type(code) is not int or code < 1:
                raise ValueError('Unexpected module identity')
            ids.add(identity)
            payload = artifact(module, 'urls', MAX_MODULE)
            if not payload.startswith(identity + '-') or not payload.endswith('.apk' if kind == 'vector' else '.zip'):
                raise ValueError('Unexpected prepared module name')
            identities[payload] = module
            document = artifact(module, 'manifest_urls', MAX_MANIFEST)
            if document != identity + '-' + str(code) + '.manifest' or document + '.sig' in expected:
                raise ValueError('Unexpected system manifest name')
            expected[document + '.sig'] = MAX_SIGNATURE
        if set(expected) != set(names):
            raise ValueError('Only catalog-referenced publication files may be served')
        snapshots, total = {catalog_name: raw}, len(raw)
        for name, limit in expected.items():
            if name == catalog_name:
                continue
            data = _read(descriptor, name, limit)
            total += len(data)
            if total > MAX_TOTAL or hashlib.sha256(data).hexdigest() != hashes[name]:
                raise ValueError('Prepared bytes exceed bounds or differ from approved hashes')
            if name in identities:
                row = identities[name]
                if type(row['size']) is not int or row['size'] != len(data) or row['sha256'] != hashes[name]:
                    raise ValueError('Payload differs from its approved catalog tuple')
            snapshots[name] = data
        if set(os.listdir(descriptor)) != set(names):
            raise ValueError('Prepared directory changed during snapshot')
        return MappingProxyType(snapshots), MappingProxyType(dict(hashes)), prefix, catalog_name, apk
    finally:
        os.close(descriptor)


def _audit(headers, authority):
    """Projection only: unknown header names/values and source IPs are not retained.

    Without receiving device identifiers, detection is deliberately structural:
    identifier-bearing header names/labels and MAC/Android-ID shapes. An unknown
    identifier hidden in any non-generic UA/header is redacted and flagged.
    """
    result = dict(serial_found=False, android_id_found=False, mac_found=False, sensitive_header_found=False)
    allowed = {'host', 'connection', 'accept', 'accept-encoding', 'user-agent', 'range', 'if-none-match', 'content-length'}
    for name, value in headers:
        compact = re.sub('[^a-z0-9]', '', name.lower())
        result['serial_found'] |= 'serial' in compact or bool(re.search(r'(?i)\bserial(?:number)?[=: /]', value))
        result['android_id_found'] |= 'androidid' in compact or bool(re.search(r'(?i)\bandroid[-_ ]?id[=: /]', value)) or bool(re.fullmatch('[a-fA-F0-9]{16}', value))
        result['mac_found'] |= compact in ('mac', 'wifimac', 'xmac', 'xwifimac', 'xdevicemac') or MAC.search(value) is not None
        safe = name.lower() in allowed
        if name == 'host':
            safe = value == authority
        elif name == 'user-agent':
            safe = bool(UA.fullmatch(value))
            if safe:
                result['user_agent'] = value
        elif name == 'range':
            safe = bool(RANGE.fullmatch(value))
            if safe:
                result['range'] = value
        elif name == 'if-none-match':
            safe = bool(ETAG.fullmatch(value))
            if safe:
                result['if_none_match'] = value
        elif name == 'accept':
            safe = value == '*/*'
        elif name == 'accept-encoding':
            safe = value in ('identity', 'gzip')
        elif name == 'connection':
            safe = value.lower() in ('close', 'keep-alive')
        elif name == 'content-length':
            safe = value == '0'
        result['sensitive_header_found'] |= not safe
    # Even superficially generic retained fields cannot contain an identified
    # value. Do not retain raw unknown names, a request line, or remote address.
    if result['serial_found'] or result['android_id_found'] or result['mac_found']:
        for key in ('user_agent', 'range', 'if_none_match'):
            result.pop(key, None)
    return result


class _Reject(Exception):
    def __init__(self, status):
        self.status = status


class _Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = False
    block_on_close = True
    request_queue_size = 4

    def __init__(self, address, origin, family):
        self.origin, self.address_family = origin, family
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, _Request)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # The stdlib default prints a traceback and peer address. Neither is a
        # public evidence channel; request failures are bounded sanitized events.
        pass


class _Request(socketserver.BaseRequestHandler):
    def handle(self):
        origin = self.server.origin
        event = dict(method=None, path=None, status=400, declared=0, sent=0, transport_error=False, fault=Fault.NORMAL.value,
                     serial_found=False, android_id_found=False, mac_found=False, sensitive_header_found=False)
        head, headers = False, []
        deadline, used = time.monotonic() + REQUEST_SECONDS, 0
        try:
            with self.request.makefile('rb') as incoming:
                def line():
                    nonlocal used
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise _Reject(408)
                    self.request.settimeout(remaining)
                    raw = incoming.readline(MAX_LINE + 1)
                    used += len(raw)
                    if len(raw) > MAX_LINE or used > MAX_REQUEST:
                        raise _Reject(431)
                    if not raw.endswith(b'\r\n') or any(byte < 32 and byte not in (10, 13) or byte == 127 for byte in raw):
                        raise _Reject(400)
                    return raw[:-2].decode('ascii')

                match = re.fullmatch(r'([A-Z]{1,12}) ([!-~]{1,1800}) HTTP/(1\.[01])', line())
                if match is None:
                    raise _Reject(400)
                method, target, version = match.groups()
                event['method'] = method if method in ('GET', 'HEAD') else 'OTHER'
                head = method == 'HEAD'
                for _ in range(MAX_HEADERS + 1):
                    row = line()
                    if row == '':
                        break
                    if len(headers) == MAX_HEADERS or ':' not in row:
                        raise _Reject(431)
                    name, value = row.split(':', 1)
                    if not HEADER_NAME.fullmatch(name):
                        raise _Reject(400)
                    headers.append((name.lower(), value.strip(' ')))
                event.update(_audit(headers, origin.authority))
                values = {}
                for key, value in headers:
                    if key in values:
                        raise _Reject(400)
                    values[key] = value
                if method not in ('GET', 'HEAD'):
                    raise _Reject(405)
                if ('transfer-encoding' in values or values.get('content-length', '0') != '0' or
                        version == '1.1' and values.get('host') != origin.authority or
                        'host' in values and values['host'] != origin.authority):
                    raise _Reject(400)
                name = origin._routes.get(target)
                if name is None:
                    raise _Reject(404)
                event['path'] = target
                response, data, applied = origin._response(name, method, values)
                event.update(status=response[0], declared=response[1], fault=applied.value)
                self.request.settimeout(REQUEST_SECONDS)
                self._send(response, b'' if head else data)
                event['sent'] = 0 if head else len(data)
        except _Reject as failure:
            event.update(status=failure.status)
            try:
                self._send((failure.status, 0, {}), b'')
            except OSError:
                event['transport_error'] = True
        except OSError:
            event['transport_error'] = True
            if event['status'] == 400:
                event['status'] = 408 if time.monotonic() >= deadline else 400
        except UnicodeError:
            event['status'] = 400
        finally:
            event.update(_audit(headers, origin.authority))
            origin._record(event)

    def _send(self, response, data):
        status, size, extra = response
        reason = {200: 'OK', 206: 'Partial Content', 304: 'Not Modified', 400: 'Bad Request', 404: 'Not Found',
                  405: 'Method Not Allowed', 408: 'Request Timeout', 416: 'Range Not Satisfiable', 431: 'Request Header Fields Too Large'}[status]
        rows = ['HTTP/1.1 ' + str(status) + ' ' + reason, 'Connection: close', 'Content-Length: ' + str(size),
                'Content-Type: application/octet-stream', 'Accept-Ranges: bytes', 'Cache-Control: no-store']
        rows.extend(key + ': ' + value for key, value in extra.items())
        self.request.sendall(('\r\n'.join(rows) + '\r\n\r\n').encode('ascii'))
        if data:
            self.request.sendall(data)
        # A deliberate short body closes this connection just like a real
        # truncated origin response; no Content-Range/Length is forged smaller.
        self.request.shutdown(socket.SHUT_WR)


class Ota5Origin:
    """Small composable local origin. Construct/start only after owner preparation.

    set_fault() is a local Python method, never an HTTP endpoint. Logs contain no
    device identifiers or peer IPs. The helper has no private-key, signing, ADB,
    package-execution, public-network client or device-install capability.
    """
    def __init__(self, directory, manifest, *, bind, port=0):
        self._address = _address(bind)
        if type(port) is not int or not 0 <= port <= 65535 or port == 0 and not self._address.is_loopback:
            raise ValueError('Use an explicit port for a real LAN origin')
        self._files, self._hashes, self._prefix, self._catalog, self._apk = _materials(directory, manifest)
        mirror = urlsplit(manifest['mirror'])
        if not self._address.is_loopback and (ipaddress.ip_address(mirror.hostname) != self._address or (mirror.port or 80) != port):
            raise ValueError('LAN bind must exactly match the prepared catalog origin')
        self._routes = MappingProxyType({self._prefix + name: name for name in self._files})
        self._port, self._server, self._thread = port, None, None
        self._lock = threading.Lock()
        self._fault, self._dropped = Fault.NORMAL, False
        self._events, self._event_count = deque(maxlen=MAX_EVENTS), 0

    @property
    def authority(self):
        host = self._address.compressed
        if self._address.version == 6:
            host = '[' + host + ']'
        return host + ':' + str(self._port)

    def url(self, name):
        if self._server is None or _name(name) not in self._files:
            raise ValueError('Origin must be running and file must be approved')
        return 'http://' + self.authority + self._prefix + name

    def set_fault(self, fault):
        if not isinstance(fault, Fault):
            raise ValueError('Use a fixed Fault enum member')
        if fault == Fault.APK_DISCONNECT_ONCE and len(self._files[self._apk]) <= DROP_BYTES:
            raise ValueError('Disconnect fixture needs an APK larger than 64 KiB')
        with self._lock:
            self._fault, self._dropped = fault, False

    def events(self):
        with self._lock:
            return {'total': self._event_count, 'dropped': max(0, self._event_count - MAX_EVENTS),
                    'events': [dict(event) for event in self._events]}

    def _record(self, event):
        with self._lock:
            self._event_count += 1
            self._events.append(dict(event))

    def _response(self, name, method, headers):
        raw, etag = self._files[name], '"' + self._hashes[name] + '"'
        if 'if-none-match' in headers and not ETAG.fullmatch(headers['if-none-match']):
            raise _Reject(400)
        first, last, partial = 0, len(raw) - 1, 'range' in headers
        if partial:
            match = RANGE.fullmatch(headers['range'])
            if not match:
                raise _Reject(416)
            first = int(match[1]); last = int(match[2]) if match[2] else last
            if first > last or last >= len(raw):
                return (416, 0, {'Content-Range': 'bytes */' + str(len(raw))}), b'', Fault.NORMAL
        with self._lock:
            fault = self._fault
            applicable = ((name == self._catalog and fault in (Fault.CATALOG_BYTE, Fault.CATALOG_OVERSIZE)) or
                          (name == self._catalog + '.sig' and fault in (Fault.SIGNATURE_BYTE, Fault.SIGNATURE_OVERSIZE)))
            drop = name == self._apk and fault == Fault.APK_DISCONNECT_ONCE and method == 'GET' and not self._dropped
            if drop:
                if first != 0 or last + 1 <= DROP_BYTES:
                    raise _Reject(416)
                self._dropped = True
        if not applicable and not drop:
            fault = Fault.NORMAL
        extra = {'ETag': etag}
        if fault == Fault.NORMAL and headers.get('if-none-match') == etag and not partial:
            return (304, 0, extra), b'', fault
        if fault in (Fault.CATALOG_OVERSIZE, Fault.SIGNATURE_OVERSIZE):
            # Only the declaration exceeds the native bound; no oversized body
            # is allocated or emitted. The finite body then closes the socket.
            maximum = MAX_CATALOG if fault == Fault.CATALOG_OVERSIZE else MAX_SIGNATURE
            return (200, maximum + 1, extra), b'', fault
        if fault in (Fault.CATALOG_BYTE, Fault.SIGNATURE_BYTE):
            raw = bytes((raw[0] ^ 1,)) + raw[1:]
            # Do not advertise a valid-byte validator for a mutated response.
            extra = {'ETag': '"' + hashlib.sha256(raw).hexdigest() + '"'}
        if partial:
            extra['Content-Range'] = 'bytes ' + str(first) + '-' + str(last) + '/' + str(len(raw))
        data = raw[first:last + 1]
        if drop:
            data = data[:DROP_BYTES]
        return (206 if partial else 200, last - first + 1, extra), data, fault

    def start(self):
        if self._server is not None:
            raise ValueError('Origin already started')
        family = socket.AF_INET6 if self._address.version == 6 else socket.AF_INET
        self._server = _Server((self._address.compressed, self._port), self, family)
        self._port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
        self._thread.start()
        return self

    def close(self):
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._thread.join(timeout=REQUEST_SECONDS + 1)
            self._server, self._thread = None, None

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()
