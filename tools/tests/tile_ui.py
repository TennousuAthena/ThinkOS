#!/usr/bin/env python3
"""Native tile compositor/binding/gesture checks; works over USB or wireless ADB without disabling Wi-Fi."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from device_app_files import read_app_file


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    p.add_argument('--output',type=Path,default=ROOT/'artifacts/ui-validation/tiles')
    p.add_argument('--skip-build',action='store_true')
    p.add_argument('--keep-fixture',action='store_true')
    p.add_argument('--live',action='store_true')
    p.add_argument('--plugins',action='store_true')
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    adb=['adb','-s',args.serial]
    if not args.skip_build:
        with (args.output/'build.log').open('w') as log:
            subprocess.run(['./gradlew','-PtileProbe=true','assembleDebug','assembleDebugAndroidTest'],cwd=ROOT/'android',stdout=log,stderr=subprocess.STDOUT,check=True)
    for apk in ['apk/debug/app-debug.apk','apk/androidTest/debug/app-debug-androidTest.apk']:
        subprocess.run(adb+['install','-r',str(ROOT/'android/app/build/outputs'/apk)],check=True)
    with tempfile.TemporaryDirectory(prefix='tile-ui-') as tmp:
        folder,_=package_plugin(ROOT/'tools/tests/fixtures/tile-probe',tmp);publish_directory(folder,args.serial)
        if args.plugins:
            folder,_=package_plugin(ROOT/'tools/tests/fixtures/legacy-three',tmp);publish_directory(folder,args.serial)
    subprocess.run(adb+['shell','run-as','moe.kiwi.thinkos','rm','-rf','files/tiles-validation'],check=True)
    scale=subprocess.check_output(adb+['shell','settings','get','global','animator_duration_scale']).decode().strip()
    try:
        subprocess.run(adb+['shell','settings','put','global','animator_duration_scale','1.0'],check=True)
        command=adb+['shell','am','instrument','-w']
        if args.live:command+=['-e','live','true']
        if args.plugins:command+=['-e','plugins','true']
        completed=subprocess.run(command+['moe.kiwi.thinkos.test/moe.kiwi.thinkos.TileInstrumentation'],capture_output=True,timeout=300)
        (args.output/'instrumentation.txt').write_bytes(completed.stdout+completed.stderr)
        raw=read_app_file(args.serial,'files/tiles-validation/report.json')
        if raw is None:raise RuntimeError('Instrumentation did not write report')
        (args.output/'report.json').write_bytes(raw);report=json.loads(raw)
        for name in report['screens']:
            (args.output/(name+'.png')).write_bytes(read_app_file(args.serial,'files/tiles-validation/'+name+'.png'))
        (args.output/'logcat.txt').write_bytes(subprocess.check_output(adb+['logcat','-d','-s','ThinkOS:V','AndroidRuntime:E']))
        assert report['complete'],report.get('error',report)
        for item in report['passed']:print('PASS '+item)
    finally:
        restore=['delete','global','animator_duration_scale'] if scale=='null' else ['put','global','animator_duration_scale',scale]
        subprocess.run(adb+['shell','settings']+restore,check=True)
        if args.plugins:remove_plugin('legacy-three',args.serial)
        if not args.keep_fixture:remove_plugin('tile-probe',args.serial)

if __name__=='__main__':main()
