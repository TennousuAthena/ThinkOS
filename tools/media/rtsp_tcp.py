#!/usr/bin/env python3
"""ThinkOS RTSP 测试源：支持 RTP over TCP（interleaved），仅标准库（asyncio）。

    python3 -I tools/media/rtsp_tcp.py <样片> [--port 8554] [--bind 0.0.0.0] [--audio-only] [--auth user:pass]

一个后台 ffmpeg 循环播放样片（-re -stream_loop -1），重编码成 H.264 Main（无 B 帧、25 帧一个关键帧）+ AAC，
按两路普通 RTP 发到本机回环 UDP；本服务把 RTP 包按 RTSP interleaved 帧（'$' + 通道 + 2 字节长度 + 包）转发给每个客户端。
地址：rtsp://<Mac 局域网 IP>:<port>/live（也接受 /live/）。

和 rtsp.sh（VLC）的区别：VLC 只会 UDP，对 TCP interleaved 的 SETUP 回 461；这里只做 TCP interleaved，
客户端要求 UDP（没有同时给出 TCP 选项）时回 461 Unsupported Transport，让客户端回退到 TCP。

支持：OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / TEARDOWN / GET_PARAMETER（SET_PARAMETER 当作空操作），多客户端并发。
--auth user:pass 时 DESCRIBE / SETUP / PLAY 需要 HTTP Basic（WWW-Authenticate: Basic realm="thinkos"），
客户端用 rtsp://user:pass@host:port/live。默认不鉴权。
每个请求一行日志写到 stderr（时间、客户端、方法、路径、状态码）；客户端断开不打印。

新客户端的起点：PLAY 要等到下一个 H.264 关键帧（最多约 1 秒）才回复，回复里带 RTP-Info（首个下发包的 seq / rtptime），
随后音视频都从该关键帧开始转发，避免从 GOP 中间解码出花屏，也让两个轨道的起点对齐。
"""
import argparse
import asyncio
import atexit
import base64
import binascii
import hmac
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
from collections import deque
from pathlib import Path
from urllib.parse import quote

FFMPEG = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg"
FFPROBE = os.environ.get("FFPROBE") or shutil.which("ffprobe") or "ffprobe"

SERVER_NAME = "ThinkOSRtspTcp/1"
REALM = "thinkos"
SESSION_TIMEOUT = 60  # 只是告诉客户端多久发一次保活；interleaved 连接本身就是会话，服务端不据此踢人
IDLE_TIMEOUT = 120  # 还没开始播放的连接，这么久没有请求就断开
PLAY_START_TIMEOUT = 5  # 等关键帧的上限（GOP 约 1 秒）
BACKLOG_DROP_BYTES = 1 << 20  # 单个客户端待发缓冲超过 1 MiB 就丢包，视频丢包后等下一个关键帧再恢复
STALL_CLOSE_SECONDS = 15  # 缓冲持续超限这么久就断开该客户端
SDP_TIMEOUT = 25
PUBLIC = "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
REASONS = {
    200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 405: "Method Not Allowed",
    454: "Session Not Found", 455: "Method Not Valid in This State", 461: "Unsupported Transport",
    501: "Not Implemented", 503: "Service Unavailable",
}


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def hms():
    return time.strftime("%H:%M:%S")


def clean(s):
    return re.sub(r"[^\x20-\x7e]", "?", s)


def die(msg):
    print(f"rtsp_tcp: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


# ----------------------------------------------------------------------------- 本机地址 / 探测


def lan_ip():
    """banner 里给设备用的地址提示：macOS 先取默认路由所在网卡的地址，再试 en0 / en1，最后用 UDP 选路。"""
    if sys.platform == "darwin":
        ifaces = []
        try:
            r = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True, timeout=3)
            m = re.search(r"interface:\s*(\S+)", r.stdout)
            if m:
                ifaces.append(m.group(1))
        except (OSError, subprocess.SubprocessError):
            pass
        ifaces += ["en0", "en1"]
        for iface in ifaces:
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


