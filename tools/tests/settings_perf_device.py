#!/usr/bin/env python3
"""Announced quiet performance gate: AWAKE, native settings, and ten-minute AMBIENT.

Run only after functional device checks. No audio changes. Captures an exact private
recovery journal before applying schema settings; validates the scene throughout sampling.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/set/SET-06/performance')
    parser.add_argument('--clock-seconds', action='store_true', help='Stress profile with seconds; default false matches the factory/default baseline')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    assert any(APP+'/' in line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get'))
    journal = capture(device, broadcast_json(device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    installed=device.shell('pm','path',APP).strip().removeprefix('package:')
    apk_hash=device.shell('sha256sum',installed).split()[0]
    assert re.fullmatch('[0-9a-f]{64}',apk_hash), 'Cannot identify the installed APK'
    report = {'complete': False, 'profiles': {}, 'clock_seconds': args.clock_seconds, 'apk_sha256': apk_hash}
    worker = logger = None

    def state():
        raw = device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"\n]*)"', raw); assert match, raw
        result=dict(zip(('page','attention','theme','orientation','pid','touches','navigationReason','luaBytes','navigationGeneration'), match[1].split('|')))
        result['variant']=broadcast_json(device,'ui_state')['variant']
        return result
    def wait(test, seconds=25):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            value = test()
            if value: return value
            time.sleep(.3)
        raise AssertionError('Performance scene did not settle')
    def orientation(value):
        device.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',value)
        time.sleep(.8)
    def settings_page(portrait):
        orientation(1 if portrait else 0)
        device.debug('page',value='home.main'); device.debug('settings',value='system')
        wait(lambda: state()['page'] == 'settings.form')
        if portrait:
            remote='/sdcard/thinkos-settings-perf.xml'
            try:
                dump_hierarchy(args.serial, remote, compressed=True)
                root=ET.fromstring(device.shell('cat', remote))
                label=next(n for n in visible_nodes(root) if n.get('text') == '显示')
                a,b,c,d=node_bounds(label); device.tap((a+c)//2,(b+d)//2)
            finally: device.shell('rm','-f',remote)
        time.sleep(1)
    def memory(folder, name):
        raw=device.shell('dumpsys','meminfo',APP); (folder/(name+'.txt')).write_text(raw)
        return int(re.search(r'^\s*TOTAL\s+(\d+)',raw,re.M)[1])
    def sample(name, seconds):
        nonlocal worker, logger
        folder=args.output/name; folder.mkdir()
        initial=state(); (folder/'initial-state.json').write_text(json.dumps(initial,indent=2)+'\n')
        memory_before=memory(folder,'memory-before')
        device.screenshot(folder/'screen.png')
        with (folder/'live-logcat.txt').open('wb') as log, (folder/'bench.log').open('wb') as output:
            logger=subprocess.Popen(['adb','-s',args.serial,'logcat','-T','1','--pid='+initial['pid'],'-s','ThinkOS:W','AndroidRuntime:E','Choreographer:W','OpenGLRenderer:W'],stdout=log,stderr=subprocess.STDOUT)
            worker=subprocess.Popen([str(ROOT/'tools/bench.sh'),str(seconds),str(folder)],env=dict(os.environ,ADB_MDNS='0',ANDROID_SERIAL=args.serial),stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
            observations=[]
            while worker.poll() is None:
                observed=state(); observations.append(observed)
                keys=('page','variant','attention','theme','orientation','pid','touches','navigationGeneration')
                assert all(observed[k] == initial[k] for k in keys), 'Scene changed: '+str(observed)
                time.sleep(2)
            assert worker.returncode == 0, 'bench.sh failed; see '+str(folder/'bench.log')
            worker=None
            logger.terminate(); logger.wait(timeout=10); logger=None
        final=state(); assert all(final[k] == initial[k] for k in keys)
        (folder/'scene-observations.json').write_text(json.dumps(observations,indent=2)+'\n')
        cpu=json.loads((folder/'cpu.json').read_text())
        gfx=(folder/'gfx-after.txt').read_text()
        frames=int(re.search(r'Total frames rendered: (\d+)',gfx)[1])
        janky=int(re.search(r'Janky frames: (\d+)',gfx)[1])
        warnings=len(re.findall(r'Skipped \d+ frames|Davey!|FATAL EXCEPTION|plugin CPU budget exceeded',(folder/'live-logcat.txt').read_text()))
        result={'seconds':cpu['elapsed_s'],'mean_cpu_pct':cpu['mean_cpu_pct'],'frames':frames,'janky_frames':janky,'janky_pct':100*janky/max(frames,1),'frame_warnings':warnings,'pss_before_kib':memory_before,'pss_after_kib':memory(folder,'memory-after'),'scene_stable':True,'scene':initial}
        report['profiles'][name]=result
        (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
        print(name+': '+json.dumps(result),flush=True)
        assert result['janky_pct'] < 5 and warnings == 0, result
        return result

    try:
        device.shell('am','start','-W','-n',APP+'/.MainActivity')
        device.debug('locale',value='zh-CN'); device.debug('theme',value='DARK'); device.debug('lux',value=20.0)
        device.debug('setting',scope='clock',key='seconds',value=str(args.clock_seconds).lower())
        device.debug('hold_attention',value='AWAKE'); orientation(0)
        device.debug('page',value='clock.face'); wait(lambda: state()['page'] == 'clock.face')
        device.debug('variant',value='digital'); wait(lambda: state()['variant']=='digital'); time.sleep(4)
        awake=sample('awake',120)
        for portrait in (True,False):
            device.debug('hold_attention',value='ENGAGED'); settings_page(portrait)
            result=sample('settings-'+('portrait' if portrait else 'landscape'),20)
            result['cpu_within_awake_plus20']=result['mean_cpu_pct'] <= awake['mean_cpu_pct']*1.2
            assert result['cpu_within_awake_plus20'], result
        device.shell('input','keyevent','KEYCODE_HOME')
        device.debug('hold_attention',value='AMBIENT'); orientation(0)
        device.debug('page',value='home.main'); wait(lambda: state()['page'] == 'home.main'); time.sleep(4)
        ambient=sample('ambient',600)
        assert ambient['frames'] > 0 and ambient['seconds'] >= 600
        for name in (() if args.clock_seconds else ('awake','ambient')):
            baseline=ROOT/'artifacts/pre-m1'/name/'cpu.json'
            if baseline.exists():
                prior=json.loads(baseline.read_text())['mean_cpu_pct']
                report['profiles'][name]['historical_baseline_cpu_pct']=prior
                report['profiles'][name]['historical_within_plus20']=report['profiles'][name]['mean_cpu_pct'] <= prior*1.2
        report['historical_checks_passed']=all(profile.get('historical_within_plus20',True) for profile in report['profiles'].values())
        report['complete']=report['historical_checks_passed']
        assert report['complete'], 'CPU exceeded the historical +20% gate; see per-profile raw values'
    except Exception as error:
        report['failure']=str(error)
        raise
    finally:
        for proc in (worker,logger):
            if proc is not None and proc.poll() is None:
                if proc is worker: os.killpg(proc.pid,signal.SIGTERM)
                else: proc.terminate()
                proc.wait(timeout=12)
        try: report.update(restore(journal,device))
        except Exception as error:
            report['recovery_error']=str(error); report['private_recovery_journal']=str(journal)
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',device.shell('media','volume','--stream',3,'--get')))
        (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
    assert not report.get('recovery_error'),report.get('recovery_error')
    assert report['media_volume_zero']


if __name__ == '__main__': main()
