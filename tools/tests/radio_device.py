#!/usr/bin/env python3
"""APP-3 factory radio: native taps, silent local ICY/M3U/PLS and a real outage.

No public station, Server, volume control or performance sampling. Exact recovery
also covers both copies of the new radio store; each run retains a private journal
until all named files match the baseline again.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/media')]
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,silent_mp3


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-3/device')
    parser.add_argument('--port',type=int,default=8891)
    parser.add_argument('--soak-seconds',type=int,default=300)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    assert args.soak_seconds>=260,'The outage needs enough time to exhaust engine retries and recover'
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert ('tcp:'+str(args.port)).encode() not in d.adb('reverse','--list')
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal)})+'\n')
    print('Private recovery journal: '+str(journal),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'passed':[],'apk_sha256':d.shell('sha256sum',installed).split()[0],'soak':[]}
    station=None;reversed_port=False;d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for radio state or native control')
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def tree(name='last-ui'):
        remote='/sdcard/thinkos-radio.xml'
        try:
            dump_hierarchy(args.serial,remote,compressed=True);raw=d.shell('cat',remote)
            (args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def node(label):
        found=[n for n in visible_nodes(tree()) if label in (n.get('text'),n.get('content-desc'))]
        return max(found,key=lambda n:(n.get('clickable')=='true',n.get('text')==label),default=None)
    def tap(label):
        view=wait(lambda:node(label));a,b,c,e=node_bounds(view);d.tap((a+c)//2,(b+e)//2);time.sleep(.5)
    def page(key):
        d.debug('page',value=key);return broadcast_json(d,'ui_state')['page']==key
    def setting(key,value,scope='radio'):
        d.debug('setting',scope=scope,key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    def media(status=None,title=None):
        state=d.state()
        return state if state and state['owner'].split(':')[0]=='radio' and (status is None or state['status']==status) and (title is None or state['title']==title) else None
    def launch():
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        wait(lambda:page('tiles.apps'));time.sleep(2.5);tap('广播')
        wait(lambda:broadcast_json(d,'ui_state')['page']=='radio.stations')
    def back_to(key):
        d.shell('input','keyevent','KEYCODE_BACK');wait(lambda:broadcast_json(d,'ui_state')['page']==key)
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-radio-device-') as directory:
            station=Station(args.port,silent_mp3(directory));threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:'+str(args.port),'tcp:'+str(args.port));reversed_port=True
            launch()
            for key,value in (('source',''),('stream_url',''),('station_name',''),('reconnect_s',5)):setting(key,value)
            check('The factory radio tile opens a native empty station page',wait(lambda:node('在设置里添加电台')) is not None)
            check('Opening radio does not autoplay',d.state()['status']=='stopped')
            setting('source','http://127.0.0.1:'+str(args.port)+'/list.m3u')
            wait(lambda:node('Local One'));check('M3U loads both local stations',node('Local Two') is not None)
            tap('Local One');wait(lambda:broadcast_json(d,'ui_state')['page']=='radio.now')
            wait(lambda:media('playing','Track Two'));check('The focus page plays a real stream and receives ICY track changes')
            d.screenshot(args.output/'now-playing.png');tree('now-playing')
            tap('收藏');check('A station can be favorited with a native tap',wait(lambda:node('取消收藏')) is not None)
            back_to('radio.stations');check('Back leaves audio playing',media('playing') is not None)
            tap('收藏');check('The favorite tab contains the selected station',wait(lambda:node('Local One')) is not None and node('Local Two') is None)
            back_to('tiles.apps');check('Leaving the application keeps the radio session',media('playing') is not None)
            stage=broadcast_json(d,'stage_state');width,height=stage['width'],stage['height']
            d.shell('input','swipe',width//2,2,width//2,int(height*.65),400);time.sleep(.5)
            tap('暂停');wait(lambda:media('paused'));check('The native control panel pauses radio')
            tap('播放');wait(lambda:media('playing'));check('The native control panel resumes radio')
            d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.4)
            wait(lambda:read_app_file(args.serial,'files/store/radio.json') is not None)
            d.shell('am','force-stop',APP);launch();tap('收藏')
            check('Favorites survive a process restart',wait(lambda:node('Local One')) is not None)
            check('Restoring favorites does not autoplay',d.state()['status']=='stopped')
            tap('Local One');wait(lambda:media('playing'));tap('取消收藏');back_to('radio.stations')
            check('Removing a favorite updates the empty state',wait(lambda:node('暂无收藏')) is not None)
            tap('电台');setting('source','http://127.0.0.1:'+str(args.port)+'/list.pls')
            check('PLS loads stations in numeric order',wait(lambda:node('PLS One')) is not None and node('PLS Two') is not None)
            tap('PLS Two');wait(lambda:media('playing'));check('A PLS station uses the same OS player',any('pls=two' in r['path'] for r in station.seen()))
            back_to('radio.stations')
            setting('media.public_http',False,'system');setting('source','');setting('stream_url','http://198.51.100.1/radio.mp3');setting('station_name','Blocked station')
            tap('Blocked station');wait(lambda:media('error'))
            check('Blocked public HTTP gets the required short notice',wait(lambda:node('已拦截明文 HTTP，可在设置里允许')) is not None)
            check('HTTP policy refusal remains categorized as policy',media()['error']['category']=='policy')
            back_to('radio.stations');setting('stream_url','');setting('source','http://127.0.0.1:'+str(args.port)+'/list.m3u')
            wait(lambda:node('Local Two'))
            station.drop_before=station.stream_connections+1;dropped=station.dropped
            tap('Local Two');wait(lambda:media('playing'))
            wait(lambda:station.dropped>dropped,seconds=25)
            station.outage_until=time.monotonic()+190
            pid=d.shell('pidof',APP).strip();began=time.monotonic();next_note=0
            while time.monotonic()-began<args.soak_seconds:
                state=d.state();elapsed=time.monotonic()-began
                report['soak'].append({'seconds':round(elapsed,2),'status':state['status'],'title':state['title'],'owner':state['owner']})
                assert state['owner'].split(':')[0]=='radio'
                if elapsed>=next_note:
                    print('SOAK '+str(int(elapsed))+'s '+state['status'],flush=True);next_note+=25
                time.sleep(1)
            state=wait(lambda:media('playing'),seconds=20)
            check('A sustained local outage exhausts engine retries and the plugin reconnects',any(x['status']=='error' for x in report['soak']) and any(x['status']=='playing' and x['seconds']>200 for x in report['soak']))
            check('The radio process survives the full functional soak',d.shell('pidof',APP).strip()==pid)
            report['soak_seconds']=time.monotonic()-began;report['stream_requests']=station.seen()
            d.screenshot(args.output/'after-outage.png')
            report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png');tree('failure')
        except Exception:pass
        raise
    finally:
        d.debug('media_stop')
        if station:station.close()
        if reversed_port:d.adb('reverse','--remove','tcp:'+str(args.port))
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_app_journal=str(journal))
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert not report.get('recovery_error') and report['media_volume_zero']


if __name__=='__main__':main()