def probe_streams(src):
    """(有视频, 有音频)；封面图（attached_pic）不算视频。"""
    try:
        r = subprocess.run(
            [FFPROBE, "-v", "error", "-show_entries", "stream=codec_type:stream_disposition=attached_pic", "-of", "json", str(src)],
            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30,
        )
        streams = json.loads(r.stdout or "{}").get("streams", [])
    except (subprocess.SubprocessError, ValueError, FileNotFoundError) as e:
        die(f"ffprobe failed for {src.name}: {e}")
    has_v = any(s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic") for s in streams)
    has_a = any(s.get("codec_type") == "audio" for s in streams)
    return has_v, has_a


def bind_udp_pair():
    """回环上绑一对相邻 UDP 端口：偶数 RTP + 下一个 RTCP（ffmpeg 的 rtp 输出默认把 RTCP 发到 RTP 端口 +1）。"""
    for _ in range(100):
        a = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            a.bind(("127.0.0.1", 0))
            port = a.getsockname()[1]
            if port % 2 or port >= 65535:
                raise OSError("odd port")
            b.bind(("127.0.0.1", port + 1))
            for s in (a, b):
                s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
                s.setblocking(False)
            return a, b
        except OSError:
            a.close()
            b.close()
    die("could not find a free UDP port pair on 127.0.0.1")


# ----------------------------------------------------------------------------- RTP / H.264 解析


def rtp_payload(pkt):
    off = 12 + (pkt[0] & 0x0F) * 4
    if pkt[0] & 0x10:  # 扩展头
        if len(pkt) < off + 4:
            return b""
        off += 4 + int.from_bytes(pkt[off + 2:off + 4], "big") * 4
    end = len(pkt)
    if pkt[0] & 0x20 and end > off:  # 填充
        end -= pkt[-1]
    return pkt[off:end]


def h264_nal_types(pkt):
    """包首的 NAL 类型：STAP-A 展开全部；FU-A 只在起始位置位时给出内层类型（中间 / 结尾分片不算帧起点）。"""
    pl = rtp_payload(pkt)
    if not pl:
        return []
    t = pl[0] & 0x1F
    if t == 24:  # STAP-A
        out, i = [], 1
        while i + 2 < len(pl):
            n = int.from_bytes(pl[i:i + 2], "big")
            i += 2
            if n == 0 or i + n > len(pl):
                break
            out.append(pl[i] & 0x1F)
            i += n
        return out
    if t in (28, 29):  # FU-A / FU-B
        return [pl[1] & 0x1F] if len(pl) > 1 and pl[1] & 0x80 else []
    return [t]


def rewrite_sdp(raw, kinds):
    """ffmpeg 的 SDP 没有 a=control，端口 / 连接地址是回环的：补上 streamid=N，端口写 0，连接地址写 0.0.0.0。"""
    head, media = [], []
    for line in raw.replace("\r\n", "\n").split("\n"):
        if not line:
            continue
        if line.startswith("m="):
            media.append([line])
        else:
            (media[-1] if media else head).append(line)
    if [m[0].split(" ")[0][2:] for m in media] != list(kinds):
        raise ValueError(f"SDP media sections {[m[0] for m in media]} do not match expected {list(kinds)}")
    out = []
    for line in head:
        if line.startswith("s="):
            line = "s=ThinkOS RTSP test (TCP interleaved)"
        elif line.startswith("o="):
            line = re.sub(r"IN IP4 \S+$", "IN IP4 0.0.0.0", line)
        elif line.startswith("c="):
            line = "c=IN IP4 0.0.0.0"
        elif line.startswith("a=control:"):
            continue
        out.append(line)
    for i, sec in enumerate(media):
        m = sec[0].split(" ")
        m[1] = "0"  # TCP interleaved 用不到端口，按惯例写 0
        out += [" ".join(m), "c=IN IP4 0.0.0.0"]
        out += [ln for ln in sec[1:] if not ln.startswith(("c=", "a=control:"))]
        out.append(f"a=control:streamid={i}")
    return "\r\n".join(out) + "\r\n"


def sdp_clock_rates(raw):
    """各 m= 段 rtpmap 里的 RTP 时钟频率（按 m= 顺序；没写则为 0）。"""
    rates = []
    for line in raw.replace("\r\n", "\n").split("\n"):
        if line.startswith("m="):
            rates.append(0)
        elif rates and not rates[-1]:
            m = re.match(r"a=rtpmap:\d+ [^/\s]+/(\d+)", line)
            if m:
                rates[-1] = int(m.group(1))
    return rates


# ----------------------------------------------------------------------------- ffmpeg


def ffmpeg_command(src, plan, sdp_path):
    """plan: [(kind, rtp_port)]，顺序即 SDP 里 m= 的顺序（视频在前）。"""
    cmd = [FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "warning", "-re", "-stream_loop", "-1", "-i", str(src)]
    for kind, port in plan:
        if kind == "video":
            cmd += ["-map", "0:v:0", "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-profile:v", "main",
                    "-pix_fmt", "yuv420p", "-bf", "0", "-g", "25", "-keyint_min", "25", "-sc_threshold", "0",
                    "-maxrate", "4000k", "-bufsize", "2000k", "-payload_type", "96"]
        else:
            cmd += ["-map", "0:a:0", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000", "-payload_type", "97"]
        # global_header：SPS / PPS 和 AAC config 才会进 SDP（sprop-parameter-sets / config=）。
        # connect=1：UDP socket 做 connect()，本服务被 kill -9 后 ffmpeg 下一次发包就收到 ECONNREFUSED 并退出，不会留孤儿进程
        cmd += ["-flags", "+global_header", "-f", "rtp", f"rtp://127.0.0.1:{port}?rtcpport={port + 1}&connect=1"]
    return cmd + ["-sdp_file", str(sdp_path)]


class Ffmpeg:
    def __init__(self, cmd, workdir):
        self.logfile = Path(workdir) / "ffmpeg.log"
        try:
            with open(self.logfile, "wb") as lf:
                self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=lf, stderr=lf, start_new_session=True)
        except FileNotFoundError:
            die(f"ffmpeg not found ({FFMPEG})")
        atexit.register(self.stop)

    def alive(self):
        return self.proc.poll() is None

    def tail(self, n=1500):
        try:
            return self.logfile.read_text(errors="replace")[-n:]
        except OSError:
            return ""

    def stop(self):
        """SIGTERM 整个进程组，5 秒不退再 SIGKILL；可重复调用。"""
        if self.proc.poll() is not None:
            return
        try:
            os.killpg(self.proc.pid, signal.SIGTERM)
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)
                self.proc.wait(timeout=5)
        except (ProcessLookupError, PermissionError):
            pass


