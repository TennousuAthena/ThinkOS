"""Build a shell-only API27 probe with the production module emitter and test Xposed interfaces."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def back_probe_jar():
    module=ROOT/'system/thinkos-immersive'
    sources=sorted((module/'stubs').rglob('*.java'))+sorted((module/'src').rglob('*.java'))+[ROOT/'tools/tests/android/ThinkBackProbe.java']
    digest=hashlib.sha256(Path(__file__).read_bytes())
    for path in sources:digest.update(str(path.relative_to(ROOT)).encode());digest.update(path.read_bytes())
    directory=Path(tempfile.gettempdir())/('thinkos-back-probe-'+digest.hexdigest()[:16]);jar=directory/'probe.jar'
    if jar.exists():return jar
    props=dict(line.split('=',1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    sdk=Path(os.environ.get('ANDROID_HOME',props['sdk.dir']));platform=sdk/'platforms/android-35/android.jar';build=sdk/'build-tools/35.0.0'
    java=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
    classes,dex=directory/'classes',directory/'dex'
    classes.mkdir(parents=True,exist_ok=True);dex.mkdir(exist_ok=True)
    subprocess.run([java+'/bin/javac','--release','8','-Xlint:all,-options','-Werror','-cp',str(platform),'-d',str(classes),*[str(p) for p in sources]],capture_output=True,check=True)
    subprocess.run([str(build/'d8'),'--min-api','27','--lib',str(platform),'--output',str(dex),*[str(p) for p in sorted(classes.rglob('*.class'))]],capture_output=True,check=True)
    with zipfile.ZipFile(jar,'w') as archive:archive.write(dex/'classes.dex','classes.dex')
    return jar
