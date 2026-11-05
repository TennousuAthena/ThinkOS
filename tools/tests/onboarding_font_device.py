#!/usr/bin/env python3
"""WEL-0 font_scale=1.3 feasibility captures, with exact system and app restoration.

This changes the real system font scale temporarily. It does not add a text-size
setting or qualify every page/language: exported screenshots need visual review.
No media volume gesture, performance sampling, module or Device Owner operation.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json

PAGES=('home.main','clock.face','tiles.main','apps.main','weather.detail','settings.form')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/welcome/WEL-0/font')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    for key in PAGES:
        plugin,page=key.split('.')
        assert page in {p['id'] for p in json.loads((ROOT/'plugins'/plugin/'manifest.json').read_text())['pages']},key
    d=Device(args.serial)
    def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    def system():return {'font_scale':d.shell('settings','get','system','font_scale').strip(),
                         'locale':d.shell('getprop','persist.sys.locale').strip(),
                         'timezone':d.shell('getprop','persist.sys.timezone').strip()}
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    assert APP+'/' in focused()
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    original=system();raw_scale=original['font_scale']
    assert raw_scale=='null' or re.fullmatch(r'\d+(?:\.\d+)?',raw_scale)
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    recovery=Path(tempfile.mkdtemp(prefix='thinkos-welcome-font-recovery-'))
    with os.fdopen(os.open(recovery/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump({'physical':'HUA0BWUT','sdk':27,'original':original,'app_journal':str(journal)},stream)
        stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(recovery)})+'\n')
    print('Private app and font journals: '+str(journal)+' '+str(recovery),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'font_scale':1.3,'visual_review':'pending','system_before':original,
            'apk_sha256':d.shell('sha256sum',installed).split()[0],'screens':{}}
    d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for real font scale or visible page')
    def scale():
        stage=broadcast_json(d,'stage_state')
        return stage if abs(stage.get('fontScale',0)-1.3)<.001 else None
    def tree(name):
        path='/sdcard/thinkos-welcome-font.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True);raw=d.shell('cat',path)
            (args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',path)
    def capture_screen(name):
        if 'preview' not in name:
            assert broadcast_json(d,'ui_state')['locale']==name.split('-portrait-')[0].split('-landscape-')[0],name+' lost the process-local locale'
        d.screenshot(args.output/(name+'.png'))
        size=struct.unpack('>II',(args.output/(name+'.png')).read_bytes()[16:24])
        nodes=[n for n in visible_nodes(tree(name)) if n.get('package')==APP]
        assert nodes,name+' has no visible ThinkOS content'
        report['screens'][name]={'size':list(size),'text':[{'text':n.get('text'),'description':n.get('content-desc'),
            'bounds':list(node_bounds(n))} for n in nodes if n.get('text') or n.get('content-desc')]}
        print('CAPTURE '+name+' at real font_scale=1.3',flush=True)
    def page(key):
        if key=='settings.form':
            d.debug('page',value='home.main');d.debug('settings',value='system')
        else:d.debug('page',value=key)
        return broadcast_json(d,'ui_state')['page']==key
    try:
        d.shell('settings','put','system','font_scale','1.3')
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        wait(scale);d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        d.debug('drift',x=0.0,y=0.0)
        for locale in ('zh-CN','en','ar'):
            for orientation_name,orientation in (('portrait',1),('landscape',0)):
                d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
                time.sleep(2.5);wait(scale)
                # Android reapplies the real global locale during rotation. Apply this
                # screenshot-only override after that, then verify it on every page.
                d.debug('locale',value=locale)
                wait(lambda:broadcast_json(d,'ui_state')['locale']==locale)
                for key in PAGES:
                    wait(lambda:page(key));time.sleep(.7)
                    capture_screen(locale+'-'+orientation_name+'-'+key)
                for screen in ('hello','update'):
                    d.shell('am','start','-W','-n',APP+'/.OnboardingPreviewActivity',
                        '--es','screen',screen,'--es','locale',locale,'--es','theme','light',
                        '--ei','orientation',orientation,'--ez','animate','false')
                    wait(lambda:'.OnboardingPreviewActivity' in focused());time.sleep(2.5)
                    capture_screen(locale+'-'+orientation_name+'-preview-'+screen)
                    d.shell('input','keyevent','KEYCODE_BACK');wait(lambda:'.MainActivity' in focused())
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:
            d.shell('am','force-stop',APP)
            if raw_scale=='null':d.shell('settings','delete','system','font_scale')
            else:d.shell('settings','put','system','font_scale',raw_scale)
            report['system_after']=system()
            if report['system_after']!=original:raise RuntimeError('System settings differ; private journal retained')
            report['system_font_scale_restored']=True
        except Exception as error:report.update(system_recovery_error=str(error),private_system_journal=str(recovery))
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_app_journal=str(journal))
        report['restored_activity_font_scale']=broadcast_json(d,'stage_state').get('fontScale')
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not report.get('system_recovery_error') and not report.get('recovery_error'):shutil.rmtree(recovery)
    assert report.get('system_font_scale_restored') and not report.get('recovery_error')
    assert abs(report['restored_activity_font_scale']-(1 if raw_scale=='null' else float(raw_scale)))<.001
    assert report['media_volume_zero']


if __name__=='__main__':main()