# ----------------------------------------------------------------------------- 轨道 / 转发


def sdiff32(x):
    """32 位 RTP 时间戳差（带符号，处理回绕）。"""
    return ((x + 0x80000000) & 0xFFFFFFFF) - 0x80000000


class Track:
    def __init__(self, index, kind, rtp_sock, rtcp_sock):
        self.index, self.kind = index, kind
        self.rtp_sock, self.rtcp_sock = rtp_sock, rtcp_sock
        self.clock = 90000 if kind == "video" else 48000  # RTP 时钟频率，启动后以 SDP 的 rtpmap 为准
        self.last_ts = None  # 视频：上一个包的 RTP 时间戳，用来判断帧起点
        self.last_sr = None  # 最近一个 RTCP SR，新客户端开播时先补发一份（ffmpeg 每 5 秒才发一次）
        self.sr = None  # 同一个 SR 的 (NTP 64 位定点, RTP 时间戳)，用来在轨道之间换算时间戳
        self.history = deque(maxlen=64)  # 非视频轨最近的包（AAC 约 15 包/秒，约 4 秒）：ffmpeg 的音频比视频提前约 0.3 秒发出，新客户端要从历史里补齐


class UdpIn(asyncio.DatagramProtocol):
    def __init__(self, callback):
        self.callback = callback

    def datagram_received(self, data, addr):
        self.callback(data)

    def error_received(self, exc):
        pass


class TrackSetup:
    __slots__ = ("track", "uri", "rtp_ch", "rtcp_ch")

    def __init__(self, track, uri, rtp_ch, rtcp_ch):
        self.track, self.uri, self.rtp_ch, self.rtcp_ch = track, uri, rtp_ch, rtcp_ch


class Start:
    """PLAY 之后等起点：first[轨道] = (seq, rtptime)，pkts 是起点之后要下发的包（按到达顺序），
    target[轨道] = 该轨与视频关键帧同一内容时刻的 RTP 时间戳（缺 SR 时为 None）。
    fut 完成时 PLAY 已回复（重新同步时 req / fut 为 None）。"""
    __slots__ = ("req", "fut", "first", "pkts", "target")

    def __init__(self, req, fut):
        self.req, self.fut, self.first, self.pkts, self.target = req, fut, {}, [], {}


class Request:
    def __init__(self, method, uri, headers):
        self.method, self.uri, self.headers = method, uri, headers
        self.cseq = headers.get("cseq", "")
        self.authority, self.path = split_uri(uri)
        self.ua = headers.get("user-agent", "")


_URI_RE = re.compile(r"^rtsp://(?:[^/@]*@)?([^/?#]*)([^?#]*)", re.I)


