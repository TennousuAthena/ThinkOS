#!/usr/bin/env python3
"""Private recovery journal for native settings tests. Raw device data never enters artifacts."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time

from device_app_files import read_app_file, restore_app_file

PATHS = ('files/settings/local.json', 'files/settings/local.json.bak',
         'shared_prefs/deck.xml', 'shared_prefs/deck.xml.bak')
OPTIONAL_PATHS = tuple('files/store/'+name+suffix for name in ('player', 'iptv', 'radio') for suffix in ('.json', '.json.bak'))
RECOVERY_PATHS = ('shared_prefs/recovery.xml', 'shared_prefs/recovery.xml.bak')
PHYSICAL = 'HUA0BWUT'


def capture(device, ui, paths=PATHS):
    """Caller supplies a read-only ui_state; stop writes before taking exact byte snapshots."""
    if len(set(paths)) != len(paths) or not set(PATHS) <= set(paths) <= set(PATHS + OPTIONAL_PATHS + RECOVERY_PATHS):
        raise ValueError('Unexpected snapshot paths')
    if device.shell('getprop', 'ro.serialno').strip() != PHYSICAL:
        raise ValueError('Unexpected physical device')
    device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
    saved = {p: read_app_file(device.serial, p) for p in paths}
    data = {'version': 1, 'serial': device.serial, 'physical': PHYSICAL, 'ui': ui,
            'files': {p: None if b is None else {'base64': base64.b64encode(b).decode(),
                     'sha256': hashlib.sha256(b).hexdigest()} for p, b in saved.items()}}
    directory = Path(tempfile.mkdtemp(prefix='thinkos-settings-recovery-'))
    with os.fdopen(os.open(directory/'state.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
        json.dump(data, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    return directory


def restore(directory, device):
    directory = Path(directory)
    data = json.loads((directory/'state.json').read_text())
    if data.get('version') != 1 or data.get('physical') != PHYSICAL or not set(PATHS) <= set(data.get('files', {})) <= set(PATHS + OPTIONAL_PATHS + RECOVERY_PATHS):
        raise ValueError('Invalid recovery journal')
    saved = {}
    for path, item in data['files'].items():
        raw = None if item is None else base64.b64decode(item['base64'], validate=True)
        if item is not None and hashlib.sha256(raw).hexdigest() != item['sha256']:
            raise ValueError('Recovery checksum mismatch')
        saved[path] = raw
    if device.shell('getprop', 'ro.serialno').strip() != PHYSICAL:
        raise ValueError('Recovery must target the authorized physical device')
    device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
    for path, raw in saved.items(): restore_app_file(device.serial, path, raw)
    restored = {p: read_app_file(device.serial, p) == b for p, b in saved.items()}
    if not all(restored.values()): raise RuntimeError('Restored bytes differ; recovery journal retained')
    device.shell('am', 'start', '-W', '-n', 'moe.kiwi.thinkos/.MainActivity')
    ui = data['ui']
    device.debug('locale', value=ui['locale'])
    for command, key in (('theme', 'theme'), ('hold_attention', 'attention'), ('lux', 'lux')):
        device.debug(command, **({'value': ui[key]} if ui[key] is not None else {}))
    if ui.get('presence') is None:
        device.debug('hold_presence')
    else:
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command',
                     'hold_presence', '--ez', 'value', 'true' if ui['presence'] else 'false')
    device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command',
                 'orientation', '--ei', 'value', ui['orientation'])
    if ui.get('page'):
        until = time.monotonic() + 25
        while time.monotonic() < until:
            device.debug('page', value=ui['page'])
            raw = device.debug('ui_state')
            match = re.search(r'data="(\{.*\})"', raw)
            if match and json.loads(match[1]).get('page') == ui['page']: break
            time.sleep(.25)
        else:
            raise RuntimeError('Original page is not available yet; recovery journal retained')
    # A late navigation write must not silently invalidate the exact-byte restoration claim.
    if not all(read_app_file(device.serial, p) == b for p, b in saved.items()):
        raise RuntimeError('Final byte verification failed; recovery journal retained')
    shutil.rmtree(directory)
    return {'physical_serial': PHYSICAL, 'restored_files': restored}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('journal', type=Path)
    parser.add_argument('--serial')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(Path(__file__).parent/'tests'))
    from media_device import Device
    data = json.loads((args.journal/'state.json').read_text())
    result = restore(args.journal, Device(args.serial or data['serial']))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print('Restored exact settings and deck bytes; private journal removed.')


if __name__ == '__main__': main()
