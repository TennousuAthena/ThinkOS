#!/usr/bin/env python3
"""Chinese update result UI, without restarting the app or touching audio.

Requires the completed, test-owned automatic-update journal. History mutations
below are explicit UI fixtures; they do not claim another install or rollback.
The original history is always atomically restored before returning.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_app_files import read_app_file,restore_app_file
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS,RECOVERY_PATHS
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='HUA0BWUT')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--replay-results',type=Path,help='Replay the exact successful update history from the actual device run; requires no existing update directory')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial);assert d.serial=='HUA0BWUT'
    assert read_app_file(d.serial,'files/update/pending.json') is None
    original=broadcast_json(d,'ui_state')
    journal=None
    if args.replay_results:
        assert 'update' not in d.shell('run-as',APP,'ls','files').splitlines()
        source=json.loads(args.replay_results.read_text());assert source['complete'] and source['history']['result']['kind']=='updated'
        current=int(re.search(r'\bversionCode=(\d+)',d.shell('dumpsys','package',APP))[1])
        assert source['history']['result']['code']==current
        journal=capture(d,original,PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
        restore_app_file(d.serial,'files/update/install-history.json',json.dumps(source['history'],ensure_ascii=False).encode())
        d.shell('am','start','-n',APP+'/.MainActivity')
        limit=time.monotonic()+30
        while time.monotonic()<limit and not broadcast_json(d,'update_status').get('result'):time.sleep(.2)
    history=read_app_file(d.serial,'files/update/install-history.json');assert history is not None
    seed=json.loads(history);assert seed['result']['kind']=='updated' and not seed['result']['needs_recovery_clear']
    q={'complete':False,'passed':[],'initial_status':broadcast_json(d,'update_status'),'fixtures':'real completed update history replay when requested; expiry and failure UI fixtures; no new package installation'}
    version=q['initial_status']['current_version'];code=q['initial_status']['current_code'];message='已更新到 '+version
    network_label=json.loads((ROOT/'plugins/panel/i18n/zh-Hans.json').read_text())['network.footer']
    def save(): (args.output/'results.json').write_text(json.dumps(q,ensure_ascii=False,indent=2)+'\n')
    def check(name,condition):
        if not condition:raise AssertionError(name)
        q['passed'].append(name);save();print('PASS '+name,flush=True)
    def wait(predicate,seconds=25):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            value=predicate()
            if value:return value
            time.sleep(.2)
        return None
    def tree():
        remote='/sdcard/thinkos-update-notice.xml'
        try:
            dump_hierarchy(d.serial,remote);raw=d.shell('cat',remote);(args.output/'last-ui.xml').write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def find(label,prefix=False):
        return next((n for n in visible_nodes(tree()) if n.get('text','').startswith(label) if prefix or n.get('text')==label),None)
    def tap(label,prefix=False):
        node=wait(lambda:find(label,prefix));assert node is not None,'Missing '+label
        a,b,c,e=node_bounds(node);d.tap((a+c)//2,(b+e)//2)
    def back():d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.3)
    def home():
        if broadcast_json(d,'update_state').get('showing'):back()
        d.shell('input','keyevent','KEYCODE_HOME')
        wait(lambda:not broadcast_json(d,'stage_state').get('panelOpen'))
    def orient(value):
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',value);time.sleep(.4)
    def settings():
        home();d.debug('settings',value='system');tap('系统')
    def panel():
        home();s=broadcast_json(d,'stage_state');w,h=s['width'],s['height']
        d.shell('input','swipe',w//2,2,w//2,int(h*.65),350)
        assert wait(lambda:broadcast_json(d,'stage_state').get('panelOpen'))
    def replace(value):
        data=json.dumps(value,ensure_ascii=False).encode()
        restore_app_file(d.serial,'files/update/notice-test.json',data)
        d.shell('run-as',APP,'mv','files/update/notice-test.json','files/update/install-history.json')
    save()
    try:
        d.debug('locale',value='zh-CN');d.debug('hold_attention',value='ENGAGED')
        for name,value in [('portrait',1),('landscape',0)]:
            orient(value);settings()
            check(name+' settings show the actual updated version and note preview',wait(lambda:find(message)) is not None)
            d.screenshot(args.output/(name+'-settings.png'))
            tap(message)
            check(name+' native update page keeps full plain notes',wait(lambda:broadcast_json(d,'update_state').get('showing')) and
                  '已完成夜间更新' in (broadcast_json(d,'update_state').get('result') or {}).get('notes',''))
            d.screenshot(args.output/(name+'-native.png'));back()
            panel()
            check(name+' control panel shows update result and keeps network access',find(message,True) is not None and find(network_label) is not None)
            node=find(message,True);a,b,c,e=node_bounds(node)
            screen=broadcast_json(d,'stage_state')
            check(name+' control-panel result stays inside the actual screen',0<=a<c<=screen['width'] and 0<=b<e<=screen['height'])
            d.screenshot(args.output/(name+'-panel.png'))
            tap(message,True)
            check(name+' real control-panel tap opens the native update page',wait(lambda:broadcast_json(d,'update_state').get('showing')))
            back();home()
        expired=copy.deepcopy(seed);expired['result']['notice_ms']=60000;expired['result']['recorded']['elapsed']-=60001
        replace(expired)
        # A full-day result can also be verified through its stored duration;
        # this bounded UI fixture exercises expiration without waiting a day.
        check('expired result is removed by the live journal observer',wait(lambda:'result' not in broadcast_json(d,'update_status')))
        settings();check('expired settings label returns to software update',wait(lambda:find('软件更新')) is not None)
        panel();check('expired control-panel row collapses',find(message,True) is None);home()
        for kind,reason,title,detail in [('rolled_back','crash_loop','更新已回退','启动失败'),('rollback_failed','rollback_failed','回退失败','回退失败')]:
            value=copy.deepcopy(seed);value['result'].update(kind=kind,reason=reason,needs_recovery_clear=False)
            replace(value)
            check(kind+' rendering fixture publishes a bounded Chinese reason',wait(lambda:(broadcast_json(d,'update_status').get('result') or {}).get('reason')==detail))
            settings();tap(title)
            check(kind+' native recovery UI shows the fixture result',wait(lambda:broadcast_json(d,'update_state').get('showing')))
            d.screenshot(args.output/(kind+'.png'));back();home()
        q['complete']=True
    finally:
        restore_app_file(d.serial,'files/update/notice-test.json',history)
        d.shell('run-as',APP,'mv','files/update/notice-test.json','files/update/install-history.json')
        q['original_history_restored']=read_app_file(d.serial,'files/update/install-history.json')==history
        orient(original['orientation']);d.debug('locale',value=original['locale'])
        home()
        for command,key in [('theme','theme'),('hold_attention','attention'),('lux','lux')]:d.debug(command,**({'value':original[key]} if original.get(key) is not None else {}))
        q['audio_zero']={str(i):bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get'))) for i in range(1,6)}
        if journal:
            d.shell('am','force-stop',APP)
            d.shell('run-as',APP,'rm','-rf','files/update')
            q['restoration']=restore(journal,d)
            q['replayed_history_removed']=True
        save()
    assert q['complete'] and q['original_history_restored'] and all(q['audio_zero'].values())


if __name__=='__main__':main()
