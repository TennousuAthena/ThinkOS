#!/usr/bin/env python3
"""Generate golden UTC-offset vectors for the Kotlin time zone engine.

The answers come from CPython's `zoneinfo` (an independent C implementation of TZif and the
POSIX TZ footer) reading the very same files that are vendored in shared/tzdata. Every UTC
transition between 1970 and 2100 is located (3-day sampling, then bisection to the second)
and the offset is recorded one second before and at the transition, together with a few
ordinary probes. The Kotlin engine must reproduce every row.

Output: android/app/src/test/resources/tz-golden.tsv.gz
    zone <TAB> epoch-second <TAB> utc-offset-seconds <TAB> isdst(0/1) <TAB> abbreviation
"""
import argparse
import datetime as dt
import gzip
from pathlib import Path
import sys
import zoneinfo

ROOT = Path(__file__).resolve().parents[1]
TZDATA = ROOT / 'shared/tzdata'
OUT = ROOT / 'android/app/src/test/resources/tz-golden.tsv.gz'
START = int(dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc).timestamp())
END = int(dt.datetime(2100, 12, 31, tzinfo=dt.timezone.utc).timestamp())
STEP = 3 * 86400
EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)


def probe(zone, t):
    local = (EPOCH + dt.timedelta(seconds=t)).astimezone(zone)
    return int(local.utcoffset().total_seconds()), 1 if local.dst() else 0, local.tzname()


def transitions(zone):
    found = []
    previous = probe(zone, START)
    t = START
    while t < END:
        nxt = min(t + STEP, END)
        current = probe(zone, nxt)
        if current[:2] != previous[:2] or current[2] != previous[2]:
            low, high = t, nxt            # change happened in (low, high]
            while high - low > 1:
                mid = (low + high) // 2
                if probe(zone, mid)[:2] != probe(zone, low)[:2] or probe(zone, mid)[2] != probe(zone, low)[2]:
                    high = mid
                else:
                    low = mid
            found.append(high)
        previous, t = current, nxt
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--out', type=Path, default=OUT)
    args = parser.parse_args()
    zoneinfo.reset_tzpath([str(TZDATA / 'zoneinfo')])
    zones = [line.split('\t')[0] for line in (TZDATA / 'zones.tsv').read_text().splitlines()]
    rows = []
    total = 0
    for name in zones:
        zone = zoneinfo.ZoneInfo(name)
        points = {START, END, 1_700_000_000, 1_900_000_000, 4_102_444_800}
        changes = transitions(zone)
        total += len(changes)
        for t in changes:
            points.update((t - 1, t))
        for a, b in zip(changes, changes[1:]):
            points.add((a + b) // 2)
        for t in sorted(points):
            offset, dst, abbreviation = probe(zone, t)
            rows.append(f'{name}\t{t}\t{offset}\t{dst}\t{abbreviation}\n')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.GzipFile(args.out, 'wb', mtime=0) as handle:
        handle.write(''.join(rows).encode('utf-8'))
    version = (TZDATA / 'VERSION').read_text().strip()
    print(f'tzdata {version}: {len(zones)} zones, {total} transitions, {len(rows)} rows, '
          f'{args.out.stat().st_size // 1024} KiB -> {args.out.relative_to(ROOT)}')


if __name__ == '__main__':
    sys.exit(main())
