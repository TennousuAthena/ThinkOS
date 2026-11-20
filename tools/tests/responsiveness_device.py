#!/usr/bin/env python3
"""Collect ThinkOS input-to-frame baselines. Confirm the device and timing before running."""
import argparse
from collections import defaultdict
from contextlib import ExitStack, nullcontext
import json
import math
import os
from pathlib import Path
import random
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from app_network_block import AppNetworkBlock
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore, OPTIONAL_PATHS, PATHS
from device_touch import path
from gestures import edge_swipe, fling, slow_drag, tap
from http_fixture import make_server
from latency_report import parse_framestats, parse_latency_report
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from ui_walkthrough import broadcast_json

SCENARIOS = tuple('S' + str(number) for number in range(1, 15))
WARMUP_SCENARIOS = {'S1b-Aero': ('aero.main', 'gallery.slideshow'), 'S1b-Tiles': ('tiles.apps', 'tiles.main')}
FLIP_SCENARIO = 'S5b'
DRAFT_P95 = {'first_ms': 33, 'follow_ms': 25, 'launch_ms': 50, 'press_ms': 33, 'plugin_ms': 50, 'wake_ms': 100}
R3_P95 = {**DRAFT_P95, 'first_ms': 50, 'follow_ms': 40, 'launch_ms': 66, 'plugin_ms': 70, 'publishWaitMs': 2,
          'clock_awake_late_ms': 40}
MAX_ATRACE_BYTES = 64 * 1024 * 1024
APP_START = 'tiles.apps'
CAST_PAGE = 'cast.home'
CLOCK_PAGE = 'clock.face'
PLAYER_PAGE = 'player.library'
GALLERY_PAGE = 'gallery.slideshow'
WEATHER_PROBE = 'responsiveness-weather-probe'


class FixtureUnavailable(RuntimeError):
    pass


def stats(values):
    ordered = sorted(values)
    if not ordered:
        return None
    return {'n': len(ordered), 'p50': ordered[math.ceil(len(ordered) * .5) - 1],
            'p95': ordered[math.ceil(len(ordered) * .95) - 1], 'max': ordered[-1]}


def compare(baseline, current):
    """Compare matching P95 values in two summary.json objects."""
    regressions = []
    for scenario, now in current['scenarios'].items():
        then = baseline['scenarios'].get(scenario, {}).get('metrics', {})
        for metric, old in then.items():
            if old is None:
                continue
            new = now['metrics'].get(metric)
            if new is None or new['p95'] > old['p95'] * 1.20:
                regressions.append({'scenario': scenario, 'metric': metric, 'baseline_p95': old['p95'],
                                    'current_p95': None if new is None else new['p95']})
    return regressions


def write_json(pathname, value):
    pathname.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def wait_for(check, seconds, reason):
    deadline = time.monotonic() + seconds
    while True:
        value = check()
        if value is not None:
            return value
        if time.monotonic() >= deadline:
            raise AssertionError(reason)
        time.sleep(.06)


def center(box):
    left, top, right, bottom = box
    return (left + right) // 2, (top + bottom) // 2


