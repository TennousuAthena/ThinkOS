#!/usr/bin/env python3
"""Finish a player UI test's private recovery journal after its adb transport returns."""
import argparse
import base64
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path[:0]=[str(Path(__file__).resolve().parent),str(Path(__file__).resolve().parent/'tests')]
from device_app_files import read_app_file,restore_app_file
from media_device import APP,Device

ALLOWED={'files/settings/local.json','files/settings/local.json.bak','files/store/player.json',
         'files/store/player.json.bak','shared_prefs/deck.xml','shared_prefs/deck.xml.bak'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('journal',type=Path,help='private /tmp/thinkos-player-ui-recovery-… directory')
    parser.add_argument('--serial',help='override transport, for example a verified TCP connection')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    journal=args.journal/'state.json'
    data=json.loads(journal.read_text())
    if not isinstance(data.get('files'),dict) or set(data['files'])!=ALLOWED:raise ValueError('Unexpected recovery paths')
    saved={p:base64.b64decode(v,validate=True) if v is not None else None for p,v in data['files'].items()}
    device=Device(args.serial or data['serial'])
    physical=device.shell('getprop','ro.serialno').strip()
    if physical!='HUA0BWUT':raise ValueError('Recovery must target the authorized physical device')
    volume=data.get('volume')
    if volume is not None and (type(volume) is not int or not 0<=volume<=256):raise ValueError('Invalid volume snapshot')
    device.debug('media_stop')
    if volume is not None:device.shell('media','volume','--stream','3','--set',volume)
    device.shell('am','force-stop',APP)
    for path,value in saved.items():restore_app_file(device.serial,path,value)
    restored={p:read_app_file(device.serial,p)==v for p,v in saved.items()}
    if not all(restored.values()):raise RuntimeError('Saved file verification failed; journal retained')
    device.shell('am','start','-W','-n',APP+'/.MainActivity')
    ui=data['ui']
    device.debug('locale',value=ui['locale'])
    for command,key in (('theme','theme'),('hold_attention','attention'),('lux','lux')):
        device.debug(command,**({'value':ui[key]} if ui[key] is not None else {}))
    device.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',ui['orientation'])
    result={'complete':True,'physical_serial':physical,'restored_files':restored,'volume':volume}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    shutil.rmtree(args.journal)
    print('Recovered the authorized device; private journal removed. '+str(args.output))
    return 0


if __name__=='__main__':sys.exit(main())
