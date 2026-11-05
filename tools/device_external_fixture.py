"""Build the separate-UID API27 external-app fixture with local SDK tools only."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/tests/android/external-fixture'
PACKAGE='moe.kiwi.thinkos.externalfixture'
OVERLAY_PACKAGE='moe.kiwi.thinkos.overlayfixture'
DENIED_PACKAGE='moe.kiwi.thinkos.deniedfixture'


def fixture_apk(overlay=False,denied=False):
    assert not (overlay and denied)
    source=ROOT/'tools/tests/android'/('overlay-fixture' if overlay else 'denied-fixture') if overlay or denied else SOURCE
    digest=hashlib.sha256(Path(__file__).read_bytes())
    for path in sorted(source.iterdir()):digest.update(path.name.encode());digest.update(path.read_bytes())
    directory=Path(tempfile.gettempdir())/(('thinkos-overlay-fixture-' if overlay else 'thinkos-denied-fixture-' if denied else 'thinkos-external-fixture-')+digest.hexdigest()[:12])
    apk=directory/'fixture.apk'
    if apk.exists():return apk
    props=dict(line.split('=',1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    sdk=Path(os.environ.get('ANDROID_HOME',props['sdk.dir']));platform=sdk/'platforms/android-35/android.jar';build=sdk/'build-tools/35.0.0'
    java=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
    classes,dex,assets=directory/'classes',directory/'dex',directory/'assets'
    for path in (classes,dex,assets):path.mkdir(parents=True,exist_ok=True)
    if not overlay and not denied:
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=blue:s=320x180:r=10','-t','6','-an','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-movflags','+faststart','-y',str(assets/'silent.mp4')],check=True,capture_output=True,timeout=30)
    subprocess.run([java+'/bin/javac','-source','8','-target','8','-cp',str(platform),'-d',str(classes),*[str(p) for p in sorted(source.glob('*.java'))]],check=True,capture_output=True)
    subprocess.run([str(build/'d8'),'--min-api','27','--lib',str(platform),'--output',str(dex),*[str(p) for p in sorted(classes.rglob('*.class'))]],check=True,capture_output=True)
    base=directory/'resources.apk'
    subprocess.run([str(build/'aapt2'),'link','--manifest',str(source/'AndroidManifest.xml'),'-I',str(platform),'-A',str(assets),'-0','mp4','--min-sdk-version','27','--target-sdk-version','27','-o',str(base)],check=True,capture_output=True)
    unsigned=directory/'unsigned.apk'
    with zipfile.ZipFile(base) as source,zipfile.ZipFile(unsigned,'w') as target:
        for info in source.infolist():target.writestr(info,source.read(info.filename))
        target.write(dex/'classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED)
    aligned=directory/'aligned.apk'
    subprocess.run([str(build/'zipalign'),'-f','4',str(unsigned),str(aligned)],check=True,capture_output=True)
    # Public, existing Android debug key. This is not a production certificate.
    subprocess.run([str(build/'apksigner'),'sign','--ks',str(Path.home()/'.android/debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--out',str(apk),str(aligned)],check=True,capture_output=True,env=os.environ|{'JAVA_HOME':java})
    return apk
