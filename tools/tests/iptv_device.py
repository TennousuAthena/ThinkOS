#!/usr/bin/env python3
"""The iptv plugin and the tv component on the device, against a fixture station on the Mac.

    python3 tools/tests/iptv_device.py [--output artifacts/iptv] [--port 8771]

The station serves two channel lists (one channel is in both and has dead lines), a programme guide and three HLS streams made by
ffmpeg. The device reaches it through `adb reverse`, so it needs no network. The test follows what a person does: it sets the plugin's
settings, opens the channel page, taps a channel, and presses the buttons of the television view; it reads the plugin's saved store
(line health, favorites, the merged list) and the station's request log to see which line the device asked for.

Changed on the device while it runs, and put back: saved settings/store/deck bytes, the attention hold, the orientation and the playing
session. It plays video and sound; the volume is not touched. No public stream list is used.
"""
import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import dump_hierarchy, visible_nodes
from media_device import Device

# Name -> (the picture, the tone). Three different pictures, so a screenshot says which channel plays.
STREAMS = {'one': ('testsrc2', 440), 'two': ('smptebars', 660), 'three': ('rgbtestsrc', 880)}
HTML = b'<html><body>This channel has moved</body></html>'


def list_a(port):
    base = 'http://127.0.0.1:%d' % port
    return '\n'.join([
        '#EXTM3U x-tvg-url="%s/guide.xml"' % base,
        '#EXTINF:-1 tvg-id="one" group-title="News",Channel One HD', base + '/dead/one.m3u8',
        '#EXTINF:-1 tvg-id="two" group-title="News",Channel Two', base + '/hls/two/index.m3u8',
        '#EXTINF:-1 group-title="Movies",Channel Three', base + '/hls/three/index.m3u8',
        '#EXTINF:-1 group-title="Movies",Channel Four', base + '/dead/four.m3u8',
        '#EXTINF:-1 group-title="Movies",Channel Five', base + '/dead/five.m3u8', ''])


def list_b(port):
    base = 'http://127.0.0.1:%d' % port
    return '\n'.join([
        '#EXTM3U',
        '#EXTINF:-1 tvg-id="one",Channel One', base + '/hls/one/index.m3u8',
        '#EXTINF:-1,channel one [1080p]', base + '/html/one.m3u8',
        '#EXTINF:-1,Channel Two HD', base + '/dead/two.m3u8',
        '#EXTINF:-1,Channel Four HD', base + '/hls/three/index.m3u8?as=four',
        '#EXTINF:-1,Channel Four [backup]', base + '/html/four.m3u8',
        '#EXTINF:-1,Channel Five HD', base + '/hls/two/index.m3u8?as=five',
        '#EXTINF:-1,Channel Five [alt]', base + '/html/five.m3u8', ''])


def guide(now):
    def stamp(moment): return moment.strftime('%Y%m%d%H%M%S +0000')
    before, soon, later = now - timedelta(minutes=20), now + timedelta(minutes=25), now + timedelta(minutes=85)
    return '\n'.join([
        '<?xml version="1.0" encoding="UTF-8"?><tv>',
        '<channel id="one"><display-name>Channel One</display-name></channel>',
        '<channel id="two"><display-name>Channel Two</display-name></channel>',
        '<programme start="%s" stop="%s" channel="one"><title>Morning Show One</title></programme>' % (stamp(before), stamp(soon)),
        '<programme start="%s" stop="%s" channel="one"><title>Late News One</title></programme>' % (stamp(soon), stamp(later)),
        '<programme start="%s" stop="%s" channel="two"><title>Cartoons Two</title></programme>' % (stamp(before), stamp(soon)),
        '</tv>', ''])


