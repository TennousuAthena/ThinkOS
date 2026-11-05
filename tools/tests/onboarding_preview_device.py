#!/usr/bin/env python3
"""WEL-0 native hello/update visual prototypes. No system language, font, timezone or onboarding state is changed."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json

LOCALES = ('zh-CN','en','zh-TW','es','fr','pt','ru','ar','ur','hi','bn')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/welcome/WEL-0/preview')
    parser.add_argument('--animation-only',action='store_true')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert APP+'/' in focused()
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    def system():return {'locale':d.shell('getprop','persist.sys.locale').strip(),
                         'timezone':d.shell('getprop','persist.sys.timezone').strip(),
                         'font_scale':d.shell('settings','get','system','font_scale').strip()}
    original_system=system();journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    print('Private recovery journal: '+str(journal),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'prototype_only':True,'system_locale_change_tested':False,
            'apk_sha256':d.shell('sha256sum',installed).split()[0],'screens':{},'system_before':original_system}
    d.start_log()
    def wait(test):
        until=time.monotonic()+25
        while time.monotonic()<until:
            if test():return
            time.sleep(.3)
        raise AssertionError('The native prototype did not become visible')
    def tree(name):
        path='/sdcard/thinkos-welcome-preview.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True)
            raw=d.shell('cat',path);(args.output/(name+'.xml')).write_text(raw)
            return ET.fromstring(raw)
        finally:d.shell('rm','-f',path)
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('hold_attention',value='ENGAGED');d.debug('theme',value='DARK')
        wait(lambda:bool(broadcast_json(d,'ui_state')['page']))
        time.sleep(3)  # Let the initial screen-pinning toast expire before the first reference.
        for locale in (() if args.animation_only else LOCALES):
            for name,orientation in (('portrait',1),('landscape',0)):
                for screen in ('hello','update'):
                    key=screen+'-'+name+'-'+locale+'-light'
                    d.shell('am','start','-W','-n',APP+'/.OnboardingPreviewActivity',
                            '--es','screen',screen,'--es','locale',locale,'--es','theme','light',
                            '--ei','orientation',orientation,'--ez','animate','false')
                    wait(lambda:'.OnboardingPreviewActivity' in focused());time.sleep(.7)
                    png=d.screenshot(args.output/(key+'.png'));width,height=struct.unpack('>II',Path(png).read_bytes()[16:24])
                    assert (width<height)==(name=='portrait')
                    nodes=[n for n in visible_nodes(tree(key)) if n.get('package')==APP and n.get('class')=='android.widget.TextView' and n.get('text')]
                    assert len(nodes)==(3 if screen=='hello' else 5),(key,[(n.get('text'),n.get('bounds')) for n in nodes])
                    rects=[node_bounds(n) for n in nodes]
                    assert all(0<=a<c<=width and 0<=b<e<=height for a,b,c,e in rects),key+' clipped text'
                    for index,(a,b,c,e) in enumerate(rects):
                        for l,t,r,v in rects[index+1:]:
                            assert min(c,r)<=max(a,l) or min(e,v)<=max(b,t),key+' overlapping text'
                    report['screens'][key]={'size':[width,height],'text':[{'value':n.get('text'),'bounds':node_bounds(n)} for n in nodes]}
                    print('PASS '+key+' visible text fits without overlap',flush=True)
                    d.shell('input','keyevent','KEYCODE_BACK');wait(lambda:'.MainActivity' in focused())
        if args.animation_only:
            d.shell('am','start','-W','-n',APP+'/.OnboardingPreviewActivity','--es','screen','hello',
                    '--es','locale','en','--es','theme','light','--ei','orientation',1,'--ez','animate','true')
            wait(lambda:'.OnboardingPreviewActivity' in focused())
            labels=[];began=time.monotonic()
            while time.monotonic()-began<8:
                root=tree('animated-'+str(len(labels)));height=node_bounds(root.find('node'))[3]
                nodes=[n for n in visible_nodes(root) if n.get('package')==APP and n.get('class')=='android.widget.TextView' and n.get('text') and node_bounds(n)[1]<height*.75]
                if nodes: labels.append(nodes[0].get('text'))
                time.sleep(.4)
            assert len(set(labels))>=2,labels
            report['animated_greetings']=labels
            d.screenshot(args.output/'animated-hello.png')
            width,height=struct.unpack('>II',(args.output/'animated-hello.png').read_bytes()[16:24])
            d.tap(width//2,height//2);wait(lambda:'.MainActivity' in focused())
            report['hello_tap_closes_prototype']=True
            print('PASS native greeting rotation and the whole-screen tap',flush=True)
        report['system_after']=system();assert report['system_after']==original_system
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_recovery_journal=str(journal))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        report['system_after_cleanup']=system()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert not report.get('recovery_error'),report.get('recovery_error')
    assert report['media_volume_zero'] and report['system_after_cleanup']==original_system


if __name__=='__main__':main()