def split_uri(uri):
    """(host:port, path)；URI 里的 user:pass@ 一律丢掉（鉴权只认 Authorization 头，也不会进日志）。"""
    m = _URI_RE.match(uri)
    if m:
        return m.group(1), m.group(2) or "/"
    if uri.startswith("/"):
        return "", re.split(r"[?#]", uri)[0]
    return "", uri


_TRACK_RE = re.compile(r"^/live/(?:streamid|trackID|track)=(\d+)$", re.I)


def match_path(path):
    """('stream', None) = /live 或 /live/；('track', n) = /live/streamid=n；(None, None) = 不认识。"""
    p = path.rstrip("/") if path != "/" else path
    if p == "/live":
        return "stream", None
    m = _TRACK_RE.match(p)
    return ("track", int(m.group(1))) if m else (None, None)


def pick_tcp_transport(header, index):
    """从客户端给出的 Transport 候选里挑 TCP interleaved，返回 (RTP 通道, RTCP 通道)；没有 TCP 候选返回 None。"""
    for spec in header.split(","):
        parts = [p.strip() for p in spec.split(";")]
        proto = parts[0].upper()
        params = {}
        for p in parts[1:]:
            k, _, v = p.partition("=")
            params[k.strip().lower()] = v.strip()
        if "multicast" in params:
            continue
        iv = params.get("interleaved")
        if proto not in ("RTP/AVP/TCP", "RTP/AVP") or (proto == "RTP/AVP" and iv is None):
            continue
        if iv is None:
            a, b = 2 * index, 2 * index + 1
        else:
            m = re.fullmatch(r"(\d+)(?:-(\d+))?", iv)
            if not m:
                continue
            a = int(m.group(1))
            b = int(m.group(2)) if m.group(2) else a + 1
        if a != b and a <= 255 and b <= 255:
            return a, b
    return None


