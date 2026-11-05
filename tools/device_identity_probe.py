"""Compile a temporary API27 reflection probe, without copying production identity code."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def identity_probe_jar():
    source = ROOT / 'tools/tests/android/ThinkCastIdentity.java'
    digest = hashlib.sha256(Path(__file__).read_bytes() + source.read_bytes()).hexdigest()[:16]
    directory = Path(tempfile.gettempdir()) / ('thinkos-cast-identity-probe-' + digest)
    jar = directory / 'probe.jar'
    if jar.exists():
        return jar
    props = dict(line.split('=', 1) for line in (ROOT / 'android/local.properties').read_text().splitlines() if '=' in line)
    sdk = Path(os.environ.get('ANDROID_HOME', props['sdk.dir']))
    platform, build = sdk / 'platforms/android-35/android.jar', sdk / 'build-tools/35.0.0'
    java = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
    classes, dex = directory / 'classes', directory / 'dex'
    classes.mkdir(parents=True, exist_ok=True); dex.mkdir(exist_ok=True)
    subprocess.run([java + '/bin/javac', '--release', '8', '-Xlint:all,-options', '-Werror', '-cp', str(platform),
                    '-d', str(classes), str(source)], capture_output=True, check=True)
    subprocess.run([str(build / 'd8'), '--min-api', '27', '--lib', str(platform), '--output', str(dex),
                    *map(str, classes.glob('*.class'))], capture_output=True, check=True)
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    return jar
