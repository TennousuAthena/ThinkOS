#!/usr/bin/env python3
"""Announced, short AMBIENT thread-CPU diagnosis; not a ten-minute acceptance run."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_recovery import capture,restore
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--seconds',type=int,default=60)
    parser.add_argument('--cast-defaults',action='store_true',help='Use schema defaults for receiver switches during this controlled comparison')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert any(APP+'/' in line for line in d.shell('dumpsys','activity','activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'diagnostic_only':True,'apk_sha256':d.shell('sha256sum',installed).split()[0]}
    journal=capture(d,broadcast_json(d,'ui_state'));print('Private recovery journal: '+str(journal),flush=True)
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='AMBIENT')
        d.debug('setting',scope='clock',key='seconds',value='false')
        if args.cast_defaults:
            schema=json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']
            report['cast_switches']={key:schema[key]['default'] for key in ('cast.enabled','cast.airplay','cast.airplay_mirror','cast.miracast')}
            for key,value in report['cast_switches'].items():d.debug('setting',key=key,value=str(value).lower())
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',0)
        until=time.monotonic()+25
        while time.monotonic()<until:
            d.debug('page',value='home.main')
            if broadcast_json(d,'ui_state')['page']=='home.main':break
            time.sleep(.3)
        else:raise AssertionError('Home did not become available')
        time.sleep(5)
        pid=d.shell('pidof',APP).strip()
        report['scene']=broadcast_json(d,'ui_state');d.screenshot(args.output/'screen.png')
        report['plugin_cpu_before']=broadcast_json(d,'plugin_cpu')
        (args.output/'ps-before.txt').write_text(d.shell('ps','-T','-p',pid))
        subprocess.run([sys.executable,str(ROOT/'tools/sample_cpu.py'),'--serial',args.serial,'--pid',pid,'--seconds',str(args.seconds),'--threads','--output',str(args.output/'cpu.json')],check=True)
        report['scene_after']=broadcast_json(d,'ui_state')
        report['plugin_cpu_after']=broadcast_json(d,'plugin_cpu')
        elapsed=json.loads((args.output/'cpu.json').read_text())['elapsed_s']
        report['plugin_cpu_pct']={key:round((value-report['plugin_cpu_before'].get(key,0))/1e9/elapsed*100,3) for key,value in report['plugin_cpu_after'].items()}
        report['same_process']=d.shell('pidof',APP).strip()==pid
        assert report['same_process'] and report['scene_after']['page']=='home.main'
    finally:
        try:report.update(restore(journal,d))
        except Exception as error:report['recovery_error']=str(error);report['private_recovery_journal']=str(journal)
        (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
    assert not report.get('recovery_error'),report.get('recovery_error')


if __name__=='__main__':main()
