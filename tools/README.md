# tools/

开发与运维脚本（bash）。所有脚本都应能在 Mac 上直接跑。

| 脚本 | 作用 |
|---|---|
| `deploy.sh` | 构建并安装 APK（不需要 Server） |
| `provision.py verify --serial HUA0BWUT` | 只读装机版本/ABI校验；可重复--serial批量检查，--dry-run只打印命令。当前尚未实现刷机、取镜像和自动安装流程 |
| `tests/cast_identity_device.py` | 在临时私有cache目录加载已安装APK的生产身份类，验证32字节/600、坏文件恢复、多目录与独立ART进程稳定；不清应用数据、不重启、不写实际身份/配对种子 |
| `plugin-push.sh` | 把一个插件目录经 `run-as` 推到设备的开发目录热加载（仅 debug 构建，见 `docs/07-build-deploy.md` §3.1）。按文件清单写入隐藏暂存目录，完成后发布整个目录 |
| `dev.sh`（M2 计划） | 起服务端 + 装 APK + 跟随日志 |
| `bench.sh` | 采样帧率与内存（`dumpsys gfxinfo` / `meminfo`） |
| `screenshot.sh` | 截屏到 `/tmp/thinkos-<时间>.png` |
| `media/` | 流媒体测试工具：样片生成、测试源站（Range / 鉴权 / 重定向 / 限速 / 断流 / HLS / DASH / 直播 / M3U）、RTSP 源、`dbg.sh`；见 `media/README.md` |
| `cast/` | 投屏验证工具（`docs/28-casting.md`）：`latency_source.py` 实时生成带“内容时钟”的 TS 流，`latency_probe.py` 经 `adb reverse` 送到设备并从屏幕截图量出端到端延迟（`python3 -I tools/cast/latency_probe.py <低延迟档 true\|false> [样本数]`，需 USB，用 `-s`；内容时钟的起点读 ffmpeg 逐帧报告，不用第一个字节，否则少算 0.5 秒）；`wfd_source.py` 扮演 Miracast 的 Source（RTSP M1–M7、RTP/TS，可注入丢包与乱序）；`airplay_sender.py` 扮演 iPhone 的 AirPlay 视频发送端（server-info、配对、事件通道、play / rate / scrub / stop） |
| `power_trace.py` | 拔电实验（`docs/40-power-loss.md` §5）：把 powerd 推到设备的 `/data/local/tmp`，经 Wi-Fi 把系统主供电轨的采样实时传回 Mac，打印拔线后电压怎么掉、设备撑多久。要人拔线；`stop` 子命令清理设备上的采样进程 |

## 约定

- 统一使用 `set -euo pipefail`
- 设备序列号默认 `HUA0BWUT`，可用 `$ANDROID_SERIAL` 覆盖
- 普通开发脚本不修改设备系统设置。刷机、系统分区、Device Owner变更、持久模块安装和重启仍是人工操作。
- AGENTS.md允许的系统层临时测试可使用ADB shell的固定事务：程序和状态只放/data/local/tmp，先存私有恢复日志、结束后核对原值。清单规定的只读root查询、WEL系统时区及APP叠层前置采用这一边界；应用代码始终不执行su。

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

## 许可证发布材料

许可证源码存档：`./tools/archive_ffmpeg_source.sh` 获取 `ffmpeg-source.lock.json` 固定的 Jellyfin、AndroidX 和 FFmpeg 提交并生成源码附件；`--offline` 从本地主机缓存重建。输出、SHA-256、GPLv3 原文和 `release-attachments.json` 在 `artifacts/lic/LIC-04/`，不执行上游代码，也不发布。用 `python3 -I -m unittest discover -s tools/tests -p test_ffmpeg_source.py` 检查确定性、原文/执行位/符号链接、错误子模块和离线缺失的拒绝。

## APK更新验收

以下设备脚本仅用于HUA0BWUT/API27，运行前音量流1–5保持0；不安装持久模块、不重启、不设置或移除Device Owner，也不生成正式钥匙。状态/设置恢复日志放主机私有临时目录，原始设备文件不进入artifacts。

