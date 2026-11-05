#!/usr/bin/env python3
"""Actual non-debuggable release smoke, using ordinary ADB observations only.

No app-private files, debug broadcasts, instrumentation, root, system changes,
or performance measurements. Both APKs must be fresh same-version builds with
the installed signer. Always return to the supplied debug build for later QA.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools/tests')]
from media_device import APP,Device
from self_update_device import apk_info


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--restore-debug-apk',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    release=apk_info(args.apk);debug=apk_info(args.restore_debug_apk)
    assert release['signer']==debug['signer'] and release['code']==debug['code'] and release['version']==debug['version']
    sdk=Path(next(l.split('=',1)[1] for l in (ROOT/'android/local.properties').read_text().splitlines() if l.startswith('sdk.dir=')))
    aapt=sdk/'build-tools/35.0.0/aapt'
    def manifest(apk):return subprocess.run([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],capture_output=True,text=True,check=True).stdout
    release_manifest=manifest(args.apk);debug_manifest=manifest(args.restore_debug_apk)
    assert 'android:debuggable(0x0101000f)=(type 0x12)0xffffffff' in debug_manifest
    assert 'android:debuggable(0x0101000f)=(type 0x12)0xffffffff' not in release_manifest
    assert all(name not in release_manifest for name in ('.DebugReceiver','.InstallProbeReceiver','.OnboardingPreviewActivity','.NotificationPreviewActivity'))
    assert 'sharedUserId' not in release_manifest
    with zipfile.ZipFile(args.apk) as archive:
        abis={n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')}
        assert abis=={'armeabi-v7a'}
        for name in ('LICENSE','LICENSE-GPL-3.0.md','LICENSE-APACHE-2.0.txt','LICENSE-MPL-2.0.txt','THIRD_PARTY_NOTICES.md'):
            assert archive.read('assets/legal/'+name)==(ROOT/name).read_bytes()
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    def silent():return {str(i):bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get'))) for i in range(1,6)}
    assert all(silent().values())
    def installed():
        raw=d.shell('dumpsys','package',APP)
        return {'uid':int(re.search(r'\buserId=(\d+)',raw)[1]),'code':int(re.search(r'\bversionCode=(\d+)',raw)[1]),
                'version':re.search(r'\bversionName=(\S+)',raw)[1],'debuggable':'DEBUGGABLE' in raw}
    def apk_hash():
        path=d.shell('pm','path',APP).strip().removeprefix('package:')
        return d.shell('sha256sum',path).split()[0]
    def home():return d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').strip().splitlines()[-1]
    def foreground():
        raw=d.shell('dumpsys','activity','activities')
        return APP+'/.MainActivity' in raw.split('mResumedActivity:')[-1].splitlines()[0]
    def wait(predicate,seconds=40):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if predicate():return True
            time.sleep(.25)
        return False
    before=installed();assert before['code']==debug['code'] and before['debuggable']
    assert home()==APP+'/.MainActivity' and foreground()
    q={'complete':False,'passed':[],'release':release,'debug':debug,'before':before,
       'before_apk_sha256':apk_hash(),'performance_measured':False,'device_rebooted':False,
       'signing':'existing local debug certificate; no formal key migration'}
    def save(): (args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def check(name,condition):
        if not condition:raise AssertionError(name)
        q['passed'].append(name);print('PASS '+name,flush=True);save()
    save();d.start_log()
    try:
        check('same-signer release replaces debug without uninstall','Success' in d.adb('install','-r',args.apk,timeout=300).decode())
        info=installed();q['installed_release']=info
        check('actual installed release is non-debuggable API27 armv7',not info['debuggable'] and info['code']==release['code'] and info['version']==release['version'] and apk_hash()==release['sha256'])
        check('release preserves the application UID',info['uid']==before['uid'])
        check('release automatically returns to the foreground after replacement',wait(foreground))
        check('release receives actual MY_PACKAGE_REPLACED',wait(lambda:any('Startup received android.intent.action.MY_PACKAGE_REPLACED' in l for l in d.log_since(0))))
        check('release starts the native runtime in local mode',wait(lambda:any('Runtime started; local mode, safe=false' in l for l in d.log_since(0))))
        check('release loads the factory home and settings plugins',wait(lambda:all(any('[plugin:'+name+'] loaded ' in l for l in d.log_since(0)) for name in ('home','settings'))))
        check('Device Owner is unchanged','admin=ComponentInfo{'+APP+'/' in d.shell('dumpsys','device_policy'))
        check('Lock Task is restored in release',wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')))
        check('preferred HOME is still ThinkOS',home()==APP+'/.MainActivity')
        # API27 dumpsys omits the persistent table. Check the actual policy call
        # completion here; the task's separate read-only platform-store probe
        # checks the saved rule without making release smoke depend on root.
        check('release completes Device Owner persistent HOME registration',wait(lambda:any('Persistent HOME registered' in l for l in d.log_since(0))))
        d.shell('input','keyevent','KEYCODE_HOME');check('HOME key leaves ThinkOS in front',wait(foreground))
        d.screenshot(args.output/'release-after-replacement.png')
        mark=d.log_mark();d.shell('am','force-stop',APP);d.shell('am','start','-W','-n',APP+'/.MainActivity')
        check('release starts again after a real force-stop',wait(foreground))
        check('the new release process initializes its local runtime and Lua',wait(lambda:any('Runtime started; local mode, safe=false' in l for l in d.log_since(mark)) and any('[plugin:settings] loaded ' in l for l in d.log_since(mark))))
        check('release startup has no Java crash',not any('FATAL EXCEPTION' in l or 'Uncaught exception; restarting' in l for l in d.log_since(0)))
        check('release reports no HOME policy failure',not any('Unable to keep ThinkOS as HOME' in l for l in d.log_since(0)))
        d.screenshot(args.output/'release-after-cold-start.png');q['complete']=True
    except BaseException as error:
        q['failure']=str(error);raise
    finally:
        # A same-version forward test never uninstalls or clears device data.
        q['debug_restored']='Success' in d.adb('install','-r',args.restore_debug_apk,timeout=300).decode()
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        q['final_installed']=installed();q['final_apk_sha256']=apk_hash()
        q['all_volume_streams_zero']=silent()
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()
    assert q['debug_restored'] and q['final_installed']['debuggable'] and q['final_installed']['uid']==before['uid']
    assert q['final_apk_sha256']==debug['sha256'] and all(q['all_volume_streams_zero'].values())


if __name__=='__main__':main()
