# tools/media/

流媒体 / 本地播放（Media3 ExoPlayer）的开发测试工具。全部在 Mac 上跑，不依赖第三方 Python 包，不联网下载任何东西。除 `dbg.sh` 外不使用 adb，不碰设备。

| 脚本 | 作用 |
|---|---|
| `gen_samples.sh [输出目录]` | 用 ffmpeg 合成 12 秒样片矩阵（视频 / 音频 / 字幕 / 歌词 / 封面），输出 `manifest.tsv`，并对每个文件跑 `ffprobe` 校验。幂等，`FORCE=1` 重新生成 |
| `serve.py` | 测试源站（标准库，多线程）：Range、鉴权、重定向、限速、断流、HLS / DASH 点播、直播、M3U 列表 |
| `rtsp.sh <样片> [端口]` | 循环播放某个样片的 RTSP 源（VLC 做服务端，见下文“已知限制”） |

## 快速开始

```bash
tools/media/gen_samples.sh                 # 默认输出 /tmp/thinkos-media-samples，约 74 MiB，约 30 秒
python3 -I tools/media/serve.py            # 起源站，默认 0.0.0.0:8099，样片目录同上
tools/media/rtsp.sh /tmp/thinkos-media-samples/video-h264-720p30.mp4   # 另开终端，默认端口 8554
```

`serve.py` 参数：`--root /tmp/thinkos-media-samples --port 8099 --bind 0.0.0.0 [--live-source video-h264-720p30.mp4] [--rtsp-port 8554]`。每个请求一行日志写到 stderr：时间、客户端、方法、路径、状态码、`range=`、发送字节数、耗时、`ua/referer/auth/host`；`/auth/` 请求额外打印收到的全部请求头。客户端断开不打印堆栈。

## 设备怎么访问 Mac

设备和 Mac 要在同一局域网。查 Mac 的局域网 IP：

```bash
ipconfig getifaddr en0                     # Wi-Fi 常见是 en0；有的机器是 en1（本机就是），en0 会返回空
ipconfig getifaddr $(route -n get default | awk '/interface:/ {print $2}')   # 取默认路由所在网卡，更稳
```

`serve.py` 和 `rtsp.sh` 启动时也会打印地址。下文用 `$MAC` 代表这个 IP。首次运行 macOS 可能弹窗问是否允许 Python / VLC 接收传入连接，需要允许。

## URL 速查（`$MAC:8099`）

| 路径 | 说明 |
|---|---|
| `/health` | 返回 `ok` |
| `/files/<name>` | 静态文件：正确 MIME、完整 `Range` / 206 / 416、`HEAD`、`Accept-Ranges: bytes` |
| `/auth/files/<name>` | 需要 `Authorization: Bearer thinkos-test`，否则 401（带 `WWW-Authenticate`）；日志打印收到的请求头 |
| `/referer/files/<name>` | 需要 `Referer: https://example.test/`，否则 403 |
| `/ua/files/<name>` | 需要 User-Agent 含 `ThinkOS-Test/1.0`，否则 403（给 m3u 里 `#EXTVLCOPT:http-user-agent` 那条用） |
| `/redirect/<n>/files/<name>` | n 次 302 后到文件，n 最大 6（客户端只允许 3 次，所以 `/redirect/4/...` 应当失败） |
| `/slow/<kbps>/files/<name>` | 按约 kbps 限速（400 kbps ≈ 50 KB/s） |
| `/flaky/<bytes>/files/<name>` | 每个 (文件, Range 起始偏移) 的第一次请求发完 `<bytes>` 字节就断开，重试同一偏移则完整返回；`/flaky/reset` 清空记忆（或在 URL 后加 `?x=1` 换一个 key） |
| `/status/<code>` | 返回该状态码（200..599；3xx 带 `Location`，401 带 `WWW-Authenticate`，429/503 带 `Retry-After`） |
| `/hls/<name>/index.m3u8` | HLS 点播（TS，4 秒切片，单码率 720p）；首次请求用 ffmpeg 切片并缓存到 `<root>/.cache` |
| `/hls/<name>/master.m3u8` | 自适应：720p + 1080p 两档 |
| `/hls-fmp4/<name>/index.m3u8` | HLS fMP4（CMAF，`EXT-X-MAP`），同样有 `master.m3u8` |
| `/hls-aes/<name>/index.m3u8` | HLS AES-128 加密，`EXT-X-KEY` 指向 `/hls-aes/key.bin`（固定 16 字节密钥 `thinkos-test-key`，IV 固定） |
| `/dash/<name>/manifest.mpd` | DASH 点播，音视频分离的 AdaptationSet，fMP4 分片，720p + 1080p |
| `/live/hls/index.m3u8` | HLS 直播：6 个 2 秒切片的滑动窗口，带 `PROGRAM-DATE-TIME`，无 `ENDLIST` |
| `/live/dash/manifest.mpd` | DASH 直播：`type="dynamic"`，窗口 5 个 2 秒切片，服务端注入 `UTCTiming` 指向 `/time` |
| `/time` | 服务器 UTC 时间（ISO-8601，DASH `UTCTiming` 用） |
| `/m3u/channels.m3u` | IPTV 风格列表：重名频道、中文 / 带重音频道名、`group-title` 含逗号、`#EXTVLCOPT:http-user-agent` / `http-referrer`、`rtsp://` 条目；URL 按请求的 `Host` 头生成 |
| `/m3u/audio.m3u8` | 纯曲目列表（只有 `#EXTINF`，没有 `#EXT-X-`），**故意用 HLS 的 MIME 返回**，客户端必须看内容才能认出它不是 HLS |
| `rtsp://$MAC:8554/live` | `rtsp.sh` 起的 RTSP 源 |