class Run:
    def __init__(self, device, seed, media_sample, gfx_only=False):
        self.device = device
        self.rng = random.Random(seed)
        self.media_sample = media_sample
        self.gfx_only = gfx_only
        self.temp_name = 'thinkos-responsiveness-' + secrets.token_hex(8)
        self.screen = (800, 1280)
        self.system = json.loads((ROOT / 'shared/i18n/system/zh-Hans.json').read_text())
        self.player = json.loads((ROOT / 'plugins/player/i18n/zh-Hans.json').read_text())
        self.remote_media = None
        self.gallery = None
        self.weather = None
        self.weather_id = None
        self.trace_path = None

    def stage(self):
        state = broadcast_json(self.device, 'stage_state')
        if not state.get('width') or not state.get('height'):
            raise FixtureUnavailable('ThinkOS stage is not visible')
        return state

    def settled(self, expected, seconds=2):
        def check():
            state = self.stage()
            return state if state.get('settled') and all(state.get(key) == value for key, value in expected.items()) else None
        return wait_for(check, seconds, 'Stage did not settle at ' + repr(expected))

    def page(self, page):
        self.device.debug('page', value=page)
        return self.settled({'page': page, 'panelOpen': False}, 8)

    def tree(self):
        remote = '/sdcard/' + self.temp_name + '.xml'
        try:
            dump_hierarchy(self.device.serial, remote)
            return ET.fromstring(self.device.shell('cat', remote))
        finally:
            self.device.shell('rm', '-f', remote)

    def node(self, label, tree=None):
        tree = self.tree() if tree is None else tree
        for node in visible_nodes(tree):
            if label in (node.get('text'), node.get('content-desc')):
                return node
        raise AssertionError('Visible control missing: ' + label)

    def tap_control(self, label):
        self.device.tap(*center(node_bounds(self.node(label))))

    def take(self, label, points, *, expected, kind, finish='up', plugin=None, prepare_page=None, settle_delay=0):
        mark = self.device.log_mark()
        self.device.shell('dumpsys', 'gfxinfo', APP, 'reset')
        if not self.gfx_only:
            self.device.debug('clock_reset')
            self.device.debug('latency_reset')
        weather_start = self.weather.responses if self.weather is not None else None
        trace_path = self.trace_path
        self.trace_path = None
        if trace_path:
            self.device.shell('atrace', '--async_start', '-b', 2048, '-a', APP, 'gfx', 'view', 'input', 'sched', 'freq')
        try:
            touch_output = path(self.device, points, finish=finish, prepare_page=prepare_page, settle_delay=settle_delay)
            try:
                state = self.settled(expected)
                failure = None
            except AssertionError as error:
                state = self.stage()
                failure = str(error)
            if not self.gfx_only:
                report = parse_latency_report(self.device.debug('latency_report'))
        finally:
            if trace_path:
                raw = self.device.adb('shell', '-T', 'atrace -z --async_stop')
                header, marker, payload = raw.partition(b'TRACE:\n')
                if not marker:
                    trace_path.write_bytes(raw)
                else:
                    try:
                        decoder = zlib.decompressobj()
                        trace = decoder.decompress(payload, MAX_ATRACE_BYTES + 1)
                        if len(trace) > MAX_ATRACE_BYTES or not decoder.eof:
                            raise ValueError('atrace is incomplete or exceeds the output limit')
                        trace_path.write_bytes(header + marker + trace)
                    except (ValueError, zlib.error) as error:
                        trace_path.with_suffix('.atrace.z').write_bytes(raw)
                        raise FixtureUnavailable(str(error)) from error
        weather = {} if weather_start is None else {'weather_responses': self.weather.responses - weather_start}
        gfx = self.device.shell('dumpsys', 'gfxinfo', APP, 'framestats')
        sample = {'label': label, 'state': state, 'expected_kind': kind, 'expected_plugin': plugin,
                  'functional_error': failure, 'framestats': parse_framestats(gfx), **weather}
        if prepare_page is not None:
            match = re.search(r'THINKOS_DOWN_AFTER_SETTLE (\d+)', touch_output)
            if match is None:
                raise FixtureUnavailable('Missing warmup DOWN timing')
            sample.update(settle_delay_ms=settle_delay, down_after_settle_ms=int(match[1]))
        if self.gfx_only:
            return sample
        self.device.debug('clock_report')
        clock = wait_for(lambda: next((line.split('DEBUG clock ', 1)[1] for line in self.device.log_since(mark)
                                       if 'DEBUG clock n=' in line), None), 1, 'Missing clock report')
        clock_details = broadcast_json(self.device, 'clock_details')
        sample.update(trace=report, clock=clock, clock_details=clock_details)
        if trace_path:
            write_json(trace_path.with_suffix('.atrace.json'), sample)
        return sample

    def drag(self, start, end, duration=250, *, slow=False, **kwargs):
        gesture = slow_drag if slow else fling
        return gesture(start, end, round(duration * self.rng.uniform(.9, 1.1)), size=self.screen, **kwargs)

    def edge(self, edge, along, distance, duration=250):
        return edge_swipe(edge, along, distance, round(duration * self.rng.uniform(.9, 1.1)), size=self.screen)

    def app_points(self):
        width, height = self.screen
        return tap(width * .82, height * .30, size=self.screen)

    def open_cast(self):
        self.page(APP_START)
        path(self.device, self.app_points())
        self.settled({'page': CAST_PAGE})

    def player_tab(self, name):
        return self.node(self.player['variant.' + name])

    def local_library(self):
        self.page(PLAYER_PAGE)
        library = self.node(self.player['group.library'])
        if library.get('selected') != 'true':
            self.device.tap(*center(node_bounds(library)))
            time.sleep(.35)

    def plugin_tap(self):
        self.local_library()
        tab = self.player_tab('recent')
        if tab.get('selected') == 'true':
            self.device.tap(*center(node_bounds(self.player_tab('music'))))
            time.sleep(.35)
            tab = self.player_tab('recent')
        sample = self.take('plugin', tap(*center(node_bounds(tab)), size=self.screen), expected={'page': PLAYER_PAGE}, kind='tap', plugin='player')
        if self.player_tab('recent').get('selected') != 'true':
            sample['functional_error'] = 'Player Recent tab was not selected'
        return [sample]

    def scenario(self, scenario, index):
        if scenario in WARMUP_SCENARIOS:
            start, end = WARMUP_SCENARIOS[scenario]
            self.page(CLOCK_PAGE if start == 'aero.main' else 'home.main')
            width, height = self.screen
            delay = self.rng.randint(0, 500)
            points = self.drag((width*.75, height*.5), (width*.25, height*.5))
            return [self.take('warmup-swipe', points, expected={'page': end}, kind='drag-h',
                              prepare_page=start, settle_delay=delay)]
        width, height = self.screen
        if scenario in ('S1', 'S2', 'S3', 'S11'):
            state = self.page(CLOCK_PAGE)
        if scenario == 'S1':
            if not state.get('nextPage'):
                raise FixtureUnavailable('Clock has no adjacent page')
            return [self.take('horizontal', self.drag((width*.75, height*.5), (width*.25, height*.5)),
                              expected={'page': state['nextPage']}, kind='drag-h')]
        if scenario == 'S2':
            points = self.drag((width*.7, height*.5), (width*.3, height*.5), 1500, slow=True, reverse_at=.5)
            return [self.take('slow-reverse', points, expected={'page': CLOCK_PAGE}, kind='drag-h')]
        if scenario == 'S3':
            if not state.get('nextVariant'):
                raise FixtureUnavailable('Clock has no adjacent variant')
            return [self.take('variant', self.drag((width*.5, height*.75), (width*.5, height*.3)),
                              expected={'page': CLOCK_PAGE, 'variant': state['nextVariant']}, kind='drag-v')]
        if scenario == 'S4':
            self.page('home.main')
            return [self.take('stack', self.drag((width*.5, height*.82), (width*.5, height*.58), 650, slow=True),
                              expected={'page': 'home.main'}, kind='stack')]
        if scenario == 'S5':
            self.page(APP_START)
            launched = self.take('launch', self.app_points(), expected={'page': CAST_PAGE}, kind='tap')
            if launched['functional_error']:
                return [launched]
            time.sleep(1)
            return [launched, self.take('return', self.edge('left', height*.5, width*.55), expected={'page': APP_START}, kind='back')]
        if scenario == FLIP_SCENARIO:
            self.page(APP_START)
            launched = self.take('launch', tap(width*.195, height*.491, hold_ms=310, size=self.screen),
                                 expected={'page': 'apps.main'}, kind='tap')
            if not launched['functional_error']:
                self.device.shell('input', 'keyevent', 'KEYCODE_BACK')
                self.settled({'page': APP_START})
            return [launched]
        if scenario == 'S6':
            self.open_cast()
            commit = index % 2 == 0
            points = self.edge('left', height*.5, width * (.55 if commit else .12), 250 if commit else 600)
            return [self.take('return' if commit else 'cancel-return', points, expected={'page': APP_START if commit else CAST_PAGE},
                              kind='back')]
        if scenario == 'S7':
            self.page(CLOCK_PAGE)
            opening = self.take('panel-open', self.edge('top', width*.5, height*.43), expected={'panelOpen': True}, kind='panel')
            if opening['functional_error']:
                return [opening]
            left, top, right, bottom = node_bounds(self.node(self.system['media_brightness']))
            y = (top + bottom) / 2
            slider = self.drag(((left*2+right)/3, y), ((left+right*2)/3, y), 2000, slow=True)
            moved = self.take('panel-slider', slider, expected={'panelOpen': True}, kind='slider')
            closed = self.take('panel-close', self.edge('left', height*.35, width*.55), expected={'panelOpen': False}, kind='back')
            return [opening, moved, closed]
        if scenario == 'S8':
            self.open_cast()
            return [self.take('home', self.edge('bottom', width*.5, height*.55),
                              expected={'page': 'home.main', 'canGoBack': False}, kind='up')]
        if scenario == 'S9':
            self.page('home.main')
            self.device.debug('settings', value='system')
            self.settled({'page': 'settings.form'})
            self.tap_control(self.system['setting_section_display'])
            time.sleep(.25)
            self.tap_control(self.system['setting_display_brightness_max'])
            time.sleep(.25)
            tree = self.tree()
            minus, plus = node_bounds(self.node('−', tree)), node_bounds(self.node('+', tree))
            y = center(minus)[1]
            points = self.drag((minus[2]+20, y), (plus[0]-20, y), 2000, slow=True)
            return [self.take('settings-slider', points, expected={'page': 'settings.form'}, kind='slider', finish='cancel')]
        if scenario == 'S10':
            return self.plugin_tap()
        if scenario == 'S11':
            self.device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'hold_attention',
                              '--es', 'value', 'AMBIENT', '--ez', 'until_touch', 'true')
            self.settled({'attention': 'AMBIENT'})
            try:
                return [self.take('wake', tap(width*.5, height*.5, size=self.screen),
                                  expected={'page': CLOCK_PAGE, 'attention': 'ENGAGED'}, kind='tap')]
            finally:
                self.device.debug('hold_attention', value='AWAKE')
        if scenario == 'S12':
            return self.seek()
        if scenario == 'S13':
            if self.gallery is None:
                self.prepare_gallery()
            state = self.page(GALLERY_PAGE)
            next_page = state.get('nextPage')
            if not next_page:
                raise FixtureUnavailable('Gallery has no adjacent page')
            samples = []
            for turn in range(10):
                out = turn % 2 == 0
                start, end = (.75, .25) if out else (.25, .75)
                samples.append(self.take('gallery-' + str(turn + 1), self.drag((width*start, height*.5), (width*end, height*.5)),
                                         expected={'page': next_page if out else GALLERY_PAGE}, kind='drag-h'))
                if samples[-1]['functional_error']:
                    break
            return samples
        if self.weather is None:
            raise FixtureUnavailable('S14 requires --fixture')
        return self.plugin_tap()

    def prepare_gallery(self):
        from gallery_device import generate_photos
        self.gallery = '/sdcard/Android/data/' + APP + '/files/Pictures/' + self.temp_name
        self.device.shell('mkdir', '-p', self.gallery)
        with tempfile.TemporaryDirectory() as temporary:
            generate_photos(Path(temporary))
            for image in sorted(Path(temporary).glob('*.png')):
                self.device.push(image, self.gallery + '/' + image.name)
        self.device.debug('setting', scope='gallery', key='source', value='local')
        self.device.debug('setting', scope='gallery', key='local_dir', value=self.gallery)
        self.page(GALLERY_PAGE)
        time.sleep(2)

    def seek(self):
        if not self.media_sample.is_file():
            raise FixtureUnavailable('S12 needs a local audio sample from tools/media/gen_samples.sh')
        if self.remote_media is None:
            self.remote_media = '/sdcard/Android/data/' + APP + '/files/Music/' + self.temp_name
            self.device.shell('mkdir', '-p', self.remote_media)
            self.device.push(self.media_sample, self.remote_media + '/' + self.media_sample.name)
            self.device.debug('setting', scope='player', key='music_dir', value=self.remote_media)
        self.local_library()
        self.device.tap(*center(node_bounds(self.player_tab('music'))))
        time.sleep(.5)
        self.tap_control(self.media_sample.stem)
        wait_for(lambda: True if broadcast_json(self.device, 'media_state').get('seekable') else None, 8, 'Sample did not become seekable')
        self.device.debug('media_pause')
        self.page('player.audio')
        left, top, right, bottom = node_bounds(self.node(self.system['media.seek']))
        y = (top + bottom)/2
        points = self.drag(((left*2+right)/3,y), ((left+right*2)/3,y), 850, slow=True)
        try:
            result = self.take('seek', points, expected={'page': 'player.audio'}, kind='seek')
            media = broadcast_json(self.device, 'media_state')
            if not media.get('seekable') or media['position'] <= media['duration'] * .4:
                result['functional_error'] = 'The seek bar did not move playback to the expected position'
            return [result]
        finally:
            self.device.debug('media_stop')

    def cleanup(self):
        self.device.debug('latency_off')
        self.device.debug('media_stop')
        for remote in (self.gallery, self.remote_media):
            if remote is not None:
                self.device.shell('rm', '-rf', remote)

    def load_weather(self, stack, port, server):
        self.weather_id = WEATHER_PROBE + '-' + secrets.token_hex(8)
        mark = self.device.log_mark()
        with tempfile.TemporaryDirectory(prefix='thinkos-weather-load-') as temporary:
            source = Path(temporary) / 'source'
            shutil.copytree(ROOT / 'plugins/weather', source)
            manifest = json.loads((source/'manifest.json').read_text())
            manifest['id'] = self.weather_id
            for key in ('pages', 'widgets', 'exports'):
                manifest.pop(key, None)
            manifest['settings']['endpoint']['default'] = f'http://127.0.0.1:{port}/weather-load'
            write_json(source/'manifest.json', manifest)
            lua = source/'main.lua'
            start = 'tos.on("start", function()\n'
            source_code = lua.read_text()
            if source_code.count(start) != 1:
                raise FixtureUnavailable('Weather start handler could not be instrumented')
            lua.write_text(source_code.replace(start, start +
                           '  tos.timer.every(.4, function() if not inflight then refresh() end end)\n', 1))
            directory, _ = package_plugin(source, Path(temporary) / 'output')
            stack.callback(remove_plugin, self.weather_id, self.device.serial)
            publish_directory(directory, self.device.serial)
        self.weather = server
        stack.callback(setattr, self, 'weather', None)
        tag = '[plugin:' + self.weather_id + '] weather refresh ok'
        try:
            wait_for(lambda: True if any(tag in line for line in self.device.log_since(mark)) else None,
                     20, 'Weather fixture did not parse a local forecast')
        except AssertionError as error:
            raise FixtureUnavailable(str(error)) from error


