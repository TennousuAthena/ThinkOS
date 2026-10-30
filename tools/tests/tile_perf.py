#!/usr/bin/env python3
"""Two independent 10-minute AWAKE live-tile scenes, with application networking blocked over wireless ADB."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from app_network_block import AppNetworkBlock
from device_app_files import read_app_file
from package_plugins import package_plugin
from plugin_push import publish_directory,remove_plugin
APP='moe.kiwi.thinkos';HOME=APP+'/.MainActivity';LAUNCHER='org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--seconds',type=int,default=600)
    p.add_argument('--output',type=Path,default=ROOT/'artifacts/tiles/performance');args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    base=['adb','-s',args.serial]
    def adb(*v):return subprocess.check_output(base+list(v),timeout=30)
    def shell(*v,data=None):return subprocess.check_output(base+['shell','-T',shlex.join(map(str,v))],input=data,timeout=30)
    def debug(c,v=None,kind='--es'):
        values=['am','broadcast','-n',APP+'/.DebugReceiver','--es','command',c]
        if v is not None:values += [kind,'value',str(v)]
        shell(*values)
    def state():
        debug('state');return adb('logcat','-d','-t','20','-s','ThinkOS:I').decode().split('DEBUG state=')[-1]
    def navigate(page):
        for _ in range(18):
            if 'page='+page+',' in state():return
            shell('input','swipe',1100,400,150,400,350);time.sleep(.6)
        raise AssertionError('Cannot reach '+page)
    def pid():return shell('pidof',APP).decode().strip()
    path='files/settings/local.json';original=read_app_file(args.serial,path)
    def write(raw):
        if raw is None:shell('run-as',APP,'rm','-f',path,path+'.bak')
        else:
            shell('run-as',APP,'sh','-c','cat > '+path+'.tile-perf',data=raw)
            shell('run-as',APP,'mv',path+'.tile-perf',path);shell('run-as',APP,'rm','-f',path+'.bak')
    scale=shell('settings','get','global','animator_duration_scale').decode().strip()
    report={'complete':False,'scenes':{},'failures':[]}
    network=AppNetworkBlock(args.serial)
    logger=None;live_log=None
    try:
        assert not shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve existing development overrides'
        shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP)
        shell('settings','put','global','animator_duration_scale','1.0')
        config=json.loads(original or b'{}');config.setdefault('system',{}).update({'theme.mode':'dark','theme.night':False,'display.orientation':'landscape','tiles.live':True,'tiles.interval':5})
        write(json.dumps(config).encode());network.start()
        (args.output/'network-before.txt').write_text(network.evidence())
        shell('cmd','package','set-home-activity',HOME);shell('am','start','-n',HOME);time.sleep(5)
        debug('hold_attention','AWAKE');debug('orientation',0,'--ei')
        for name,page in [('builtin','tiles.main'),('sixteen','tile-probe.stress')]:
            if name=='sixteen':
                with tempfile.TemporaryDirectory(prefix='tiles-stress-') as temp:
                    directory,digest=package_plugin(ROOT/'tools/tests/fixtures/tile-probe',temp);publish_directory(directory,args.serial)
                    report['stressFixtureDigest']=digest
                time.sleep(2)
            navigate(page);time.sleep(5)
            folder=args.output/name;folder.mkdir(parents=True,exist_ok=True)
            (folder/'screen.png').write_bytes(adb('exec-out','screencap','-p'))
            before=pid();(folder/'pid-before.txt').write_text(before)
            shell('dumpsys','gfxinfo',APP,'reset')
            live_log=(folder/'live-logcat.txt').open('wb')
            logger=subprocess.Popen(base+['logcat','-T','1','--pid='+before,'-s','ThinkOS:V','AndroidRuntime:E','Choreographer:W','OpenGLRenderer:W'],stdout=live_log,stderr=subprocess.STDOUT)
            samples=[];start=time.monotonic();last=0
            print('Sampling '+name+' for '+str(args.seconds)+' seconds',flush=True)
            while time.monotonic()-start<args.seconds:
                # Each framestats ring holds 120 frames. Poll every 3s to capture continuous
                # 30fps icon drawing, including every scheduled face change.
                raw=shell('dumpsys','gfxinfo',APP,'framestats').decode();elapsed=time.monotonic()-start
                (folder/f'frames-{len(samples):04d}.txt').write_text(raw)
                sample={'elapsed':elapsed}
                if elapsed-last>=30 or not samples:
                    memory=shell('dumpsys','meminfo',APP).decode();(folder/f'memory-{len(samples):04d}.txt').write_text(memory)
                    sample['pssKiB']=int(re.search(r'^\s*TOTAL:\s*(\d+)',memory,re.M)[1]);last=elapsed
                    sample['state']=state()
                    values=[int(n) for n in re.findall(r'bytes=(\d+)',sample['state'])];sample['luaBytes']=sum(values)
                samples.append(sample);time.sleep(3)
            logger.terminate();logger.wait(timeout=10);live_log.close();logger=None;live_log=None
            warnings=(folder/'live-logcat.txt').read_text()
            warning_count=len(re.findall(r'Skipped \d+ frames|Davey!|FATAL EXCEPTION|plugin CPU budget exceeded',warnings))
            (folder/'samples.json').write_text(json.dumps(samples,indent=2)+'\n')
            gfx=shell('dumpsys','gfxinfo',APP).decode();(folder/'gfx-after.txt').write_text(gfx)
            after=pid();(folder/'pid-after.txt').write_text(after)
            frames=int(re.search(r'Total frames rendered: (\d+)',gfx)[1]);janky=int(re.search(r'Janky frames: (\d+)',gfx)[1])
            durations={}
            for f in folder.glob('frames-*.txt'):
                since_match=re.search(r'Stats since: (\d+)ns',f.read_text())
                since=int(since_match[1]) if since_match else 0
                in_profile=False;columns=None
                for line in f.read_text().splitlines():
                    if line=='---PROFILEDATA---':in_profile=not in_profile;columns=None;continue
                    if not in_profile:continue
                    cells=line.rstrip(',').split(',')
                    if cells[0]=='Flags':columns={v:i for i,v in enumerate(cells)};continue
                    if not columns or len(cells)<len(columns) or cells[0]!='0':continue
                    vsync=int(cells[columns['IntendedVsync']]);draw=int(cells[columns['DrawStart']]);sync=int(cells[columns['SyncQueued']])
                    if vsync<since:continue
                    end=int(cells[columns['FrameCompleted']]);durations[vsync]=((sync-draw)/1e6,(end-vsync)/1e6)
            draw_max=max((v[0] for v in durations.values()),default=0);frame_max=max((v[1] for v in durations.values()),default=0)
            scene={'seconds':time.monotonic()-start,'frames':frames,'jankyFrames':janky,'jankyPercent':janky/max(frames,1)*100,'maxDrawMs':draw_max,'maxFrameMs':frame_max,'sampledFrames':len(durations),'peakPssMiB':max(s.get('pssKiB',0) for s in samples)/1024,'peakLuaMiB':max(s.get('luaBytes',0) for s in samples)/1048576,'sameProcess':before==after,'frameWarnings':warning_count}
            report['scenes'][name]=scene
            if not frames or not durations or janky/max(frames,1)>=.05 or warning_count or draw_max>12 or scene['peakPssMiB']>=350 or scene['peakLuaMiB']>=24 or before!=after:report['failures'].append(name)
            print(json.dumps(scene),flush=True)
        (args.output/'network-after.txt').write_text(network.evidence())
        report['complete']=not report['failures'] and args.seconds>=600
    finally:
        if logger is not None and logger.poll() is None:logger.terminate();logger.wait(timeout=10)
        if live_log is not None:live_log.close()
        (args.output/'logcat.txt').write_bytes(adb('logcat','-d','-s','ThinkOS:V','AndroidRuntime:E'))
        network.stop();remove_plugin('tile-probe',args.serial)
        shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP);write(original)
        if scale=='null':shell('settings','delete','global','animator_duration_scale')
        else:shell('settings','put','global','animator_duration_scale',scale)
        shell('cmd','package','set-home-activity',HOME);shell('am','start','-n',HOME)
        report['settingsRestored']=read_app_file(args.serial,path)==original
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    assert report['complete'],report
if __name__=='__main__':main()
