"""Private, multi-channel ThinkOS music source. Uses only Python's standard library."""
import argparse
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import ThreadingHTTPServer
from threading import BoundedSemaphore
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

import contract as rules
from serve import audio_url, handler, text

ORIGIN = 'https://music.163.com'
NET_ID = re.compile(r'[1-9][0-9]{0,13}')
SOURCE_ID = re.compile(r'[A-Za-z0-9_-]{1,60}')
ART = re.compile(r'p[0-9]+\.music\.126\.net/[A-Za-z0-9_=/.-]+\.(?:jpg|png)')


def official_url(url):
    u = urlsplit(url)
    if (u.scheme not in ('https', 'http') or u.username is not None or u.port is not None or
            not (u.hostname == 'music.163.com' or (u.hostname or '').endswith('.music.126.net'))):
        raise ValueError('invalid upstream address')
    return urlunsplit(('https', u.netloc, u.path, u.query, ''))


class OfficialRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, official_url(newurl))


def official(url, headers=None):
    return build_opener(OfficialRedirect()).open(Request(official_url(url), headers={"User-Agent":"ThinkOS/1.0", "Accept":"*/*", **(headers or {})}), timeout=2)


def read_json(response, limit):
    with response:
        raw = response.read(limit + 1)
    if len(raw) > limit:
        raise ValueError('metadata exceeds budget')
    return json.loads(raw)


def api(path, args):
    data = read_json(official(ORIGIN + path + '?' + urlencode(args)), 256 * 1024)
    if data.get('code') != 200:
        raise ValueError('catalogue unavailable')
    return data


def catalogue(query, base):
    results = api('/api/search/get', {'s': query, 'type': 1, 'limit': rules.MAX_TRACKS})['result']
    ids = [s['id'] for s in results.get('songs', [])[:rules.MAX_TRACKS] if NET_ID.fullmatch(str(s.get('id')))]
    details = api('/api/song/detail/', {'ids': json.dumps(ids)})['songs'] if ids else []
    by_id = {s['id']: s for s in details}
    tracks = []
    for key in ids:
        s = by_id.get(key)
        if not s or s.get('fee') not in (0, 8):
            continue
        album = s['album']
        item = dict(id='n_' + str(key), title=s['name'], artist=' / '.join(a['name'] for a in s['artists']), album=album['name'])
        if not all(text(item[k], int(k == 'title'), 240) for k in ('title','artist','album')):
            continue
        if type(s.get('duration')) is int and 0 < s['duration'] <= rules.MAX_DURATION_MS:
            item['durationMs'] = s['duration']
        url = urlsplit(album.get('picUrl') or '')
        art = url.netloc + url.path
        if ART.fullmatch(art) and len(art) < 500:
            item['art'] = base + '/art/' + art
        tracks.append(item)
    return tracks


