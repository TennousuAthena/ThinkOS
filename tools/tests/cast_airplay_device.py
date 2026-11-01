#!/usr/bin/env python3
"""AirPlay video reception on the device, against a scripted sender (docs/28-casting.md, stage C3).

    tools/media/gen_samples.sh && python3 -I tools/tests/cast_airplay_device.py [--serial HUA0BWUT]

The test switches `cast.airplay` on in the settings file (and puts the file back byte for byte), checks from this Mac that the device
is announced over mDNS (dns-sd), and then plays the part of an iPhone with tools/cast/airplay_sender.py: server-info, pairing,
the event channel, play, pause, seek, stop, a film that ends, what must be refused, and what is malformed. The media comes from
tools/media/serve.py on this machine's LAN address, so the device and the Mac must be on one network.
"""
import argparse
import json
import os
from pathlib import Path
import re
import socket
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

SETTINGS = 'files/settings/local.json'
NAME = 'ThinkOS-AirPlay-Test'
CLIP = 'video-h264-720p30.mp4'


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

    def write_settings(enabled):
        document = json.loads(original.decode()) if original else {}
        document.setdefault('system', {}).update({'cast.airplay': enabled, 'cast.airplay_mirror': False, 'cast.name': NAME})      # this test is the video-only identity; mirroring has its own
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS],
                       input=json.dumps(document).encode(), capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        time.sleep(10)

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

    def raw(ip, data, wait=1.5):
        s = socket.create_connection((ip, 7000), timeout=5)
        s.sendall(data); s.settimeout(wait)
        try: got = s.recv(4096)
        except (socket.timeout, ConnectionResetError): got = b''
        s.close()
        return got

    try:
        device.prepare_display()
        ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', device.shell('ip', '-4', 'addr', 'show', 'wlan0')).group(1)
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); probe.connect((ip, 9)); mac_ip = probe.getsockname()[0]; probe.close()
        url = 'http://%s:%d/files/%s' % (mac_ip, PORT, CLIP)
        write_settings(True)
        device.debug('hold_attention', value='AWAKE')
        device.debug('media_stop')

        # --- announced over mDNS ----------------------------------------------------------------------------------------
        found = dns_sd(['-B', '_airplay._tcp', 'local.'], 6)
        check('the device is announced as an _airplay._tcp service named after cast.name', NAME in found, [l for l in found.splitlines() if NAME in l][:1])
        detail = dns_sd(['-L', NAME, '_airplay._tcp', 'local.'], 5)
        check('and the record resolves to this device on port 7000', ':7000' in detail, [l for l in detail.splitlines() if 'reached' in l][:1])
        txt = dict(re.findall(r'(\w+)=(\S+)', detail))
        check('with features 0x19 (video, its volume control, HTTP live streams) and no pairing key or audio or mirroring claimed',
              txt.get('features') == '0x19' and 'pk' not in txt and txt.get('model') == 'AppleTV3,2', {k: txt.get(k) for k in ('deviceid', 'features', 'flags', 'model', 'srcvers')})

        # --- what a sender reads and does before it plays --------------------------------------------------------------
        sender = AirPlaySender(ip)
        status, info = sender.server_info()
        check('/server-info answers with the same identity as the record', status == 200 and info.get('deviceid') == txt.get('deviceid') and info.get('features') == 0x19, info)
        status, info = sender.info()
        check('/info (binary plist) carries the name', status == 200 and info.get('name') == NAME, info.get('name'))
        check('the pairing handshake completes, and the device proves its key', sender.pair() == 200)
        check('a wrong signature in pair-verify is refused', AirPlaySender(ip).pair(break_signature=True) == 470)
        events = sender.events()
        check('POST /reverse upgrades to the event channel', events.status == 101 and events.headers.get('upgrade') == 'PTTH/1.0', events.status)

        # --- playing --------------------------------------------------------------------------------------------------
        mark = device.log_mark()
        check('/play of an http address is accepted', sender.play(url) == 200)
        check('the sender is told the film is loading, then playing, each once', events.wait_for('loading', 10) and events.wait_for('playing', 25) and events.states() == ['loading', 'playing'], events.states())
        s = state()
        check('the engine plays it as the cast:airplay session, as video', s.get('owner') == 'cast:airplay' and s.get('kind') == 'video' and s.get('status') == 'playing', (s.get('owner'), s.get('status')))
        check('the cast page took the screen', page() == 'cast.video', page())
        info = sender.playback_info()
        check('/playback-info says ready, with the film\'s duration', info.get('readyToPlay') is True and 8 < info.get('duration', 0) < 20, {k: info.get(k) for k in ('readyToPlay', 'duration', 'position', 'rate')})
        p1 = sender.position()['position']; time.sleep(1.5); p2 = sender.position()['position']
        check('the position moves in step with real time', 0.9 < p2 - p1 < 2.4, '%.2f -> %.2f' % (p1, p2))

        sender.rate(0.0)
        check('/rate 0 pauses it and the sender hears "paused"', events.wait_for('paused', 8) and state().get('status') == 'paused', events.states()[-3:])
        p1 = sender.position()['position']; time.sleep(1.5); p2 = sender.position()['position']
        check('and the position stands still', abs(p2 - p1) < 0.3, '%.2f -> %.2f' % (p1, p2))
        before = len(events.states()); sender.rate(1.0)
        check('/rate 1 resumes it and the sender hears "playing"', events.wait_for('playing', 8, after=before) and state().get('status') == 'playing', events.states()[-3:])
        sender.scrub(8.0); time.sleep(1.2)
        check('/scrub moves the playhead', sender.position()['position'] >= 7.5, sender.position())
        before = len(events.states()); sender.stop()
        check('/stop ends it: the sender hears "stopped"', events.wait_for('stopped', 8, after=before), events.states()[-3:])
        check('and the screen is given back', bool(until(lambda: page() != 'cast.video', 8)) and state().get('status') in ('stopped', None), (page(), state().get('status')))

        # --- start position, and a film that ends -------------------------------------------------------------------
        before = len(events.states())
        sender.play(url, start=0.5)
        events.wait_for('playing', 25, after=before)
        time.sleep(1.5)
        pos = sender.position()['position']
        check('a start position of half the film is honoured', 5 < pos < 9.5, pos)
        before = len(events.states())
        sender.scrub(11.0)
        check('a film that reaches its end is reported as stopped', events.wait_for('stopped', 12, after=before), events.states()[-4:])
        check('and gives the screen back by itself', bool(until(lambda: page() != 'cast.video', 8)), page())

        # --- what must be refused ------------------------------------------------------------------------------------
        for bad in ('mlhls://localhost/master.m3u8', 'file:///sdcard/Movies/x.mp4', 'content://media/external/video/1', 'rtsp://192.168.3.9/live', 'http://user:secret@tv.test/a.mp4', 'not an address'):
            before = len(events.states())
            status = sender.play(bad)
            check('/play %s is refused with 400 and the sender hears "stopped"' % bad.split(':')[0][:12], status == 400 and events.wait_for('stopped', 5, after=before), status)
        check('nothing was loaded by the refusals', state().get('owner') in ('', None) and page() != 'cast.video', (state().get('owner'), page()))

        # --- malformed and hostile -----------------------------------------------------------------------------------
        check('garbage gets a 400', b'400' in raw(ip, b'hello\r\n\r\n'))
        check('an oversize body gets a 413 without being read', b'413' in raw(ip, b'POST /play HTTP/1.1\r\nContent-Length: 9000000\r\n\r\n'))
        check('an unknown route gets a 404', b'404' in raw(ip, b'GET /nothing HTTP/1.1\r\n\r\n'))
        check('FairPlay setup is not offered (404)', b'404' in raw(ip, b'POST /fp-setup HTTP/1.1\r\nContent-Length: 0\r\n\r\n'))
        hold = [socket.create_connection((ip, 7000), timeout=5) for _ in range(14)]
        time.sleep(0.5)
        for h in hold: h.close()
        time.sleep(1)
        check('fourteen idle connections do not stop it answering', AirPlaySender(ip).server_info()[0] == 200)
        events.close(); sender.close()

        # --- switching it off -----------------------------------------------------------------------------------------
        device.debug('setting', key='cast.airplay', value='false')           # at run time, as the settings page does it
        time.sleep(4)
        refused = False
        try: socket.create_connection((ip, 7000), timeout=3).close()
        except OSError: refused = True
        check('switched off, the device no longer listens on 7000', refused)
        check('and is no longer announced', NAME not in dns_sd(['-B', '_airplay._tcp', 'local.'], 5))
    finally:
        device.debug('media_stop')
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