| 脚本 | 范围 |
|---|---|
| `tests/ota_package_fixtures.py --output <目录>` | 主机生成下一版本及故意损坏的APK，供原生包验证读取；**这些是验证夹具，绝不安装**。只用现有debug证书和临时错误证书，结束恢复正常版本构建 |
| `tests/ota_device.py --packages <夹具目录> --workflow --runtime --output <目录>` | 真机读取已安装APK中的更新生产类，验证真实HTTP/TLS/包解析、受控媒体/投屏/前台门禁、强杀续传和READY重验；不安装夹具 |
| `tests/update_ui_device.py --output <目录>` | 出厂权限/真实点按、中文更新页、所有Lua停止和真实安全模式下的原生恢复入口；12份文件原样恢复 |
| `tests/self_update_device.py --baseline-apk <正常包> --candidate-apk <新包> --test-apk <测试包> --output <目录>` | **有意安装候选包**：既有Device Owner的应用UID提交会话，实际验证自动拉起、UID/HOME/Lock Task及升级前数据。两包必须是当前工作树的新构建、同一签名、相邻版本，不能用上面的验证夹具。无回调时结合原平台会话终态和实际APK核对，不伪造回调、不重复提交；事务不明则保留恢复日志和暂存。成功升级后采用新正常版本，不降级还原 |
| `tests/downgrade_device.py --baseline-apk <正常包> --lower-apk <低一修订包> --test-apk <测试包> --output <目录>` | 显式OTA-0原型，分别验证应用UID隐藏标志和shell `pm -r -d`，结束覆盖回正常包及原文件，源版本不降低。`--shell-only`只重测命令行观察器。shell会话的估计大小和空包名不是APK身份，身份以实际包版本/摘要为准 |
| `tests/release_smoke.py --apk <release包> --restore-debug-apk <同版debug包> --output <目录>` | 非debuggable构建的独立冒烟，本身不用应用私有文件、调试接收器或root；用普通ADB观察自动启动、原生Runtime/Lua、HOME、Lock Task及冷启动功能。结束覆盖回给定同版debug包，不卸载、不清数据 |

上述原型不等于生产安装器或回滚引擎完成；正式签名迁移仍由所有者完成。`test_self_update_observer.py`包含对错误会话、空包名/估计大小和丢失收藏的负例。2026-11-04的证据与未验项见`artifacts/update/OTA-0/`。

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
| `tests/iptv_native.sh [--device]` | iptv 的 Lua 逻辑（列表读取、合并、线路健康、节目单，以及 `main.lua` 对着 `tos.*` 的替身），同一套沙箱；`android/app/src/testNative/plugin_test.c` 是通用的：`plugin-test <名字> <测试.lua> <全局名>=<文件> ...`，别的插件也可以用 |
| `tests/iptv_device.py` | iptv 与 `tv` 组件在设备上：Mac 上起一个测试台（两份频道列表、节目单、三路 ffmpeg 做的 HLS，死线路与“200 但是网页”的线路），经 `adb reverse` 访问；像人一样设置、点频道页、按电视界面的按钮和双击，再读插件的存储和测试台的请求日志核对；结束时重置插件设置 |

`python3 -I tools/tests/cast_miracast_device.py`（27 项）：`wfd_source.py` 扮演手机，调试命令 `miracast_dial` 让设备去连它（Wi-Fi Direct 配对不在这个测试里，只测它之后的一切）：握手与设备的声明、引擎里的会话、延迟、丢包与乱序、各种结束方式、畸形的发送端。Mac 与设备要在同一网段。`python3 -I tools/tests/cast_airplay_device.py`（37 项）：打开 `cast.airplay`（结束时把设置文件原样放回），用 `dns-sd` 从 Mac 发现设备，再用 `airplay_sender.py` 走完整个视频推送。`system/test/run.sh`（18 项）：Miracast 系统层守护进程对着假的 wpa_supplicant，不需要设备。调试包的 `setting` 命令（`--es key cast.airplay --es value false`）像设置页一样在运行时改系统设置。

