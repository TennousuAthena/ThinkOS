#!/usr/bin/env python3
"""AirPlay screen mirroring and its sound on the device, against a scripted sender (docs/28-casting.md, stage C5).

    tools/media/gen_samples.sh && python3 -I tools/tests/cast_airplay_mirror_device.py [--serial HUA0BWUT]

tools/cast/airplay_mirror_sender.py plays the iPhone from /info to the streams. Only Apple's FairPlay wrapping of the session key cannot be
imitated, so the debug build is told (`airplay_testkey`) to answer every unwrap with the key the sender uses; the native FairPlay routine itself
is only probed (it must answer and not crash). Everything after the key is real: the encrypted H.264 over TCP, the encrypted AAC over UDP, the
timing exchange, the re-packing as a transport stream, the engine, the screen. The settings file is put back byte for byte at the end.
"""
import argparse
import json
import plistlib
import os
from pathlib import Path
import re
import socket
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools/cast'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file
from media_device import Device
from media_stream_device import Origin, PORT
from airplay_sender import AirPlaySender
from airplay_mirror_sender import MirrorSender, TEST_KEY
import latency_probe

SETTINGS = 'files/settings/local.json'
NAME = 'ThinkOS-Mirror-Test'


def dns_sd(args, seconds):
    process = subprocess.Popen(['dns-sd'] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(seconds)
    process.terminate()
    try: out, _ = process.communicate(timeout=3)
    except subprocess.TimeoutExpired: process.kill(); out, _ = process.communicate()
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    origin = Origin(args.samples)
    origin.start()
    results = []
    original = read_app_file(args.serial, SETTINGS)

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def write_settings(**values):
        document = json.loads(original.decode()) if original else {}
        document.setdefault('system', {}).update(values)
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS], input=json.dumps(document).encode(), capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        time.sleep(10)
        device.debug('hold_attention', value='AWAKE')
        device.debug('airplay_testkey', value=TEST_KEY.hex())

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        m = re.search(r'data="([^"]*)"', out)
        return m.group(1).split('|')[0] if m else ''

    def until(test, seconds=15, interval=0.4):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = test()
            if value: return value
            time.sleep(interval)
        return None

    def state(): return device.state() or {}

    def playing_mirror(): s = state(); return s.get('mirror') is True and s.get('status') == 'playing' and s.get('videoWidth', 0) > 0

    def closed_by_device(sender):
        """True when the device has hung up on the sender's control connection."""
        try:
            sender.http.control.sock.settimeout(1)
            sender.feedback()
        except Exception: return True
        return False

    def start(sender, audio=True):
        sender.http.pair(); sender.fp_setup()
        status, keys = sender.setup_keys()
        status2, streams = sender.setup_streams(video=True, audio=audio)
        sender.record(); sender.start_media(video=True, audio=audio)
        return status, keys, status2, streams

    sender = None
    try:
        device.prepare_display()
        ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', device.shell('ip', '-4', 'addr', 'show', 'wlan0')).group(1)
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); probe.connect((ip, 9)); mac_ip = probe.getsockname()[0]; probe.close()
        url = 'http://%s:%d/files/video-h264-720p30.mp4' % (mac_ip, PORT)
        device.debug('media_stop')
        write_settings(**{'cast.airplay': True, 'cast.airplay_mirror': True, 'cast.name': NAME})
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', '0')      # landscape, so the picture can be read from screenshots

        # --- announced, and what the native FairPlay routine does -------------------------------------------------------
        found = dns_sd(['-B', '_airplay._tcp', 'local.'], 5)
        check('the device is announced as an _airplay._tcp service', NAME in found, [l for l in found.splitlines() if NAME in l][:1])
        resolved = dns_sd(['-L', NAME, '_airplay._tcp', 'local.'], 4)
        txt = dict(re.findall(r'(\w+)=(\S+)', resolved))
        check('claiming what UxPlay does: features 0x5A7FFEE6,0x0, vv 2, a pi, a pk, model AppleTV3,2', txt.get('features') == '0x5A7FFEE6,0x0' and txt.get('vv') == '2' and 'pi' in txt and len(txt.get('pk', '')) == 64 and txt.get('model') == 'AppleTV3,2', txt)
        raop = dns_sd(['-B', '_raop._tcp', 'local.'], 5)
        instance = re.search(r'(\w{12})@%s' % re.escape(NAME), raop)
        check('and as a _raop._tcp service named <device id>@<name> (senders look for it to mirror)', instance is not None and instance.group(1) == txt.get('deviceid', '').replace(':', ''), instance and instance.group(0))
        raop_txt = dict(re.findall(r'(\w+)=(\S+)', dns_sd(['-L', instance.group(0), '_raop._tcp', 'local.'], 4))) if instance else {}
        check('with the encryption types and transport a mirroring sender expects', raop_txt.get('et') == '0,3,5' and raop_txt.get('tp') == 'UDP' and raop_txt.get('vn') == '65537', raop_txt)
        mark = device.log_mark(); device.debug('fairplay_probe'); time.sleep(1.5)
        probe_line = next((l for l in device.log_since(mark) if 'fairplay_probe' in l), '')
        check('the native FairPlay library loads and answers (phase 1: 142 bytes in the mode asked, phase 2: 32, the key: 16) without crashing',
              'available=true' in probe_line and 'key=16' in probe_line and 'phase1=142' in probe_line and 'mode=2' in probe_line and 'phase2=32' in probe_line, probe_line[-110:])

        # --- the handshake -----------------------------------------------------------------------------------------------
        sender = MirrorSender(ip)
        status, info = sender.info()
        display = (info.get('displays') or [{}])[0]
        check('/info is the Apple TV one: features 0x5A7FFEE6, statusFlags 68, a public key, a 1280x720 display, audio formats', status == 200 and info.get('features') == 0x5A7FFEE6 and info.get('statusFlags') == 68 and len(info.get('pk', b'')) == 32
              and (display.get('width'), display.get('height')) == (1280, 720) and bool(info.get('audioFormats')), {k: info.get(k) for k in ('features', 'model', 'name', 'statusFlags')})
        # What an iPhone does first: asks for the text records and compares them with what it found by mDNS.
        qualified = sender.http.control.request('GET', '/info', plistlib.dumps({'qualifier': ['txtAirPlay']}, fmt=plistlib.FMT_BINARY), [('Content-Type', 'application/x-apple-binary-plist')])
        answer = plistlib.loads(qualified[2]) if qualified[0] == 200 and qualified[2] else {}
        records = {}; blob = answer.get('txtAirPlay', b''); at = 0
        while at < len(blob):
            n = blob[at]; entry = blob[at + 1:at + 1 + n].decode(errors='replace'); at += 1 + n
            records[entry.partition('=')[0]] = entry.partition('=')[2]
        check('a /info that asks for txtAirPlay gets the text record, and only that', set(answer) == {'txtAirPlay'} and bool(records), sorted(answer))
        check('and it says what mDNS announced (checked from this Mac with dns-sd)', {k: v for k, v in records.items()} == {k: v for k, v in txt.items() if k in records} and len(records) >= 8, records)
        check('with the public key that pair-setup hands out', records.get('pk') == info.get('pk', b'').hex(), records.get('pk'))
        check('pair-setup and pair-verify complete', sender.http.pair() == 200)
        check('and pair-setup answered with that same key', sender.http.server_key.hex() == records.get('pk'), sender.http.server_key.hex())
        (s1, a1), (s2, a2, message) = sender.fp_setup(mode=3)
        check('/fp-setup phase 1 answers with the fixed 142-byte reply for the mode asked', s1 == 200 and len(a1) == 142 and a1[:6] == b'FPLY\x03\x01' and a1[13] == 3, (s1, len(a1)))
        check('and phase 2 with the 12-byte header and the last 20 bytes of the request', s2 == 200 and len(a2) == 32 and a2[:12] == bytes.fromhex('46504c59030104000000 0014'.replace(' ', '')) and a2[12:] == message[144:], (s2, len(a2)))
        status, keys = sender.setup_keys()
        check('SETUP with the wrapped key gives an event port and a timing port', status == 200 and keys.get('eventPort', 0) > 0 and keys.get('timingPort', 0) > 0, keys)
        status, streams = sender.setup_streams(video=True, audio=True)
        by_type = {s['type']: s for s in streams.get('streams', [])}
        check('SETUP of the streams gives a TCP port for the picture and UDP ports for the sound', status == 200 and 110 in by_type and 96 in by_type and by_type[96].get('controlPort', 0) > 0, streams)
        check('RECORD is accepted', sender.record() == 200)

        # --- the picture and the sound ----------------------------------------------------------------------------------
        sender.start_media()
        ok = until(playing_mirror, 25)
        s = state()
        check('the engine plays the screen as a live mirror of the cast:airplay session', bool(ok) and s.get('owner') == 'cast:airplay' and s.get('live') is True and s.get('seekable') is False,
              {k: s.get(k) for k in ('owner', 'status', 'mirror', 'live', 'seekable')})
        check('as 1280x720 video with AAC-LC stereo sound at 44.1 kHz', (s.get('videoWidth'), s.get('videoHeight')) == (1280, 720) and s.get('sampleRate') == 44100 and s.get('channels') == 2,
              {k: s.get(k) for k in ('videoWidth', 'videoHeight', 'sampleRate', 'channels', 'audioCodec')})
        check('on the cast page', page() == 'cast.video', page())
        check('the device polled the sender\'s timing port (the sender does not go on without it)', bool(until(lambda: sender.timing.requests >= 2, 8)), sender.timing.requests)
        f1 = sender.frames; time.sleep(2); rate = (sender.frames - f1) / 2.0
        check('and the pictures keep coming at about 30 a second', 24 <= rate <= 36, '%.1f' % rate)
        check('/feedback is answered', sender.feedback() == 200)
        check('the sender\'s volume reaches the player (-15 dB is half)', sender.set_volume(-15.0) == 200 and bool(until(lambda: abs(state().get('volume', 1) - 0.5) < 0.02, 4)), state().get('volume'))
        sender.set_volume(0.0)
        check('the player catches up to the live edge', bool(until(lambda: state().get('buffered', 0) - state().get('position', 0) < 450, 30, 0.5)), state().get('buffered', 0) - state().get('position', 0))
        time.sleep(2)
        rows = []
        for i in range(10):
            t1 = time.time()
            proc = subprocess.Popen(['adb', '-s', args.serial, 'exec-out', 'screencap'], stdout=subprocess.PIPE)
            head = proc.stdout.read(12); t_grab = time.time()
            raw = head + proc.stdout.read(); proc.wait()
            got = latency_probe.decode(raw)
            if got and sender.t0: rows.append((t_grab - (sender.t0 + got[0] / 1000.0)) * 1000)
            time.sleep(1.1)
        median = statistics.median(rows) if rows else None
        check('the picture was read back %d times of 10, and it is no more than 700 ms behind the sender (median)' % len(rows), len(rows) >= 6 and median is not None and median < 700,
              'median %.0f ms, min %.0f, max %.0f' % (median or -1, min(rows or [-1]), max(rows or [-1])))
        s = state()
        check('few pictures were dropped', s.get('droppedFrames', 99) < 30, s.get('droppedFrames'))

        # --- a full-screen video takes the streams away and gives them back ---------------------------------------------
        check('the sender drops its streams (TEARDOWN of video and audio) and the session stays', sender.teardown([110, 96]) == 200)
        sender.stop_media()
        side = AirPlaySender(ip, session='6C1C9B2A-0D55-4B1F-8F3B-7A1E5F000002')
        events = side.events()
        check('a video address is played on another connection of the same sender', side.play(url) == 200 and bool(until(lambda: state().get('status') == 'playing' and state().get('mirror') is False and state().get('owner') == 'cast:airplay', 25)),
              {k: state().get(k) for k in ('owner', 'status', 'mirror')})
        check('without ending the mirror session (the sender is still connected)', not closed_by_device(sender))
        side.stop(); until(lambda: state().get('status') != 'playing', 8)
        status, streams = sender.setup_streams(video=True, audio=True)
        sender.record(); sender.start_media()
        check('and the mirror comes back when the sender sets its streams up again', status == 200 and bool(until(playing_mirror, 25)), {k: state().get(k) for k in ('owner', 'status', 'mirror')})
        events.close(); side.close()

        # --- every way it ends --------------------------------------------------------------------------------------------
        device.open_page('home.main')
        check('leaving the page ends the mirror', bool(until(lambda: state().get('status') in ('stopped',) or not state().get('owner'), 10)), (state().get('status'), state().get('owner')))
        check('and the device hangs up on the sender, which is how it learns', bool(until(lambda: closed_by_device(sender), 6)))
        sender.close()

        sender = MirrorSender(ip)
        start(sender)
        check('a new sender starts a new mirror at once', bool(until(playing_mirror, 25)))
        sender.http.control.sock.close()                                       # the sender vanishes
        check('a sender that disappears ends it and the screen is given back', bool(until(lambda: not state().get('owner') or state().get('status') == 'stopped', 10)) and bool(until(lambda: page() != 'cast.video', 8)),
              (state().get('status'), page()))
        sender.close()

        sender = MirrorSender(ip)
        start(sender)
        until(playing_mirror, 25)
        sender2 = MirrorSender(ip)
        start(sender2)
        check('a second sender takes over; the first is dropped', bool(until(lambda: closed_by_device(sender), 8)) and bool(until(playing_mirror, 25)))
        sender2.teardown()
        check('TEARDOWN of the whole session ends it', bool(until(lambda: not state().get('owner') or state().get('status') == 'stopped', 10)))
        sender.close(); sender2.close()

        # --- what must not work, and what is malformed -------------------------------------------------------------------
        junk = AirPlaySender(ip)
        def rtsp(method, body=b'', headers=()): return junk.control.request(method, 'rtsp://%s/1' % ip, body, list(headers), protocol='RTSP/1.0')[0]
        check('a stream before the keys is refused', rtsp('SETUP', __import__('plistlib').dumps({'streams': [{'type': 110, 'streamConnectionID': 1}]}, fmt=__import__('plistlib').FMT_BINARY)) == 400)
        check('a SETUP without the handshakes is refused', rtsp('SETUP', __import__('plistlib').dumps({'ekey': os.urandom(72)}, fmt=__import__('plistlib').FMT_BINARY)) == 400)
        check('a SETUP that is not a plist is refused', rtsp('SETUP', b'bplist00garbage') == 400)
        check('an /fp-setup of the wrong size is refused', junk.control.request('POST', '/fp-setup', os.urandom(20))[0] == 400)
        junk.close()
        mirror = MirrorSender(ip)
        mirror.http.pair(); mirror.fp_setup(); mirror.setup_keys(); mirror.setup_streams(video=True, audio=False)
        wild = socket.create_connection((ip, mirror.video_port), timeout=5)
        wild.sendall(mirror._header(0x7fffffff, 0, 0) + b'x' * 50)                    # a picture the size of the address space
        time.sleep(1.5)
        wild.close()
        check('a picture packet of two gigabytes ends the stream, not the device', device.shell('pidof', 'moe.kiwi.thinkos').strip() != '')
        mirror.close()
        check('and the device still serves the sender after all of it', AirPlaySender(ip).info()[0] == 200)

        # --- mirroring switched off at run time -------------------------------------------------------------------------
        device.debug('setting', key='cast.airplay_mirror', value='false'); time.sleep(5)
        plain = AirPlaySender(ip)
        status, info = plain.info()
        check('with mirroring off the device is the video-only receiver again: features 0x19', status == 200 and info.get('features') == 0x19, info.get('features'))
        check('FairPlay setup is not offered then', plain.control.request('POST', '/fp-setup', os.urandom(16))[0] == 404)
        check('and the _raop._tcp service is gone', not re.search(re.escape(NAME), dns_sd(['-B', '_raop._tcp', 'local.'], 5)))
        plain.close()
    finally:
        device.debug('airplay_testkey')
        device.debug('media_stop')
        if sender: sender.close()
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        if original is not None:
            subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS], input=original, capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        device.debug('hold_attention'); device.debug('lux'); device.debug('theme')
        origin.stop()
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
