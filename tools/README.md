# tools/

开发与运维脚本（bash）。所有脚本都应能在 Mac 上直接跑。

| 脚本 | 作用 |
|---|---|
| `deploy.sh` | 构建并安装 APK（不需要 Server） |
| `plugin-push.sh` | 把一个插件目录经 `run-as` 推到设备的开发目录热加载（仅 debug 构建，见 `docs/07-build-deploy.md` §3.1）。按文件清单写入隐藏暂存目录，完成后发布整个目录 |
| `dev.sh`（M2 计划） | 起服务端 + 装 APK + 跟随日志 |
| `bench.sh` | 采样帧率与内存（`dumpsys gfxinfo` / `meminfo`） |
| `screenshot.sh` | 截屏到 `/tmp/thinkos-<时间>.png` |
| `media/` | 流媒体测试工具：样片生成、测试源站（Range / 鉴权 / 重定向 / 限速 / 断流 / HLS / DASH / 直播 / M3U）、RTSP 源、`dbg.sh`；见 `media/README.md` |
| `cast/` | 投屏验证工具（`docs/28-casting.md`）：`latency_source.py` 实时生成带“内容时钟”的 TS 流，`latency_probe.py` 经 `adb reverse` 送到设备并从屏幕截图量出端到端延迟（`python3 -I tools/cast/latency_probe.py <低延迟档 true\|false> [样本数]`，需 USB，用 `-s`；内容时钟的起点读 ffmpeg 逐帧报告，不用第一个字节，否则少算 0.5 秒）；`wfd_source.py` 扮演 Miracast 的 Source（RTSP M1–M7、RTP/TS，可注入丢包与乱序）；`airplay_sender.py` 扮演 iPhone 的 AirPlay 视频发送端（server-info、配对、事件通道、play / rate / scrub / stop） |

## 约定

- 统一使用 `set -euo pipefail`
- 设备序列号默认 `HUA0BWUT`，可用 `$ANDROID_SERIAL` 覆盖
- 脚本不修改设备系统设置（那是人工操作）
- 需要 root 的命令一律不放进脚本

## 示例：screenshot.sh

```bash
#!/usr/bin/env bash
set -euo pipefail
out="/tmp/thinkos-$(date +%H%M%S).png"
adb exec-out screencap -p > "$out"
echo "$out"
```


## 已实现（M0）

- `deploy.sh`：构建（macOS 自动选择本机 JDK 17/21）、安装、设置 HOME、启动。`--no-build` 使用已有 APK。
- `package_plugins.py`：按共享 schema 校验，生成可复现的插件目录与 `index.json`（ADR-018）；文件清单摘要的规范见 `docs/06-plugins.md` §2。`--help` 查看输出与单插件选项。
- `plugin-push.sh plugins/clock`：通过 `run-as` 原子安装开发插件；`--remove clock` 恢复内置版本。
- `screenshot.sh [输出文件]`：截图。
- `bench.sh [秒数=600] [输出目录=/tmp/thinkos-bench]`：记录 gfxinfo、进程 CPU、内存、PID 和日志。CPU 使用 `/proc/<pid>/stat` 累计时间与设备 `AT_CLKTCK` 采样，输出 `cpu.json`（100% 为单核满载）。

设备默认 `HUA0BWUT`，可用 `ANDROID_SERIAL` 覆盖。真机验收记录见 `../docs/12-m0-validation.md`。脚本不使用 root、不改系统分区。

受控 AMBIENT 性能测试可在 debug 构建中发送 `hold_attention`：

```bash
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command hold_attention --es value AMBIENT
tools/bench.sh 600 /tmp/thinkos-bench
# 测试后清除固定档位，恢复真实传感器/触摸策略：
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command hold_attention
```

## 投屏验收（ADR-031，记录见 `../docs/28-casting.md`）

