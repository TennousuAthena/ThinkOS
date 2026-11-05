#!/usr/bin/env python3
"""Generate Android string resources from the shared system string tables.

shared/i18n/system/<table>.json is the single source of the OS's own text (the Server can reuse it to
draw settings forms). This script checks that every table has exactly the keys of English and the
same placeholders, then writes res/values*/strings.xml so Android's own locale resolution and lint
(MissingTranslation) apply.

    python3 tools/gen_android_strings.py            # write
    python3 tools/gen_android_strings.py --check    # verify only
"""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TABLES = ROOT / 'shared/i18n/system'
RES = ROOT / 'android/app/src/main/res'
# table name -> resource directories. Hong Kong and Taiwan both get Traditional Chinese.
TARGETS = {
    'en': ['values'], 'zh-Hans': ['values-zh-rCN'], 'zh-Hant': ['values-zh-rTW', 'values-zh-rHK'],
    'hi': ['values-hi'], 'es': ['values-es'], 'fr': ['values-fr'], 'ar': ['values-ar'],
    'bn': ['values-bn'], 'pt': ['values-pt'], 'ru': ['values-ru'], 'ur': ['values-ur'],
}
PLACEHOLDER = re.compile(r'\{(\d+)\}')


def load():
    tables = {}
    for name in TARGETS:
        path = TABLES / f'{name}.json'
        if not path.is_file():
            sys.exit(f'missing table {path}')
        tables[name] = json.loads(path.read_text(encoding='utf-8'))
    return tables


def check(tables):
    english = tables['en']
    problems = []
    for name, table in tables.items():
        for key in sorted(set(english) - set(table)):
            problems.append(f'{name}: missing key {key}')
        for key in sorted(set(table) - set(english)):
            problems.append(f'{name}: extra key {key}')
        for key, text in table.items():
            if key in english and set(PLACEHOLDER.findall(text)) != set(PLACEHOLDER.findall(english[key])):
                problems.append(f'{name}: placeholders of {key} differ from English')
            if any(int(index) < 1 for index in PLACEHOLDER.findall(text)):
                problems.append(f'{name}: placeholders of {key} start at 1')
            if not text.strip():
                problems.append(f'{name}: empty text for {key}')
    return problems


def android_text(text):
    if PLACEHOLDER.search(text):
        text = text.replace('%', '%%')            # literal percent signs must be doubled in a format string
    text = PLACEHOLDER.sub(lambda m: f'%{m.group(1)}$s', text)
    text = (text.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
            .replace('&', '&amp;').replace('<', '&lt;').replace('\n', '\\n'))
    # aapt collapses literal whitespace, including ADR-044's two-space separator.
    # Escape after backslashes so the compiled resource retains each space.
    text = re.sub(r' {2,}', lambda match: r'\u0020' * len(match.group()), text)
    if text.startswith(('@', '?')):
        text = '\\' + text
    return text


def render(table):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- GENERATED from shared/i18n/system by tools/gen_android_strings.py - DO NOT EDIT -->',
             '<resources>']
    for key, text in table.items():
        lines.append(f'    <string name="{key.replace(".", "_")}">{android_text(text)}</string>')
    lines.append('</resources>')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    tables = load()
    problems = check(tables)
    if problems:
        sys.exit('system strings are inconsistent:\n  ' + '\n  '.join(problems))
    if args.check:
        stale = [str(RES / directory / 'strings.xml') for name, directories in TARGETS.items()
                 for directory in directories
                 if not (RES / directory / 'strings.xml').exists()
                 or (RES / directory / 'strings.xml').read_text(encoding='utf-8') != render(tables[name])]
        if stale:
            sys.exit('generated strings are stale; run tools/gen_android_strings.py:\n  ' + '\n  '.join(stale))
        print(f'{len(tables)} tables x {len(tables["en"])} keys and generated resources are consistent')
        return
    for name, directories in TARGETS.items():
        for directory in directories:
            target = RES / directory / 'strings.xml'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(render(tables[name]), encoding='utf-8')
    print(f'wrote {sum(len(d) for d in TARGETS.values())} resource files, {len(tables["en"])} strings each')


if __name__ == '__main__':
    main()
