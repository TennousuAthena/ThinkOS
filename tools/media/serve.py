#!/usr/bin/env python3
"""ThinkOS 流媒体测试源站（仅标准库，线程化）。

    python3 -I tools/media/serve.py [--root /tmp/thinkos-media-samples] [--port 8099] [--bind 0.0.0.0]

样片由 tools/media/gen_samples.sh 生成。设备端用 http://<Mac 的局域网 IP>:8099/... 访问，URL 速查见 README.md。
HLS / DASH 点播由 ffmpeg 在首次请求时切片并缓存到 <root>/.cache（以点开头，不会被 /files 暴露）；
直播由后台 ffmpeg 产生，首个请求时启动，60 秒无请求自动结束，服务退出时一并结束。

路径修饰符可以叠加，写在 /files/<name> 之前：
    /auth/ /referer/ /ua/ /redirect/<n>/ /slow/<kbps>/ /flaky/<bytes>/
例如 /redirect/3/slow/800/files/video-h264-720p30.mp4
"""
import argparse
import atexit
import http.client
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

TEST_TOKEN = "thinkos-test"
REFERER_OK = "https://example.test/"
UA_OK = "ThinkOS-Test/1.0"
KEY_BYTES = b"thinkos-test-key"  # 16 字节，AES-128 测试密钥
KEY_IV = "0123456789abcdef0123456789abcdef"
MAX_REDIRECTS = 6
LIVE_IDLE_SECONDS = 60
LIVE_READY_TIMEOUT = 25
LIVE_MAX_RUNTIME = 3600  # ffmpeg -t：服务被 kill -9 时孤儿进程最迟 1 小时后自行结束
FFMPEG = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg"
FFPROBE = os.environ.get("FFPROBE") or shutil.which("ffprobe") or "ffprobe"

MIME = {
    ".mp4": "video/mp4", ".m4v": "video/mp4", ".m4a": "audio/mp4", ".mov": "video/quicktime", ".3gp": "video/3gpp",
    ".mkv": "video/x-matroska", ".mka": "audio/x-matroska", ".webm": "video/webm",
    ".ts": "video/mp2t", ".mpg": "video/mpeg", ".avi": "video/x-msvideo", ".flv": "video/x-flv",
    ".wmv": "video/x-ms-wmv", ".wma": "audio/x-ms-wma",
    ".mp3": "audio/mpeg", ".aac": "audio/aac", ".flac": "audio/flac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
    ".wav": "audio/wav", ".ac3": "audio/ac3", ".eac3": "audio/eac3", ".dts": "audio/vnd.dts", ".amr": "audio/amr",
    ".srt": "application/x-subrip", ".vtt": "text/vtt; charset=utf-8", ".lrc": "text/plain; charset=utf-8",
    ".jpg": "image/jpeg", ".png": "image/png", ".tsv": "text/tab-separated-values; charset=utf-8",
    ".m3u8": "application/vnd.apple.mpegurl", ".m3u": "audio/x-mpegurl", ".mpd": "application/dash+xml",
    ".m4s": "video/iso.segment", ".bin": "application/octet-stream",
}

_log_lock = threading.Lock()


def log(msg):
    with _log_lock:
        print(msg, file=sys.stderr, flush=True)


def clean(s):
    return re.sub(r"[^\x20-\x7e]", "?", s)


def mime_for(name):
    if name.endswith(".lrc") and "gbk" in name:
        return "text/plain"  # 故意不带 charset，测试客户端编码探测
    return MIME.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def safe_file(base, *parts):
    """base 下的普通文件；任何以点开头 / 含分隔符 / .. 的片段都视为不存在。"""
    p = Path(base)
    for part in parts:
        if not part or part.startswith(".") or "/" in part or "\\" in part or "\0" in part:
            return None
        p = p / part
    try:
        p.resolve().relative_to(Path(base).resolve())
    except ValueError:
        return None
    return p if p.is_file() else None


def host_only(host):
    if host.startswith("["):
        return host[: host.find("]") + 1]
    return host.split(":")[0]


# ----------------------------------------------------------------------------- ffmpeg 构建


class BuildError(Exception):
    pass


