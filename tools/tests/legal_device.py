#!/usr/bin/env python3
"""Read native legal notices, reject an unprivileged Lua caller, and retry without plugins.

All five installed asset bytes are checked against their repository originals.
Scrolling uses touch; legal_state only observes the real native scroll position.
The all-disabled exercise is process-local and ends with a normal process restart.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/lic/LIC-03/device')
    parser.add_argument('--without-gpl', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    original = broadcast_json(device, 'ui_state')
    saved = {p:read_app_file(args.serial,p) for p in ('files/settings/local.json','files/settings/local.json.bak',
                                                       'shared_prefs/deck.xml','shared_prefs/deck.xml.bak')}
    probe = 'legal-notice-probe'
    check_dev = device.shell('run-as', APP, 'ls', 'files/dev-plugins').strip()
    assert not check_dev, 'Preserve existing development overrides: '+check_dev
    report = {'complete':False, 'passed':[], 'documents':{}, 'without_gpl':args.without_gpl}
    device.start_log()

    def check(name, result):
        if not result: raise AssertionError(name)
        report['passed'].append(name)
        print('PASS '+name, flush=True)

    def wait(test, seconds=12):
        until = time.monotonic()+seconds
        while time.monotonic()<until:
            if test(): return True
            time.sleep(.25)
        return False

    def tree():
        remote = '/sdcard/thinkos-legal.xml'
        dump_hierarchy(args.serial,remote)
        try:
            raw=device.shell('cat',remote)
            (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: device.shell('rm','-f',remote)

    def find(label):
        for _ in range(10):
            root=tree()
            for node in visible_nodes(root):
                if label in (node.get('text'),node.get('content-desc')): return node
            a,b,c,d=node_bounds(root.find('node'))
            device.shell('input','swipe',(a+c)//2,int(d*.85),(a+c)//2,int(d*.3),300)
        raise AssertionError('Missing legal control: '+label)

    def tap(label):
        a,b,c,d=node_bounds(find(label));device.tap((a+c)//2,(b+d)//2);time.sleep(.35)

    def legal(): return broadcast_json(device,'legal_state')
    def back(): device.shell('input','keyevent','KEYCODE_BACK');time.sleep(.35)

    try:
        device.debug('hold_attention',value='ENGAGED')
        device.debug('theme',value='DARK')
        device.debug('locale',value='zh-CN')
        device.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        installed=device.shell('pm','path',APP).strip().removeprefix('package:')
        report['apk_sha256']=device.shell('sha256sum',installed).split()[0]
        with tempfile.TemporaryDirectory(prefix='thinkos-legal-') as temporary:
            apk=Path(temporary)/'installed.apk';device.adb('pull',installed,str(apk))
            with zipfile.ZipFile(apk) as archive:
                for file in ('LICENSE','LICENSE-GPL-3.0.md','LICENSE-APACHE-2.0.txt','LICENSE-MPL-2.0.txt','THIRD_PARTY_NOTICES.md'):
                    bundled=archive.read('assets/legal/'+file)
                    check(file+' is bundled without modification',bundled==(ROOT/file).read_bytes())
                    report['documents'][file]={'sha256':hashlib.sha256(bundled).hexdigest(),'bytes':len(bundled)}
            device.debug('settings',value='system')
            tap('系统')
            tap('关于本机')
            check('settings opens the native about page',wait(lambda:legal().get('ready')))
            texts=[n.get('text') for n in tree().iter('node')]
            notice=json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())['legal_notice_mit' if args.without_gpl else 'legal_notice_gpl']
            check('the distribution notice matches this build',notice in texts)
            check('copyright comes from LICENSE line three',(ROOT/'LICENSE').read_text().splitlines()[2] in texts)
            check('the source address is shown','https://github.com/TennousuAthena/ThinkOS' in texts)
            device.screenshot(args.output/'about.png')
            labels=json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())
            documents=[('LICENSE-GPL-3.0.md','legal_gpl'),('LICENSE','legal_mit'),('LICENSE-APACHE-2.0.txt','legal_apache'),('LICENSE-MPL-2.0.txt','legal_mpl'),('THIRD_PARTY_NOTICES.md','legal_third_party')]
            for index,(file,key) in enumerate(documents):
                tap(labels[key])
                check(file+' opens',legal().get('document')==file)
                if file == 'THIRD_PARTY_NOTICES.md':
                    shown = '\n'.join(node.get('text','') for node in visible_nodes(tree()))
                    check('third-party title appears once', len(re.findall(r'(?m)^第三方说明$', shown)) == 1)
                    check('third-party notices do not expose Markdown source',
                          not re.search(r'(?m)^#{1,6} |^\|.*\|$', shown) and '`' not in shown and
                          not re.search(r'\[[^]]+\]\(https?://', shown))
                    check('third-party tables are readable named entries', '许可证：' in shown and '位置：' in shown)
                    check('third-party notices retain readable source addresses', 'https://github.com/FD-/RPiPlay' in shown)
                device.screenshot(args.output/(str(index)+'-top.png'))
                steps=0
                while legal().get('canScrollDown') and steps<90:
                    root=tree();a,b,c,d=node_bounds(root.find('node'))
                    device.shell('input','swipe',(a+c)//2,int(d*.88),(a+c)//2,int(d*.22),250)
                    steps+=1
                state=legal()
                report['documents'][file].update(scrollY=state.get('scrollY'),swipes=steps)
                check(file+' can be read to its end',not state.get('canScrollDown') and state.get('document')==file)
                device.screenshot(args.output/(str(index)+'-bottom.png'))
                back()
                check('Back returns from '+file+' to the index',legal().get('document') is None and legal().get('showing'))
            back()
            check('Back leaves the native about page',not legal().get('showing'))

            fixture=Path(temporary)/probe;fixture.mkdir()
            (fixture/'manifest.json').write_text(json.dumps({'id':probe,'name':'Legal permission probe','version':'1.0.0','api':9,'main':'main.lua','permissions':[]}))
            (fixture/'main.lua').write_text('tos.on("start",function() local ok=pcall(tos.settings.about); tos.log.info("LEGAL_PERMISSION_DENIED",tostring(not ok)) end)\n')
            packaged,_=package_plugin(fixture,Path(temporary)/'package')
            mark=device.log_mark();publish_directory(packaged,args.serial)
            check('a plugin without system.settings cannot open About',wait(lambda:any('LEGAL_PERMISSION_DENIED true' in line for line in device.log_since(mark))))
            check('a refused caller leaves the native window closed',not legal().get('showing'))
            remove_plugin(probe,args.serial)

            device.debug('plugins_disable_all')
            check('all plugin Lua runtimes are stopped',wait(lambda:any(re.search(r'DEBUG plugins_disabled count=\d+ lua=0',line) for line in device.log_since(0))))
            back()
            device.debug('maintenance')
            tap('许可证')
            check('the rescue entry works with every plugin disabled',wait(lambda:legal().get('ready')))
            device.screenshot(args.output/'without-plugins.png')
            report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        device.screenshot(args.output/'failed-screen.png')
        raise
    finally:
        remove_plugin(probe,args.serial)
        device.shell('am','force-stop',APP)
        for path,content in saved.items():restore_app_file(args.serial,path,content)
        report['restored_files']={p:read_app_file(args.serial,p)==v for p,v in saved.items()}
        device.shell('am','start','-W','-n',APP+'/.MainActivity')
        time.sleep(2)
        device.debug('locale',value=original['locale'])
        device.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',original['orientation'])
        for command,key in (('hold_attention','attention'),('theme','theme'),('lux','lux')):
            device.debug(command,**({'value':original[key]} if original[key] is not None else {}))
        (args.output/'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n')
        device.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        assert all(report['restored_files'].values())
    return 0


if __name__=='__main__':sys.exit(main())
