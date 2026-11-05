# 第三方说明

ThinkOS 自己写的代码和文档是 MIT（`LICENSE`）。完整版 APK 含有下表的 GPL-3.0 部分，所以作为一个整体按 GPL-3.0 发布（`LICENSE-GPL-3.0.md`，ADR-043）。其它第三方内容的许可证见后面各节。

## GPL-3.0 的部分

| 内容 | 位置 | 来源 | 在 APK 里 |
|---|---|---|---|
| `playfair`（FairPlay 密钥解包） | `android/app/src/main/cpp/playfair/` | RPiPlay，原样复制 | 是 |
| FairPlay 握手的固定回答表（整个文件按 GPL-3.0） | `android/app/src/main/cpp/fairplay_jni.c` | RPiPlay | 是 |
| FFmpeg 音频扩展 `libffmpegJNI.so` | Gradle 依赖 `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | Jellyfin，预编译 | 是；`-Pthinkos.ffmpeg=false` 可去掉，见 `docs/27-streaming.md` |
| Magisk 模块安装脚本 | `system/thinkos-cast/META-INF/com/google/android/update-binary`、`system/thinkos-certs/META-INF/com/google/android/update-binary` | Magisk 的 `scripts/module_installer.sh`，原样 | 否，只在模块 zip 里 |

FFmpeg 扩展的源码在 https://github.com/jellyfin/jellyfin-androidx-media 的标签 `v1.9.0+1`（子模块 `media` 指向 androidx/media，`ffmpeg` 指向 FFmpeg 的 `release/6.0`）。发布 APK 时要把这份源码一起存档：上游删掉了，提供源码的义务还在（GPL-3.0 第 6 条）。

LIC-04 的源码存档由本项目的 `tools/archive_ffmpeg_source.sh` 生成，位置为 `artifacts/lic/LIC-04/thinkos-ffmpeg-source-v1.9.0+1.tar.gz`，正式发布时作为同名 Release 附件；压缩包内完整保留上游的许可证和构建脚本，GPL 来源不并入项目的 MIT 源文件。精确提交固定于 `tools/ffmpeg-source.lock.json`；分支名仅用于说明，不追踪分支最新内容。 2026-11-03 的完整存档 SHA-256 为 `596cda9ccd49b14d20781b6ef2e7f86033b9506d2bac7c05628b4db412458f66`（468,864,556 字节）；同机离线重建摘要一致，14,524 个上游源码 blob 均按固定 Git 对象校验，清单与结果见 `artifacts/lic/LIC-04/`。

## 运行时库与数据

| 内容 | 许可证 | 说明 |
|---|---|---|
| Lua 5.4.9 | MIT | `android/app/src/main/cpp/lua/`，声明在 `lua.h` 末尾；`lstrlib.c` 改过，见 `android/app/src/main/cpp/PATCHES.md` |
| yyjson 0.12.0 | MIT | `android/app/src/main/cpp/json/`，同目录有 `LICENSE` 和 `SOURCE.json` |
| micro-ecc，固定提交 `541b3a78026420a3e369c4c9281c396b5e531113` | BSD-2-Clause | 仅系统更新器：`system/thinkos-updater/vendor/micro-ecc/`，Kenneth MacKay；原代码、许可证与 README 保留，仅编译纯 C 的 P-256 验签路径。官方来源、逐文件摘要和配置说明见 [更新器来源记录](system/thinkos-updater/vendor/ORIGIN.md) |
| amosnier/sha-2，固定提交 `565f65009bdd98267361b17d50cddd7c9beb3e6c` | 0BSD（原作者允许 Unlicense 或 0BSD，本项目选 0BSD） | 仅系统更新器：`system/thinkos-updater/vendor/sha-2/`，Alain Mosnier；原样使用流式 SHA-256，不自行实现密码算法；原许可证和固定文件摘要随源码保留，见同一来源记录 |
| AndroidX Media3 1.9.2（ExoPlayer）、其它 AndroidX、Guava、OkHttp、Okio、Kotlin 标准库、kotlinx-coroutines、JetBrains annotations | Apache-2.0 | 2026-11-03 核对 `releaseRuntimeClasspath` 的40个模块（含BOM/转发模块）；本轮补取16个缺失POM，37项直接声明、Guava三项从parent继承。FFmpeg之外39项均声明Apache 2.0；逐模块原文、Google Maven / Maven Central链接、继承依据和SHA-256见 [LIC-05核对表](artifacts/lic/LIC-05/runtime-licenses.md) |
| IANA 时区数据 2026e | 公有领域 | `shared/tzdata/`，编译好的 TZif，来源和哈希在 `SOURCE.json` |
| Mozilla/curl 根证书数据：OTA 子集 16 张、系统模块 117 张 | MPL-2.0 | 从 [curl/Mozilla 2026-10-20 CA 包](https://curl.se/ca/cacert-2026-10-20.pem)选取，完整证书未改动，位于 `android/app/src/main/resources/moe/kiwi/thinkos/update/roots.pem`；固定源文件及各 DER SHA-256 见同目录 `roots-source.json`，用 `tools/vendor_update_roots.py` 可重现。原始数据的 MPL-2.0 说明见 [curl CA Extract](https://curl.se/docs/caextract.html)；许可证全文为 `LICENSE-MPL-2.0.txt`，随 APK 的原生关于页分发。系统模块的 117 张位于 `system/thinkos-certs/system/etc/security/cacerts/`，由同一固定包及其匹配的 NSS `certdata.txt` 生成，排除服务器 distrust-after 根 Izenpe.com；完整来源、排除记录和指纹在模块的 `certs-source.json`，工具为 `tools/vendor_system_cacerts.py`。模块带 MPL 全文，主机源码输入和哈希在 `artifacts/certs/CERT-1/source/`。这里只复制证书数据，ThinkOS 的加载/生成代码是本项目 MIT 代码 |
| VSOP87D 地球系数 | 官方使用说明未明确许可条款 | 2026-11-03 已读 [IMCCE README](https://ftp.imcce.fr/pub/ephem/planets/vsop87/README) 与 [使用说明](https://ftp.imcce.fr/pub/ephem/planets/vsop87/vsop87.doc)。README称其为“Notice for use of VSOP87 files”；两份说明未找到明确许可证或再分发授权，不把可下载推断为公有领域。P. Bretagnon、G. Francou（1988），原始数据来源与SHA-256仍在 `astro/EarthVsop.kt`；OWN-12保留 |
| Gradle Wrapper | Apache-2.0 | `android/gradlew`、`android/gradle/wrapper/`，只用于构建 |

`system/thinkos-immersive/stubs/` 是自己写的 Xposed API 占位，只参与编译和主机测试，不打包；运行时由设备上的 Vector 提供。

在线数据不随 APK 分发，由设备运行时获取：Open-Meteo / GeoNames（CC BY 4.0，署名显示在搜索器里；Open-Meteo 免费服务只限非商业使用，见 `docs/15-location.md`）、Bing 每日一图。这些是服务条款，不是代码许可证。

Radio Browser 是可关闭、默认关闭的在线电台目录，台名、标签与流地址仅在用户启用后获取，不随 APK 预置；刷新缓存和收藏只保存在设备。2026-11-04 重读[官方 API 入口](https://api.radio-browser.info/)及[服务文档](https://de1.api.radio-browser.info/)，前者允许自由/非自由软件调用和镜像数据，后者说明无可用性保证、要求描述性 User-Agent 和播放点击上报。[官方首页](https://www.radio-browser.info/)的[原始首页模块](https://www.radio-browser.info/chunk-XJBKC73J.js)明确将目录数据标为 public domain，并将维护者对汇集站名、标签、流与主页链接、语言和地区资料拥有的权利交付公共领域；[新增站点页](https://www.radio-browser.info/add)的[原始模块](https://www.radio-browser.info/chunk-FFUJJMXR.js)也声明提交资料进入公共领域，流媒体、favicon 和电台主页内容仍归各电台。原始响应摘要与定位见`artifacts/app/APP-9/0.1.10/radio-browser-license.json`。ThinkOS 的点击上报默认关闭，单独启用后会向目录提供方发送所选电台请求，提供方可见来源 IP；正式默认值仍由 OWN-15 决定。服务端 [README](https://gitlab.com/radiobrowser/radiobrowser-api-rust/-/raw/master/README.md)和 [LICENSE](https://gitlab.com/radiobrowser/radiobrowser-api-rust/-/raw/master/LICENSE)已读，代码为 AGPL-3.0；本项目只根据公开接口独立实现客户端，没有复制或运行其服务端代码。数据声明已核清，点击规则与默认来源仍列 OWN-12/14/15。此项是在线服务与数据声明。

## 开发工具与 CI

以下工具由主机或 GitHub runner 提供，没有把其程序代码打进 APK。

| 项目 | 许可证 | 用途与来源 |
|---|---|---|
| curl 命令行 | curl | `tools/registry_discovery.py` 调用主机的 HTTPS 客户端，未复制 curl/libcurl 源码；[官方版权与许可证](https://curl.se/docs/copyright.html)说明 SPDX 标识为 `curl`，不是 MIT。 |
| actions/upload-artifact v4.6.2 | MIT | 既有未签名材料上传步骤，固定提交 [ea165f8d65b6e75b540449e92b4886f43607fa02](https://github.com/actions/upload-artifact/commit/ea165f8d65b6e75b540449e92b4886f43607fa02)，[上游许可证](https://github.com/actions/upload-artifact/blob/ea165f8d65b6e75b540449e92b4886f43607fa02/LICENSE)。 |
| actions/download-artifact v4.3.0（仅 GitHub CI 取同次运行的未签名审核数据） | MIT | 固定提交 [`d3f86a106a0bac45b974a628896c90dbdf5c8093`](https://github.com/actions/download-artifact/commit/d3f86a106a0bac45b974a628896c90dbdf5c8093)，对应[官方 v4.3.0 Release](https://github.com/actions/download-artifact/releases/tag/v4.3.0)。原[MIT 许可证](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/LICENSE)，[固定版本 Action 输入说明](https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/action.yml)。只在独立审核 CI job 使用，不进入 APK；不从 artifact 执行代码，不携带签名私钥。 |

## 投屏接收的参考

ThinkOS 的投屏接收（`docs/28-casting.md`）参考了下面的项目。**除了下面 RPiPlay 一节列出的文件，没有复制它们的源代码**；协议部分由我们按各协议的公开资料重写，并附自己的测试。

### weekdayjast/MiracastReceiver（固定提交 `d1aea7e`，MIT）

用作 DLNA、Miracast（Wi-Fi Display）的协议流程参考：SSDP / UPnP 的动作表、WFD 的 M1–M7 顺序与“Sink 主动连 Source 的 7236 端口”这一事实、`wfd_presentation_URL` 只取第一个词、RTP 载荷类型 33 是 MPEG-TS、`wpa_supplicant` 控制接口的命令（`SET wifi_display`、`WFD_SUBELEM_SET 0 …`）。AirPlay 部分用它读懂了 `/server-info`、`/reverse`、`/play`、`/playback-info` 的实际行为与配对握手的步骤。

> MIT License. Copyright (c) the MiracastReceiver authors. Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, subject to the inclusion of this copyright notice and permission notice in copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

它的 `cpp/playfair/`（FairPlay）没有许可证头，上游 shairplay 把 playfair 标为 GPLv3：**ThinkOS 没有使用这份拷贝**。

### 协议资料

- AirPlay：nto.github.io 的非官方规范（Apple TV 软件 5.0）；UxPlay 与 RPiPlay（GPLv3）的文档和部分源码，只读，见下面 RPiPlay 一节。
- Wi-Fi Display：Wi-Fi Alliance WFD 技术规范的公开概述；RFC 2326（RTSP）、RFC 3550（RTP）。
- 曲线算法的测试向量：RFC 7748（X25519）、RFC 8032（Ed25519）。

### RPiPlay（固定提交 `64d0341ed3bef098c940c9ed0675948870a271f9`，GPLv3）

`android/app/src/main/cpp/playfair/` 里的 `playfair`（FairPlay 密钥解包）从 https://github.com/FD-/RPiPlay 的 `lib/playfair/` 原样复制，连同它的 `LICENSE.md`（GNU General Public License 第 3 版）；`android/app/src/main/cpp/fairplay_jni.c` 里 FairPlay 握手的固定回答表来自同一个提交的 `lib/fairplay_playfair.c`。完整版 APK 因此按 GPL-3.0 发布（ADR-043）。

AirPlay 镜像的密钥派生与包格式参照了 RPiPlay 的 `lib/mirror_buffer.c`、`lib/raop_buffer.c` 与 `lib/raop_rtp_mirror.c`（只读，代码重写），写在 `cast/airplay/MirrorCrypto.kt`、`AirPlayMirror.kt`；`AirPlayPairing.kt`、`AirPlayServer.kt`、`AirPlayService.kt`、`AacTranscoder.kt` 里的握手步骤、功能位和音频配置也是对照 RPiPlay、UxPlay 写的。这些内容是协议本身要求的算法和常量，代码是我们写的，所以按项目的 MIT 标注。**这是项目自己的判断，不是法律意见**；只想用 MIT 部分的人要自己评估这 6 个文件（ADR-043）。

## 图标：Lucide（经 Iconfont，ISC）

设置分类新增的 `monitor`、`map-pin`、`house`、`sliders-horizontal`、`puzzle`，以及模式/蓝牙使用的 `moon-star`、`sunset`、`bluetooth` 同样来自合集54062。2026-11-05核对公开目录 `https://www.iconfont.cn/api/collection/detail.json?id=54062` 的名称、原始SVG及ISC标注；对应Iconfont编号完整登记于 `assets/icons/glyphs.json`，未复制其他图标库或自行描画。

