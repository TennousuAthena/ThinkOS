#!/usr/bin/env python3
"""LIC-01: mirror stays unavailable without FairPlay; AC-3 warns while video keeps playing."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/cast')]
from device_app_files import read_app_file,restore_app_file
from device_hierarchy import dump_hierarchy
from media_device import APP,Device,wait_for
from ui_walkthrough import broadcast_json
from airplay_sender import AirPlaySender


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--samples',type=Path,default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/lic/LIC-01/optout-device')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    device=Device(args.serial)
    original=broadcast_json(device,'ui_state')
    device.shell('am','force-stop',APP)
    paths=('files/settings/local.json','files/settings/local.json.bak','files/store/player.json','files/store/player.json.bak',
           'shared_prefs/deck.xml','shared_prefs/deck.xml.bak')
    saved={p:read_app_file(args.serial,p) for p in paths}
    device.shell('am','start','-W','-n',APP+'/.MainActivity')
    device.start_log()
    report={'complete':False,'passed':[]}
    remote='/sdcard/Movies/thinkos-lic-'+uuid.uuid4().hex+'.mkv'
    def check(name,ok):
        if not ok:raise AssertionError(name)
        report['passed'].append(name);print('PASS '+name,flush=True)
    try:
        installed=device.shell('pm','path',APP).strip().removeprefix('package:')
        report['apk_sha256']=device.shell('sha256sum',installed).split()[0]
        device.debug('hold_attention',value='ENGAGED');device.debug('theme',value='DARK');device.debug('locale',value='zh-CN')
        device.debug('setting',key='cast.airplay_mirror',value='true')
        device.debug('setting',key='cast.airplay',value='true')
        ip=re.search(r'inet (\d+\.\d+\.\d+\.\d+)',device.shell('ip','-4','addr','show','wlan0')).group(1)
        info={};status=0
        until=time.monotonic()+15
        while time.monotonic()<until:
            try:
                sender=AirPlaySender(ip);status,info=sender.server_info();sender.close()
                if status==200:break
            except OSError:time.sleep(.5)
        report['server_info']=info
        check('video-only features remain advertised even with the mirror setting on',status==200 and info.get('features')==0x19)
        sender=AirPlaySender(ip)
        status,_,_=sender.control.request('POST','/fp-setup',b'')
        sender.close()
        check('FairPlay setup is unavailable',status==404)
        device.push(args.samples/'video-h264-480p30-ac3.mkv',remote)
        mark=device.load(remote,kind='video',title='AC-3 opt-out test',repeat='one')
        device.open_page('player.video')
        playing,state=wait_for(device,lambda s:s.get('status')=='playing' and s.get('videoWidth',0)>0 and bool(s.get('warning')),18,.4)
        report['media']=state
        check('AC-3 produces a warning while H.264 still plays',bool(playing) and state.get('warning','').startswith('audio-unsupported:'))
        first=state.get('position',0);time.sleep(2)
        after=device.state() or {};report['media_after']=after
        check('the video playhead continues after the audio warning',after.get('status')=='playing' and after.get('position',0)>first+1000)
        remote_xml='/sdcard/thinkos-lic-optout.xml';dump_hierarchy(args.serial,remote_xml)
        raw=device.shell('cat',remote_xml);device.shell('rm','-f',remote_xml)
        (args.output/'warning-ui.xml').write_text(raw)
        texts=[n.get('text','') for n in ET.fromstring(raw).iter('node')]
        check('the decoder warning names AC-3 on screen',any('AC-3' in t and '无法解码' in t for t in texts))
        device.screenshot(args.output/'ac3-warning-video.png')
        report['complete']=True
    except Exception as error:
        report['failure']=str(error);device.screenshot(args.output/'failed-screen.png');raise
    finally:
        device.debug('media_stop');device.shell('rm','-f',remote)
        device.shell('am','force-stop',APP)
        for p,v in saved.items():restore_app_file(args.serial,p,v)
        report['restored_files']={p:read_app_file(args.serial,p)==v for p,v in saved.items()}
        device.shell('am','start','-W','-n',APP+'/.MainActivity')
        time.sleep(2)
        device.debug('locale',value=original['locale'])
        for command,key in (('hold_attention','attention'),('theme','theme'),('lux','lux')):
            device.debug(command,**({'value':original[key]} if original[key] is not None else {}))
        (args.output/'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n');device.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        assert all(report['restored_files'].values())
    return 0


if __name__=='__main__':sys.exit(main())
