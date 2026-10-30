#!/usr/bin/env python3
"""Vendor an IANA time zone database into shared/tzdata.

The ThinkSmart View runs Android 8.1 whose system tzdata is frozen at the ROM build
(2021a on the target device), so many governments' later rule changes are missing.
ThinkOS therefore carries its own copy of the IANA database and evaluates it itself.

Input is a directory of *compiled* TZif files exactly as produced by IANA's `zic`
(for example /usr/share/zoneinfo on macOS, or a tzdata release compiled with zic) together
with zone.tab, iso3166.tab and tzdata.zi from the same release. The TZif files are
copied byte-for-byte; nothing is regenerated or edited. Their SHA-256 values are recorded
in SOURCE.json so a later update can be reviewed as a plain diff.

    python3 tools/vendor_tzdata.py                       # from /usr/share/zoneinfo
    python3 tools/vendor_tzdata.py --source DIR --out shared/tzdata
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = Path('/usr/share/zoneinfo')
EXTRA_ZONES = ['Etc/UTC']          # not in zone.tab, but worth offering ("UTC")
MAX_ZONE_BYTES = 64 * 1024


def fail(message):
    sys.exit(f'vendor_tzdata: {message}')


def parse_coordinates(value):
    """zone.tab: +DDMM+DDDMM or +DDMMSS+DDDMMSS -> (lat, lon) in decimal degrees."""
    match = re.fullmatch(r'([+-])(\d{2})(\d{2})(\d{2})?([+-])(\d{3})(\d{2})(\d{2})?', value)
    if not match:
        fail(f'bad coordinates: {value!r}')
    ns, lat_d, lat_m, lat_s, ew, lon_d, lon_m, lon_s = match.groups()
    lat = int(lat_d) + int(lat_m) / 60 + int(lat_s or 0) / 3600
    lon = int(lon_d) + int(lon_m) / 60 + int(lon_s or 0) / 3600
    return round(lat * (-1 if ns == '-' else 1), 4), round(lon * (-1 if ew == '-' else 1), 4)


def read_tab(path):
    rows = []
    for line in path.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            rows.append(line.split('\t'))
    return rows


def check_tzif(zone, data):
    if len(data) > MAX_ZONE_BYTES:
        fail(f'{zone}: unexpectedly large TZif file ({len(data)} bytes)')
    if data[:4] != b'TZif' or data[4:5] not in (b'2', b'3', b'4'):
        fail(f'{zone}: not a TZif version 2+ file')
    if not data.rstrip(b'\n').rsplit(b'\n', 1)[-1]:
        fail(f'{zone}: missing POSIX TZ footer')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--source', type=Path, default=DEFAULT_SOURCE, help='directory of compiled TZif files')
    parser.add_argument('--out', type=Path, default=ROOT / 'shared/tzdata')
    args = parser.parse_args()
    source = args.source
    for name in ('zone.tab', 'iso3166.tab', 'tzdata.zi'):
        if not (source / name).is_file():
            fail(f'{source / name} is missing; need a complete tz release directory')

    header = (source / 'tzdata.zi').read_text(encoding='utf-8').splitlines()[0]
    match = re.fullmatch(r'# version (\d{4}[a-z])(?:-(\w+))?', header)
    if not match:
        fail(f'cannot read the release version from tzdata.zi header {header!r}')
    version, flavor = match.group(1), match.group(2) or 'main'

    zones = {}   # id -> (country, lat, lon, comment)
    for row in read_tab(source / 'zone.tab'):
        country, coordinates, zone = row[0], row[1], row[2]
        lat, lon = parse_coordinates(coordinates)
        zones[zone] = (country, lat, lon, row[3] if len(row) > 3 else '')
    for zone in EXTRA_ZONES:
        zones.setdefault(zone, ('', 0.0, 0.0, 'Coordinated Universal Time'))

    links = {}   # alias -> target (as written in tzdata.zi)
    for line in (source / 'tzdata.zi').read_text(encoding='utf-8').splitlines():
        if line.startswith('L '):
            _, target, alias = line.split()
            links[alias] = target

    def resolve(name):
        # Stop at the first zone.tab entry on the chain: it keeps its country (Africa/Asmera ->
        # Africa/Asmara, which is itself a link to Africa/Nairobi in the data).
        seen = set()
        while name in links and name not in zones and name not in seen:
            seen.add(name)
            name = links[name]
        return name

    out = args.out
    if out.exists():
        shutil.rmtree(out)
    (out / 'zoneinfo').mkdir(parents=True)

    hashes = {}
    for zone in sorted(zones):
        data = (source / zone).read_bytes()
        check_tzif(zone, data)
        target = out / 'zoneinfo' / zone
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[zone] = hashlib.sha256(data).hexdigest()

    # Aliases that are not zone.tab entries ("Asia/Calcutta", "US/Eastern", "UTC", ...).
    aliases = {}
    for alias in sorted(links):
        target = resolve(alias)
        if alias not in zones and target in zones:
            aliases[alias] = target

    (out / 'zones.tsv').write_text(''.join(
        f'{zone}\t{c}\t{lat}\t{lon}\t{comment}\n' for zone, (c, lat, lon, comment) in sorted(zones.items())), encoding='utf-8')
    (out / 'links.tsv').write_text(''.join(f'{a}\t{t}\n' for a, t in sorted(aliases.items())), encoding='utf-8')
    (out / 'countries.tsv').write_text(''.join(
        f'{row[0]}\t{row[1]}\n' for row in sorted(read_tab(source / 'iso3166.tab'))), encoding='utf-8')
    (out / 'VERSION').write_text(version + '\n', encoding='utf-8')
    digest = hashlib.sha256(''.join(f'{z}:{h}\n' for z, h in sorted(hashes.items())).encode()).hexdigest()
    (out / 'SOURCE.json').write_text(json.dumps({
        'version': version,
        'flavor': flavor,
        'origin': 'IANA tz database, compiled TZif files copied unchanged from ' + str(source),
        'zones': len(zones),
        'aliases': len(aliases),
        'digest': digest,
        'sha256': hashes,
    }, indent=1, sort_keys=True) + '\n', encoding='utf-8')
    size = sum(p.stat().st_size for p in (out / 'zoneinfo').rglob('*') if p.is_file())
    print(f'tzdata {version} ({flavor}): {len(zones)} zones, {len(aliases)} aliases, {size // 1024} KiB -> {out}')


if __name__ == '__main__':
    main()
