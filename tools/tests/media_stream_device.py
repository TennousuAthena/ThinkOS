#!/usr/bin/env python3
"""Network streaming on the device: progressive HTTP, headers, redirects, retries, HLS, DASH, live and subtitles.

    python3 tools/tests/media_stream_device.py [--samples /tmp/thinkos-media-samples] [--only hls] [--no-live]

It starts tools/media/serve.py itself and reaches it over `adb reverse` (the device's Wi-Fi is on another subnet
than the Mac); the loopback address counts as private, so the cleartext rule is exercised exactly as on a LAN.
Results go to artifacts/streaming/stream-matrix.json; the exit status is non-zero if any case failed.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device

PORT = 8099
BASE = 'http://127.0.0.1:%d' % PORT
MOVIE = 'video-h264-480p30.mp4'
MOVIE_720 = 'video-h264-720p30.mp4'


class Origin:
    def __init__(self, samples):
        self.samples = samples
        self.process = None

    def start(self):
        if self.process and self.process.poll() is None: return
        # The origin's own log (requests that failed, ffmpeg restarts) is the other half of every stall seen on the device.
        self.log = open(os.environ.get('THINKOS_ORIGIN_LOG', '/tmp/thinkos-media-origin.log'), 'ab')
        self.process = subprocess.Popen([sys.executable, '-I', str(ROOT / 'tools/media/serve.py'), '--root', str(self.samples), '--port', str(PORT)],
                                        stdout=self.log, stderr=self.log)
        for _ in range(50):
            try:
                if urllib.request.urlopen(BASE + '/health', timeout=1).read().strip() == b'ok': return
            except OSError: time.sleep(0.2)
        raise RuntimeError('serve.py did not come up')

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(10)
            except subprocess.TimeoutExpired: self.process.kill()

    def warm(self, path, seconds=25):
        """Live streams need a few seconds before their first playlist exists: ask until it answers."""
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            try:
                urllib.request.urlopen(BASE + path, timeout=10).read()
                return True
            except OSError: time.sleep(1)
        return False


class RtspOrigin:
    """tools/media/rtsp_tcp.py: an RTSP source that speaks interleaved TCP, the transport the engine insists on."""
    PORT = 8554

    def __init__(self, sample, auth=None):
        self.command = [sys.executable, '-I', str(ROOT / 'tools/media/rtsp_tcp.py'), str(sample), '--port', str(self.PORT), '--bind', '127.0.0.1']
        if auth: self.command += ['--auth', auth]
        self.process = None

    def start(self):
        self.process = subprocess.Popen(self.command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        import socket
        for _ in range(60):
            try:
                socket.create_connection(('127.0.0.1', self.PORT), timeout=0.5).close()
                return
            except OSError: time.sleep(0.3)
        raise RuntimeError('rtsp_tcp.py did not come up')

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(10)
            except subprocess.TimeoutExpired: self.process.kill()
        self.process = None


class Runner:
    def __init__(self, device, origin):
        self.device = device
        self.origin = origin
        self.results = []

    def observe(self, seconds, interval=1.0, until=None):
        """Poll the engine for [seconds]; stop early when `until(state)` is true. Returns every state seen."""
        seen = []
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            state = self.device.state()
            if state is not None:
                seen.append(state)
                if until and until(state): break
            time.sleep(interval)
        return seen

    def play(self, url, kind='video', seconds=20, **extras):
        mark = self.device.load(url, kind=kind, **extras)
        first = []

        def done(state):
            if state['status'] == 'error': return True
            first.append(state['position']) if state['status'] == 'playing' else None
            return bool(first) and state['status'] == 'playing' and state['position'] - first[0] >= 2500
        seen = self.observe(seconds, 0.7, done)
        return seen, self.device.media_log(mark)

    def record(self, name, passed, detail):
        self.results.append({'case': name, 'pass': bool(passed), 'detail': detail})
        print(('PASS ' if passed else 'FAIL ') + name + ('  ' + str(detail) if detail else ''), flush=True)
        self.device.debug('media_stop')
        time.sleep(0.5)

    def expect_play(self, name, url, kind='video', seconds=25, check=None, **extras):
        seen, log = self.play(url, kind, seconds, **extras)
        last = seen[-1] if seen else {}
        played = last.get('status') == 'playing' and last.get('position', 0) >= 2500
        extra = check(seen) if (check and played) else ''
        ok = played and not extra
        self.record(name, ok, extra or {'status': last.get('status'), 'pos': last.get('position'), 'source': last.get('source'),
                                         'decoder': last.get('videoDecoder') or last.get('audioDecoder'), 'error': last.get('error')})
        return seen

    def expect_error(self, name, url, category, status=None, kind='video', seconds=30, **extras):
        seen, log = self.play(url, kind, seconds, **extras)
        last = seen[-1] if seen else {}
        error = last.get('error') or {}
        ok = last.get('status') == 'error' and error.get('category') == category and (status is None or error.get('status') == status)
        self.record(name, ok, {'status': last.get('status'), 'error': {k: error.get(k) for k in ('category', 'status', 'name', 'message')},
                               'retries_seen': max([s.get('retry', 0) for s in seen] or [0])})
        return seen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/streaming')
    parser.add_argument('--only', default='')
    parser.add_argument('--no-live', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    device.prepare_display()
    device.open_page('player.video')
    time.sleep(1.5)
    run = Runner(device, origin)
    want = lambda name: args.only in name

    if want('progressive'):
        run.expect_play('progressive http 480p', '%s/files/%s' % (BASE, MOVIE))
        run.expect_play('progressive http seeks with Range', '%s/files/%s' % (BASE, MOVIE_720), start=6000,
                        check=lambda seen: '' if seen[-1]['position'] >= 8000 else 'did not start at 6 s: %s' % seen[-1]['position'])
        run.expect_play('audio over http (mp3)', '%s/files/audio-mp3-cbr192.mp3' % BASE, kind='audio')
    if want('headers'):
        run.expect_play('Authorization header reaches the origin', '%s/auth/files/%s' % (BASE, MOVIE), headers=json.dumps({'Authorization': 'Bearer thinkos-test'}))
        run.expect_error('missing Authorization is a plain 401 (no retry storm)', '%s/auth/files/%s' % (BASE, MOVIE), 'http', 401)
        run.expect_play('Referer header', '%s/referer/files/%s' % (BASE, MOVIE), headers=json.dumps({'Referer': 'https://example.test/'}))
        run.expect_error('missing Referer is 403', '%s/referer/files/%s' % (BASE, MOVIE), 'http', 403)
        run.expect_play('custom User-Agent', '%s/ua/files/%s' % (BASE, MOVIE), headers=json.dumps({'User-Agent': 'ThinkOS-Test/1.0'}))
    if want('redirect'):
        run.expect_play('3 redirects are followed', '%s/redirect/3/files/%s' % (BASE, MOVIE))
        run.expect_error('4 redirects are refused', '%s/redirect/4/files/%s' % (BASE, MOVIE), 'network')
    if want('errors'):
        run.expect_error('404 is final', '%s/files/does-not-exist.mp4' % BASE, 'http', 404)
        run.expect_error('plain HTTP to a public host is refused', 'http://example.com/video.mp4', 'policy')
        run.expect_error('a corrupt file is a format error', '%s/files/lyrics-sample.lrc' % BASE, 'format', kind='audio')
    if want('flaky'):
        seen = run.expect_play('connection cut mid-file is retried', '%s/flaky/600000/files/%s' % (BASE, MOVIE_720), seconds=45)
        run.results[-1]['detail'] = {**(run.results[-1]['detail'] if isinstance(run.results[-1]['detail'], dict) else {}),
                                     'max_retry_seen': max([s.get('retry', 0) for s in seen] or [0]),
                                     'reconnecting_seen': any(s['status'] == 'reconnecting' for s in seen)}
    if want('slow'):
        def buffering(seen):
            return '' if any(s['status'] == 'buffering' for s in seen) else 'never buffered on a throttled link'
        run.expect_play('throttled link buffers then plays', '%s/slow/700/files/%s' % (BASE, MOVIE_720), seconds=60, check=buffering)
    if want('server-error'):
        seen = run.expect_error('503 is retried with backoff, then reported', '%s/status/503' % BASE, 'http', 503, seconds=80)
        run.results[-1]['pass'] = run.results[-1]['pass'] and any(s['status'] == 'reconnecting' for s in seen)
    if want('hls'):
        run.expect_play('HLS VOD (TS segments)', '%s/hls/%s/index.m3u8' % (BASE, MOVIE_720), check=lambda seen: '' if seen[-1]['source'] == 'hls' else 'source=%s' % seen[-1]['source'])
        run.expect_play('HLS VOD (fMP4 segments)', '%s/hls-fmp4/%s/index.m3u8' % (BASE, MOVIE_720))
        run.expect_play('HLS AES-128', '%s/hls-aes/%s/index.m3u8' % (BASE, MOVIE_720))
        run.expect_play('HLS master with two renditions', '%s/hls/%s/master.m3u8' % (BASE, 'video-h264-1080p30-high.mp4'), seconds=40,
                        check=lambda seen: '' if 0 < seen[-1]['videoHeight'] <= 1088 else 'picked %s' % seen[-1]['videoHeight'])
    if want('dash'):
        run.expect_play('DASH VOD', '%s/dash/%s/manifest.mpd' % (BASE, MOVIE_720), check=lambda seen: '' if seen[-1]['source'] == 'dash' else 'source=%s' % seen[-1]['source'])
    if want('live') and not args.no_live:
        origin.warm('/live/hls/index.m3u8')
        seen = run.expect_play('live HLS', '%s/live/hls/index.m3u8' % BASE, seconds=40,
                               check=lambda seen: '' if seen[-1]['live'] else 'live flag missing')
        origin.warm('/live/dash/manifest.mpd')
        run.expect_play('live DASH', '%s/live/dash/manifest.mpd' % BASE, seconds=40, check=lambda seen: '' if seen[-1]['live'] else 'live flag missing')
    if want('live-window') and not args.no_live:
        origin.warm('/live/hls/index.m3u8')
        device.load('%s/live/hls/index.m3u8' % BASE, kind='video')
        run.observe(20, 1.0, lambda s: s['status'] == 'playing' and s['position'] > 4000)
        device.debug('media_pause')
        time.sleep(45)   # longer than the sliding window: the playhead falls out of it
        device.debug('media_play')
        seen = run.observe(40, 1.0, lambda s: s['status'] == 'playing' and s['position'] > 0)
        last = seen[-1] if seen else {}
        run.record('live HLS recovers after falling out of the window', last.get('status') == 'playing' and not last.get('error'),
                   {'status': last.get('status'), 'error': last.get('error')})
    if want('outage'):
        origin.start()
        device.load('%s/files/%s' % (BASE, 'video-h264-1080p30-high.mp4'), kind='video', repeat='one')
        run.observe(15, 1.0, lambda s: s['status'] == 'playing' and s['position'] > 2000)
        origin.stop()
        saw = run.observe(40, 1.0, lambda s: s['status'] in ('reconnecting', 'error'))
        origin.start()
        recovered = run.observe(45, 1.0, lambda s: s['status'] == 'playing')
        last = recovered[-1] if recovered else {}
        run.record('server outage ends in reconnect, then playback resumes (or a clear error)',
                   last.get('status') == 'playing' or (last.get('status') == 'error' and (last.get('error') or {}).get('message')),
                   {'during': saw[-1]['status'] if saw else None, 'after': last.get('status'), 'error': last.get('error')})
    if want('subtitles'):
        device.push(args.samples / 'sub-base.mp4', '/sdcard/Movies/sub-base.mp4')
        seen = run.expect_play('sidecar SRT over http', '%s/files/sub-base.mp4' % BASE, subtitle='%s/files/sub-sidecar.srt' % BASE,
                               check=lambda seen: '' if seen[-1]['textTracks'] else 'no text track listed')
        run.expect_play('sidecar VTT over http', '%s/files/sub-base.mp4' % BASE, subtitle='%s/files/sub-sidecar.vtt' % BASE,
                        check=lambda seen: '' if seen[-1]['textTracks'] else 'no text track listed')

    if want('https'):
        # Public HTTPS sources: this is the one place where the device's old TLS stack (Android 8.1) meets real servers.
        # A source that the Mac or the device cannot even open a TCP connection to is skipped: that says something about
        # the network between them and the internet, not about the player (the Mux test host, for one, is unreachable
        # from some LANs).
        for name, url, kind in [('HTTPS progressive MP4', 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4', 'video'),
                                ('HTTPS HLS (Apple bipbop, master playlist)', 'https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8', 'video'),
                                ('HTTPS DASH (Shaka demo, Angel One)', 'https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd', 'video'),
                                ('HTTPS HLS (Mux test stream)', 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8', 'video')]:
            try: urllib.request.urlopen(url, timeout=8).read(1)
            except OSError as error:
                print('SKIP %s  (not reachable from the Mac: %s)' % (name, error), flush=True)
                continue
            host = url.split('/')[2]
            if 'connected' not in device.shell('sh', '-c', 'echo | nc -w 6 %s 443 && echo connected' % host, check=False):
                print('SKIP %s  (the device cannot open a connection to %s)' % (name, host), flush=True)
                continue
            run.expect_play(name, url, kind, seconds=45)
    if want('rtsp'):
        device.adb('reverse', 'tcp:%d' % RtspOrigin.PORT, 'tcp:%d' % RtspOrigin.PORT)
        rtsp = RtspOrigin(args.samples / MOVIE_720)
        rtsp.start()
        try:
            run.expect_play('RTSP over interleaved TCP', 'rtsp://127.0.0.1:%d/live' % RtspOrigin.PORT, seconds=30,
                            check=lambda seen: '' if seen[-1]['source'] == 'rtsp' and seen[-1]['live'] else 'source=%s live=%s' % (seen[-1]['source'], seen[-1]['live']))
            run.expect_error('RTSP to a public host is refused', 'rtsp://example.com:8554/live', 'policy', seconds=20)
        finally: rtsp.stop()
        rtsp = RtspOrigin(args.samples / MOVIE_720, auth='thinkos:s3cret-t0ken')
        rtsp.start()
        try:
            mark = device.log_mark()
            run.expect_play('RTSP with credentials in the URL', 'rtsp://thinkos:s3cret-t0ken@127.0.0.1:%d/live' % RtspOrigin.PORT, seconds=30)
            leaked = [line for line in device.log_since(mark) if 's3cret-t0ken' in line]
            run.record('RTSP credentials never reach the log', not leaked, leaked[:2])
            run.expect_error('RTSP with the wrong password is a final error', 'rtsp://thinkos:wrong@127.0.0.1:%d/live' % RtspOrigin.PORT, 'http', seconds=30)
        finally: rtsp.stop()
        rtsp = RtspOrigin(args.samples / MOVIE_720)
        rtsp.start()
        try:
            device.load('rtsp://127.0.0.1:%d/live' % RtspOrigin.PORT, kind='video')
            run.observe(20, 1.0, lambda s: s['status'] == 'playing' and s['position'] > 2000)
            rtsp.stop()
            saw = run.observe(40, 1.0, lambda s: s['status'] in ('reconnecting', 'error'))
            rtsp.start()
            after = run.observe(45, 1.0, lambda s: s['status'] == 'playing' and s['position'] > 0)
            last = after[-1] if after else {}
            run.record('RTSP source restart ends in reconnect or a clear error',
                       last.get('status') == 'playing' or bool((last.get('error') or {}).get('message')),
                       {'during': saw[-1]['status'] if saw else None, 'after': last.get('status'), 'error': last.get('error')})
        finally: rtsp.stop()

    failed = [r for r in run.results if not r['pass']]
    (args.output / 'stream-matrix.json').write_text(json.dumps(run.results, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('\n%d cases, %d failed -> %s' % (len(run.results), len(failed), args.output / 'stream-matrix.json'))
    device.debug('media_stop')
    origin.stop()
    device.stop_log()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
