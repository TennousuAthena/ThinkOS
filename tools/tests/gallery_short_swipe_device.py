#!/usr/bin/env python3
"""Replay 8–30ms touch streams. Build tests with: ./gradlew -PgalleryGestureProbe=true assembleDebugAndroidTest."""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory,remove_plugin
APP='moe.kiwi.thinkos';PROBE='gallery-history-probe';TEST=APP+'.test'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='HUA0BWUT')
    parser.add_argument('--baseline-apk',type=Path)
    parser.add_argument('--record',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/gallery/short-swipe')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    base=['adb','-s',args.serial];published=False
    def adb(*a,check=True):
        result=subprocess.run(base+list(map(str,a)),capture_output=True,timeout=60)
        if check and result.returncode:raise RuntimeError(result.stdout.decode(errors='replace')+result.stderr.decode(errors='replace'))
        return result.stdout
    def shell(*a,check=True):return adb('shell','-T',shlex.join(list(map(str,a))),check=check)
    def png(color):
        def chunk(k,data):return struct.pack('>I',len(data))+k+data+struct.pack('>I',zlib.crc32(k+data)&0xffffffff)
        pixels=b''.join(b'\0'+bytes((245,245,245) if y%10==0 else color)*64 for y in range(40))
        return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,40,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')
    scale=shell('settings','get','global','animator_duration_scale').strip()
    had_test=bool(shell('pm','path',TEST,check=False).strip())
    assert not shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve existing development plugins'
    try:
        shell('settings','put','global','animator_duration_scale',1)
        adb('install','-r','-t',ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        assert 'GallerySwipeInstrumentation' in shell('pm','list','instrumentation').decode(), 'Build with -PgalleryGestureProbe=true assembleDebugAndroidTest'
        with tempfile.TemporaryDirectory(prefix='thinkos-short-swipe-') as temp:
            source=Path(temp)/PROBE;shutil.copytree(ROOT/'tools/tests/fixtures'/PROBE,source)
            (source/'assets').mkdir()
            for name,color in [('A',(180,45,45)),('B',(45,180,45)),('C',(45,45,180))]:(source/'assets'/(name+'.png')).write_bytes(png(color))
            m=json.loads((source/'manifest.json').read_text());m['permissions']=[]
            (source/'manifest.json').write_text(json.dumps(m))
            (source/'main.lua').write_text('''tos.on("start",function()
  local photos=tos.json.decode("[]")
  for _,id in ipairs({"A","B","C"}) do photos[#photos+1]={id=id,src="asset:assets/"..id..".png",title=id} end
  tos.state.set{photos=photos}
end)
''')
            packed,_=package_plugin(source,Path(temp)/'out');published=True;publish_directory(packed,args.serial)
            cases=[]
            if args.baseline_apk:cases.append(('before',args.baseline_apk))
            cases.append(('after',ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'))
            for name,apk in cases:
                adb('install','-r',apk)
                recording=None;remote='/sdcard/Android/data/'+APP+'/files/swipe-'+name+'.mp4'
                try:
                    if args.record:
                        recording=subprocess.Popen(base+['shell','screenrecord','--time-limit','18','--bit-rate','2500000',remote],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                        time.sleep(.4)
                    output=adb('shell','am','instrument','-w','-e','expect',name,
                               TEST+'/moe.kiwi.thinkos.GallerySwipeInstrumentation')
                    (args.output/(name+'-instrumentation.txt')).write_bytes(output)
                    data=json.loads(adb('exec-out','run-as',APP,'cat','files/swipe-instrumentation.json'))
                    (args.output/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
                    print(json.dumps({k:v for k,v in data.items() if k!='events'},ensure_ascii=False),flush=True)
                    assert data['complete'],data.get('error')
                finally:
                    if recording:
                        recording.wait(timeout=25);adb('pull',remote,args.output/(name+'.mp4'));shell('rm','-f',remote)
            if args.baseline_apk:
                before=json.loads((args.output/'before.json').read_text());after=json.loads((args.output/'after.json').read_text())
                assert before['tinyHighVelocityChangedSelection'],'Baseline did not reproduce the tiny-flick selection error'
                assert not after['tinyHighVelocityChangedSelection'] and after['takeoverDiscontinuities']==0
    finally:
        if published:remove_plugin(PROBE,args.serial)
        shell('run-as',APP,'rm','-f','files/swipe-instrumentation.json')
        if not had_test:adb('uninstall',TEST)
        if scale==b'null':shell('settings','delete','global','animator_duration_scale')
        else:shell('settings','put','global','animator_duration_scale',scale.decode())
        shell('am','start','-W','-n',APP+'/.MainActivity')


if __name__=='__main__':main()
