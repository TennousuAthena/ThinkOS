#!/usr/bin/env python3
"""Copy installed dependency notices to the local web bundle. No network or execution."""
import hashlib
import json
from pathlib import Path

SIM = Path(__file__).resolve().parents[1]


def main():
    lock = json.loads((SIM / 'package-lock.json').read_text())['packages']
    output = SIM / 'public/licenses'
    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, spec in sorted(lock.items()):
        if not name or not (SIM / name / 'package.json').is_file():
            continue  # npm's optional platform packages not installed on this host
        package = json.loads((SIM / name / 'package.json').read_text())
        if package['version'] != spec['version']:
            raise SystemExit('Installed dependency differs from lock: ' + name)
        files = [p for p in (SIM / name).iterdir() if p.is_file() and p.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE'))]
        if not files and package['name'].startswith('@rolldown/binding-'):
            # Platform binding is distributed by the same Rolldown release and MIT license.
            parent = SIM / 'node_modules/rolldown'
            if json.loads((parent / 'package.json').read_text())['version'] != package['version']:
                raise SystemExit('Rolldown binding release mismatch')
            files = [parent / 'LICENSE']
        if not files:
            raise SystemExit('Dependency has no local notice: ' + name)
        entry = {'name': package['name'], 'version': package['version'], 'license': package.get('license'), 'source': spec.get('resolved'), 'integrity': spec.get('integrity'), 'notices': []}
        for path in sorted(files):
            data = path.read_bytes()
            destination = package['name'].replace('/', '__') + '-' + path.name
            (output / destination).write_bytes(data)
            entry['notices'].append({'file': destination, 'sha256': hashlib.sha256(data).hexdigest()})
        manifest.append(entry)
    for source in sorted((SIM / 'assets/licenses').glob('*')):
        (output / source.name).write_bytes(source.read_bytes())
    (output / 'Kotlin-Apache-2.0.txt').write_bytes((SIM.parent / 'LICENSE-APACHE-2.0.txt').read_bytes())
    (output / 'Kotlin-NOTICE.txt').write_text('Kotlin/JS standard library 2.2.0\nCopyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.\nApache License 2.0. https://github.com/JetBrains/kotlin/tree/v2.2.0\n')
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Collected {len(manifest)} installed dependency notices')


if __name__ == '__main__':
    main()
