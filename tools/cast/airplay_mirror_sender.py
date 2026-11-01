"""A stand-in for an iPhone's AirPlay screen mirroring: what a sender does after the key exchange (docs/28-casting.md, stage C5).

    python3 -I tools/cast/airplay_mirror_sender.py <device ip> [--seconds 10]

It talks to a debug build that was told to answer every FairPlay unwrap with one fixed key (the debug command `airplay_testkey`), because
only Apple's code can wrap a key the way FairPlay does. Everything else is the real thing: /info, pair-setup and pair-verify, the two-step
/fp-setup (the device's own answers are checked), the RTSP SETUP with the wrapped key and then with the streams, an AES-CTR encrypted H.264 stream over TCP
with the 128-byte packet headers and NTP timestamps, AES-CBC encrypted AAC over UDP, each packet twice, the timing exchange the receiver starts, /feedback.
Written from the protocol notes: it proves the receiver does what the notes say, not what an iPhone does.
"""
import hashlib
import os
import plistlib
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

sys.path.insert(0, str(Path(__file__).resolve().parent))
import latency_source
from airplay_sender import AirPlaySender

TEST_KEY = bytes(range(0x40, 0x50))
NTP_EPOCH = 2208988800


def sha512(*parts): return hashlib.sha512(b''.join(parts)).digest()


def ntp_header_time(now):
    seconds = int(now) + NTP_EPOCH; fraction = int((now % 1) * 65536)
    return (seconds << 32) | (fraction << 16)


class Timing(threading.Thread):
    """The sender's timing port: answers the receiver's requests (type 0xD2) the way an NTP server does, and counts them."""

    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); self.sock.bind(('0.0.0.0', 0)); self.sock.settimeout(0.5)
        self.port = self.sock.getsockname()[1]; self.requests = 0; self.stopped = False

    def run(self):
        while not self.stopped:
            try: data, addr = self.sock.recvfrom(256)
            except socket.timeout: continue
            except OSError: return
            if len(data) >= 32 and data[1] == 0xD2:
                self.requests += 1
                now = time.time() + NTP_EPOCH
                reply = bytearray(32); reply[0] = 0x80; reply[1] = 0xD3; reply[2:4] = data[2:4]
                reply[8:16] = data[24:32]                                         # origin = the request's transmit time
                reply[16:24] = struct.pack('>II', int(now), int((now % 1) * 2**32)); reply[24:32] = reply[16:24]
                self.sock.sendto(bytes(reply), addr)

    def stop(self): self.stopped = True; self.sock.close()


