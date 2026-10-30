#!/usr/bin/env python3
"""Independent solar vectors from PyEphem/libastro (test dependency, never shipped to Android).

Run with: /tmp/thinkos-venv/astro/bin/python -I tools/gen_astro_golden.py
Uses USNO's fixed 34 arcminute refraction and the upper solar limb for sunrise/set.
Twilight/colour bands use geometric centre altitudes. See https://rhodesmill.org/pyephem/rise-set.html
The events are independently rooted from libastro's geometric altitude. PyEphem's next_rising /
next_setting iteration can stop far from the threshold near a pole (e.g. -89.9 on 2020-04-21),
and can miss one of two shallow crossings. Those convenience functions are not used as truth.
"""
import datetime as dt
import math
from pathlib import Path
import random
import zoneinfo
import ephem

ROOT = Path(__file__).resolve().parents[1]
PLACES = [
    ('Singapore', 'Asia/Singapore', 1.29, 103.85, 0),
    ('London', 'Europe/London', 51.5074, -.1278, 0),
    ('New_York', 'America/New_York', 40.7128, -74.006, 0),
    ('Cape_Town', 'Africa/Johannesburg', -33.925, 18.424, 0),
    ('Ushuaia', 'America/Argentina/Ushuaia', -54.802, -68.303, 0),
    ('Auckland', 'Pacific/Auckland', -36.849, 174.764, 0),
    ('Kiritimati', 'Pacific/Kiritimati', 1.872, -157.427, 0),
    ('Urumqi', 'Asia/Shanghai', 43.825, 87.617, 0),
    ('Kathmandu', 'Asia/Kathmandu', 27.717, 85.324, 1400),
    ('Reykjavik', 'Atlantic/Reykjavik', 64.147, -21.94, 0),
    ('Arctic_Circle', 'Europe/Helsinki', 66.56, 25.83, 0),
    ('Tromso', 'Europe/Oslo', 69.649, 18.955, 0),
    ('Longyearbyen', 'Arctic/Longyearbyen', 78.223, 15.627, 0),
    ('South_Pole', 'Antarctica/McMurdo', -89.9, 0, 0),
]


def ephem_date(t):
    return ephem.Date(t.astimezone(dt.timezone.utc).replace(tzinfo=None))


def millis(t):
    return round(t.datetime().replace(tzinfo=dt.timezone.utc).timestamp() * 1000)


def solar_samples(obs, start, end):
    """One-minute independent libastro samples; cache shared across all seven thresholds."""
    cache = {}
    def altitude(t):
        if t not in cache:
            obs.date = ephem.Date(t)
            sun = ephem.Sun(obs)
            cache[t] = math.degrees(sun.alt), math.degrees(sun.radius)
        return cache[t]
    count = math.ceil((end - start) * 1440)
    grid = [float(start) + (float(end) - float(start)) * i / count for i in range(count + 1)]
    return altitude, grid


def event_pair(altitude, grid, horizon, centre):
    def value(t):
        alt, radius = altitude(t)
        return alt - horizon + (0 if centre else radius)
    points = [(t, value(t)) for t in grid]
    events = []
    for (a, fa), (b, fb) in zip(points, points[1:]):
        if (fa < 0 <= fb) or (fa > 0 >= fb):
            rising = fb > fa
            while (b - a) * 86400 > 0.002:
                mid = (a + b) / 2
                if (value(mid) > 0) == rising:
                    b = mid
                else:
                    a = mid
            crossing = (a + b) / 2
            # Require the libastro altitude itself to be at the requested geometric threshold.
            assert abs(value(crossing)) < 0.00002
            events.append((crossing, rising))
    rise = next((t for t, rising in events if rising), None)
    setting = next((t for t, rising in reversed(events) if not rising), None)
    return [str(millis(ephem.Date(t))) if t is not None else '-' for t in (rise, setting)]


def main():
    rng = random.Random(2701)
    dates = set()
    for year in (2020, 2024, 2026, 2030):
        for month, day in ((3, 20), (6, 21), (9, 22), (12, 21), (3, 8), (3, 29), (11, 1)):
            dates.add(dt.date(year, month, day))
        dates.update(dt.date(year, 1, 1) + dt.timedelta(days=rng.randrange(365)) for _ in range(12))
    rows = [f'# PyEphem {ephem.__version__}; pressure=0; sunrise upper limb horizon=-34 arcmin-dip',
            '# place zone latitude longitude elevation input noon sunrise sunset civilDawn civilDusk nauticalDawn nauticalDusk astroDawn astroDusk minus8Rise minus8Set minus4Rise minus4Set plus6Rise plus6Set']
    for name, zone, lat, lon, elevation in PLACES:
        tz = zoneinfo.ZoneInfo(zone)
        obs = ephem.Observer()
        obs.lat, obs.lon = str(lat), str(lon)
        obs.elevation, obs.pressure = elevation, 0
        for date in sorted(dates):
            local = dt.datetime.combine(date, dt.time(12), tzinfo=tz)
            midnight = local.replace(hour=0)
            noon = obs.next_transit(ephem.Sun(), start=ephem_date(midnight))
            start, end = ephem.Date(noon - .5), ephem.Date(noon + .5)
            altitude, grid = solar_samples(obs, start, end)
            values = [name, zone, str(lat), str(lon), str(elevation), str(round(local.timestamp() * 1000)), str(millis(noon))]
            for horizon, centre in ((-34 / 60 - 1.76 / 60 * math.sqrt(elevation), False),
                                    (-6, True), (-12, True), (-18, True), (-8, True), (-4, True), (6, True)):
                values += event_pair(altitude, grid, horizon, centre)
            rows.append('\t'.join(values))
    output = ROOT / 'android/app/src/test/resources/astro-golden.tsv'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(rows) + '\n')
    print(f'{output}: {len(rows) - 2} independent solar vectors')


if __name__ == '__main__':
    main()
