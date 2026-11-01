"""A stand-in for a Miracast (Wi-Fi Display) Source: the part of a phone or PC that the device's Sink talks to.

    python3 -I tools/cast/wfd_source.py [--port 7236] [--seconds 60] [--loss 0.0] [--reorder] [--pattern latency|solid]

It listens on the RTSP control port (WFD Sources do; the Sink dials them), walks the Sink through M1..M7 the way Windows and
Android Sources do, and then sends RTP (payload type 33, seven TS packets per datagram) from ffmpeg's real-time MPEG-TS. The
Wi-Fi Direct link, pairing and discovery are not part of this: the test starts the Sink's session with the debug command
`miracast_dial <this machine> <port>` over the ordinary network.

Used by tools/tests/cast_miracast_device.py; also runnable by hand. Stop with Ctrl-C.
"""
import argparse
import random
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import latency_source

CRLF = b'\r\n'


def read_message(sock, buffer):
    """One RTSP message from `sock`; `buffer` is a bytearray that keeps what arrived beyond it. None at end of stream."""
    while CRLF + CRLF not in buffer:
        chunk = sock.recv(4096)
        if not chunk: return None
        buffer += chunk
    head, _, rest = bytes(buffer).partition(CRLF + CRLF)
    lines = head.decode('latin-1').split('\r\n')
    headers = {}
    for line in lines[1:]:
        k, _, v = line.partition(':')
        headers[k.strip().lower()] = v.strip()
    length = int(headers.get('content-length', '0'))
    while len(rest) < length:
        chunk = sock.recv(4096)
        if not chunk: return None
        rest += chunk
    del buffer[:]
    buffer += rest[length:]
    return {'start': lines[0], 'headers': headers, 'body': rest[:length].decode('utf-8', 'replace'),
            'response': lines[0].startswith('RTSP/'), 'method': lines[0].split(' ')[0], 'cseq': int(headers.get('cseq', '0'))}


def message(start, cseq, headers=(), body=''):
    out = '%s\r\nCSeq: %d\r\n' % (start, cseq)
    for k, v in headers: out += '%s: %s\r\n' % (k, v)
    if body: out += 'Content-Type: text/parameters\r\nContent-Length: %d\r\n' % len(body.encode())
    return (out + '\r\n').encode() + body.encode()


def parameters(body):
    out = {}
    for line in body.splitlines():
        if not line.strip(): continue
        k, sep, v = line.partition(':')
        out[k.strip()] = v.strip() if sep else ''
    return out


