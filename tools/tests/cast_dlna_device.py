#!/usr/bin/env python3
"""The DLNA receiver on the device, spoken to the way a controller does (docs/28-casting.md, stage C2).

    tools/media/gen_samples.sh && python3 tools/tests/cast_dlna_device.py

The test switches `cast.enabled` on in the settings file (and puts the file back byte for byte), then talks SSDP and
UPnP/SOAP to the device over the network: search, description, push an address, play, pause, seek, volume, stop, being
taken over, being switched off. The media comes from tools/media/serve.py through `adb reverse` (the debug build lets a
cast point at the loopback address for exactly this). Multicast discovery needs a sender on the device's own subnet, so
here the search is sent straight to the device.
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
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file
from media_device import Device
from media_stream_device import BASE, Origin, PORT

SETTINGS = 'files/settings/local.json'
NAME = 'ThinkOS-Test'
CLIP = 'video-h264-720p30.mp4'
AVT = 'urn:schemas-upnp-org:service:AVTransport:1'
RCS = 'urn:schemas-upnp-org:service:RenderingControl:1'
DIDL = ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>Cast test film</dc:title>'
        '<upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*">%s</res></item></DIDL-Lite>')


def esc(text): return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def soap(base, path, service, action, **args):
    body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            '<u:%s xmlns:u="%s"><InstanceID>0</InstanceID>%s</u:%s></s:Body></s:Envelope>') % (
        action, service, ''.join('<%s>%s</%s>' % (k, esc(str(v)), k) for k, v in args.items()), action)
    request = urllib.request.Request(base + path, body.encode(), {'Content-Type': 'text/xml; charset="utf-8"', 'SOAPACTION': '"%s#%s"' % (service, action)})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply: status, text = reply.status, reply.read().decode()
    except urllib.error.HTTPError as error: status, text = error.code, error.read().decode()
    root = ET.fromstring(text)
    values = {e.tag.split('}')[-1]: (e.text or '') for e in root.iter() if len(e) == 0}
    return status, values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--keep-muted', action='store_true', help='Require media volume0 and omit nonzero session-volume/mute changes')
    args = parser.parse_args()
    device = Device(args.serial)
    if args.keep_muted:
        assert re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get'))
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    results = []
    original = read_app_file(args.serial, SETTINGS)

    def check(name, ok, detail=''):
        if args.keep_muted:
            assert re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get'))
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def write_settings(enabled):
        document = json.loads(original.decode()) if original else {}
        document.setdefault('system', {}).update({'cast.enabled': enabled, 'cast.name': NAME})
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS],
                       input=json.dumps(document).encode(), capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        time.sleep(9)

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|')[0] if match else ''

    def until(test, seconds=15):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = test()
            if value: return value
            time.sleep(0.6)
        return None

    def listening(port, udp=False):
        """From the device's own point of view: this Mac's network tool accepts connections to any address, so a connect() proves nothing."""
        return bool(re.search(r':%d\s' % port, ''.join(l + '\n' for l in device.shell('netstat', '-uln' if udp else '-tln', check=False).splitlines() if udp or 'LISTEN' in l)))

    def search(ip, target='ssdp:all', wait=3.0):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(0.5)
        sock.sendto(('M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 1\r\nST: %s\r\n\r\n' % target).encode(), (ip, 1900))
        answers, end = [], time.monotonic() + wait
        while time.monotonic() < end:
            try: answers.append(sock.recvfrom(4096)[0].decode())
            except socket.timeout: pass
        sock.close()
        return answers

    try:
        device.prepare_display()
        ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', device.shell('ip', '-4', 'addr', 'show', 'wlan0')).group(1)
        write_settings(True)
        device.debug('hold_attention', value='AWAKE')

        # --- discovery and description ------------------------------------------------------------------------
        answers = search(ip)
        targets = sorted(re.search(r'(?im)^ST:\s*(.+)$', a).group(1).strip() for a in answers)
        check('the device listens for control (TCP 49152) and searches (UDP 1900)', listening(49152) and listening(1900, udp=True))
        check('a search for everything is answered with the root device, the device, its type and three services', len(set(targets)) == 6, targets)
        locations = {re.search(r'(?im)^LOCATION:\s*(.+)$', a).group(1).strip() for a in answers}
        check('every answer points to the same description', len(locations) == 1, locations)
        check('a search for something else is not answered', search(ip, 'urn:schemas-upnp-org:device:MediaServer:1', 2.0) == [])
        location = next(iter(locations))
        check('the description lives on the device itself', location.startswith('http://%s:' % ip), location)
        base = location.rsplit('/dlna/', 1)[0]
        description = ET.fromstring(urllib.request.urlopen(location, timeout=10).read())
        ns = {'d': 'urn:schemas-upnp-org:device-1-0'}
        check('the description carries the name from the settings', description.find('.//d:friendlyName', ns).text == NAME, description.find('.//d:friendlyName', ns).text)
        services = [(s.find('d:serviceType', ns).text, s.find('d:SCPDURL', ns).text, s.find('d:controlURL', ns).text) for s in description.findall('.//d:service', ns)]
        ok = all(urllib.request.urlopen(base + scpd, timeout=10).status == 200 for _, scpd, _ in services)
        check('all three service documents can be fetched', len(services) == 3 and ok, [s[0].split(':')[-2] for s in services])
        control = {t: c for t, _, c in services}

        # --- push, play, pause, seek, volume, stop ------------------------------------------------------------
        url = '%s/files/%s' % (BASE, CLIP)
        status, _ = soap(base, control[AVT], AVT, 'SetAVTransportURI', CurrentURI=url, CurrentURIMetaData=DIDL % url)
        check('an address and its metadata are accepted', status == 200, status)
        info = until(lambda: soap(base, control[AVT], AVT, 'GetTransportInfo')[1])
        check('loaded but not started reads as STOPPED', info and info['CurrentTransportState'] == 'STOPPED', info and info['CurrentTransportState'])
        check('the cast page took the screen', until(lambda: page() == 'cast.video', 8) is not None, page())
        soap(base, control[AVT], AVT, 'Play', Speed=1)
        playing = until(lambda: (lambda i: i if i['CurrentTransportState'] == 'PLAYING' else None)(soap(base, control[AVT], AVT, 'GetTransportInfo')[1]), 20)
        check('Play starts it', playing is not None)
        state = device.state() or {}
        check('the engine plays it as the cast session, with the title the controller gave', state.get('owner') == 'cast:dlna' and state.get('title') == 'Cast test film',
              (state.get('owner'), state.get('title'), state.get('status')))
        time.sleep(2.5)
        position = soap(base, control[AVT], AVT, 'GetPositionInfo')[1]
        check('the position moves and the duration is known', position['RelTime'] >= '0:00:02' and position['TrackDuration'] > '0:00:05' and position['TrackURI'] == url,
              (position['RelTime'], position['TrackDuration']))
        soap(base, control[AVT], AVT, 'Pause')
        check('Pause is PAUSED_PLAYBACK', until(lambda: soap(base, control[AVT], AVT, 'GetTransportInfo')[1]['CurrentTransportState'] == 'PAUSED_PLAYBACK', 6))
        soap(base, control[AVT], AVT, 'Seek', Unit='REL_TIME', Target='0:00:08')
        time.sleep(1.2)
        moved = soap(base, control[AVT], AVT, 'GetPositionInfo')[1]['RelTime']
        check('Seek moves the playhead', moved >= '0:00:07', moved)
        check('a seek past the end is refused with 711', soap(base, control[AVT], AVT, 'Seek', Unit='REL_TIME', Target='5:00:00')[0] == 500)
        soap(base, control[AVT], AVT, 'Play', Speed=1)
        if args.keep_muted:
            print('SKIP three session-volume/mute checks (--keep-muted; media volume stays0)', flush=True)
        else:
            status, _ = soap(base, control[RCS], RCS, 'SetVolume', Channel='Master', DesiredVolume=30)
            volume = soap(base, control[RCS], RCS, 'GetVolume', Channel='Master')[1]['CurrentVolume']
            check('the volume is set and read back on the 0-100 scale', status == 200 and volume == '30', volume)
            engine_volume = (device.state() or {}).get('volume')
            check('the engine volume follows', engine_volume is not None and abs(engine_volume - 0.3) < 0.02, engine_volume)
            soap(base, control[RCS], RCS, 'SetMute', Channel='Master', DesiredMute=1)
            muted = (device.state() or {}).get('volume')
            check('mute silences and reads back', soap(base, control[RCS], RCS, 'GetMute', Channel='Master')[1]['CurrentMute'] == '1' and muted is not None and muted < 0.01, muted)
            soap(base, control[RCS], RCS, 'SetMute', Channel='Master', DesiredMute=0)

        # --- what must be refused -----------------------------------------------------------------------------
        for bad in ('file:///sdcard/Movies/x.mp4', 'content://media/external/video/1', 'rtsp://192.168.1.9/live', 'http://user:secret@tv.test/a.mp4'):
            status, values = soap(base, control[AVT], AVT, 'SetAVTransportURI', CurrentURI=bad, CurrentURIMetaData='')
            check('a pushed address %s is refused' % bad.split(':')[0], status == 500 and values.get('errorCode') == '402', (status, values.get('errorCode')))
        check('the cast that was playing is untouched by the refusals', soap(base, control[AVT], AVT, 'GetTransportInfo')[1]['CurrentTransportState'] in ('PLAYING', 'TRANSITIONING'))
        check('an unknown action is a UPnP error', soap(base, control[AVT], AVT, 'Launch')[1].get('errorCode') == '401')

        # --- subscribing -------------------------------------------------------------------------------------
        mac = subprocess.run(['ipconfig', 'getifaddr', 'en1'], capture_output=True, text=True).stdout.strip()
        if mac:
            request = urllib.request.Request(base + services[0][2].replace('control', 'event'), method='SUBSCRIBE',
                                             headers={'CALLBACK': '<http://%s:9/cb>' % mac, 'NT': 'upnp:event', 'TIMEOUT': 'Second-60'})
            try:
                with urllib.request.urlopen(request, timeout=10) as reply: code, sid = reply.status, reply.headers.get('SID', '')
            except urllib.error.HTTPError as error: code, sid = error.code, ''
            if code == 200:
                check('a controller can subscribe to events and gets a SID', sid.startswith('uuid:'), sid)
                un = urllib.request.Request(base + services[0][2].replace('control', 'event'), method='UNSUBSCRIBE', headers={'SID': sid})
                check('and unsubscribe again', urllib.request.urlopen(un, timeout=10).status == 200)
            else: print('SKIP subscribing (%s: the device sees this Mac under another address)' % code, flush=True)
        else: print('SKIP subscribing (no LAN address for the Mac)', flush=True)

        # --- stop, take-over, switch off --------------------------------------------------------------------
        soap(base, control[AVT], AVT, 'Stop')
        check('Stop ends the cast', until(lambda: soap(base, control[AVT], AVT, 'GetTransportInfo')[1]['CurrentTransportState'] == 'STOPPED', 6))
        check('and gives the screen back', until(lambda: page() != 'cast.video', 8) is not None, page())
        check('the engine is idle after Stop', (device.state() or {}).get('status') == 'stopped', (device.state() or {}).get('status'))
        soap(base, control[AVT], AVT, 'Play', Speed=1)
        check('Play after Stop starts the same address again', until(lambda: soap(base, control[AVT], AVT, 'GetTransportInfo')[1]['CurrentTransportState'] in ('PLAYING', 'TRANSITIONING'), 15) is not None)
        device.load('%s/files/%s' % (BASE, 'audio-mp3-cbr192.mp3'), kind='audio')
        time.sleep(3)
        check('when something else takes the media session the cast reads STOPPED', soap(base, control[AVT], AVT, 'GetTransportInfo')[1]['CurrentTransportState'] == 'STOPPED')
        device.debug('media_stop')

        write_settings(False)
        check('switched off, the device no longer answers searches', search(ip, 'ssdp:all', 2.5) == [])
        check('and no longer listens for control or searches', not listening(49152) and not listening(1900, udp=True))
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