def assess(metrics, scenario):
    stats_by_metric = {name: stats(values) for name, values in sorted(metrics.items()) if values}

    def exceeded(name, field, limit):
        return name in stats_by_metric and stats_by_metric[name][field] > limit

    draft = [name for name, limit in DRAFT_P95.items() if exceeded(name, 'p95', limit)]
    provisional_p95 = dict(R3_P95)
    if scenario == 'S14':
        provisional_p95['plugin_ms'] = 80
        del provisional_p95['publishWaitMs']
    provisional = [name for name, limit in provisional_p95.items() if exceeded(name, 'p95', limit)]
    if scenario in WARMUP_SCENARIOS and exceeded('downDispatchMs', 'p95', 16):
        provisional.append('downDispatchMs')
    for name, field, limit in [('drag_gap_count', 'p95', 0), ('flip_gap_count', 'max', 0), ('clock_max_ms', 'max', 0)]:
        if exceeded(name, field, limit):
            draft.append(name)
    for name, limit in [('clock_whole_period_late', 0), ('clock_awake_late_ms', 80)]:
        if exceeded(name, 'max', limit) and name not in provisional:
            provisional.append(name)
    for name, limit in [('clock_skipped', 0), ('blocking_gc', 1)]:
        if exceeded(name, 'max', limit):
            draft.append(name)
            provisional.append(name)
    gap_free = {}
    for name in ('drag_gap_count', 'flip_gap_count'):
        if values := metrics.get(name):
            gap_free[name] = {'zero': values.count(0), 'total': len(values)}
            if values.count(0) * 10 < len(values) * 9:
                provisional.append(name)
    return stats_by_metric, draft, provisional, gap_free


