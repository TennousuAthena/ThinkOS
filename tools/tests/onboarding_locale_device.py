#!/usr/bin/env python3
"""WEL-0: real API27 system locale/RTL changes, using the shell's existing permission.

No application permission grant, root, module installation or reboot. Saves the
complete system locale list and userSetLocale flag before the first mutation.
Restores those values and the named application files even after a failed assertion.
This proves the ROM method and refresh path, not the ungranted application's UID.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_locale import locale_jar,call_locale
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/welcome/WEL-0/locale')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    jar=locale_jar();remote='/data/local/tmp/'+jar.parent.name+'.jar'
    d.push(str(jar),remote)
    try:original=call_locale(d,remote)
    except Exception:
        d.shell('rm','-f',remote);raise
    if not (original['uid']==2000 and original['canChange']):
        d.shell('rm','-f',remote)
        raise RuntimeError('Shell must already hold CHANGE_CONFIGURATION; this test never grants it')
    unchanged={'timezone':d.shell('getprop','persist.sys.timezone').strip(),
               'font_scale':d.shell('settings','get','system','font_scale').strip()}
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    recovery=Path(tempfile.mkdtemp(prefix='thinkos-welcome-locale-recovery-'))
    system={'physical':'HUA0BWUT','sdk':27,'original':original,'unchanged':unchanged,
            'app_journal':str(journal),'remote_jar':remote}
    with os.fdopen(os.open(recovery/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump(system,stream);stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(recovery)})+'\n')
    print('Private app and locale journals: '+str(journal)+' '+str(recovery),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'caller_uid':2000,'application_permission_granted':False,'original':original,
            'apk_sha256':d.shell('sha256sum',installed).split()[0],'passed':[],'cases':{}}
    d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for system locale or native UI')
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def tree(name):
        path='/sdcard/thinkos-welcome-locale.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True);raw=d.shell('cat',path)
            (args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',path)
    def settings():
        d.debug('page',value='home.main');d.debug('settings',value='system')
        return broadcast_json(d,'ui_state')['page']=='settings.form'
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('hold_attention',value='ENGAGED');d.debug('theme',value='DARK')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',0)
        wait(settings);time.sleep(.8)
        pid=d.shell('pidof',APP).strip()
        for tag,language,direction in (('en-US','en',0),('ar-EG','ar',1),('ur-PK','ur',1)):
            wait(settings)
            # No process-local locale override: this invokes the actual ROM LocalePicker.
            changed=call_locale(d,remote,'set',tag)
            check(tag+' LocalePicker changes the actual global locale',changed['tags']==tag and changed['layoutDirection']==direction and changed['persistedLocale']==tag)
            wait(lambda:broadcast_json(d,'ui_state')['locale']==tag)
            stage=wait(lambda:(s if s.get('layoutDirection')==direction else None) if (s:=broadcast_json(d,'stage_state')) else None)
            check(tag+' native layout direction follows the real system change',stage['layoutDirection']==direction)
            check(tag+' the open settings page is preserved',broadcast_json(d,'ui_state')['page']=='settings.form')
            labels=json.loads((ROOT/'shared/i18n/system'/f'{language}.json').read_text())
            def sidebar():
                root=tree(tag+'-settings');wanted={labels['setting_section_'+name] for name in ('display','location','desktop','media','system','plugins')}
                nodes=[n for n in visible_nodes(root) if n.get('text') in wanted and n.get('clickable')=='true']
                return (root,nodes) if len(nodes)==6 else None
            root,nodes=wait(sidebar)
            check(tag+' all six native settings categories refresh',len(nodes)==6)
            settings_title=json.loads((ROOT/'plugins/settings/i18n'/f'{language}.json').read_text())['name']
            check(tag+' the page accessibility title refreshes',any(n.get('content-desc')==settings_title for n in visible_nodes(root)))
            width=node_bounds(root.find('node'))[2]
            center=sum((node_bounds(n)[0]+node_bounds(n)[2])/2 for n in nodes)/len(nodes)
            check(tag+' sidebar moves to the reading-start side',center>width/2 if direction else center<width/2)
            d.screenshot(args.output/(tag+'-settings.png'))
            d.shell('input','keyevent','KEYCODE_BACK');wait(lambda:broadcast_json(d,'ui_state')['page']=='home.main')
            check(tag+' Back retains the existing navigation stack',broadcast_json(d,'ui_state')['page']=='home.main')
            d.debug('page',value='apps.main');wait(lambda:broadcast_json(d,'ui_state')['page']=='apps.main')
            title=json.loads((ROOT/'plugins/apps/i18n'/f'{language}.json').read_text())['name']
            def apps():
                root=tree(tag+'-apps')
                return root if any(n.get('text')==title for n in visible_nodes(root)) else None
            root=wait(apps);d.screenshot(args.output/(tag+'-apps.png'))
            check(tag+' the factory app page refreshes its translated title',True)
            check(tag+' locale changes preserve the running app process',d.shell('pidof',APP).strip()==pid)
            report['cases'][tag]={'locale':changed,'stage':stage,'settings_categories':[n.get('text') for n in nodes]}
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:
            restored=call_locale(d,remote,'restore',original['tags'],original['userSetLocale'])
            report['system_after']=restored
            report['system_locale_restored']=all(restored[k]==original[k] for k in ('tags','userSetLocale','layoutDirection','persistedLocale'))
            if not report['system_locale_restored']:raise RuntimeError('Locale list or userSetLocale flag differs; journal retained')
        except Exception as error:report.update(system_recovery_error=str(error),private_system_journal=str(recovery))
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_app_journal=str(journal))
        report['unchanged_after']={'timezone':d.shell('getprop','persist.sys.timezone').strip(),
                                  'font_scale':d.shell('settings','get','system','font_scale').strip()}
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not report.get('system_recovery_error') and not report.get('recovery_error'):
            d.shell('rm','-f',remote);shutil.rmtree(recovery)
    assert report.get('system_locale_restored') and not report.get('recovery_error')
    assert report['media_volume_zero'] and report['unchanged_after']==unchanged


if __name__=='__main__':main()