先 `tools/media/gen_samples.sh`。`python3 tools/tests/cast_dlna_device.py` 在设备上打开 `cast.enabled`（结束时把设置文件原样放回），对设备直接发 SSDP 搜索与 UPnP / SOAP 请求，覆盖发现、描述、推送、播放、暂停、跳转、音量、停止、被接管、关闭开关；媒体经 `adb reverse` 取自 `tools/media/serve.py`。多播发现需要在设备同一网段的真实发送端，这个脚本做不到。延迟测量见 `cast/`。`python3 tools/tests/video_gestures_device.py`：视频页的单击切换控件、双击暂停 / 跳 10 秒、竖拖调亮度与音量（24 项；双击由调试包的 `touch` 命令注入，因为 `input tap` 在这台设备上一次要 0.7 秒）。`python3 tools/tests/video_edges_device.py`：视频页在两个主题、四个方向的最大防烧屏漂移下，屏幕上除了一块纯灰的片子（脚本自己生成，不闪）和黑边不得有任何别的颜色，用来抓“页面底色从边缘露出来”这类缺陷。

## 流媒体验收（ADR-030，记录见 `../docs/27-streaming.md`）

先 `tools/media/gen_samples.sh` 生成样片（`/tmp/thinkos-media-samples`），设备用 debug 包。脚本经 `adb reverse` 访问 Mac 上的测试源站，不依赖局域网互通。

| 脚本 | 验证什么 |
|---|---|
| `tests/media_matrix_device.py` | 每个样片：能播 / 明确报错 / 超时（不允许超时），对照样片清单里的预期；结果写 `artifacts/streaming/format-matrix.tsv` |
| `tests/media_stream_device.py` | 渐进式 HTTP、请求头、重定向、重试、限速、HLS（TS / fMP4 / AES）、DASH、直播、窗口恢复、服务端中断、RTSP、字幕 |
| `tests/media_ui_device.py` | 影院视图：控件浮现与淡出、拖动 / 点按进度条、播放暂停、音轨与字幕按钮、长按与翻页仍归 OS；`--attention` 另测视频播放不降到 AMBIENT |
| `tests/player_device.py` | player 插件：媒体库、点播、离开视频页暂停、设置里输入串流地址与播放列表；结束时原样恢复设置文件 |
| `tests/media_perf_device.py` | 各档播放的全进程 CPU、丢帧、掉帧页、PSS；结果写 `artifacts/streaming/perf.json` |
| `tests/player_native.sh [--device]` | player 的 Lua 逻辑，用与 App 相同的沙箱（50 ms 切片、内存上限）；`--device` 在 armv7 设备上跑 |

`python3 -I tools/tests/cast_miracast_device.py`（27 项）：`wfd_source.py` 扮演手机，调试命令 `miracast_dial` 让设备去连它（Wi-Fi Direct 配对不在这个测试里，只测它之后的一切）：握手与设备的声明、引擎里的会话、延迟、丢包与乱序、各种结束方式、畸形的发送端。Mac 与设备要在同一网段。`python3 -I tools/tests/cast_airplay_device.py`（37 项）：打开 `cast.airplay`（结束时把设置文件原样放回），用 `dns-sd` 从 Mac 发现设备，再用 `airplay_sender.py` 走完整个视频推送。`system/test/run.sh`（18 项）：Miracast 系统层守护进程对着假的 wpa_supplicant，不需要设备。调试包的 `setting` 命令（`--es key cast.airplay --es value false`）像设置页一样在运行时改系统设置。

`python3 -I tools/tests/cast_airplay_mirror_device.py`（45 项）：`tools/cast/airplay_mirror_sender.py` 扮演 iPhone 的镜像（`/info`、配对、`/fp-setup` 两步、`SETUP`、加密的 H.264 与 AAC、定时端口、`/feedback`），调试命令 `airplay_testkey` 让设备对每次 FairPlay 解包回同一个密钥（只有苹果的代码能造出真的包），所以真正的 `playfair` 只用 `fairplay_probe` 探测。`eld_selftest` 让设备用平台的 ELD 编码器造一段音频再走转码器，看平台的编解码器能不能走通这条路。`cast_airplay_device.py` 现在测的是镜像关闭时的纯视频身份。`python3 -I tools/tests/cast_airplay_still_device.py [--still 40]`：同一个发送端只发画面（没有声音），用它的 `still` 开关让屏幕“静止”几十秒再恢复，检查设备不重置播放器、不留几十秒的领先量、下一个画面两秒内到（Mac 与设备要在同一网段；**写好之后还没有在设备上跑过**）。