def summarize(samples, scenario=None):
    metrics = defaultdict(list)
    errors = []
    functional = []
    for sample in samples:
        label = sample['label']
        if sample.get('functional_error'):
            functional.append({'label': label, 'error': sample['functional_error']})
            continue
        if 'weather_responses' in sample and sample['weather_responses'] == 0:
            errors.append(label + ': no concurrent weather responses')
        gfx = sample.get('framestats', [])
        if gfx:
            metrics['gfx_frame_ms'].extend(round((frame['FrameCompleted'] - frame['IntendedVsync']) / 1e6, 2) for frame in gfx)
            vsyncs = sorted({frame['Vsync'] for frame in gfx})
            metrics['gfx_gap_ms'].extend(round((last - first) / 1e6, 2) for first, last in zip(vsyncs, vsyncs[1:]))
        if 'trace' not in sample:
            if not gfx:
                errors.append(label + ': no gfxinfo frames')
            continue
        trace = sample['trace']
        gestures = [gesture for gesture in trace['gestures'] if gesture['complete'] and gesture['kind'] == sample['expected_kind']]
        if len(gestures) != 1 or trace['dropped']:
            errors.append(label + ': missing gesture or dropped records')
            continue
        gesture = gestures[0]
        if 'down_after_settle_ms' in sample:
            metrics['downAfterSettleMs'].append(sample['down_after_settle_ms'])
            if 'downDispatchMs' in gesture:
                metrics['downDispatchMs'].append(gesture['downDispatchMs'])
        if gesture['kind'] != 'tap':
            if ('firstSwap' not in gesture or not gesture.get('followLagMs') or
                    gesture['unpairedMoves'] or not gesture.get('frameIntervalsMs')):
                errors.append(label + ': incomplete drag frames')
            if 'firstSwap' in gesture:
                metrics['first_ms'].append(round(gesture['firstSwap'] - gesture['firstMove'], 2))
            metrics['follow_ms'].extend(gesture['followLagMs'])
            if gesture['frameIntervalsMs']:
                metrics['drag_gap_count'].append(sum(gap > 25 for gap in gesture['frameIntervalsMs']))
        for field, key, start in [('pressSwap', 'press_ms', 'down'), ('launchSwap', 'launch_ms', 'end'), ('wakeSwap', 'wake_ms', 'down')]:
            if field in gesture:
                metrics[key].append(round(gesture[field] - gesture[start], 2))
            elif (label, key) in (('launch', 'launch_ms'), ('launch', 'press_ms'), ('wake', 'wake_ms')):
                errors.append(label + ': missing ' + field)
        if label == 'launch':
            gaps = gesture.get('launchIntervalsMs', [])
            if not gaps:
                errors.append(label + ': missing flip frames')
            else:
                metrics['flip_gap_count'].append(sum(gap > 33 for gap in gaps))
        if 'gcBlocking' in gesture:
            metrics['blocking_gc'].append(gesture['gcBlocking'])
        else:
            errors.append(label + ': missing GC counter')
        if 'gcTotal' in gesture:
            metrics['total_gc'].append(gesture['gcTotal'])
        for phase, durations in gesture['marks'].items():
            metrics['mark_' + phase + '_ms'].extend(durations)
        if sample['expected_plugin']:
            taps = [tap for tap in trace['taps'] if tap['plugin'] == sample['expected_plugin'] and tap['gestureId'] == gesture['id']]
            if not any('swap' in tap for tap in taps):
                errors.append(label + ': no state-bind frame linked to UP')
            for event in taps:
                for key in ('upToEventMs', 'queueMs', 'luaMs', 'publishWaitMs', 'publishAfterLuaMs', 'deliverMs',
                            'diffMs', 'bindMs', 'bindToSwapMs'):
                    if key in event:
                        metrics[key].append(event[key])
                if 'swap' in event:
                    metrics['plugin_ms'].append(round(event['swap'] - event['up'], 2))
        clock = re.search(r'max=(-?\d+)ms skipped=(\d+)', sample['clock'])
        if clock:
            metrics['clock_max_ms'].append(int(clock[1]))
        if details := sample.get('clock_details'):
            metrics['clock_awake_late_ms'].extend(details['awakeLatenessMs'])
            metrics['clock_whole_period_late'].append(details['wholePeriodsLate'])
            metrics['clock_skipped'].append(details['skipped'])
        elif clock:
            metrics['clock_skipped'].append(int(clock[2]))
    result, draft, provisional, gap_free = assess(metrics, scenario)
    return {'metrics': result, 'draft_misses': draft, 'provisional_misses': provisional, 'gap_free': gap_free,
            'functional_failures': functional, 'measurement_errors': errors}