class MirrorSender:
    def __init__(self, host, port=7000, key=TEST_KEY, stream_connection_id=0x0123456789ABCDEF):
        self.host, self.port, self.key, self.scid = host, port, key, stream_connection_id
        self.http = AirPlaySender(host, port, protocol='RTSP/1.0')       # as Apple's senders do: every request on the control connection says RTSP
        self.timing = Timing(); self.timing.start()
        self.video_socket = None; self.audio_socket = None
        self.audio_ports = None; self.video_port = None
        self.stop = threading.Event(); self.threads = []; self.procs = []
        self.t0 = None; self.frames = 0; self.audio_packets = 0
        self.iv = bytes(range(0x60, 0x70))
        self.still = threading.Event()         # while set the screen is "still": a phone sends no pictures at all then, and the stamps go on with real time
        self.still_total = 0.0

    # ---- RTSP over the control connection ------------------------------------------------------------------
    def rtsp(self, method, body=b'', headers=()):
        return self.http.control.request(method, 'rtsp://%s/1234567890' % self.host, body, list(headers), protocol='RTSP/1.0')

    def info(self):
        status, headers, body = self.http.control.request('GET', '/info')
        return status, plistlib.loads(body) if body else {}

    def fp_setup(self, mode=3):
        one = bytearray(b'FPLY\x03\x01\x01\x00\x00\x00\x00\x04\x02\x00\x00\xbb'); one[14] = mode
        status1, _, answer1 = self.http.control.request('POST', '/fp-setup', bytes(one), [('Content-Type', 'application/octet-stream')])
        message = bytearray(os.urandom(164)); message[0:12] = b'FPLY\x03\x01\x03\x00\x00\x00\x00\x98'
        status2, _, answer2 = self.http.control.request('POST', '/fp-setup', bytes(message), [('Content-Type', 'application/octet-stream')])
        return (status1, answer1), (status2, answer2, bytes(message))

    def setup_keys(self):
        body = plistlib.dumps({'ekey': os.urandom(72), 'eiv': self.iv, 'timingPort': self.timing.port, 'deviceID': '11:22:33:44:55:66',
                               'sessionUUID': 'E2D5A6F0-0000-4000-8000-000000000001', 'model': 'iPhone14,5', 'name': 'Test iPhone'}, fmt=plistlib.FMT_BINARY)
        status, headers, answer = self.rtsp('SETUP', body, [('Content-Type', 'application/x-apple-binary-plist')])
        return status, (plistlib.loads(answer) if status == 200 and answer else {})

    def setup_streams(self, video=True, audio=True):
        streams = []
        if audio: streams.append({'type': 96, 'ct': 4, 'sr': 44100, 'channels': 2, 'spf': 1024, 'audioFormat': 0x400000})
        if video: streams.append({'type': 110, 'streamConnectionID': self.scid})
        status, headers, answer = self.rtsp('SETUP', plistlib.dumps({'streams': streams}, fmt=plistlib.FMT_BINARY), [('Content-Type', 'application/x-apple-binary-plist')])
        out = plistlib.loads(answer) if status == 200 and answer else {}
        for s in out.get('streams', []):
            if s['type'] == 110: self.video_port = s['dataPort']
            if s['type'] == 96: self.audio_ports = (s['dataPort'], s['controlPort'])
        return status, out

    def record(self): return self.rtsp('RECORD')[0]
    def feedback(self): return self.http.control.request('POST', '/feedback')[0]
    def set_volume(self, db): return self.rtsp('SET_PARAMETER', ('volume: %f\r\n' % db).encode(), [('Content-Type', 'text/parameters')])[0]
    def teardown(self, types=None):
        body = plistlib.dumps({'streams': [{'type': t} for t in types]}, fmt=plistlib.FMT_BINARY) if types else b''
        return self.rtsp('TEARDOWN', body)[0]

    # ---- the streams ------------------------------------------------------------------------------------------
    def start_media(self, video=True, audio=True):
        if video:
            t = threading.Thread(target=self._video, daemon=True); t.start(); self.threads.append(t)
        if audio:
            t = threading.Thread(target=self._audio, daemon=True); t.start(); self.threads.append(t)

    def _video(self):
        cipher = Cipher(algorithms.AES(sha512(b'AirPlayStreamKey%d' % self.scid, sha512(self.key, self.http.secret)[:16])[:16]),
                        modes.CTR(sha512(b'AirPlayStreamIV%d' % self.scid, sha512(self.key, self.http.secret)[:16])[:16])).encryptor()
        sock = socket.create_connection((self.host, self.video_port), timeout=10); self.video_socket = sock
        proc = subprocess.Popen(latency_source.h264_command(clock_log=True), stdout=subprocess.PIPE, stderr=subprocess.PIPE); self.procs.append(proc)
        threading.Thread(target=self._clock, args=(proc,), daemon=True).start()
        buffer = b''; configured = False; started = None; still_since = None
        try:
            while not self.stop.is_set():
                chunk = proc.stdout.read1(65536)
                if not chunk: break
                buffer += chunk
                units = self._split(buffer)
                if not units: continue
                *complete, buffer = units
                for unit in complete:
                    if self.still.is_set():
                        if still_since is None: still_since = time.time()
                        continue                                      # a still screen sends nothing; what the encoder made meanwhile is dropped
                    if still_since is not None: self.still_total += time.time() - still_since; still_since = None
                    nals = self._nals(unit)
                    sps = next((n for n in nals if n[0] & 31 == 7), None); pps = next((n for n in nals if n[0] & 31 == 8), None)
                    if sps and pps and not configured:
                        record = bytes([1, sps[1], sps[2], sps[3], 0xFF, 0xE1]) + struct.pack('>H', len(sps)) + sps + b'\x01' + struct.pack('>H', len(pps)) + pps
                        sock.sendall(self._header(len(record), 1, 0) + record); configured = True
                    plain = b''.join(struct.pack('>I', len(n)) + n for n in nals if n[0] & 31 not in (7, 8, 9))
                    if not plain or not configured: continue
                    body = cipher.update(plain)
                    # The time a frame is stamped with is when it was made, not when ffmpeg's output buffer let it go: frames come out in bursts.
                    if started is None: started = time.time()
                    sock.sendall(self._header(len(body), 0, ntp_header_time(started + self.frames / 30.0 + self.still_total)) + body); self.frames += 1
        except OSError: pass
        finally:
            try: proc.kill()
            except OSError: pass

    def _clock(self, proc):
        for raw in proc.stderr:
            line = raw.decode('latin-1')
            if 'pts_time:' not in line: continue
            try: pts = float(line.split('pts_time:')[1].split()[0])
            except (IndexError, ValueError): continue
            origin = time.time() - pts
            if self.t0 is None or origin < self.t0: self.t0 = origin

    @staticmethod
    def _header(size, kind, ntp):
        h = bytearray(128); h[0:4] = struct.pack('<I', size); h[4] = kind; h[8:16] = struct.pack('<Q', ntp)
        return bytes(h)

    @staticmethod
    def _split(data):
        """Cuts an Annex-B stream at its access unit delimiters; the last piece may be incomplete."""
        marks = []; i = 0
        while True:
            i = data.find(b'\x00\x00\x00\x01\x09', i)
            if i < 0: break
            marks.append(i); i += 5
        if len(marks) < 2: return None
        pieces = [data[a:b] for a, b in zip(marks, marks[1:] + [len(data)])]
        return pieces

    @staticmethod
    def _nals(unit):
        out = []; i = 0; start = None
        while i + 3 <= len(unit):
            if unit[i:i + 3] == b'\x00\x00\x01': 
                if start is not None: out.append(unit[start:i].rstrip(b'\x00') if False else unit[start:i])
                i += 3; start = i
            else: i += 1
        if start is not None and start < len(unit): out.append(unit[start:])
        return [n[:-1] if n.endswith(b'\x00') and False else n for n in out]

    def _audio(self):
        key = sha512(self.key, self.http.secret)[:16]
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); self.audio_socket = udp
        proc = subprocess.Popen(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100', '-ac', '2',
                                 '-c:a', 'aac', '-b:a', '96k', '-f', 'adts', '-'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL); self.procs.append(proc)
        buffer = b''; sequence = 1000; timestamp = 5000
        try:
            while not self.stop.is_set():
                chunk = proc.stdout.read1(4096)
                if not chunk: break
                buffer += chunk
                while len(buffer) >= 7:
                    if buffer[0] != 0xFF: buffer = buffer[1:]; continue
                    length = ((buffer[3] & 3) << 11) | (buffer[4] << 3) | (buffer[5] >> 5)
                    if length < 7 or len(buffer) < length: break
                    frame = buffer[7:length]; buffer = buffer[length:]
                    whole = len(frame) // 16 * 16
                    encryptor = Cipher(algorithms.AES(key), modes.CBC(self.iv)).encryptor()
                    sealed = (encryptor.update(frame[:whole]) + encryptor.finalize() if whole else b'') + frame[whole:]
                    packet = struct.pack('>BBHII', 0x80, 0x60, sequence & 0xFFFF, timestamp & 0xFFFFFFFF, 0x1234) + sealed
                    for _ in range(2): udp.sendto(packet, (self.host, self.audio_ports[0]))
                    sequence += 1; timestamp += 1024; self.audio_packets += 1
        except OSError: pass
        finally:
            try: proc.kill()
            except OSError: pass

    def stop_media(self):
        """Ends the streams (the way a sender does before it plays a full-screen video) and makes ready for new ones with a new stream connection id."""
        self.stop.set()
        for p in self.procs:
            try: p.kill()
            except OSError: pass
        for s_ in (self.video_socket, self.audio_socket):
            try: s_ and s_.close()
            except OSError: pass
        for t in self.threads: t.join(3)
        self.threads = []; self.procs = []; self.video_socket = self.audio_socket = None
        self.stop = threading.Event(); self.frames = 0; self.audio_packets = 0; self.t0 = None; self.scid += 1

    def close(self):
        self.stop.set()
        for p in self.procs:
            try: p.kill()
            except OSError: pass
        for s in (self.video_socket, self.audio_socket):
            try: s and s.close()
            except OSError: pass
        self.timing.stop(); self.http.close()


def main():
    host = sys.argv[1]; seconds = float(sys.argv[sys.argv.index('--seconds') + 1]) if '--seconds' in sys.argv else 10
    sender = MirrorSender(host)
    print('info', sender.info()[0], 'pair', sender.http.pair(), 'fp-setup', [(a[0], len(a[1])) for a in sender.fp_setup()[:1]])
    print('setup keys', sender.setup_keys()); print('setup streams', sender.setup_streams()); print('record', sender.record())
    sender.start_media(); time.sleep(seconds); print('frames', sender.frames, 'audio', sender.audio_packets, 'timing requests', sender.timing.requests)
    print('teardown', sender.teardown()); sender.close()


if __name__ == '__main__':
    main()