def make_handler(base, channel, featured):
    def channel_request(path, args, key):
        data = read_json(urlopen(channel + path + ('?' + urlencode(args) if args else ''), timeout=2), rules.MAX_BODY)
        rows = data[key]
        if not isinstance(rows, list) or len(rows) > rules.MAX_TRACKS:
            raise ValueError('invalid channel response')
        return rows

    def local_tracks(query):
        rows = (channel_request(rules.SEARCH_PATH, {'q': query}, 'tracks') if query else
                channel_request(rules.BROWSE_PATH, {}, 'tracks'))
        return [dict(s, id='c_' + s['id']) for s in rows if SOURCE_ID.fullmatch(s['id']) and text(s['title'],1,240) and text(s['artist'],0,240)]

    class Source(handler({})):
        def do_GET(self):
            try:
                if len(self.path) > 4096:
                    return self.reply(400, {})
                u = urlsplit(self.path)
                args = parse_qs(u.query, strict_parsing=True, max_num_fields=1)
                if u.path in (rules.SEARCH_PATH, rules.BROWSE_PATH):
                    query = args.get('q', [''])[0] if u.path == rules.SEARCH_PATH else None
                    if query is not None and not text(query, 1, rules.QUERY_LENGTH):
                        return self.reply(400, {})
                    with ThreadPoolExecutor(max_workers=2) as pool:
                        futures = [pool.submit(catalogue, query or featured, base), pool.submit(local_tracks, query)]
                        groups = []
                        for future in futures:
                            try:
                                groups.append(future.result())
                            except (OSError, ValueError, AttributeError, KeyError, TypeError):
                                pass
                    if not groups or (len(groups) < 2 and not any(groups)):
                        return self.reply(502, {'error':'source unavailable'})
                    tracks = [g[i] for i in range(rules.MAX_TRACKS) for g in groups if i < len(g)]
                    return self.reply(200, {'tracks':tracks[:rules.MAX_TRACKS]})
                if u.path == rules.RESOLVE_PATH:
                    ids = args.get('ids',[''])[0].split(',')
                    if len(ids) > rules.MAX_TRACKS or not all(re.fullmatch(r'n_[0-9]{1,14}|c_[A-Za-z0-9_-]{1,60}', k) for k in ids):
                        return self.reply(400, {})
                    links = [dict(id=k,url=base+'/audio/'+k[2:]) for k in ids if k.startswith('n_')]
                    local = [k[2:] for k in ids if k.startswith('c_')]
                    if local:
                        try:
                            rows = channel_request(rules.RESOLVE_PATH, {'ids':','.join(local)}, 'links')
                            links.extend(dict(id='c_'+s['id'], url=s['url']) for s in rows if s['id'] in local and audio_url(s['url']))
                        except (OSError, ValueError, KeyError, TypeError):
                            if not links:
                                raise
                    return self.reply(200, {'links':links})
                if u.path.startswith('/art/') and ART.fullmatch(u.path[5:]):
                    return self.relay('https://' + u.path[5:] + '?param=512y512', False)
                if u.path.startswith('/audio/') and NET_ID.fullmatch(u.path[7:]):
                    return self.relay(ORIGIN + '/song/media/outer/url?id=' + u.path[7:] + '.mp3', True)
                return self.reply(404,{})
            except (BrokenPipeError, ConnectionResetError):
                pass
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                if not getattr(self, 'started', False):
                    self.reply(502, {'error':'source unavailable'})
                self.close_connection = True

        def end_headers(self):
            self.started = True
            super().end_headers()

        def relay(self,url,audio):
            headers = {}
            if audio and 'Range' in self.headers:
                interval = self.headers['Range']
                if not re.fullmatch(r'bytes=\d{1,10}-\d{0,10}',interval):
                    return self.reply(416,{})
                headers['Range'] = interval
            with official(url,headers) as response:
                size = int(response.headers.get('Content-Length',0))
                mime = response.headers.get_content_type()
                # NetEase serves JPEG covers with the image/jpg alias.
                if not audio and mime == 'image/jpg':
                    mime = 'image/jpeg'
                allowed = ('audio/mpeg','application/octet-stream') if audio else ('image/png','image/jpeg','image/webp')
                if mime not in allowed or not 0 < size <= ((64 if audio else 2) << 20):
                    return self.reply(404,{'error':'unavailable'})
                self.send_response(response.status)
                self.send_header('Content-Type', mime)
                for h in ('Content-Length','Content-Range','Accept-Ranges'):
                    if h in response.headers:
                        self.send_header(h,response.headers[h])
                self.end_headers()
                deadline = time.monotonic() + 90
                while size and time.monotonic() < deadline:
                    chunk = response.read(min(65536,size))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    size -= len(chunk)
    return Source


class Server(ThreadingHTTPServer):
    slots = BoundedSemaphore(12)

    def process_request(self, request, address):
        if self.slots.acquire(blocking=False):
            super().process_request(request,address)
        else:
            self.shutdown_request(request)

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request,address)
        finally:
            self.slots.release()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,required=True)
    parser.add_argument('--device-port',type=int,required=True)
    parser.add_argument('--channel',required=True,help='Trusted upstream ThinkOS source as reachable from this host')
    parser.add_argument('--featured',required=True)
    a = parser.parse_args()
    if not all(0 < p < 65536 for p in (a.port,a.device_port)) or not audio_url(a.channel) or not text(a.featured,1,rules.QUERY_LENGTH):
        parser.error('invalid source configuration')
    with Server(('127.0.0.1',a.port),make_handler('http://127.0.0.1:'+str(a.device_port),a.channel.rstrip('/'),a.featured)) as server:
        server.serve_forever()
