#!/usr/bin/env python3
"""Cold-start IPTV with 600 cached local fixture channels; preserve user bytes.

Announce this startup timing check before running. Every channel and source uses
the local fixture station, with background line testing disabled.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from media_device import APP, Device
from iptv_device import Station


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/ui/UI-08/cache-start')
    parser.add_argument('--rounds',type=int,default=10)
    args=parser.parse_args()
    parser.error('--rounds must be between 1 and 100') if not 1 <= args.rounds <= 100 else None
    args.output.mkdir(parents=True,exist_ok=True)
    device=Device(args.serial)
    paths=('files/settings/local.json','files/settings/local.json.bak','files/store/iptv.json','files/store/iptv.json.bak')
    device.shell('am','force-stop',APP)
    saved={p:read_app_file(args.serial,p) for p in paths}
    settings=json.loads(saved[paths[0]] or b'{}')
    settings.setdefault('iptv',{}).update(source_1='http://127.0.0.1:8774/lists/a.m3u',source_2='',source_3='',source_4='',test=False,epg_url='')
    cached={'items':[{'k':'cache'+str(i),'n':'测试频道 '+str(i),'g':'本地测试',
                      'l':[{'u':'http://127.0.0.1:8774/local/'+str(i)+'.ts','s':1}]} for i in range(1,601)]}
    store={'list':json.dumps(cached,ensure_ascii=False,separators=(',',':')),
           'prefs':json.dumps({'view':'all','favorites':{},'recent':[]}), 'health':'{}'}
    report={'rounds':[],'complete':False,'channels':600}
    with tempfile.TemporaryDirectory(prefix='thinkos-iptv-cache-') as temp:
        station=Station(8774,Path(temp),lambda:datetime.now(timezone.utc))
        threading.Thread(target=station.serve_forever,daemon=True).start()
        device.adb('reverse','tcp:8774','tcp:8774')
        device.start_log()
        try:
            for index in range(1,args.rounds+1):
                device.shell('am','force-stop',APP)
                restore_app_file(args.serial,paths[0],json.dumps(settings,ensure_ascii=False).encode())
                restore_app_file(args.serial,paths[1],None)
                restore_app_file(args.serial,paths[2],json.dumps(store,ensure_ascii=False).encode())
                restore_app_file(args.serial,paths[3],None)
                mark=device.log_mark()
                began=time.monotonic()
                device.shell('am','start','-W','-n',APP+'/.MainActivity')
                loaded=False
                while time.monotonic()-began<12:
                    if any('[plugin:iptv] loaded ' in l for l in device.log_since(mark)):
                        loaded=True
                        break
                    time.sleep(.2)
                loaded_seconds=round(time.monotonic()-began,3)
                time.sleep(.5)
                lines=device.log_since(mark)
                (args.output/('round-'+str(index)+'.log')).write_text('\n'.join(lines)+'\n')
                errors=[l for l in lines if '[plugin:iptv]' in l and ('budget exceeded' in l or 'slice cpu=' in l)]
                loaded_lines=[l for l in lines if '[plugin:iptv] loaded ' in l]
                timing=re.search(r'; start cpu=(\d+)us wall=(\d+)us host=(\d+)us calls=(\d+) max=([^:; ]+):(\d+)us compile=(\d+)us',loaded_lines[0]) if loaded_lines else None
                metrics=dict(zip(('cpu_us','wall_us','host_cpu_us','host_calls','slowest_host','slowest_host_us','compile_us'),
                                 [int(x) if i!=4 else x for i,x in enumerate(timing.groups())])) if timing else None
                result={'round':index,'loaded':loaded,'iptv_loaded_seconds':loaded_seconds,'budget_errors':errors,
                        'startup_peak':metrics,'state_timings':[l for l in lines if '[plugin:iptv] state.set chars=' in l]}
                report['rounds'].append(result)
                print(('PASS ' if loaded and not errors else 'FAIL ')+json.dumps(result,ensure_ascii=False),flush=True)
            report['complete']=all(r['loaded'] and not r['budget_errors'] for r in report['rounds'])
        finally:
            device.shell('am','force-stop',APP)
            for path,content in saved.items():restore_app_file(args.serial,path,content)
            report['restored_files']={p:read_app_file(args.serial,p)==v for p,v in saved.items()}
            device.shell('am','start','-W','-n',APP+'/.MainActivity')
            station.shutdown();station.server_close()
            device.adb('reverse','--remove','tcp:8774',check=False)
            device.stop_log()
            (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            assert all(report['restored_files'].values())
    return 0 if report['complete'] else 1


if __name__=='__main__':sys.exit(main())
