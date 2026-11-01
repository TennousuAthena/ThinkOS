#!/usr/bin/env python3
"""Playback cost on the device: CPU of every process (decoding runs outside the app), dropped video frames, jank, memory.

    tools/media/gen_samples.sh && python3 tools/tests/media_perf_device.py [--seconds 45] [--only 1080p60]

For each scenario the test plays for a warm-up, then measures a window: cumulative CPU ticks of all processes from
/proc, the engine's own dropped-frame count, `dumpsys gfxinfo` frames and the app's PSS. 1.0 "core" means one core
fully busy; the device has eight. Needs the debug build. Results: artifacts/streaming/perf.json.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device

REMOTE = '/sdcard/Movies/perf'
CLOCK_PAGE = 'clock.face'
LONG = {  # three minutes of each clip, made by looping the 12 s sample without re-encoding
    'video-h264-480p30.mp4': 'perf-h264-480p30.mp4', 'video-h264-720p30.mp4': 'perf-h264-720p30.mp4',
    'video-h264-1080p30-high.mp4': 'perf-h264-1080p30.mp4', 'video-h264-1080p60.mp4': 'perf-h264-1080p60.mp4',
    'video-hevc-1080p30-main.mp4': 'perf-hevc-1080p30.mp4', 'video-vp9-1080p30.webm': 'perf-vp9-1080p30.webm',
    'video-vp8-720p30.webm': 'perf-vp8-720p30.webm', 'audio-flac-24bit-48k.flac': 'perf-flac-24bit.flac',
    'audio-mp3-cbr192.mp3': 'perf-mp3.mp3',
}


def make_long(samples, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for source, name in LONG.items():
        target = directory / name
        if target.exists(): continue
        codec = ['-c:a', 'flac'] if name.endswith('.flac') else ['-c', 'copy']   # a copied FLAC loop keeps the first loop's length in its header
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-stream_loop', '14', '-i', str(samples / source), *codec, '-t', '180', str(target)], check=True)


class Probe:
    def __init__(self, device):
        self.device = device
        auxv = device.adb('exec-out', 'cat', '/proc/self/auxv')
        import struct
        self.hz = dict(struct.iter_unpack('<II', auxv))[17]

    def processes(self):
        text = self.device.shell('sh', '-c', 'cat /proc/[0-9]*/stat 2>/dev/null; echo ---; cat /proc/stat | head -1; cat /proc/uptime')
        body, _, tail = text.partition('---\n')
        found = {}
        for line in body.splitlines():
            head, _, rest = line.partition(' (')
            name, _, fields = rest.rpartition(') ')
            parts = fields.split()
            if len(parts) > 13 and head.isdigit(): found[int(head)] = (name, int(parts[11]) + int(parts[12]))
        cpu_line, uptime = tail.splitlines()[0], float(tail.splitlines()[1].split()[0])
        cpu = list(map(int, cpu_line.split()[1:8]))
        return found, sum(cpu) - cpu[3] - cpu[4], uptime

    def window(self, seconds):
        a, busy_a, up_a = self.processes()
        time.sleep(seconds)
        b, busy_b, up_b = self.processes()
        span = up_b - up_a
        deltas = sorted(((b[pid][1] - a[pid][1]) / self.hz / span, b[pid][0]) for pid in b if pid in a and b[pid][0] == a[pid][0])
        deltas = [(round(v, 3), n) for v, n in deltas if v > 0.004]
        return {'all_cores': round((busy_b - busy_a) / self.hz / span, 3), 'top': list(reversed(deltas))[:7], 'seconds': round(span, 1)}


def app_numbers(device):
    gfx = device.shell('dumpsys', 'gfxinfo', 'moe.kiwi.thinkos')
    frames = re.search(r'Total frames rendered: (\d+)', gfx)
    janky = re.search(r'Janky frames: (\d+) \(([\d.]+)%\)', gfx)
    mem = device.shell('dumpsys', 'meminfo', 'moe.kiwi.thinkos')
    pss = re.search(r'TOTAL\s+(\d+)', mem)
    return {'frames': int(frames.group(1)) if frames else 0, 'janky': int(janky.group(1)) if janky else 0,
            'pss_mb': round(int(pss.group(1)) / 1024, 1) if pss else 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/streaming')
    parser.add_argument('--seconds', type=int, default=45)
    parser.add_argument('--only', default='')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    device.start_log()
    long_dir = args.samples / 'perf'
    make_long(args.samples, long_dir)
    device.shell('mkdir', '-p', REMOTE)
    for path in sorted(long_dir.iterdir()): device.push(path, REMOTE + '/' + path.name)
    probe = Probe(device)
    device.prepare_display()    # theme, light and an AWAKE hold: the display must not drift to sleep during a long run

    def play(name, kind, page, **extras):
        device.debug('page', value=page)
        time.sleep(1)
        device.load(REMOTE + '/' + name, kind=kind, repeat='one', **extras)
        for _ in range(30):
            state = device.state() or {}
            if state.get('status') == 'playing' and state.get('position', 0) > 2000: return state
            time.sleep(0.7)
        return device.state() or {}

    keep_engaged = {'on': False}
    scenarios = [
        ('idle: clock face, nothing playing', lambda: (device.debug('media_stop'), device.debug('page', value=CLOCK_PAGE)), None),
        ('audio: FLAC 24-bit, clock face visible', lambda: play('perf-flac-24bit.flac', 'audio', CLOCK_PAGE), None),
        ('audio: MP3, clock face visible', lambda: play('perf-mp3.mp3', 'audio', CLOCK_PAGE), None),
        ('video: H.264 480p30', lambda: play('perf-h264-480p30.mp4', 'video', 'player.video'), None),
        ('video: H.264 720p30', lambda: play('perf-h264-720p30.mp4', 'video', 'player.video'), None),
        ('video: H.264 1080p30', lambda: play('perf-h264-1080p30.mp4', 'video', 'player.video'), None),
        ('video: H.264 1080p60', lambda: play('perf-h264-1080p60.mp4', 'video', 'player.video'), None),
        ('video: H.264 1080p60, controls shown', lambda: (play('perf-h264-1080p60.mp4', 'video', 'player.video'), keep_engaged.update(on=True))[0], 'touch'),
        ('video: HEVC 1080p30', lambda: play('perf-hevc-1080p30.mp4', 'video', 'player.video'), None),
        ('video: VP9 1080p30', lambda: play('perf-vp9-1080p30.webm', 'video', 'player.video'), None),
        ('video: VP8 720p30', lambda: play('perf-vp8-720p30.webm', 'video', 'player.video'), None),
    ]
    results = []
    for name, setup, mode in scenarios:
        if args.only and args.only not in name: continue
        device.debug('media_stop')
        time.sleep(1.5)
        keep_engaged['on'] = False
        started = setup()
        started = started if isinstance(started, dict) else (device.state() or {})
        time.sleep(8)
        before = device.state() or {}
        device.shell('dumpsys', 'gfxinfo', 'moe.kiwi.thinkos', 'reset')
        if mode == 'touch':
            # keep the control overlay up for the whole window, as someone scrubbing would
            end = time.monotonic() + args.seconds
            taps = []
            import threading
            def touch():
                while time.monotonic() < end:
                    device.tap(640, 300); time.sleep(6)
            thread = threading.Thread(target=touch, daemon=True); thread.start()
        window = probe.window(args.seconds)
        after = device.state() or {}
        numbers = app_numbers(device)
        row = {'scenario': name, **window, **numbers, 'video': after.get('videoCodec', ''), 'decoder': after.get('videoDecoder') or after.get('audioDecoder', ''),
               'size': '%dx%d' % (after.get('videoWidth', 0), after.get('videoHeight', 0)), 'fps': after.get('frameRate', 0),
               'dropped': after.get('droppedFrames', 0) - before.get('droppedFrames', 0), 'status': after.get('status', '')}
        results.append(row)
        print('%-42s all=%.2f cores  dropped=%-4s janky=%d/%d  pss=%.0f MB  %s %s | %s' % (
            name, row['all_cores'], row['dropped'], row['janky'], row['frames'], row['pss_mb'], row['size'], row['decoder'],
            ', '.join('%s %.2f' % (n.split('.')[-1][:22], v) for v, n in row['top'][:4])), flush=True)
    device.debug('media_stop')
    device.shell('rm', '-rf', REMOTE, check=False)
    device.stop_log()
    (args.output / 'perf.json').write_text(json.dumps(results, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('-> %s' % (args.output / 'perf.json'))


if __name__ == '__main__':
    main()
