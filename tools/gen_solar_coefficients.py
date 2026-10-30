#!/usr/bin/env python3
"""Generate the bounded VSOP87D Earth coefficient subset used by Sun.kt.

Download the original mathematical data into a fresh directory, then run this script elsewhere:
  python3 -I tools/gen_solar_coefficients.py /path/to/VSOP87D.ear
Source: https://ftp.imcce.fr/pub/ephem/planets/vsop87/VSOP87D.ear
This reads numerical data only. It never imports or executes downloaded content.
"""
import argparse
import hashlib
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA256 = "8b160c859136d467f2be7fc29efa8a9652e95516dfbde00e4c739d7ddc90ca91"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path,
        default=ROOT / "android/app/src/main/java/moe/kiwi/thinkos/astro/EarthVsop.kt")
    args = parser.parse_args()
    data = args.source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SOURCE_SHA256:
        raise SystemExit("Unexpected VSOP87D.ear digest; inspect original data before updating the generator")
    rows = {}
    key = None
    for line in data.decode("ascii").splitlines():
        if "VSOP87 VERSION" in line:
            match = re.search(r"VARIABLE (\d).*\*T\*\*(\d)", line)
            key = tuple(map(int, match.groups()))
            rows[key] = []
        else:
            coefficients = tuple(line.split()[-3:])
            if len(coefficients) != 3 or not all(re.fullmatch(r"-?\d+\.\d+", c) for c in coefficients):
                raise SystemExit("Invalid coefficient row")
            rows[key].append(coefficients)
    text = '''package moe.kiwi.thinkos.astro

import kotlin.math.cos

/**
 * Truncated VSOP87D Earth heliocentric ecliptic coordinates, equinox of date.
 * Mathematical coefficients: P. Bretagnon and G. Francou, Bureau des Longitudes (1988).
 * Original source: https://ftp.imcce.fr/pub/ephem/planets/vsop87/VSOP87D.ear
 * SHA-256: SOURCE_HASH
 * Only terms with |amplitude| * 0.05^power >= 1e-8 retained. For |tau| <= 0.05
 * (1950–2050), omitted absolute sums bound longitude error by 0.25 arcsec and latitude
 * by 0.08 arcsec, distance by 9.2e-7 AU. Inputs and series angles are radians.
 */
internal object EarthVsop {
    fun at(tau: Double): DoubleArray = doubleArrayOf(series(L, tau), series(B, tau), series(R, tau))

    private fun series(groups: Array<DoubleArray>, t: Double): Double {
        var sum = 0.0
        var power = 1.0
        for (terms in groups) {
            var group = 0.0
            var i = 0
            while (i < terms.size) {
                group += terms[i] * cos(terms[i + 1] + terms[i + 2] * t)
                i += 3
            }
            sum += group * power
            power *= t
        }
        return sum
    }
'''.replace("SOURCE_HASH", digest)
    for variable, name in [(1, "L"), (2, "B"), (3, "R")]:
        selected = []
        for (v, power), terms in rows.items():
            if v != variable:
                continue
            selected.append((power, [term for term in terms if float(term[0]) * 0.05 ** power >= 1e-8]))
        while not selected[-1][1]:
            selected.pop()
        text += f"\n    private val {name} = arrayOf(\n"
        for power, terms in selected:
            text += f"        doubleArrayOf( // tau^{power}\n"
            for a, b, c in terms:
                text += f"            {a}, {b}, {c},\n"
            text += "        ),\n"
        text += "    )\n"
    text += "}\n"
    args.output.write_text(text, encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
