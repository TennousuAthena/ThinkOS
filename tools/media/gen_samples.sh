#!/usr/bin/env bash
# gen_samples.sh [outdir=/tmp/thinkos-media-samples]
#
# 生成流媒体 / 本地播放测试用的合成样片矩阵（12 秒，全部由 ffmpeg lavfi 合成，不下载任何东西）。
#
# 画面：testsrc2（自带帧计数器）+ 每秒开头 0.1 秒底部白条闪一次（顶部左上角是 testsrc2 计数器，不遮挡）；
# 声音：双声道 440 Hz 持续音 + 每秒开头 0.1 秒 1 kHz 嘀声。白条与嘀声同时出现，可用来肉眼/耳朵检查音画同步。
# （本机 ffmpeg 没有 drawtext/freetype，所以不烧录时间码，靠 testsrc2 的计数器。）
#
# 环境变量：FORCE=1 重新生成已存在的文件。幂等：已存在的文件跳过，但每次都会对全部输出跑 ffprobe 校验。
# 输出：<outdir>/manifest.tsv（file / container / video codec / audio codec / resolution@fps / expected_on_device / note）。
# 校验失败（编码器名、分辨率、帧率、时长、体积 > 6 MiB 等）会逐条打印 FAIL 并以非 0 退出。
#
# 兼容 macOS 自带 bash 3.2（不使用关联数组 / mapfile）。
set -euo pipefail

OUT="${1:-/tmp/thinkos-media-samples}"
FORCE="${FORCE:-0}"
DUR=12
MAX_BYTES=$((6 * 1024 * 1024))

for tool in ffmpeg ffprobe iconv; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing tool: $tool" >&2; exit 1; }
done
mkdir -p "$OUT"
rm -f "$OUT"/.part-*

ENCODERS=$(ffmpeg -hide_banner -encoders 2>/dev/null)
have_enc() { grep -Eq "^ [VAS][.A-Z]{5} $1 " <<<"$ENCODERS"; }
die() { echo "ERROR: $*" >&2; exit 1; }

FF=(ffmpeg -nostdin -hide_banner -loglevel error -y)
AEXPR='0.25*sin(2*PI*440*t)+0.5*sin(2*PI*1000*t)*lt(mod(t,1),0.1)'
AIN=(-f lavfi -i "aevalsrc=exprs='${AEXPR}|${AEXPR}':s=48000:d=${DUR}")
COMMON=(-fflags +bitexact -flags +bitexact -map_metadata -1 -t "$DUR")
IN=()
ROWS=()
CK=()
BUILT=0
SKIPPED=0
FAILS=0

# v_in W H FPS：视频 + 音频输入；a_in：仅音频输入
v_in() {
  IN=(-f lavfi -i "testsrc2=size=${1}x${2}:rate=${3}:duration=${DUR},format=yuv420p,drawbox=x=0:y=ih-ih/12:w=iw:h=ih/12:color=white:t=fill:enable='lt(mod(t,1),0.1)'${4:+,$4}" "${AIN[@]}")
}
a_in() { IN=("${AIN[@]}"); }

# clip NAME CONTAINER VCODEC ACODEC RES EXPECT NOTE -- ffmpeg 输出参数...
# VCODEC/ACODEC 为 ffprobe codec_name（"-" 表示没有该流）；RES 为 WxH[@fps]，"-" 表示不检查。
clip() {
  local name=$1 cont=$2 vc=$3 ac=$4 res=$5 exp=$6 note=$7
  shift 8
  ROWS+=("$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' "$name" "$cont" "$vc" "$ac" "$res" "$exp" "$note")")
  local dst="$OUT/$name" tmp="$OUT/.part-$name"
  if [[ -s $dst && $FORCE != 1 ]]; then
    SKIPPED=$((SKIPPED + 1))
    printf 'skip  %s\n' "$name"
    return 0
  fi
  rm -f "$tmp"
  printf 'gen   %s\n' "$name"
  "${FF[@]}" "${IN[@]}" "${COMMON[@]}" "$@" "$tmp" 2>"$tmp.log" || { cat "$tmp.log" >&2; die "ffmpeg failed for $name"; }
  grep -v '^Svt\[' "$tmp.log" >&2 || true
  rm -f "$tmp.log"
  mv "$tmp" "$dst"
  BUILT=$((BUILT + 1))
}

# 文本类旁路文件（字幕、歌词、封面）：只登记到 manifest，内容由下面的 here-doc 写出
sidecar() {
  ROWS+=("$(printf '%s\t%s\t-\t-\t-\tsidecar\t%s' "$1" "$2" "$3")")
}
want_write() { [[ ! -s $OUT/$1 || $FORCE == 1 ]]; }

