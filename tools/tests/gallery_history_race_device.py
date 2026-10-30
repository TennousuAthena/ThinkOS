#!/usr/bin/env python3
"""Exercise missing-image history previews and late HTTP results through the public API 3 carousel."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zlib
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory,remove_plugin
APP='moe.kiwi.thinkos'
PROBE='gallery-history-probe'


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',default='HUA0BWUT');p.add_argument('--output',type=Path,default=ROOT/'artifacts/gallery/motion/races')
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    base=['adb','-s',args.serial];gate=threading.Event();requested=threading.Event();report={'complete':False,'passed':[]};published=False
    def png(rgb):
        def chunk(key,data):return struct.pack('>I',len(data))+key+data+struct.pack('>I',zlib.crc32(key+data)&0xffffffff)
        return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+bytes(rgb)*64)*64))+chunk(b'IEND',b'')
    pictures={'/A.png':png((190,50,50)),'/B.png':png((50,190,50)),'/C.png':png((50,50,190))}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path=='/B.png':requested.set();gate.wait(15)
            body=pictures.get(self.path)
            if body is None:self.send_error(404);return
            try:
                self.send_response(200);self.send_header('Content-Type','image/png');self.send_header('Content-Length',str(len(body)));self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError,ConnectionResetError):pass
        def log_message(self,*a):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);port=server.server_port
    worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    def adb(*a):return subprocess.check_output(base+list(map(str,a)),timeout=40)
    def shell(*a):return adb('shell','-T',shlex.join(list(map(str,a))))
    def debug(cmd,val=None,kind='--es'):
        a=['am','broadcast','-n',APP+'/.DebugReceiver','--es','command',cmd]
        if val is not None:a +=[kind,'value',str(val)]
        shell(*a)
    def logs():return adb('logcat','-d','--pid='+shell('pidof',APP).decode().strip(),'-s','ThinkOS:V','AndroidRuntime:E').decode()
    def state():debug('state');return logs().split('DEBUG state=')[-1]
    def ids():return re.findall(r'\[image:'+PROBE+r'\] displayed (\S+)',logs())
    def wait(fn,message):
        until=time.monotonic()+20
        while time.monotonic()<until:
            if fn():return
            time.sleep(.2)
        raise AssertionError(message+'\n'+logs()[-3000:])
    def navigate(page):
        for _ in range(12):
            if 'page='+page+',' in state():return
            shell('input','swipe',1100,400,150,400,350);time.sleep(.5)
        raise AssertionError('Cannot navigate to '+page)
    def swipe(direction,distance=500,duration=300):shell('input','swipe',640,400+direction*distance//2,640,400-direction*distance//2,duration)
    def passed(s):report['passed'].append(s);print('PASS '+s,flush=True)
    scale=shell('settings','get','global','animator_duration_scale').strip()
    assert not shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve existing development overrides'
    assert ('tcp:'+str(port)) not in adb('reverse','--list').decode()
    adb('reverse','tcp:'+str(port),'tcp:'+str(port))
    try:
        shell('settings','put','global','animator_duration_scale',1)
        debug('orientation',0,'--ei');debug('hold_attention','AWAKE');debug('lux',20,'--ef');debug('theme','DARK')
        wait(lambda:'orientation=LANDSCAPE' in state(),'Landscape failed')
        with tempfile.TemporaryDirectory(prefix='thinkos-history-race-') as temp:
            source=Path(temp)/PROBE;shutil.copytree(ROOT/'tools/tests/fixtures'/PROBE,source)
            m=json.loads((source/'manifest.json').read_text());m['settings']['base_url']['default']='http://127.0.0.1:'+str(port)
            (source/'manifest.json').write_text(json.dumps(m))
            package,_=package_plugin(source,Path(temp)/'out');published=True;publish_directory(package,args.serial)
            time.sleep(1);navigate(PROBE+'.history');wait(lambda:ids() and ids()[-1]=='A','A did not render')
            swipe(1);assert requested.wait(3),'Missing image request did not begin'
            swipe(1);wait(lambda:ids()[-1]=='C','Second swipe did not select C while B was downloading')
            gate.set();time.sleep(1);assert ids()[-1]=='C' and 'B' not in ids(),'Late cancelled B replaced C'
            passed('a second swipe advances past a downloading image; late HTTP completion cannot replace it')
            before=len(ids());swipe(1);time.sleep(.5);assert len(ids())==before and ids()[-1]=='C'
            passed('oldest history boundary does not wrap to newest')
            before=len(ids());swipe(-1,40,500);time.sleep(.5);assert len(ids())==before and ids()[-1]=='C'
            passed('cancelled preview stays on the selected image after decoding')
            swipe(-1);wait(lambda:ids()[-1]=='B','Back to B failed');swipe(-1);wait(lambda:ids()[-1]=='A','Back to A failed')
            passed('download cancellation releases the URL lock; reverse browsing subsequently loads the same image')
            assert 'disabled=true' not in state()
            report['displayed']=ids();report['complete']=True
            (args.output/'history.png').write_bytes(adb('exec-out','screencap','-p'))
    finally:
        gate.set()
        if published:remove_plugin(PROBE,args.serial)
        adb('reverse','--remove','tcp:'+str(port))
        server.shutdown();server.server_close()
        if scale==b'null':shell('settings','delete','global','animator_duration_scale')
        else:shell('settings','put','global','animator_duration_scale',scale.decode())
        time.sleep(1);navigate('gallery.slideshow')
        owned=['cache/images/'+hashlib.sha256(('http://127.0.0.1:'+str(port)+path).encode()).hexdigest() for path in pictures]
        shell('run-as',APP,'rm','-f',*owned)
        debug('orientation');debug('hold_attention');debug('lux')
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        (args.output/'logcat.txt').write_text(logs())


if __name__=='__main__':main()
