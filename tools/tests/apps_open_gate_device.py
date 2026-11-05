#!/usr/bin/env python3
"""Exercise built-in tos.apps.open gates through normal Lua and native taps.

A temporary debug-signed APK replaces only the apps bundle with a visible probe.
DEX, manifest, native libraries and legal assets stay byte-identical. The original
APK and eight app files are restored afterwards. No private plugin APIs are added.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from package_plugins import package_plugin
from ui_walkthrough import broadcast_json


def instrument(original,temporary,report):
    source=temporary/'apps';shutil.copytree(ROOT/'plugins/apps',source)
    manifest=json.loads((source/'manifest.json').read_text())
    manifest['pages'].append({'id':'gate-probe','title':'应用权限验证','view':'views/gate-probe.json'})
    (source/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    (source/'main.lua').write_text('''local attempt=0
tos.on("start",function()
  tos.state.set { actions={{title="打开计算器"},{title="打开不存在的应用"}}, result="" }
end)
tos.on("tap",function(name,index)
  if name~="gate_open" or (index~=1 and index~=2) then return end
  attempt=attempt+1
  local number=attempt
  local id=index==1 and "android:com.android.calculator2" or "android:moe.kiwi.thinkos.missing"
  local opened=tos.apps.open(id)
  tos.state.set { result=number..(opened and " 已打开" or " 已拒绝") }
end)
''')
    (source/'views/gate-probe.json').write_text(json.dumps({'layout':'focus','slots':{
        'over':{'type':'text','value':'应用权限验证','style':'title'},
        'hero':{'type':'list','items':'actions','item':{'title':'{{.title}}'},'tap':'gate_open'},
        'under':{'type':'text','value':'{{result}}','style':'caption'}}},ensure_ascii=False)+'\n')
    bundle,_=package_plugin(source,temporary/'packages')
    index=json.loads((bundle/'index.json').read_text());report['probe_index']=index
    unsigned=temporary/'unsigned.apk'
    with zipfile.ZipFile(original) as src,zipfile.ZipFile(unsigned,'w') as dst:
        catalog=json.loads(src.read('assets/plugins/index.json'))
        catalog['plugins']=[index if entry['id']=='apps' else entry for entry in catalog['plugins']]
        for info in src.infolist():
            name=info.filename
            if name.startswith('assets/plugins/apps/') or name=='assets/plugins/index.json':continue
            if name.startswith('META-INF/') and (name.endswith(('.SF','.RSA','.DSA','.EC')) or name=='META-INF/MANIFEST.MF'):continue
            dst.writestr(info,src.read(name))
        dst.writestr('assets/plugins/index.json',json.dumps(catalog,separators=(',',':')))
        for path in bundle.rglob('*'):
            if path.is_file():dst.write(path,'assets/plugins/apps/'+path.relative_to(bundle).as_posix())
    properties=dict(line.split('=',1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    build_tools=Path(properties['sdk.dir'])/'build-tools/35.0.0'
    environment=dict(os.environ,THINKOS_PROBE_KEY_PASS='android')
    environment['JAVA_HOME']=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
    def run(*args):return subprocess.check_output(list(map(str,args)),env=environment,stderr=subprocess.STDOUT,text=True)
    aligned=temporary/'aligned.apk';probe=temporary/'probe.apk'
    run(build_tools/'zipalign','-p','-f','4',unsigned,aligned)
    run(build_tools/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey',
        '--ks-pass','env:THINKOS_PROBE_KEY_PASS','--key-pass','env:THINKOS_PROBE_KEY_PASS','--out',probe,aligned)
    def certificate(path):
        raw=run(build_tools/'apksigner','verify','--print-certs',path)
        return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',raw)[1]
    assert certificate(probe)==certificate(original),'The fixture must retain the installed debug certificate'
    with zipfile.ZipFile(original) as before,zipfile.ZipFile(probe) as after:
        names=[name for name in before.namelist() if not name.startswith(('assets/plugins/apps/','META-INF/')) and name!='assets/plugins/index.json']
        assert all(before.read(name)==after.read(name) for name in names),'Only plugin assets and signing records may change'
    report['code_manifest_native_and_legal_unchanged']=True
    report['same_certificate']=True
    report['probe_apk_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    return probe


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--apk',type=Path,default=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-5/open-gate-device')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial);report={'complete':False,'passed':[]}
    assert any(APP+'/' in line for line in d.shell('dumpsys','activity','activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    with tempfile.TemporaryDirectory(prefix='thinkos-app-open-gate-') as folder:
        temporary=Path(folder);original=temporary/'original.apk';shutil.copyfile(args.apk,original);original.chmod(0o600)
        report['original_apk_sha256']=hashlib.sha256(original.read_bytes()).hexdigest()
        installed=d.shell('pm','path',APP).strip().removeprefix('package:')
        assert d.shell('sha256sum',installed).split()[0]==report['original_apk_sha256'],'Use the currently installed production-debug APK'
        probe=instrument(original,temporary,report)
        journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
        print('Private recovery journal: '+str(journal),flush=True)
        report['private_apk_backup']=str(original)
        def wait(test):
            until=time.monotonic()+25
            while time.monotonic()<until:
                value=test()
                if value is not None and value is not False:return value
                time.sleep(.3)
            raise AssertionError('Timed out waiting for the open gate probe')
        def tree():
            remote='/sdcard/thinkos-app-open-gate.xml'
            try:
                dump_hierarchy(args.serial,remote,compressed=True)
                raw=d.shell('cat',remote);(args.output/'last-ui.xml').write_text(raw)
                return ET.fromstring(raw)
            finally:d.shell('rm','-f',remote)
        def find(text):return wait(lambda:next((n for n in visible_nodes(tree()) if n.get('text')==text),None))
        def tap(text):
            a,b,c,e=node_bounds(find(text));d.tap((a+c)//2,(b+e)//2);time.sleep(.3)
        def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
        def check(name,ok=True):
            assert ok,name
            report['passed'].append(name);print('PASS '+name,flush=True)
        try:
            report['install_probe']=d.adb('install','-r',str(probe)).decode();assert 'Success' in report['install_probe']
            d.shell('am','start','-W','-n',APP+'/.MainActivity')
            d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
            d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
            d.debug('setting',key='apps.android',value='true');d.debug('setting',key='apps.hidden',value='')
            until=time.monotonic()+25
            while time.monotonic()<until:
                d.debug('page',value='apps.gate-probe')
                if broadcast_json(d,'ui_state')['page']=='apps.gate-probe':break
                time.sleep(.3)
            find('打开计算器');tap('打开计算器');wait(lambda:'com.android.calculator2' in focused())
            d.shell('input','keyevent','KEYCODE_BACK');wait(lambda:APP in focused());find('1 已打开')
            check('the same factory caller can open a real enabled Android app')
            d.debug('setting',key='apps.android',value='false');tap('打开计算器');find('2 已拒绝')
            check('a direct public open request is denied when Android apps are disabled',APP in focused())
            d.debug('setting',key='apps.android',value='true');d.debug('setting',key='apps.hidden',value='com.android.calculator2')
            tap('打开计算器');find('3 已拒绝')
            check('a direct public open request is denied for a hidden installed app',APP in focused())
            d.debug('setting',key='apps.hidden',value='');tap('打开不存在的应用');find('4 已拒绝')
            check('a missing package fails without leaving ThinkOS',APP in focused())
            d.screenshot(args.output/'open-gates.png');report['complete']=True
        finally:
            try:
                report['restore_apk']=d.adb('install','-r',str(original)).decode();assert 'Success' in report['restore_apk']
                report.update(restore(journal,d))
                fixture_dir='files/plugins/apps/'+report['probe_index']['version']+'-'+report['probe_index']['digest']
                d.shell('run-as',APP,'rm','-r',fixture_dir,check=False)
                restored=d.shell('pm','path',APP).strip().removeprefix('package:')
                report['original_apk_restored']=d.shell('sha256sum',restored).split()[0]==report['original_apk_sha256']
                assert report['original_apk_restored']
                report.pop('private_apk_backup',None)
            except Exception as error:
                report['recovery_error']=str(error);report['private_recovery_journal']=str(journal)
                # Retain the APK as well as the raw-data journal if cleanup fails.
                backup=journal/'original.apk';shutil.copyfile(original,backup);backup.chmod(0o600)
                report['private_apk_backup']=str(backup)
            report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
            (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        assert not report.get('recovery_error'),report.get('recovery_error')
        assert report['media_volume_zero']


if __name__=='__main__':main()