操作栏与应用里的图标 `wifi`、`wifi-off`、`volume`、`volume-off`、`brightness`、`tv`、`radio`、`cast`、`apps`、`settings`，以及天气图标 `clear-day`、`clear-night`、`partly-day`、`partly-night`、`cloud`、`fog`、`rain`、`shower`、`snow`、`thunder`、`sun`、`moon`、`unknown`（`assets/icons/`，由 `tools/gen_glyphs.py` 生成安卓矢量图）取自 Iconfont（https://www.iconfont.cn）上用户“逆态度”上传的合集“Lucide”（合集编号 54062，合集标注为 ISC，指向 https://lucide.dev）。每个图标在 Iconfont 上的编号写在 `assets/icons/glyphs.json`。形状是 Lucide 的描边图标（2px 描边、圆头端点）转成的轮廓；这份拷贝不是 Lucide 官方发布的文件，没有逐个与上游比对。Iconfont 上用户上传内容的授权由上传者标注、平台不作保证；需要更稳妥的来源时，同名的图标可以直接换成 lucide.dev 上游的 SVG。

> ISC License. Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022. Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies. THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

## CALL-0 显式实验测试 APK：WebRTC SDK 150.7871.04

`-PregistryFixtureProbe=true` 下，`-PcallProbeVariant` 与仓库外本地 `-PcallProbeAar` 同时指定时，才把已静态核验且固定 SHA-256 的一个 WebRTC AAR 放入独立 `.regfixture` UID 的 `androidTestImplementation`；普通主 APK、默认测试包与正式依赖均不包含它。三个候选为 `io.github.webrtc-sdk:android`、`android-prefixed`、`android-prefixed-stripped:150.7871.04`，一次仅选一个，并过滤其它 ABI，保留原始 armv7 动态库与 Java 字节。