# chk NAME SELECTOR ENTRY GLOB：额外断言。SELECTOR = v:0 | a:0 | s:0 | format | tag | rot
chk() { CK+=("$1|$2|$3|$4"); }

# ---------------------------------------------------------------- sidecars
if want_write sub-sidecar.srt; then
  printf '%s\n' \
    '1' '00:00:01,000 --> 00:00:03,500' 'Hello from ThinkOS' '' \
    '2' '00:00:04,000 --> 00:00:07,000' 'Second cue, line one' 'line two of the same cue' '' \
    '3' '00:00:08,000 --> 00:00:11,000' '你好，ThinkOS' '' > "$OUT/sub-sidecar.srt"
  echo "gen   sub-sidecar.srt"
fi
if want_write sub-sidecar.vtt; then
  printf '%s\n' \
    'WEBVTT' '' \
    '1' '00:00:01.000 --> 00:00:03.500' 'Hello from ThinkOS' '' \
    '2' '00:00:04.000 --> 00:00:07.000' 'Second cue, line one' 'line two of the same cue' '' \
    '3' '00:00:08.000 --> 00:00:11.000' '你好，ThinkOS' '' > "$OUT/sub-sidecar.vtt"
  echo "gen   sub-sidecar.vtt"
fi
if want_write lyrics-sample.lrc; then
  printf '%s\n' \
    '[ti:ThinkOS Lyrics Sample]' '[ar:ThinkOS]' '[al:Test]' '[by:gen_samples.sh]' \
    '[00:01.00]First line of the sample lyric' \
    '[00:03.50]Second line, a little longer than the first one' \
    '[00:06.00]Third line' \
    '[00:08.00]Bilingual pair: Hello, ThinkOS' \
    '[00:08.00]双语对照：你好，ThinkOS' \
    '[00:10.50]Last line before the clip ends' > "$OUT/lyrics-sample.lrc"
  echo "gen   lyrics-sample.lrc"
fi
if want_write lyrics-gbk.lrc; then
  printf '%s\n' \
    '[ti:ThinkOS 歌词测试]' '[ar:测试歌手]' '[al:编码测试]' \
    '[00:01.00]第一行歌词：你好，世界' \
    '[00:03.50]第二行歌词，稍微长一点的一行' \
    '[00:06.00]第三行' \
    '[00:08.00]中英对照 Hello, ThinkOS' \
    '[00:08.00]同一时间戳的第二行' \
    '[00:10.50]最后一行歌词' | iconv -f UTF-8 -t GBK > "$OUT/lyrics-gbk.lrc"
  echo "gen   lyrics-gbk.lrc"
fi
if want_write cover-300.jpg; then
  "${FF[@]}" -f lavfi -i "testsrc2=size=300x300:rate=1:duration=1,format=yuvj420p" -frames:v 1 -q:v 3 \
    -fflags +bitexact "$OUT/.part-cover-300.jpg" || die "ffmpeg failed for cover-300.jpg"
  mv "$OUT/.part-cover-300.jpg" "$OUT/cover-300.jpg"
  echo "gen   cover-300.jpg"
fi
sidecar sub-sidecar.srt srt "SubRip 旁路字幕，3 条，其中一条两行、一条中文，对应 sub-base.mp4"
sidecar sub-sidecar.vtt vtt "WebVTT 旁路字幕，内容同 srt，对应 sub-base.mp4"
sidecar lyrics-sample.lrc lrc "UTF-8 歌词，6 个时间行，00:08.00 有中英双语同时间戳两行"
sidecar lyrics-gbk.lrc lrc "GBK 编码歌词（非 UTF-8），测试编码探测，00:08.00 同样有两行同时间戳"
sidecar cover-300.jpg jpeg "300x300 封面图，被 audio-mp3-id3v2-cover.mp3 / audio-flac-comments-cover.flac 内嵌"

# ------------------------------------------------------- progressive video
H264=(-c:v libx264 -preset veryfast -pix_fmt yuv420p)
KF=(-force_key_frames 'expr:gte(t,n_forced*2)')
AAC=(-c:a aac -b:a 128k -ac 2)
FAST=(-movflags +faststart)

v_in 854 480 30
clip video-h264-480p30.mp4 mp4 h264 aac 854x480@30 play "H.264 Main, 0.9 Mbps，基础冒烟样片" -- \
  "${H264[@]}" -profile:v main -b:v 900k -maxrate 1200k -bufsize 2400k "${KF[@]}" "${AAC[@]}" "${FAST[@]}"

