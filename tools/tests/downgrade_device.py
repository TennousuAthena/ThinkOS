#!/usr/bin/env python3
"""OTA-0 API27 downgrade preflight; restore the normal APK and exact user files.

The owner-UID hidden flag and shell pm -d are separate, explicitly labelled
experiments. Uses fresh local builds only, never archive-verification fixtures.
No reboot, root installer, persistent module, or Device Owner change.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_app_files import read_app_file
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS,RECOVERY_PATHS
from media_device import APP,Device
from self_update_device import apk_info,install_session,retained_file
from ui_walkthrough import broadcast_json


def shell_downgrade_sessions(dump, before_sessions):
    found=[]
    for item in re.finditer(r'^  Session (\d+):.*?(?=^  Session |^\S|\Z)',dump,re.M|re.S):
        number=int(item[1]);compact=re.sub(r'\s+','',item[0]);session=install_session(dump,number)
        flags=re.search(r'installFlags=0x([a-fA-F0-9]+)',compact)
        # pm leaves appPackageName null and estimates size with extracted native
        # libraries. Neither is the verified APK identity. Bind a NEW shell
        # downgrade session to the actual installed version/SHA in the caller.
        declared=re.search(r'appPackageName=(.*?)appIcon=',compact)
        package_allowed=declared is not None and declared[1] in ('null',APP)
        if number not in before_sessions and package_allowed and session['installer_uid']==2000 and flags and int(flags[1],16)&0x80:
            found.append((session,item[0]))
    return found


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--baseline-apk',type=Path,required=True)
    parser.add_argument('--lower-apk',type=Path,required=True)
    parser.add_argument('--test-apk',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--shell-only',action='store_true',help='Retest only the shell observer after an already-qualified owner-UID experiment')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    normal=apk_info(args.baseline_apk);lower=apk_info(args.lower_apk)
    assert normal['signer']==lower['signer'] and normal['code']==lower['code']+1
    assert 'fixtures' not in args.lower_apk.parts
    d=Device(args.serial);assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.debuggable').strip()=='1'
    def quiet():return {str(i):bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get'))) for i in range(1,6)}
    assert all(quiet().values())
    ui=broadcast_json(d,'ui_state');assert ui['safeMode'] is False
    journal=capture(d,ui,PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    identity={p:read_app_file(d.serial,p) for p in ('files/cast/identity-seed','files/cast/airplay-identity')}
    remote='/data/local/tmp/thinkos-downgrade-'+uuid.uuid4().hex
    inner=args.output/'owner-uid';safe=True;before_sessions=set();q={'complete':False,'passed':[],'normal':normal,'lower':lower,
        'recovery_journal':str(journal),'remote':remote,'performance_measured':False,'device_rebooted':False,'candidate_retried':False}
    def save(): (args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def check(name,value):
        if not value:raise AssertionError(name)
        q['passed'].append(name);print('PASS '+name,flush=True);save()
    def wait(predicate,seconds=40):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            if predicate():return True
            time.sleep(.25)
        return False
    def installed():
        raw=d.shell('dumpsys','package',APP);path=d.shell('pm','path',APP).strip().removeprefix('package:')
        return {'code':int(re.search(r'\bversionCode=(\d+)',raw)[1]),'uid':int(re.search(r'\buserId=(\d+)',raw)[1]),'sha256':d.shell('sha256sum',path).split()[0]}
    def foreground():return APP+'/.MainActivity' in d.shell('dumpsys','activity','activities').split('mResumedActivity:')[-1].splitlines()[0]
    def replace_normal():
        result=subprocess.run(d.base+['install','-r',str(args.baseline_apk)],capture_output=True,text=True,timeout=300)
        assert result.returncode==0 and 'Success' in result.stdout
        assert wait(foreground) and installed()['sha256']==normal['sha256']
    save();d.start_log()
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        if not args.shell_only:
            safe=False
            result=subprocess.run([sys.executable,str(ROOT/'tools/tests/self_update_device.py'),'--serial',d.serial,
                '--baseline-apk',str(args.baseline_apk),'--candidate-apk',str(args.lower_apk),'--test-apk',str(args.test_apk),
                '--output',str(inner),'--downgrade'])
            owner=json.loads((inner/'results.json').read_text());safe='cleanup_deferred' not in owner
            check('owner-UID setAllowDowngrade experiment completes',result.returncode==0 and owner['complete'])
            check('the real API27 hidden downgrade flag was set',owner['request'].get('hidden_downgrade_flag') is True and owner['request']['downgrade_requested'] is True)
            check('the owner-UID candidate was not installed by shell/root',owner['root_or_shell_installer_used_for_candidate'] is False and owner['request']['uid']>=10000)
            check('owner-UID downgrade retains settings, favorites and a nonempty plugin KV before restoration',all(owner['data_retained_before_restore'].values()))
        else:q['owner_uid_phase']='not repeated in this shell-only retest'
        replace_normal()
        check('normal version is installed before the shell experiment',installed()['code']==normal['code'])
        before={p:read_app_file(d.serial,p) for p in PATHS+OPTIONAL_PATHS+RECOVERY_PATHS};old_pid=int(d.shell('pidof',APP).strip());uid=installed()['uid']
        d.shell('mkdir',remote);d.shell('chmod','755',remote);d.push(args.lower_apk,remote+'/lower.apk');d.shell('chmod','644',remote+'/lower.apk')
        before_sessions={int(n) for n in re.findall(r'^  Session (\d+):',d.shell('dumpsys','package'),re.M)}
        q['before_shell_sessions']=sorted(before_sessions);q['before_shell_pid']=old_pid
        q['before_shell_file_sha256']={p:hashlib.sha256(b).hexdigest() if b is not None else None for p,b in before.items()};save()
        mark=d.log_mark();safe=False
        # Never use Device.adb's generic retry wrapper for a candidate commit.
        result=subprocess.run(d.base+['shell','-T',shlex.join(['pm','install','-r','-d',remote+'/lower.apk'])],capture_output=True,text=True,timeout=180)
        q['pm_stdout']=result.stdout[:4096];q['pm_stderr']=result.stderr[:4096];q['pm_exit']=result.returncode;save()
        sessions=shell_downgrade_sessions(d.shell('dumpsys','package'),before_sessions)
        safe=bool(sessions and all(s['destroyed'] and s['legacy_status'] not in (None,0) for s,_ in sessions))
        check('pm install -r -d returns success',result.returncode==0 and result.stdout.strip()=='Success')
        check('exactly one new shell session commits the lower APK',len(sessions)==1)
        session,raw=sessions[0];q['shell_session']=session;(args.output/'shell-session.txt').write_text(raw)
        flags=re.search(r'installFlags=0x([a-f0-9]+)',raw);q['shell_install_flags']=int(flags[1],16) if flags else None
        check('platform confirms shell UID, allow-downgrade flag and terminal success',session['installer_uid']==2000 and session['destroyed'] and session['legacy_status']==1 and q['shell_install_flags'] is not None and q['shell_install_flags']&0x80!=0)
        info=installed();q['installed_lower']=info
        check('actual lower-version APK and application UID match',info['code']==lower['code'] and info['sha256']==lower['sha256'] and info['uid']==uid)
        check('shell downgrade returns to ThinkOS automatically',wait(foreground))
        new_pid=int(d.shell('pidof',APP).strip());q['shell_processes']={'old':old_pid,'new':new_pid}
        check('old process is replaced and actual MY_PACKAGE_REPLACED reaches the new one',new_pid!=old_pid and wait(lambda:any('Startup received android.intent.action.MY_PACKAGE_REPLACED' in l and re.search(r'\s'+str(new_pid)+r'\s',l) for l in d.log_since(mark))))
        q['shell_data_retained_before_restore']={p:retained_file(p,b,read_app_file(d.serial,p)) for p,b in before.items()}
        check('shell downgrade preserves settings and plugin preferences before restoration',all(q['shell_data_retained_before_restore'].values()))
        check('Device Owner and Lock Task survive shell downgrade','admin=ComponentInfo{'+APP+'/' in d.shell('dumpsys','device_policy') and 'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
        check('preferred HOME survives shell downgrade',d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').strip().splitlines()[-1]==APP+'/.MainActivity')
        d.shell('input','keyevent','KEYCODE_HOME');check('HOME still returns to ThinkOS',wait(foreground));d.screenshot(args.output/'after-shell-downgrade.png');q['complete']=True
    except BaseException as error:
        q['failure']=str(error);raise
    finally:
        if not safe and before_sessions:
            sessions=shell_downgrade_sessions(d.shell('dumpsys','package'),before_sessions)
            safe=bool(sessions and all(s['destroyed'] and s['legacy_status'] not in (None,0) for s,_ in sessions))
        if safe:
            replace_normal();q['restoration']=restore(journal,d);q.pop('recovery_journal',None)
            d.shell('rm','-rf',remote);q['remote_removed']=not d.shell('ls','-d',remote,check=False).strip()
            q['identities_unchanged']={p:read_app_file(d.serial,p)==b for p,b in identity.items()};q['final_installed']=installed()
            q['all_volume_streams_zero']=quiet();q['source_version_unchanged']='thinkos.version='+normal['version'] in (ROOT/'android/gradle.properties').read_text().splitlines()
        else:q['cleanup_deferred']='Original transaction is not known terminal; retain staging/journal and do not issue another install'
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()
    assert q['remote_removed'] and all(q['identities_unchanged'].values()) and all(q['all_volume_streams_zero'].values()) and q['source_version_unchanged']


if __name__=='__main__':main()
