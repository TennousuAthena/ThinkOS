#!/usr/bin/env python3
"""Copy a validated plugin directory through run-as and publish it after the last file arrives."""
import argparse
import base64
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import uuid
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_plugins import package_plugin

APP = 'moe.kiwi.thinkos'
ID = re.compile(r'[a-z][a-z0-9-]{0,63}\Z')


def device_shell(serial, command, data=None):
    subprocess.run(['adb', '-s', serial, 'shell', 'run-as', APP, 'sh', '-c', shlex.quote(command)],
                   input=data, check=True, timeout=30)


def remove_plugin(plugin_id, serial='HUA0BWUT'):
    if not ID.fullmatch(plugin_id):
        raise ValueError('invalid plugin id')
    device_shell(serial, 'rm -rf ' + shlex.quote('files/dev-plugins/.previous-' + plugin_id) + ' ' + shlex.quote('files/dev-plugins/' + plugin_id))


def publish_directory(directory, serial='HUA0BWUT'):
    """Directory comes from package_plugin; never interprets file names as shell syntax."""
    directory = Path(directory)
    index = json.loads((directory / 'index.json').read_text())
    plugin_id = index['id']
    if not ID.fullmatch(plugin_id) or directory.name != plugin_id:
        raise ValueError('directory must match the plugin id')
    base = 'files/dev-plugins/'
    stage = base + '.stage-' + plugin_id + '-' + uuid.uuid4().hex
    final = base + plugin_id
    previous = base + '.previous-' + plugin_id
    q = shlex.quote
    # One trusted shell program streams separate base64 files into the directory. There is no
    # archive on either side, and plugin bytes never appear as shell source.
    temporary = stage + '-tmp'
    script = bytearray(('set -e\nmkdir -p ' + q(stage) + ' ' + q(temporary) +
                        '\nexport TMPDIR="$PWD/' + temporary + '"\ntrap ' +
                        q('rm -rf ' + q(stage) + ' ' + q(temporary)) + ' EXIT\n').encode())
    for entry in index['files'] + [{'path': 'index.json'}]:
        relative = entry['path']
        target = stage + '/' + relative
        script.extend(('mkdir -p ' + q(str(Path(target).parent)) + '\nbase64 -d > ' + q(target) +
                       " <<'__THINKOS_FILE__'\n").encode())
        script.extend(base64.encodebytes((directory / relative).read_bytes()))
        script.extend(b'__THINKOS_FILE__\n')
    # Ignore MOVED_FROM in the observer. Already queued scans use .previous as a fallback.
    command = ('rm -rf ' + q(previous) + ' && '
               'if [ -e ' + q(final) + ' ]; then mv ' + q(final) + ' ' + q(previous) + '; fi && '
               'if mv ' + q(stage) + ' ' + q(final) + '; then rm -rf ' + q(previous) + '; '
               'else if [ -e ' + q(previous) + ' ]; then mv ' + q(previous) + ' ' + q(final) + '; fi; exit 1; fi\n')
    script.extend(command.encode())
    subprocess.run(['adb', '-s', serial, 'shell', 'run-as', APP, 'sh'], input=script,
                   check=True, timeout=120)
    return index['digest']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--remove', metavar='ID')
    parser.add_argument('directory', nargs='?', type=Path)
    args = parser.parse_args()
    if args.remove:
        if args.directory:
            parser.error('--remove does not accept a directory')
        remove_plugin(args.remove, args.serial)
        print('Removed development override: ' + args.remove)
    else:
        if not args.directory:
            parser.error('provide a plugin source directory')
        with tempfile.TemporaryDirectory(prefix='thinkos-plugin-') as temporary:
            directory, _ = package_plugin(args.directory, temporary)
            publish_directory(directory, args.serial)
            print('Hot-load queued: ' + directory.name)


if __name__ == '__main__':
    main()
