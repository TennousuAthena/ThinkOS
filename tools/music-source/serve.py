#!/usr/bin/env python3
"""Serve a configured catalogue of audio links over the ThinkOS source API."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit

import contract as rules

ID = re.compile(r'[a-zA-Z0-9_-]+\Z')
CONTROL = re.compile(r'[\x00-\x1f\x7f]')

OPTIONAL = {'album', 'art', 'durationMs'}
REQUIRED = {'id', 'title', 'artist', 'url'}


def track_metadata(item):
    return {key: value for key, value in item.items() if key != 'url'}


def text(value, minimum, maximum):
    return isinstance(value, str) and minimum <= len(value.encode('utf-8')) <= maximum and not CONTROL.search(value)


def audio_url(value):
    if not text(value, 1, rules.URL_LENGTH) or not value.isascii() or re.search(r'[\s#\\]', value):
        return False
    url = urlsplit(value)
    return url.scheme in ('http', 'https') and bool(url.hostname) and url.username is None and url.port != 0


def load_catalog(path):
    with path.open('rb') as file:
        raw = file.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('catalogue exceeds 1 MiB')
    tracks = json.loads(raw)
    if not isinstance(tracks, list) or len(tracks) > 4096:
        raise ValueError('expected up to 4096 catalogue entries')
    indexed = {}
    for number, item in enumerate(tracks, start=1):
        try:
            if not isinstance(item, dict) or not REQUIRED <= item.keys() <= REQUIRED | OPTIONAL:
                raise ValueError()
            key = item['id']
            if not text(key, 1, rules.ID_LENGTH) or not ID.fullmatch(key) or key in indexed:
                raise ValueError()
            if not text(item['title'], 1, rules.TITLE_LENGTH) or not text(item['artist'], 0, rules.ARTIST_LENGTH):
                raise ValueError()
            if 'album' in item and not text(item['album'], 0, rules.ALBUM_LENGTH):
                raise ValueError()
            if 'art' in item and (not audio_url(item['art']) or len(item['art']) > rules.ART_LENGTH):
                raise ValueError()
            if 'durationMs' in item and (type(item['durationMs']) is not int or not 1 <= item['durationMs'] <= rules.MAX_DURATION_MS):
                raise ValueError()
            if not audio_url(item['url']):
                raise ValueError()
        except (ValueError, TypeError):
            raise ValueError(f'invalid catalogue entry {number}') from None
        indexed[key] = item
    return indexed


def handler(tracks):
    class Source(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(rules.TIMEOUT_MS / 1000)
            super().setup()

        def log_message(self, *_args):
            pass  # queries and playback links stay out of access logs

        def reply(self, code, data):
            body = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
            if len(body) > rules.MAX_BODY:
                code, body = 500, b'{"error":"response too large"}'
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            try:
                if len(self.path) > 4096 or not self.path.startswith('/'):
                    raise ValueError()
                request = urlsplit(self.path)
                args = parse_qs(request.query, keep_blank_values=True, max_num_fields=1, errors='strict')
                if request.path == rules.BROWSE_PATH and not args:
                    self.reply(200, {'tracks': [track_metadata(item) for item in list(tracks.values())[:rules.MAX_TRACKS]]})
                elif request.path == rules.SEARCH_PATH:
                    query, = args.get('q', ())
                    if not text(query.strip(), 1, rules.QUERY_LENGTH):
                        raise ValueError()
                    query = query.strip().casefold()
                    results = []
                    for item in tracks.values():
                        if query in ' '.join((item['title'], item['artist'], item.get('album', ''))).casefold():
                            results.append(track_metadata(item))
                            if len(results) == rules.MAX_TRACKS:
                                break
                    self.reply(200, {'tracks': results})
                elif request.path == rules.RESOLVE_PATH:
                    key_list, = args.get('ids', ())
                    ids = key_list.split(',')
                    if len(ids) > rules.MAX_TRACKS or len(set(ids)) != len(ids) or any(
                        not text(key, 1, rules.ID_LENGTH) or not ID.fullmatch(key) for key in ids
                    ):
                        raise ValueError()
                    self.reply(200, {'links': [{'id': key, 'url': tracks[key]['url']} for key in ids if key in tracks]})
                else:
                    self.reply(404, {'error': 'unknown endpoint'})
            except (ValueError, UnicodeError):
                self.reply(400, {'error': 'invalid request'})
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

    return Source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--bind', default='127.0.0.1', type=ipaddress.IPv4Address)
    parser.add_argument('--port', default=8765, type=int)
    args = parser.parse_args()
    try:
        if not 1 <= args.port <= 65535 or not (args.bind.is_private and not args.bind.is_unspecified):
            raise ValueError('bind to loopback or a private IPv4 address')
        tracks = load_catalog(args.catalog)
    except (OSError, ValueError) as error:
        parser.error(str(error) if not isinstance(error, json.JSONDecodeError) else 'invalid catalogue JSON')
    with HTTPServer((str(args.bind), args.port), handler(tracks)) as server:
        print(f'ThinkOS music source listening on {args.bind}:{args.port}', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
