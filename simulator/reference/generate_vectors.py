#!/usr/bin/env python3
"""Explicitly regenerate reviewed reference vectors using the unchanged JVM implementation."""
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

def main():
    settings = json.loads((HERE / 'build/jvm.json').read_text())
    cases = []
    for width, height in [(1280, 800), (800, 1280), (12, 8)]:
        for layout, role in [('focus', 'main'), ('focus', 'header'), ('split', 'lead'), ('split', 'main'), ('stage', 'stage'), ('stage', 'under')]:
            cases.append({'command': 'slot', 'args': [layout, role, width, height, 1.625, True, True]})
        for sizes in ['wide,medium,medium,large,wide,medium,small,small,small,small', 'large,large,wide,wide', 'large,large,large', 'small,invalid,medium']:
            for rtl in [False, True]:
                cases.append({'command': 'tiles', 'args': [sizes, width, height, 1.625, rtl]})
    for available, measured, weights, gap in [(100, '10,99,99', '0,1,2', 5), (100, '10,20', '0,0', 10), (10, '2147483647,2147483647,0', '0,NaN,1', 0), (101, '5,5,5', '1e308,1e308,5e-324', 0), (0, '20,20', '1,1', 20)]:
        cases.append({'command': 'vertical', 'args': [available, measured, weights, gap]})
    for width, height in [(1280, 800), (800, 1280)]:
        for media in [False, True]:
            cases.append({'command': 'panel', 'args': [width, height, 1.625, True, True, 2, media, True, True, 6]})
    for color in ['#ffffff', '#000000', '#0B5CC4', '#D24726', '#ffff00', 'bad']:
        for night in [0, 1]: cases.append({'command': 'colors', 'args': [color, night]})
    for case in cases:
        args = [str(value).lower() if isinstance(value, bool) else str(value) for value in case['args']]
        result = subprocess.run([settings['java'], '-cp', settings['classpath'], settings['main'], case['command'], *args], check=True, capture_output=True, text=True)
        case['expected'] = json.loads(result.stdout)
    sources = json.loads((HERE / 'build/source-hashes.json').read_text())
    (HERE / 'vectors.json').write_text(json.dumps({'source': 'unchanged production Kotlin, JVM 17', 'kotlin': '2.2.0', 'sha256': sources, 'cases': cases}, indent=2) + '\n')
    print(f'Generated {len(cases)} JVM vectors. Review changes before accepting new baselines.')

if __name__ == '__main__': main()