修饰符可以叠加，写在 `/files/<name>` 前面，按从左到右顺序生效，例如 `/redirect/3/auth/slow/800/files/video-h264-720p30.mp4`。

直播流由后台 ffmpeg 产生（`-re -stream_loop -1` 循环 `--live-source`，重编码并强制 2 秒关键帧；音频加 `aresample=async=1`，因为每次回绕会少两个 AAC 帧，约 5 次累计超过 ExoPlayer 音频输出 200 ms 的容忍线，设备上表现为每 2 分钟一次丢帧与短暂缓冲），首个请求时启动，60 秒没有请求自动结束，服务退出时一并结束；ffmpeg 自带 `-t 3600`，服务被 `kill -9` 时孤儿进程最迟 1 小时后自己退出；一小时到点后源站重启，播放列表重置，播放器按设计重连（2 小时直播测试里恢复在 30 秒内）。

## 样片矩阵

每个样片 12 秒、测试图 `testsrc2`（自带帧计数器，左上角）；双声道 440 Hz 持续音，每秒开头 0.1 秒叠加 1 kHz 嘀声，同一时刻画面底部白条闪一下，用来肉眼 / 耳朵检查音画同步。`manifest.tsv` 是权威清单，下表由它生成。

`expected_on_device`：`play` 平台能放；`play-with-ffmpeg-ext` 平台解不了，加 Media3 FFmpeg 音频扩展后可放；`video-unsupported` / `container-unsupported` 应优雅报不支持；`unknown` 需要在设备上确认；`sidecar` 不是可播放片，是旁路文件。

