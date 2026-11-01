#!/usr/bin/env python3
"""Leaks and endurance on the device: many queue switches, then a long live stream.

    python3 tools/tests/media_soak_device.py --switches 96                # 96 loads across formats, memory sampled every 16
    python3 tools/tests/media_soak_device.py --live-minutes 120           # HLS live for two hours, sampled every 30 s

A switch loads an item, waits until it plays, and stops it. The numbers that matter are the app's PSS (the peak
budget is 350 MB), the native and Java heaps, and, for the live run, that the stream never ends in an error and
the playhead keeps moving. Results: artifacts/streaming/soak.json.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device
from media_stream_device import BASE, Origin, PORT

REMOTE = '/sdcard/Movies/soak'
CLIPS = [('video-h264-1080p30-high.mp4', 'video'), ('video-hevc-1080p30-main.mp4', 'video'), ('video-vp9-1080p30.webm', 'video'),
         ('video-h264-480p30-ac3.mkv', 'video'), ('audio-flac-24bit-48k.flac', 'audio'), ('audio-mp3-cbr192.mp3', 'audio'),
         ('audio-ac3.ac3', 'audio'), ('video-h264-480p30.mov', 'video')]


def memory(device):
    """The App Summary of `dumpsys meminfo`, in MB: the categories say whether growth is heap, codecs or graphics."""
    text = device.shell('dumpsys', 'meminfo', 'moe.kiwi.thinkos')
    def number(label):
        match = re.search(r'^\s*' + label + r':?\s+(\d+)', text, re.M)
        return round(int(match.group(1)) / 1024, 1) if match else 0.0
    return {'pss_mb': number('TOTAL'), 'java_heap_mb': number('Java Heap'), 'native_heap_mb': number('Native Heap'),
            'code_mb': number('Code'), 'graphics_mb': number('Graphics'), 'other_mb': number('Private Other')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/streaming')
    parser.add_argument('--switches', type=int, default=0)
    parser.add_argument('--live-minutes', type=int, default=0)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    report = {}
    device.prepare_display()
    device.debug('hold_attention')
    device.open_page('player.video')

    if args.switches:
        device.shell('mkdir', '-p', REMOTE)
        for name, _ in CLIPS: device.push(args.samples / name, REMOTE + '/' + name)
        device.debug('media_stop')
        time.sleep(2)
        # One warm-up round so the decoders and caches exist before the first reading.
        for name, kind in CLIPS:
            device.load(REMOTE + '/' + name, kind=kind); time.sleep(2.5); device.debug('media_stop')
        samples = [{'switch': 0, **memory(device)}]
        failures = 0
        for count in range(1, args.switches + 1):
            name, kind = CLIPS[count % len(CLIPS)]
            device.load(REMOTE + '/' + name, kind=kind)
            ok = False
            for _ in range(12):
                state = device.state() or {}
                if state.get('status') == 'playing' and state.get('position', 0) > 700: ok = True; break
                if state.get('status') == 'error': break
                time.sleep(0.4)
            failures += 0 if ok else 1
            device.debug('media_stop')
            time.sleep(0.4)
            # Sampled every two rounds of the clip list, always after the same clip and once the decoders have let go.
            if count % (2 * len(CLIPS)) == 0:
                time.sleep(3)
                samples.append({'switch': count, **memory(device)})
                print('after %3d switches: %s' % (count, samples[-1]), flush=True)
        device.shell('rm', '-rf', REMOTE, check=False)
        # The graphics buffers belong to the video surface: once another page is showing they must be given back.
        device.open_page('home.main')
        time.sleep(6)
        samples.append({'switch': args.switches, 'after': 'leaving the video page', **memory(device)})
        print('after leaving the video page: %s' % samples[-1], flush=True)
        growth = samples[-2]['pss_mb'] - samples[0]['pss_mb']
        peak = max(s['pss_mb'] for s in samples)
        report['switches'] = {'count': args.switches, 'failures': failures, 'samples': samples, 'pss_growth_mb': round(growth, 1), 'pss_peak_mb': peak,
                              'pss_after_leaving_mb': samples[-1]['pss_mb']}
        print('switches: %d, failed to play: %d, PSS %.1f -> %.1f MB (peak %.1f), %.1f MB once the video page is left' % (
            args.switches, failures, samples[0]['pss_mb'], samples[-2]['pss_mb'], peak, samples[-1]['pss_mb']), flush=True)

    if args.live_minutes:
        origin = Origin(args.samples)
        origin.start()
        origin.warm('/live/hls/index.m3u8')
        device.load('%s/live/hls/index.m3u8' % BASE, kind='video')
        started = time.monotonic()
        rows, errors, last_position = [], 0, -1
        stalled = 0
        while time.monotonic() - started < args.live_minutes * 60:
            time.sleep(30)
            state = device.state() or {}
            row = {'minute': round((time.monotonic() - started) / 60, 1), 'status': state.get('status'), 'position': state.get('position'),
                   'liveOffset': state.get('liveOffset'), 'dropped': state.get('droppedFrames'), 'retry': state.get('retry'), **memory(device)}
            rows.append(row)
            if state.get('status') == 'error': errors += 1
            if state.get('position') == last_position and state.get('status') == 'playing': stalled += 1
            last_position = state.get('position')
            if len(rows) % 10 == 0: print(row, flush=True)
        origin.stop()
        report['live'] = {'minutes': args.live_minutes, 'samples': rows, 'error_samples': errors, 'frozen_samples': stalled,
                          'pss_first_mb': rows[0]['pss_mb'] if rows else 0, 'pss_last_mb': rows[-1]['pss_mb'] if rows else 0,
                          'pss_peak_mb': max(r['pss_mb'] for r in rows) if rows else 0}
        print('live: %d samples, error samples %d, frozen samples %d, PSS %.1f -> %.1f MB' % (len(rows), errors, stalled, report['live']['pss_first_mb'], report['live']['pss_last_mb']), flush=True)

    device.debug('media_stop')
    device.stop_log()
    path = args.output / 'soak.json'
    previous = json.loads(path.read_text()) if path.exists() else {}
    previous.update(report)
    path.write_text(json.dumps(previous, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    bad = report.get('switches', {}).get('failures', 0) or report.get('live', {}).get('error_samples', 0)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
