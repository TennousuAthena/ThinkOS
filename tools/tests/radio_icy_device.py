#!/usr/bin/env python3
"""APP-3 prerequisite: ICY titles through the real MediaEngine, silent local source."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/media')]
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,silent_mp3


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-3/icy')
    parser.add_argument('--port',type=int,default=8891)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert ('tcp:'+str(args.port)).encode() not in d.adb('reverse','--list'),'Preserve existing reverse mappings'
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal)})+'\n')
    print('Private recovery journal: '+str(journal),flush=True)
    report={'complete':False,'passed':[],'states':[]}
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report['apk_sha256']=d.shell('sha256sum',installed).split()[0]
    station=None;reversed_port=False;d.start_log()
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-icy-device-') as directory:
            station=Station(args.port,silent_mp3(directory))
            threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:'+str(args.port),'tcp:'+str(args.port));reversed_port=True
            d.shell('am','start','-W','-n',APP+'/.MainActivity')
            d.debug('hold_attention',value='ENGAGED');d.debug('theme',value='DARK')
            d.load('http://127.0.0.1:'+str(args.port)+'/stream.mp3',kind='audio',title='Station title')
            began=time.monotonic();titles=set()
            while time.monotonic()-began<25:
                state=d.state();report['states'].append(state)
                if state:
                    titles.add(state['title'])
                    if state['status']=='error':raise AssertionError('Local ICY stream failed: '+str(state['error']))
                    if {'Track One','Track Two'}<=titles:break
                time.sleep(.3)
            report['titles']=sorted(titles);report['requests']=station.seen()
            assert any(x['metadata']=='1' for x in report['requests']),'Player did not request ICY metadata'
            report['passed'].append('The real player requests ICY metadata')
            assert any(s and s['status']=='playing' and s['audioCodec'] for s in report['states']),'Silent MP3 did not play'
            report['passed'].append('The real engine decodes the local silent MP3')
            assert {'Track One','Track Two'}<=titles,'ICY StreamTitle does not reach media.title: '+repr(sorted(titles))
            report['passed'].append('Changing ICY titles reach the public media.title snapshot')
            d.load('http://127.0.0.1:'+str(args.port)+'/plain.mp3',kind='audio',title='Next station')
            until=time.monotonic()+8
            while time.monotonic()<until:
                state=d.state()
                if state and state['title']=='Next station':break
                time.sleep(.2)
            assert state and state['title']=='Next station','ICY title leaked into another queue item'
            report['passed'].append('Replacing the item clears its old ICY title')
            report['complete']=True
    except Exception as error:
        report['failure']=str(error);raise
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
    print('PASS ICY prerequisites and restoration',flush=True)


if __name__=='__main__':main()