class Station(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, streams, now):
        super().__init__(('127.0.0.1', port), Handler)
        self.port, self.streams, self.now, self.requests, self.lock = port, streams, now, [], threading.Lock()

    def seen(self, since=0):
        with self.lock: return list(self.requests[since:])


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def reply(self, status, kind, body):
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass

    def do_GET(self):
        path = self.path.split('?')[0]
        with self.server.lock:
            self.server.requests.append({'path': path, 'range': self.headers.get('Range'), 'ua': self.headers.get('User-Agent'), 'at': time.time()})
        port = self.server.port
        if path == '/lists/a.m3u': return self.reply(200, 'audio/x-mpegurl', list_a(port).encode())
        if path == '/lists/b.m3u': return self.reply(200, 'audio/x-mpegurl', list_b(port).encode())
        if path == '/guide.xml': return self.reply(200, 'application/xml', guide(self.server.now()).encode())
        if path.startswith('/html/'): return self.reply(200, 'text/html; charset=utf-8', HTML)
        if path.startswith('/hls/'):
            file = (self.server.streams / path[1:]).resolve()
            if self.server.streams.resolve() in file.parents and file.is_file():
                kind = 'application/vnd.apple.mpegurl' if file.suffix == '.m3u8' else 'video/mp2t'
                return self.reply(200, kind, file.read_bytes())
        self.reply(404, 'text/plain', b'no such channel')


def make_streams(directory):
    for name, (source, tone) in STREAMS.items():
        out = directory / 'hls' / name
        out.mkdir(parents=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', '%s=size=640x360:rate=25' % source,
                        '-f', 'lavfi', '-i', 'sine=frequency=%d:sample_rate=44100' % tone, '-t', '240',
                        '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'main', '-pix_fmt', 'yuv420p', '-g', '50',
                        '-c:a', 'aac', '-b:a', '64k', '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_playlist_type', 'vod',
                        '-hls_segment_filename', str(out / 'seg%03d.ts'), str(out / 'index.m3u8')], check=True, timeout=300)