v_in 1280 720 30
clip video-h264-720p30.mp4 mp4 h264 aac 1280x720@30 play "H.264 High, 1.8 Mbps" -- \
  "${H264[@]}" -profile:v high -b:v 1800k -maxrate 2400k -bufsize 4800k "${KF[@]}" "${AAC[@]}" "${FAST[@]}"

v_in 1920 1080 30
clip video-h264-1080p30-high.mp4 mp4 h264 aac 1920x1080@30 play "H.264 High@L4.0 1080p30" -- \
  "${H264[@]}" -profile:v high -level 4.0 -b:v 2500k -maxrate 3200k -bufsize 6400k "${KF[@]}" "${AAC[@]}" "${FAST[@]}"
chk video-h264-1080p30-high.mp4 v:0 profile High
chk video-h264-1080p30-high.mp4 v:0 level 40

v_in 1920 1080 60
clip video-h264-1080p60.mp4 mp4 h264 aac 1920x1080@60 play "H.264 High@L4.2 1080p60，解码器上限边缘，留意掉帧" -- \
  "${H264[@]}" -profile:v high -level 4.2 -b:v 3000k -maxrate 3600k -bufsize 7200k "${KF[@]}" "${AAC[@]}" "${FAST[@]}"
chk video-h264-1080p60.mp4 v:0 profile High
chk video-h264-1080p60.mp4 v:0 level 42

v_in 1920 1080 30
clip video-hevc-1080p30-main.mp4 mp4 hevc aac 1920x1080@30 play "HEVC Main 8-bit (hvc1)" -- \
  -c:v libx265 -preset veryfast -tag:v hvc1 -pix_fmt yuv420p -profile:v main -b:v 2500k \
  -x265-params "log-level=error:vbv-maxrate=3200:vbv-bufsize=6400" "${KF[@]}" "${AAC[@]}" "${FAST[@]}"
chk video-hevc-1080p30-main.mp4 v:0 profile Main
chk video-hevc-1080p30-main.mp4 v:0 pix_fmt yuv420p

v_in 1920 1080 30 "format=yuv420p10le"
clip video-hevc-1080p30-main10.mp4 mp4 hevc aac 1920x1080@30 unknown "HEVC Main10 (10-bit)，硬解是否支持未知，需在设备上确认" -- \
  -c:v libx265 -preset veryfast -tag:v hvc1 -pix_fmt yuv420p10le -profile:v main10 -b:v 2500k \
  -x265-params "log-level=error:vbv-maxrate=3200:vbv-bufsize=6400" "${KF[@]}" "${AAC[@]}" "${FAST[@]}"
chk video-hevc-1080p30-main10.mp4 v:0 profile "Main 10"
chk video-hevc-1080p30-main10.mp4 v:0 pix_fmt yuv420p10le

OPUS=(-c:a libopus -b:a 96k -ar 48000 -ac 2)
v_in 1280 720 30
clip video-vp8-720p30.webm webm vp8 opus 1280x720@30 play "VP8 + Opus，WebM" -- \
  -c:v libvpx -pix_fmt yuv420p -b:v 1800k -deadline good -cpu-used 4 -auto-alt-ref 0 "${KF[@]}" "${OPUS[@]}"

v_in 1920 1080 30
clip video-vp9-1080p30.webm webm vp9 opus 1920x1080@30 play "VP9 + Opus，WebM" -- \
  -c:v libvpx-vp9 -pix_fmt yuv420p -b:v 2500k -deadline good -cpu-used 5 -row-mt 1 "${KF[@]}" "${OPUS[@]}"

v_in 1280 720 30
clip video-av1-720p30.mp4 mp4 av1 aac 1280x720@30 video-unsupported "AV1 (SVT-AV1) + AAC；设备无 AV1 解码器，应报不支持而不是崩溃" -- \
  -c:v libsvtav1 -preset 10 -crf 52 -g 60 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 "${FAST[@]}"

v_in 720 576 25
clip video-mpeg2-576p25-mp2.ts mpegts mpeg2video mp2 720x576@25 unknown "MPEG-2 PAL + MP2 音频，TS；平台是否带 MP2 解码器未知（FFmpeg 扩展也不含 MP2）" -- \
  -c:v mpeg2video -pix_fmt yuv420p -aspect 4:3 -b:v 3000k -maxrate 3500k -bufsize 1835k -g 12 -bf 2 \
  -c:a mp2 -b:a 192k -ar 48000 -ac 2 -f mpegts
clip video-mpeg2-576p25-ac3.mpg mpeg mpeg2video ac3 720x576@25 play-with-ffmpeg-ext "MPEG-2 PAL + AC-3，MPEG-2 Program Stream（DVD/VOB 同款，ffmpeg -f vob）" -- \
  -c:v mpeg2video -pix_fmt yuv420p -aspect 4:3 -b:v 3000k -maxrate 3500k -bufsize 1835k -g 12 -bf 2 \
  -c:a ac3 -b:a 192k -ar 48000 -ac 2 -f vob
