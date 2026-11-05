"""Build a temporary API27 shell fixture for WEL-0; this is not an APK capability or installer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/tests/android/ThinkLocale.java'


def locale_jar():
    digest=hashlib.sha256(SOURCE.read_bytes()).hexdigest()[:12]
    directory=Path(tempfile.gettempdir())/('thinkos-welcome-locale-'+digest);jar=directory/'locale.jar'
    if jar.exists():return jar
    props=dict(line.split('=',1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    sdk=Path(os.environ.get('ANDROID_HOME',props['sdk.dir']));platform=sdk/'platforms/android-35/android.jar'
    java=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
    classes,dex=directory/'classes',directory/'dex';classes.mkdir(parents=True,exist_ok=True);dex.mkdir(exist_ok=True)
    subprocess.run([java+'/bin/javac','-source','8','-target','8','-cp',str(platform),'-d',str(classes),str(SOURCE)],capture_output=True,check=True)
    subprocess.run([str(sdk/'build-tools/35.0.0/d8'),'--min-api','27','--lib',str(platform),'--output',str(dex),str(classes/'ThinkLocale.class')],capture_output=True,check=True)
    with zipfile.ZipFile(jar,'w') as archive:archive.write(dex/'classes.dex','classes.dex')
    return jar


def call_locale(device, remote, action='read', tags=None, user_set=None):
    args=['env','CLASSPATH='+remote,'app_process','/system/bin','ThinkLocale',action]
    if tags is not None:args.append(tags)
    if user_set is not None:args.append(str(user_set).lower())
    raw=device.shell(*args)
    line=next((line for line in raw.splitlines() if line.startswith('THINKOS_LOCALE ')),None)
    if line is None:raise RuntimeError('No locale result from the API27 fixture')
    return json.loads(line.split(' ',1)[1])
