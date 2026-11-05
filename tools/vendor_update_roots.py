#!/usr/bin/env python3
"""Reproduce the pinned OTA CA subset from an already downloaded curl/Mozilla bundle.

No networking, private keys, system trust-store changes or device commands.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import ssl

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'android/app/src/main/resources/moe/kiwi/thinkos/update'
HEADER = (
    '# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.\n'
    '# If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.\n'
    '# Selected from curl/Mozilla 2026-10-20; complete certificates retained without changes.\n'
    '# Source and DER SHA-256 fingerprints: roots-source.json.\n\n'
)


def render(source):
    manifest = json.loads((DIRECTORY / 'roots-source.json').read_text())
    with source.open('rb') as stream:
        raw = stream.read(512 * 1024 + 1)
    if len(raw) > 512 * 1024 or hashlib.sha256(raw).hexdigest() != manifest['source_sha256']:
        raise ValueError('The source bundle does not match its pinned SHA-256')
    entries = re.findall(r'(?m)^([^\n]+)\n={3,}\n(?:#[^\n]*\n)*(-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----)', raw.decode('utf-8'))
    certificates = dict(entries)
    if len(certificates) != len(entries):
        raise ValueError('Duplicate source certificate name')
    selected = []
    for item in manifest['certificates']:
        pem = certificates[item['name']]
        der = ssl.PEM_cert_to_DER_cert(pem)
        if hashlib.sha256(der).hexdigest() != item['der_sha256']:
            raise ValueError('A selected certificate does not match its pinned DER SHA-256')
        selected.append('# ' + item['name'] + '\n' + pem)
    return HEADER + '\n\n'.join(selected) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Pinned curl/Mozilla bundle from roots-source.json')
    parser.add_argument('--check', action='store_true', help='Verify committed output without writing')
    args = parser.parse_args()
    try:
        result = render(args.source)
        output = DIRECTORY / 'roots.pem'
        if args.check:
            if output.read_text() != result:
                raise ValueError('Committed roots.pem does not match the reproducible subset')
            print('Verified pinned OTA CA subset; no files changed')
        else:
            output.write_text(result)
            print('Wrote pinned OTA CA subset')
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
