#!/usr/bin/env python3
"""The Lua media API on the device: every tos.media function, events and error answers, and the permission gate.

    tools/media/gen_samples.sh && python3 tools/tests/media_api_device.py

Two throwaway plugins from tools/tests/fixtures are pushed as development overrides: `media-probe` (media, library and
network permissions) runs a scripted tour of the API, `media-probe-denied` has no permissions at all and must be refused
everywhere. Both are removed again, together with the pushed audio files.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from media_device import Device
from media_stream_device import Origin, PORT
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    device.prepare_display()
    device.shell('mkdir', '-p', '/sdcard/Music/probe')
    device.push(args.samples / 'audio-mp3-cbr192.mp3', '/sdcard/Music/probe/a.mp3')
    device.push(args.samples / 'audio-flac-16bit-44k.flac', '/sdcard/Music/probe/b.flac')
    findings = []

    def check(name, ok, detail=''):
        findings.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def collect(prefix, until, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            lines = [line.split('ThinkOS : ', 1)[-1] for line in device.log_since(0) if prefix in line]
            if any(until in line for line in lines): return lines
            time.sleep(1)
        return [line.split('ThinkOS : ', 1)[-1] for line in device.log_since(0) if prefix in line]

    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-media-api-') as temporary:
            for name in ('media-probe-denied', 'media-probe'):
                package, _ = package_plugin(ROOT / 'tools/tests/fixtures' / name, Path(temporary) / 'out')
                publish_directory(package, args.serial)
                if name == 'media-probe-denied':
                    lines = collect('MEDIA-PROBE-DENIED', 'DONE', 40)
                    fails = [l for l in lines if 'FAIL' in l]
                    check('without permissions every media call is refused with a reason', any('DONE failed=0' in l for l in lines) and not fails,
                          fails or [l for l in lines if 'DONE' in l])
                    check('and each refusal was actually exercised', len([l for l in lines if 'PASS' in l]) == 4, len([l for l in lines if 'PASS' in l]))
        lines = collect('MEDIA-PROBE ', 'DONE', 240)
        for line in lines:
            if 'FAIL' in line: print('     ' + line)
        done = [l for l in lines if 'DONE' in l]
        passes = len([l for l in lines if ' PASS ' in l])
        check('the scripted tour of tos.media finished', bool(done), lines[-3:])
        check('and nothing in it failed', bool(done) and 'failed=0' in done[0] and not [l for l in lines if ' FAIL ' in l], done[0] if done else '')
        check('with a meaningful number of assertions behind it', passes >= 40, passes)
    finally:
        device.debug('media_stop')
        for plugin in ('media-probe', 'media-probe-denied'):
            try: remove_plugin(plugin, args.serial)
            except Exception as error: print('could not remove %s: %s' % (plugin, error))
        device.shell('rm', '-rf', '/sdcard/Music/probe', check=False)
        origin.stop()
        device.stop_log()
    failed = findings.count(False)
    print('\n%d checks, %d failed' % (len(findings), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