class WfdSource:
    def __init__(self, port=7236, loss=0.0, reorder=False, pattern='latency', real_time=True, log=print, seed=1):
        self.port, self.loss, self.reorder, self.pattern, self.real_time, self.log = port, loss, reorder, pattern, real_time, log
        self.random = random.Random(seed)
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(('0.0.0.0', port))
        self.listener.listen(1)
        self.sink_ip = None
        self.capabilities = {}            # what the Sink answered to M3
        self.sink_requests = []           # requests the Sink made after PLAY (wfd_idr_request, TEARDOWN ...)
        self.t0 = None                    # when the first media byte went out: the content clock of the latency pattern starts here
        self.sent = self.dropped = 0
        self.ready = threading.Event()
        self.playing = threading.Event()
        self.ended = threading.Event()
        self._stop = threading.Event()
        self._conn = None
        self._cseq = 0
        self._lock = threading.Lock()
        self._ffmpeg = None

    # ---- RTSP -------------------------------------------------------------------------------------------------------------

    def _send(self, data):
        with self._lock: self._conn.sendall(data)

    def _request(self, method, uri, headers=(), body=''):
        self._cseq += 1
        self._send(message('%s %s RTSP/1.0' % (method, uri), self._cseq, headers, body))
        return self._cseq

    def serve(self, timeout=120):
        """Wait for the Sink to dial in, run the handshake, stream until `stop()`; returns when the session is over."""
        self.listener.settimeout(timeout)
        self._conn, address = self.listener.accept()
        self.sink_ip = address[0]
        self._conn.settimeout(15)
        self.log('sink connected from %s' % self.sink_ip)
        buf = bytearray()
        try:
            self._request('OPTIONS', '*', [('Require', 'org.wfa.wfd1.0')])                                    # M1
            m = read_message(self._conn, buf); assert m and m['response'] and m['start'].split(' ')[1] == '200', m
            m = read_message(self._conn, buf); assert m and m['method'] == 'OPTIONS', m                          # M2
            self._send(message('RTSP/1.0 200 OK', m['cseq'], [('Public', 'org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER')]))
            wanted = ['wfd_video_formats', 'wfd_audio_codecs', 'wfd_3d_video_formats', 'wfd_content_protection', 'wfd_display_edid', 'wfd_coupled_sink',
                      'wfd_client_rtp_ports', 'wfd_I2C', 'wfd_uibc_capability', 'wfd_connector_type', 'wfd_standby_resume_capability']
            self._request('GET_PARAMETER', 'rtsp://localhost/wfd1.0', body=''.join(w + '\r\n' for w in wanted))   # M3
            m = read_message(self._conn, buf); assert m and m['response'], m
            self.capabilities = parameters(m['body'])
            self.log('sink capabilities: %s' % self.capabilities)
            ports = self.capabilities['wfd_client_rtp_ports'].split()
            client_port = int(ports[1])
            url = 'rtsp://%s/wfd1.0/streamid=0' % self._conn.getsockname()[0]
            self._request('SET_PARAMETER', 'rtsp://localhost/wfd1.0', body=(                                  # M4
                'wfd_video_formats: 00 00 01 04 00000040 00000000 00000000 00 0000 0000 00 none none\r\n'
                'wfd_audio_codecs: AAC 00000001 00\r\nwfd_presentation_URL: %s none\r\n'
                'wfd_client_rtp_ports: RTP/AVP/UDP;unicast %d 0 mode=play\r\n' % (url, client_port)))
            m = read_message(self._conn, buf); assert m and m['response'], m
            self._request('SET_PARAMETER', 'rtsp://localhost/wfd1.0', body='wfd_trigger_method: SETUP\r\n')     # M5
            m = read_message(self._conn, buf); assert m and m['response'], m
            m = read_message(self._conn, buf); assert m and m['method'] == 'SETUP', m                            # M6
            assert 'client_port=%d' % client_port in m['headers']['transport'], m
            server_port = 51000
            self._send(message('RTSP/1.0 200 OK', m['cseq'], [('Session', '6F3A1B2C;timeout=30'),
                                                              ('Transport', 'RTP/AVP/UDP;unicast;client_port=%d;server_port=%d' % (client_port, server_port))]))
            m = read_message(self._conn, buf); assert m and m['method'] == 'PLAY', m                             # M7
            assert m['headers'].get('session') == '6F3A1B2C', m
            self._send(message('RTSP/1.0 200 OK', m['cseq'], [('Session', '6F3A1B2C')]))
            self.log('PLAY accepted; streaming to %s:%d' % (self.sink_ip, client_port))
            self.ready.set()
            stream = threading.Thread(target=self._stream, args=(client_port, server_port), daemon=True)
            stream.start()
            self._conn.settimeout(1)
            last_keepalive = time.time()
            while not self._stop.is_set():
                if time.time() - last_keepalive > 8:
                    last_keepalive = time.time(); self._request('GET_PARAMETER', 'rtsp://localhost/wfd1.0')
                try: m = read_message(self._conn, buf)
                except socket.timeout: continue
                if m is None: self.log('sink closed the connection'); break
                if not m['response']:
                    self.sink_requests.append((m['method'], m['body'].strip()))
                    self._send(message('RTSP/1.0 200 OK', m['cseq']))
                    self.log('sink asked: %s %s' % (m['method'], m['body'].strip()))
                    if m['method'] == 'TEARDOWN': break
            self._stop.set()
            stream.join(3)
        finally:
            self._stop.set()
            if self._ffmpeg: self._ffmpeg.kill()
            try: self._conn.close()
            except OSError: pass
            self.ended.set()

    def trigger(self, method):
        """The Source asks the Sink to TEARDOWN / PAUSE / PLAY the way a Windows source does when its user disconnects."""
        self._request('SET_PARAMETER', 'rtsp://localhost/wfd1.0', body='wfd_trigger_method: %s\r\n' % method)

    def stop(self):
        self._stop.set()

    # ---- RTP --------------------------------------------------------------------------------------------------------------

    def _media_command(self):
        if self.pattern == 'latency':
            return latency_source.command(real_time=self.real_time, clock_log=True)
        return (['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error'] + (['-re'] if self.real_time else []) +
                ['-f', 'lavfi', '-i', 'color=c=0x303030:s=1280x720:r=30,format=yuv420p', '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo',
                 '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-g', '30', '-bf', '0', '-b:v', '2M',
                 '-x264-params', 'repeat-headers=1', '-c:a', 'aac', '-b:a', '64k', '-f', 'mpegts', '-muxdelay', '0', '-muxpreload', '0',
                 '-flush_packets', '1', '-pes_payload_size', '0', '-mpegts_flags', '+resend_headers', '-'])

    def _read_clock(self, proc):
        """`t0` is when the content clock of the latency pattern read zero on the wall clock: the smallest (arrival - stream time) over the frames."""
        for raw in proc.stderr:
            line = raw.decode('latin-1')
            if 'pts_time:' not in line: continue
            try: pts = float(line.split('pts_time:')[1].split()[0])
            except (IndexError, ValueError): continue
            origin = time.time() - pts
            if self.t0 is None or origin < self.t0: self.t0 = origin

    def _stream(self, client_port, server_port):
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.bind(('0.0.0.0', server_port))
        self._ffmpeg = subprocess.Popen(self._media_command(), stdout=subprocess.PIPE, stderr=subprocess.PIPE if self.pattern == 'latency' else subprocess.DEVNULL)
        if self.pattern == 'latency': threading.Thread(target=self._read_clock, args=(self._ffmpeg,), daemon=True).start()
        sequence, ssrc, pending, held = self.random.randrange(65536), 0x7a11cafe, b'', None
        destination = (self.sink_ip, client_port)
        try:
            while not self._stop.is_set():
                chunk = self._ffmpeg.stdout.read1(65536)
                if not chunk: break
                if self.t0 is None and self.pattern != 'latency': self.t0 = time.time()
                pending += chunk
                while len(pending) >= 188 * 7:
                    payload, pending = pending[:188 * 7], pending[188 * 7:]
                    header = struct.pack('!BBHII', 0x80, 33, sequence, int((time.time() - self.t0) * 90000) & 0xffffffff, ssrc)
                    sequence = (sequence + 1) & 0xffff
                    packet = header + payload
                    if self.loss and self.random.random() < self.loss:
                        self.dropped += 1; continue
                    if self.reorder and held is None and self.random.random() < 0.05:
                        held = packet; continue
                    udp.sendto(packet, destination); self.sent += 1
                    if held is not None: udp.sendto(held, destination); self.sent += 1; held = None
        finally:
            udp.close()
            self.playing.clear()

    def close(self):
        self._stop.set()
        try: self.listener.close()
        except OSError: pass
        if self._ffmpeg: self._ffmpeg.kill()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--port', type=int, default=7236)
    parser.add_argument('--seconds', type=float, default=60)
    parser.add_argument('--loss', type=float, default=0.0)
    parser.add_argument('--reorder', action='store_true')
    parser.add_argument('--pattern', choices=['latency', 'solid'], default='latency')
    args = parser.parse_args()
    source = WfdSource(args.port, args.loss, args.reorder, args.pattern)
    threading.Timer(args.seconds, source.stop).start()
    print('waiting for a sink on port %d' % args.port, flush=True)
    try: source.serve()
    except KeyboardInterrupt: pass
    finally: source.close()
    print('sent %d packets, dropped %d' % (source.sent, source.dropped))


if __name__ == '__main__':
    main()