`python3 -I tools/tests/cast_airplay_mirror_device.py`（45 项）：`tools/cast/airplay_mirror_sender.py` 扮演 iPhone 的镜像（`/info`、配对、`/fp-setup` 两步、`SETUP`、加密的 H.264 与 AAC、定时端口、`/feedback`），调试命令 `airplay_testkey` 让设备对每次 FairPlay 解包回同一个密钥（只有苹果的代码能造出真的包），所以真正的 `playfair` 只用 `fairplay_probe` 探测。`eld_selftest` 让设备用平台的 ELD 编码器造一段音频再走转码器，看平台的编解码器能不能走通这条路。`cast_airplay_device.py` 现在测的是镜像关闭时的纯视频身份。`python3 -I tools/tests/cast_airplay_still_device.py [--still 40]`：同一个发送端只发画面（没有声音），用它的 `still` 开关让屏幕“静止”几十秒再恢复，检查设备不重置播放器、不留几十秒的领先量、下一个画面两秒内到（Mac 与设备要在同一网段；**写好之后还没有在设备上跑过**）。

## 控制面板与系统栏（ADR-035、ADR-037）

| 脚本 | 验证什么 |
|---|---|
| `tests/panel_device.py` | 控制面板（32 项）：开头先强制停止再启动（保证只有一个 ThinkOS 任务）并走一遍“网络设置 → 安卓 Wi-Fi 页 → 返回键 → 锁定挂回”；之后是从顶边下拉打开、亮度与音量滑块、静音、关闭的各种方式（上拉、Home、点遮罩、无操作）、顶边的横滑与点按仍归页面、设置与网络按钮、`panel.enabled` 关掉后不响应。结束时把亮度、音量和设置放回 |
| `tests/immersive_device.py` | `system/thinkos-immersive`：从上下边缘滑动，两条系统栏的 `mTransientBar` 都应保持 `NONE`（6 项）。没装模块时 4 项失败，这是基线；装好后全过 |
| `../system/test/run-immersive.sh` | 钩子的主机测试（10 项），不需要设备 |
| `tests/fixtures/icon-probe/` | 取自 Iconfont 的 23 个图标名：四个页面——系统图标 10 个和天气图标 13 个，各有一个磁贴页和一个列表页，把每个名字都放出来看渲染。`tools/plugin-push.sh tools/tests/fixtures/icon-probe` 推到 debug 设备，调试命令 `page --es value icon-probe.tiles`（`.list`、`.weather`、`.weather-list`）打开，看完 `--remove icon-probe`。竖屏一屏最多 12 张中号磁贴，所以 13 个天气名字里最后一个（`unknown`）只在列表页能看到 |
| `tests/test_glyphs.py` | 图标：生成的安卓资源与 `assets/icons` 一致、来源登记齐全、名字在图标词汇表里（`tools/gen_glyphs.py --check`） |

## 首页卡片栈

| 脚本 | 验证什么 |
|---|---|
| `tests/stack_mask_device.py` | 慢速拖动第二个栈（竖屏在下、横屏在右），上拉和下拉各一次，在手指还按着时截图：**栈的范围之外的像素不能变**（8 项）。先拍几张静止图，求出每个像素自然变化的范围（时钟会呼吸、背景会缓动），拖动中的截图超出这个范围才算被卡片弄脏；开始前固定防烧屏微移，并避开分钟翻页。关掉遮罩时 4 项“栈外不变”全部失败（本任务里验证过），打开是 0 |

## 界面走查与克制（ADR-044）

`python3 -I -m unittest discover -s tools/tests` 自动包含 `test_ui_restraint.py`：11 种语言、内置脚本/视图、四档字号、字体和阴影规则，并用 `fixtures/ui-restraint-invalid` 验证能抓到违规样本。

`python3 tools/tests/ui_walkthrough.py --output artifacts/ui/UI-00` 采集改前的六组截图；改后使用 `artifacts/ui/UI-17`。`--only portrait-ar-DARK` 只重拍一个组合，索引保留其它组合。脚本结束还原调试语言、方向、主题及保存的设置/标签/变体；它使用本地电视测试台，不依赖公网频道。安全模式横幅是无持久副作用的预览，索引中标明 preview。

`python3 tools/tests/settings_metadata_device.py` 验证 DLNA 警告随开关显隐、节目单输入占位符，以及恢复默认的取消、确认和地点六个键联动。结束后逐字节还原设置及备份和电视状态；原始日志、截图与还原结果在 `artifacts/ui/UI-03/`。`settings_feedback_ui.py` 保留 API 1 第三方 `help` 检查，拒绝值的提示通过调试包专用的 `form_change` 注入真实设置事件，不暴露已隐藏的传感器调校项。