def start_weather_fixture(device, stack):
    forecast = json.loads((ROOT/'tools/tests/fixtures/weather/forecast.json').read_text())
    now = int(time.time())
    delta = now // 86400 * 86400 - 28800 - forecast['daily']['time'][0]
    for kind in ('hourly', 'daily'):
        forecast[kind]['time'] = [stamp + delta for stamp in forecast[kind]['time']]
    forecast['current']['time'] = now // 900 * 900
    server = stack.enter_context(make_server(0, response_bytes=192 * 1024, body=json.dumps(forecast).encode()))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    stack.callback(server.shutdown)
    port = server.server_port
    device.adb('reverse', '--no-rebind', f'tcp:{port}', f'tcp:{port}')
    stack.callback(device.adb, 'reverse', '--remove', f'tcp:{port}')
    return port, server


def capture_atrace(run, scenario, output):
    run.trace_path = output / (scenario + '.atrace')
    try:
        run.scenario(scenario, 0)
    finally:
        run.trace_path = None


def environment(device, args):
    foreground = device.shell('dumpsys', 'activity', 'activities')
    if not re.search(r'mResumedActivity:.*' + re.escape(APP) + '/', foreground):
        raise FixtureUnavailable('ThinkOS is not in the foreground')
    package = device.shell('dumpsys', 'package', APP)
    installed = device.shell('pm', 'path', APP).strip().removeprefix('package:')
    return {'serial': device.serial, 'apk_version': re.search(r'versionName=(\S+)', package)[1],
            'apk_code': int(re.search(r'versionCode=(\d+)', package)[1]), 'apk_sha256': device.shell('sha256sum', installed).split()[0],
            'git_rev': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
            'seed': args.seed, 'repeat': args.repeat, 'online': args.online, 'fixture': args.fixture, 'sampling_hz': 120}


