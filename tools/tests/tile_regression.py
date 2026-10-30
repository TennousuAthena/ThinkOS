#!/usr/bin/env python3
"""Run the existing component/weather/settings device suites with temporary auto-theme settings."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from device_app_files import read_app_file
APP='moe.kiwi.thinkos'
HOME=APP+'/.MainActivity'
LAUNCHER='org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True)
    p.add_argument('--only',choices=['component_ui','weather_ui','settings_ui'])
    args=p.parse_args();out=ROOT/'artifacts/tiles/regression';out.mkdir(parents=True,exist_ok=True)
    base=['adb','-s',args.serial]
    def shell(*args,data=None):return subprocess.check_output(base+['shell','-T',shlex.join(map(str,args))],input=data,timeout=30)
    path='files/settings/local.json';original=read_app_file(args.serial,path)
    def write(data):
        if data is None:shell('run-as',APP,'rm','-f',path,path+'.bak')
        else:
            shell('run-as',APP,'sh','-c','cat > '+path+'.tiles-restore',data=data)
            shell('run-as',APP,'mv',path+'.tiles-restore',path);shell('run-as',APP,'rm','-f',path+'.bak')
    results=json.loads((out/"report.json").read_text()) if args.only and (out/"report.json").exists() else {}
    with tempfile.TemporaryDirectory(prefix='thinkos-tiles-settings-') as backup:
        if original is not None:Path(backup,'local.json').write_bytes(original)
        try:
            shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP)
            config=json.loads(original or b'{}');config.setdefault('system',{}).update({'theme.mode':'auto','theme.night':True,'display.orientation':'landscape'})
            write(json.dumps(config,ensure_ascii=False).encode());shell('cmd','package','set-home-activity',HOME);shell('am','start','-n',HOME)
            import time;time.sleep(4)
            for name in ([args.only] if args.only else ['component_ui','weather_ui','settings_ui']):
                command=[sys.executable,str(ROOT/'tools/tests'/f'{name}.py'),'--serial',args.serial,'--output',str(out/name)]
                if name=='weather_ui':command+=['--wireless-offline']
                with (out/(name+'.log')).open('w') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
                results[name]=result.returncode==0
                print(name+': '+('PASS' if result.returncode==0 else 'FAIL'),flush=True)
        finally:
            shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP);write(original)
            shell('cmd','package','set-home-activity',HOME);shell('am','start','-n',HOME)
            results['settingsRestored']=read_app_file(args.serial,path)==original
            (out/'report.json').write_text(json.dumps(results,indent=2)+'\n')
    assert results.get(args.only,False) if args.only else all(results.values()),results
if __name__=='__main__':main()
