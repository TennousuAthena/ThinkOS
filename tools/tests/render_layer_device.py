#!/usr/bin/env python3
"""Functional RenderThread probe; never measures frame rate or changes production code.

Run only from an idle base page. Every case restarts the actual app process, holds
DARK/AWAKE/landscape, then enters the same one-photo stage. Cases differ only in
the literal carousel pan boolean. See render_layer_probe.md before running.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

PHYSICAL = 'HUA0BWUT'
PROVIDER = 'render-layer-provider'
PROBE = 'render-layer-probe'
PAGE = PROBE + '.stage'
IDS = (PROVIDER, PROBE)
FIXED_PATHS = tuple('files/settings/' + name + suffix
                    for name in ('local', 'server') for suffix in ('.json', '.json.bak')) + tuple(
    'shared_prefs/' + name + suffix for name in ('deck', 'recovery') for suffix in ('.xml', '.xml.bak')) + tuple(
    'files/store/' + name + suffix for name in ('welcome', 'player', 'iptv', 'radio') + IDS
    for suffix in ('.json', '.json.bak'))
PRIVATE_PATH = re.compile(r'(?:files/(?:settings|store)/[a-zA-Z0-9_.-]+\.json(?:\.bak)?|shared_prefs/[a-zA-Z0-9_.-]+\.xml(?:\.bak)?)\Z')
MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def debug_json(device, command):
    raw = device.debug(command)
    match = re.search(r'data="(\{.*\})"', raw)
    require(match is not None, 'Installed debug APK does not expose ' + command)
    return json.loads(match[1])


def pid(device):
    raw = device.shell('pidof', APP, check=False).strip().split()
    return int(raw[0]) if len(raw) == 1 and raw[0].isdigit() else None


def audio_zero(device):
    """Never restore an audible level, including on failure or manual recovery."""
    levels = {}
    for stream in range(1, 6):
        raw = device.shell('media', 'volume', '--stream', stream, '--get')
        if not re.search(r'volume is 0\b', raw):
            device.shell('media', 'volume', '--stream', stream, '--set', 0)
            raw = device.shell('media', 'volume', '--stream', stream, '--get')
        levels[str(stream)] = bool(re.search(r'volume is 0\b', raw))
    require(all(levels.values()), 'Cannot maintain audio streams 1–5 at zero; probe refused')
    return levels


def owned_dev_name(name):
    return any(name == owner or name == '.previous-' + owner or
               name.startswith('.stage-' + owner + '-') for owner in IDS)


def list_files(device, directory):
    require(directory in ('files/settings', 'files/store', 'shared_prefs', 'cache/images', 'files/dev-plugins'), 'Unexpected directory')
    # Directory is one of the fixed constants above, never an external argument.
    text = device.shell('run-as', APP, 'sh', '-c',
                        'if [ -d ' + directory + ' ]; then ls -1A ' + directory + '; fi')
    return text.splitlines()


def protected_paths(device):
    paths = set(FIXED_PATHS)
    for directory in ('files/settings', 'files/store', 'shared_prefs'):
        names = list_files(device, directory)
        require(len(names) <= 512, 'Too many private files to snapshot safely')
        for name in names:
            path = directory + '/' + name
            require(PRIVATE_PATH.fullmatch(path), 'Unexpected private file; probe refused')
            paths.add(path)
    return sorted(paths)


def image_info(raw):
    """Read PNG/JPEG dimensions only, with no image decoding dependency."""
    if raw.startswith(b'\x89PNG\r\n\x1a\n') and len(raw) >= 33 and raw[12:16] == b'IHDR':
        width, height = struct.unpack('>II', raw[16:24])
        extension = '.png'
    elif raw.startswith(b'\xff\xd8'):
        offset = 2
        width = height = 0
        while offset + 3 < len(raw):
            if raw[offset] != 0xff:
                break
            while offset < len(raw) and raw[offset] == 0xff:
                offset += 1
            if offset >= len(raw):
                break
            marker = raw[offset]
            offset += 1
            if marker in (0xd9, 0xda):
                break
            if marker == 0x01 or 0xd0 <= marker <= 0xd7:
                continue
            size = int.from_bytes(raw[offset:offset + 2], 'big')
            if size < 2 or offset + size > len(raw):
                break
            if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) and size >= 8:
                height, width = struct.unpack('>HH', raw[offset + 3:offset + 7])
                break
            offset += size
        extension = '.jpg'
    else:
        raise ValueError('Only an existing PNG/JPEG cache entry is supported')
    if not (1 <= width <= 8192 and 1 <= height <= 8192 and width * height <= 20_000_000):
        raise ValueError('Image dimensions outside probe budget')
    return {'width': width, 'height': height, 'extension': extension}


def valid_url(value):
    if not isinstance(value, str) or not 1 <= len(value.encode()) <= 2048 or any(ord(c) < 33 for c in value):
        return False
    try:
        parsed = urlsplit(value)
        return parsed.scheme in ('http', 'https') and bool(parsed.hostname) and not parsed.username and not parsed.password
    except ValueError:
        return False


def gallery_urls(raw):
    """Only inspect the documented gallery bing cache; never dump private stores."""
    try:
        data = json.loads(raw or b'{}')
        cache = data.get('bing', {})
        cache = json.loads(cache) if isinstance(cache, str) else cache
        items = cache.get('items', [])
        return list(dict.fromkeys(item['src'] for item in items[:128]
                                 if isinstance(item, dict) and valid_url(item.get('src'))))
    except (ValueError, TypeError, AttributeError):
        return []


def read_cache(device, key):
    require(re.fullmatch(r'[0-9a-f]{64}', key), 'Invalid cache key')
    path = 'cache/images/' + key
    # Reject symlinks and bound bytes before cat; raw caches have no extension.
    stat = device.shell('run-as', APP, 'sh', '-c',
                        'if [ -f ' + path + ' ] && [ ! -L ' + path + ' ]; then stat -c %s ' + path + '; fi').strip()
    if not stat.isdigit() or not 1 <= int(stat) <= MAX_IMAGE_BYTES:
        return None
    raw = read_app_file(device.serial, path)
    if raw is None or len(raw) != int(stat):
        return None
    try:
        return raw, image_info(raw)
    except ValueError:
        return None


def choose_source(device, cached_url=None, cache_key=None):
    if cached_url:
        require(valid_url(cached_url), 'Invalid --cached-url')
        key = hashlib.sha256(cached_url.encode()).hexdigest()
        require(not cache_key or cache_key == key, '--cache-key does not match --cached-url')
        candidates = [(cached_url, key)]
    elif cache_key:
        candidates = [(None, cache_key)]
    else:
        candidates = [(url, hashlib.sha256(url.encode()).hexdigest()) for url in gallery_urls(
            read_app_file(device.serial, 'files/store/gallery.json'))]
    for url, key in candidates:
        found = read_cache(device, key)
        if found:
            raw, info = found
            if url or len(raw) <= 1024 * 1024:
                return url, raw, dict(info, cache_key=key, source_mode='original-url-cache' if url else 'copied-cache-asset',
                                      probe_only=not bool(url), sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw))
    require(not (cached_url or cache_key), 'Requested cache entry is missing, unsupported, or too large for an asset')
    for key in sorted(list_files(device, 'cache/images'))[:512]:
        if re.fullmatch(r'[0-9a-f]{64}', key):
            found = read_cache(device, key)
            if found and len(found[0]) <= 1024 * 1024:
                raw, info = found
                return None, raw, dict(info, cache_key=key, source_mode='copied-cache-asset', probe_only=True,
                                       sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw))
    raise RuntimeError('No existing bounded PNG/JPEG cache image; no synthetic image or public download attempted')


def fixture_documents(pan):
    require(type(pan) is bool, 'pan must be a literal boolean')
    provider = {'id': PROVIDER, 'name': 'Render layer image source', 'version': '1.0.0', 'api': 6,
                'main': 'main.lua', 'permissions': ['network'],
                'exports': {'daily': {'path': 'public.daily', 'shape': 'photos/1'}}}
    reader = {'id': PROBE, 'name': 'Render layer probe', 'version': '1.0.0', 'api': 6, 'permissions': [],
              'uses': {'daily': {'export': PROVIDER + '.daily', 'shape': 'photos/1'}},
              'pages': [{'id': 'stage', 'view': 'views/stage.json', 'title': 'Render layer probe'}]}
    view = {'layout': 'stage', 'slots': {'stage': {'type': 'carousel', 'items': '{{uses.daily.photos}}',
                                               'interval': 30, 'duration': 800, 'fit': 'pan', 'pan': pan}}}
    return provider, reader, view


def prepare_fixture(directory, pan, url, raw, info, token):
    provider, reader, view = fixture_documents(pan)
    source = directory / PROVIDER
    target = directory / PROBE
    source.mkdir()
    (target / 'views').mkdir(parents=True)
    if not url:
        (source / 'assets').mkdir()
        asset = 'assets/cached' + info['extension']
        (source / asset).write_bytes(raw)
        url = 'asset:' + asset
        provider['permissions'] = []
    (source / 'manifest.json').write_text(json.dumps(provider))
    (target / 'manifest.json').write_text(json.dumps(reader))
    for path in (source, target):
        (path / 'NOTICE').write_text('ThinkOS functional fixture owner: ' + token + '\n')
    (target / 'views/stage.json').write_text(json.dumps(view))
    state = json.dumps({'public': {'daily': {'photos': [{'id': 'cached-probe', 'src': url, 'title': ''}]}}}, separators=(',', ':'))
    # Long-bracket content is generated JSON, never Lua source interpolation.
    delimiter = '='
    while ']' + delimiter + ']' in state:
        delimiter += '='
    (source / 'main.lua').write_text('tos.on("start", function() tos.state.set(tos.json.decode([' + delimiter + '[' +
                                      state + ']' + delimiter + '])) end)\n')
    packed = directory / 'packed'
    return [package_plugin(path, packed)[0] for path in (source, target)]


def crash_evidence(lines, process_id):
    """A signal from another process must not be attributed to ThinkOS."""
    pid_pattern = r'\bpid[: ]+\s*' + str(process_id) + r'\b'
    signal_lines = [line for line in lines if 'Fatal signal ' in line and re.search(pid_pattern, line)]
    tombstone_lines = [line for line in lines if ('>>> ' + APP + ' <<<') in line and re.search(pid_pattern, line)]
    java = [line for line in lines if re.search(r'Process: ' + re.escape(APP) + r', PID: ' + str(process_id) + r'\b', line)]
    return {'native': bool(signal_lines or tombstone_lines),
            'render_thread': any('RenderThread' in line for line in signal_lines + tombstone_lines),
            'java': bool(java), 'signal_lines': signal_lines}


class LogTail:
    def __init__(self, device, output):
        self.lines = []
        self.lock = threading.Lock()
        self.stream = os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w')
        # Keep old crash buffers intact. -T 1 tails without logcat -c.
        self.process = subprocess.Popen(device.base + ['logcat', '-b', 'main', '-b', 'system', '-b', 'crash',
            '-v', 'epoch', '-T', '1', 'ThinkOS:V', 'AndroidRuntime:E', 'libc:F', 'DEBUG:F',
            'OpenGLRenderer:V', 'ActivityManager:I', '*:S'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, errors='replace')
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        for line in self.process.stdout:
            with self.lock:
                if len(self.lines) < 100_000:
                    self.lines.append(line.rstrip())
                    self.stream.write(line)
                    self.stream.flush()

    def mark(self):
        with self.lock:
            return len(self.lines)

    def since(self, mark):
        with self.lock:
            return list(self.lines[mark:])

    def close(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.thread.join(timeout=5)
        self.stream.close()


class Journal:
    """Exact original private bytes; never place this journal in public artifacts."""
    def __init__(self, device, ui, media):
        self.directory = Path(tempfile.mkdtemp(prefix='thinkos-render-layer-recovery-'))
        self.data = {'version': 1, 'serial': device.serial, 'physical': PHYSICAL, 'ui': ui, 'media': media,
                     'token': uuid.uuid4().hex, 'owned_plugins': [], 'owned_digests': {},
                     'firewall': [], 'uid': None, 'files': {}}
        total = 0
        for path in protected_paths(device):
            raw = read_app_file(device.serial, path)
            total += len(raw or b'')
            require(total <= MAX_SNAPSHOT_BYTES, 'Private snapshot exceeds bounded budget')
            self.data['files'][path] = None if raw is None else {'base64': base64.b64encode(raw).decode(),
                                                               'sha256': hashlib.sha256(raw).hexdigest()}
        self.save()

    def save(self):
        temporary = self.directory / 'state.new'
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as stream:
            json.dump(self.data, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.directory / 'state.json')

    @classmethod
    def load(cls, directory):
        journal = cls.__new__(cls)
        journal.directory = Path(directory)
        require((journal.directory / 'state.json').stat().st_size <= 24 * 1024 * 1024, 'Recovery journal too large')
        journal.data = json.loads((journal.directory / 'state.json').read_text())
        data = journal.data
        require(data.get('version') == 1 and data.get('physical') == PHYSICAL and
                re.fullmatch(r'[0-9a-f]{32}', data.get('token', '')), 'Invalid private journal')
        require(set(FIXED_PATHS) <= set(data['files']) and len(data['files']) <= 1550 and
                all(PRIVATE_PATH.fullmatch(path) for path in data['files']), 'Invalid recovery file scope')
        require(set(data['owned_plugins']) <= set(IDS) and set(data['firewall']) <= {'iptables', 'ip6tables'}, 'Invalid ownership scope')
        require(set(data['owned_digests']) <= set(data['owned_plugins']) and all(
            isinstance(digests, list) and len(digests) <= 10 and all(re.fullmatch(r'[0-9a-f]{64}', value)
            for value in digests) for digests in data['owned_digests'].values()), 'Invalid owned package digests')
        if data['firewall']:
            require(type(data['uid']) is int and 10000 <= data['uid'] <= 199999, 'Invalid app UID')
        journal.bytes()  # Validate every checksum before changing the device.
        return journal

    def bytes(self):
        result = {}
        total = 0
        for path, entry in self.data['files'].items():
            raw = None if entry is None else base64.b64decode(entry['base64'], validate=True)
            total += len(raw or b'')
            require(total <= MAX_SNAPSHOT_BYTES and (entry is None or hashlib.sha256(raw).hexdigest() == entry['sha256']),
                    'Private snapshot checksum/budget mismatch')
            result[path] = raw
        return result


def firewall_rule(journal):
    return ['OUTPUT', '-m', 'owner', '--uid-owner', str(journal.data['uid']), '-m', 'comment',
            '--comment', 'thinkos-render-layer-' + journal.data['token'], '-j', 'REJECT']


def root_program(device, program, arguments, check=True):
    import shlex
    require(program in ('iptables', 'ip6tables'), 'Unexpected root program')
    # Fixed host test helper only. No su is added to the Android application.
    result = subprocess.run(device.base + ['shell', '-T', shlex.join(['su', '-c', shlex.join([program] + arguments)])],
                            capture_output=True, timeout=25)
    if check and result.returncode:
        raise RuntimeError('Owned firewall command failed: ' + (result.stderr + result.stdout).decode(errors='replace')[:1000])
    return result.stdout


def block_network(device, journal):
    package = device.shell('dumpsys', 'package', APP)
    match = re.search(r'\buserId=(\d+)', package)
    require(match and 10000 <= int(match[1]) <= 199999, 'Unexpected application UID')
    journal.data['uid'] = int(match[1])
    for program in ('iptables', 'ip6tables'):
        # Record the unique owned rule before insertion, allowing interrupted recovery.
        journal.data['firewall'].append(program)
        journal.save()
        root_program(device, program, ['-I'] + firewall_rule(journal)[:1] + ['1'] + firewall_rule(journal)[1:])


def unblock_network(device, journal):
    import shlex
    for program in list(journal.data['firewall']):
        command = shlex.join([program, '-C'] + firewall_rule(journal))
        result = subprocess.run(device.base + ['shell', '-T', shlex.join(['su', '-c', command])],
                                capture_output=True, timeout=25)
        if result.returncode == 0:
            root_program(device, program, ['-D'] + firewall_rule(journal))
        else:
            require(result.returncode == 1 and not result.stdout.strip() and
                    (not result.stderr.strip() or b'Bad rule' in result.stderr or b'does a matching rule exist' in result.stderr),
                    'Cannot verify removal of owned firewall rule; private journal retained')
        journal.data['firewall'].remove(program)
        journal.save()


def temporary_settings(device, journal):
    original = journal.bytes().get('files/settings/local.json')
    settings = json.loads(original or b'{}')
    require(isinstance(settings, dict) and isinstance(settings.get('system', {}), dict), 'Invalid original local settings')
    system = settings.setdefault('system', {})
    system.update({'onboarding.initialized': True, 'onboarding.done': 1, 'update.enabled': False,
                   'update.auto_install': False, 'plugins.auto_update': 'off'})
    restore_app_file(device.serial, 'files/settings/local.json', json.dumps(settings).encode())
    # AtomicFile would otherwise prefer an old .bak over the temporary base.
    restore_app_file(device.serial, 'files/settings/local.json.bak', None)


def wait_page(device, page, seconds=20):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        require(pid(device) is not None, 'Application exited while waiting for page')
        device.debug('page', value=page)
        state = debug_json(device, 'ui_state')
        if state.get('page') == page:
            return state
        time.sleep(.2)
    raise RuntimeError('Page unavailable: ' + page)


def restore_ui(device, ui):
    device.debug('locale', value=ui['locale'])
    for command, key in (('theme', 'theme'), ('hold_attention', 'attention'), ('lux', 'lux')):
        device.debug(command, **({'value': ui[key]} if ui.get(key) is not None else {}))
    if ui.get('presence') is None:
        device.debug('hold_presence')
    else:
        device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'hold_presence',
                     '--ez', 'value', 'true' if ui['presence'] else 'false')
    device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation',
                 '--ei', 'value', ui['orientation'])
    wait_page(device, ui['page'])
    device.debug('variant', value=ui['variant'])
    state = debug_json(device, 'ui_state')
    require(all(state.get(key) == ui.get(key) for key in ('locale', 'orientation', 'theme', 'attention', 'lux', 'presence',
                                                       'page', 'variant', 'onboardingOpen', 'safeMode')), 'Original UI overrides/navigation differ')


def verify_owned_directory(device, journal, owner):
    for directory in (owner, '.previous-' + owner):
        index = read_app_file(device.serial, 'files/dev-plugins/' + directory + '/index.json')
        if index is not None:
            require(len(index) <= 64 * 1024 and json.loads(index).get('digest') in journal.data['owned_digests'].get(owner, []),
                    'Owned dev directory was replaced by another writer; refusing overwrite/removal')


def restore_journal(device, journal):
    require(device.shell('getprop', 'ro.serialno').strip() == PHYSICAL, 'Wrong physical device')
    audio_zero(device)
    device.shell('am', 'force-stop', APP)
    # Keep fresh public-data responses out of the exact-byte recovery window.
    # A prior restoration may already have removed its rules before failing.
    if not journal.data['firewall']:
        block_network(device, journal)
    for owner in journal.data['owned_plugins']:
        verify_owned_directory(device, journal, owner)
        remove_plugin(owner, device.serial)  # No unrelated dev plugin is removed.
    original = journal.bytes()
    for path, raw in original.items():
        restore_app_file(device.serial, path, raw)
    require(all(read_app_file(device.serial, path) == raw for path, raw in original.items()), 'Stopped byte restoration failed')
    device.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
    restore_ui(device, journal.data['ui'])
    media = debug_json(device, 'media_state')
    require(not any(debug_json(device, command).get('showing') for command in ('legal_state', 'update_state')),
            'Preserve a visible legal/update dialog')
    require(not media.get('owner') and not media.get('kind') and not media.get('count'), 'Unexpected media session after restore')
    audio_zero(device)
    # Plugins can coalesce cached public-data writes during their normal startup.
    # Let those flush, then restore only their snapshotted stores; never mask a
    # settings/navigation mismatch or overwrite concurrent physical user input.
    time.sleep(2.5)
    require(debug_json(device, 'stage_state').get('downCount') == 0,
            'Physical user input during recovery; preserve the private journal')
    for path, raw in original.items():
        if path.startswith('files/store/') and read_app_file(device.serial, path) != raw:
            restore_app_file(device.serial, path, raw)
    require(all(read_app_file(device.serial, path) == raw for path, raw in original.items()),
            'Running byte verification differs; private recovery journal retained')
    require(not any(owned_dev_name(name) for name in list_files(device, 'files/dev-plugins')), 'Owned dev directory remains')
    unblock_network(device, journal)
    shutil.rmtree(journal.directory)
    return {'exact_private_bytes_observed_while_blocked': True, 'original_ui': True, 'media_idle': True, 'audio_streams_1_to_5_zero': True,
            'owned_dev_removed': True, 'owned_firewall_removed': True}


def run_case(device, log, case, observe_seconds):
    mark = log.mark()
    device.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
    process_id = pid(device)
    require(process_id is not None, 'Application did not start')
    case['pid'] = process_id
    try:
        device.prepare_display()
        device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 0)
        state = wait_page(device, 'home.main')
        require(not state.get('onboardingOpen') and not state.get('safeMode'), 'Unexpected welcome or safe mode')
        require(state.get('theme') == 'DARK' and state.get('attention') == 'AWAKE' and state.get('orientation') == 0,
                'DARK/AWAKE/landscape holds were not applied')
        until = time.monotonic() + 5
        while True:
            stage = debug_json(device, 'stage_state')
            require(stage.get('downCount') == 0, 'Real user touched device; aborting unattended probe')
            if stage.get('width', 0) > stage.get('height', 0) > 0:
                break
            require(time.monotonic() < until, 'Landscape precondition unavailable')
            time.sleep(.1)
        require(stage.get('animatorsEnabled'), 'System animators are disabled; probe refused')
        require(stage.get('downCount') == 0, 'Real user touched device; aborting unattended probe')
        case['viewport'] = {'width': stage['width'], 'height': stage['height']}
        # A single entry from home follows the original SLIDE path, not app flip.
        device.debug('page', value=PAGE)
        until = time.monotonic() + observe_seconds
        next_checkpoint = 0
        while time.monotonic() < until:
            current_pid = pid(device)
            if current_pid != process_id:
                case['process_lost'] = True
                case['replacement_pid'] = current_pid
                break
            if time.monotonic() >= next_checkpoint:
                current = debug_json(device, 'stage_state')
                require(current.get('downCount') == 0 and not current.get('touching'), 'Real user input observed; aborting probe')
                audio_zero(device)
                next_checkpoint = time.monotonic() + 1
            time.sleep(.1)
    except Exception as error:
        case['error'] = str(error)
    # Let crash_dump finish before reading evidence; never clear logcat.
    time.sleep(.5)
    lines = log.since(mark)
    case.update(crash_evidence(lines, process_id))
    case['displayed'] = any('[image:' + PROBE + '] displayed cached-probe' in line for line in lines)
    case['entered'] = any('DEBUG page ' + PAGE + ' -> ' + PAGE in line for line in lines)
    case['native_pan_seen'] = any('[image:' + PROBE + '] native-pan ' in line for line in lines)
    case['process_lost'] = case.get('process_lost', False) or pid(device) != process_id
    case['alive_at_end'] = not case['process_lost']
    case['functional_case_valid'] = case['entered'] and case['displayed'] and (case['native_pan_seen'] == case['pan'])
    return case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=PHYSICAL)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--rounds', type=int, default=2, help='1–5 paired restarts; each pair runs true then false')
    parser.add_argument('--observe-seconds', type=float, default=6, help='2–20 seconds per entry, no performance measurement')
    parser.add_argument('--cached-url', help='Use this exact already-cached URL; no download is allowed')
    parser.add_argument('--cache-key', help='Existing SHA256 URL key; without URL this is explicitly an asset probe')
    parser.add_argument('--restore', type=Path, help='Retry restoration from the private recovery directory only')
    args = parser.parse_args()
    require(1 <= args.rounds <= 5 and 2 <= args.observe_seconds <= 20, 'Probe outside bounded case budget')
    device = Device(args.serial)
    require(device.shell('getprop', 'ro.serialno').strip() == PHYSICAL, 'Unexpected physical device')
    if args.restore:
        print(json.dumps(restore_journal(device, Journal.load(args.restore))))
        return
    require(args.output is not None and not args.output.exists(), '--output must name a new directory')
    require(not args.cache_key or re.fullmatch(r'[0-9a-f]{64}', args.cache_key), 'Invalid --cache-key')
    foreground = device.shell('dumpsys', 'activity', 'activities')
    require(any(APP + '/.MainActivity' in line for line in foreground.splitlines()
                if 'mResumedActivity' in line or 'ResumedActivity:' in line), 'ThinkOS MainActivity must already be foreground')
    audio_zero(device)
    original = debug_json(device, 'ui_state')
    stage = debug_json(device, 'stage_state')
    media = debug_json(device, 'media_state')
    require(not any(debug_json(device, command).get('showing') for command in ('legal_state', 'update_state')),
            'Preserve a visible legal/update dialog')
    require(original.get('page') and not original.get('onboardingOpen') and not original.get('safeMode'), 'Preserve visible welcome/safe-mode session')
    require(not any(stage.get(key) for key in ('canGoBack', 'pageBack', 'panelOpen', 'touching', 'practice')),
            'Probe requires an idle base page with an empty return stack and no visible panel')
    require(not media.get('owner') and not media.get('kind') and not media.get('count') and media.get('status') == 'stopped',
            'Preserve existing media session, including paused/ended queues')
    dev_before = list_files(device, 'files/dev-plugins')
    require(not any(owned_dev_name(name) for name in dev_before), 'Same-name development plugin/stage already exists; refusing overwrite')
    args.output.mkdir(parents=True, mode=0o700)
    result = {'complete': False, 'reproduced': False, 'serial': args.serial, 'cases': [],
              'scope': 'Functional process/RenderThread detection only; no timing/FPS/gfxinfo or certificate attribution',
              'limitation': 'Even original URL-cache mode does not reproduce historical GPU/context/cache residency or the preceding rotation timing.'}
    journal = log = None
    stopped = False
    previous_pid = pid(device)
    def save():
        (args.output / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    def interrupted(signum, frame):
        raise RuntimeError('Interrupted by signal ' + str(signum) + '; restoring original state')
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupted)
    try:
        require(debug_json(device, 'stage_state').get('downCount') == stage.get('downCount'),
                'Real user input observed during preflight; probe refused')
        device.shell('am', 'force-stop', APP)
        stopped = True
        journal = Journal(device, original, media)
        result['private_recovery_journal'] = str(journal.directory)
        print('Private recovery journal: ' + str(journal.directory), flush=True)
        url, raw, info = choose_source(device, args.cached_url, args.cache_key)
        result['source'] = info
        save()
        block_network(device, journal)
        temporary_settings(device, journal)
        log = LogTail(device, args.output / 'logcat.txt')
        for round_number in range(1, args.rounds + 1):
            for pan in (True, False):
                device.shell('am', 'force-stop', APP)
                audio_zero(device)
                case = {'round': round_number, 'pan': pan}
                result['cases'].append(case)
                with tempfile.TemporaryDirectory(prefix='thinkos-render-layer-fixture-') as temporary:
                    directories = prepare_fixture(Path(temporary), pan, url, raw, info, journal.data['token'])
                    for directory in directories:
                        if directory.name not in journal.data['owned_plugins']:
                            # Already refused existing IDs before stopping the app; recheck before first publish.
                            require(not any(name == directory.name or name == '.previous-' + directory.name or
                                            name.startswith('.stage-' + directory.name + '-')
                                            for name in list_files(device, 'files/dev-plugins')), 'Development directory appeared concurrently')
                            journal.data['owned_plugins'].append(directory.name)
                            journal.save()
                        else:
                            verify_owned_directory(device, journal, directory.name)
                        digest = json.loads((directory / 'index.json').read_text())['digest']
                        digests = journal.data['owned_digests'].setdefault(directory.name, [])
                        if digest not in digests:
                            digests.append(digest)
                        journal.save()
                        publish_directory(directory, device.serial)
                run_case(device, log, case, args.observe_seconds)
                case['new_process'] = case.get('pid') is not None and case['pid'] != previous_pid
                previous_pid = case.get('pid')
                result['reproduced'] = result['reproduced'] or case.get('native', False)
                save()
                print(json.dumps(case), flush=True)
                require(case['new_process'], 'Did not observe a new application process')
                require(not case.get('error') or case.get('native') or case.get('process_lost'), case.get('error'))
                # A single native crash is sufficient evidence. Continue its false comparison,
                # then stop before repeated intentional starts can trip crash recovery.
                if not pan and any(item.get('process_lost') for item in result['cases']):
                    break
            if any(item.get('process_lost') for item in result['cases']):
                break
        result['complete'] = all(case.get('new_process') and (case.get('functional_case_valid') or case.get('native'))
                                 and (not case.get('process_lost') or case.get('native'))
                                 and not case.get('java') for case in result['cases'])
        result['interpretation'] = ('Native failure observed; compare paired cases and retain raw logs.' if result['reproduced'] else
                                    'No native failure observed in these bounded entries; this does not establish a fix or exclude the historical driver failure.')
    except Exception as error:
        result['error'] = str(error)
    finally:
        # Ignore a second termination request during the bounded recovery sequence.
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, signal.SIG_IGN)
        try:
            if journal:
                result['restoration'] = restore_journal(device, journal)
                result.pop('private_recovery_journal', None)
            elif stopped:
                device.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
                restore_ui(device, original)
        except Exception as error:
            result['restoration_error'] = str(error)
        try:
            result['audio_zero'] = audio_zero(device)
        except Exception as error:
            result['audio_error'] = str(error)
        if log:
            log.close()
        save()
    require(result['complete'] and not result.get('error') and not result.get('restoration_error') and not result.get('audio_error'),
            'Probe incomplete or restoration needs attention; inspect results.json and retained private journal')
    print(json.dumps({'complete': True, 'reproduced': result['reproduced'], 'source_mode': result['source']['source_mode'],
                      'restored': True}), flush=True)


if __name__ == '__main__':
    main()
