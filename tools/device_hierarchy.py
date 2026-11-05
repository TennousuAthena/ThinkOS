"""Legacy API-27 UIAutomator snapshot with idle waiting disabled for live displays. No APK dependency."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import zipfile
import re
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/tests/android/ThinkHierarchy.java'


def node_bounds(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))


def visible_nodes(hierarchy):
    """Skip zero-size and off-window nodes that the API-27 dumper still reports."""
    window = hierarchy.find('node')
    viewport = node_bounds(window) if window is not None else ()
    for node in hierarchy.iter('node'):
        b = node_bounds(node)
        if len(b) != 4 or b[2] <= b[0] or b[3] <= b[1]: continue
        if len(viewport) == 4 and not (viewport[0] <= (b[0]+b[2])/2 < viewport[2] and viewport[1] <= (b[1]+b[3])/2 < viewport[3]): continue
        yield node


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


def dump_hierarchy(serial,path,compressed=False,all_windows=False):
    base=['adb','-s',serial];jar=hierarchy_jar();remote='/data/local/tmp/thinkos-hierarchy-'+jar.parent.name.rsplit('-',1)[-1]+'.jar'
    subprocess.run(base+['push',str(jar),remote],capture_output=True,check=True,timeout=25)
    # This API-27 ROM's legacy UiDevice and dumper each prepend local/tmp.
    subprocess.run(base+['shell','mkdir','-p','/data/local/tmp/local/tmp'],capture_output=True,check=True,timeout=25)
    # API 27's dumper can report success without writing anything while a window is
    # being replaced. Use a fresh name for every attempt: an old snapshot must never
    # make a failed dump look like a successful observation of the current screen.
    errors=[]
    for attempt in range(3):
        name='thinkos-hierarchy-'+uuid.uuid4().hex+'.xml'
        source='/data/local/tmp/local/tmp/'+name
        try:
            run=subprocess.run(base+['shell','uiautomator','runtest',remote,'-c','ThinkHierarchy','-e','path',name,'-e','compressed',str(compressed).lower(),'-e','allWindows',str(all_windows).lower()],capture_output=True,timeout=30)
            copied=subprocess.run(base+['shell','cp',source,path],capture_output=True,timeout=25) if run.returncode==0 else None
            if copied is not None and copied.returncode==0:return
            errors.append((run.stdout+run.stderr+(copied.stderr if copied else b'')).decode(errors='replace'))
        finally:
            subprocess.run(base+['shell','rm','-f',source],capture_output=True,timeout=25)
        if attempt<2:time.sleep(.25)
    raise RuntimeError('Unable to snapshot the current window after three attempts:\n'+'\n'.join(errors))