def run_ffmpeg(cmd, what, timeout=240):
    try:
        r = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise BuildError(f"{what}: ffmpeg timed out after {timeout}s")
    except FileNotFoundError:
        raise BuildError(f"{what}: ffmpeg not found ({FFMPEG})")
    if r.returncode != 0:
        raise BuildError(f"{what}: ffmpeg exit {r.returncode}\n{r.stderr[-1500:]}")


def probe_streams(src):
    """(有视频, 有音频)；封面图（attached_pic）不算视频。"""
    try:
        r = subprocess.run(
            [FFPROBE, "-v", "error", "-show_entries", "stream=codec_type:stream_disposition=attached_pic", "-of", "json", str(src)],
            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30,
        )
        streams = json.loads(r.stdout or "{}").get("streams", [])
    except (subprocess.SubprocessError, ValueError, FileNotFoundError) as e:
        raise BuildError(f"ffprobe failed for {src.name}: {e}")
    has_v = any(s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic") for s in streams)
    has_a = any(s.get("codec_type") == "audio" for s in streams)
    return has_v, has_a


LADDER = [("v720", 1280, 720, "1800k", "2200k", "4400k"), ("v1080", 1920, 1080, "3000k", "3600k", "7200k")]
FILTER_COMPLEX = (
    "[0:v:0]split=2[s0][s1];"
    + ";".join(
        f"[s{i}]scale=w={w}:h={h}:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p[o{i}]"
        for i, (_, w, h, *_rest) in enumerate(LADDER)
    )
)


def ladder_encode_args():
    args = ["-c:v", "libx264", "-preset", "veryfast", "-profile:v", "high",
            "-force_key_frames", "expr:gte(t,n_forced*4)", "-sc_threshold", "0"]
    for i, (_, _, _, br, mr, bs) in enumerate(LADDER):
        args += [f"-b:v:{i}", br, f"-maxrate:v:{i}", mr, f"-bufsize:v:{i}", bs]
    return args + ["-c:a", "aac", "-b:a", "128k", "-ac", "2"]


def prefix_playlist(text, prefix):
    """把变体播放列表里的相对 URI 加上目录前缀；以 / 或 http 开头的（如密钥）保持不变。"""
    def fix(u):
        return u if u.startswith(("/", "http:", "https:")) else prefix + u

    out = []
    for line in text.splitlines():
        if not line.strip():
            out.append(line)
        elif line.startswith("#"):
            out.append(re.sub(r'URI="([^"]+)"', lambda m: f'URI="{fix(m.group(1))}"', line))
        else:
            out.append(fix(line))
    return "\n".join(out) + "\n"


class Builder:
    """按需生成并缓存 HLS / HLS-fMP4 / HLS-AES / DASH 点播输出。"""

    def __init__(self, root, cache):
        self.root, self.cache = Path(root), Path(cache)
        self._guard = threading.Lock()
        self._locks = {}

    def ensure(self, kind, name):
        src = safe_file(self.root, name)
        if src is None:
            raise FileNotFoundError(name)
        with self._guard:
            lock = self._locks.setdefault((kind, name), threading.Lock())
        out = self.cache / kind / name
        with lock:
            done = out / ".done"
            if done.exists() and done.stat().st_mtime >= src.stat().st_mtime:
                return out
            tmp = self.cache / kind / (name + ".tmp")
            shutil.rmtree(tmp, ignore_errors=True)
            tmp.mkdir(parents=True)
            t0 = time.monotonic()
            try:
                if kind == "dash":
                    self._build_dash(src, tmp)
                else:
                    self._build_hls(kind, src, tmp)
            except BuildError:
                shutil.rmtree(tmp, ignore_errors=True)
                raise
            (tmp / ".done").touch()
            shutil.rmtree(out, ignore_errors=True)
            tmp.rename(out)
            log(f"{time.strftime('%H:%M:%S')} built {kind}/{name} in {time.monotonic() - t0:.1f}s")
            return out

    def _base_cmd(self, src):
        return [FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(src)]

    def _build_hls(self, kind, src, d):
        has_v, has_a = probe_streams(src)
        if not (has_v or has_a):
            raise BuildError(f"{src.name}: no audio or video stream")
        cmd = self._base_cmd(src)
        if has_v:
            names = [n for n, *_ in LADDER]
            audio = ["-map", "0:a:0"] if has_a else []
            cmd += ["-filter_complex", FILTER_COMPLEX, "-map", "[o0]"] + audio + ["-map", "[o1]"] + audio + ladder_encode_args()
            vsm = " ".join(f"v:{i}" + (f",a:{i}" if has_a else "") + f",name:{n}" for i, n in enumerate(names))
        else:
            names = ["a0"]
            cmd += ["-map", "0:a:0", "-c:a", "aac", "-b:a", "128k", "-ac", "2"]
            vsm = "a:0,name:a0"
        for n in names:
            (d / n).mkdir()
        seg_ext = "m4s" if kind == "hls-fmp4" else "ts"
        cmd += ["-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
                "-hls_segment_filename", str(d / "%v" / f"seg_%05d.{seg_ext}"),
                "-master_pl_name", "master.m3u8", "-var_stream_map", vsm]
        if kind == "hls-fmp4":
            cmd += ["-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4"]
        if kind == "hls-aes":
            keydir = self.cache / "keys"
            keydir.mkdir(exist_ok=True)
            (keydir / "key.bin").write_bytes(KEY_BYTES)
            (keydir / "key.info").write_text(f"/hls-aes/key.bin\n{keydir / 'key.bin'}\n{KEY_IV}\n")
            cmd += ["-hls_key_info_file", str(keydir / "key.info")]
        cmd.append(str(d / "%v" / "stream.m3u8"))
        run_ffmpeg(cmd, f"{kind}/{src.name}")
        # 单码率入口：把 720p（或音频）变体的播放列表搬到顶层
        first = d / names[0] / "stream.m3u8"
        (d / "index.m3u8").write_text(prefix_playlist(first.read_text(), names[0] + "/"))

    def _build_dash(self, src, d):
        has_v, has_a = probe_streams(src)
        if not (has_v or has_a):
            raise BuildError(f"{src.name}: no audio or video stream")
        cmd = self._base_cmd(src)
        if has_v:
            cmd += ["-filter_complex", FILTER_COMPLEX, "-map", "[o0]", "-map", "[o1]"]
            if has_a:
                cmd += ["-map", "0:a:0"]
            cmd += ladder_encode_args()
            sets = "id=0,streams=v id=1,streams=a" if has_a else "id=0,streams=v"
        else:
            cmd += ["-map", "0:a:0", "-c:a", "aac", "-b:a", "128k", "-ac", "2"]
            sets = "id=0,streams=a"
        cmd += ["-f", "dash", "-seg_duration", "4", "-use_template", "1", "-use_timeline", "1",
                "-adaptation_sets", sets, str(d / "manifest.mpd")]
        run_ffmpeg(cmd, f"dash/{src.name}")


class LiveStream:
    """后台 ffmpeg 直播流：首个请求启动，LIVE_IDLE_SECONDS 无请求后结束。"""

    def __init__(self, kind, root, cache, source):
        self.kind, self.root, self.source = kind, Path(root), source
        self.dir = Path(cache) / f"live-{kind}"
        self.logfile = Path(cache) / f"live-{kind}.log"
        self.lock = threading.Lock()
        self.proc = None
        self.ready_pid = None
        self.last = time.monotonic()

    def touch(self):
        self.last = time.monotonic()

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def ensure(self):
        with self.lock:
            self.touch()
            if not self.running():
                self._start()
            proc = self.proc
        if self.ready_pid != proc.pid:
            self._wait_ready(proc)
            self.ready_pid = proc.pid
        self.touch()
        return self.dir

    def _command(self, src):
        cmd = [FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
               "-re", "-stream_loop", "-1", "-i", str(src), "-t", str(LIVE_MAX_RUNTIME),
               "-map", "0:v:0", "-map", "0:a:0",
               "-vf", "scale=w=1280:h=720:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p",
               "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-b:v", "1500k", "-maxrate", "1800k", "-bufsize", "3600k",
               "-force_key_frames", "expr:gte(t,n_forced*2)", "-sc_threshold", "0",
               # -stream_loop cuts the audio at every loop (two AAC frames, 43 ms, per 12 s clip); ExoPlayer's audio sink calls a
               # hole of more than 200 ms an unexpected discontinuity and drops video frames. async=1 fills the holes with silence.
               "-af", "aresample=async=1",
               "-c:a", "aac", "-b:a", "96k", "-ac", "2"]
        if self.kind == "hls":
            cmd += ["-f", "hls", "-hls_time", "2", "-hls_list_size", "6",
                    "-hls_flags", "delete_segments+independent_segments+program_date_time",
                    "-hls_segment_filename", str(self.dir / "seg_%05d.ts"), str(self.dir / "index.m3u8")]
        else:
            cmd += ["-f", "dash", "-seg_duration", "2", "-window_size", "5", "-extra_window_size", "2",
                    "-remove_at_exit", "1", "-use_template", "1", "-use_timeline", "1",
                    "-adaptation_sets", "id=0,streams=v id=1,streams=a", str(self.dir / "manifest.mpd")]
        return cmd

    def _start(self):
        src = safe_file(self.root, self.source)
        if src is None:
            raise BuildError(f"live source {self.source} not found in {self.root}")
        shutil.rmtree(self.dir, ignore_errors=True)
        self.dir.mkdir(parents=True)
        self.ready_pid = None
        with open(self.logfile, "wb") as lf:
            self.proc = subprocess.Popen(self._command(src), stdin=subprocess.DEVNULL, stdout=lf, stderr=lf, start_new_session=True)
        log(f"{time.strftime('%H:%M:%S')} live-{self.kind}: started ffmpeg pid {self.proc.pid} (source {self.source})")

    def _ready(self):
        if self.kind == "hls":
            pl = self.dir / "index.m3u8"
            return pl.exists() and pl.read_text(errors="replace").count("#EXTINF") >= 2
        return (self.dir / "manifest.mpd").exists() and all(
            (self.dir / f"chunk-stream{i}-00002.m4s").exists() for i in (0, 1)
        )

    def _wait_ready(self, proc):
        deadline = time.monotonic() + LIVE_READY_TIMEOUT
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise BuildError(f"live-{self.kind}: ffmpeg exited with {proc.returncode}, see {self.logfile}")
            try:
                if self._ready():
                    return
            except OSError:
                pass
            time.sleep(0.2)
        raise BuildError(f"live-{self.kind}: not ready after {LIVE_READY_TIMEOUT}s, see {self.logfile}")

    def stop(self, reason="stopped"):
        with self.lock:
            proc, self.proc = self.proc, None
        if proc is None or proc.poll() is not None:
            return
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=5)
        except (ProcessLookupError, PermissionError):
            pass
        shutil.rmtree(self.dir, ignore_errors=True)
        log(f"{time.strftime('%H:%M:%S')} live-{self.kind}: {reason} (ffmpeg pid {proc.pid})")


