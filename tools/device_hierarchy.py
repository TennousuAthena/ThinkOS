"""Legacy API-27 UIAutomator snapshot with idle waiting disabled for live displays. No APK dependency."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/tests/android/ThinkHierarchy.java'


def hierarchy_jar():
    digest=hashlib.sha256(SOURCE.read_bytes()).hexdigest()[:12]
    directory=Path(tempfile.gettempdir())/('thinkos-hierarchy-'+digest);jar=directory/'hierarchy.jar'
    if jar.exists():return jar
    props=dict(line.split('=',1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    sdk=Path(os.environ.get('ANDROID_HOME',props['sdk.dir']));platform=sdk/'platforms/android-35'
    junit=next((Path.home()/'.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2').glob('*/*.jar'))
    directory.mkdir(exist_ok=True);classes=directory/'classes';classes.mkdir(exist_ok=True);dex=directory/'dex';dex.mkdir(exist_ok=True)
    subprocess.run(['javac','-source','8','-target','8','-cp',os.pathsep.join(map(str,[platform/'android.jar',platform/'uiautomator.jar',junit])),'-d',str(classes),str(SOURCE)],capture_output=True,check=True)
    subprocess.run([str(sdk/'build-tools/35.0.0/d8'),'--min-api','27','--lib',str(platform/'android.jar'),'--output',str(dex),str(classes/'ThinkHierarchy.class')],capture_output=True,check=True)
    with zipfile.ZipFile(jar,'w') as z:z.write(dex/'classes.dex','classes.dex')
    return jar


def dump_hierarchy(serial,path):
    base=['adb','-s',serial];jar=hierarchy_jar();remote='/data/local/tmp/thinkos-hierarchy-'+jar.parent.name.rsplit('-',1)[-1]+'.jar'
    subprocess.run(base+['push',str(jar),remote],capture_output=True,check=True,timeout=25)
    # This API-27 ROM's legacy UiDevice and dumper each prepend local/tmp.
    subprocess.run(base+['shell','mkdir','-p','/data/local/tmp/local/tmp'],capture_output=True,check=True,timeout=25)
    run=subprocess.run(base+['shell','uiautomator','runtest',remote,'-c','ThinkHierarchy','-e','path',Path(path).name],capture_output=True,timeout=30)
    if run.returncode:raise RuntimeError(run.stdout.decode()+run.stderr.decode())
    subprocess.run(base+['shell','cp','/data/local/tmp/local/tmp/'+Path(path).name,path],capture_output=True,check=True,timeout=25)
    subprocess.run(base+['shell','rm','-f','/data/local/tmp/local/tmp/'+Path(path).name],capture_output=True,check=True,timeout=25)
