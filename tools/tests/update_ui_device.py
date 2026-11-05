#!/usr/bin/env python3
"""API10 update UI/authority on the actual device, including real safe-mode boot.

No trust keys, install engine, system module, or nonzero volume. Recovery prefs
are included in the private exact-byte journal before temporary safe-mode setup.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/update/OTA-3/api/ui')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    d = Device(args.serial)
    assert re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get'))
    assert not d.shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing development plugins'
    original = broadcast_json(d, 'ui_state'); assert not original.get('safeMode'), 'Do not overwrite an existing safe-mode session'
    journal = capture(d, original, PATHS + OPTIONAL_PATHS + RECOVERY_PATHS)
    probe = 'update-api-probe'
    report = {'complete': False, 'passed': [], 'recovery_journal': str(journal)}
    identity = {p: read_app_file(d.serial,p) for p in ('files/cast/identity-seed','files/cast/airplay-identity')}
    saved_recovery = {p:read_app_file(d.serial,p) for p in RECOVERY_PATHS}
    d.start_log()

    def check(name, value):
        if not value: raise AssertionError(name)
        report['passed'].append(name); print('PASS '+name, flush=True)

    def wait(predicate, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if predicate(): return True
            time.sleep(.2)
        return False

    def state(): return broadcast_json(d, 'update_state')
    def tree():
        remote = '/sdcard/thinkos-update-ui.xml'
        try:
            dump_hierarchy(d.serial, remote)
            raw = d.shell('cat', remote); (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: d.shell('rm', '-f', remote)
    def find(label):
        for _ in range(10):
            root = tree()
            n = next((n for n in visible_nodes(root) if n.get('text') == label), None)
            if n is not None: return n
            a,b,c,e = node_bounds(root.find('node'))
            d.shell('input','swipe',(a+c)//2,int(e*.85),(a+c)//2,int(e*.3),300)
        raise AssertionError('Missing native update control: '+label)
    def tap(label):
        a,b,c,e = node_bounds(find(label)); d.tap((a+c)//2,(b+e)//2); time.sleep(.35)
    def back(): d.shell('input','keyevent','KEYCODE_BACK'); time.sleep(.3)
    def start():
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN'); d.debug('theme',value='DARK'); d.debug('hold_attention',value='ENGAGED')
    def settings():
        def opened():
            d.debug('page',value='home.main');d.debug('settings',value='system')
            return broadcast_json(d,'ui_state')['page']=='settings.form'
        assert wait(opened)
        tap('系统')

    try:
        start()
        installed=d.shell('pm','path',APP).strip().removeprefix('package:')
        report['apk_sha256']=d.shell('sha256sum',installed).split()[0]
        package_info=d.shell('dumpsys','package',APP)
        installed_version=re.search(r'\bversionName=(\S+)',package_info)[1]
        report['installed_version']=installed_version
        for name, orientation in [('landscape',0),('portrait',1)]:
            d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
            settings(); tap('软件更新')
            check(name+' real settings tap calls the factory-only public update.open API',wait(lambda:state().get('showing')))
            snapshot=state()
            check(name+' unconfigured state is honest and exposes no install action', snapshot['phase']=='unavailable' and snapshot['error']=='keys' and not any(snapshot[k] for k in ('can_check','can_download','can_install')))
            check(name+' Chinese status and current version are visible','更新暂不可用' in [n.get('text') for n in tree().iter('node')] and find('当前版本 '+installed_version) is not None and snapshot['current_version']==installed_version)
            d.screenshot(args.output/(name+'.png'));back()
            check(name+' Back closes native update without losing the settings route',not state().get('showing') and broadcast_json(d,'ui_state')['page']=='settings.form')

        with tempfile.TemporaryDirectory(prefix='thinkos-update-api-') as temporary:
            source=Path(temporary)/probe;source.mkdir()
            manifest={'id':probe,'name':'Update authority probe','version':'1.0.0','api':10,'main':'main.lua','permissions':['system.update'],'pages':[{'id':'main','title':'Update probe','view':'views/main.json'}]}
            (source/'manifest.json').write_text(json.dumps(manifest))
            (source/'views').mkdir();(source/'views/main.json').write_text(json.dumps({'layout':'stage','slots':{'stage':{'type':'text','value':'权限检查','tap':'attempt'}}}))
            (source/'main.lua').write_text('''local updates=0
local function attempt(phase)
 local denied=0
 for _,fn in ipairs{tos.update.status,tos.update.open,tos.update.check,tos.update.download,tos.update.install} do if not pcall(fn) then denied=denied+1 end end
 tos.log.info("UPDATE_DENIED",phase,tostring(denied),"events",tostring(updates))
end
tos.on("start",function() attempt("start") end)
tos.on("tap",function() attempt("tap") end)
tos.on("update",function() updates=updates+1;tos.log.info("UPDATE_LEAK",tostring(updates)) end)
''')
            package,_=package_plugin(source,Path(temporary)/'package')
            mark=d.log_mark();publish_directory(package,d.serial)
            check('a declared system.update permission does not grant a development plugin any update method',wait(lambda:any('UPDATE_DENIED start 5 events 0' in line for line in d.log_since(mark))))
            d.debug('page',value=probe+'.main');tap('权限检查')
            check('a real user tap still cannot turn a development copy into an authorized factory copy',wait(lambda:any('UPDATE_DENIED tap 5 events 0' in line for line in d.log_since(mark))))
            d.debug('setting',key='update.enabled',value='false');time.sleep(.5)
            d.debug('setting',key='update.enabled',value='true');time.sleep(.5)
            check('update events are withheld from the unprivileged copy',not any('UPDATE_LEAK' in line for line in d.log_since(mark)))
            check('refused callers leave the native window closed',not state().get('showing'))
            remove_plugin(probe,d.serial)

        d.debug('plugins_disable_all')
        check('all plugin Lua runtimes really stop',wait(lambda:any(re.search(r'DEBUG plugins_disabled count=\d+ lua=0',line) for line in d.log_since(0))))
        d.debug('maintenance');tap('检查更新')
        check('native rescue entry works without any plugin runtime',wait(lambda:state().get('showing')))
        d.screenshot(args.output/'without-plugins.png');back()

        d.shell('am','force-stop',APP)
        safe=ET.fromstring(saved_recovery[RECOVERY_PATHS[0]] or b'<map/>')
        for n in list(safe):
            if n.get('name')=='safe_mode': safe.remove(n)
        ET.SubElement(safe,'boolean',name='safe_mode',value='true')
        restore_app_file(d.serial,RECOVERY_PATHS[0],ET.tostring(safe,encoding='utf-8',xml_declaration=True))
        restore_app_file(d.serial,RECOVERY_PATHS[1],None)
        start()
        check('the actual restarted application reports real persisted safe mode',wait(lambda:broadcast_json(d,'ui_state').get('safeMode') is True))
        d.debug('plugins_disable_all');d.debug('maintenance');tap('检查更新')
        check('native updates also work in real safe mode with every Lua VM stopped',wait(lambda:state().get('showing')))
        time.sleep(3)  # Let Android's existing lock-task toast leave the screenshot.
        d.screenshot(args.output/'safe-mode.png')
        report['complete']=True
    except Exception as error:
        report['failure']=str(error);d.screenshot(args.output/'failure.png');raise
    finally:
        remove_plugin(probe,d.serial);d.shell('am','force-stop',APP)
        d.shell('run-as',APP,'rm','-rf','files/plugins/'+probe,'files/store/'+probe+'.json','files/store/'+probe+'.json.bak')
        report['restoration']=restore(journal,d);report.pop('recovery_journal',None)
        report['identity_files_unchanged']={p:read_app_file(d.serial,p)==raw for p,raw in identity.items()}
        report['restored_safe_mode']=broadcast_json(d,'ui_state').get('safeMode')
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert all(report['identity_files_unchanged'].values()) and report['media_volume_zero'] and report['restored_safe_mode'] is False


if __name__=='__main__':main()