`python3 tools/tests/legal_device.py` 检查安装 APK 中四份法律原文、关于页的声明和版权、触摸滚动到底、无权限 Lua 调用被拒，以及所有插件暂时停用时的维护入口。停用只在当前进程生效，结束重启应用并还原保存文件，不修改设备系统。两个 GPL 构建开关都关闭时加 `--without-gpl` 验证 MIT 声明。

`python3 tools/tests/settings_controls_device.py` 在真实指针按住期间读取窗口亮度与保存值，检查亮度跟手、CANCEL恢复、松手提交及普通数字不预览。临时ThinkTouch仅在shell运行，DEX的min API为27，不装进应用。断连时保留私有/tmp日志，重连并核对物理设备后可用`python3 tools/device_recovery.py <日志目录> --output <结果.json>`恢复四份设置/导航原始字节；工具拒绝其它设备、路径或被改内容，核对成功才删日志。

`python3 tools/tests/settings_columns_device.py` 采集11语言横屏分类，检查RTL、两栏滚动、旋转保留分类/插件及返回不积累。它采用相同的私有恢复日志，不包含性能测试。

`python3 tools/tests/settings_routes_device.py` 检查竖屏分类、原生计时档位重启保留、非档位显示、related-only长按和真实安卓设置Back/Lock Task往返；`--weather-only`单独检查城市/单位排序、城市编辑写入system范围和天气立即更新。媒体音量必须为0，四文件逐字节恢复。

功能在设备上确认、告知所有者后，`python3 tools/tests/settings_perf_device.py`采样固定数字表盘AWAKE120秒、横竖屏设置各20秒（保持在30秒闲置退出之前）、首页AMBIENT600秒，持续核对页面/变体/注意力/方向/进程/触摸和导航代次。默认关闭秒以匹配出厂基线；`--clock-seconds`另作压力场景。保存CPU、gfxinfo、PSS和警告原文，读取实际安装APK摘要；记录历史基线比较。不会自动改变媒体音量。

`python3 tools/tests/clock_accuracy_device.py --face digital`固定表盘测60秒换秒、时间源、进程重启和阻塞恢复，`--face analog`可测指针；默认数字，不沿用保存的变体。`clock_frame_probe.py --output <目录>`是每表盘30秒的短诊断，记录完整framestats和阶段耗时，不能替代十分钟验收。两者均需事先告知，使用私有恢复日志，不改默认桌面。

`python3 tools/tests/apps_device.py`验证安卓应用网格、18个桌面入口、横竖屏/三主题/三语言、Back/Lock Task、隐藏/开关与第三方API和app图片权限；计算器临时停用后恢复原始enabled状态，设置和导航字节还原。先通过功能、告知后再用`--scroll-only`测100项grid-probe的120秒滚动掉帧。`idle_threads_device.py --output <目录>`只做60秒AMBIENT线程CPU诊断，不能替代正式性能验收。

`onboarding_preview_device.py`是WEL-0的debug两屏效果图/轮播，不是正式引导。`onboarding_locale_device.py`在API27用shell已持有的CHANGE_CONFIGURATION临时切真实系统语言，保留完整语言列表、userSetLocale和persist.sys.locale，不给应用授权。`onboarding_timezone_device.py`仅执行清单规定的shell-root固定时区事务，跨UTC+14/−11验证日期/太阳时刻。`onboarding_font_device.py`临时设真实font_scale=1.3采图，必须人工看原图；不能把capture_complete当作字号适配通过。各驱动都先存私有恢复日志并还原系统值/应用文件，禁止重置设备猜测新机默认值。

`tools/media/icy.py`提供本机回环的静音MP3/ICY、M3U和PLS测试台。`radio_icy_device.py`验证动态曲名及换台清除；`radio_device.py`用真实磁贴/列表/收藏/操作栏点按，验证重启保留、HTTP策略及默认300秒功能运行和190秒源站503故障恢复，以覆盖引擎重试耗尽后的插件兜底。全程媒体音量0，无公网电台或性能采样。恢复白名单现覆盖设置/导航以及player、iptv、radio三个存储的主文件和备份，共十文件；旧八文件日志仍可恢复。

`radio_ui_device.py`使用同一静音测试源，检查11种局部语言×竖横屏×列表/播放页的44个原生标签/RTL/方向画面，并采集应用磁贴；语言覆盖在旋转完成后重设，系统语言/时区/字号保持不变。`media_state`在当前debug/DUMP接收者直接返回广播结果，工具保留旧APK的日志回退路径。