| 文件 | 容器 | 视频 | 音频 | 分辨率@fps | 预期 | 备注 |
|---|---|---|---|---|---|---|
| `sub-sidecar.srt` | srt | - | - | - | `sidecar` | SubRip 旁路字幕，3 条，其中一条两行、一条中文，对应 sub-base.mp4 |
| `sub-sidecar.vtt` | vtt | - | - | - | `sidecar` | WebVTT 旁路字幕，内容同 srt，对应 sub-base.mp4 |
| `lyrics-sample.lrc` | lrc | - | - | - | `sidecar` | UTF-8 歌词，6 个时间行，00:08.00 有中英双语同时间戳两行 |
| `lyrics-gbk.lrc` | lrc | - | - | - | `sidecar` | GBK 编码歌词（非 UTF-8），测试编码探测，00:08.00 同样有两行同时间戳 |
| `cover-300.jpg` | jpeg | - | - | - | `sidecar` | 300x300 封面图，被 audio-mp3-id3v2-cover.mp3 / audio-flac-comments-cover.flac 内嵌 |
| `video-h264-480p30.mp4` | mp4 | h264 | aac | 854x480@30 | `play` | H.264 Main, 0.9 Mbps，基础冒烟样片 |
| `video-h264-720p30.mp4` | mp4 | h264 | aac | 1280x720@30 | `play` | H.264 High, 1.8 Mbps |
| `video-h264-1080p30-high.mp4` | mp4 | h264 | aac | 1920x1080@30 | `play` | H.264 High@L4.0 1080p30 |
| `video-h264-1080p60.mp4` | mp4 | h264 | aac | 1920x1080@60 | `play` | H.264 High@L4.2 1080p60，解码器上限边缘，留意掉帧 |
| `video-hevc-1080p30-main.mp4` | mp4 | hevc | aac | 1920x1080@30 | `play` | HEVC Main 8-bit (hvc1) |
| `video-hevc-1080p30-main10.mp4` | mp4 | hevc | aac | 1920x1080@30 | `unknown` | HEVC Main10 (10-bit)，硬解是否支持未知，需在设备上确认 |
| `video-vp8-720p30.webm` | webm | vp8 | opus | 1280x720@30 | `play` | VP8 + Opus，WebM |
| `video-vp9-1080p30.webm` | webm | vp9 | opus | 1920x1080@30 | `play` | VP9 + Opus，WebM |
| `video-av1-720p30.mp4` | mp4 | av1 | aac | 1280x720@30 | `video-unsupported` | AV1 (SVT-AV1) + AAC；设备无 AV1 解码器，应报不支持而不是崩溃 |
| `video-mpeg2-576p25-mp2.ts` | mpegts | mpeg2video | mp2 | 720x576@25 | `unknown` | MPEG-2 PAL + MP2 音频，TS；平台是否带 MP2 解码器未知（FFmpeg 扩展也不含 MP2） |
| `video-mpeg2-576p25-ac3.mpg` | mpeg | mpeg2video | ac3 | 720x576@25 | `play-with-ffmpeg-ext` | MPEG-2 PAL + AC-3，Program Stream (.mpg) |
| `video-mpeg4asp-480p30-mp3.avi` | avi | mpeg4 | mp3 | 854x480@30 | `unknown` | MPEG-4 Part 2 + MP3，AVI；Media3 可能没有 AVI 提取器，若如此则应记为 container-unsupported |
| `video-h264-480p30-aac.flv` | flv | h264 | aac | 854x480@30 | `play` | H.264 + AAC，FLV |
| `video-wmv2-480p30.wmv` | asf | wmv2 | wmav2 | 854x480@30 | `container-unsupported` | WMV2 + WMA2，ASF；应优雅报不支持 |
| `video-h264-480p30.mov` | mov | h264 | aac | 854x480@30 | `play` | H.264 + AAC，QuickTime 封装 |
| `video-h263-cif-aac.3gp` | 3gp | h263 | aac | 352x288@25 | `play` | H.263 CIF + AAC，3GP（无 amr_nb 编码器，未覆盖 AMR-NB 音频） |
| `video-h264-480p30-ac3.mkv` | matroska | h264 | ac3 | 854x480@30 | `play-with-ffmpeg-ext` | H.264 + AC-3，MKV |
| `video-h264-480p30-eac3.mkv` | matroska | h264 | eac3 | 854x480@30 | `play-with-ffmpeg-ext` | H.264 + E-AC-3，MKV |
| `video-h264-480p30-dts.mkv` | matroska | h264 | dts | 854x480@30 | `play-with-ffmpeg-ext` | H.264 + DTS (dca, 实验编码器)，MKV |
| `video-h264-480p30-alac.mp4` | mp4 | h264 | alac | 854x480@30 | `play-with-ffmpeg-ext` | H.264 + ALAC，MP4 |
| `sub-embedded-srt.mkv` | matroska | h264 | aac | 854x480@30 | `play` | MKV 内嵌 SubRip 字幕轨（eng，3 条，含中文） |
| `sub-embedded-movtext.mp4` | mp4 | h264 | aac | 854x480@30 | `play` | MP4 内嵌 mov_text (tx3g) 字幕轨（eng，3 条，含中文） |
| `sub-base.mp4` | mp4 | h264 | aac | 854x480@30 | `play` | 无内嵌字幕的基础片，配 sub-sidecar.srt / sub-sidecar.vtt 做旁路字幕测试 |
| `video-h264-portrait-rot90.mp4` | mp4 | h264 | aac | 1920x1080@30 | `play` | 竖屏：编码 1920x1080 + 显示矩阵顺时针 90°（ffprobe 显示 rotation=-90），应显示为 1080x1920，画面方向正确 |
| `video-h264-aspect-2.35.mp4` | mp4 | h264 | aac | 1920x816@30 | `play` | 2.35:1 宽银幕 1920x816，测试非 16:9 的缩放/黑边 |
| `audio-mp3-cbr192.mp3` | mp3 | - | mp3 | - | `play` | MP3 CBR 192 kbps |
| `audio-mp3-vbr.mp3` | mp3 | - | mp3 | - | `play` | MP3 VBR (-q:a 2)，带 Xing 头；测试时长估算与 seek |
| `audio-aac-lc.m4a` | mov,mp4 | - | aac | - | `play` | AAC-LC 128 kbps，M4A |
| `audio-aac-adts.aac` | aac | - | aac | - | `play` | AAC ADTS 裸流（无容器，时长靠估算） |
| `audio-flac-16bit-44k.flac` | flac | - | flac | - | `play` | FLAC 16-bit / 44.1 kHz |
| `audio-flac-24bit-48k.flac` | flac | - | flac | - | `play` | FLAC 24-bit / 48 kHz（平台 FLAC 解码器对 24-bit 的输出需确认） |
| `audio-vorbis.ogg` | ogg | - | vorbis | - | `play` | Vorbis，Ogg（libvorbis 不存在，用 ffmpeg 原生实验编码器，音质一般） |
| `audio-opus.opus` | ogg | - | opus | - | `play` | Opus 96 kbps，Ogg Opus (.opus) |
| `audio-opus.ogg` | ogg | - | opus | - | `play` | Opus 96 kbps，Ogg Opus，扩展名 .ogg |
| `audio-wav-pcm16.wav` | wav | - | pcm_s16le | - | `play` | WAV PCM 16-bit / 44.1 kHz |
| `audio-ac3.ac3` | ac3 | - | ac3 | - | `play-with-ffmpeg-ext` | AC-3 裸流 192 kbps |
| `audio-eac3.eac3` | eac3 | - | eac3 | - | `play-with-ffmpeg-ext` | E-AC-3 裸流 192 kbps |
| `audio-dts.dts` | dts | - | dts | - | `play-with-ffmpeg-ext` | DTS 裸流 768 kbps（dca 实验编码器） |
| `audio-dts.mka` | matroska | - | dts | - | `play-with-ffmpeg-ext` | DTS 768 kbps，Matroska 音频 (.mka) |
| `audio-alac.m4a` | mov,mp4 | - | alac | - | `play-with-ffmpeg-ext` | ALAC，M4A |
| `audio-wma.wma` | asf | - | wmav2 | - | `container-unsupported` | WMA v2，ASF 容器；应优雅报不支持 |
| `audio-mp3-id3v2-cover.mp3` | mp3 | mjpeg | mp3 | 300x300 | `play` | MP3 192k + ID3v2.3 标签（标题/艺术家含非 ASCII/专辑）+ 300x300 内嵌封面 |
| `audio-flac-comments-cover.flac` | flac | mjpeg | flac | 300x300 | `play` | FLAC 16-bit + Vorbis comments（含中文专辑名）+ 300x300 内嵌封面 (PICTURE block) |