clip video-mpeg1ps-vcd.mpg mpeg mpeg1video mp2 352x288@50 container-unsupported "MPEG-1 System Stream（VCD 同款，ffmpeg -f mpeg，ffprobe 报 50 fps 是 MPEG-1 的帧率表达方式）；Media3 的 PsExtractor 只认 MPEG-2 PS，应明确报不支持" -- \
  -c:v mpeg1video -pix_fmt yuv420p -b:v 1150k -g 12 -c:a mp2 -b:a 224k -ar 44100 -ac 2 -s 352x288 -f mpeg

v_in 854 480 30
clip video-mpeg4asp-480p30-mp3.avi avi mpeg4 mp3 854x480@30 unknown "MPEG-4 Part 2 + MP3，AVI；Media3 可能没有 AVI 提取器，若如此则应记为 container-unsupported" -- \
  -c:v mpeg4 -vtag XVID -pix_fmt yuv420p -b:v 1500k -g 60 -c:a libmp3lame -b:a 128k -ac 2
clip video-h264-480p30-aac.flv flv h264 aac 854x480@30 play "H.264 + AAC，FLV" -- \
  "${H264[@]}" -profile:v main -b:v 900k -maxrate 1200k -bufsize 2400k "${KF[@]}" "${AAC[@]}" -f flv
clip video-wmv2-480p30.wmv asf wmv2 wmav2 854x480@30 container-unsupported "WMV2 + WMA2，ASF；应优雅报不支持" -- \
  -c:v wmv2 -pix_fmt yuv420p -b:v 1500k -g 60 -c:a wmav2 -b:a 128k -ac 2 -f asf
clip video-h264-480p30.mov mov h264 aac 854x480@30 play "H.264 + AAC，QuickTime 封装" -- \
  "${H264[@]}" -profile:v main -b:v 900k -maxrate 1200k -bufsize 2400k "${KF[@]}" "${AAC[@]}" "${FAST[@]}" -f mov
chk video-h264-480p30.mov tag major_brand 'qt*'

# 3GP：本机 ffmpeg 通常没有 amr_nb 编码器（libopencore_amrnb），此时退化为 H.263 + AAC，只验证容器与 H.263
v_in 352 288 25
if have_enc libopencore_amrnb; then
  clip video-h263-cif-amrnb.3gp 3gp h263 amr_nb 352x288@25 play "H.263 CIF + AMR-NB，3GP" -- \
    -c:v h263 -pix_fmt yuv420p -b:v 300k -g 50 -c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k -f 3gp
  chk video-h263-cif-amrnb.3gp tag major_brand '3gp*'
else
  echo "note  libopencore_amrnb 编码器不存在，3gp 不含 AMR-NB；改为 H.263 + AAC（只覆盖 3GP 容器与 H.263）" >&2
  clip video-h263-cif-aac.3gp 3gp h263 aac 352x288@25 play "H.263 CIF + AAC，3GP（无 amr_nb 编码器，未覆盖 AMR-NB 音频）" -- \
    -c:v h263 -pix_fmt yuv420p -b:v 300k -g 50 -c:a aac -b:a 64k -ac 2 -f 3gp
  chk video-h263-cif-aac.3gp tag major_brand '3gp*'
fi

v_in 854 480 30
MKVV=("${H264[@]}" -profile:v main -b:v 900k -maxrate 1200k -bufsize 2400k "${KF[@]}")
clip video-h264-480p30-ac3.mkv matroska h264 ac3 854x480@30 play-with-ffmpeg-ext "H.264 + AC-3，MKV" -- \
  "${MKVV[@]}" -c:a ac3 -b:a 192k -ac 2
clip video-h264-480p30-eac3.mkv matroska h264 eac3 854x480@30 play-with-ffmpeg-ext "H.264 + E-AC-3，MKV" -- \
  "${MKVV[@]}" -c:a eac3 -b:a 192k -ac 2
clip video-h264-480p30-dts.mkv matroska h264 dts 854x480@30 play-with-ffmpeg-ext "H.264 + DTS (dca, 实验编码器)，MKV" -- \
  "${MKVV[@]}" -c:a dca -strict -2 -b:a 768k -ac 2
clip video-h264-480p30-alac.mp4 mp4 h264 alac 854x480@30 play-with-ffmpeg-ext "H.264 + ALAC，MP4" -- \
  "${MKVV[@]}" -c:a alac -ac 2 "${FAST[@]}"

