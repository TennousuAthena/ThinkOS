#!/usr/bin/env python3
"""Narrow REG-2b lifecycle on the separately installed regFixture application.

Never builds/installs APKs, instruments, injects roots, changes trial_s, writes
private files, or restores a snapshot. Only initial fixture SettingsStore calls,
public Hub/native touchscreen input and one fixture-only cold stop are allowed.
Importing this module performs no I/O. Raw observations stay in host private TMP.
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import traceback
from urllib.parse import urlsplit
import zipfile

sys.dont_write_bytecode = True
os.environ['ADB_MDNS'] = '0'  # Also inherited by target-independent monitor/hierarchy subprocesses.
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_hierarchy import node_bounds, visible_nodes
from registry_device import TouchWatch, Hierarchy, quiet, focused_component, home_component, home_candidates
from registry_fixture_origin import Material, Origin

FIXTURE = 'moe.kiwi.thinkos.regfixture'
ORIGINAL = 'moe.kiwi.thinkos'
DEBUG = FIXTURE + '/moe.kiwi.thinkos.DebugReceiver'
LABEL = 'ThinkOS REG 验收'
STATE = 'files/plugins/registry-state.json'
TRIAL = 600
REASONS = {'network':'读取本地测试台内容','media':'建立静音暂停会话','location':'读取测试城市设置'}
MAX_READ = 2 * 1024 * 1024
PENDING_PATHS = ('files/update/pending.json','files/update/pending.json.bak',
                 'files/update/module-pending.json','files/update/module-pending.json.bak')
READ_PATHS = set(PENDING_PATHS) | {STATE, 'files/settings/local.json', 'files/settings/server.json',
              'files/registry/cache.json', 'files/registry/ledger.json'}
INITIAL = {'onboarding.done': 1, 'onboarding.initialized': True,
           'plugins.auto_update': 'off', 'plugins.sources': '',
           'update.auto_install': False, 'cast.enabled': False,
           'cast.miracast': False, 'cast.airplay': False, 'cast.airplay_mirror': False}
PENDING = ['Official application UID/Device Owner/global gestures are not exercised by this separate application.',
           'Force-stop followed by public-grid Main launch does not verify Alarm/Kiosk autonomous or native unclean recovery.',
           'Malicious catalogs, revocation, 16-instance limit, factory reset and performance are outside this lifecycle run.']


class Stopped(RuntimeError):
    pass


def require(value, message):
    if not value:
        raise Stopped(message)
    return value


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def owner_identity(raw):
    owners = []
    lines = raw.splitlines()
    for index, line in enumerate(lines):
        if re.search(r'(?:Device|Profile) Owner', line):
            block = '\n'.join(lines[index:index+5])
            owners.extend(re.findall(r'ComponentInfo\{([^}]+)\}', block))
    return tuple(sorted(set(owners)))


def component_package(component):
    return component.split('/', 1)[0] if component else None


def pid_logs(raw, pid):
    """epoch format is timestamp PID TID priority TAG: message; reject other PIDs."""
    result = []
    for line in raw.splitlines():
        match = re.match(r'^\s*\d+\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+ThinkOS\s*:', line)
        if match and int(match[1]) == pid:
            result.append(line)
    return result


class Device:
    def __init__(self, serial, run=subprocess.run):
        require(re.fullmatch(r'[A-Za-z0-9_.:-]{1,100}', serial), 'Invalid explicit device identity')
        self.serial, self.base, self.run = serial, ['adb', '-s', serial], run

    def adb(self, *args, timeout=40, check=True):
        # A failed mutation is never replayed after a connection failure.
        result = self.run(self.base + list(map(str, args)), capture_output=True, timeout=timeout,
                          env={**os.environ, 'ADB_MDNS': '0'})
        require(not check or result.returncode == 0, 'ADB command failed; no retry')
        require(len(result.stdout) <= MAX_READ and len(result.stderr) <= MAX_READ, 'ADB observation exceeds bound')
        return result.stdout

    def shell(self, *args, check=True):
        return self.adb('shell', '-T', shlex.join(list(map(str, args))), check=check).decode('utf-8', errors='strict')

    def pid(self):
        raw = self.shell('pidof', FIXTURE, check=False).strip()
        require(not raw or re.fullmatch(r'[1-9][0-9]*', raw), 'Ambiguous fixture PID')
        return int(raw) if raw else None

    def debug(self, command, *, package=FIXTURE, **values):
        require(package in (FIXTURE, ORIGINAL), 'Unknown debug target')
        require(command in {'ui_state', 'stage_state', 'media_state', 'state', 'setting'}, 'Debug command outside lifecycle')
        if command == 'setting':
            require(package == FIXTURE and values.get('scope') == 'system', 'Only fixture initial settings may change')
            key, value = values.get('key'), values.get('value')
            allowed = {**INITIAL, 'plugins.mirror': getattr(self, 'registry_url', None)}
            require(key in allowed, 'Noninitial setting refused')
            expected = str(allowed[key]).lower() if isinstance(allowed[key], bool) else str(allowed[key])
            require(value == expected and allowed[key] is not None, 'Noninitial setting refused')
        component = package + '/moe.kiwi.thinkos.DebugReceiver'
        arguments = ['am', 'broadcast', '-n', component, '--es', 'command', command]
        for key, value in values.items():
            arguments.extend(('--es', key, str(value)))
        return self.shell(*arguments)

    def state(self, command='ui_state', *, package=FIXTURE):
        raw = self.debug(command, package=package)
        match = re.search(r'data="(\{.*\})"', raw)
        require(match is not None, 'Missing explicit debug observation: ' + command)
        value = json.loads(match[1])
        require(isinstance(value, dict), 'Unexpected debug observation')
        return value

    def read(self, package, path):
        require(package in (FIXTURE, ORIGINAL) and path in READ_PATHS, 'Private read outside fixed paths')
        quoted = shlex.quote(path)
        script = 'if [ -L ' + quoted + " ]; then exit 2; elif [ -f " + quoted + " ]; then printf 'PRESENT\\n'; cat " + quoted + "; elif [ ! -e " + quoted + " ]; then printf 'ABSENT\\n'; else exit 3; fi"
        raw = self.shell('run-as', package, 'sh', '-c', script)
        if raw == 'ABSENT\n':
            return None
        require(raw.startswith('PRESENT\n'), 'Private observation malformed')
        return raw[len('PRESENT\n'):].encode()

    def json(self, path, package=FIXTURE):
        raw = self.read(package, path)
        value = json.loads(raw) if raw is not None else None
        require(value is None or isinstance(value, dict), 'Expected private JSON object')
        return value

    def original_hashes(self):
        # No symlink is followed. Only hashes/names are observed, never copied back.
        script = 'for t in files/registry files/update; do if [ -L "$t" ]; then exit 2; elif [ -d "$t" ]; then find "$t" -type f -exec sha256sum {} \\; ; find "$t" -type l -exec ls -ld {} \\; ; else echo "ABSENT $t"; fi; done'
        return sorted(self.shell('run-as', ORIGINAL, 'sh', '-c', script).splitlines())

    def logs(self, pid):
        raw = self.adb('logcat', '-d', '-v', 'epoch', '--pid=' + str(pid), '-s', 'ThinkOS:V').decode('utf-8', errors='replace')
        return pid_logs(raw, pid)


def effective(device, key, default, package=FIXTURE):
    for name in ('local', 'server'):
        layer = device.json('files/settings/' + name + '.json', package) or {}
        if key in layer.get('system', {}):
            return layer['system'][key]
    if package == FIXTURE and hasattr(device,'schema_defaults'):
        require(key in device.schema_defaults, 'Setting default was not validated from this APK: ' + key)
        return device.schema_defaults[key]
    return default


def set_initial_setting(device,guard,key,value):
    guard.check()
    text = str(value).lower() if isinstance(value,bool) else str(value)
    device.debug('setting',scope='system',key=key,value=text)
    deadline = time.monotonic() + 5
    while True:
        guard.check()
        if effective(device,key,None) == value:
            return
        require(time.monotonic() < deadline, 'Initial SettingsStore readback timed out: ' + key)
        time.sleep(.1)  # Bounded observation cadence, never another write or a forced flush.


class Guard:
    def __init__(self, device, watch, original_home, homes, owner):
        self.device, self.watch = device, watch
        self.home, self.homes, self.owner = original_home, homes, owner
        self.transition_until = 0
        self.target = FIXTURE

    def policy(self):
        require(home_component(self.device) == self.home and home_candidates(self.device) == self.homes,
                'Original HOME policy changed')
        require(owner_identity(self.device.shell('dumpsys', 'device_policy')) == self.owner, 'Device-owner policy changed')

    def check(self, focused=True, expected=None):
        expected = expected or self.target
        self.watch.check()
        require(quiet(self.device), 'Audio no longer zero; stopped')
        component = focused_component(self.device)
        allowed = (FIXTURE, ORIGINAL) if time.monotonic() < self.transition_until else (expected,)
        if component is None:
            require(not focused and time.monotonic() < self.transition_until, 'Unexpected empty foreground')
        else:
            require(component_package(component) in allowed, 'Unexpected foreground: ' + component)
            if focused:
                require(component_package(component) == expected, 'Wrong foreground for touch: ' + component)
        self.watch.check()
        return component

    def observe_transition(self):
        return self.check(focused=False)


class Driver:
    def __init__(self, device, guard, hierarchy, material, origin, output):
        self.d, self.guard, self.h, self.material, self.origin, self.output = device, guard, hierarchy, material, origin, output
        self.meta = material.meta
        self.ident = self.meta['id']
        self.pid = require(device.pid(), 'No fixture PID')
        self.media_owner = None
        self.start_witnesses = {}
        self.result = {'complete': False, 'scope': 'Independent same-source regFixture public Hub/native touch lifecycle only',
                       'run_id': self.meta['run_id'], 'target': FIXTURE, 'trial_seconds': TRIAL, 'checks': [],
                       'health': [], 'start_witnesses': [], 'pids': [self.pid], 'pending': PENDING, 'original_private_data_written': False}

    def save(self):
        path = self.output / 'result.json'
        path.write_text(json.dumps(self.result, ensure_ascii=False, indent=2) + '\n')

    def check(self, name, value):
        self.result['checks'].append({'name': name, 'passed': bool(value)})
        self.save()
        require(value, name)

    def phase(self, name):
        self.guard.check()
        self.result['phase'] = name
        self.save()
        print('REGFIXTURE ' + name, flush=True)

    def wait(self, predicate, seconds=25, expected=FIXTURE):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.guard.check(expected=expected)
            value = predicate()
            if value:
                return value
            time.sleep(.3)
        return None

    def nodes(self, expected=FIXTURE):
        self.guard.check(expected=expected)
        previous = self.guard.target
        self.guard.target = expected
        try:
            return [dict(n.attrib) for n in visible_nodes(self.h.tree()) if n.get('package') in (expected, 'android')]
        finally:
            self.guard.target = previous

    def node(self, label=None, resource=None, expected=FIXTURE, body=False):
        snapshot = self.nodes(expected)
        matches = [n for n in snapshot if label and label in (n.get('text'), n.get('content-desc')) or resource and n.get('resource-id') == resource]
        if body:
            # Derive the segmented header from this same real hierarchy. Duplicate
            # tab parent/child labels must never become a detail-row action.
            anchors = []
            for name in ('发现','已安装'):
                rows = [n for n in snapshot if name in (n.get('text'),n.get('content-desc'))]
                if not rows:
                    return None
                anchors.append(node_bounds(min(rows,key=lambda n:node_bounds(n)[1])))
            if max(b[1] for b in anchors) >= min(b[3] for b in anchors):
                return None
            boundary = max(b[3] for b in anchors)
            matches = [n for n in matches if node_bounds(n)[1] >= boundary]
        return min(matches, key=lambda n: node_bounds(n)[1]) if matches else None

    def geometry(self, package=FIXTURE):
        value = self.d.state('stage_state', package=package)
        return require(value.get('width'), 'No stage width'), require(value.get('height'), 'No stage height')

    def input(self, *args, expected=FIXTURE):
        self.guard.check(expected=expected)
        now = time.monotonic()
        self.d.shell('input', *args)
        self.guard.check(expected=expected)
        time.sleep(.25)
        return now

    def tap_node(self, node, expected=FIXTURE):
        x1, y1, x2, y2 = node_bounds(node)
        width, height = self.geometry(expected)
        require(0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, 'Tap bounds not visible')
        return self.input('touchscreen', 'tap', (x1+x2)//2, (y1+y2)//2, expected=expected)

    def scroll(self, up=True, expected=FIXTURE):
        width, height = self.geometry(expected)
        self.input('touchscreen', 'swipe', width//2, int(height*(.77 if up else .33)),
                   width//2, int(height*(.33 if up else .77)), 350, expected=expected)

    def tap(self, label, scroll=False, expected=FIXTURE, body=False):
        node = self.wait(lambda: self.node(label=label, expected=expected, body=body), seconds=8, expected=expected)
        if scroll:
            for _ in range(8):
                if node:
                    break
                self.scroll(expected=expected)
                node = self.node(label=label, expected=expected, body=body)
        return self.tap_node(require(node, 'Required visible label missing: ' + label), expected)

    def open_tiles(self, package=FIXTURE):
        for _ in range(10):
            page = self.d.state(package=package).get('page')
            if page in {'home.main', 'tiles.main', 'tiles.apps'}:
                break
            self.input('keyevent', 'KEYCODE_BACK', expected=package)
        require(self.d.state(package=package).get('page') in {'home.main', 'tiles.main', 'tiles.apps'}, 'Real Back did not reach a base page')
        seen = set()
        for _ in range(16):
            page = self.d.state(package=package).get('page')
            if page == 'tiles.apps':
                return
            require(page not in seen, 'App tile page absent')
            seen.add(page)
            width, height = self.geometry(package)
            self.input('touchscreen', 'swipe', int(width*.85), height//2, int(width*.16), height//2, 400, expected=package)
            require(self.wait(lambda: self.d.state(package=package).get('page') != page, expected=package), 'Base-page swipe did not advance')
        raise Stopped('App tile page unreachable')

    def open_hub(self):
        self.open_tiles()
        self.tap('插件中心', scroll=True)
        require(self.wait(lambda: self.d.state().get('page') == 'hub.main'), 'Actual Hub did not open')

    def refresh(self, seq):
        self.open_hub()
        self.tap('发现')
        node = self.wait(lambda: next((n for n in self.nodes() if n.get('text') in {'刷新', '目录刷新失败', '目录可能过旧'} or n.get('content-desc') in {'刷新', '目录刷新失败', '目录可能过旧'}), None))
        self.tap_node(require(node, 'Hub refresh missing'))
        require(self.wait(lambda: (self.d.json('files/registry/ledger.json') or {}).get('seq') == seq, seconds=60), 'Published signature/sequence not authenticated')
        self.scroll(up=False)

    def detail(self):
        self.tap('发现')
        self.tap('仓库测试', scroll=True)
        require(self.wait(lambda: any(n.get('text','').startswith('thinkos-tests/registry-probe@') or n.get('content-desc','').startswith('thinkos-tests/registry-probe@') for n in self.nodes())), 'Signed fixture exact source detail missing')

    def record(self):
        state = self.d.json(STATE) or {}
        records = state.get('installed', [])
        require(all(r.get('id') == self.ident for r in records), 'Unexpected installed registry plugin in fixture domain')
        return next((r for r in records if r.get('id') == self.ident), None)

    def stable(self, version, trial):
        record = self.record() or {}
        return record.get('version') == version and record.get('digest') == self.meta['digests'][version] and record.get('enabled') is True and record.get('trial') is trial

    def diagnostic(self):
        before = self.d.logs(self.pid)
        mark = float(before[-1].split()[0]) if before else 0
        self.d.debug('state')
        def observe():
            lines = [line for line in self.d.logs(self.pid) if float(line.split()[0]) > mark]
            return next((line for line in reversed(lines) if 'DEBUG state=' in line and ' plugins=[' in line), None)
        line = require(self.wait(observe, seconds=10), 'Fixture PID diagnostic absent')
        return {ident: {'version': version, 'bytes': int(size), 'disabled': disabled == 'true'} for ident, version, size, disabled in re.findall(r'PluginDiagnostic\(id=([^,]+), version=([^,]+), bytes=(\d+), disabled=(true|false)', line.split(' plugins=',1)[1])}

    def active(self, version):
        value = self.diagnostic().get(self.ident, {})
        return value.get('version') == version and value.get('bytes', 0) > 0 and value.get('disabled') is False

    def started(self, version):
        # A captured historical fact, never a late search of the rolling ring.
        digest = self.meta['digests'].get(version)
        return self.d.pid() == self.pid and (self.pid,version,digest) in self.start_witnesses

    def capture_started(self, version):
        if self.started(version):
            return True
        digest = self.meta['digests'].get(version)
        if digest is None or self.d.pid() != self.pid:
            return False
        record = self.record() or {}
        if not (record.get('id') == self.ident and record.get('version') == version and
                record.get('digest') == digest and record.get('enabled') is True and self.active(version)):
            return False
        # The signed fixture's existing marker contains its exact version, not
        # a digest. Bind that real line to the adjacent authoritative record.
        pattern = r'ThinkOS\s*:\s*\[plugin:' + re.escape(self.ident) + r'\]\s+REGISTRY_STARTED\s+' + re.escape(version) + r'\s*$'
        lines = pid_logs('\n'.join(self.d.logs(self.pid)),self.pid)
        line = next((line for line in reversed(lines) if len(line.encode()) <= 2048 and re.search(pattern,line)),None)
        if line is None:
            return False
        after = self.record() or {}
        if self.d.pid() != self.pid or any(after.get(key) != record.get(key) for key in ('id','version','digest','enabled')):
            return False
        self.guard.check()
        require(len(self.start_witnesses) < 8, 'Startup witness bound exceeded')
        witness = {'id':self.ident,'pid':self.pid,'version':version,'digest':digest,
                   'marker':line,'marker_epoch':float(line.split()[0]),
                   'captured_monotonic':time.monotonic(),'record_trial':after.get('trial'),
                   'digest_binding':'adjacent enabled registry record and authenticated fixture metadata'}
        self.start_witnesses[(self.pid,version,digest)] = witness
        self.result['start_witnesses'].append(witness)
        self.save()
        return True

    def confirm(self, version, cancel=False):
        require(self.wait(lambda: self.node(resource='android:id/button1')), 'Native permission dialog missing')
        commit = sha((self.ident + '@' + version).encode())[:40]
        wanted = {'仓库测试', 'thinkos-tests/registry-probe@' + commit, '已审核'}
        wanted.update(self.meta.get('permission_reasons',REASONS)[p] for p in self.meta['versions'][version]['permissions'])
        seen = set()
        for _ in range(4):
            text = '\n'.join(n.get('text','') + '\n' + n.get('content-desc','') for n in self.nodes())
            seen.update(value for value in wanted if value in text)
            if seen == wanted:
                break
            self.scroll()
        self.check('Native exact source/tier/all permission reasons ' + version, seen == wanted)
        resource = 'android:id/button2' if cancel else 'android:id/button1'
        submitted = self.tap_node(require(self.node(resource=resource), 'Native real-touch action missing'))
        require(self.wait(lambda: self.node(resource='android:id/button1') is None), 'Native dialog did not close')
        return submitted

    def health(self, version, earliest):
        self.phase('real-600s-' + version)
        next_observe = 0
        samples = []
        while time.monotonic() < earliest + TRIAL + 120:
            self.guard.check()
            require(self.d.pid() == self.pid, 'Fixture PID changed during real trial')
            require(effective(self.d, 'plugins.trial_s', 600) == TRIAL, 'Real trial duration changed')
            record = self.record() or {}
            require(record.get('version') == version and record.get('enabled') is True and record.get('digest') == self.meta['digests'][version], 'Trial candidate changed')
            elapsed = time.monotonic() - earliest
            if record.get('trial') is False:
                self.check('Real health at/after 600s ' + version, elapsed >= TRIAL and self.active(version) and self.started(version))
                self.result['health'].append({'version': version, 'elapsed_s': round(elapsed,3), 'pid': self.pid, 'samples': samples})
                self.save()
                return
            require(record.get('trial') is True, 'Invalid trial state')
            if time.monotonic() >= next_observe:
                require(self.active(version), 'Trial VM stopped')
                # A real harmless current Hub-tab tap keeps normal attention awake;
                # no policy/timer/attention override is used.
                self.tap('已安装')
                samples.append({'elapsed_s': round(elapsed,3), 'trial': True, 'pid': self.pid})
                self.result['health_wait_s'] = round(elapsed,3)
                self.save()
                print('REGFIXTURE ' + version + ' ' + str(int(elapsed)) + '/600s', flush=True)
                next_observe = time.monotonic() + 25
            time.sleep(1)
        raise Stopped('Real 600s health did not finish within 720s bounded window')

    def owned_media(self):
        value = self.d.state('media_state')
        owner = value.get('owner','')
        return value if re.fullmatch(re.escape(self.ident) + r':[1-9][0-9]*',owner) and value.get('kind') == 'video' and value.get('count') == 1 and value.get('index') == 1 else None

    def paused_media(self):
        value = self.owned_media()
        return value if value and value.get('status') == 'paused' else None

    def stop_media(self):
        before = self.owned_media()
        require(before and before['owner'] == self.media_owner, 'Real media instance changed before public stop')
        self.open_hub()
        self.tap('已安装')
        self.tap('仓库测试', scroll=True)
        self.tap('打开', scroll=True)
        require(self.wait(lambda: self.node(label='仓库测试 1.0.2')), 'Actual v3 page missing')
        self.tap(self.meta.get('stop_label','停止媒体'))
        require(self.wait(lambda: not (value := self.d.state('media_state')).get('owner') and value.get('status') == 'stopped' and value.get('count') == 0), 'Actual public stop did not clear paused media')
        self.open_hub()  # real Back releases the visible-page replacement gate

    def await_foreground(self, package, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            component = self.guard.observe_transition()
            if component_package(component) == package:
                self.guard.check(expected=package)
                return
            time.sleep(.2)
        raise Stopped('Owned foreground transition timed out: ' + str(component))

    def return_original(self):
        self.guard.check()
        self.guard.policy()
        self.guard.transition_until = time.monotonic() + 30
        self.d.shell('input','keyevent','KEYCODE_HOME')
        self.await_foreground(ORIGINAL, 30)
        self.guard.transition_until = 0
        self.guard.target = ORIGINAL
        self.guard.check()
        self.guard.policy()
        # HOME normally selects home.main; if an existing public layer remains,
        # peel it with Back and use only ordinary base-page horizontal navigation.
        for _ in range(8):
            page = self.d.state(package=ORIGINAL).get('page')
            if page in {'home.main','tiles.main','tiles.apps'}:
                break
            self.input('keyevent','KEYCODE_BACK',expected=ORIGINAL)
        seen = set()
        for _ in range(16):
            page = self.d.state(package=ORIGINAL).get('page')
            if page == 'home.main':
                break
            require(page not in seen, 'Original public Home page unreachable')
            seen.add(page)
            width,height = self.geometry(ORIGINAL)
            self.input('touchscreen','swipe',int(width*.85),height//2,int(width*.16),height//2,400,expected=ORIGINAL)
            require(self.wait(lambda:self.d.state(package=ORIGINAL).get('page') != page,expected=ORIGINAL),'Original public base swipe did not advance')
        require(self.d.state(package=ORIGINAL).get('page') == 'home.main','Original home.main did not settle')
        self.result['final_page'] = 'home.main'
        self.result['final_foreground'] = focused_component(self.d)
        self.save()

    def checkpoint_cold(self, candidate):
        require(candidate in ('1.0.3','1.0.5') and candidate in self.meta['digests'], 'Unknown fixed cold candidate')
        self.guard.check()
        require(self.d.pid() == self.pid, 'Cold candidate PID changed')
        cache = self.d.read(FIXTURE, 'files/registry/cache.json')
        ledger = self.d.read(FIXTURE, 'files/registry/ledger.json')
        require(cache and ledger, 'Authenticated cache/floor absent before cold recovery')
        record = self.record() or {}
        require(record.get('version') == candidate and record.get('digest') == self.meta['digests'][candidate] and
                record.get('enabled') is True and record.get('trial') is True and self.active(candidate),
                'Cold candidate must be the exact running real trial')
        before = {'schema':1,'old_pid':self.pid,'candidate_version':candidate,'candidate_digest':self.meta['digests'][candidate],
                  'rollback_version':'1.0.2','rollback_digest':self.meta['digests']['1.0.2'],
                  'cache_sha256':sha(cache),'ledger_sha256':sha(ledger),'captured_monotonic':time.monotonic()}
        # This owner-private checkpoint must exist before Origin.close/HOME/stop.
        path = self.output/'cold-before.json'
        with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'w') as stream:
            stream.write(json.dumps(before,sort_keys=True)+'\n'); stream.flush(); os.fsync(stream.fileno())
        descriptor = os.open(self.output,os.O_RDONLY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
        self.result['cold_before'] = before
        self.save()
        return before

    def cold(self, candidate='1.0.3'):
        self.phase('ordinary-cold-main-with-origin-closed')
        before = self.checkpoint_cold(candidate)
        self.origin.close()
        self.check('Host content origin closed before cold recovery', self.origin.server is None)
        self.guard.policy()
        self.guard.check()
        self.guard.transition_until = time.monotonic() + 60
        self.d.shell('input', 'keyevent', 'KEYCODE_HOME')
        self.await_foreground(ORIGINAL, 30)
        self.guard.policy()
        old_pid = self.pid
        self.guard.check(expected=ORIGINAL)
        self.d.shell('am', 'force-stop', FIXTURE)
        require(self.wait(lambda: self.d.pid() is None, expected=ORIGINAL), 'Fixture PID did not exit')
        self.open_tiles(ORIGINAL)
        self.tap('安卓应用', scroll=True, expected=ORIGINAL)
        # The grid launch itself may transition: check before touching original,
        # then only observe the bounded two-known-app window until fixture returns.
        node = require(self.wait(lambda: self.node(label=LABEL, expected=ORIGINAL), expected=ORIGINAL), 'Public grid fixture label absent')
        self.guard.check(expected=ORIGINAL)
        x1,y1,x2,y2 = node_bounds(node)
        width,height = self.geometry(ORIGINAL)
        require(0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, 'Public grid launch bounds invalid')
        self.guard.transition_until = time.monotonic() + 45
        self.d.shell('input', 'touchscreen', 'tap', (x1+x2)//2, (y1+y2)//2)
        end = time.monotonic() + 45
        while time.monotonic() < end:
            component = self.guard.observe_transition()
            if component_package(component) == FIXTURE:
                break
            time.sleep(.2)
        self.guard.transition_until = 0
        self.guard.check()
        self.pid = require(self.d.pid(), 'Cold fixture PID absent')
        self.check('Public grid ordinary Main created a new fixture PID', self.pid != old_pid)
        self.result['pids'].append(self.pid)
        self.check('Offline cold Main rolls back '+candidate+' to healthy v3', self.wait(lambda: self.stable('1.0.2', False) and self.capture_started('1.0.2'), seconds=60))
        state = self.d.json(STATE) or {}
        self.check('Interrupted '+candidate+' digest retained in blocked history', any(item.get('id') == self.ident and item.get('digest') == before['candidate_digest'] and item.get('reason') == 'trial_interrupted' for item in state.get('blocked', [])))
        if candidate == '1.0.5':
            self.check('Earlier v4 blocked evidence remains',any(item.get('id') == self.ident and item.get('digest') == self.meta['digests']['1.0.3'] for item in state.get('blocked',[])))
        self.check('Cold recovery keeps authenticated catalog and sequence floor byte-identical', sha(self.d.read(FIXTURE,'files/registry/cache.json') or b'') == before['cache_sha256'] and sha(self.d.read(FIXTURE,'files/registry/ledger.json') or b'') == before['ledger_sha256'])
        self.guard.policy()
        # Cold v3 start creates another autoplay=false queue. Stop that exact new
        # instance through its signed public UI even if offline prepare reports error.
        media = require(self.wait(self.owned_media, seconds=20), 'Cold v3 own media queue absent')
        self.media_owner = media['owner']
        self.result['cold_media_before_stop'] = {'owner':self.media_owner,'status':media.get('status'),'count':media['count']}
        self.stop_media()
        self.check('Final cold v3 media queue cleared through real public UI', not self.d.state('media_state').get('owner') and self.d.state('media_state').get('count') == 0)

    def run(self):
        self.phase('v1-real-public-install')
        self.refresh(self.origin.publish(level=1)['seq'])
        self.detail()
        self.tap('安装', scroll=True)
        earliest = self.confirm('1.0.0')
        self.check('v1 real VM runs after exact native consent', self.wait(lambda: self.stable('1.0.0', True) and self.active('1.0.0'), seconds=60))
        self.check('v1 exact startup marker captured before health wait', self.wait(lambda:self.capture_started('1.0.0'),seconds=15))
        self.health('1.0.0', earliest)
        self.phase('v3-added-permissions-cancel-then-confirm')
        self.refresh(self.origin.publish(level=3)['seq'])
        self.detail()
        before = self.record()
        self.tap('更新', scroll=True, body=True)
        self.confirm('1.0.2', cancel=True)
        self.check('Native cancel leaves healthy v1 exact record and VM', self.record() == before and self.active('1.0.0'))
        self.tap('更新', scroll=True, body=True)
        earliest = self.confirm('1.0.2')
        self.check('v3 real trial and exact permission grant', self.wait(lambda: self.stable('1.0.2', True) and self.active('1.0.2'), seconds=60) and set(self.record()['confirmed']) == {'network','media','location'})
        self.check('v3 exact startup marker captured before health wait', self.wait(lambda:self.capture_started('1.0.2'),seconds=15))
        paused = self.wait(self.paused_media, seconds=60)
        self.check('v3 owns one real silent paused video session', paused is not None)
        self.media_owner = paused['owner']
        self.result['paused_media'] = {'owner':self.media_owner,'count':paused['count'],'index':paused['index'],'kind':paused['kind']}
        self.save()
        self.stop_media()
        self.health('1.0.2', earliest)
        self.phase('v4-same-permissions-real-trial')
        self.refresh(self.origin.publish(level=4)['seq'])
        self.detail()
        self.tap('更新', scroll=True, body=True)
        self.check('v4 needs no extra permission consent and really executes', self.wait(lambda: self.stable('1.0.3', True) and self.active('1.0.3'), seconds=60) and self.node(resource='android:id/button1') is None)
        self.cold()


def validate_apk(apk, material):
    require(apk.is_file() and not apk.is_symlink(), 'Explicit local fixture APK required')
    with zipfile.ZipFile(apk) as archive:
        infos = [entry for entry in archive.infolist() if entry.filename == 'assets/update/keys.json']
        require(len(infos) == 1 and infos[0].file_size <= 65536, 'APK fixed public roots missing/duplicated/oversized')
        public = archive.read(infos[0])
    require(sha(public) == material.meta['public_keys_sha256'] and public == (material.directory/'public/keys.json').read_bytes(), 'APK fixed roots differ from this signed host origin')
    return sha(apk.read_bytes())


def apk_settings_defaults(apk,mirror):
    # Read the pinned APK's actual schema, not a workspace copy or a presumed
    # serialized override. SettingsStore may omit any value equal to its base.
    with zipfile.ZipFile(apk) as archive:
        infos = [entry for entry in archive.infolist() if entry.filename == 'assets/settings/system.json']
        require(len(infos) == 1 and 0 < infos[0].file_size <= 1024*1024, 'APK system settings schema missing/duplicated/oversized')
        document = json.loads(archive.read(infos[0]))
    require(isinstance(document,dict) and document.get('scope') == 'system' and isinstance(document.get('fields'),dict), 'APK system settings schema malformed')
    wanted = {**INITIAL,'plugins.mirror':mirror,'plugins.trial_s':TRIAL}
    defaults = {}
    for key,value in wanted.items():
        field = document['fields'].get(key)
        require(isinstance(field,dict) and 'default' in field, 'APK required setting/default missing: ' + key)
        kind = field.get('type')
        for candidate in (field['default'],value):
            if kind == 'bool':
                valid = type(candidate) is bool
            elif kind == 'number':
                valid = type(candidate) in (int,float) and math.isfinite(candidate) and field.get('min',-math.inf) <= candidate <= field.get('max',math.inf)
            elif kind == 'text':
                valid = isinstance(candidate,str) and len(candidate) <= field.get('maxLength',1024)
            elif kind == 'choice':
                choices = field.get('choices')
                valid = isinstance(choices,list) and all(isinstance(item,dict) and 'value' in item for item in choices) and any(type(candidate) is type(item['value']) and candidate == item['value'] for item in choices)
            else:
                valid = False
            require(valid, 'APK setting type/default/value incompatible: ' + key)
        defaults[key] = field['default']
    return defaults


def preflight(device, apk_sha, material, *, require_fresh=True, expected_return_after_s=0):
    require(device.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT', 'Only authorized physical device HUA0BWUT')
    require(device.shell('getprop','ro.build.version.sdk').strip() == '27', 'Actual device API must remain 27')
    require('armeabi-v7a' in device.shell('getprop','ro.product.cpu.abilist').strip().split(','), 'Actual device ABI must include armeabi-v7a')
    path = device.shell('pm', 'path', FIXTURE).strip()
    require(re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=-]+\.apk', path), 'Fixture must be separately installed as one APK')
    require(device.shell('sha256sum', path[len('package:'):]).split()[0] == apk_sha, 'Installed fixture APK differs')
    fixture = device.shell('dumpsys', 'package', FIXTURE)
    original = device.shell('dumpsys', 'package', ORIGINAL)
    uid = re.search(r'\buserId=(\d+)', fixture); old_uid = re.search(r'\buserId=(\d+)', original)
    require(uid and old_uid and uid[1] != old_uid[1] and 'DEBUGGABLE' in fixture, 'Fixture UID/debug isolation not established')
    require(component_package(focused_component(device)) == FIXTURE, 'Open fixture through original public grid before starting')
    home, homes = home_component(device), home_candidates(device)
    require(component_package(home) == ORIGINAL and all(component_package(item) != FIXTURE for item in homes), 'Original HOME required; fixture must not be HOME')
    owner = owner_identity(device.shell('dumpsys', 'device_policy'))
    require(any(item in {ORIGINAL+'/.admin.ThinkOSAdminReceiver',ORIGINAL+'/'+ORIGINAL+'.admin.ThinkOSAdminReceiver'} for item in owner) and all(FIXTURE not in item for item in owner), 'Known original Device Owner required; fixture cannot own device')
    require(all(device.read(ORIGINAL,path) is None for path in PENDING_PATHS), 'Original APK/module transaction exists or is unknown; preserve and audit before fixture run')
    require(type(expected_return_after_s) is int and expected_return_after_s in (0,900), 'Unsupported explicit original return-after preflight')
    require(effective(device, 'apps.return_after_s', 900, ORIGINAL) == expected_return_after_s, 'Original return-after differs from the required existing value; never change it here')
    require(effective(device, 'plugins.trial_s', 600) == TRIAL, 'Real unmodified 600-second fixture policy required')
    if require_fresh:
        require(not (device.json(STATE) or {}).get('installed'), 'A fresh fixture registry domain is required; never clear existing records')
    return home, homes, owner


def cleanup_hierarchy(device,guard,hierarchy):
    remote = hierarchy.remote
    guard.check()
    entries = set(device.shell('find',remote,'-print').splitlines())
    allowed = {remote,hierarchy.jar,remote+'/oat',remote+'/oat/arm',
               remote+'/oat/arm/hierarchy.odex',remote+'/oat/arm/hierarchy.vdex'}
    require(remote in entries and hierarchy.jar in entries and entries <= allowed, 'Unknown owned helper layout retained')
    require(not device.shell('find',remote,'-type','l','-print').strip(), 'Owned helper symlink retained')
    guard.check()
    device.shell('rm','-f',hierarchy.jar,remote+'/oat/arm/hierarchy.odex',remote+'/oat/arm/hierarchy.vdex')
    for directory in (remote+'/oat/arm',remote+'/oat',remote):
        if directory in entries:
            guard.check()
            device.shell('rmdir',directory)


def main(argv=None, *, device_factory=Device, material_open=Material.open, origin_factory=Origin,
         watch_factory=TouchWatch, hierarchy_factory=Hierarchy):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--material', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--hierarchy-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    output = args.output.resolve()
    require(not output.exists() and str(output).startswith(('/private/tmp/','/tmp/')), 'New private host TMP output required')
    require(args.hierarchy_jar.is_file() and not args.hierarchy_jar.is_symlink(), 'Existing prebuilt hierarchy jar required')
    output.mkdir(mode=0o700)
    origin, hierarchy, driver, reverse = None, None, None, False
    watch, guard = None, None
    main_succeeded = False
    try:
        os.chmod(output,0o700)
        material = material_open(args.material)
        apk_sha = validate_apk(args.apk, material)
        defaults = apk_settings_defaults(args.apk,material.meta['registry_url'])
        device = device_factory(args.serial)
        device.schema_defaults = defaults
        home, homes, owner = preflight(device, apk_sha, material)
        watch = watch_factory(device)
        guard = Guard(device,watch,home,homes,owner)
        guard.check(); guard.policy()
        baseline = device.original_hashes()
        (output/'original-hashes.json').write_text(json.dumps(baseline,ensure_ascii=False))
        # These are ordinary fixture SettingsStore calls; no file injection.
        device.registry_url = material.meta['registry_url']
        for key,value in {**INITIAL,'plugins.mirror':device.registry_url}.items():
            set_initial_setting(device,guard,key,value)
        if device.state().get('onboardingOpen'):
            guard.check(); device.shell('input','keyevent','KEYCODE_BACK'); guard.check()
        require(device.state().get('locale','').startswith('zh') and not device.state().get('onboardingOpen'), 'Existing Chinese locale/closed guide required; no system locale mutation')
        port = urlsplit(material.meta['base_url']).port
        if urlsplit(material.meta['base_url']).hostname == '127.0.0.1':
            guard.check()
            require(not any('tcp:'+str(port) in line.split() for line in device.adb('reverse','--list').decode().splitlines()), 'Reverse port already owned')
            device.adb('reverse','tcp:'+str(port),'tcp:'+str(port)); reverse = True
        origin = origin_factory(material).start()
        hierarchy = hierarchy_factory(device,guard,material.meta['run_id'],args.hierarchy_jar)
        driver = Driver(device,guard,hierarchy,material,origin,output)
        driver.result['apk_sha256'] = apk_sha
        driver.run()
        driver.return_original()
        guard.check(); guard.policy()
        after = device.original_hashes()
        (output/'original-hashes-after.json').write_text(json.dumps(after,ensure_ascii=False))
        driver.check('Original registry/update tree hashes unchanged; no restoration was attempted', after == baseline)
        main_succeeded = True
    except BaseException as error:
        # No automatic screen takeover, private restore, uninstall, policy or audio write.
        failure_trace = traceback.format_exc()
        try:
            (output/'failure.txt').write_text(failure_trace)
        except BaseException:
            pass  # Diagnostic I/O must never replace the original exception.
        if driver:
            try:
                driver.result['error'] = str(error) if isinstance(error,Stopped) else type(error).__name__
                driver.result['complete'] = False
                driver.save()
            except BaseException:
                pass
        raise
    finally:
        # Host origin may always stop. Device cleanup requires an intact owner guard;
        # human input/foreign foreground leaves exact owned temporary artifacts alone.
        cleanup_failure, cleanup_trace = None, None
        try:
            if origin:
                origin.close()
            if guard is not None:
                guard.check()
                if reverse:
                    device.adb('reverse','--remove','tcp:'+str(port))
                if hierarchy:
                    cleanup_hierarchy(device,guard,hierarchy)
        except BaseException as error:
            cleanup_failure, cleanup_trace = error, traceback.format_exc()
        try:
            if watch is not None:
                watch.close()
        except BaseException as error:
            if cleanup_failure is None:
                cleanup_failure, cleanup_trace = error, traceback.format_exc()
        if cleanup_failure is not None:
            (output/'cleanup-failure.txt').write_text(cleanup_trace)
            if driver:
                driver.result['temporary_cleanup'] = 'retained; guard or exact owned cleanup failed'
                driver.result['complete'] = False
                if main_succeeded:
                    driver.result['error'] = 'Owned cleanup failed; scene retained'
                driver.save()
            # A cleanup failure must fail an otherwise successful CLI run, while
            # an original main-flow exception continues unchanged through finally.
            if main_succeeded:
                raise Stopped('Owned cleanup failed; scene retained') from cleanup_failure
        elif driver:
            driver.result['temporary_cleanup'] = 'owned helper/reverse removed'
            driver.save()
    driver.result['complete'] = True
    driver.save()
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (Stopped,OSError,ValueError,subprocess.SubprocessError):
        print('REG fixture lifecycle stopped; private TMP report retains details',file=sys.stderr)
        sys.exit(1)
