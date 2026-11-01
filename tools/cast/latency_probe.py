"""Glass-to-glass latency of a real-time TS stream through the engine (casting plan C0, docs/28-casting.md).

    python3 -I tools/cast/latency_probe.py <low-latency profile: true|false> [samples]

The source (latency_source.py) draws its own content clock in milliseconds as a row of white boxes; every screenshot of the
device is decoded back into that clock. Latency = the moment of the screenshot - (the moment the stream started + that clock).
The content clock's origin is read from ffmpeg's own per-frame report (see read_clock), not from the first byte: the first byte comes half a second after stream time zero was due. The screenshot instant is the arrival of the first byte of a raw `screencap` (it grabs the frame, then writes the header), so the\nremaining error is the clock of the source (+-30 ms) and the adb hop (a few ms).
The stream goes through `adb reverse` (the device cannot reach the Mac's subnet), which needs the USB transport on this setup.
"""
import os, statistics, subprocess, sys, threading, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
sys.path.insert(0, str(Path(__file__).resolve().parent))
import latency_source as F

SERIAL, PORT = os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'), 8097
state = {'t0': None}

def read_clock(proc):
    """When did the source's content clock read zero on the wall clock? ffmpeg runs half a second ahead of its own pacing (-re allows an initial
    burst), so it is not when the first byte left: it is the smallest (arrival - stream time) of the frames it reports through `showinfo`."""
    for raw in proc.stderr:
        line = raw.decode('latin-1')
        if 'pts_time:' not in line: continue
        try: pts = float(line.split('pts_time:')[1].split()[0])
        except (IndexError, ValueError): continue
        origin = time.time() - pts
        if state['t0'] is None or origin < state['t0']: state['t0'] = origin

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path != '/mirror.ts': self.send_error(404); return
        proc = subprocess.Popen(F.command(clock_log=True), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        threading.Thread(target=read_clock, args=(proc,), daemon=True).start()
        self.send_response(200); self.send_header('Content-Type', 'video/mp2t'); self.send_header('Connection', 'close'); self.end_headers()
        try:
            while True:
                chunk = proc.stdout.read1(4096)
                if not chunk: break
                self.wfile.write(chunk); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError): pass
        finally: proc.kill()

def adb(*a, **kw): return subprocess.run(['adb', '-s', SERIAL, *a], capture_output=True, **kw)
def dbg(command, *extra): adb('shell', 'am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', command, *extra)

def decode(raw):
    """`screencap` without -p: a header (width, height, format[, colour space]) and RGBA pixels, no PNG compression to wait for."""
    w, h = int.from_bytes(raw[0:4], 'little'), int.from_bytes(raw[4:8], 'little')
    head = len(raw) - w * h * 4
    if not (0 < w <= 4096 and 0 < h <= 4096 and head in (12, 16)): return None
    px = lambda x, y: raw[head + (int(y) * w + int(x)) * 4]          # red channel: the picture is black and white
    my = next((y for y in range(0, min(h, 300)) if any(px(x, y) > 200 for x in range(0, min(w, 120)))), None)
    if my is None: return None
    mx = next(x for x in range(0, min(w, 120)) if px(x, my) > 200)
    run = 0
    while mx + run < w and px(mx + run, my) > 200: run += 1
    scale = run / F.SIZE
    if not 0.5 < scale < 1.6: return None
    bits = [1 if px(mx + (F.STEP * (k + 1) + F.SIZE / 2) * scale, my + F.SIZE / 2 * scale) > 128 else 0 for k in range(F.BITS)]
    return sum(b << k for k, b in enumerate(bits)), scale

def main():
    low, n = sys.argv[1] == 'true', int(sys.argv[2]) if len(sys.argv) > 2 else 14
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler); threading.Thread(target=server.serve_forever, daemon=True).start()
    adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    dbg('theme', '--es', 'value', 'DARK'); dbg('lux', '--ef', 'value', '200.0'); dbg('hold_attention', '--es', 'value', 'AWAKE')
    dbg('orientation', '--ei', 'value', '0'); time.sleep(1.5)
    dbg('page', '--es', 'value', 'player.video'); dbg('media_stop'); time.sleep(1)
    dbg('media_lowlatency', '--es', 'value', 'true' if low else 'false')
    dbg('media_load', '--es', 'url', 'http://127.0.0.1:%d/mirror.ts' % PORT, '--es', 'kind', 'video', '--es', 'type', 'progressive')
    time.sleep(9)
    rows = []
    for i in range(n):
        # `screencap` grabs the frame first and only then writes the header: the first byte marks the instant the picture was taken.
        t1 = time.time()
        proc = subprocess.Popen(['adb', '-s', SERIAL, 'exec-out', 'screencap'], stdout=subprocess.PIPE)
        head = proc.stdout.read(12); t_grab = time.time()
        raw = head + proc.stdout.read(); proc.wait(); t2 = time.time()
        got = decode(raw)
        if got and state['t0']:
            ms, scale = got
            rows.append((t_grab - (state['t0'] + ms / 1000.0), t_grab - t1, ms))
            print('sample %2d  content %6d ms  latency %5.0f ms  (frame grabbed %3.0f ms after the command)' % (i, ms, rows[-1][0] * 1000, rows[-1][1] * 1000), flush=True)
        else: print('sample %2d  not decodable' % i, flush=True)
        time.sleep(1.4)
    dbg('media_stop'); dbg('media_lowlatency', '--es', 'value', 'false'); dbg('hold_attention'); dbg('lux'); dbg('theme'); dbg('orientation', '--ei', 'value', '10')
    server.shutdown()
    if rows:
        lat = [r[0] * 1000 for r in rows]
        print('low-latency profile: %s | %d samples | latency median %.0f ms, min %.0f, max %.0f | grab delay median %.0f ms' % (
            low, len(lat), statistics.median(lat), min(lat), max(lat), statistics.median(r[1] for r in rows) * 1000))
if __name__ == '__main__':
    main()
