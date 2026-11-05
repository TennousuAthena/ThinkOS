#!/usr/bin/env python3
"""APP-2: real foreign activity, launch denial, video/mirror departure, cast return and audio-aware timeout.

Requires the prerequisites in apps_foreign_preflight.py. No persistent module,
Device Owner/default-home change, reboot, public source or performance sampling.
All local playback is silent or runs with the required system media volume zero.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sys
import tempfile
import threading
import time
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/media'),str(ROOT/'tools/cast')]
from device_external_fixture import fixture_apk,PACKAGE,DENIED_PACKAGE
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,Handler as IcyHandler,silent_mp3
from cast_dlna_device import soap,AVT,DIDL
from wfd_source import WfdSource


class Handler(IcyHandler):
    def do_GET(self):
        if self.path.split('?')[0]!='/video.mp4':return super().do_GET()
        body=self.server.video;start=0
        header=self.headers.get('Range','')
        if header:
            match=re.fullmatch(r'bytes=(\d+)-',header)
            if not match or int(match[1])>=len(body):
                self.send_response(416);self.send_header('Content-Length','0');self.end_headers();return
            start=int(match[1])
        self.send_response(206 if header else 200)
        self.send_header('Content-Type','video/mp4');self.send_header('Accept-Ranges','bytes')
        self.send_header('Content-Length',str(len(body)-start))
        if header:self.send_header('Content-Range',f'bytes {start}-{len(body)-1}/{len(body)}')
        self.end_headers()
        try:self.wfile.write(body[start:])
        except (BrokenPipeError,ConnectionResetError):pass


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-2/device')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    apks={PACKAGE:fixture_apk(),DENIED_PACKAGE:fixture_apk(denied=True)}
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert APP+'/' in focused()
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert broadcast_json(d,'media_state')['status']=='stopped'
    for package in apks:assert not d.shell('pm','path',package,check=False).strip(),'Preserve an existing fixture installation'
    assert b'tcp:8892' not in d.adb('reverse','--list')
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip()
    original_stay=d.shell('settings','get','global','stay_on_while_plugged_in').strip()
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    recovery=Path(tempfile.mkdtemp(prefix='thinkos-foreign-functional-recovery-'))
    with os.fdopen(os.open(recovery/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump({'physical':'HUA0BWUT','app_journal':str(journal),'stay_on_while_plugged_in':original_stay,'owned_packages':list(apks),'reverse':'tcp:8892'},stream)
        stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(recovery)})+'\n')
    print('Private recovery journals: '+str(journal)+' '+str(recovery),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'passed':[],'apk_sha256':d.shell('sha256sum',installed).split()[0]}
    owned=[];station=None;reverse=False;source=None;source_thread=None;d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('External-app function did not reach the expected state')
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def fixture(command='state',**extras):
        argv=['am','broadcast','-n',PACKAGE+'/.FixtureReceiver','--es','command',command]
        for key,value in extras.items():argv += ['--ez' if isinstance(value,bool) else '--ei' if isinstance(value,int) else '--es',key,str(value).lower() if isinstance(value,bool) else str(value)]
        raw=d.shell(*argv);match=re.search(r'data="(\{.*\})"',raw);assert match,raw
        state=json.loads(match[1]);assert state;return state
    def tree(name,windows=False):
        remote='/sdcard/thinkos-foreign-functional.xml'
        try:
            dump_hierarchy(args.serial,remote,compressed=True,all_windows=windows)
            raw=d.shell('cat',remote);(args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def find(label):
        found=[n for n in visible_nodes(tree('last-ui')) if label in (n.get('text'),n.get('content-desc'))]
        return max(found,key=lambda n:(n.get('clickable')=='true',n.get('text')==label),default=None)
    def tap(node):
        a,b,c,e=node_bounds(node);assert a<c and b<e;d.tap((a+c)//2,(b+e)//2)
    def page(key):d.debug('page',value=key);return broadcast_json(d,'ui_state')['page']==key
    def locked():return 'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')
    def home(name):
        root=tree(name+'-windows',True)
        button=next((n for n in root.iter('node') if n.get('resource-id')=='com.android.systemui:id/home' and n.get('enabled')=='true'),None)
        assert button is not None;tap(button);wait(lambda:APP+'/' in focused());wait(locked)
    def open_fixture():
        wait(lambda:page('apps.main'));tap(wait(lambda:find('外部应用测试')));wait(lambda:PACKAGE+'/' in focused())
    def settings(key,value):d.debug('setting',key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    def tasks(name):
        raw=d.shell('dumpsys','activity','activities');(args.output/(name+'-activities.txt')).write_text(raw)
        return sorted(set(re.findall(r'Hist #\d+: ActivityRecord\{[^\n]* '+re.escape(APP)+r'/\.MainActivity t(\d+)',raw)))
    def launch_from_media():
        # Exercise the real OS departure path while keeping the visible media
        # page intact. This DUMP fixture does not claim to test plugin grants.
        d.debug('foreign_launch',value=PACKAGE);wait(lambda:PACKAGE+'/' in focused())
    def description_url(ip):
        # CastHost binds its control listener to the private Wi-Fi address,
        # not loopback. Discover the actual port like a LAN controller does.
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as search:
            search.settimeout(.5)
            search.sendto(b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 1\r\nST: upnp:rootdevice\r\n\r\n',(ip,1900))
            until=time.monotonic()+4
            while time.monotonic()<until:
                try:raw,peer=search.recvfrom(4096)
                except socket.timeout:continue
                if peer[0]!=ip:continue
                match=re.search(r'(?im)^LOCATION:\s*(\S+)',raw.decode('ascii','replace'))
                if not match:continue
                url=match[1];parsed=urllib.parse.urlsplit(url)
                assert parsed.scheme=='http' and parsed.hostname==ip and parsed.path=='/dlna/description.xml'
                assert parsed.username is None and parsed.password is None and 0<parsed.port<65536
                return url
        raise AssertionError('The private DLNA renderer did not answer SSDP')
    def mirror_end():
        nonlocal source,source_thread
        if source:source.stop();source.close()
        if source_thread:source_thread.join(8)
        source=source_thread=None
    try:
        for package,apk in apks.items():
            assert 'Success' in d.adb('install',str(apk),timeout=120).decode();owned.append(package)
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        for key,value in (('apps.android',True),('apps.hidden',''),('apps.return_after_s',0),('panel.enabled',True),('kiosk.lock',True),('cast.enabled',True)):settings(key,value)
        report['shell_module_present']=bool(d.shell('su','-c','ls -d /data/adb/modules/thinkos-shell 2>/dev/null',check=False).strip())
        assert not report['shell_module_present'],'This run must exercise absence of the shell module'
        open_fixture();fixture('ignore_back',value=True);d.shell('input','keyevent','KEYCODE_BACK')
        check('An ordinary launcher entry can ignore Back while Home remains available',PACKAGE+'/' in focused())
        d.shell('settings','put','global','stay_on_while_plugged_in','0')
        home('stay-on')
        check('The existing Device Owner applies stay-on for AC, USB and wireless power',d.shell('settings','get','global','stay_on_while_plugged_in').strip()=='7')
        wait(lambda:page('apps.main'));tap(wait(lambda:find('拒绝启动测试')))
        wait(lambda:find('无法打开应用'))
        check('Android launch refusal reaches the awaiting plugin and its native error notice',APP+'/' in focused() and broadcast_json(d,'ui_state')['page']=='apps.main')
        check('A refused launch clears the foreign exemption and relocks',not broadcast_json(d,'kiosk_state')['foreign'] and locked())
        check('The refused Activity never starts',DENIED_PACKAGE+'/' not in focused())
        with tempfile.TemporaryDirectory(prefix='thinkos-foreign-media-') as work:
            station=Station(8892,silent_mp3(work));station.RequestHandlerClass=Handler;station.video=(apks[PACKAGE].parent/'assets/silent.mp4').read_bytes()
            threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:8892','tcp:8892');reverse=True
            d.load('http://127.0.0.1:8892/video.mp4',kind='video',repeat='all',title='Silent departure video')
            wait(lambda:page('player.video'));wait(lambda:d.state()['status']=='playing' and d.state()['videoWidth']>0)
            launch_from_media()
            check('Launching a foreign application pauses visible ordinary video',d.state()['status']=='paused' and d.state()['kind']=='video' and d.state()['count']==1)
            home('after-video');d.debug('media_stop')
            device_ip=re.search(r'inet (\d+\.\d+\.\d+\.\d+)',d.shell('ip','-4','addr','show','wlan0'))[1]
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as route:
                route.connect((device_ip,9));mac_ip=route.getsockname()[0]
            source=WfdSource(0,pattern='solid',log=lambda message:None);source.port=source.listener.getsockname()[1]
            assert 'anullsrc=r=48000:cl=stereo' in source._media_command(),'The solid mirror fixture must remain silent'
            source_thread=threading.Thread(target=lambda:source.serve(120),daemon=True);source_thread.start()
            d.debug('miracast_dial',host=mac_ip,port=source.port)
            wait(lambda:d.state()['status']=='playing' and d.state()['mirror'] and d.state()['videoWidth']>0,seconds=30)
            check('A real post-pairing Miracast fixture plays through MirrorSource',d.state()['owner']=='cast:miracast')
            launch_from_media();wait(lambda:d.state()['status']=='stopped')
            check('Launching the foreign app stops a mirror rather than leaving it paused',not d.state()['mirror'] and d.state()['count']==0)
            check('The mirror RTSP/RTP sender sees the transport end',source.ended.wait(15))
            mirror_end();home('after-mirror')
            open_fixture();before=tasks('before-cast');task_id=broadcast_json(d,'kiosk_state')['taskId']
            location=description_url(device_ip);base=location.rsplit('/dlna/',1)[0];url='http://127.0.0.1:8892/video.mp4'
            report['dlna_description_url']=location
            with urllib.request.urlopen(location,timeout=10) as response:description=ET.fromstring(response.read(65536))
            ns={'d':'urn:schemas-upnp-org:device-1-0'}
            control=next(s.find('d:controlURL',ns).text for s in description.findall('.//d:service',ns) if s.find('d:serviceType',ns).text==AVT)
            assert control.startswith('/dlna/')
            status,_=soap(base,control,AVT,'SetAVTransportURI',CurrentURI=url,CurrentURIMetaData=DIDL%url)
            check('The local DLNA request is accepted while the foreign app is in front',status==200)
            wait(lambda:APP+'/' in focused());wait(locked)
            check('A real pushed cast returns to the stored task without creating an Activity',broadcast_json(d,'kiosk_state')['taskId']==task_id and tasks('after-cast')==before)
            soap(base,control,AVT,'Play',Speed=1)
            wait(lambda:d.state()['status']=='playing' and d.state()['owner']=='cast:dlna')
            check('The returned cast page is visible and Lock Task is restored',broadcast_json(d,'ui_state')['page']=='cast.video' and locked())
            soap(base,control,AVT,'Stop');d.debug('media_stop')
            settings('apps.return_after_s',60)
            open_fixture();fixture('audio_start');wait(lambda:fixture()['musicActive'])
            began=time.monotonic();next_note=0
            while time.monotonic()-began<95:
                elapsed=time.monotonic()-began
                assert PACKAGE+'/' in focused() and fixture()['musicActive']
                if elapsed>=next_note:
                    print('WAIT silent foreign audio '+str(int(elapsed))+'s',flush=True);next_note+=25
                time.sleep(2)
            check('Silent real audio activity postpones the 60-second foreign timeout for 95 seconds',PACKAGE+'/' in focused())
            fixture('audio_stop');ended=time.monotonic()
            wait(lambda:APP+'/' in focused(),seconds=95);wait(locked)
            report['audio_active_seconds']=time.monotonic()-began-(time.monotonic()-ended)
            report['return_after_audio_stops_seconds']=time.monotonic()-ended
            check('After audio stops the real watchdog returns and relocks without the shell module',not broadcast_json(d,'kiosk_state')['foreign'] and locked())
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png');tree('failure',True)
        except Exception:pass
        raise
    finally:
        cleanup=[]
        try:mirror_end()
        except Exception as error:cleanup.append('mirror: '+str(error))
        try:d.debug('media_stop')
        except Exception as error:cleanup.append('media: '+str(error))
        if station:station.close()
        for enabled,command,port in ((reverse,'reverse','tcp:8892'),):
            if enabled:
                try:d.adb(command,'--remove',port)
                except Exception as error:cleanup.append(command+': '+str(error))
        for package in owned:
            try:d.shell('am','force-stop',package);assert 'Success' in d.adb('uninstall',package,timeout=60).decode()
            except Exception as error:cleanup.append('fixture: '+str(error))
        try:
            if original_stay=='null':d.shell('settings','delete','global','stay_on_while_plugged_in')
            else:d.shell('settings','put','global','stay_on_while_plugged_in',original_stay)
            report['stay_on_restored']=d.shell('settings','get','global','stay_on_while_plugged_in').strip()==original_stay
            assert report['stay_on_restored']
        except Exception as error:cleanup.append('system: '+str(error))
        try:report.update(restore(journal,d))
        except Exception as error:cleanup.append('app: '+str(error))
        report['fixture_packages_removed']=all(not d.shell('pm','path',p,check=False).strip() for p in apks)
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        if cleanup:report.update(cleanup_errors=cleanup,private_app_journal=str(journal),private_system_journal=str(recovery))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not cleanup and report['fixture_packages_removed']:shutil.rmtree(recovery)
    assert not cleanup and report['fixture_packages_removed'] and report['media_volume_zero']


if __name__=='__main__':main()
