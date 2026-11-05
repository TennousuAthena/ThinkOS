#!/usr/bin/env python3
"""APP-3: native radio pages in eleven process-local languages and both orientations."""
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
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,silent_mp3

LOCALES=('zh-Hans','zh-Hant','en','es','fr','pt','ru','ar','hi','bn','ur')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-3/ui')
    parser.add_argument('--port',type=int,default=8891)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    def system():return {k:d.shell('getprop',k).strip() for k in ('persist.sys.locale','persist.sys.timezone')} | {'font_scale':d.shell('settings','get','system','font_scale').strip()}
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert ('tcp:'+str(args.port)).encode() not in d.adb('reverse','--list')
    before=system();journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal)})+'\n')
    print('Private recovery journal: '+str(journal),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'screens':{},'apk_sha256':d.shell('sha256sum',installed).split()[0],'system_before':before}
    station=None;reversed_port=False;d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Radio UI did not become visible')
    def tree(name):
        remote='/sdcard/thinkos-radio-ui.xml'
        try:
            dump_hierarchy(args.serial,remote,compressed=True);raw=d.shell('cat',remote)
            (args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def page(key):d.debug('page',value=key);return broadcast_json(d,'ui_state')['page']==key
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-radio-ui-') as work:
            station=Station(args.port,silent_mp3(work));threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:'+str(args.port),'tcp:'+str(args.port));reversed_port=True
            d.shell('am','start','-W','-n',APP+'/.MainActivity')
            d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
            wait(lambda:page('radio.stations'))
            for key,value in (('stream_url',''),('source','http://127.0.0.1:'+str(args.port)+'/list.m3u')):
                d.debug('setting',scope='radio',key=key,value=value)
            def first():return next((n for n in visible_nodes(tree('initial')) if n.get('text')=='Local One'),None)
            item=wait(first);a,b,c,e=node_bounds(item);d.tap((a+c)//2,(b+e)//2)
            wait(lambda:(s if s and s['status']=='playing' else None) if (s:=d.state()) else None)
            for language in LOCALES:
                locale={'zh-Hans':'zh-CN','zh-Hant':'zh-TW'}.get(language,language)
                labels=json.loads((ROOT/'plugins/radio/i18n'/f'{language}.json').read_text())
                for orientation_name,orientation in (('portrait',1),('landscape',0)):
                    d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
                    time.sleep(2.5);d.debug('locale',value=locale)
                    wait(lambda:broadcast_json(d,'ui_state')['locale']==locale)
                    for name in ('stations','now'):
                        wait(lambda:page('radio.'+name));time.sleep(.6)
                        key=language+'-'+orientation_name+'-'+name
                        root=tree(key);stage=broadcast_json(d,'stage_state')
                        assert stage['layoutDirection']==(1 if language in ('ar','ur') else 0)
                        assert (stage['width']<stage['height'])==(orientation_name=='portrait')
                        values={n.get('text') or n.get('content-desc') for n in visible_nodes(root)}
                        wanted={labels['tab.all'],labels['tab.favorites']} if name=='stations' else {labels['action.favorite']}
                        assert wanted<=values,(key,wanted-values)
                        assert broadcast_json(d,'ui_state')['locale']==locale
                        d.screenshot(args.output/(key+'.png'))
                        report['screens'][key]={'size':[stage['width'],stage['height']],'layout_direction':stage['layoutDirection'],'labels':sorted(wanted)}
                        print('PASS '+key+' native labels and reading direction',flush=True)
            wait(lambda:page('tiles.apps'));time.sleep(.7);d.screenshot(args.output/'applications.png')
            report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        d.debug('media_stop')
        if station:station.close()
        if reversed_port:d.adb('reverse','--remove','tcp:'+str(args.port))
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_app_journal=str(journal))
        report['system_after']=system()
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert report['system_after']==before and report['media_volume_zero'] and not report.get('recovery_error')


if __name__=='__main__':main()