## 已知限制 / 注意

- **RTSP**：原计划的 `ffmpeg -f rtsp -rtsp_flags listen rtsp://0.0.0.0:8554/live` 在 ffmpeg 9.0.2 上不可用（RTSP 输出不支持 listen，会去连 `0.0.0.0:8554` 报 `Connection refused`），所以 `rtsp.sh` 用 VLC 的 sout 做 RTSP 服务端（支持多客户端），ffmpeg 只负责预编码成无 B 帧、1 秒 GOP 的 H.264 + AAC。VLC 的 RTSP 服务端**不支持 TCP interleaved**（SETUP 回 461），只能测 UDP。
- **DASH 校验**：本机 ffmpeg 没带 libxml2，没有 DASH 解复用器，`ffprobe` / `ffmpeg` 打不开 `.mpd`。点播和直播 DASH 是用 VLC（`vlc -I dummy --vout dummy --aout dummy <url>`）和脚本拼接分片后 ffmpeg 解码来验证的；设备上用 Media3 `DashMediaSource` 验证。
- **首包延迟**：`/hls*`、`/dash` 第一次请求会先跑 ffmpeg 切片（约 1 秒，之后走缓存）；直播第一次请求要等 2 个切片（约 5 秒）。ExoPlayer 默认连接 / 读取超时 8 秒，够用但不宽裕，测超时场景前先 `curl` 预热。
- **本机 ffmpeg 缺的编码器**：没有 `drawtext`（所以不烧时间码，靠 `testsrc2` 计数器）、没有 AMR-NB（跳过 `audio-amr-nb.amr`；3GP 退化为 H.263 + AAC，没有覆盖 AMR-NB 音频）、没有 libvorbis（Vorbis 用 ffmpeg 原生实验编码器，音质一般）；DTS 用 `dca` 实验编码器。
- **MP2 / AVI**：`video-mpeg2-576p25-mp2.ts` 的 MP2 音频、`video-mpeg4asp-480p30-mp3.avi` 的 AVI 容器，我没有把握 Media3 / 设备是否支持，所以标 `unknown`，以设备实测为准。
- 样片目录里的 `.cache`（HLS / DASH 缓存、直播临时目录与日志、AES 密钥）以点开头，不会被 `/files` 暴露；删掉即可重建。
- 不会提交任何生成物：样片、缓存都在 `/tmp` 下。

