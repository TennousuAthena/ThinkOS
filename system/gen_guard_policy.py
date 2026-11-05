#!/usr/bin/env python3
"""Render root guard defaults from the OS settings schema. No device operations."""
import argparse
import json
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def render(health_s=None, poll_s=None):
    fields = json.loads((ROOT / 'shared/schema/settings/system.json').read_text())['fields']
    values = []
    for key, requested in (('update.module_health_s', health_s), ('update.module_guard_poll_s', poll_s)):
        field = fields[key]
        value = field['default'] if requested is None else requested
        if isinstance(value, bool) or not isinstance(value, int) or not field.get('hidden') or \
                value < field['min'] or value > field['max'] or (value - field['min']) % field['step']:
            raise ValueError('Invalid guard policy setting: ' + key)
        values.append(value * 1000)
    return ('thinkos-guard-policy 1\nhealth_ms=%d\npoll_ms=%d\n' % tuple(values)).encode('ascii')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--health-s', type=int)
    parser.add_argument('--poll-s', type=int)
    args = parser.parse_args()
    data = render(args.health_s, args.poll_s)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.guard-policy-', dir=args.output.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, args.output)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    print(args.output)


if __name__ == '__main__':
    main()
