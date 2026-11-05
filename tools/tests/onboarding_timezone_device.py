#!/usr/bin/env python3
"""WEL-0 cross-offset system timezone validation on this API27 ROM, then exact restoration.

Uses only the task's fixed shell-root IAlarmManager transaction (alarm 3 s16).
No app su, module installation, partition changes, reboot or volume changes.
The Lua observer uses public tos.* APIs; the visible page is the factory clock.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from package_plugins import package_plugin
from plugin_push import publish_directory,remove_plugin
from ui_walkthrough import broadcast_json

PROBE='onboarding-timezone-probe'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/welcome/WEL-0/timezone')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve existing development plugins'
    assert re.search(r'\buid=0\b',d.shell('su','-c','id')),'This specific WEL-0 probe requires the already-authorized shell root'
    original=d.shell('getprop','persist.sys.timezone').strip();assert re.fullmatch(r'[A-Za-z0-9_+/-]{1,64}',original)
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    recovery=Path(tempfile.mkdtemp(prefix='thinkos-welcome-system-recovery-'))
    system_state={'physical':'HUA0BWUT','sdk':27,'original_timezone':original,'app_journal':str(journal)}
    with os.fdopen(os.open(recovery/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump(system_state,stream);stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(recovery)})+'\n')
    print('Private app and system recovery journals: '+str(journal)+' '+str(recovery),flush=True)
    report={'complete':False,'passed':[],'original_timezone':original,'cases':{}}
    d.start_log();published=False
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for timezone-driven UI')
    def timezone(zone):
        assert re.fullmatch(r'[A-Za-z0-9_+/-]{1,64}',zone)
        # API27 only. The fixed transaction is specified by docs/35 §4.6 and docs/36 WEL-0.
        d.shell('su','-c','/system/bin/service call alarm 3 s16 '+zone)
        wait(lambda:d.shell('getprop','persist.sys.timezone').strip()==zone,seconds=8)
    def setting(key,value,scope='system'):
        d.debug('setting',scope=scope,key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def payload(mark,zone):
        for line in reversed(d.log_since(mark)):
            if 'WEL_TIMEZONE ' not in line:continue
            value=json.loads(line.split('WEL_TIMEZONE ',1)[1])
            if value.get('zone')==zone and value.get('sun_zone')==zone:return value
        return None
    def tree(name):
        path='/sdcard/thinkos-welcome-zone.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True);raw=d.shell('cat',path)
            (args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',path)
    def capture_case(zone,mark,changed):
        value=wait(lambda:payload(mark,zone));local=datetime.fromtimestamp(value['epoch']/1000,ZoneInfo(zone))
        check(zone+' public clock/date and observing zone follow the system',value['clock']==local.strftime('%H%M%S') and value['date']==local.strftime('%Y%m%d'))
        start=datetime.fromtimestamp(value['sun_start']/1000,ZoneInfo(zone))
        check(zone+' solar civil day follows the new local date',start.date()==local.date() and start.hour==0 and start.minute==0 and value['sun_end']>value['sun_start'])
        if changed:check(zone+' refresh comes from the real system broadcast',value['trigger']=='locale')
        assert broadcast_json(d,'ui_state')['page']=='clock.face','Timezone change navigated away from the factory clock'
        label=f'{local.year}年{local.month}月{local.day}日'
        def actual_clock():
            root=tree(zone.replace('/','-')+'-digital')
            nodes=list(visible_nodes(root))
            return nodes if any(label in n.get('text','') for n in nodes) and any(n.get('content-desc')==value['clock_ui'] for n in nodes) else None
        nodes=wait(actual_clock,seconds=8)
        check(zone+' the visible factory digital clock and date update without restart',bool(nodes))
        d.screenshot(args.output/(zone.replace('/','-')+'-digital.png'))
        d.debug('variant',value='solar');time.sleep(.5)
        d.screenshot(args.output/(zone.replace('/','-')+'-solar.png'))
        d.debug('variant',value='digital');time.sleep(.3)
        report['cases'][zone]=value
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        wait(lambda:bool(broadcast_json(d,'ui_state')['page']))
        wanted={'location.mode':'manual','location.latitude':22.3,'location.longitude':114.2,'location.elevation':0,'location.timezone':''}
        for key,value in wanted.items():setting(key,value)
        defaults=json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']
        def saved():
            values=json.loads(read_app_file(args.serial,'files/settings/local.json'))['system']
            return all(values.get(key,defaults[key]['default'])==value for key,value in wanted.items())
        wait(saved);setting('seconds',False,'clock')
        with tempfile.TemporaryDirectory(prefix='thinkos-timezone-probe-') as work:
            bundle,_=package_plugin(ROOT/'tools/tests/fixtures/onboarding-timezone-probe',Path(work));publish_directory(bundle,args.serial);published=True
        wait(lambda:d.debug('page',value='clock.face') and broadcast_json(d,'ui_state')['page']=='clock.face')
        d.debug('variant',value='digital');time.sleep(.6)
        pid=d.shell('pidof',APP).strip();capture_case(original,0,False)
        # UTC+14 and UTC-11 are 25 hours apart, so the date check is deterministic at any test time.
        for zone in ('Europe/London','Pacific/Kiritimati','Pacific/Pago_Pago',original):
            mark=d.log_mark();timezone(zone);capture_case(zone,mark,True)
            check(zone+' updates without replacing the app process',d.shell('pidof',APP).strip()==pid)
        report['crosses_local_date']=len({value['date'] for value in report['cases'].values()})>1
        check('the offset cases include a changed calendar date',report['crosses_local_date'])
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:
            d.shell('am','force-stop',APP)
            if published:remove_plugin(PROBE,args.serial)
            d.shell('am','force-stop',APP)
        except Exception as error:report['fixture_cleanup_error']=str(error)
        try:timezone(original);report['system_timezone_restored']=True
        except Exception as error:report.update(system_recovery_error=str(error),private_system_journal=str(recovery))
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_app_journal=str(journal))
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not any(report.get(key) for key in ('fixture_cleanup_error','system_recovery_error','recovery_error')):shutil.rmtree(recovery)
    assert report.get('system_timezone_restored') and not report.get('recovery_error') and not report.get('fixture_cleanup_error')
    assert report['media_volume_zero']


if __name__=='__main__':main()