def render_summary(report):
    gfx_only = report.get('environment', {}).get('gfx_only', False)
    method = 'InputTrace 关闭，只记录 gfxinfo 帧耗时和 vsync 间隔。' if gfx_only else (
        'FrameMetrics 与触摸事件时间配对。间隔用 vsync 间距。面板扫描与合成时间未计入。')
    lines = ['# ThinkOS 真机跟手性', '', method, '']
    for scenario, result in report['scenarios'].items():
        lines += ['## ' + scenario, '', '| 指标 | P50 | P95 | Max | 样本数 |', '|---|---:|---:|---:|---:|']
        for name, values in result['metrics'].items():
            lines.append(f"| {name} | {values['p50']} | {values['p95']} | {values['max']} | {values['n']} |")
        lines.append('')
        if not gfx_only:
            lines += ['草案门槛超限 ' + (', '.join(result['draft_misses']) or '0'),
                      '暂定门槛超限 ' + (', '.join(result['provisional_misses']) or '0')]
        lines += [f"功能失败 {len(result['functional_failures'])}，测量问题 {len(result['measurement_errors'])}", '']
        if 'unavailable' in result:
            lines += [result['unavailable'], '']
    if 'environment_error' in report:
        lines += ['环境问题 ' + report['environment_error'], '']
    if 'regressions' in report:
        lines += [f"P95 基线比较退化 {len(report['regressions'])} 项", '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--scenario', action='append', choices=SCENARIOS + tuple(WARMUP_SCENARIOS) + (FLIP_SCENARIO,))
    parser.add_argument('--repeat', type=int, default=20, choices=range(1, 101), metavar='1..100')
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--theme', choices=('LIGHT', 'DARK', 'NIGHT'), help='Hold a theme for comparison with a baseline')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--online', action='store_true')
    parser.add_argument('--fixture', action='store_true', help='Enable bounded local weather load for S14')
    parser.add_argument('--atrace', action='append', choices=SCENARIOS, default=[])
    parser.add_argument('--gfx-only', action='store_true', help='Compare frame durations using gfxinfo with InputTrace off')
    parser.add_argument('--page-diagnostics', choices=('page', 'pss'), help='PSS probes perturb timings and need a separate run')
    parser.add_argument('--meminfo-every', type=int, choices=range(1, 101), metavar='1..100',
                        help='In a separate diagnostic run, record memory and object counts between rounds')
    parser.add_argument('--cpu-seconds', type=int, choices=range(10, 601), metavar='10..600',
                        help='Sample idle process CPU in each of AWAKE and AMBIENT before enabling traces')
    parser.add_argument('--media-sample', type=Path, default=Path('/tmp/thinkos-media-samples/audio-flac-16bit-44k.flac'))
    args = parser.parse_args()
    scenarios = tuple(dict.fromkeys(args.scenario or SCENARIOS))
    if not set(args.atrace) <= set(scenarios):
        parser.error('--atrace must select a scenario included in this run')
    if args.gfx_only and (args.atrace or args.page_diagnostics or args.baseline):
        parser.error('--gfx-only cannot collect traces or compare input latency against a baseline')
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        parser.error('--output must be empty so an earlier run is preserved')
    os.environ['ADB_MDNS'] = '0'
    device = Device(args.serial)
    report = {'scenarios': {}, 'draft_p95': DRAFT_P95, 'provisional_p95': R3_P95}
    run = Run(device, args.seed, args.media_sample, gfx_only=args.gfx_only)
    try:
        report['environment'] = environment(device, args)
        ui = broadcast_json(device, 'ui_state')
        if ui['onboardingOpen'] or ui['safeMode'] or broadcast_json(device, 'media_state').get('status') in ('playing', 'buffering'):
            raise FixtureUnavailable('Close the current onboarding, recovery or playback session first')
        volume = int(re.search(r'volume is (\d+)\b', device.shell('media', 'volume', '--stream', 3, '--get'))[1])
        journal = capture(device, ui, paths=PATHS+OPTIONAL_PATHS)
        try:
            with ExitStack() as stack:
                weather = start_weather_fixture(device, stack) if args.fixture and 'S14' in scenarios else None
                network = nullcontext() if args.online else AppNetworkBlock(args.serial, loopback_port=weather[0] if weather else None)
                stack.enter_context(network)
                stack.callback(run.cleanup)
                device.shell('media', 'volume', '--stream', 3, '--set', 0)
                device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')
                device.debug('locale', value='zh-CN')
                device.debug('hold_attention', value='AWAKE')
                if args.theme:
                    device.debug('theme', value=args.theme)
                device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 1)
                device.debug('setting', scope='clock', key='seconds', value='true')
                run.page(CLOCK_PAGE)
                state = run.stage()
                if not state.get('animatorsEnabled') or 'settled' not in state or state['width'] >= state['height']:
                    raise FixtureUnavailable('Expected portrait, animations enabled and the instrumented debug APK')
                if not re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get')):
                    raise FixtureUnavailable('Media volume did not reach zero')
                run.screen = state['width'], state['height']
                details = device.debug('benchmark_state').split('data="', 1)[-1].split('|')
                if args.theme and details[2] != args.theme:
                    raise FixtureUnavailable('Expected theme ' + args.theme + ', got ' + details[2])
                report['environment'].update(screen=list(run.screen), attention='AWAKE', theme=details[2], orientation='portrait')
                if args.cpu_seconds:
                    for attention in ('AWAKE', 'AMBIENT'):
                        device.debug('hold_attention', value=attention)
                        run.settled({'page': CLOCK_PAGE, 'attention': attention})
                        time.sleep(2)
                        cpu_path = args.output / f'cpu-{attention.lower()}.json'
                        subprocess.run([sys.executable, str(ROOT/'tools/sample_cpu.py'), '--serial', args.serial,
                                        '--pid', device.shell('pidof', APP).strip(), '--seconds', str(args.cpu_seconds),
                                        '--output', str(cpu_path)], check=True, timeout=args.cpu_seconds+20)
                    device.debug('hold_attention', value='AWAKE')
                    run.settled({'page': CLOCK_PAGE, 'attention': 'AWAKE'})
                device.start_log(clear=False)
                stack.callback(device.stop_log)
                if args.gfx_only:
                    device.debug('latency_off')
                else:
                    device.debug('latency_on', **({'value': args.page_diagnostics} if args.page_diagnostics else {}))
                report['environment']['gfx_only'] = args.gfx_only
                report['environment']['page_diagnostics'] = args.page_diagnostics
                report['environment']['meminfo_every'] = args.meminfo_every
                (args.output/'meminfo-before.txt').write_text(device.shell('dumpsys', 'meminfo', APP))
                for scenario in scenarios:
                    samples = []
                    unavailable = None
                    try:
                        with ExitStack() as scenario_resources:
                            if scenario == 'S14' and weather is not None:
                                run.load_weather(scenario_resources, *weather)
                            for index in range(args.repeat):
                                print(f'{scenario}  {index + 1}/{args.repeat}', flush=True)
                                try:
                                    samples.extend(run.scenario(scenario, index))
                                except AssertionError as error:
                                    samples.append({'label': f'{scenario}-{index+1}', 'functional_error': str(error)})
                                round_number = index + 1
                                if args.meminfo_every and round_number % args.meminfo_every == 0:
                                    (args.output / f'meminfo-{scenario}-{round_number:03}.txt').write_text(
                                        device.shell('dumpsys', 'meminfo', APP))
                            if scenario in args.atrace:
                                capture_atrace(run, scenario, args.output)
                    except FixtureUnavailable as error:
                        unavailable = str(error)
                    finally:
                        result = summarize(samples, scenario)
                        if unavailable:
                            result['unavailable'] = unavailable
                        report['scenarios'][scenario] = result
                        write_json(args.output/(scenario+'.json'), {**result, 'rounds': samples, 'environment': report['environment']})
                (args.output/'meminfo-after.txt').write_text(device.shell('dumpsys', 'meminfo', APP))
        finally:
            try:
                report['recovery'] = restore(journal, device)
                if run.weather_id is not None:
                    cache = 'files/store/' + run.weather_id + '.json'
                    device.shell('run-as', APP, 'rm', '-f', cache, cache+'.bak')
                    device.shell('run-as', APP, 'rmdir', 'files/plugins/' + run.weather_id, check=False)
            except BaseException:
                report['private_recovery_journal'] = str(journal)
                raise
            finally:
                device.shell('media', 'volume', '--stream', 3, '--set', volume)
        if args.baseline:
            baseline = json.loads((args.baseline/'summary.json').read_text())
            for key in ('seed', 'online', 'fixture', 'screen', 'orientation', 'sampling_hz', 'theme'):
                if baseline['environment'].get(key) != report['environment'].get(key):
                    raise FixtureUnavailable('Baseline environment differs: ' + key)
            report['regressions'] = compare(baseline, report)
    except KeyboardInterrupt:
        report['environment_error'] = 'Interrupted'
    except (AssertionError, OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        report['environment_error'] = str(error)
    finally:
        write_json(args.output/'summary.json', report)
        (args.output/'summary.md').write_text(render_summary(report))
    results = report['scenarios'].values()
    if 'environment_error' in report or any('unavailable' in result for result in results):
        return 3
    if any(result['functional_failures'] for result in results):
        return 2
    if report.get('regressions') or any(result['provisional_misses'] or result['measurement_errors'] for result in results):
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
