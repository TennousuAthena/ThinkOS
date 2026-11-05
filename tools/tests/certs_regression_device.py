#!/usr/bin/env python3
"""Run CA media/public-data regressions serially, preserving private device state and silence."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_app_files import read_app_file,restore_app_file
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS,RECOVERY_PATHS
from media_device import APP,Device
from plugin_push import remove_plugin
from ui_walkthrough import broadcast_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='HUA0BWUT')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--expect',choices=('add','replace'),default='replace')
    parser.add_argument('--checks',nargs='+',choices=('inventory','public-data','radio','player'),default=('inventory','public-data','radio','player'))
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert APP in next(s for s in d.shell('dumpsys','activity','activities').splitlines() if 'mResumedActivity' in s or 'ResumedActivity:' in s)
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve active development overrides'
    assert all(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get')) for i in range(1,6))
    original=broadcast_json(d,'ui_state')
    assert not original.get('onboardingOpen'),'Preserve an in-progress visible onboarding session'
    journal=capture(d,original,PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    extra={p:read_app_file(d.serial,p) for p in ('files/store/welcome.json','files/store/welcome.json.bak')}
    with os.fdopen(os.open(journal/'welcome-extra.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:
        json.dump({p:None if v is None else base64.b64encode(v).decode() for p,v in extra.items()},f)
    q={'complete':False,'mode':args.expect,'serial':args.serial,'checks':args.checks,'private_recovery_journal':str(journal),'steps':[],
       'scope':'Installed CA overlay plus serial native radio/player and public-data regressions; no volume controls or performance measurements'}
    def save():(args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def run(name,command):
        print('START '+name,flush=True)
        with (args.output/(name+'.log')).open('w') as f:
            result=subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,env=dict(os.environ,ADB_MDNS='0',ANDROID_SERIAL=d.serial))
        q['steps'].append({'name':name,'exit_code':result.returncode});save()
        print(('PASS ' if result.returncode==0 else 'FAIL ')+name,flush=True)
        if result.returncode:raise RuntimeError(name+' failed; original log retained')
    save();d.start_log()
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        if 'inventory' in args.checks:
            run('inventory',[sys.executable,'-I','tools/tests/certs_device.py','--serial',d.serial,'--expect',args.expect,'--output',str(args.output/'inventory')])
        if 'public-data' in args.checks:
            run('public-data',[sys.executable,'tools/tests/public_data_device.py','--serial',d.serial,'--output',str(args.output/'public-data')])
        if 'radio' in args.checks:
            d.debug('page',value=original['page']);time.sleep(.5)
            run('radio',[sys.executable,'tools/tests/radio_device.py','--serial',d.serial,'--output',str(args.output/'radio'),'--soak-seconds','260'])
        if 'player' in args.checks:
            d.debug('page',value=original['page']);time.sleep(.5)
            run('player',[sys.executable,'tools/tests/player_device.py','--serial',d.serial])
        errors=[line for line in d.log_since(0) if 'SSLHandshakeException' in line or 'CertPathValidatorException' in line]
        q['tls_errors_in_collected_logs']=errors
        assert not errors,'New certificate errors in regression logs'
        q['complete']=True
    except Exception as error:
        q['error']=repr(error)
        raise
    finally:
        d.debug('media_stop')
        for name in ('public-reader','public-script','public-provider','gallery'):remove_plugin(name,d.serial)
        d.shell('am','force-stop',APP)
        for path,raw in extra.items():restore_app_file(d.serial,path,raw)
        q['welcome_restored']=all(read_app_file(d.serial,p)==v for p,v in extra.items())
        try:
            q['restoration']=restore(journal,d);q.pop('private_recovery_journal',None)
        except Exception as error:q['recovery_error']=repr(error)
        q['audio_zero']={str(i):bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get'))) for i in range(1,6)}
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()
    assert q['complete'] and q['welcome_restored'] and all(q['audio_zero'].values()) and not q.get('recovery_error')


if __name__=='__main__':main()
