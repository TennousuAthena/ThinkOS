#!/usr/bin/env python3
"""Measure Android process CPU from cumulative kernel ticks (100% = one core)."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--threads', action='store_true', help='Also report CPU for threads present throughout the sample')
    args = parser.parse_args()
    if args.pid <= 0 or args.seconds <= 0:
        parser.error('PID and seconds must be positive')
    base = ['adb', '-s', args.serial]
    # Linux AT_CLKTCK=17; this target is a little-endian 32-bit Android device.
    auxv = subprocess.check_output(base + ['exec-out', 'cat', '/proc/self/auxv'], timeout=10)
    values = dict(struct.iter_unpack('<II', auxv))
    tick_hz = values[17]
    if not 1 <= tick_hz <= 10000:
        raise RuntimeError('Invalid kernel clock tick rate')

    def sample():
        lines = subprocess.check_output(base + ['shell', 'cat', f'/proc/{args.pid}/stat', '/proc/uptime'], text=True, timeout=10).splitlines()
        # The comm field can itself contain spaces and parentheses.
        fields = lines[0].rpartition(')')[2].split()
        return int(fields[11]) + int(fields[12]), float(lines[1].split()[0])

    def thread_ticks():
        command = f'for task in /proc/{args.pid}/task/*; do cat "$task/stat" 2>/dev/null; done'
        lines = subprocess.check_output(base + ['shell', command], text=True, timeout=10).splitlines()
        result = {}
        for line in lines:
            prefix, _, suffix = line.rpartition(')')
            tid, _, name = prefix.partition(' (')
            fields = suffix.split()
            result[int(tid)] = (name, int(fields[11]) + int(fields[12]))
        return result

    first_threads = thread_ticks() if args.threads else {}
    first_ticks, first_time = previous_ticks, previous_time = sample()
    deadline = time.monotonic() + args.seconds
    samples = []
    result = {'pid': args.pid, 'tick_hz': tick_hz, 'unit': 'percent of one CPU core', 'samples': samples}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    while time.monotonic() < deadline:
        time.sleep(min(10, max(0, deadline - time.monotonic())))
        ticks, uptime = sample()
        elapsed = uptime - previous_time
        if elapsed <= 0 or ticks < previous_ticks:
            raise RuntimeError('Process or device restarted during sampling')
        samples.append({'elapsed_s': round(uptime-first_time, 2), 'cpu_pct': round((ticks-previous_ticks)/tick_hz/elapsed*100, 3)})
        result['elapsed_s'] = round(uptime-first_time, 2)
        result['mean_cpu_pct'] = round((ticks-first_ticks)/tick_hz/(uptime-first_time)*100, 3)
        result['max_cpu_pct'] = max(row['cpu_pct'] for row in samples)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        previous_ticks, previous_time = ticks, uptime
    if args.threads:
        rows = []
        for tid, (name, ticks) in thread_ticks().items():
            if tid in first_threads:
                rows.append({'tid': tid, 'name': name, 'cpu_pct': round((ticks-first_threads[tid][1])/tick_hz/result['elapsed_s']*100, 3)})
        result['persistent_threads'] = sorted(rows, key=lambda row: row['cpu_pct'], reverse=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"CPU over {result['elapsed_s']:.1f}s: mean {result['mean_cpu_pct']:.2f}%, max {result['max_cpu_pct']:.2f}% (one core)")


if __name__ == '__main__':
    main()