def fnv(url):
    """The short name the plugin keeps a line's health record under (main.lua: hash)."""
    h = 2166136261
    for byte in url.encode():
        h = ((h ^ byte) * 16777619) & 0xffffffff
    return '%08x' % h


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/iptv')
    parser.add_argument('--port', type=int, default=8771)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    original_ui = json.loads(re.search(r'data="(\{.*\})"',device.debug('ui_state')).group(1))
    device.shell('am','force-stop','moe.kiwi.thinkos')
    saved_paths = ['files/settings/local.json','files/settings/local.json.bak','files/store/iptv.json','files/store/iptv.json.bak',
                   'shared_prefs/deck.xml','shared_prefs/deck.xml.bak']
    saved_files = {p:read_app_file(args.serial,p) for p in saved_paths}
    device.shell('am','start','-W','-n','moe.kiwi.thinkos/.MainActivity')
    device.start_log()
    results = []

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    # The texts the screen shows, in the device's own language: the same tables the app is built from.
    locale = original_ui['locale']
    table = 'zh-Hant' if re.search('TW|HK|Hant', locale) else 'zh-Hans' if locale.startswith('zh') else locale.split('-')[0]
    if not (ROOT / 'shared/i18n/system' / (table + '.json')).exists(): table = 'en'
    system = json.loads((ROOT / 'shared/i18n/system' / (table + '.json')).read_text(encoding='utf-8'))
    plugin = json.loads((ROOT / 'plugins/iptv/i18n' / (table + '.json')).read_text(encoding='utf-8'))

    def say(text, *values, **named):
        for i, value in enumerate(values, 1): text = text.replace('{%d}' % i, str(value))
        for key, value in named.items(): text = text.replace('{%s}' % key, str(value))
        return text

    def tree():
        path = '/sdcard/thinkos-iptv-ui.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def boxes(root=None):
        """Every node with a text or a description: [(text, description, (left, top, right, bottom))]."""
        found = []
        for node in visible_nodes(root if root is not None else tree()):
            numbers = re.findall(r'-?\d+', node.get('bounds', ''))
            if len(numbers) == 4 and (node.get('text') or node.get('content-desc')):
                found.append((node.get('text') or '', node.get('content-desc') or '', tuple(map(int, numbers))))
        return found

    def locate(text=None, desc=None, root=None):
        for t, d, box in boxes(root):
            if text is not None and t == text: return box
            if desc is not None and d == desc: return box
        return None

    def centre(box): return (box[0] + box[2]) // 2, (box[1] + box[3]) // 2

    def wait(test, seconds=20, interval=0.8):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            value = test()
            if value: return value
            time.sleep(interval)
        return None

    def shot(name): device.screenshot(args.output / (name + '.png'))

    def screen():
        left, top, right, bottom = map(int, re.findall(r'\d+', tree().find('node').get('bounds')))
        return right - left, bottom - top

    def orient(value):
        # The receiver reads an int; Device.debug sends integers as longs.
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', value)

    def tap_text(text, seconds=15):
        box = wait(lambda: locate(text=text), seconds)
        if box: device.tap(*centre(box))
        return box is not None

    def tap_desc(desc, seconds=15):
        box = wait(lambda: locate(desc=desc), seconds)
        if box: device.tap(*centre(box))
        return box is not None

    def stored():
        raw = read_app_file(args.serial, 'files/store/iptv.json')
        data = json.loads(raw) if raw else {}
        out = {}
        for key, value in data.items():
            try: out[key] = json.loads(value) if isinstance(value, str) else value
            except ValueError: out[key] = value
        return out

    def configure(**values):
        for key, value in values.items():
            device.debug('setting', scope='iptv', key=key, value=str(value).lower() if isinstance(value, bool) else str(value))
            time.sleep(0.4)

    def media():
        state = device.state()
        if state is None:
            # The logcat reader dies when adb's server restarts; the app's answer is in the log, so start reading it again.
            device.stop_log()
            device.start_log()
            time.sleep(0.5)
            state = device.state()
        return state or {}

    tapped_at = [0.0]

    def tap_row(text):
        """Tap a row of the channel list, scrolling until it is wholly on screen (the list keeps where it was left)."""
        width, height = screen()
        for _ in range(5):
            box = wait(lambda: locate(text=text), 4)
            if box and 112 < box[1] and box[3] < height - 24:
                device.tap(*centre(box))
                tapped_at[0] = time.monotonic()
                return True
            device.shell('input', 'swipe', width // 2, int(height * 0.7), width // 2, int(height * 0.3), 300)
            time.sleep(0.8)
        return False

    def favorite():
        return 'channelone' in ((stored().get('prefs') or {}).get('favorites') or {})

    def make_favorite(on):
        """Press the favorite pill of the channel on screen until channel one is (or is not) a favorite."""
        if favorite() == on: return True
        tap_text(system['media.tv_favorite'])
        return bool(wait(lambda: favorite() == on, 15))

    def playing(title, seconds=40):
        def test():
            state = media()
            return state.get('owner', '').split(':')[0] == 'iptv' and state.get('title') == title and state.get('status') == 'playing' and state.get('videoWidth', 0) > 0
        return wait(test, seconds, 1.0)

    def streams_seen(station, since):
        """The playlists and dead addresses asked for since [since], one entry for each run of the same address: the player asks a
        playlist that answers 404 a few times before it gives up on it."""
        asked = []
        for r in station.seen(since):
            if r['path'].endswith('.m3u8') or '/html/' in r['path'] or '/dead/' in r['path']:
                if not asked or asked[-1] != r['path']: asked.append(r['path'])
        return asked

    skew = int(device.shell('date', '+%s')) - int(time.time())
    work = Path(tempfile.mkdtemp(prefix='thinkos-iptv-'))
    station = None
    original_orientation = None
    try:
        print('making the streams ...', flush=True)
        make_streams(work)
        station = Station(args.port, work, lambda: datetime.now(timezone.utc) + timedelta(seconds=skew))
        threading.Thread(target=station.serve_forever, daemon=True).start()
        device.adb('reverse', 'tcp:%d' % args.port, 'tcp:%d' % args.port)
        base = 'http://127.0.0.1:%d' % args.port
        device.prepare_display()
        orient(8)                                  # landscape: the way television is watched
        time.sleep(2.5)

        # --- the lists ------------------------------------------------------------------------------------------
        # A start from nothing: a run before this one left its lines and their health in the plugin's store.
        # Emptying the lists only does something when they were not empty: so load one first, then take it away again.
        # The plugin's own guide (CCTV's schedule on the internet) is not what this test is about: its guide is the station's.
        configure(test=False, epg_url='', source_1=base + '/lists/a.m3u')
        wait(lambda: (stored().get('list') or {}).get('items'), 45)
        configure(source_1='')
        wait(lambda: not (stored().get('list') or stored().get('health')), 90, 3)
        configure(source_1=base + '/lists/a.m3u', source_2=base + '/lists/b.m3u')
        saved = wait(lambda: (stored().get('list') or {}).get('items') and stored()['list'], 45)
        items = (saved or {}).get('items', [])
        check('two lists became five channels', [i['n'] for i in items] == ['Channel One HD', 'Channel Two', 'Channel Three', 'Channel Four', 'Channel Five'], [i['n'] for i in items])
        check('the same channel from both lists has all its lines, the first list first, better pictures before worse ones',
              [len(i['l']) for i in items] == [3, 2, 1, 3, 3] and [u['u'].replace(base, '') for u in items[0]['l']] == ['/dead/one.m3u8', '/html/one.m3u8', '/hls/one/index.m3u8'],
              [[u['u'].replace(base, '') for u in i['l']] for i in items])
        check('the guide the list names is kept', (saved or {}).get('epg') == base + '/guide.xml', (saved or {}).get('epg'))
        asked = [r['path'] for r in station.seen()]
        # One setting at a time: list a is loaded alone first, then both lists together.
        check('both lists were fetched', asked.count('/lists/a.m3u') >= 1 and asked.count('/lists/b.m3u') == 1, asked)

        tabs = {'fav': 'tab.favorites', 'recent': 'tab.recent', 'all': 'tab.all', 'groups': 'tab.groups'}
        original_view = (stored().get('prefs') or {}).get('view', 'all')      # a run before this one may have left the page on another tab
        device.open_page('iptv.channels')
        wait(lambda: locate(text=plugin['tab.all']), 20)
        tap_text(plugin['tab.all'])
        rows = wait(lambda: locate(text='1  Channel One HD'), 20)
        check('the channel page lists the channels', rows and all(locate(text='%d  %s' % (n, name)) for n, name in
              ((2, 'Channel Two'), (3, 'Channel Three'), (4, 'Channel Four'), (5, 'Channel Five'))))
        labels = [t for t, _, _ in boxes()]
        check('the tabs are present without a redundant channel count', all(plugin[k] in labels for k in ('tab.favorites', 'tab.recent', 'tab.groups')) and not any(re.fullmatch(r'5 channels|共 5 个频道', text) for text in labels), labels)
        shot('channels')

        # --- a channel with dead lines: the third one plays -----------------------------------------------------
        mark = len(station.seen())
        tap_row('1  Channel One HD')
        check('channel one plays', playing('Channel One HD'), media())
        print('     (channel one: %.1f s from the tap to playing, two dead lines in front, no look before the player)' % (time.monotonic() - tapped_at[0]), flush=True)
        order = streams_seen(station, mark)
        check('the dead lines were tried first, in order, and the living one took over',
              order[:3] == ['/dead/one.m3u8', '/html/one.m3u8', '/hls/one/index.m3u8'], order)
        asked = station.seen(mark)
        tries = [r['at'] for r in asked if r['path'] == '/dead/one.m3u8']
        living = [r['at'] for r in asked if r['path'] == '/hls/one/index.m3u8']
        if tries and living:
            print('     (the player asked for the dead playlist %d times over %.1f s; the living line came %.1f s after the first try)'
                  % (len(tries), tries[-1] - tries[0], living[0] - tries[0]), flush=True)

        device.debug('hold_attention', value='ENGAGED')
        time.sleep(0.8)
        width, height = screen()
        if not locate(desc=system['media.tv_next']):
            device.tap(width // 2, height // 2)
            time.sleep(1.5)
        check('the television overlay shows the channel, the buttons and the pills',
              all(locate(desc=d) for d in (system['media.tv_previous'], system['media.tv_next'], say(system['media.tv_channel'], 1, 'Channel One HD')))
              and locate(text=system['media.tv_list']) and locate(text=system['media.tv_favorite']) and locate(text=say(system['media.tv_line'], 3, 3)),
              sorted({t or d for t, d, _ in boxes()}))
        guide_nodes = [t for t, _, _ in boxes() if 'Morning Show One' in t or 'Late News One' in t]
        check('the programme on air and the next one are shown', len(guide_nodes) == 2, guide_nodes)
        shot('live-overlay')
        check('the line that worked is remembered (the others are paused)', wait(lambda: (lambda h: h.get(fnv(base + '/dead/one.m3u8'), {}).get('f') == 1
              and h.get(fnv(base + '/html/one.m3u8'), {}).get('f') == 1 and 'p' in h.get(fnv(base + '/hls/one/index.m3u8'), {}))(stored().get('health', {})), 70, 3),
              stored().get('health'))

        # --- the buttons and the gestures -----------------------------------------------------------------------
        mark = len(station.seen())
        tap_desc(system['media.tv_next'])
        check('next channel plays channel two', playing('Channel Two'), media())
        check('... from its first line', '/hls/two/index.m3u8' in streams_seen(station, mark), streams_seen(station, mark))
        check('the banner follows', locate(desc=say(system['media.tv_channel'], 2, 'Channel Two')) is not None)
        mark = len(station.seen())
        tap_desc(system['media.tv_previous'])
        check('previous channel is channel one again', playing('Channel One HD'), media())
        order = streams_seen(station, mark)
        check('... straight on its working line: the paused ones are not asked again', order == ['/hls/one/index.m3u8'], order)

        was_favorite = favorite()
        make_favorite(False)
        check('the favorite pill is kept', make_favorite(True), stored().get('prefs'))

        tap_desc(system['media.tv_next'])
        playing('Channel Two')
        mark = len(station.seen())
        check('channel two offers two sources', locate(text=say(system['media.tv_line'], 1, 2)) is not None)
        tap_text(say(system['media.tv_line'], 1, 2))
        check('the next source of channel two is dead, so the first one takes over again', wait(lambda: '/dead/two.m3u8' in streams_seen(station, mark), 20)
              and playing('Channel Two') and locate(text=say(system['media.tv_line'], 1, 2)) is not None, streams_seen(station, mark))
        check('the switch is said on screen', say(plugin['notice.switched'], line=1, lines=2) in [t for t, _, _ in boxes()])

        device.debug('touch', x=float(width * 0.9), y=float(height * 0.35), taps=2, gap=120)
        check('a double tap on the right edge is the next channel', playing('Channel Three'), media())
        device.debug('touch', x=float(width * 0.1), y=float(height * 0.35), taps=2, gap=120)
        check('a double tap on the left edge is the previous channel', playing('Channel Two'), media())

        tap_text(system['media.tv_list'])
        check('the list pill opens the channel page', wait(lambda: locate(text=plugin['tab.groups']), 15) is not None)
        check('leaving the live page stops the channel', wait(lambda: media().get('status') == 'stopped', 15), media())
        shot('channels-after-live')

        # --- a line never tested is looked at before the player gets it -------------------------------------------
        # With the tests on but the tester asleep (one test in ten minutes), channel five's first line is dead and has never been seen.
        configure(test=True, test_gap=600)
        mark = len(station.seen())
        device.open_page('iptv.channels')
        assert tap_row('5  Channel Five'), 'Channel five is not reachable in the list'
        check('channel five plays', playing('Channel Five'), media())
        took = time.monotonic() - tapped_at[0]
        dead_five = [r for r in station.seen(mark) if r['path'] == '/dead/five.m3u8']
        check('its dead line was asked for once, by the test, and the player never saw it',
              len(dead_five) == 1 and dead_five[0]['range'] == 'bytes=0-16383', dead_five)
        print('     (channel five: %.1f s from the tap to playing, one dead line in front)' % took, flush=True)
        device.open_page('iptv.channels')
        time.sleep(1.5)

        # --- lines are tested in the background ------------------------------------------------------------------
        # Channel four has never been played: its three lines are for the tester alone.
        mark = len(station.seen())
        configure(test_gap=3)
        four = [base + '/dead/four.m3u8', base + '/hls/three/index.m3u8?as=four', base + '/html/four.m3u8']

        def tested():
            health = stored().get('health', {})
            return all(fnv(url) in health for url in four) and health
        health = wait(tested, 150, 4)
        check('the tester tried all three lines of channel four', health, stored().get('health'))
        if health:
            dead, alive, page = (health[fnv(url)] for url in four)
            check('a missing line and a page that is not a stream are paused, the living one is not',
                  dead.get('f') >= 1 and dead.get('u', 0) > dead.get('t', 0) and page.get('f') >= 1 and 'f' not in alive and 'u' not in alive,
                  {'dead': dead, 'alive': alive, 'page': page})
        probes = [r for r in station.seen(mark) if r['path'] in ('/dead/four.m3u8', '/html/four.m3u8') or r['path'].startswith('/hls/three/')]
        check('the tests ask for the first bytes only, in the name of the player',
              probes and all(r['range'] == 'bytes=0-16383' and r['ua'] == 'ThinkOS/1' for r in probes), probes[:3])

        device.open_page('iptv.channels')
        time.sleep(2.5)
        check('the page shows how many sources of each channel work', wait(lambda: locate(text='1/3') and locate(text='1/2'), 25) is not None,
              [t for t, _, _ in boxes()])
        total = sum(len(i['l']) for i in items)
        check('and in the end every line has been tested', wait(lambda: len(stored().get('health', {})) >= total, 150, 4), len(stored().get('health', {})))
        paused = len([1 for h in stored().get('health', {}).values() if h.get('u', 0) > h.get('t', 0)])
        check('background line counts are not shown as a footer', not any(str(total) + ' ' in text and str(paused) in text for text, _, _ in boxes()),
              [t for t, _, _ in boxes()])
        check('the programme on air is under the channel name', locate(text='Morning Show One') is not None and locate(text='Cartoons Two') is not None)
        shot('channels-tested')
        tap_text(plugin['tab.favorites'])
        time.sleep(1.5)
        check('the favorites tab holds the favorite', locate(text='1  Channel One HD') is not None and locate(text='2  Channel Two') is None)
        shot('channels-favorites')
        if not was_favorite:
            # Put the favorite back as it was: open the channel, press the pill.
            tap_row('1  Channel One HD')
            playing('Channel One HD')
            device.debug('hold_attention', value='ENGAGED')
            if not locate(desc=system['media.tv_next']):
                device.tap(width // 2, height // 2)
                time.sleep(1.5)
            check('and the favorite is put back as it was', make_favorite(False), stored().get('prefs'))

        # --- nothing is left behind ------------------------------------------------------------------------------
        device.open_page('iptv.channels')
        time.sleep(1.5)
        tap_text(plugin[tabs[original_view]])
        fatal = [line for line in device.log_since(0) if 'FATAL EXCEPTION' in line or 'AndroidRuntime' in line]
        check('the app did not crash', not fatal, fatal[:3])
    finally:
        try:
            device.debug('media_stop')
            device.shell('am','force-stop','moe.kiwi.thinkos')
            for path,content in saved_files.items(): restore_app_file(args.serial,path,content)
            restored={p:read_app_file(args.serial,p)==v for p,v in saved_files.items()}
            (args.output/'restored-files.json').write_text(json.dumps(restored,indent=2)+'\n')
            assert all(restored.values())
            device.shell('am','start','-W','-n','moe.kiwi.thinkos/.MainActivity')
            for command,key in (('hold_attention','attention'),('theme','theme'),('lux','lux')):
                device.debug(command,**({'value':original_ui[key]} if original_ui[key] is not None else {}))
            orient(original_ui['orientation'])
        except Exception as error:
            print('cleanup failed:', error, file=sys.stderr)
            results.append(False)
        device.adb('reverse', '--remove', 'tcp:%d' % args.port, check=False)
        (args.output/'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n')
        device.stop_log()
        if station:
            station.shutdown()
        shutil.rmtree(work, ignore_errors=True)
    failed = results.count(False)
    print('\n%d/%d checks passed' % (len(results) - failed, len(results)))
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
