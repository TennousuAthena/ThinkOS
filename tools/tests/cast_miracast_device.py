#!/usr/bin/env python3
"""Miracast (Wi-Fi Display) reception on the device, against a scripted Source (docs/28-casting.md, stage C4).

    python3 -I tools/tests/cast_miracast_device.py [--serial HUA0BWUT]

tools/cast/wfd_source.py plays the phone or PC: it waits on the RTSP control port, the debug build's `miracast_dial` command makes the
device's Sink dial it (the Wi-Fi Direct pairing is not part of this test, only everything after it), and then M1..M7 and an RTP/MPEG-TS
stream run over the ordinary network. What is checked: the handshake and what the Sink offers, picture and sound arriving in the engine
as the `cast:miracast` session, how late the picture is (the source draws its clock into the picture), packet loss and reordering,
IDR requests, and every way a session ends. The Mac and the device must be on the same network.
"""
import argparse
import os
from pathlib import Path
import re
import socket
import statistics
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools/cast'))
from media_device import Device
from wfd_source import WfdSource
import latency_probe

PORT = 7236


def local_ip_towards(address):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((address, 9)); ip = s.getsockname()[0]; s.close()
    return ip


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def until(test, seconds=15, interval=0.4):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = test()
            if value: return value
            time.sleep(interval)
        return None

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        m = re.search(r'data="([^"]*)"', out)
        return m.group(1).split('|')[0] if m else ''

    def state(): return device.state() or {}

    device_ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', device.shell('ip', '-4', 'addr', 'show', 'wlan0')).group(1)
    mac_ip = local_ip_towards(device_ip)
    print('device %s, this machine %s' % (device_ip, mac_ip), flush=True)

    def session(**options):
        """A Source that the device is made to dial; returns once it is serving."""
        source = WfdSource(PORT, log=lambda text: print('   source:', text, flush=True), **options)
        thread = threading.Thread(target=lambda: source.serve(60) if True else None, daemon=True)
        thread.start()
        mark = device.log_mark()
        device.debug('miracast_dial', host=mac_ip, port=PORT)
        return source, thread, mark

    def finish(source, thread):
        source.stop(); thread.join(8); source.close()
        device.debug('media_stop')
        time.sleep(1.2)

    def logs(mark): return [l for l in device.log_since(mark) if 'Miracast' in l]

    try:
        device.prepare_display()
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', '0')
        device.debug('media_stop'); time.sleep(1)

        # --- the handshake and a picture -----------------------------------------------------------------------
        source, thread, mark = session()
        check('the Source is dialled and walked through M1..M7 and PLAY', source.ready.wait(20))
        caps = source.capabilities
        check('the Sink offers AAC audio only, no HDCP, no UIBC', caps.get('wfd_audio_codecs') == 'AAC 00000001 00' and caps.get('wfd_content_protection') == 'none'
              and caps.get('wfd_uibc_capability') == 'none', {k: caps.get(k) for k in ('wfd_audio_codecs', 'wfd_content_protection', 'wfd_uibc_capability')})
        video = caps.get('wfd_video_formats', '').split()
        check('and 720p only up to level 4.0 (no 1080p the panel cannot use)', len(video) >= 5 and video[4] == '00000061' and video[3] == '04', video[:5])
        check('the RTP port it asks for is a real one', re.match(r'RTP/AVP/UDP;unicast (\d+) 0 mode=play', caps.get('wfd_client_rtp_ports', '')) is not None, caps.get('wfd_client_rtp_ports'))
        ok = until(lambda: state().get('status') == 'playing' and state().get('videoWidth', 0) > 0, 25)
        s = state()
        check('the engine plays it as the cast:miracast session', bool(ok) and s.get('owner') == 'cast:miracast', (s.get('owner'), s.get('status')))
        check('as a live, unseekable video of 1280x720', s.get('mirror') is True and s.get('live') is True and s.get('seekable') is False and s.get('kind') == 'video'
              and (s.get('videoWidth'), s.get('videoHeight')) == (1280, 720), {k: s.get(k) for k in ('mirror', 'live', 'seekable', 'kind', 'videoWidth', 'videoHeight')})
        check('with sound, from the AAC track', s.get('sampleRate', 0) == 48000 and s.get('channels', 0) == 2, (s.get('sampleRate'), s.get('channels')))
        check('the cast page took the screen', page() == 'cast.video', page())

        # --- how late the picture is -----------------------------------------------------------------------------
        # The first seconds are spent catching up: the player holds what arrived while the decoders were starting, and plays a little faster until it is gone.
        check('the player caught up to the live edge within 20 s', bool(until(lambda: state().get('buffered', 0) - state().get('position', 0) < 450, 20, 0.5)),
              state().get('buffered', 0) - state().get('position', 0))
        time.sleep(2)
        rows = []
        for i in range(12):
            t1 = time.time()
            proc = subprocess.Popen(['adb', '-s', args.serial, 'exec-out', 'screencap'], stdout=subprocess.PIPE)
            head = proc.stdout.read(12); t_grab = time.time()
            raw = head + proc.stdout.read(); proc.wait()
            got = latency_probe.decode(raw)
            if got and source.t0: rows.append((t_grab - (source.t0 + got[0] / 1000.0)) * 1000)
            time.sleep(1.1)
        median = statistics.median(rows) if rows else None
        check('the picture could be read back %d times out of 12' % len(rows), len(rows) >= 8, len(rows))
        check('and it is no more than 600 ms behind the source (median)', median is not None and median < 600, 'median %.0f ms, min %.0f, max %.0f' % (median or -1, min(rows or [-1]), max(rows or [-1])))
        check('the clock never seemed to run backwards or stall for seconds', rows and max(rows) < 1500, 'max %.0f' % max(rows or [-1]))
        finish(source, thread)
        check('stopping ends the session and gives the screen back', state().get('status') in ('stopped', None) and page() != 'cast.video', (state().get('status'), page()))

        # --- loss and reordering ---------------------------------------------------------------------------------
        source, thread, mark = session(loss=0.02)
        source.ready.wait(20); until(lambda: state().get('status') == 'playing', 20)
        time.sleep(14)
        s = state()
        check('with 2% of the packets lost the picture goes on', s.get('status') == 'playing' and s.get('owner') == 'cast:miracast', s.get('status'))
        check('the Sink asked the Source for a new IDR frame', ('SET_PARAMETER', 'wfd_idr_request') in source.sink_requests, len(source.sink_requests))
        check('and no more than twice a second', len(source.sink_requests) <= 14 * 2 + 2, len(source.sink_requests))
        finish(source, thread)
        said = [l for l in logs(mark) if 'packets received' in l]
        m = re.search(r'(\d+) packets received, (\d+) lost', said[-1]) if said else None
        check('the loss was counted by the receiver', m is not None and int(m.group(2)) > 0 and int(m.group(2)) < int(m.group(1)) * 0.1, said[-1] if said else None)

        source, thread, mark = session(reorder=True)
        source.ready.wait(20); until(lambda: state().get('status') == 'playing', 20)
        time.sleep(10)
        check('reordered packets are put back and the picture goes on', state().get('status') == 'playing', state().get('status'))
        finish(source, thread)
        said = [l for l in logs(mark) if 'packets received' in l]
        m = re.search(r'(\d+) packets received, (\d+) lost', said[-1]) if said else None
        check('with nothing counted as lost', m is not None and int(m.group(2)) == 0, said[-1] if said else None)

        # --- every way a session can end ---------------------------------------------------------------------------
        source, thread, mark = session()
        source.ready.wait(20); until(lambda: state().get('status') == 'playing', 20)
        device.open_page('home.main')
        check('leaving the page ends a shared screen (it cannot be paused)', bool(until(lambda: state().get('status') in ('stopped',) or state().get('owner') in ('', None), 10)),
              (state().get('status'), state().get('owner')))
        check('and the Source is told with a TEARDOWN', bool(until(lambda: ('TEARDOWN', '') in source.sink_requests, 8)), source.sink_requests[-3:])
        thread.join(8); source.close()

        source, thread, mark = session()
        source.ready.wait(20); until(lambda: state().get('status') == 'playing', 20)
        source.trigger('TEARDOWN')
        check('a Source that disconnects ends it', bool(until(lambda: state().get('status') == 'stopped' or not state().get('owner'), 10)), (state().get('status'), state().get('owner')))
        check('and the screen goes back', bool(until(lambda: page() != 'cast.video', 8)), page())
        thread.join(8); source.close()

        source, thread, mark = session()
        source.ready.wait(20); until(lambda: state().get('status') == 'playing', 20)
        device.load('http://127.0.0.1:1/none.mp3', kind='audio')           # somebody else takes the media session
        check('when another cast takes the media session the Source is told', bool(until(lambda: ('TEARDOWN', '') in source.sink_requests, 8)), source.sink_requests[-3:])
        thread.join(8); source.close(); device.debug('media_stop'); time.sleep(1)

        mark = device.log_mark()
        device.debug('miracast_dial', host=mac_ip, port=PORT)       # nobody listening any more
        check('nothing listening is reported, not hung on', bool(until(lambda: any('nothing is listening' in l for l in logs(mark)), 8)), logs(mark)[-2:])

        # --- a Source that talks nonsense ----------------------------------------------------------------------------
        junk = socket.socket(); junk.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); junk.bind(('0.0.0.0', PORT)); junk.listen(1)
        def bad():
            c, _ = junk.accept(); c.sendall(b'GET / HTTP/1.1\r\nHost: x\r\n\r\n'); time.sleep(2); c.close()
        threading.Thread(target=bad, daemon=True).start()
        mark = device.log_mark()
        device.debug('miracast_dial', host=mac_ip, port=PORT)
        check('a Source that is not speaking RTSP ends the session without a crash', bool(until(lambda: any('session ended (protocol' in l for l in logs(mark)), 10)), logs(mark)[-3:])
        junk.close()
        check('and the device is still fine afterwards', device.shell('pidof', 'moe.kiwi.thinkos').strip() != '' and page() != 'cast.video', page())
    finally:
        device.debug('media_stop')
        for command in ('lux', 'theme', 'hold_attention'):
            device.debug(command)
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', '0')
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