# ----------------------------------------------------------------------------- 全局状态

ROOT = Path("/tmp/thinkos-media-samples")
CACHE = ROOT / ".cache"
BUILDER = None
LIVE = {}
FLAKY_SEEN = set()
FLAKY_LOCK = threading.Lock()
RTSP_PORT = 8554


def stop_all_live(reason="server exit"):
    for ls in LIVE.values():
        ls.stop(reason)


def watchdog():
    while True:
        time.sleep(2)
        for ls in LIVE.values():
            if ls.running() and time.monotonic() - ls.last > LIVE_IDLE_SECONDS:
                ls.stop(f"idle {LIVE_IDLE_SECONDS}s, stopped")


def parse_range(header, size):
    """None = 忽略（返回整个文件），"bad" = 416，(start, end) = 206。只支持单段 bytes 范围。"""
    m = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if not m or (m.group(1) == "" and m.group(2) == ""):
        return None
    a, b = m.groups()
    if size == 0:
        return "bad"
    if a == "":
        n = int(b)
        return "bad" if n == 0 else (max(0, size - n), size - 1)
    start = int(a)
    if start >= size:
        return "bad"
    end = min(int(b), size - 1) if b else size - 1
    return None if end < start else (start, end)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ThinkOSMediaServe/1"
    sys_version = ""
    timeout = 60

    # --- 日志 / 连接生命周期 -------------------------------------------------------------

    def log_message(self, fmt, *args):
        msg = fmt % args
        if not msg.startswith("Request timed out"):  # 空闲 keep-alive 超时不必刷屏
            log(f"{time.strftime('%H:%M:%S')} proto {clean(msg)}")

    def log_request(self, code="-", size="-"):
        pass

    def send_response(self, code, message=None):
        self._status = code
        super().send_response(code, message)

    def setup(self):
        super().setup()
        try:
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass

    def handle_one_request(self):
        self._status, self._sent, self._note = 0, 0, []
        self._t0 = time.monotonic()
        self.command = None
        try:
            super().handle_one_request()
        except (ConnectionError, TimeoutError) as e:
            self.close_connection = True
            self._note.append(f"client-closed({type(e).__name__})")
        except Exception:
            self.close_connection = True
            self._note.append("server-error")
            log(traceback.format_exc())
            if not self._status:
                try:
                    self._reply(500, b"internal error\n")
                except OSError:
                    pass
        finally:
            if self.command:
                self._log_access()

    def _log_access(self):
        h = getattr(self, "headers", None)
        parts = [time.strftime("%H:%M:%S"), self.client_address[0], self.command, clean(self.path), "->", str(self._status or "-")]
        parts.append("range=" + (clean(h.get("Range", "")) if h and h.get("Range") else "-"))
        parts.append(f"sent={self._sent}")
        parts.append(f"{(time.monotonic() - self._t0) * 1000:.0f}ms")
        if h:
            for key, label in (("User-Agent", "ua"), ("Referer", "referer"), ("Authorization", "auth"), ("Host", "host")):
                if h.get(key):
                    parts.append(f'{label}="{clean(h[key])}"')
        if self._note:
            parts.append("note=" + ",".join(self._note))
        log(" ".join(parts))

    def _write(self, data):
        self.wfile.write(data)
        self._sent += len(data)

    # --- 响应辅助 ------------------------------------------------------------------------

    def _origin(self):
        return "http://" + (self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}")

    def _reply(self, status, body=b"", ctype="text/plain; charset=utf-8", headers=(), head=False):
        self.send_response(status)
        if status not in (204, 304):
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        if not head and status not in (204, 304) and body:
            self._write(body)

    def _text(self, status, text, head=False, headers=(), ctype="text/plain; charset=utf-8"):
        self._reply(status, text.encode("utf-8"), ctype, headers, head)

    def _send_file(self, path, ctype, head, slow=None, flaky=None, nocache=False):
        size = path.stat().st_size
        start, end, status = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng:
            r = parse_range(rng, size)
            if r == "bad":
                return self._reply(416, b"", headers=[("Content-Range", f"bytes */{size}"), ("Accept-Ranges", "bytes")], head=head)
            if r:
                (start, end), status = r, 206
        length = max(0, end - start + 1)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        if nocache:
            self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if head or length == 0:
            return
        cut = None
        if flaky:
            key = (path.name, start, flaky, urlsplit(self.path).query)
            with FLAKY_LOCK:
                first = key not in FLAKY_SEEN
                FLAKY_SEEN.add(key)
            if first and length > flaky:
                cut = flaky
        rate = slow * 1000 / 8 if slow else None
        chunk = 64 * 1024 if not rate else max(512, min(64 * 1024, int(rate / 10)))
        t0 = time.monotonic()
        sent = 0
        with open(path, "rb") as f:
            f.seek(start)
            while sent < length:
                n = min(chunk, length - sent)
                if cut is not None:
                    n = min(n, cut - sent)
                data = f.read(n)
                if not data:
                    break
                self._write(data)
                sent += len(data)
                if cut is not None and sent >= cut:
                    self._note.append(f"flaky-cut@{sent}")
                    self.close_connection = True
                    try:
                        self.wfile.flush()
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    return
                if rate:
                    delay = t0 + sent / rate - time.monotonic()
                    if delay > 0:
                        time.sleep(delay)

    # --- 路由 ----------------------------------------------------------------------------

    def do_GET(self):
        self._route(False)

    def do_HEAD(self):
        self._route(True)

    def _route(self, head):
        split = urlsplit(self.path)
        segs = [unquote(s) for s in split.path.split("/") if s]
        if any(("/" in s or "\\" in s or "\0" in s or s == "..") for s in segs):
            return self._text(400, "bad path\n", head)
        if not segs:
            return self._index(head)
        if segs == ["flaky", "reset"]:
            with FLAKY_LOCK:
                FLAKY_SEEN.clear()
            return self._text(200, "ok\n", head)

        mods, i = [], 0
        while i < len(segs):
            s = segs[i]
            if s in ("auth", "referer", "ua"):
                mods.append((s, None, [s]))
                i += 1
            elif s in ("redirect", "slow", "flaky") and i + 1 < len(segs) and segs[i + 1].isdigit():
                mods.append((s, int(segs[i + 1]), [s, segs[i + 1]]))
                i += 2
            else:
                break
        rest = segs[i:]
        if mods:
            if len(rest) != 2 or rest[0] != "files":
                return self._text(404, "modifiers apply to /files/<name> only\n", head)
            opts = {}
            for idx, (kind, arg, _) in enumerate(mods):
                if kind == "auth":
                    if not self._check_auth(head):
                        return
                elif kind == "referer":
                    if self.headers.get("Referer") != REFERER_OK:
                        return self._text(403, f"Referer must be {REFERER_OK}\n", head)
                elif kind == "ua":
                    if UA_OK not in (self.headers.get("User-Agent") or ""):
                        return self._text(403, f"User-Agent must contain {UA_OK}\n", head)
                elif kind == "redirect":
                    if arg > MAX_REDIRECTS:
                        return self._text(400, f"at most {MAX_REDIRECTS} redirects\n", head)
                    if arg > 0:
                        return self._redirect(mods, idx, rest, split.query, head)
                else:
                    opts[kind] = arg
            return self._static(rest[1], head, **opts)

        head0 = segs[0]
        if head0 == "files":
            if len(segs) == 1:
                return self._index(head)
            if len(segs) == 2:
                return self._static(segs[1], head)
        elif head0 == "health" and len(segs) == 1:
            return self._text(200, "ok\n", head)
        elif head0 == "time" and len(segs) == 1:
            return self._text(200, self._utc_now() + "\n", head)
        elif head0 == "status" and len(segs) == 2 and segs[1].isdigit():
            return self._status_route(int(segs[1]), head)
        elif head0 == "hls-aes" and segs[1:] == ["key.bin"]:
            return self._reply(200, KEY_BYTES, "application/octet-stream", [("Cache-Control", "no-cache")], head)
        elif head0 in ("hls", "hls-fmp4", "hls-aes", "dash") and len(segs) >= 3:
            return self._generated(head0, segs[1], segs[2:], head)
        elif head0 == "live" and len(segs) == 3 and segs[1] in ("hls", "dash"):
            return self._live(segs[1], segs[2], head)
        elif head0 == "m3u" and len(segs) == 2:
            if segs[1] == "channels.m3u":
                return self._m3u_channels(head)
            if segs[1] == "audio.m3u8":
                return self._m3u_audio(head)
        return self._text(404, "not found\n", head)

    # --- 各路由实现 ----------------------------------------------------------------------

    @staticmethod
    def _utc_now():
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

    def _check_auth(self, head):
        hdrs = "; ".join(f"{k}: {clean(v)}" for k, v in self.headers.items())
        log(f"{time.strftime('%H:%M:%S')} auth-check {clean(self.path)} headers: {hdrs}")
        if self.headers.get("Authorization") == f"Bearer {TEST_TOKEN}":
            return True
        self._text(401, "Authorization: Bearer thinkos-test required\n", head, headers=[("WWW-Authenticate", 'Bearer realm="thinkos"')])
        return False

    def _redirect(self, mods, idx, rest, query, head):
        new = []
        for j, (_, arg, consumed) in enumerate(mods):
            if j == idx:
                if arg - 1 > 0:
                    new += ["redirect", str(arg - 1)]
            else:
                new += consumed
        new += rest
        loc = self._origin() + "/" + "/".join(quote(s) for s in new) + (("?" + query) if query else "")
        self._text(302, f"redirecting to {loc}\n", head, headers=[("Location", loc)])

    def _static(self, name, head, slow=None, flaky=None):
        p = safe_file(ROOT, name)
        if p is None:
            return self._text(404, f"no such file: {clean(name)}\n", head)
        self._send_file(p, mime_for(name), head, slow, flaky)

    def _status_route(self, code, head):
        if not 200 <= code <= 599:
            return self._text(400, "status must be 200..599\n", head)
        headers = []
        if code in (301, 302, 303, 307, 308):
            headers.append(("Location", self._origin() + "/health"))
        if code == 401:
            headers.append(("WWW-Authenticate", 'Bearer realm="thinkos"'))
        if code in (429, 503):
            headers.append(("Retry-After", "1"))
        if code == 416:
            headers.append(("Content-Range", "bytes */0"))
        self._text(code, f"{code} {http.client.responses.get(code, 'Status')}\n", head, headers)

    def _generated(self, kind, name, sub, head):
        if safe_file(ROOT, name) is None:
            return self._text(404, f"no such sample: {clean(name)}\n", head)
        try:
            d = BUILDER.ensure(kind, name)
        except BuildError as e:
            log(f"{time.strftime('%H:%M:%S')} BUILD FAILED {kind}/{name}: {e}")
            return self._text(500, f"build failed: {e}\n", head)
        p = safe_file(d, *sub)
        if p is None:
            return self._text(404, "not found\n", head)
        self._send_file(p, mime_for(p.name), head, nocache=p.suffix in (".m3u8", ".mpd"))

    def _live(self, kind, fname, head):
        ls = LIVE[kind]
        want = "index.m3u8" if kind == "hls" else "manifest.mpd"
        if fname != want and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", fname):
            return self._text(404, "not found\n", head)
        try:
            d = ls.ensure()
        except BuildError as e:
            log(f"{time.strftime('%H:%M:%S')} LIVE FAILED {kind}: {e}")
            return self._text(503, f"live {kind} unavailable: {e}\n", head, headers=[("Retry-After", "2")])
        ls.touch()
        p = safe_file(d, fname)
        if p is None or p.suffix == ".tmp":
            return self._text(404, "not found\n", head)
        if kind == "dash" and fname == "manifest.mpd":
            text = p.read_text(encoding="utf-8")
            if "<UTCTiming" not in text:  # 让播放器用服务器时钟对时，避免设备时钟偏差导致直播边缘错位
                tag = f'\t<UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-iso:2014" value="{self._origin()}/time"/>\n'
                text = text.replace("</MPD>", tag + "</MPD>")
            return self._reply(200, text.encode("utf-8"), mime_for(fname), [("Cache-Control", "no-cache")], head)
        self._send_file(p, mime_for(fname), head, nocache=fname.endswith((".m3u8", ".mpd")))

    def _index(self, head):
        lines = ["ThinkOS media test origin", "", f"root: {ROOT}", "", "files:"]
        for p in sorted(ROOT.iterdir()):
            if p.is_file() and not p.name.startswith("."):
                lines.append(f"  /files/{p.name}  ({p.stat().st_size} bytes)")
        lines += ["", "see tools/media/README.md for the URL cheat-sheet", ""]
        self._text(200, "\n".join(lines), head)

    def _m3u_channels(self, head):
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        b = "http://" + host
        rtsp = f"rtsp://{host_only(host)}:{RTSP_PORT}/live"
        out = ['#EXTM3U x-tvg-url=""']

        def ch(tvg_id, group, name, url, opts=(), logo=True):
            attrs = ([f'tvg-id="{tvg_id}"'] if tvg_id else []) + ([f'tvg-logo="{b}/files/cover-300.jpg"'] if logo else [])
            out.append(" ".join(["#EXTINF:-1"] + attrs + [f'group-title="{group}",{name}']))
            out.extend(opts)
            out.append(url)

        ch("vod.h264.720", "VOD", "H.264 720p MP4", f"{b}/files/video-h264-720p30.mp4")
        ch("vod.hls", "HLS", "HLS VOD (single rendition)", f"{b}/hls/video-h264-1080p30-high.mp4/index.m3u8")
        ch("vod.hls.abr", "HLS", "HLS VOD (adaptive 720p/1080p)", f"{b}/hls/video-h264-1080p30-high.mp4/master.m3u8")
        ch("vod.dash", "DASH", "DASH VOD", f"{b}/dash/video-h264-1080p30-high.mp4/manifest.mpd")
        ch("live.hls", "Live", "Live HLS", f"{b}/live/hls/index.m3u8")
        ch("live.dash", "Live", "Live DASH", f"{b}/live/dash/manifest.mpd")
        ch("live.rtsp", "Live", "RTSP (tools/media/rtsp.sh)", rtsp, logo=False)
        ch("dup.1", "VOD", "Duplicate Channel", f"{b}/files/video-h264-480p30.mp4")
        ch("dup.2", "VOD", "Duplicate Channel", f"{b}/files/video-vp9-1080p30.webm")
        ch("cn.1", "中文频道", "测试频道 · 你好，ThinkOS", f"{b}/files/video-h264-480p30-aac.flv")
        ch("fr.1", "Divers", "Café Música", f"{b}/files/video-h264-480p30.mov")
        ch("comma.1", "News, Weather", "Group title with comma", f"{b}/files/video-h264-720p30.mp4")
        ch("ua.1", "Headers", "Needs User-Agent", f"{b}/ua/files/video-h264-480p30.mp4",
           opts=[f"#EXTVLCOPT:http-user-agent={UA_OK}"])
        ch("ref.1", "Headers", "Needs Referer", f"{b}/referer/files/video-h264-480p30.mp4",
           opts=[f"#EXTVLCOPT:http-referrer={REFERER_OK}"])
        ch("", "Radio", "Audio only (no tvg-id)", f"{b}/files/audio-mp3-cbr192.mp3", logo=False)
        self._text(200, "\n".join(out) + "\n", head, ctype="audio/x-mpegurl; charset=utf-8")

    def _m3u_audio(self, head):
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        b = "http://" + host
        tracks = [
            ("ThinkOS - MP3 CBR 192", f"{b}/files/audio-mp3-cbr192.mp3"),
            ("ThinkOS - AAC-LC", f"{b}/files/audio-aac-lc.m4a"),
            ("中文曲目 - FLAC", f"{b}/files/audio-flac-16bit-44k.flac"),
            ("Absolute-path entry (Opus)", "/files/audio-opus.opus"),
            ("Relative entry (Vorbis)", "../files/audio-vorbis.ogg"),
        ]
        out = ["#EXTM3U"]
        for title, url in tracks:
            out += [f"#EXTINF:12,{title}", url]
        # 故意用 HLS 的 MIME：很多服务器按扩展名就这样回，客户端必须靠内容（无 #EXT-X- 标签）识别这不是 HLS
        self._text(200, "\n".join(out) + "\n", head, ctype="application/vnd.apple.mpegurl")


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, TimeoutError)):
            return
        log(f"error from {client_address}:\n{traceback.format_exc()}")


