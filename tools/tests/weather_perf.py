#!/usr/bin/env python3
"""Check weather benchmark artifacts against the documented API-27 device budgets."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
# Same DARK scenes and single-core CPU convention as docs/13-pre-m1-hardening.md.
BASELINE = {'awake': 32.112, 'ambient': 2.923}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT / 'artifacts/weather')
    args = parser.parse_args()
    report = {'complete': False, 'scenes': {}, 'failures': []}
    pids = set()
    for name, seconds in [('weather-awake', 120), ('awake', 120), ('ambient', 600)]:
        folder = args.input / name
        cpu = json.loads((folder / 'cpu.json').read_text())
        gfx = (folder / 'gfx-after.txt').read_text()
        frames = int(re.search(r'Total frames rendered: (\d+)', gfx)[1])
        janky = int(re.search(r'Janky frames: (\d+)', gfx)[1])
        before = (folder / 'pid-before.txt').read_text().strip()
        after = (folder / 'pid-after.txt').read_text().strip()
        pids.update((before, after))
        memory = (folder / 'memory.txt').read_text()
        pss = int(re.search(r'^\s*TOTAL:\s*(\d+)', memory, re.M)[1])
        scene = {'durationSeconds': cpu['elapsed_s'], 'meanCpuPercent': cpu['mean_cpu_pct'],
                 'frames': frames, 'jankyFrames': janky, 'jankyPercent': round(janky / max(1, frames) * 100, 3),
                 'pssMiB': round(pss / 1024, 2), 'pid': before}
        if before != after: report['failures'].append(name + ': process restarted')
        if cpu['elapsed_s'] < seconds: report['failures'].append(name + ': sample too short')
        if not frames or scene['jankyPercent'] >= 5: report['failures'].append(name + ': janky frames >= 5%')
        if name in BASELINE:
            scene['baselineCpuPercent'] = BASELINE[name]
            scene['cpuGrowthPercent'] = round((cpu['mean_cpu_pct'] / BASELINE[name] - 1) * 100, 3)
            if scene['cpuGrowthPercent'] > 20: report['failures'].append(name + ': CPU grew by more than 20%')
        # bench.sh retains the system ring buffer; only this measured process counts.
        process_line = re.compile(r'^\d{2}-\d{2}\s+\S+\s+' + re.escape(before) + r'\s+')
        logs = '\n'.join(line for line in (folder / 'logcat.txt').read_text().splitlines() if process_line.match(line))
        if 'plugin CPU budget exceeded' in logs or 'FATAL EXCEPTION' in logs:
            report['failures'].append(name + ': runtime failure in sampled process')
        report['scenes'][name] = scene
    if len(pids) != 1: report['failures'].append('process changed between scenes')
    report['complete'] = not report['failures']
    (args.input / 'performance.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    assert report['complete'], 'Weather performance checks failed'


if __name__ == '__main__': main()
