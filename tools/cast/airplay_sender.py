"""A stand-in for an iPhone's AirPlay video sender: just the HTTP side (docs/28-casting.md, stage C3).

    python3 -I tools/cast/airplay_sender.py <device ip> <media url> [--seconds 8]

It does what AVFoundation does to start a video on an AirPlay receiver: reads /server-info, runs the pairing handshake, opens the
event channel (`POST /reverse`, upgraded to PTTH so the receiver can post `/event` messages back), `POST /play`s an address, and then
polls /playback-info. It is a test double written from the protocol notes, so it proves the receiver does what the notes say, not what
an iPhone does: the real sender is the last check (see docs/28-casting.md section 10).
"""
import hashlib
import plistlib
import socket
import sys
import threading
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

RAW = serialization.Encoding.Raw, serialization.PublicFormat.Raw


def _public(key): return key.public_key().public_bytes(*RAW)


def _read_head(sock, buffer):
    while b'\r\n\r\n' not in buffer:
        chunk = sock.recv(4096)
        if not chunk: return None
        buffer += chunk
    head, _, rest = bytes(buffer).partition(b'\r\n\r\n')
    del buffer[:]; buffer += rest
    lines = head.decode('latin-1').split('\r\n')
    headers = {}
    for line in lines[1:]:
        k, _, v = line.partition(':'); headers[k.strip().lower()] = v.strip()
    return lines[0], headers


def _read_body(sock, buffer, headers):
    length = int(headers.get('content-length', '0'))
    while len(buffer) < length:
        chunk = sock.recv(4096)
        if not chunk: break
        buffer += chunk
    body = bytes(buffer[:length]); del buffer[:length]
    return body


class Connection:
    def __init__(self, host, port, timeout=10, protocol='HTTP/1.1'):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.buffer = bytearray()
        self.protocol = protocol         # what the request lines say; a real sender says RTSP/1.0 even for GET /info and POST /pair-setup

    def request(self, method, path, body=b'', headers=(), protocol=None):
        protocol = protocol or self.protocol
        head = '%s %s %s\r\nHost: tv\r\nUser-Agent: AirPlay/320.20\r\nContent-Length: %d\r\n' % (method, path, protocol, len(body))
        if protocol.startswith('RTSP'): self.cseq = getattr(self, 'cseq', 0) + 1; head += 'CSeq: %d\r\n' % self.cseq
        for k, v in headers: head += '%s: %s\r\n' % (k, v)
        self.sock.sendall(head.encode() + b'\r\n' + body)
        line, headers_in = _read_head(self.sock, self.buffer)
        return int(line.split(' ')[1]), headers_in, _read_body(self.sock, self.buffer, headers_in)

    def close(self):
        try: self.sock.close()
        except OSError: pass


class EventChannel:
    """`POST /reverse`: after the 101 this side serves, and the receiver posts /event requests to it."""

    def __init__(self, host, port, session):
        self.conn = Connection(host, port)
        self.events = []             # (monotonic time, state)
        self.status = None
        sock = self.conn.sock
        sock.sendall(('POST /reverse HTTP/1.1\r\nHost: tv\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\nX-Apple-Purpose: event\r\n'
                      'X-Apple-Session-ID: %s\r\nContent-Length: 0\r\n\r\n' % session).encode())
        line, headers = _read_head(sock, self.conn.buffer)
        self.status = int(line.split(' ')[1]); self.headers = headers
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self):
        sock, buffer = self.conn.sock, self.conn.buffer
        sock.settimeout(1)
        while True:
            try:
                got = _read_head(sock, buffer)
            except socket.timeout: continue
            except OSError: return
            if got is None: return
            line, headers = got
            body = _read_body(sock, buffer, headers)
            if line.startswith('POST /event'):
                try: self.events.append((time.monotonic(), plistlib.loads(body).get('state')))
                except Exception: self.events.append((time.monotonic(), '?'))
            try: sock.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n')
            except OSError: return

    def states(self): return [s for _, s in list(self.events)]

    def wait_for(self, state, seconds=15, after=0):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if state in self.states()[after:]: return True
            time.sleep(0.1)
        return False

    def close(self): self.conn.close()


