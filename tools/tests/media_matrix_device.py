#!/usr/bin/env python3
"""Format matrix: play every sample from tools/media/gen_samples.sh on the device and compare with its expectation.

    tools/media/gen_samples.sh
    python3 tools/tests/media_matrix_device.py [--samples /tmp/thinkos-media-samples] [--only h264] [--keep]

Every file ends in exactly one of: PLAY (the playhead advanced), ERROR (a categorised error with a message),
or TIMEOUT (neither: a failure of the "no silent blank screen" rule). Results go to artifacts/streaming/.
"""
import argparse
import csv
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device

REMOTE_VIDEO = '/sdcard/Movies/matrix'
REMOTE_AUDIO = '/sdcard/Music/matrix'
PLAY_MILLIS = 2500
LIMIT_SECONDS = 22


def is_video(name):
    return name.startswith(('video-', 'sub-')) and not name.endswith(('.srt', '.vtt'))


def judge(expected, outcome, detail):
    """(passed, note). `unknown` expectations pass on any definite outcome, which is recorded."""
    if outcome == 'TIMEOUT': return False, 'neither played nor reported an error'
    if expected in ('play', 'play-with-ffmpeg-ext'):
        if outcome != 'PLAY': return False, 'expected playback'
        if detail.get('warning'): return False, 'played but warned: ' + detail['warning']
        if str(detail.get('seek', '')).startswith('fail'): return False, 'seek ' + detail['seek']
        return True, ''
    if expected in ('video-unsupported', 'container-unsupported', 'audio-unsupported'):
        if outcome != 'ERROR': return False, 'expected an explicit error'
        return (bool(detail.get('message')), '' if detail.get('message') else 'error without a message')
    if expected == 'unknown': return True, 'observed ' + outcome.lower()
    return False, 'unknown expectation ' + expected


def seek_check(device, state):
    """Jump to the middle of a seekable item and see the playhead follow. 'n/a' when the container cannot seek or is too short."""
    if not state.get('seekable') or state.get('duration', 0) < 6000: return 'n/a'
    target = state['duration'] // 2
    device.debug('media_seek', value=target)
    time.sleep(1.8)
    after = device.state() or {}
    return 'ok' if after.get('status') == 'playing' and abs(after.get('position', 0) - target - 1500) < 3500 else 'fail %s->%s' % (target, after.get('position'))


def run_one(device, name, remote, kind, extras=None):
    mark = device.load(remote, kind=kind, **(extras or {}))
    started = time.monotonic()
    first = None
    last = None
    outcome = 'TIMEOUT'
    while time.monotonic() - started < LIMIT_SECONDS:
        time.sleep(0.8)
        state = device.state()
        if state is None: continue
        last = state
        status = state['status']
        if status == 'error': outcome = 'ERROR'; break
        if status == 'playing':
            if first is None: first = state['position']
            if state['position'] - first >= PLAY_MILLIS: outcome = 'PLAY'; break
        if status == 'ended' and state['duration'] > 0: outcome = 'PLAY'; break
    detail = {}
    if last and outcome == 'PLAY' and last.get('status') == 'playing': detail['seek'] = seek_check(device, last)
    if last:
        error = last.get('error') or {}
        detail = {'status': last['status'], 'video': last.get('videoCodec', ''), 'audio': last.get('audioCodec', ''),
                  'size': '%dx%d' % (last.get('videoWidth', 0), last.get('videoHeight', 0)),
                  'decoder': last.get('videoDecoder') or last.get('audioDecoder', ''), 'dropped': last.get('droppedFrames', 0),
                  'warning': last.get('warning', ''), 'category': error.get('category', ''), 'message': error.get('message', ''),
                  'codec': error.get('codec', ''), 'tracks': '%d/%d' % (len(last.get('audioTracks', [])), len(last.get('textTracks', []))),
                  'seekable': last.get('seekable'), **{k: v for k, v in detail.items() if k == 'seek'}}
    return outcome, detail, device.media_log(mark)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/streaming')
    parser.add_argument('--only', default='', help='substring filter on the file name')
    parser.add_argument('--keep', action='store_true', help='leave the pushed samples on the device')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = list(csv.DictReader((args.samples / 'manifest.tsv').open(encoding='utf-8'), delimiter='\t'))
    rows = [row for row in manifest if row['expected_on_device'] != 'sidecar' and args.only in row['file']]

    device = Device(args.serial)
    device.start_log()
    device.shell('mkdir', '-p', REMOTE_VIDEO, REMOTE_AUDIO)
    for path in sorted(args.samples.iterdir()):
        if path.is_file() and path.name != 'manifest.tsv':
            device.push(path, (REMOTE_VIDEO if is_video(path.name) or path.suffix in ('.srt', '.vtt') else REMOTE_AUDIO) + '/' + path.name)
    device.prepare_display()
    device.open_page('player.video')
    time.sleep(1.5)

    results = []
    for row in rows:
        name = row['file']
        video = is_video(name)
        remote = (REMOTE_VIDEO if video else REMOTE_AUDIO) + '/' + name
        outcome, detail, notes = run_one(device, name, remote, 'video' if video else 'audio')
        passed, note = judge(row['expected_on_device'], outcome, detail)
        results.append({'file': name, 'expected': row['expected_on_device'], 'outcome': outcome, 'pass': 'PASS' if passed else 'FAIL',
                        'note': note, **detail})
        print('%-4s %-34s expected=%-22s got=%-7s %s %s %s' % ('PASS' if passed else 'FAIL', name, row['expected_on_device'], outcome,
              detail.get('video') or detail.get('audio') or '', detail.get('decoder', ''), detail.get('message') or note), flush=True)
        device.debug('media_stop')
        time.sleep(0.4)

    columns = ['file', 'expected', 'outcome', 'pass', 'note', 'video', 'audio', 'size', 'decoder', 'dropped', 'tracks', 'seekable', 'seek', 'category', 'codec', 'message', 'warning', 'status']
    with (args.output / 'format-matrix.tsv').open('w', encoding='utf-8', newline='') as out:
        writer = csv.DictWriter(out, fieldnames=columns, delimiter='\t', extrasaction='ignore')
        writer.writeheader()
        writer.writerows(results)
    failed = [r for r in results if r['pass'] == 'FAIL']
    print('\n%d files, %d passed, %d failed -> %s' % (len(results), len(results) - len(failed), len(failed), args.output / 'format-matrix.tsv'))
    if not args.keep:
        device.shell('rm', '-rf', REMOTE_VIDEO, REMOTE_AUDIO, check=False)
    device.stop_log()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