# 字幕：先有 sub-sidecar.srt，再内嵌
SRT="$OUT/sub-sidecar.srt"
IN=(-f lavfi -i "testsrc2=size=854x480:rate=30:duration=${DUR},format=yuv420p,drawbox=x=0:y=ih-ih/12:w=iw:h=ih/12:color=white:t=fill:enable='lt(mod(t,1),0.1)'" "${AIN[@]}" -i "$SRT")
clip sub-embedded-srt.mkv matroska h264 aac 854x480@30 play "MKV 内嵌 SubRip 字幕轨（eng，3 条，含中文）" -- \
  "${MKVV[@]}" "${AAC[@]}" -map 0:v -map 1:a -map 2:s -c:s srt -metadata:s:s:0 language=eng
chk sub-embedded-srt.mkv s:0 codec_name subrip
clip sub-embedded-movtext.mp4 mp4 h264 aac 854x480@30 play "MP4 内嵌 mov_text (tx3g) 字幕轨（eng，3 条，含中文）" -- \
  "${MKVV[@]}" "${AAC[@]}" -map 0:v -map 1:a -map 2:s -c:s mov_text -metadata:s:s:0 language=eng "${FAST[@]}"
chk sub-embedded-movtext.mp4 s:0 codec_name mov_text
IN=(-f lavfi -i "testsrc2=size=854x480:rate=30:duration=${DUR},format=yuv420p,drawbox=x=0:y=ih-ih/12:w=iw:h=ih/12:color=white:t=fill:enable='lt(mod(t,1),0.1)'" "${AIN[@]}" -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=${DUR}" -i "$SRT")
clip video-2audio-embedded-sub.mkv matroska h264 aac 854x480@30 play "MKV 两条音轨（eng：与其它样片相同的 440 Hz 加节拍，jpn：880 Hz 纯音）+ 一条内嵌字幕，测试音轨 / 字幕切换" -- \
  "${MKVV[@]}" "${AAC[@]}" -map 0:v -map 1:a -map 2:a -map 3:s -c:s srt -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn -metadata:s:s:0 language=eng
chk video-2audio-embedded-sub.mkv a:0 codec_name aac
v_in 854 480 30
clip sub-base.mp4 mp4 h264 aac 854x480@30 play "无内嵌字幕的基础片，配 sub-sidecar.srt / sub-sidecar.vtt 做旁路字幕测试" -- \
  "${MKVV[@]}" "${AAC[@]}" "${FAST[@]}"

# 竖屏：编码画面是 1920x1080 横屏（内容已按 90° 转置），再用显示矩阵 rotation=90 转回 1080x1920 显示
PORTRAIT_RAW="$OUT/.part-portrait-raw.mp4"
if [[ ! -s $OUT/video-h264-portrait-rot90.mp4 || $FORCE == 1 ]]; then
  v_in 1080 1920 30
  rm -f "$PORTRAIT_RAW"
  "${FF[@]}" "${IN[@]}" "${COMMON[@]}" -vf transpose=2 "${H264[@]}" -profile:v high -b:v 2500k -maxrate 3200k -bufsize 6400k \
    "${KF[@]}" "${AAC[@]}" "$PORTRAIT_RAW" || die "ffmpeg failed for portrait pre-pass"
fi
ROWS+=("$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' video-h264-portrait-rot90.mp4 mp4 h264 aac 1920x1080@30 play \
  "竖屏：编码 1920x1080 + 显示矩阵顺时针 90°（ffprobe 显示 rotation=-90），应显示为 1080x1920，画面方向正确")")
if [[ -s $PORTRAIT_RAW ]]; then
  printf 'gen   %s\n' video-h264-portrait-rot90.mp4
  ffmpeg -nostdin -hide_banner -loglevel error -y -display_rotation -90 -i "$PORTRAIT_RAW" -c copy \
    -fflags +bitexact "${FAST[@]}" "$OUT/.part-video-h264-portrait-rot90.mp4" || die "ffmpeg failed for portrait remux"
  mv "$OUT/.part-video-h264-portrait-rot90.mp4" "$OUT/video-h264-portrait-rot90.mp4"
  rm -f "$PORTRAIT_RAW"
  BUILT=$((BUILT + 1))
else
  SKIPPED=$((SKIPPED + 1))
  printf 'skip  %s\n' video-h264-portrait-rot90.mp4
fi
chk video-h264-portrait-rot90.mp4 rot rotation -90

v_in 1920 816 30
clip video-h264-aspect-2.35.mp4 mp4 h264 aac 1920x816@30 play "2.35:1 宽银幕 1920x816，测试非 16:9 的缩放/黑边" -- \
  "${H264[@]}" -profile:v high -b:v 2000k -maxrate 2800k -bufsize 5600k "${KF[@]}" "${AAC[@]}" "${FAST[@]}"

# ------------------------------------------------------------------ audio
a_in
clip audio-mp3-cbr192.mp3 mp3 - mp3 - play "MP3 CBR 192 kbps" -- -c:a libmp3lame -b:a 192k -ac 2
clip audio-mp3-vbr.mp3 mp3 - mp3 - play "MP3 VBR (-q:a 2)，带 Xing 头；测试时长估算与 seek" -- -c:a libmp3lame -q:a 2 -ac 2
clip audio-aac-lc.m4a mov,mp4 - aac - play "AAC-LC 128 kbps，M4A" -- "${AAC[@]}" "${FAST[@]}"
clip audio-aac-adts.aac aac - aac - play "AAC ADTS 裸流（无容器，时长靠估算）" -- "${AAC[@]}" -f adts
clip audio-flac-16bit-44k.flac flac - flac - play "FLAC 16-bit / 44.1 kHz" -- -c:a flac -ar 44100 -sample_fmt s16 -ac 2
chk audio-flac-16bit-44k.flac a:0 sample_rate 44100
chk audio-flac-16bit-44k.flac a:0 sample_fmt s16
clip audio-flac-24bit-48k.flac flac - flac - play "FLAC 24-bit / 48 kHz（平台 FLAC 解码器对 24-bit 的输出需确认）" -- \
  -c:a flac -ar 48000 -sample_fmt s32 -bits_per_raw_sample 24 -ac 2
chk audio-flac-24bit-48k.flac a:0 bits_per_raw_sample 24
chk audio-flac-24bit-48k.flac a:0 sample_rate 48000
clip audio-vorbis.ogg ogg - vorbis - play "Vorbis，Ogg（libvorbis 不存在，用 ffmpeg 原生实验编码器，音质一般）" -- \
  -c:a vorbis -strict -2 -b:a 128k -ac 2 -f ogg
clip audio-opus.opus ogg - opus - play "Opus 96 kbps，Ogg Opus (.opus)" -- "${OPUS[@]}" -f opus
clip audio-opus.ogg ogg - opus - play "Opus 96 kbps，Ogg Opus，扩展名 .ogg" -- "${OPUS[@]}" -f ogg
clip audio-wav-pcm16.wav wav - pcm_s16le - play "WAV PCM 16-bit / 44.1 kHz" -- -c:a pcm_s16le -ar 44100 -ac 2
clip audio-ac3.ac3 ac3 - ac3 - play-with-ffmpeg-ext "AC-3 裸流 192 kbps" -- -c:a ac3 -b:a 192k -ac 2 -f ac3
clip audio-eac3.eac3 eac3 - eac3 - play-with-ffmpeg-ext "E-AC-3 裸流 192 kbps" -- -c:a eac3 -b:a 192k -ac 2 -f eac3
clip audio-dts.dts dts - dts - container-unsupported "DTS 裸流 768 kbps（dca 实验编码器）；Media3 没有 DTS 裸流提取器，应明确报不支持，封装在 MKA/MKV/MP4/TS 里的 DTS 才能播" -- -c:a dca -strict -2 -b:a 768k -ac 2 -f dts
clip audio-dts.mka matroska - dts - play-with-ffmpeg-ext "DTS 768 kbps，Matroska 音频 (.mka)" -- -c:a dca -strict -2 -b:a 768k -ac 2 -f matroska
clip audio-alac.m4a mov,mp4 - alac - play-with-ffmpeg-ext "ALAC，M4A" -- -c:a alac -ac 2 "${FAST[@]}"
if have_enc libopencore_amrnb; then
  clip audio-amr-nb.amr amr - amr_nb - play "AMR-NB 12.2 kbps，8 kHz 单声道" -- -c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k -f amr
else
  echo "note  libopencore_amrnb 编码器不存在，跳过 audio-amr-nb.amr（本机 ffmpeg 只能解 AMR 不能编）" >&2
fi
if have_enc wmav2; then
  clip audio-wma.wma asf - wmav2 - container-unsupported "WMA v2，ASF 容器；应优雅报不支持" -- -c:a wmav2 -b:a 128k -ac 2 -f asf
else
  echo "note  wmav2 编码器不存在，跳过 audio-wma.wma" >&2
fi

COVER="$OUT/cover-300.jpg"
IN=("${AIN[@]}" -i "$COVER")
clip audio-mp3-id3v2-cover.mp3 mp3 mjpeg mp3 300x300 play "MP3 192k + ID3v2.3 标签（标题/艺术家含非 ASCII/专辑）+ 300x300 内嵌封面" -- \
  -map 0:a -map 1:v -c:a libmp3lame -b:a 192k -ac 2 -c:v copy -id3v2_version 3 \
  -metadata title="ThinkOS Test Track" -metadata artist="ThinkOS 测试艺术家" -metadata album="Sync Beep" \
  -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" -disposition:v attached_pic
chk audio-mp3-id3v2-cover.mp3 tag title 'ThinkOS Test Track'
chk audio-mp3-id3v2-cover.mp3 tag album 'Sync Beep'
chk audio-mp3-id3v2-cover.mp3 v:0 disposition:attached_pic 1
clip audio-flac-comments-cover.flac flac mjpeg flac 300x300 play "FLAC 16-bit + Vorbis comments（含中文专辑名）+ 300x300 内嵌封面 (PICTURE block)" -- \
  -map 0:a -map 1:v -c:a flac -sample_fmt s16 -ac 2 -c:v copy \
  -metadata TITLE="ThinkOS Test Track" -metadata ARTIST="ThinkOS" -metadata ALBUM="同步测试专辑" -metadata DATE=2026 -metadata TRACKNUMBER=1 \
  -disposition:v attached_pic
chk audio-flac-comments-cover.flac tag title 'ThinkOS Test Track'
chk audio-flac-comments-cover.flac tag album '同步测试专辑'
chk audio-flac-comments-cover.flac v:0 disposition:attached_pic 1

# ------------------------------------------------------------------ verify
fmt_glob() {
  case "$1" in
    mp4|m4a|mov|3gp) echo 'mov,mp4,m4a,3gp,3g2,mj2' ;;
    mkv|mka|webm) echo 'matroska,webm' ;;
    ts) echo mpegts ;;
    mpg) echo mpeg ;;
    wmv|wma) echo asf ;;
    opus) echo ogg ;;
    jpg) echo 'image2' ;;
    *) echo "$1" ;;
  esac
}
bad() { echo "FAIL: $*" >&2; FAILS=$((FAILS + 1)); }