## RTSP over TCP：`rtsp_tcp.py`

`rtsp.sh`（VLC）只会 UDP，对 `Transport: RTP/AVP/TCP;unicast;interleaved=0-1` 回 461，测不了 Media3 的 `setForceUseRtpTcp(true)`。`rtsp_tcp.py` 补这个缺口：标准库 asyncio 写的最小 RTSP 服务端，不需要 VLC。后台一个 ffmpeg 循环播放样片（`-re -stream_loop -1`），重编码成 H.264 Main（无 B 帧、25 帧一个关键帧）+ AAC，经回环 UDP 发出普通 RTP；服务把 RTP 包封成 interleaved 帧（`$` + 通道 + 2 字节长度 + 包）转发给每个客户端。

```bash
python3 -I tools/media/rtsp_tcp.py /tmp/thinkos-media-samples/video-h264-720p30.mp4      # rtsp://$MAC:8554/live，也接受 /live/
python3 -I tools/media/rtsp_tcp.py <样片> [--port 8554] [--bind 0.0.0.0] [--audio-only] [--auth user:pass]
```

| | `rtsp.sh`（VLC） | `rtsp_tcp.py` |
|---|---|---|
| 传输 | 只有 UDP，TCP interleaved 回 461 | 只有 TCP interleaved；客户端只给 UDP / 组播候选时回 461，让它回退到 TCP（ffmpeg 自动模式实测会回退；Media3 默认模式按其行为应当也会，未验证） |
| 方法 | 由 VLC 决定 | OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / TEARDOWN / GET_PARAMETER，多客户端并发 |
| 鉴权 | 无 | `--auth user:pass`：DESCRIBE / SETUP / PLAY 要 HTTP Basic（`realm="thinkos"`），客户端用 `rtsp://user:pass@$MAC:8554/live`；默认不鉴权 |
| 纯音频 | 纯音频样片只推音频 | 纯音频样片自动只推音频；`--audio-only` 可对带视频的样片强制只推音频 |

- 每个请求一行日志写到 stderr：时间、客户端、方法、路径、状态码（SETUP 带会话和通道）；不打印 `Authorization`，客户端断开不打印。
- PLAY 会等到下一个 H.264 关键帧（最多约 1 秒）才回复，回复里带 `RTP-Info`（首个下发包的 seq / rtptime）。ffmpeg 发出的音频比视频提前约 0.3 秒，所以音频从历史里补到和关键帧同一内容时刻，两个轨道的第一个包对齐（实测录下来的蜂鸣 / 白条相差不超过 33 ms）；不要拿“PLAY 慢了”当缺陷。
- PAUSE 停止转发，再 PLAY 又从关键帧开始。客户端读得慢会丢包，持续约 15 秒会被断开，不拖累别的客户端。
- Ctrl-C / SIGTERM 会结束 ffmpeg 并删临时目录；`kill -9` 时 ffmpeg 在下一次发包被拒绝后自己退出（`/tmp` 下会留一个很小的 `thinkos-rtsp-tcp-*` 目录，删掉即可）。
- 默认端口也是 8554，和 `rtsp.sh` 不能同时开；`serve.py` 的 `channels.m3u` 里的 `rtsp://` 条目指向 8554（`--rtsp-port` 可改），用哪个脚本起都行。
- 验证过的客户端是 ffmpeg / ffprobe（`-rtsp_transport tcp` 和自动模式）和手写的原始 RTSP 客户端；还没有在 Media3 / 设备上验证。不支持 Digest 鉴权；视频固定重编码成 H.264 Main，测不了 HEVC 等其它编码的 RTSP。

## dbg.sh：给 debug 包发调试指令

```bash
tools/media/dbg.sh media_load --es url /sdcard/Movies/a.mp4 --es kind video      # url 用 | 分隔可排多项；另有 type / headers / subtitle / start / repeat
tools/media/dbg.sh media_state          # 引擎快照以 "DEBUG media {json}" 写进日志：adb logcat -d -s ThinkOS:V | grep "DEBUG media"
tools/media/dbg.sh media_pause|media_play|media_toggle|media_stop|media_next|media_prev|media_live
tools/media/dbg.sh media_seek --el value 30000    # 毫秒；media_jump / media_audio / media_text 的序号同样用 --el value N
```

每个参数都会为设备端 shell 加引号，带空格、括号、中文的路径可以直接传。指令只在 debug 构建里存在，绕过插件权限检查，不写任何设备设置。