def lan_ip():
    """banner 里给设备用的地址提示。macOS 先问 ipconfig（有 VPN/代理时 UDP 选路会选到虚拟网卡），否则用 UDP 选路。"""
    if sys.platform == "darwin":
        for iface in ("en0", "en1"):
            try:
                r = subprocess.run(["ipconfig", "getifaddr", iface], capture_output=True, text=True, timeout=3)
            except (OSError, subprocess.SubprocessError):
                break
            if r.returncode == 0 and r.stdout.strip():
                return r.stdout.strip()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))  # TEST-NET，不会真的发包，只用来选出口网卡
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def main():
    global ROOT, CACHE, BUILDER, RTSP_PORT
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default="/tmp/thinkos-media-samples", help="样片目录（gen_samples.sh 的输出）")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--live-source", default="video-h264-720p30.mp4", help="直播循环播放的样片（需含音视频，关键帧间隔 2 秒最佳）")
    ap.add_argument("--rtsp-port", type=int, default=8554, help="只用于 channels.m3u 里的 rtsp:// 地址")
    args = ap.parse_args()

    ROOT = Path(args.root).resolve()
    if not ROOT.is_dir():
        sys.exit(f"root {ROOT} does not exist; run tools/media/gen_samples.sh first")
    CACHE = ROOT / ".cache"
    CACHE.mkdir(exist_ok=True)
    RTSP_PORT = args.rtsp_port
    BUILDER = Builder(ROOT, CACHE)
    for kind in ("hls", "dash"):
        LIVE[kind] = LiveStream(kind, ROOT, CACHE, args.live_source)

    def on_signal(signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGHUP, on_signal)
    atexit.register(stop_all_live)
    threading.Thread(target=watchdog, daemon=True).start()

    server = Server((args.bind, args.port), Handler)
    ip = lan_ip() if args.bind in ("0.0.0.0", "") else args.bind
    log(f"serving {ROOT} on {args.bind}:{args.port}")
    log(f"  local:  http://127.0.0.1:{args.port}/")
    if ip:
        log(f"  LAN:    http://{ip}:{args.port}/   (设备上用这个地址；有 VPN/代理时请用 ipconfig getifaddr en0 核对)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("shutting down")
    finally:
        stop_all_live()
        server.server_close()


if __name__ == "__main__":
    main()