class AirPlaySender:
    def __init__(self, host, port=7000, session='6C1C9B2A-0D55-4B1F-8F3B-7A1E5F000001', protocol='HTTP/1.1'):
        self.host, self.port, self.session = host, port, session
        self.control = Connection(host, port, protocol=protocol)

    def get(self, path): return self.control.request('GET', path, headers=[('X-Apple-Session-ID', self.session)])

    def server_info(self):
        status, headers, body = self.get('/server-info')
        return status, plistlib.loads(body) if body else {}

    def info(self):
        status, headers, body = self.get('/info')
        return status, plistlib.loads(body) if body else {}

    def pair(self, break_signature=False):
        """pair-setup then pair-verify M1/M2. Returns the status of the last step (200 = paired, 470 = refused)."""
        ed = ed25519.Ed25519PrivateKey.generate(); ecdh = x25519.X25519PrivateKey.generate()
        status, _, server_ed = self.control.request('POST', '/pair-setup', _public(ed), [('Content-Type', 'application/octet-stream')])
        if status != 200 or len(server_ed) != 32: return status
        self.server_key = server_ed
        status, _, m1 = self.control.request('POST', '/pair-verify', b'\x01\x00\x00\x00' + _public(ecdh) + _public(ed), [('Content-Type', 'application/octet-stream')])
        if status != 200 or len(m1) != 96: return status
        theirs = m1[:32]
        secret = ecdh.exchange(x25519.X25519PublicKey.from_public_bytes(theirs))
        self.secret = secret
        key = hashlib.sha512(b'Pair-Verify-AES-Key' + secret).digest()[:16]; iv = hashlib.sha512(b'Pair-Verify-AES-IV' + secret).digest()[:16]
        decryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).decryptor()
        signature = decryptor.update(m1[32:])
        ed25519.Ed25519PublicKey.from_public_bytes(server_ed).verify(signature, theirs + _public(ecdh))      # raises if the device signed the wrong thing
        encryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        encryptor.update(b'\0' * 64)
        mine = ed.sign(_public(ecdh) + theirs)
        if break_signature: mine = bytes([mine[0] ^ 1]) + mine[1:]
        status, _, _ = self.control.request('POST', '/pair-verify', b'\x00\x00\x00\x00' + encryptor.update(mine), [('Content-Type', 'application/octet-stream')])
        return status

    def events(self): return EventChannel(self.host, self.port, self.session)

    def play(self, url, start=0.0, seconds=None):
        body = {'Content-Location': url, 'Start-Position': float(start), 'rate': 1.0, 'uuid': self.session, 'streamType': 1}
        if seconds is not None: body['Start-Position-Seconds'] = float(seconds)
        return self.control.request('POST', '/play', plistlib.dumps(body, fmt=plistlib.FMT_BINARY),
                                    [('Content-Type', 'application/x-apple-binary-plist'), ('X-Apple-Session-ID', self.session)])[0]

    def rate(self, value): return self.control.request('POST', '/rate?value=%f' % value, headers=[('X-Apple-Session-ID', self.session)])[0]
    def scrub(self, position): return self.control.request('POST', '/scrub?position=%f' % position, headers=[('X-Apple-Session-ID', self.session)])[0]
    def stop(self): return self.control.request('POST', '/stop', headers=[('X-Apple-Session-ID', self.session)])[0]

    def playback_info(self):
        status, _, body = self.get('/playback-info')
        return plistlib.loads(body) if status == 200 and body else {}

    def position(self):
        status, _, body = self.get('/scrub')
        out = {}
        for line in body.decode().splitlines():
            k, _, v = line.partition(':'); out[k.strip()] = float(v)
        return out

    def close(self): self.control.close()


def main():
    host, url = sys.argv[1], sys.argv[2]
    seconds = float(sys.argv[sys.argv.index('--seconds') + 1]) if '--seconds' in sys.argv else 8
    sender = AirPlaySender(host)
    print('server-info', sender.server_info()); print('info', sender.info()); print('pairing', sender.pair())
    events = sender.events(); print('reverse', events.status)
    print('play', sender.play(url))
    end = time.time() + seconds
    while time.time() < end:
        info = sender.playback_info(); print({k: info.get(k) for k in ('readyToPlay', 'duration', 'position', 'rate')}, events.states())
        time.sleep(1)
    print('stop', sender.stop())


if __name__ == '__main__':
    main()