# probe NAME SELECTOR ENTRY
probe() {
  local f="$OUT/$1" sel=$2 ent=$3
  case "$sel" in
    format) ffprobe -v error -show_entries "format=$ent" -of default=nw=1:nk=1 "$f" | sed -n 1p ;;
    tag)
      ffprobe -v error -show_entries format_tags -of default=nw=1 "$f" |
        awk -v k="$(printf '%s' "$ent" | tr '[:upper:]' '[:lower:]')" \
          '{ i = index($0, "="); key = tolower(substr($0, 1, i - 1)); sub(/^tag:/, "", key); if (key == k) { print substr($0, i + 1); exit } }'
      ;;
    rot)
      ffprobe -v error -select_streams v:0 -show_streams -of flat "$f" | awk -F= '/rotation=/ { gsub(/"/, "", $2); print $2; exit }'
      ;;
    *)
      case "$ent" in
        disposition:*) ffprobe -v error -select_streams "$sel" -show_entries "stream_disposition=${ent#disposition:}" -of default=nw=1:nk=1 "$f" | sed -n 1p ;;
        *) ffprobe -v error -select_streams "$sel" -show_entries "stream=$ent" -of default=nw=1:nk=1 "$f" | sed -n 1p ;;
      esac
      ;;
  esac
}

