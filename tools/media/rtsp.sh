#!/usr/bin/env bash
# rtsp.sh <sample-file> [port=8554]
#
# 在本机起一个循环播放的 RTSP 源：rtsp://<Mac 局域网 IP>:<port>/live（H.264 + AAC，UDP 传输）。前台运行，Ctrl-C 结束。
#
# 为什么不是单纯的 ffmpeg：原计划是
#     ffmpeg -re -stream_loop -1 -i FILE -c copy -f rtsp -rtsp_flags listen rtsp://0.0.0.0:8554/live
# 在 ffmpeg 9.0.2 (Homebrew) 上实测不可用：RTSP 复用器（-f rtsp 输出）不支持 listen，
# 它会尝试作为客户端去连 tcp://0.0.0.0:8554，直接报 "Connection refused / Could not write header"。
# listen 标志只对 RTSP 解复用器（-i rtsp://...）有效，含义是等别人推流进来，不是给播放器当源。
# 因此 RTSP 服务端由 VLC 的 sout 负责（本机已装 VLC 3.0.24），ffmpeg 只用来预编码素材。
#
# 实际验证过的（macOS，ffmpeg 9.0.2，VLC 3.0.24）：
#   - DESCRIBE 返回正确 SDP：H264 带 sprop-parameter-sets（SPS/PPS），AAC 为 mpeg4-generic 带 config
#   - ffprobe rtsp://127.0.0.1:8554/live（默认 UDP）识别出 h264 + aac
#   - 两个 ffmpeg 客户端同时拉流 5 秒均正常退出（VLC 支持多客户端，不是“一次一个”）
#   - --loop 循环播放时时间戳连续（拉流 40 秒以上不断）
# 已知限制：
#   - VLC 的 RTSP 服务端不支持 TCP interleaved，SETUP 回 461 Unsupported transport；只能测 UDP。
#     （ffprobe -rtsp_transport tcp 会失败，这是预期；Media3 默认先试 UDP，收到 461 会回退 TCP，但对本源无效。）
#   - 没有鉴权（RTSP_AUTH 不需要）。
#   - 首次运行 macOS 可能弹窗询问是否允许 VLC 接收传入连接，需要点允许，否则设备连不上。
#
# 素材总是先用 ffmpeg 预编码成 “无 B 帧、1 秒 GOP、H.264 Main + AAC” 的副本（缓存在 $TMPDIR/thinkos-rtsp，源文件更新才重编）：
# 无 B 帧避免 RTP 时间戳乱序，1 秒 GOP 让中途加入的客户端很快拿到关键帧。纯音频文件则只推音频。
set -euo pipefail

SRC="${1:?usage: $0 <sample-file> [port=8554]}"
PORT="${2:-8554}"
[[ -f $SRC ]] || { echo "no such file: $SRC" >&2; exit 1; }
command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg not found" >&2; exit 1; }
command -v ffprobe >/dev/null 2>&1 || { echo "ffprobe not found" >&2; exit 1; }

VLC="${VLC:-}"
if [[ -z $VLC ]]; then
  for c in /Applications/VLC.app/Contents/MacOS/VLC "$HOME/Applications/VLC.app/Contents/MacOS/VLC"; do
    [[ -x $c ]] && { VLC=$c; break; }
  done
fi
[[ -z $VLC ]] && VLC="$(command -v vlc || true)"
[[ -n $VLC ]] || { echo "VLC not found (set VLC=/path/to/VLC); ffmpeg alone cannot act as an RTSP server, see header comment" >&2; exit 1; }

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use" >&2
  exit 1
fi

CACHE="${TMPDIR:-/tmp}/thinkos-rtsp"
mkdir -p "$CACHE"
ENC="$CACHE/$(basename "$SRC").rtsp.mp4"
if [[ ! -s $ENC || $SRC -nt $ENC ]]; then
  has_video=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_type -of csv=p=0 "$SRC" | head -n1 || true)
  if [[ -n $has_video ]]; then
    vargs=(-map 0:v:0 -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p -bf 0 -g 30 -keyint_min 30 -sc_threshold 0)
  else
    vargs=(-vn)
  fi
  echo "pre-encoding $SRC -> $ENC (no B-frames, 1s GOP)"
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$SRC" "${vargs[@]}" -map 0:a:0 -c:a aac -b:a 128k -ac 2 -ar 48000 \
    -movflags +faststart "$ENC.part.mp4"
  mv "$ENC.part.mp4" "$ENC"
fi

iface="$(route -n get default 2>/dev/null | awk '/interface:/ {print $2}')"
ip="$([[ -n $iface ]] && ipconfig getifaddr "$iface" 2>/dev/null || true)"
echo "RTSP source: rtsp://${ip:-<Mac-LAN-IP>}:$PORT/live   (local: rtsp://127.0.0.1:$PORT/live)"
echo "UDP only, looping $(basename "$SRC"); Ctrl-C to stop"
exec "$VLC" -I dummy --no-video-title-show --no-video --loop --sout-keep --sout-all \
  --sout "#rtp{sdp=rtsp://:$PORT/live}" "$ENC"