class Client:
    def __init__(self, app, reader, writer):
        self.app, self.reader, self.writer = app, reader, writer
        peer = writer.get_extra_info("peername")
        self.peer = f"{peer[0]}:{peer[1]}" if peer else "-"
        self.session = None
        self.tracks = {}  # 轨道序号 -> TrackSetup
        self.state = "init"  # init -> ready（已 SETUP）-> starting（PLAY 等关键帧）-> playing
        self.start = None
        self.stall_since = None
        self.close_after = False

    # --- 输出 ----------------------------------------------------------------------------

    def send_raw(self, data):
        if not self.writer.transport.is_closing():
            self.writer.write(data)

    def send_media(self, ts, pkt, rtcp=False):
        t = self.writer.transport
        if t.is_closing():
            return
        if t.get_write_buffer_size() > BACKLOG_DROP_BYTES:  # 客户端读得慢：丢包，持续太久就断开
            now = time.monotonic()
            if self.stall_since is None:
                self.stall_since = now
            elif now - self.stall_since > STALL_CLOSE_SECONDS:
                t.abort()
                return
            if not rtcp and ts.track.kind == "video" and self.state == "playing":
                self.state, self.start = "starting", Start(None, None)  # 视频丢了包，等下一个关键帧再恢复
            return
        self.stall_since = None
        t.write(b"$" + bytes((ts.rtcp_ch if rtcp else ts.rtp_ch,)) + len(pkt).to_bytes(2, "big") + pkt)

    def reply(self, req, status, headers=(), body=b"", ctype=None, note=""):
        lines = [f"RTSP/1.0 {status} {REASONS[status]}"]
        if req.cseq:
            lines.append(f"CSeq: {req.cseq}")
        lines.append(f"Server: {SERVER_NAME}")
        lines += [f"{k}: {v}" for k, v in headers]
        if ctype:
            lines.append(f"Content-Type: {ctype}")
        lines.append(f"Content-Length: {len(body)}")
        self.send_raw("\r\n".join(lines).encode("utf-8") + b"\r\n\r\n" + body)
        extra = [f"session={self.session}"] if self.session else []
        log(" ".join([hms(), self.peer, req.method, clean(req.path), "->", str(status)] + extra
                     + ([note] if note else []) + ([f'ua="{clean(req.ua)}"'] if req.ua else [])))

    # --- 转发（由 App 在每个 RTP / RTCP 包到达时调用）------------------------------------------

    def video_index(self):
        return next((i for i, ts in self.tracks.items() if ts.track.kind == "video"), None)

    def feed(self, track, pkt, key_start):
        if track.index not in self.tracks:
            return
        if self.state == "playing":
            self.send_media(self.tracks[track.index], pkt)
        elif self.state == "starting":
            self.offer(track, pkt, key_start)

    def feed_rtcp(self, track, pkt):
        if self.state == "playing" and track.index in self.tracks:
            self.send_media(self.tracks[track.index], pkt, rtcp=True)

    def take(self, st, idx, pkt):
        st.first[idx] = (int.from_bytes(pkt[2:4], "big"), int.from_bytes(pkt[4:8], "big"))
        st.pkts.append((idx, pkt))

    def offer(self, track, pkt, key_start):
        """起点：有视频轨时从视频关键帧开始，其他轨对齐到同一内容时刻（先从历史补，不够再等后续的包）；
        没有视频轨（纯音频）则各轨的第一个包。"""
        st, idx, gate = self.start, track.index, self.video_index()
        if idx in st.first:  # 该轨已有起点：后续的包不能丢（等另一轨起点期间先缓存），否则第一帧是花的
            st.pkts.append((idx, pkt))
            return
        if gate is None:
            self.take(st, idx, pkt)
        elif idx == gate:
            if not key_start:
                return
            self.take(st, idx, pkt)
            ts = st.first[idx][1]
            for other in self.tracks.values():
                o = other.track
                if o.index != gate:
                    st.target[o.index] = self.app.map_ts(track, ts, o)
                    self.backfill(st, o)
        elif gate in st.first:
            target = st.target.get(idx)
            if target is not None and sdiff32(int.from_bytes(pkt[4:8], "big") - target) < -(track.clock // 100):
                return  # 还没到视频关键帧的那个内容时刻
            self.take(st, idx, pkt)
        else:
            return
        if len(st.first) == len(self.tracks):
            self.finish_play()

    def backfill(self, st, o):
        """o 轨比视频提前发出时，历史里已经有与关键帧同一时刻（容差 10 ms）及之后的包：从那里开始补发。"""
        target = st.target.get(o.index)
        if target is None:
            return
        hist = list(o.history)
        for i, p in enumerate(hist):
            if sdiff32(int.from_bytes(p[4:8], "big") - target) >= -(o.clock // 100):
                self.take(st, o.index, p)
                st.pkts.extend((o.index, q) for q in hist[i + 1:])
                return

    def finish_play(self):
        st, self.start = self.start, None
        if self.writer.transport.is_closing():
            self.state = "ready"
            if st.fut and not st.fut.done():
                st.fut.set_result(False)
            return
        self.state = "playing"
        if st.req is not None:
            info = ",".join(f"url={self.tracks[i].uri};seq={seq};rtptime={ts}" for i, (seq, ts) in sorted(st.first.items()))
            self.reply(st.req, 200, [("Range", "npt=0.000-"), ("Session", self.session), ("RTP-Info", info)])
        for ts in self.tracks.values():
            if ts.track.last_sr:
                self.send_media(ts, ts.track.last_sr, rtcp=True)
        for idx, pkt in st.pkts:
            self.send_media(self.tracks[idx], pkt)
        if st.fut and not st.fut.done():
            st.fut.set_result(True)

    # --- 请求循环 ------------------------------------------------------------------------

    async def run(self):
        sock = self.writer.get_extra_info("socket")
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        except (OSError, AttributeError):
            pass
        self.app.clients.add(self)
        try:
            while not self.close_after:
                timeout = None if self.state in ("starting", "playing") else IDLE_TIMEOUT
                req = await asyncio.wait_for(self.read_request(), timeout)
                if req is None:
                    break
                await self.dispatch(req)
        except (ConnectionError, OSError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError):
            pass  # 客户端断开 / 超时：静默
        except Exception:
            log(f"{hms()} {self.peer} internal error:\n{traceback.format_exc()}")
        finally:
            self.state, self.start = "init", None
            self.app.clients.discard(self)
            try:
                if not self.writer.transport.is_closing():
                    await asyncio.wait_for(self.writer.drain(), 2)
            except Exception:
                pass
            self.writer.close()
            try:
                await asyncio.wait_for(self.writer.wait_closed(), 2)
            except Exception:
                pass

    async def read_request(self):
        r = self.reader
        b = await r.readexactly(1)
        while b in (b"$", b"\r", b"\n"):  # 播放中客户端会发 '$' 帧（RTCP RR 等），丢弃；个别客户端会多发空行
            if b == b"$":
                hdr = await r.readexactly(3)
                await r.readexactly(int.from_bytes(hdr[1:3], "big"))
            b = await r.readexactly(1)
        head = (b + await r.readuntil(b"\r\n\r\n")).decode("latin-1")
        lines = head.split("\r\n")
        parts = lines[0].split(" ")
        headers = {}
        for ln in lines[1:]:
            k, sep, v = ln.partition(":")
            if sep:
                headers[k.strip().lower()] = v.strip()
        req = Request(parts[0].upper(), parts[1] if len(parts) > 1 else "", headers)
        if len(parts) != 3 or not parts[2].startswith("RTSP/"):
            self.close_after = True
            self.reply(req, 400)
            return None
        try:
            n = int(headers.get("content-length", "0") or 0)
        except ValueError:
            n = -1
        if n < 0 or n > 65536:
            self.close_after = True
            self.reply(req, 400)
            return None
        if n:
            await r.readexactly(n)  # 请求体（GET_PARAMETER 可能带）用不上
        return req

    # --- 方法 ----------------------------------------------------------------------------

    async def dispatch(self, req):
        m = req.method
        if m == "OPTIONS":
            self.reply(req, 200, [("Public", PUBLIC)])
        elif m == "DESCRIBE":
            self.do_describe(req)
        elif m == "SETUP":
            self.do_setup(req)
        elif m == "PLAY":
            await self.do_play(req)
        elif m == "PAUSE":
            self.do_pause(req)
        elif m == "TEARDOWN":
            self.do_teardown(req)
        elif m in ("GET_PARAMETER", "SET_PARAMETER"):
            if self.check_session(req, required=False):
                self.reply(req, 200, [("Session", self.session)] if self.session else [])
        elif m in ("ANNOUNCE", "RECORD", "REDIRECT"):
            self.reply(req, 405, [("Public", PUBLIC)])
        else:
            self.reply(req, 501, [("Public", PUBLIC)])

    def authorize(self, req):
        auth = self.app.auth
        if auth is None:
            return True
        scheme, _, rest = req.headers.get("authorization", "").partition(" ")
        ok = False
        if scheme.lower() == "basic":
            try:
                ok = hmac.compare_digest(base64.b64decode(rest.strip(), validate=True), auth)
            except (binascii.Error, ValueError):
                ok = False
        if not ok:
            self.reply(req, 401, [("WWW-Authenticate", f'Basic realm="{REALM}"')])
        return ok

    def check_session(self, req, required=True):
        sid = req.headers.get("session", "").split(";")[0].strip()
        if (sid and sid != self.session) or (required and not sid and self.session is None):
            self.reply(req, 454)
            return False
        return True

    def do_describe(self, req):
        if not self.authorize(req):
            return
        if match_path(req.path)[0] != "stream":
            return self.reply(req, 404)
        authority = req.authority
        if not authority:
            sockname = self.writer.get_extra_info("sockname")
            authority = f"{sockname[0]}:{sockname[1]}" if sockname else ""
        self.reply(req, 200, [("Content-Base", f"rtsp://{authority}/live/"), ("Cache-Control", "no-cache")],
                   body=self.app.sdp, ctype="application/sdp")

    def do_setup(self, req):
        if not self.authorize(req):
            return
        kind, idx = match_path(req.path)
        if kind != "track" or idx >= len(self.app.tracks):
            return self.reply(req, 404)
        if self.state in ("starting", "playing"):
            return self.reply(req, 455)
        sid = req.headers.get("session", "").split(";")[0].strip()
        if sid and sid != self.session:
            return self.reply(req, 454)
        chans = pick_tcp_transport(req.headers.get("transport", ""), idx)
        used = {c for i, ts in self.tracks.items() if i != idx for c in (ts.rtp_ch, ts.rtcp_ch)}
        if chans is None or used & set(chans):
            return self.reply(req, 461)  # 只有 UDP / 组播候选：让客户端回退到 TCP
        if self.session is None:
            self.session = secrets.token_hex(4).upper()
        uri = f"rtsp://{req.authority}{req.path}" if req.authority else req.path
        self.tracks[idx] = TrackSetup(self.app.tracks[idx], uri, *chans)
        self.state = "ready"
        self.reply(req, 200, [("Transport", f"RTP/AVP/TCP;unicast;interleaved={chans[0]}-{chans[1]}"),
                              ("Session", f"{self.session};timeout={SESSION_TIMEOUT}")],
                   note=f"transport=tcp/{chans[0]}-{chans[1]}")

    async def do_play(self, req):
        if not self.authorize(req):
            return
        if match_path(req.path)[0] is None:
            return self.reply(req, 404)
        if not self.check_session(req):
            return
        if not self.tracks:
            return self.reply(req, 455)
        if self.state == "playing":
            return self.reply(req, 200, [("Range", "npt=0.000-"), ("Session", self.session)])
        fut = asyncio.get_running_loop().create_future()
        self.state, self.start = "starting", Start(req, fut)
        try:
            await asyncio.wait_for(fut, PLAY_START_TIMEOUT)  # 回复由 finish_play 在起点包到达时写出
        except asyncio.TimeoutError:
            self.state, self.start = "ready", None
            self.reply(req, 503, note="no-keyframe")

    def do_pause(self, req):
        if not self.check_session(req):
            return
        if self.state in ("starting", "playing"):
            self.state, self.start = "ready", None
        self.reply(req, 200, [("Session", self.session)])

    def do_teardown(self, req):
        if not self.check_session(req):
            return
        self.state, self.start = "init", None
        self.reply(req, 200, [("Session", self.session)] if self.session else [])
        self.close_after = True


class App:
    def __init__(self, tracks, auth):
        self.tracks, self.auth = tracks, auth
        self.sdp = b""
        self.clients = set()

    def on_rtp(self, track, pkt):
        if len(pkt) < 12 or pkt[0] >> 6 != 2:
            return
        key_start = False
        if track.kind == "video":
            ts = int.from_bytes(pkt[4:8], "big")
            frame_start, track.last_ts = ts != track.last_ts, ts
            if frame_start and self.clients:
                # SPS 只会出现在 IDR 前；这里把 "帧的第一个包含 IDR 或 SPS" 当作可开始的关键帧
                key_start = any(t in (5, 7) for t in h264_nal_types(pkt))
        for c in tuple(self.clients):
            c.feed(track, pkt, key_start)
        if track.kind != "video":
            track.history.append(pkt)  # 放在下发之后：同一个包不会被“历史补发”和“实时下发”各发一次

    def on_rtcp(self, track, pkt):
        if len(pkt) < 8:
            return
        if pkt[1] == 200 and len(pkt) >= 20:  # SR：NTP(8) + RTP 时间戳(4)
            track.last_sr = pkt
            track.sr = (int.from_bytes(pkt[8:16], "big"), int.from_bytes(pkt[16:20], "big"))
        for c in tuple(self.clients):
            c.feed_rtcp(track, pkt)

    @staticmethod
    def map_ts(src, ts, dst):
        """src 轨上时间戳 ts 对应的 dst 轨时间戳（经两轨各自最近一个 SR 的 NTP↔RTP 对应关系换算）；缺 SR 返回 None。"""
        if not (src.sr and dst.sr):
            return None
        (ntp_s, rtp_s), (ntp_d, rtp_d) = src.sr, dst.sr
        secs = (ntp_s - ntp_d) / (1 << 32) + sdiff32(ts - rtp_s) / src.clock
        return (rtp_d + round(secs * dst.clock)) & 0xFFFFFFFF

    async def on_connect(self, reader, writer):
        await Client(self, reader, writer).run()


# ----------------------------------------------------------------------------- 主流程


async def wait_for_sdp(ff, sdp_path, n_media, stop):
    deadline = time.monotonic() + SDP_TIMEOUT
    while time.monotonic() < deadline and not stop.is_set():
        if not ff.alive():
            die(f"ffmpeg exited with {ff.proc.returncode} before producing an SDP:\n{ff.tail()}")
        try:
            text = sdp_path.read_text()
            if text.count("\nm=") + text.startswith("m=") >= n_media and text.endswith("\n"):
                return text
        except OSError:
            pass
        await asyncio.sleep(0.1)
    if stop.is_set():
        return None
    die(f"ffmpeg produced no SDP within {SDP_TIMEOUT}s:\n{ff.tail()}")


def banner(args, src, kinds, ip, auth_text):
    userinfo = ""
    if auth_text:
        user, _, pw = auth_text.partition(":")
        userinfo = f"{quote(user, safe='')}:{quote(pw, safe='')}@"
    log(f"{hms()} serving {src.name} ({'+'.join(kinds)}, looping) on {args.bind}:{args.port}, RTSP over TCP interleaved only")
    if args.bind in ("0.0.0.0", ""):
        log(f"  local:  rtsp://{userinfo}127.0.0.1:{args.port}/live")
        if ip:
            log(f"  LAN:    rtsp://{userinfo}{ip}:{args.port}/live   (设备上用这个地址；有 VPN/代理时请用 ipconfig getifaddr en0 核对)")
    else:
        log(f"  URL:    rtsp://{userinfo}{args.bind}:{args.port}/live")
    if auth_text:
        log("  auth:   HTTP Basic required on DESCRIBE / SETUP / PLAY")


async def run(args, src, has_v, has_a, auth_text):
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        loop.add_signal_handler(sig, stop.set)

    workdir = Path(tempfile.mkdtemp(prefix="thinkos-rtsp-tcp-"))
    code = 0
    ff = server = watcher = None
    endpoints = []
    app = None
    try:
        kinds = [k for k, on in (("video", has_v), ("audio", has_a)) if on]
        tracks, plan = [], []
        for kind in kinds:
            rtp_sock, rtcp_sock = bind_udp_pair()
            track = Track(len(tracks), kind, rtp_sock, rtcp_sock)
            tracks.append(track)
            plan.append((kind, rtp_sock.getsockname()[1]))
        app = App(tracks, auth_text.encode("utf-8") if auth_text else None)
        for t in tracks:
            for sock, handler in ((t.rtp_sock, app.on_rtp), (t.rtcp_sock, app.on_rtcp)):
                endpoints.append((await loop.create_datagram_endpoint(
                    lambda h=handler, tr=t: UdpIn(lambda data: h(tr, data)), sock=sock))[0])

        try:  # 先占住 TCP 端口（不接受连接），端口被占用就不必再启动 ffmpeg
            server = await asyncio.start_server(app.on_connect, args.bind, args.port, backlog=64, start_serving=False)
        except OSError as e:
            die(f"cannot listen on {args.bind}:{args.port}: {e}")

        sdp_path = workdir / "stream.sdp"
        ff = Ffmpeg(ffmpeg_command(src, plan, sdp_path), workdir)
        raw = await wait_for_sdp(ff, sdp_path, len(tracks), stop)
        if raw is None:
            return 0
        try:
            app.sdp = rewrite_sdp(raw, kinds).encode("utf-8")
        except ValueError as e:
            die(str(e))
        for t, clock in zip(tracks, sdp_clock_rates(raw)):
            t.clock = clock or t.clock
        if has_v and "sprop-parameter-sets" not in raw:
            log(f"{hms()} warning: SDP has no sprop-parameter-sets; clients that join mid-stream may not get SPS/PPS")

        async def watch():
            nonlocal code
            while ff.alive():
                await asyncio.sleep(0.5)
            log(f"{hms()} ffmpeg exited unexpectedly (code {ff.proc.returncode}):\n{ff.tail()}")
            code = 1
            stop.set()

        watcher = asyncio.create_task(watch())
        await server.start_serving()
        banner(args, src, kinds, lan_ip() if args.bind in ("0.0.0.0", "") else None, auth_text)
        await stop.wait()
        log(f"{hms()} shutting down")
    finally:
        if watcher:
            watcher.cancel()
        if server:
            server.close()
        if app:
            for c in tuple(app.clients):
                c.writer.transport.abort()
        for ep in endpoints:
            ep.close()
        if ff:
            ff.stop()
        for t in (app.tracks if app else []):
            t.rtp_sock.close()
            t.rtcp_sock.close()
        shutil.rmtree(workdir, ignore_errors=True)
    return code


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sample", help="样片（gen_samples.sh 的输出之一），循环播放")
    ap.add_argument("--port", type=int, default=8554)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--audio-only", action="store_true", help="只提供音频轨（忽略样片里的视频）")
    ap.add_argument("--auth", metavar="user:pass", help="DESCRIBE / SETUP / PLAY 需要 HTTP Basic；默认不鉴权")
    args = ap.parse_args()

    src = Path(args.sample).resolve()
    if not src.is_file():
        die(f"no such file: {args.sample}")
    if args.auth is not None and (":" not in args.auth or args.auth.startswith(":")):
        ap.error("--auth must look like user:pass")
    has_v, has_a = probe_streams(src)
    if args.audio_only:
        if not has_a:
            die(f"--audio-only but {src.name} has no audio stream")
        has_v = False
    if not (has_v or has_a):
        die(f"{src.name} has no usable audio or video stream")
    sys.exit(asyncio.run(run(args, src, has_v, has_a, args.auth)))


if __name__ == "__main__":
    main()