封装仓库 [webrtc-sdk/android 固定提交](https://github.com/webrtc-sdk/android/tree/1d6a68a74162d3f218f9681d49c6b5eef8f02800) 是 MIT；其 [WebRTC 许可汇编](https://github.com/webrtc-sdk/android/blob/1d6a68a74162d3f218f9681d49c6b5eef8f02800/Licenses/WEBRTC.md) 及默认固定源码的 [LICENSE](https://github.com/webrtc-sdk/webrtc/blob/a545de45259b47dfac5f3635863e47cb601f1bb2/LICENSE)、[NOTICE](https://github.com/webrtc-sdk/webrtc/blob/a545de45259b47dfac5f3635863e47cb601f1bb2/NOTICE) 表明 native WebRTC 为 BSD-3-Clause，并有 Shiguredo/LiveKit 的 Apache-2.0 修改。实验测试包同时携带 `tools/call-probe-NOTICE.txt` 的来源与限制说明。

原始 AAR/嵌套 JAR 未见 LICENSE/NOTICE；逐个 Maven 成品对应的构建 run 和精确完整第三方清单仍未闭合，不能以此登记冒称已完成分发许可核对。本项只允许显式本地实验，不选定产品变体，不构成正式引入或发布批准。静态证据见 `artifacts/call/CALL-0/libraries-20261105/`。

## 只读参考

下面的项目读过或对照过，代码是自己写的。`windowsphonelauncher` 做过逐词比对（结果见 ADR-043），其它的没有比对。

| 项目 | 许可证 | 用到什么 |
|---|---|---|
| windowsphonelauncher（快照 `f5a14c9`） | GPL-3.0 | 读过 `TileView`、`TileGridLayout`，用它的布局思路（`docs/25-windows-design.md`） |
| MetroWinLauncher | GPL-3.0 | 项目展示 |
| GrooveLauncher | MIT | README 与项目展示 |
| 7.css（快照 `76d29ab`） | MIT | 读过 `gui/_window.scss` 的叠层配方，用 Canvas 独立实现 |
| RetroBar | Apache-2.0 | 项目展示 |
| windowslauncher | 没有写许可证 | 只读 README |
| soniakeys/meeus | MIT | `astro/Seasons.kt` 的系数和它的第 27 章实现对照过 |
| Suntimes | GPL-3.0-or-later | 2026-11-03，[上游README的Legal Stuff](https://github.com/forrestguice/SuntimesWidget#legal-stuff)明写“GPLv3 or later”，[LICENSE](https://github.com/forrestguice/SuntimesWidget/blob/master/LICENSE)为GPLv3全文。只读参考；黄金时刻、蓝调时刻的角度数值和它一致（`docs/14-handoff.md`），未引入依赖或复制源码 |
| Navidrome | GPL-3.0 | 只计划对接它的接口（`docs/22-player-plan.md`），没有用到代码 |
| iptv-api | AGPL-3.0 | `plugins/iptv/manifest.json` 里有一个它生成的列表地址，用户可改；没有复制代码和列表（`docs/31-iptv.md`） |