`apps_foreign_preflight.py`验证真实导航栏Home、不同UID叠层触摸/背光及外部音频焦点；临时修改policy_control并精确还原。`apps_foreign_device.py`验证故意拒绝Back的APK、真实Android启动拒绝回传、可见视频暂停、实际RTSP/RTP镜像结束、SSDP发现后的局域网DLNA唤回原任务，以及静音AudioTrack活动95秒时的超时豁免与停音后看门狗返回。仅用本地静音媒体，系统媒体音量须已为0；十文件和Global常亮还原，本轮测试APK及reverse删除。不会安装系统模块、改变Device Owner或重启。

`apps_foreign_panel_device.py`默认用中文检查外部应用上方的同一个PanelHost：横竖屏实际顶边下拉、日期/网络文字边界、不同UID视频持续前进且不暂停、收起后的真实触摸交还、亮度背光、同一MediaEngine暂停/恢复、闲置关闭、设置/应用/Wi-Fi入口、权限撤销和无模块Home降级。只临时运行system/thinkos-shell/shell.sh的apply/restore，代码和root私有状态在本轮/data/local/tmp目录，结束核对策略/appop/属性及十文件并清理。音量/静音控件一律不触碰。按所有者最新优先级不默认跑其它语言，--all-locales仅保留为扩展工具。

ADR-046工具：`global_home_device.py`检查ThinkOS里中文横竖屏实际底缘Home、控制栏打开时先Back/再Home和已经在首页的处理。`global_back_device.py`默认用临时app_process运行同一固定Back发射函数，检查真实API27投递、普通UID不能跨应用注入、debug同UID私有Home与原生弹窗/维护/冷进程；默认不加载模块，报告明确区分范围。`apps_foreign_panel_device.py --home-signal`使用临时协议2/full策略，验证不取焦点的顶部和左缘窗口；Home走DUMP保护的同UID夹具，不冒充已加载系统钩子。

人工持久安装immersive1.2、shell1.1并重启后，`global_back_device.py --native-gestures`才测试物理外部Left/Bottom、外部控制栏优先Back、静音视频、安卓设置及真实系统栏状态。该选项不安装或写模块策略，前置不符直接停止；IME/权限窗口和角落起手另列待验。旧up_gesture_device.py的launcher/离开前页目标属于ADR-045历史，不能作为ADR-046通过证据。

## OTA 根证书

`vendor_update_roots.py`从已下载、SHA-256固定的curl/Mozilla证书包重现APK里的16张根证书子集；不联网、不改系统信任库，也不处理私钥。源地址、原始文件摘要和每张证书DER摘要见`android/app/src/main/resources/moe/kiwi/thinkos/update/roots-source.json`。运行`python3 tools/vendor_update_roots.py /path/to/cacert-2026-10-20.pem --check`只核对，不写文件；去掉`--check`只重建该目录的`roots.pem`。证书数据按MPL-2.0分发，原生关于页可读完整许可证。更换版本时须重新核对来源、选择名单、元数据与脚本头部日期，随后跑UpdateTrustTest和完整构建；不会运行时从下载站信任一张新根证书。

## 断电保护

`python3 -I tools/tests/power_device.py`：armeabi-v7a 版 `powerd` 在真机上对着 `/data/local/tmp/powertest` 里假的供电轨、sysrq、背光和挂载表跑（11 项）：上膛、掉压触发、`sync`、灭背光、恢复后重启，不碰真实的供电轨和 `/proc/sysrq-trigger`，不重启设备。主机测试是 `system/test/run-power.sh`（28 项）。


`vendor_system_cacerts.py`离线生成系统CA模块数据，需要主机Python标准库与OpenSSL 3+，不引入APK依赖。完整命令、来源固定值、两种构建模式与刷新流程见system/README.md的thinkos-certs段；tools/tests/test_system_cacerts.py覆盖来源/NSS信任策略、旧式哈希、真实同主题冲突、坏包和可复现构建。certs_device.py只读核对baseline/add/replace三种预期，调用debug-only trust_roots读取AndroidCAStore；它不安装、不重启，add/replace只能用于人工激活模块以后。每次使用独立--output目录保留原始证据。