verify_row() {
  local name=$1 vc=$3 ac=$4 res=$5 f="$OUT/$1" ext got want size dur
  ext=${name##*.}
  [[ -s $f ]] || { bad "$name: missing or empty"; return; }
  size=$(wc -c <"$f" | tr -d ' ')
  (( size <= MAX_BYTES )) || bad "$name: $size bytes exceeds $MAX_BYTES"
  got=$(probe "$name" v:0 codec_name); want=$vc; [[ $want == - ]] && want=''
  [[ $got == "$want" ]] || bad "$name: video codec '$got', expected '${want:-none}'"
  got=$(probe "$name" a:0 codec_name); want=$ac; [[ $want == - ]] && want=''
  [[ $got == "$want" ]] || bad "$name: audio codec '$got', expected '${want:-none}'"
  got=$(probe "$name" format format_name)
  [[ $got == "$(fmt_glob "$ext")" ]] || bad "$name: container '$got', expected '$(fmt_glob "$ext")'"
  if [[ $res == *x* ]]; then
    got="$(probe "$name" v:0 width)x$(probe "$name" v:0 height)"
    [[ $got == "${res%%@*}" ]] || bad "$name: size $got, expected ${res%%@*}"
    if [[ $res == *@* ]]; then
      got=$(probe "$name" v:0 r_frame_rate | awk -F/ '{ printf "%.0f", $1 / $2 }')
      [[ $got == "${res##*@}" ]] || bad "$name: fps $got, expected ${res##*@}"
    fi
  fi
  dur=$(probe "$name" format duration)
  awk -v d="$dur" 'BEGIN { exit !(d >= 11.4 && d <= 12.8) }' || bad "$name: duration $dur s, expected about $DUR"
}

verify_sidecars() {
  local f
  [[ $(grep -c -- ' --> ' "$OUT/sub-sidecar.srt") == 3 ]] || bad "sub-sidecar.srt: expected 3 cues"
  [[ $(grep -c -- ' --> ' "$OUT/sub-sidecar.vtt") == 3 ]] || bad "sub-sidecar.vtt: expected 3 cues"
  [[ $(head -c 6 "$OUT/sub-sidecar.vtt") == WEBVTT ]] || bad "sub-sidecar.vtt: missing WEBVTT header"
  for f in sub-sidecar.srt sub-sidecar.vtt lyrics-sample.lrc; do
    LC_ALL=C grep -qF '你好，ThinkOS' "$OUT/$f" || bad "$f: missing Chinese cue text"
  done
  [[ $(probe sub-sidecar.srt s:0 codec_name) == subrip ]] || bad "sub-sidecar.srt: ffprobe does not see a subrip stream"
  [[ $(probe sub-sidecar.vtt s:0 codec_name) == webvtt ]] || bad "sub-sidecar.vtt: ffprobe does not see a webvtt stream"
  [[ $(grep -Ec '^\[[0-9]{2}:[0-9]{2}\.[0-9]{2}\]' "$OUT/lyrics-sample.lrc") == 6 ]] || bad "lyrics-sample.lrc: expected 6 timed lines"
  [[ $(grep -c '^\[00:08\.00\]' "$OUT/lyrics-sample.lrc") == 2 ]] || bad "lyrics-sample.lrc: expected 2 lines at 00:08.00"
  iconv -f UTF-8 -t UTF-8 "$OUT/lyrics-sample.lrc" >/dev/null 2>&1 || bad "lyrics-sample.lrc: not valid UTF-8"
  if iconv -f UTF-8 -t UTF-8 "$OUT/lyrics-gbk.lrc" >/dev/null 2>&1; then
    bad "lyrics-gbk.lrc: is valid UTF-8, expected GBK"
  fi
  iconv -f GBK -t UTF-8 "$OUT/lyrics-gbk.lrc" 2>/dev/null | LC_ALL=C grep -qF '最后一行歌词' || bad "lyrics-gbk.lrc: GBK decode does not give expected text"
  [[ $(LC_ALL=C grep -Ec '^\[[0-9]{2}:[0-9]{2}\.[0-9]{2}\]' "$OUT/lyrics-gbk.lrc") == 6 ]] || bad "lyrics-gbk.lrc: expected 6 timed lines"
  [[ $(probe cover-300.jpg v:0 codec_name) == mjpeg ]] || bad "cover-300.jpg: not mjpeg"
  [[ $(probe cover-300.jpg v:0 width)x$(probe cover-300.jpg v:0 height) == 300x300 ]] || bad "cover-300.jpg: not 300x300"
}

echo
echo "== ffprobe 校验 =="
for row in "${ROWS[@]}"; do
  IFS=$'\t' read -r name cont vc ac res exp note <<<"$row"
  [[ $exp == sidecar ]] && continue
  verify_row "$name" "$cont" "$vc" "$ac" "$res"
done
verify_sidecars
for c in "${CK[@]}"; do
  IFS='|' read -r name sel ent want <<<"$c"
  got=$(probe "$name" "$sel" "$ent")
  # shellcheck disable=SC2053
  [[ $got == $want ]] || bad "$name: $sel $ent = '$got', expected '$want'"
done

# manifest
{
  printf 'file\tcontainer\tvideo_codec\taudio_codec\tresolution_fps\texpected_on_device\tnote\n'
  printf '%s\n' "${ROWS[@]}"
} > "$OUT/manifest.tsv"

echo
echo "== 汇总 =="
echo "新生成 ${BUILT}，跳过（已存在）${SKIPPED}，清单 ${#ROWS[@]} 项，输出目录 ${OUT}"
total=0
while IFS=$'\t' read -r name _ _ _ _ exp _; do
  [[ $name == file ]] && continue
  size=$(wc -c <"$OUT/$name" | tr -d ' ')
  total=$((total + size))
  printf '%-40s %9s B  %s\n' "$name" "$size" "$exp"
done < "$OUT/manifest.tsv"
awk -v t="$total" 'BEGIN { printf "合计 %.2f MiB\n", t / 1048576 }'
if (( FAILS > 0 )); then
  echo "校验失败 ${FAILS} 项" >&2
  exit 1
fi
echo "校验全部通过 (${SECONDS}s)"
