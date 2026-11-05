#!/usr/bin/env python3
"""Generate the Android vector drawables for the icons taken from Iconfont (assets/icons).

assets/icons/glyphs.json names each icon the OS knows (`wifi`, `tv`, `clear-day`, ...) and the SVG it is drawn from. This script turns every SVG
into res/drawable/glyph_<file>.xml (white, so the OS can tint it) and writes the name -> resource table the renderer uses, so the
two cannot drift apart. The shapes are drawn at the size they are shown at: no bitmap is scaled.

    python3 tools/gen_glyphs.py            # write
    python3 tools/gen_glyphs.py --check    # verify only (exits 1 when something is out of date)
"""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / 'assets/icons'
DRAWABLES = ROOT / 'android/app/src/main/res/drawable'
TABLE = ROOT / 'android/app/src/main/java/moe/kiwi/thinkos/ui/GlyphResources.kt'
PATH_DATA = re.compile(r'^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]+$')
NAME = re.compile(r'^[a-z][a-z0-9-]*$')


def resource(entry):
    """One drawable per SVG file: several names (brightness, clear-day, sun) can share the same picture."""
    return 'glyph_' + Path(entry['file']).stem.replace('-', '_')


def drawable(name, svg_file):
    root = ET.fromstring(svg_file.read_text(encoding='utf-8'))
    ns = '{http://www.w3.org/2000/svg}'
    if root.tag != ns + 'svg':
        sys.exit(f'{svg_file}: not an SVG')
    view = root.attrib.get('viewBox', '').split()
    if len(view) != 4 or view[0:2] != ['0', '0']:
        sys.exit(f'{svg_file}: viewBox must start at 0 0')
    paths = [e for e in root.iter() if e.tag != ns + 'svg']
    if len(paths) != 1 or paths[0].tag != ns + 'path' or set(paths[0].attrib) != {'d'}:
        sys.exit(f'{svg_file}: expected a single <path d="..."> and nothing else')
    d = paths[0].attrib['d'].strip()
    if not PATH_DATA.match(d):
        sys.exit(f'{svg_file}: unexpected characters in the path data')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- GENERATED from assets/icons/{svg_file.relative_to(ICONS).as_posix()} by tools/gen_glyphs.py - DO NOT EDIT -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp"\n'
        f'    android:viewportWidth="{view[2]}" android:viewportHeight="{view[3]}">\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />\n'
        '</vector>\n')


def table(glyphs):
    rows = ',\n'.join(f'        "{name}" to R.drawable.{resource(entry)}' for name, entry in glyphs.items())
    return (
        '// GENERATED from assets/icons/glyphs.json by tools/gen_glyphs.py - DO NOT EDIT.\n'
        'package moe.kiwi.thinkos.ui\n\n'
        'import moe.kiwi.thinkos.R\n\n'
        '/** Which drawable draws each icon name that comes from Iconfont. */\n'
        'internal object GlyphResources {\n'
        '    val ids: Map<String, Int> = mapOf(\n' + rows + '\n    )\n}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    manifest = json.loads((ICONS / 'glyphs.json').read_text(encoding='utf-8'))
    wanted = {}
    for name, entry in manifest['glyphs'].items():
        if not NAME.match(name):
            sys.exit(f'bad icon name: {name}')
        wanted[DRAWABLES / (resource(entry) + '.xml')] = drawable(name, ICONS / entry['file'])
    wanted[TABLE] = table(manifest['glyphs'])
    stale = [p for p, text in wanted.items() if not p.exists() or p.read_text(encoding='utf-8') != text]
    # A drawable left over from an icon that was removed would ship in the APK for nothing.
    orphans = [p for p in DRAWABLES.glob('glyph_*.xml') if p not in wanted]
    if args.check:
        for p in stale: print('out of date:', p.relative_to(ROOT))
        for p in orphans: print('no longer in glyphs.json:', p.relative_to(ROOT))
        if stale or orphans: sys.exit(1)
        print(f'{len(manifest["glyphs"])} icon names, resources up to date')
        return
    for p, text in wanted.items():
        p.write_text(text, encoding='utf-8')
    for p in orphans: p.unlink()
    print(f'wrote {sum(1 for p in wanted if p != TABLE)} drawables for {len(manifest["glyphs"])} icon names, and {TABLE.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
