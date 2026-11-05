#!/usr/bin/env python3
"""Production app-UID update guard on HUA0BWUT, with two fresh worktree builds.

crash: three deliberate debug Java startup crashes, then one native downgrade.
healthy: a real 600-second first-frame/catalog trial exits persisted safe mode.
No fixture APK installation, reboot, persistent system change, formal key or sound.
An uncertain live transaction is preserved with its private recovery journal.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from self_update_device import apk_info, adopted_source_version, install_session, retained_file
from ui_walkthrough import broadcast_json


def crash_processes(lines):
    # Android logs both the Application wrapper and its cause, then the custom
    # recovery handler logs the same stack again. Count actual PIDs, not lines.
    return {int(line.split()[1]) for line in lines if len(line.split())>2 and
            'java.lang.IllegalStateException: Deliberate debug update-trial startup crash' in line}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--baseline-apk', type=Path, required=True)
    parser.add_argument('--candidate-apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--mode', choices=('crash', 'healthy', 'automatic'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    old, new = apk_info(args.baseline_apk), apk_info(args.candidate_apk)
    assert old['signer'] == new['signer'] and new['code'] == old['code'] + 1
    assert not any('fixtures' in p.parts for p in (args.baseline_apk, args.candidate_apk))
    d = Device(args.serial); assert d.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT'
    assert 'update' not in d.shell('run-as', APP, 'ls', 'files').splitlines(), 'Preserve an existing production update journal'
    assert read_app_file(d.serial, 'cache/update-guard-crash.json') is None
    assert not d.shell('run-as', APP, 'ls', 'files/dev-plugins').strip()
    original = broadcast_json(d, 'ui_state'); assert original['safeMode'] is False
    journal = capture(d, original, PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    token = uuid.uuid4().hex; directory = 'cache/update-device-'+token
    remote = '/data/local/tmp/thinkos-guard-'+token
    q = {'complete': False, 'mode': args.mode, 'physical_serial': 'HUA0BWUT', 'builds': {'from': old, 'to': new},
         'private_recovery_journal': str(journal), 'checks': [], 'pending_samples': [], 'sessions': [],
         'scope': 'Production app-UID installer and main-process guard; no module, power-cut or performance validation'}
    active = None
    wifi = d.shell('settings','get','global','wifi_on').strip()
    q['original_wifi_on']=wifi
    seed_paths = ('files/cast/identity-seed', 'files/cast/airplay-identity')
    seeds = {p: read_app_file(d.serial, p) for p in seed_paths}
    crash_names = d.shell('run-as', APP, 'ls', 'files/crashes', check=False).splitlines()
    crash_names = [p for p in crash_names if re.fullmatch(r'crash-\d+\.log', p)]
    crashes = {p: read_app_file(d.serial, 'files/crashes/'+p) for p in crash_names}
    # Private bytes stay with the recovery journal, never in artifacts.
    import base64
    extra = {p: base64.b64encode(v).decode() for p,v in crashes.items()}
    descriptor = os.open(journal/'guard-crash-files.json', os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream: json.dump(extra, stream)

    def save(): (args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def check(name, value):
        q['checks'].append({'name': name, 'passed': bool(value)}); save()
        print(('PASS ' if value else 'FAIL ')+name, flush=True)
        if not value: raise AssertionError(name)
    def read(name):
        value = read_app_file(d.serial, name)
        return json.loads(value) if value is not None else None
    def installed():
        path = d.shell('pm','path',APP).strip().removeprefix('package:')
        return d.shell('sha256sum',path).split()[0]
    def home():
        return d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').strip().splitlines()[-1]
    def quiet():
        return all(re.search(r'volume is 0\b', d.shell('media','volume','--stream',i,'--get')) for i in range(1,6))
    def sample():
        pending = read('files/update/pending.json')
        if pending is not None:
            if not q['pending_samples'] or pending != q['pending_samples'][-1]:
                q['pending_samples'].append(pending)
                for key in ('session_id','rollback_session_id'):
                    if pending.get(key) is not None and pending[key] not in q['sessions']: q['sessions'].append(pending[key])
                save()
        return pending
    def wait(predicate, seconds=60):
        until = time.monotonic()+seconds
        while time.monotonic()<until:
            result = predicate()
            if result: return result
            time.sleep(.3)
        return None

    save()
    try:
        check('all five Android audio streams remain at zero', quiet())
        package = d.shell('dumpsys','package',APP)
        check('the normal installed version equals the fresh baseline', int(re.search(r'\bversionCode=(\d+)',package)[1]) == old['code'])
        output=d.adb('install','-r',args.baseline_apk,timeout=300).decode(); check('the new baseline guard APK is actually installed', 'Success' in output and installed()==old['sha256'])
        output=d.adb('install','-r',args.test_apk,timeout=180).decode(); check('the production-class instrumentation APK is installed', 'Success' in output)
        d.shell('am','force-stop',APP)
        if args.mode=='healthy':
            restore_app_file(d.serial,'shared_prefs/recovery.xml',b'<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="safe_mode" value="true" /></map>')
            restore_app_file(d.serial,'shared_prefs/recovery.xml.bak',None)
        d.shell('am','start','-n',APP+'/.MainActivity')
        check('baseline safe-mode state is real persisted state', bool(wait(lambda:broadcast_json(d,'ui_state')['safeMode'] == (args.mode=='healthy'))))
        before = {p: read_app_file(d.serial,p) for p in PATHS+OPTIONAL_PATHS+RECOVERY_PATHS}
        q['before_file_sha256']={p: hashlib.sha256(v).hexdigest() if v is not None else None for p,v in before.items()}
        d.shell('mkdir',remote);d.shell('chmod','755',remote)
        d.adb('push',args.candidate_apk,remote+'/candidate.apk',timeout=120);d.shell('chmod','644',remote+'/candidate.apk')
        d.shell('run-as',APP,'mkdir','-p',directory+'/install')
        d.shell('run-as',APP,'cp',remote+'/candidate.apk',directory+'/install/candidate.apk')
        metadata=args.output/'builds.json';metadata.write_text(json.dumps({'from':old,'to':new,'crash':args.mode=='crash'}))
        d.adb('push',metadata,remote+'/builds.json');d.shell('chmod','644',remote+'/builds.json')
        d.shell('run-as',APP,'cp',remote+'/builds.json',directory+'/install/builds.json')
        d.start_log()
        with (args.output/'instrumentation.log').open('w') as log:
            phase='automatic_update' if args.mode=='automatic' else 'install_production'
            active=subprocess.Popen(d.base+['shell','-T','am','instrument','-w','-e','run_id',token,'-e','phase',phase,APP+'.test/moe.kiwi.thinkos.UpdateInstrumentation'],stdout=log,stderr=subprocess.STDOUT)
        def request_ready():
            failed=read(directory+'/report.json')
            if failed and failed.get('error'): raise RuntimeError(failed['error'])
            name='automatic-wait.json' if args.mode=='automatic' else 'request.json'
            return read(directory+'/install/'+name)
        request=wait(request_ready,180); check('real app UID starts the production installer', request is not None and request['uid']>=10000)
        q['request']=request;save()
        if args.mode=='automatic':
            check('all live runtime preflight checks pass before automatic submission is armed',all(item['passed'] for item in request['preflight']['checks']))
            check('cached READY waits for automatic installation with no active default network',request['network_connected'] is False)
            # Snapshot after the deliberate test settings have reached disk;
            # the outer private journal still owns the user's original values.
            time.sleep(1)
            before={p:read_app_file(d.serial,p) for p in PATHS+OPTIONAL_PATHS+RECOVERY_PATHS}
            q['before_file_sha256']={p:hashlib.sha256(v).hexdigest() if v is not None else None for p,v in before.items()}
            since=time.monotonic();premature=False
            while time.monotonic()-since<570:
                if sample() is not None:premature=True;break
                time.sleep(5)
            check('the real default ten-minute touch grace does not submit an early installation',not premature and installed()==old['sha256'])
        check('pending journal is committed before APK installation', wait(sample,90) is not None)
        first=q['pending_samples'][0]
        check('the predecessor identity pins the actual installed APK',first['from']==old and first['to']==new and first['attempts'] in (0,1))
        if args.mode=='automatic':
            check('the production transaction is automatic and starts only after actual 600-second idle',first['mode']=='automatic' and first['created']['elapsed']-request['last_touch_elapsed']>=600000)
        check('previous.apk contains exactly the installed predecessor',d.shell('run-as',APP,'sha256sum','files/update/previous.apk').split()[0]==old['sha256'])
        if args.mode=='crash':
            def rolled_back():
                sample()
                value=read('files/update/install-history.json')
                return value if value and value.get('result',{}).get('kind')=='rolled_back' else None
            history=wait(rolled_back,240);check('three actual startup crashes trigger the production rollback and the old guard completes it', history is not None)
            q['history']=history
            check('the actual installed APK is the exact saved predecessor',installed()==old['sha256'])
            check('rollback cleans the pending journal and predecessor only after committing its result',read_app_file(d.serial,'files/update/pending.json') is None and read_app_file(d.serial,'files/update/previous.apk') is None)
            failed=read('files/update/failure-'+str(new['code'])+'.json');q['failure']=failed
            check('the failed version is durably hard-blacklisted once',failed['entry']['blocked'] and failed['entry']['count']==1 and failed['entry']['code']==new['code'])
            lines=d.log_since(0)
            trials=[line for line in lines if 'Update guard TRIAL attempts=' in line]
            rollbacks=[line for line in lines if 'Update guard ROLLBACK attempts=4' in line]
            actual=crash_processes(lines)
            check('attempts 1, 2 and 3 each fail as real Java startup crashes',len(trials)==3 and all('attempts='+str(i) in trials[i-1] for i in range(1,4)) and
                actual=={int(line.split()[1]) for line in trials} and len(actual)==3)
            check('the fourth process chooses rollback once',len(rollbacks)==1)
            if rollbacks:
                pid=rollbacks[0].split()[1];q['rollback_guard_pid']=int(pid)
                check('the rollback process never starts tzdata, Activity or Lua runtime',not any(line.split()[1]==pid and any(word in line for word in ('tzdata ','Activity created','Lua runtime')) for line in lines if len(line.split())>2))
            # A fast transition may finish before a sampled pending file. The
            # production submission log is a second independent id observation.
            for line in lines:
                m=re.search(r'Update (?:install|rollback) submitted session=(\d+)',line)
                if m and int(m[1]) not in q['sessions']:q['sessions'].append(int(m[1]))
            check('exactly one upgrade and one downgrade session were submitted',len(q['sessions'])==2)
        else:
            def trial():
                value=sample()
                return value if value and value['phase']=='TRIAL' else None
            pending=wait(trial,180);check('replacement starts the new MAIN process on its first trial',pending is not None and pending['attempts']==1)
            check('the actual installed APK is exactly the new candidate',installed()==new['sha256'])
            check('replacement has the expected real safe-mode state',broadcast_json(d,'ui_state')['safeMode'] is (args.mode=='healthy'))
            if args.mode=='automatic':
                check('the new installed version immediately publishes its update and plain notes before health completion',wait(lambda:(broadcast_json(d,'update_status').get('result') or {}).get('version')==new['version']))
                notice=broadcast_json(d,'update_status')['result'];q['trial_notice']=notice
                check('the actual notice has Chinese updated text and no Markdown heading or list markers',notice['message']=='已更新到 '+new['version'] and '# ' not in notice['notes'] and '- ' not in notice['notes'])
            check('actual first frame and catalog start the health interval',wait(lambda:any('Update trial ready; health_ms=' in line for line in d.log_since(0))) and pending['health_ms']==600000)
            started=time.monotonic();q['health_wait_started']=time.time();save()
            premature=False
            while time.monotonic()-started<585:
                if sample() is None:premature=True;break
                time.sleep(5)
            check('health is not declared early during the real ten-minute trial',not premature and read_app_file(d.serial,'files/update/previous.apk') is not None)
            print('Waiting for final health commit and safe-mode restart',flush=True)
            def healthy():
                value=read('files/update/install-history.json')
                return value if value and value.get('result',{}).get('kind')=='updated' and not value['result']['needs_recovery_clear'] else None
            history=wait(healthy,90);check('continuous actual health commits updated and acknowledges safe-mode clearing',history is not None)
            q['history']=history
            check('the committed monotonic trial spans at least 600 seconds',history['result']['recorded']['elapsed']-pending['trial_started']['elapsed']>=600000)
            check('health deletes pending and previous APK',read_app_file(d.serial,'files/update/pending.json') is None and read_app_file(d.serial,'files/update/previous.apk') is None)
            check('repair exits real safe mode automatically',wait(lambda:broadcast_json(d,'ui_state')['safeMode'] is False))
            check('the healthy restart does not start another update trial',len([line for line in d.log_since(0) if 'Update guard TRIAL attempts=' in line])==1)
            version_file=ROOT/'android/gradle.properties'
            version_file.write_text(adopted_source_version(version_file.read_text(),old['version'],new['version']))
            q['source_version_adopted']=new['version']
        raw=d.shell('dumpsys','package');(args.output/'platform-sessions.txt').write_text(raw)
        q['platform_sessions']=[install_session(raw,sid) for sid in q['sessions']]
        check('every original platform session succeeds as app UID without manual permission acceptance',bool(q['sessions']) and all(v and v['destroyed'] and v['legacy_status']==1 and v['installer_uid']==request['uid'] and v['permissions_manually_accepted'] is False for v in q['platform_sessions']))
        check('existing Device Owner remains intact','admin=ComponentInfo{'+APP+'/' in d.shell('dumpsys','device_policy'))
        check('preferred HOME remains ThinkOS',home()==APP+'/.MainActivity')
        check('ThinkOS returns to foreground automatically',wait(lambda:APP+'/.MainActivity' in d.shell('dumpsys','activity','activities').split('mResumedActivity:')[-1].splitlines()[0]))
        check('Lock Task is restored',wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')))
        q['data_retained_before_restore']={p:retained_file(p,v,read_app_file(d.serial,p)) for p,v in before.items() if p not in RECOVERY_PATHS}
        check('original settings, deck, media preferences and favorites survive before restoration',all(q['data_retained_before_restore'].values()))
        check('both original cast identities survive before restoration',all(read_app_file(d.serial,p)==v for p,v in seeds.items()))
        check('all five audio streams remain zero after replacement',quiet())
        d.screenshot(args.output/'after.png')
        q['complete']=True;save()
    except BaseException as error:
        q['error']=repr(error);save();raise
    finally:
        if args.mode=='automatic' and wifi in ('1','2'):d.shell('svc','wifi','enable')
        if d._logcat:
            (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        if active is not None and active.poll() is not None:active.wait(timeout=5)
        if q['complete']:
            d.shell('am','force-stop',APP)
            if active is not None:
                active.wait(timeout=20)
            d.shell('run-as',APP,'rm','-rf','files/update',directory)
            d.shell('run-as',APP,'rm','-f','cache/update-guard-crash.json')
            for name in d.shell('run-as',APP,'ls','files/crashes',check=False).splitlines():
                if re.fullmatch(r'crash-\d+\.log',name):restore_app_file(d.serial,'files/crashes/'+name,None)
            for name,value in crashes.items():restore_app_file(d.serial,'files/crashes/'+name,value)
            d.shell('rm','-rf',remote)
            q['restoration']=restore(journal,d)
            q['cast_identities_unchanged']=all(read_app_file(d.serial,p)==v for p,v in seeds.items())
            q['restored_audio_zero']=quiet();q['restored_safe_mode']=broadcast_json(d,'ui_state')['safeMode'];save()
        else:
            q['cleanup']='Transaction and private journal retained; reconcile the exact package/session before any retry or deletion';save()


if __name__=='__main__':main()
