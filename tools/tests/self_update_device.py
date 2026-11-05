#!/usr/bin/env python3
"""OTA-0 actual owner-UID replacement, without UI confirmation or a root installer.

Requires two fresh builds of this worktree (normal version, then next patch).
Explicit --downgrade tests the previous patch on the known userdebug ROM only.
The candidate is intentionally installed. Never use ota_package_fixtures output.
After replacement, verify all retention/relaunch claims BEFORE restoring files.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from ui_walkthrough import broadcast_json


def install_session(dump, session_id):
    """Read only the exact session; another successful install is not evidence."""
    match=re.search(r'^  Session '+str(session_id)+r':.*?(?=^  Session |^\S|\Z)',dump,re.M|re.S)
    if match is None:return None
    compact=re.sub(r'\s+','',match[0])
    def number(key):
        # Whitespace removal joins fields, so word boundaries are not useful.
        value=re.search(key+r'=(-?\d+)',compact)
        return int(value[1]) if value else None
    permissions=re.search(r'mPermissionsManuallyAccepted=(true|false)',compact)
    return {'session_id':session_id,'installer_uid':number('mInstallerUid'),
            'destroyed':'mDestroyed=true' in compact,'legacy_status':number('mFinalStatus'),
            'permissions_manually_accepted':permissions[1]=='true' if permissions else None}


def retained_file(path, before, after):
    if before==after:return True
    if path!='files/store/iptv.json' or before is None or after is None:return False
    # The running TV plugin refreshes its list timestamp and health cache.
    # Preserve exact-byte results separately; never waive preferences/favorites
    # or a changed channel list merely because the app is allowed to refresh.
    try:
        old=json.loads(before);new=json.loads(after)
        if not isinstance(old,dict) or not isinstance(new,dict) or old.keys()!=new.keys():return False
        def value(raw):
            try:return json.loads(raw) if isinstance(raw,str) else raw
            except ValueError:return raw
        for key in old:
            a,b=value(old[key]),value(new[key])
            if key=='health':
                if not isinstance(a,dict) or not isinstance(b,dict):return False
            elif key=='list' and isinstance(a,dict) and isinstance(b,dict):
                if a.keys()!=b.keys() or any(a[k]!=b[k] for k in a if k!='at'):return False
                if 'at' in b and (type(b['at']) is not int or b['at']<0):return False
            elif a!=b:return False
        return True
    except (ValueError,TypeError):return False


def adopted_source_version(content, old, new):
    lines=content.splitlines(keepends=True)
    fields=[line.rstrip('\r\n') for line in lines if line.startswith('thinkos.version=')]
    previous='thinkos.version='+old;following='thinkos.version='+new
    if len(fields)!=1 or fields[0] not in (previous,following):
        raise ValueError('Source version changed independently; preserve it')
    return ''.join(following+line[len(previous):] if line.rstrip('\r\n')==previous else line for line in lines)


def apk_info(path):
    sdk=Path(next(line.split('=',1)[1] for line in (ROOT/'android/local.properties').read_text().splitlines() if line.startswith('sdk.dir=')))
    binary=sdk/'build-tools/35.0.0'
    raw=subprocess.run([str(binary/'aapt'),'dump','badging',str(path)],capture_output=True,text=True,check=True).stdout
    package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",raw)
    assert package and package[1]==APP and "sdkVersion:'27'" in raw and "targetSdkVersion:'27'" in raw
    signed=subprocess.run([str(binary/'apksigner'),'verify','--print-certs',str(path)],capture_output=True,text=True,check=True).stdout
    signers=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})',signed)
    assert len(signers)==1
    return {'code':int(package[2]),'version':package[3],'signer':signers[0],
            'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'size':path.stat().st_size}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--baseline-apk',type=Path,required=True)
    parser.add_argument('--candidate-apk',type=Path,required=True)
    parser.add_argument('--test-apk',type=Path,default=ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--downgrade',action='store_true',help='Explicit OTA-0 hidden-flag experiment; source version is never lowered')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    old=apk_info(args.baseline_apk);new=apk_info(args.candidate_apk)
    assert old['signer']==new['signer'] and new['code']==old['code']+(-1 if args.downgrade else 1) and new['size']<=64*1024*1024
    assert 'fixtures' not in args.candidate_apk.parts, 'Archive-verification fixtures must never be installed'
    d=Device(args.serial);assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert all(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get')) for i in range(1,6))
    assert 'admin=ComponentInfo{'+APP+'/' in d.shell('dumpsys','device_policy')
    installed=d.shell('dumpsys','package',APP)
    assert int(re.search(r'\bversionCode=(\d+)',installed)[1])==old['code'], 'Baseline must match the installed version; never silently downgrade'
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip(), 'Preserve existing development overrides'
    ui=broadcast_json(d,'ui_state');assert ui['safeMode'] is False
    journal=capture(d,ui,PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    token=uuid.uuid4().hex;directory='cache/update-device-'+token
    remote='/data/local/tmp/thinkos-self-update-'+token
    probe='self-update-data-probe';kv='files/store/'+probe+'.json'
    active=None;receipt=None;committed=False;terminal=False
    q={'complete':False,'passed':[],'baseline':old,'candidate':new,'recovery_journal':str(journal),
       'direction':'downgrade' if args.downgrade else 'upgrade',
       'run_id':token,'owned_cache':directory,'owned_upload':remote,
       'confirmation_input_sent':False,'root_or_shell_installer_used_for_candidate':False,'device_rebooted':False,
       'source_version_adopted':False,'performance_measured':False}
    identity={p:read_app_file(d.serial,p) for p in ('files/cast/identity-seed','files/cast/airplay-identity')}
    d.start_log()
    def save(): (args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def check(name,value):
        if not value: raise AssertionError(name)
        q['passed'].append(name);print('PASS '+name,flush=True);save()
    def wait(predicate,seconds=40):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            if predicate():return True
            time.sleep(.2)
        return False
    def current():
        value=d.shell('dumpsys','package',APP)
        return {'code':int(re.search(r'\bversionCode=(\d+)',value)[1]),
                'version':re.search(r'\bversionName=(\S+)',value)[1]}
    def installed_hash():
        path=d.shell('pm','path',APP).strip().removeprefix('package:')
        return d.shell('sha256sum',path).split()[0]
    def home():return d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').strip().splitlines()[-1]
    def read_json(path):
        raw=d.shell('run-as',APP,'cat',path,check=False).strip()
        return json.loads(raw) if raw.startswith('{') else None
    save()
    try:
        check('normal-version probe APK installs before the self-update experiment','Success' in d.adb('install','-r',args.baseline_apk,timeout=300).decode())
        assert installed_hash()==old['sha256']
        check('instrumentation APK installs','Success' in d.adb('install','-r',args.test_apk,timeout=180).decode())
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value=ui['locale']);d.debug('hold_attention',value='ENGAGED')
        before_home=home();check('ThinkOS is the existing preferred HOME',before_home==APP+'/.MainActivity')
        check('Lock Task is locked before replacement','mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
        with tempfile.TemporaryDirectory(prefix='thinkos-update-data-') as temporary:
            source=Path(temporary)/probe;source.mkdir()
            (source/'manifest.json').write_text(json.dumps({'id':probe,'name':'Update data probe','version':'1.0.0','api':1,'main':'main.lua','permissions':[]}))
            (source/'main.lua').write_text('''tos.on("start",function()
 local value=tos.store.get("data")
 if value==nil then value={sentinel="'''+token+'''",favorites={"probe-only"}};tos.store.set("data",value);tos.log.info("SELF_UPDATE_SENTINEL","seeded")
 else assert(value.sentinel=="'''+token+'''" and value.favorites[1]=="probe-only");tos.log.info("SELF_UPDATE_SENTINEL","reused","true") end
end)
''')
            package,_=package_plugin(source,Path(temporary)/'package');publish_directory(package,d.serial)
            check('a sandboxed plugin persists a nonempty KV/favorites fixture through tos.store',wait(lambda:(read_app_file(d.serial,kv) or b'').find(token.encode())>=0))
        before_files={p:read_app_file(d.serial,p) for p in PATHS+OPTIONAL_PATHS+RECOVERY_PATHS+(kv,kv+'.bak')}
        q['before_file_sha256']={p:hashlib.sha256(raw).hexdigest() if raw is not None else None for p,raw in before_files.items()}
        d.shell('mkdir',remote);d.shell('chmod','755',remote)
        d.adb('push',args.candidate_apk,remote+'/candidate.apk',timeout=120);d.shell('chmod','644',remote+'/candidate.apk')
        d.shell('run-as',APP,'mkdir','-p',directory+'/install')
        d.shell('run-as',APP,'cp',remote+'/candidate.apk',directory+'/install/candidate.apk')
        metadata=args.output/'candidate.json';metadata.write_text(json.dumps(new))
        d.adb('push',metadata,remote+'/candidate.json');d.shell('chmod','644',remote+'/candidate.json')
        d.shell('run-as',APP,'cp',remote+'/candidate.json',directory+'/install/candidate.json')
        mark=d.log_mark();log=(args.output/'instrumentation.log').open('w')
        phase='install_downgrade' if args.downgrade else 'install_probe'
        active=subprocess.Popen(d.base+['shell','-T','am','instrument','-w','-e','run_id',token,'-e','phase',phase,APP+'.test/moe.kiwi.thinkos.UpdateInstrumentation'],stdout=log,stderr=subprocess.STDOUT);log.close()
        def request_ready():
            failure=read_json(directory+'/report.json')
            if failure is not None and failure.get('error'):
                q['precommit_failure']=failure['error'][:4096];save()
                raise RuntimeError('Prototype rejected before commit: '+failure['error'][:1000])
            return read_json(directory+'/install/request.json') is not None
        check('owner-UID prototype creates and fsyncs its PackageInstaller session',wait(request_ready,60))
        request=read_json(directory+'/install/request.json');q['request']=request;committed=True;save()
        check('installer caller is the real app UID and Device Owner',request['uid']>=10000 and request['device_owner'] and request['signer']==old['signer'])
        # No taps, HOME or am start occur in this observation window. Do not
        # restart a missing callback: the package-manager transaction may be live.
        since=time.monotonic();observed=since
        while receipt is None:
            receipt=read_json(directory+'/install/result.json')
            if receipt is None:
                failure=read_json(directory+'/report.json')
                if failure is not None and failure.get('error'):
                    raise RuntimeError('Prototype rejected before replacement: '+failure['error'][:1000])
                now=time.monotonic()
                if now-observed>=2:
                    snapshot=install_session(d.shell('dumpsys','package'),request['session_id'])
                    q['platform_session']=snapshot
                    terminal=bool(snapshot and snapshot['destroyed'] and snapshot['legacy_status'] not in (None,0))
                    observed=now
                    if terminal:break
                if now-since>180:
                    q['observation']='callback missing after bounded observation; original session inspected, never retried';save()
                    raise RuntimeError('No application callback; platform session '+json.dumps(snapshot))
                time.sleep(.3)
        q['receipt']=receipt;q['result_source']='application_callback' if receipt else 'platform_terminal_session';save()
        if receipt is not None:
            check('callback belongs to this session and application UID',receipt['session_id']==request['session_id'] and receipt['uid']==request['uid'])
            terminal=receipt['status']>=0
            check('PackageInstaller returns SUCCESS without pending user action',receipt['status']==0 and not receipt['requires_user_action'])
        else:
            # API27 can start the callback process and then kill it again for
            # PACKAGE_REMOVED. Preserve receipt=None; do not forge a callback.
            # The independent installed-byte/process/data checks below still apply.
            check('the original platform session succeeds without manual permission acceptance',snapshot['legacy_status']==1 and snapshot['installer_uid']==request['uid'] and snapshot['permissions_manually_accepted'] is False)
        check('the actual installed APK is exactly the target candidate',current()=={'code':new['code'],'version':new['version']} and installed_hash()==new['sha256'])
        check('replacement kills the old ART process',wait(lambda:str(request['pid']) not in d.shell('pidof',APP,check=False).split()))
        check('real MY_PACKAGE_REPLACED reaches BootReceiver',wait(lambda:any('Startup received android.intent.action.MY_PACKAGE_REPLACED' in line for line in d.log_since(mark))))
        check('ThinkOS returns to foreground automatically, without host assistance',wait(lambda:APP+'/.MainActivity' in d.shell('dumpsys','activity','activities').split('mResumedActivity:')[-1].splitlines()[0]))
        new_pid=int(d.shell('pidof',APP).strip());q['restarted_pid']=new_pid
        check('the replacement creates a new ART process',new_pid!=request['pid'])
        check('the replacement restarts Lua and reads its existing nonempty KV',wait(lambda:any('SELF_UPDATE_SENTINEL reused true' in line and re.search(r'\s'+str(new_pid)+r'\s',line) for line in d.log_since(mark))))
        q['files_retained_before_restore']={p:read_app_file(d.serial,p)==raw for p,raw in before_files.items()}
        q['data_retained_before_restore']={p:retained_file(p,raw,read_app_file(d.serial,p)) for p,raw in before_files.items()}
        check('settings, recovery history, actual player/TV/radio preferences and probe KV survive before restoration',all(q['data_retained_before_restore'].values()))
        check('Device Owner remains ThinkOS','admin=ComponentInfo{'+APP+'/' in d.shell('dumpsys','device_policy'))
        check('preferred HOME survives the self-update',home()==before_home)
        check('Lock Task resumes after replacement',wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')))
        d.shell('input','keyevent','KEYCODE_HOME')
        check('pressing HOME still lands in ThinkOS',wait(lambda:APP+'/.MainActivity' in d.shell('dumpsys','activity','activities').split('mResumedActivity:')[-1].splitlines()[0]))
        d.screenshot(args.output/'after-self-update.png')
        q['complete']=True
    except Exception as error:
        q['failure']=str(error);raise
    finally:
        if committed and not terminal and receipt is None:
            snapshot=install_session(d.shell('dumpsys','package'),q['request']['session_id'])
            q['platform_session']=snapshot
            terminal=bool(snapshot and snapshot['destroyed'] and snapshot['legacy_status'] not in (None,0))
        if committed and not terminal and receipt is None:
            # Do not force-stop the caller or remove bytes while a committed
            # install may still be live. Leave the private journal and owned
            # staging for diagnosis; never create a replacement session here.
            q['cleanup_deferred']='Original committed session is not known terminal; retain journal and staging'
            q['observer_child_pid']=active.pid if active is not None else None
            (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()
        else:
            cleanup(args,old,new,d,q,active,committed,receipt,token,directory,remote,probe,kv,journal,identity,current,installed_hash,save)
    assert all(q['all_volume_streams_zero'].values()) and all(q['identity_files_unchanged'].values())


def cleanup(args,old,new,d,q,active,committed,receipt,token,directory,remote,probe,kv,journal,identity,current,installed_hash,save):
    if active is not None and active.poll() is None:
        d.shell('am','force-stop',APP);active.wait(timeout=20)
    if committed and receipt is not None and receipt['status']==-1:
        d.shell('am','instrument','-w','-e','run_id',token,'-e','phase','install_abandon',APP+'.test/moe.kiwi.thinkos.UpdateInstrumentation')
    # A successful replacement is never downgraded merely to restore a test.
    # Keep the source's normal version consistent with the actual device.
    if new['code']>old['code'] and current()['code']==new['code'] and installed_hash()==new['sha256']:
        props=ROOT/'android/gradle.properties'
        try:
            props.write_text(adopted_source_version(props.read_text(),old['version'],new['version']));q['source_version_adopted']=True
        except ValueError as error:
            # A concurrent source edit must not be overwritten or prevent the
            # already-authorized restoration of device files below.
            q['source_version_adoption_error']=str(error)
    remove_plugin(probe,d.serial);d.shell('am','force-stop',APP)
    d.shell('run-as',APP,'rm','-rf',directory,'files/plugins/'+probe,kv,kv+'.bak')
    d.shell('rm','-rf',remote)
    q['restoration']=restore(journal,d);q.pop('recovery_journal',None)
    q['identity_files_unchanged']={p:read_app_file(d.serial,p)==raw for p,raw in identity.items()}
    q['final_installed']=current();q['final_apk_sha256']=installed_hash()
    q['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
    q['all_volume_streams_zero']={str(i):bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get'))) for i in range(1,6)}
    q['staging_removed']=not d.shell('ls','-d',remote,check=False).strip()
    q['cache_removed']=not d.shell('run-as',APP,'ls','-d',directory,check=False).strip()
    (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()


if __name__=='__main__':main()
