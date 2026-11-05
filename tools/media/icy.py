#!/usr/bin/env python3
"""Local silent ICY station for APP-3. No public radio service or media download.

Each stream changes StreamTitle and can deliberately close once for reconnect
tests. Bind stays on loopback; device tests reach it through their own adb reverse.
"""
import argparse
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import subprocess
import tempfile
import threading
import time

METAINT=4096


def silent_mp3(directory):
    path=Path(directory)/'silence.mp3'
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i',
        'anullsrc=r=44100:cl=stereo','-t','2','-c:a','libmp3lame','-b:a','64k',
        '-write_xing','0','-id3v2_version','0','-y',str(path)],check=True,timeout=30)
    data=path.read_bytes()
    assert 0<len(data)<32768
    return data


def metadata(title):
    # Fixture titles are controlled literals, not sender-provided strings.
    body=("StreamTitle='"+title+"';").encode('utf-8')
    blocks=(len(body)+15)//16
    if blocks>255:raise ValueError('ICY metadata is too long')
    return bytes([blocks])+body.ljust(blocks*16,b'\0')


class Station(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,port,audio,disconnect_once=False):
        super().__init__(('127.0.0.1',port),Handler)
        self.audio=audio;self.disconnect_once=disconnect_once
        self.requests=[];self.lock=threading.Lock();self.stopping=threading.Event()
        self.stream_connections=0;self.drop_before=1 if disconnect_once else 0
        self.dropped=0;self.outage_until=0

    def close(self):
        self.stopping.set();self.shutdown();self.server_close()

    def seen(self):
        with self.lock:return list(self.requests)


class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def do_GET(self):
        path=self.path.split('?')[0]
        if path in ('/list.m3u','/list.pls'):
            port=self.server.server_port
            if path.endswith('.pls'):
                body=f'[playlist]\nTitle2=PLS Two\nFile2=http://127.0.0.1:{port}/stream.mp3?pls=two\nTitle1=PLS One\nFile1=http://127.0.0.1:{port}/stream.mp3?pls=one\nNumberOfEntries=2\nVersion=2\n'
            else:
                body=f'#EXTM3U\n#EXTINF:-1,Local One\nhttp://127.0.0.1:{port}/stream.mp3?one\n#EXTINF:-1,Local Two\nhttp://127.0.0.1:{port}/stream.mp3?two\n'
            data=body.encode();self.send_response(200);self.send_header('Content-Type','audio/x-mpegurl')
            self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if self.path.split('?')[0]=='/plain.mp3':
            self.send_response(200);self.send_header('Content-Type','audio/mpeg')
            self.send_header('Content-Length',str(len(self.server.audio)));self.end_headers()
            try:self.wfile.write(self.server.audio)
            except (BrokenPipeError,ConnectionResetError):pass
            return
        if self.path.split('?')[0]!='/stream.mp3':
            self.send_response(404);self.send_header('Content-Length','0');self.end_headers();return
        with self.server.lock:
            number=self.server.stream_connections;self.server.stream_connections+=1
            self.server.requests.append({'path':self.path,'metadata':self.headers.get('Icy-MetaData'),
                'range':self.headers.get('Range'),'at':time.time()})
        if time.monotonic()<self.server.outage_until:
            self.send_response(503);self.send_header('Content-Length','0');self.end_headers();return
        self.send_response(200)
        for key,value in (('Content-Type','audio/mpeg'),('icy-name','ThinkOS local station'),
                          ('icy-br','64'),('icy-metaint',str(METAINT)),('Connection','close')):
            self.send_header(key,value)
        self.end_headers();self.close_connection=True;self.connection.settimeout(5)
        audio=self.server.audio;position=0;began=time.monotonic();sent=0
        try:
            while not self.server.stopping.is_set() and time.monotonic()-began<180:
                chunk=bytearray()
                while len(chunk)<METAINT:
                    part=audio[position:position+METAINT-len(chunk)]
                    chunk.extend(part);position=(position+len(part))%len(audio)
                self.wfile.write(chunk)
                if self.headers.get('Icy-MetaData')=='1':
                    self.wfile.write(metadata('Track One' if sent<4 else 'Track Two'))
                self.wfile.flush();sent+=1
                if number<self.server.drop_before and sent==12:
                    with self.server.lock:self.server.dropped+=1
                    return
                self.server.stopping.wait(METAINT/8000)
        except (BrokenPipeError,ConnectionResetError,TimeoutError):pass


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8891)
    parser.add_argument('--disconnect-once',action='store_true')
    args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='thinkos-icy-station-') as directory:
        station=Station(args.port,silent_mp3(directory),args.disconnect_once)
        print('Silent local ICY stream: http://127.0.0.1:'+str(station.server_port)+'/stream.mp3',flush=True)
        try:station.serve_forever()
        except KeyboardInterrupt:pass
        finally:station.stopping.set();station.server_close()


if __name__=='__main__':main()
