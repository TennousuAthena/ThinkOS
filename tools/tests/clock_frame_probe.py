#!/usr/bin/env python3
"""Short clock framestats diagnosis, never a ten-minute acceptance result.

Announce before running. Uses each named face explicitly, keeps volume zero, and
restores app settings/navigation exactly. No system/animation settings are changed.
"""
import argparse
import json
import os
from pathlib import Path
import re
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
    parser.add_argument('--seconds',type=int,default=30)
    parser.add_argument('--face',action='append',choices=('analog','digital'))
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    device=Device(args.serial)
    assert any(APP+'/' in line for line in device.shell('dumpsys','activity','activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b',device.shell('media','volume','--stream',3,'--get'))
    journal=capture(device,broadcast_json(device,'ui_state'))
    print('Private recovery journal: '+str(journal),flush=True)
    report={'diagnostic_only':True,'faces':{}}

    def parse(raw,frames):
        active=False;columns=None
        since=int(re.search(r'Stats since: (\d+)ns',raw)[1])
        for line in raw.splitlines():
            if line=='---PROFILEDATA---': active=not active;columns=None;continue
            if not active:continue
            cells=line.rstrip(',').split(',')
            if cells[0]=='Flags':columns={name:i for i,name in enumerate(cells)};continue
            if not columns or len(cells)<len(columns):continue
            values={name:int(cells[index]) for name,index in columns.items()}
            start=values['IntendedVsync'];end=values['FrameCompleted']
            if start<since or end>=9223372036854775807 or end<start:continue
            phases=('IntendedVsync','HandleInputStart','AnimationStart','PerformTraversalsStart','DrawStart','SyncQueued','SyncStart','IssueDrawCommandsStart','SwapBuffers','FrameCompleted')
            durations={phases[i]+'..'+phases[i+1]:(values[phases[i+1]]-values[phases[i]])/1e6 for i in range(len(phases)-1)}
            frames[start]={'flags':values['Flags'],'total_ms':(end-start)/1e6,'phases_ms':durations}
    try:
        device.shell('am','start','-W','-n',APP+'/.MainActivity')
        device.debug('locale',value='zh-CN');device.debug('theme',value='DARK');device.debug('hold_attention',value='AWAKE')
        device.debug('setting',scope='clock',key='seconds',value='true')
        device.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',0)
        until=time.monotonic()+25
        while time.monotonic()<until:
            device.debug('page',value='clock.face')
            if broadcast_json(device,'ui_state')['page']=='clock.face':break
            time.sleep(.3)
        else:raise AssertionError('Clock did not become available')
        for face in args.face or ('analog','digital'):
            device.debug('variant',value=face);time.sleep(4)
            assert broadcast_json(device,'ui_state')['variant']==face
            folder=args.output/face;folder.mkdir();device.screenshot(folder/'screen.png')
            device.shell('dumpsys','gfxinfo',APP,'reset')
            frames={};start=time.monotonic();count=0
            while time.monotonic()-start<args.seconds:
                raw=device.shell('dumpsys','gfxinfo',APP,'framestats')
                (folder/f'frames-{count:04d}.txt').write_text(raw);parse(raw,frames);count+=1;time.sleep(1)
            raw=device.shell('dumpsys','gfxinfo',APP,'framestats');(folder/'gfx-after.txt').write_text(raw);parse(raw,frames)
            rows=list(frames.values());(folder/'frames.json').write_text(json.dumps(rows,indent=2)+'\n')
            total=int(re.search(r'Total frames rendered: (\d+)',raw)[1]);jank=int(re.search(r'Janky frames: (\d+)',raw)[1])
            def p95(values):return sorted(values)[int((len(values)-1)*.95)] if values else None
            phases={key:p95([row['phases_ms'][key] for row in rows]) for key in rows[0]['phases_ms']} if rows else {}
            result={'seconds':time.monotonic()-start,'frames':total,'janky':jank,'janky_pct':100*jank/max(total,1),'sampled_frames':len(rows),'total_p95_ms':p95([r['total_ms'] for r in rows]),'phase_p95_ms':phases}
            report['faces'][face]=result;print(face+': '+json.dumps(result),flush=True)
    except Exception as error:
        report['failure']=str(error);raise
    finally:
        try:report.update(restore(journal,device))
        except Exception as error:report['recovery_error']=str(error);report['private_recovery_journal']=str(journal)
        (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
    assert not report.get('recovery_error'),report.get('recovery_error')


if __name__=='__main__':main()
