# 10 · 已知问题与规避

> 这些都是**在本设备上实际踩过**的坑。遇到问题时先查这里。

## 1. 设备连接

| 现象 | 原因 | 处理 |
|---|---|---|
| `adb devices` 空，但设备亮着 | 重启后 adbd 启动慢 | 等 1–3 分钟，不要反复 `kill-server` |
| 设备不枚举 | USB 线是充电线 / 直插 USB3 口 | 换数据线，经 USB 2.0 hub |
| `adb install` 报 `device offline` | 传输中断 | 重新插拔，等设备重新授权 |
| macOS 上 `adb devices` 报 `cannot connect to daemon` / `failed to start daemon`，日志只有 `libunwind: stepWithCompactEncoding` | platform-tools 37.0.1 自带的 mDNS 桥（Rust `adbmdns_bridge`）解析某些服务名时 abort，间歇发生，与 USB、设备无关；崩溃报告在 `~/Library/Logs/DiagnosticReports/adb-*.ips` | `export ADB_MDNS=0` 后再起守护进程（`tools/tests/media_device.py` 已默认设置）。不要用 `ADB_MDNS_OPENSCREEN`，二进制里没有这个名字 |
| `adb logcat -c` 偶尔报 `failed to clear the 'main' log` | logd 暂时拒绝 | 忽略，读日志按新行读即可 |
| 设备 Wi-Fi 与 Mac 不在同一网段，设备访问不到 Mac 上的测试源站 | 路由器不互通 | `adb reverse tcp:8099 tcp:8099`，设备用 `http://127.0.0.1:8099`（环回地址属于私网，明文规则照常生效） |

## 2. 时间

| 现象 | 说明 |
|---|---|
| 开机后时间显示 2008/2009 | 设备无 RTC，属于正常；`timesync` 模块会在 1–2 分钟内校时 |
| HTTPS 全部失败 | 时间未校准导致证书校验失败，等校时完成 |
| 系统报「网络有问题」 | 同因：网络验证走 HTTPS，时间错误则验证失败 |

**开发注意**：应用启动时若时间年份 < 2020，不要显示日程/相对时间，显示 `--:--` 占位。

## 3. 系统与权限

| 现象 | 原因 | 处理 |
|---|---|---|
| `su -c "echo > /data/adb/..."` 报 Permission denied | Magisk 保护 `/data/adb` | 用 `magisk --install-module` / `magisk --sqlite` |
| `adb root` 后卡死 | 本 ROM 已知问题 | **禁止使用**，见 AGENTS.md |
| `adb disable-verity` | 会破坏启动签名 | **禁止使用**，可能变砖 |
| 安装后 Home 键仍回系统桌面 | 没设默认 launcher | `cmd package set-home-activity moe.kiwi.thinkos/.MainActivity` |

## 4. 显示

| 现象 | 说明 |
|---|---|
| 界面出现残影 | LCD 长期静态内容；让待机动画缓慢移动可缓解 |
| 屏保/休眠后唤不醒 | 系统电源策略问题；本项目已设常亮，应用内不要主动熄屏 |
| 横竖屏切换后布局错乱 | 需要 `onConfigurationChanged` 里重排，且不重放动画 |
| 缓存图片页进入时 RenderThread 在 Adreno `CalcVfdRegs` / HWUI `onLayerOp` 崩溃（2026-11-04，未解决） | 公共数据断网重启回归中，`public-reader.stage` 显示图片并开始平移后68ms出现；页面SLIDE与图片硬件层平移重叠，尚不能确定具体View或根因。后续广播/播放器正常，无证据归因CA。原始栈见`artifacts/certs/CERT-3/activation-20261104/regressions-replace/native-crash.txt`；待以固定方向、缓存图片和单变量`pan=false`复现，不据此全局关闭硬件加速 |
| 首页的卡片栈里，翻卡时滑动的卡片盖到另一个栈上，或者溢出到屏幕边缘（以前如此，已修） | 栈的容器只设了 `clipChildren` 和 `clipToPadding`：前者只让每个子视图待在自己的边框里，后者要有内边距才起作用，都拦不住一张被平移的卡片 | 栈在卡片移动期间把自己画进一层离屏图层，再用一张圆角蒙版裁成圆角窗口（`StackSlot.dispatchDraw`）；静止时不走这条路。`tools/tests/stack_mask_device.py` 检查栈外的像素不变 |
| 给容器设圆角轮廓加 `clipToOutline`，容器里的子视图的圆角没有被裁出来，切口还是直角 | 这台设备的 Android 8.1 只用轮廓的外接矩形去裁子视图，圆角只作用在容器自己画的东西上（卡片自己的圆角背景因此是对的）；把半径加到 220px 也一样 | 不要靠 `clipToOutline` 给子视图做圆角遮罩，用离屏图层加蒙版 |

## 5. 摄像头

| 现象 | 说明 |
|---|---|
| 低光下检测失效 | 单目定焦，属硬件限制；降级为「不检测」而非报错 |
| 打开相机后其它应用无法使用相机 | 释放 `CameraDevice` 要彻底（`close()` + 清空 callback） |
| 连续采集后发热/降频 | 控制 fps，L1 最长连续 10 秒 |

## 6. 内存与性能

| 现象 | 原因 | 处理 |
|---|---|---|
| OOM 崩溃 | 大图未降采样 / 同时播放多个 Lottie | 图片 `inSampleSize`；Lottie 最多 3 个 |
| 动画掉帧 | 图层过多、用了 shadow/blur | 开启 HARDWARE 层，遵守 `docs/04-ui-spec.md` |
| 内存持续增长 | 图片缓存无上限 | 缓存上限 64MB，用 LRU |
| 频谱耗 CPU | FFT 频率过高 | 降采样到 1024 点，20fps 计算即可 |

## 7. 后台与保活

| 现象 | 说明 |
|---|---|
| 长时间后应用被杀 | Android 8.1 Doze；需电池优化白名单 + 前台服务 |
| 开机不自启 | 需 `RECEIVE_BOOT_COMPLETED` + 部分 ROM 需先手动启动一次 |

## 8. 依赖

| 现象 | 原因 | 处理 |
|---|---|---|
| `UnsatisfiedLinkError` | 依赖只有 arm64 的 so | 换库，或在 gradle 里 `abiFilters += "armeabi-v7a"` 并确认有对应 so |
| 运行时崩溃在 `MediaPipe` | 模型文件缺失 / ABI 不匹配 | 确认 `tasks-core-*.aar` 含 armeabi-v7a |

## 9. 网络

| 现象 | 说明 |
|---|---|
| 实时通道（WebSocket）频繁断开 | 电源管理或路由器休眠；心跳 20s + 指数退避重连。断开不影响设备功能 |
| 服务端换了 IP 连不上 | MVP 用固定 IP 配置项，V1 可加 mDNS 发现 |
| 断网后界面卡死 | 所有网络请求必须有超时（3s）；网络只在 IO 线程和插件协程里发生，绝不阻塞主线程 |
| 局域网明文连接被拒绝 | `tos.http` 只允许全部 DNS 结果为私网/本地地址，并固定连接 IP；检查重定向是否跳到公网。Android XML 不支持 CIDR，策略由客户端执行 |

## 10. 插件恢复

| 现象 | 说明 |
|---|---|
| 单次 Lua 脚本错误 | 实例与局部状态保留，查看 ThinkOS 日志；累计频繁错误才重启 |
| 插件进入冷却 | 一小时内重启 5 次后暂停一小时再自动试运行；维护页恢复出厂插件可清除覆盖包 |
| 日志出现 Unclean native execution | 上一次进程在插件 C 执行切片内退出；共享标记归因到插件摘要，三次后隔离一小时。标记无法证明一定是 SIGSEGV，也可能是该切片内被外部杀进程 |
| 进程强停后最后几秒 KV 未保存 | store 为 2s 延迟合并写；停止时提交，突然进程死亡不能保证提交。避免用它作为每次调用同步 fsync 的事务接口 |
| `dpm remove-active-admin` 报错、无法放弃 Device Owner | APK 不是 testOnly | debug 构建用 `DebugReceiver --es command kiosk_surrender`，见 `07-build-deploy.md` §4.3 |

## 11. 媒体与流媒体（ADR-030）

不支持的东西都会得到明确的错误提示，不会黑屏或无声；下面是实测会遇到的。

| 现象 | 原因 | 处理 |
|---|---|---|
| `.mpg` 报“不支持此格式” | MPEG-1 System Stream（VCD 同款）；Media3 的 PS 提取器只认 MPEG-2 Program Stream（DVD / VOB 可播） | 重新封装或转码 |
| 裸 `.dts` 报不支持 | 没有 DTS 裸流提取器；封装在 MKA / MKV / MP4 / TS 里的 DTS 可播（FFmpeg 扩展） | 封进容器 |
| `.wmv` / `.wma` / ASF 不支持 | 没有 ASF 提取器 | 转码 |
| 10-bit HEVC 报“无法解码 hevc” | 硬解不支持 Main10，也没有软解 | 转成 8-bit |
| AV1 报“无法解码 av01” | 没有 AV1 解码器 | 转码 |
| 4K 无法播放 | 硬解上限 1920×1088；HDR 不做色调映射 | 选 1080p 档或转码 |
| RTSP 服务端只提供 UDP（VLC 的 RTSP 输出）连不上 | 引擎固定 RTP over TCP（让数据留在一条经过地址校验的连接里），VLC 回 `461 Unsupported transport` | 用支持 TCP 交织的服务端，测试用 `tools/media/rtsp_tcp.py` |
| H.265 的 RTSP 摄像头放不了 | Media3 的 RTSP 只支持 H.264 + AAC / AC-3 | 摄像头改输出 H.264 |
| 公网 `http://` 流被拒 | 明文只允许私网（ADR-022） | 系统设置打开 `media.public_http`；HTTPS 降级到 HTTP 仍然拒绝 |
| 带 AC-3 / DTS 的片子没有声音，画面正常，提示“无法解码 … 音频” | 构建时关掉了 FFmpeg 扩展（`-Pthinkos.ffmpeg=false`） | 开启扩展，或转成 AAC |
| 用 `ffmpeg -stream_loop … -c copy` 复制出来的 FLAC 播到第一圈末尾报错 | STREAMINFO 里记录的是第一圈的长度 | 重新编码，不要复制 |
| 从眠屏状态开始播视频，视频一直停着 | 旧版本：播放保持把显示唤醒到 AWAKE，路由器“从 ASLEEP 回到首页”把视频页换走，插件又因离开视频页暂停了它 | 已修：live 页面已占着屏幕时不再回首页 |
| 视频页（`player.video`、`cast.video`）的两条边露出页面底色，浅色主题下是白边；两个方向、随时间慢慢变宽变窄，最宽 8 × 6 像素 | 防烧屏漂移（±8 × ±6 px）把整页推开，而视频舞台没有像磁贴 / 照片舞台那样被钉在屏幕边缘；深色主题下底色近黑所以不显眼，一直存在，投屏时第一次被发现 | 已修：视频舞台与照片舞台一样不随漂移；`tools/tests/video_edges_device.py` 在两个主题、四个方向的最大漂移下检查屏幕上除了片子和黑边没有任何别的颜色（未修复时 16 / 16 失败，修复后全过） |
| 视频播放时竖拖右半屏调的是系统媒体音量，而 DLNA 投屏里手机上的音量滑块调的是引擎自己的增益；两者相乘才是实际音量，所以手机上拉到 100 也不一定响 | 本地手势用系统媒体流（和音量键一致），DLNA 映射当初没有系统音量接口，只能用引擎增益 | 观察项：计划把 DLNA 的 `SetVolume` 也改成系统媒体音量，两个量合一；改动涉及 `EngineCastPlayer` 与 DLNA 测试，没有在这一轮做 |
| 公共 HTTPS 测试流（Mux 的 `test-streams.mux.dev`）在设备所在的局域网连不上，测试用例自动跳过 | 设备到该主机 443 端口的 TCP 连接超时（Mac 在另一个网段可以连上；设备连其它公共主机正常，ping 该主机的 CDN 边缘也通），属于网络环境；播放器按设计重试 6 次后报 `network` | 无需处理；HTTPS 的 HLS / DASH 改用 Apple bipbop 与 Shaka 示例验证 |
| 播放 HLS 的 TS 分片时，logcat 里每个分片两条 `PesReader: Unexpected start code prefix`（每 2 秒 2 行） | Media3 的 TS 解析器在分片起点的警告；本机 ffmpeg 与 Apple 的 bipbop 都有，fMP4 的 HLS 没有；测试里没有看到画面或声音问题 | 只是日志噪声，但会把 256 KB 的 logcat 环形缓冲在约 40 分钟内冲掉；长时间排查时用 `adb logcat -v time ThinkOS:V *:E` 过滤 |
| 反复切换片源时原生堆每次切换约涨 16 KB（160 次切换 16.5 → 19.4 MB），总 PSS 在 106–114 MB 之间波动 | 未查明：可能是分配器碎片，也可能是极小的泄漏；离 350 MB 预算很远 | 观察项：长时间使用后看 `dumpsys meminfo` 的 Native Heap；`media_soak_device.py --switches` 会按类别记录 |

## 12. 投屏接收（ADR-031、ADR-033）

| 现象 | 原因 | 处理 |
|---|---|---|
| 打开了“用 Miracast 接收投屏”，手机里搜不到设备 | 系统层模块 `thinkos-cast` 没装或没在跑（应用里没有任何提示，日志里有一行 “the ThinkOS Cast system module is not running”） | `system/install.sh` 装好并重启；`adb logcat -s ThinkOS-wfdd:V` 看守护进程 |
| 模块装了，手机仍搜不到 | 开关/Wi-Fi 刚被重启过（WFD 元素丢了，守护进程每 5 秒会补）；或手机不支持 Miracast 发送 | 等 15 秒；换另一台发送端试 |
| 手机连上后设备上没有画面 | 日志里看停在哪一步：`nothing answered on port 7236`（找不到 Source）、`session ended (protocol …)`（握手被拒） | `adb logcat -s ThinkOS:V \| grep -i miracast`，把日志带回来 |
| iPhone 的 AirPlay 列表里没有设备 | `cast.airplay` 没开；手机与设备不在同一个网络（设备在 5G 网络、手机或 Mac 在 2.4G 网络时，两边可能是不同子网，mDNS 过不去） | 设置里看开关；两边连同一个 Wi-Fi；`dns-sd -L <名字> _airplay._tcp local.` 看 TXT |
| iPhone / Mac 选中设备后提示 “Unable to connect” | 设备日志只有 `GET /info`、`POST /pair-setup` 就没了。原因（已修）：请求行是 `RTSP/1.0` 的 HTTP 风格请求被当成 RTSP 动词回了 501。再出现同样现象时：设备日志每行现在带 `proto=`；Mac 上看发送端日志 `/usr/bin/log stream --predicate 'process IN {"airplayd","AirPlayXPCHelper","AirPlayUIAgent"}' \| grep -E "Status [0-9]+\|got error"` | `adb logcat -s ThinkOS:V \| grep -i airplay`，看发送端走到了哪一步（`pair-verify`、`fp-setup`、`SETUP`） |
| 控制中心的“屏幕镜像”里没有设备 | 本设备没有声明镜像（AirPlay 镜像与 FairPlay 的决定见 ADR-031） | 用支持 AirPlay 的视频 App，或改走 Miracast / DLNA |
| 某个 App 的 AirPlay 投不过来 | 它用 `mlhls://`（发送端中转 HLS，YouTube 等）、或是 FairPlay 保护的内容 | 暂不支持，设备会回 400 并告诉发送端已停止 |
| Miracast 开头几秒画面比对方慢 1 秒左右 | 播放器起播时攒下的领先量，追赶再生用约 10 秒消掉（`MirrorPace`） | 正常 |
| Mac 的 `dns-sd -B` 在关掉 AirPlay 后（强杀应用时）仍短暂列出设备 | 应用被强制结束时没来得及撤销 mDNS 注册 | 正常关闭开关时会撤销（测试覆盖） |
| SELinux enforcing 下守护进程回 `wpa-silent` | wpa_supplicant 回复守护进程的 socket 被拒 | 模块带了 `sepolicy.rule`（设备是 permissive，未在 enforcing 下验证） |
| iPhone 连上镜像后画面全是花屏或黑，日志里 `AirPlay mirror: keys accepted` 之后没有 `[media] mirror catching up` | FairPlay 密钥解包得到的密钥不对（`playfair` 在真实的 Mac 与 iPhone Air 上都解对过，所以先看别处），或 AAC-ELD 的配置与 iPhone 的不同 | `adb logcat -s ThinkOS:V \| grep -i airplay`，把整段日志带回来 |
| 镜像开头几秒画面比对方慢 1 秒左右 | 同 Miracast：播放器起播时攒下的领先量，追赶再生约 10 秒消掉 | 正常 |
| 镜像时屏幕一直转圈、画面停了很久才跟上，甚至重置 | 手机的屏幕不变时不发画面；旧版把它当成卡顿：播放头停住，下一个画面要等过这段空白才显示，30 秒没新数据还会把播放器重置（已修：`MirrorTimeline`、镜像不跑停滞看门狗、镜像不显示转圈，见 `docs/28-casting.md` §15） | 日志里 `buffering at … buffer ahead 25062 ms` 这样的几十秒就是它；调试包 `am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command media_state`，日志里 `DEBUG media` 一行看 `status`、`position`、`buffered` |
| `tools/tests/cast_*_device.py` 连 `/info` 都没有回答（`TypeError: cannot unpack non-iterable NoneType object`） | 这些脚本的发送端跑在 Mac 上，Mac 与设备要在同一网段；一次现场里 Mac 在 `192.168.3.x`、设备在 5G 网络的 `192.168.2.x`，Mac 到设备的路由还经过代理的虚拟网卡（源地址 `198.18.x.x`）。具体是哪一步挡住的没有查实 | 把 Mac 连到设备所在的网络再跑 |
| 镜像里播放全屏视频后镜像没有回来 | 发送端应当在同一会话上重新 `SETUP` 镜像流，设备保留密钥并重新显示；若发送端直接断开则会话结束 | 看日志有没有 `AirPlay mirror ended` |
| 镜像没有声音 | 发送端用了 ALAC（`ct=2`）或缓冲音频（type 103），设备不支持，SETUP 的回答里省略了这条流 | 日志里 `audio codec 2 is not supported`；用 iPhone 的镜像时声音是 AAC-ELD |
| 关掉“AirPlay 屏幕镜像”后手机的 AirPlay 列表里仍短暂看得到镜像身份 | 手机缓存 mDNS 记录 | 等一两分钟或重开 Wi-Fi |

## 13. 控制面板与系统栏（ADR-035、ADR-037）

| 现象 | 原因 | 处理 |
|---|---|---|
| 从屏幕上沿下拉打开控制面板时，安卓的状态栏和底部的返回栏闪出约 1.5 秒 | 系统在最上、最下约一条状态栏高度（24dp，本机约 38px）内把向里的滑动识别成“唤出临时栏”，发生在触摸到达任何窗口之前，应用无权拒绝；顶部和底部两条栏一起出现 | 装 `system/thinkos-immersive`（Vector 模块，人工安装，`system/README.md`）。没装时：从离边缘 26dp 以下起手的滑动不会触发，但控制面板的触发区就在边缘 |
| 装了 thinkos-immersive 后栏还是闪（验收脚本 2/6，模块日志里没有 `hooked 1` 那行） | 没启用、没选“系统框架”、没重启；或**模块 APK 重装过而 Vector 数据库里的路径是旧的**（手动 `adb install -r` 会换掉 `/data/app` 里的目录名）；或日志里是 `hooked 0` | 重新跑 `system/install-immersive.sh` 再重启。看登记：`su -c "sqlite3 /data/adb/lspd/config/modules_config.db 'select module_pkg_name,apk_path,enabled from modules'"`，路径要和 `pm path moe.kiwi.thinkos.immersive` 一致；`su -c 'grep -rh ThinkOS-immersive /data/adb/lspd/log'` |
| 外部App底边上滑没有回ThinkOS | 1.0模块只有临时栏钩子；1.1需要人工更新并重启。系统底边手势只识别约500ms内的快速上滑；nav.up_enabled关闭、维护页或权限弹窗期间也不返回 | 核实1.1、hooked onSwipeFromBottom日志；人工重启后运行immersive_device.py --external-returns。1.1目前只完成构建和主机检查，不能用旧6/6替代联动验收 |
| Vector 管理器或 `cli` 连不上（`Error: Socket Failure: Connection refused`），`ps` 里没有 `vectord` | Vector 2.2 在 Android 8.1 上，只要有启用的模块，守护进程每次开机后一两秒就因 `PackageInfo.getLongVersionCode()`（API 28 才有）崩掉；没有模块时不崩。已加载的钩子不受影响 | 已知，与 thinkos-immersive 无关（任何模块都这样）；改登记用 `system/install-immersive.sh`（见 `system/README.md`）。要让守护进程回到在线：`adb uninstall moe.kiwi.thinkos.immersive` 后重启 |
| 从安卓设置页（面板的“网络设置”）按返回回到 ThinkOS 后，锁定没有挂回来（`mLockTaskModeState=NONE`，日志里 `Invalid task, not in foreground`） | 回到 ThinkOS 时它先被恢复、任务稍后才到最前，这时 `startLockTask` 被安卓拒绝；以前只在恢复那一刻试一次 | 已修：窗口拿到焦点后每 300 毫秒补试一次，最多 10 次（`KioskPolicy.relock`）；日志里 `locked again (attempt N)`。外部 App 期间和维护菜单打开系统设置期间故意不补 |
| 开发时设备上有两个 ThinkOS 任务（`dumpsys activity activities` 里两个 `A=moe.kiwi.thinkos`），回来后锁挂不上 | `adb shell am start`（`tools/deploy.sh`）把 ThinkOS 开在应用栈，测试里的 `KEYCODE_HOME` 又在主屏栈开一个；锁定只能挂在其中一个上 | `adb shell am force-stop moe.kiwi.thinkos` 后重新启动，只会剩一个；`tools/tests/panel_device.py` 开头就这样做。正常使用（开机后只有一个任务，没有实体 Home 键）碰不到 |
| 离开锁定去安卓设置页时屏幕上弹出“已取消固定屏幕” | 安卓在 `stopLockTask` 时自己弹的提示（API 27 无法关掉） | 正常；回来时锁定会自动挂回 |
| 在外部应用（如安卓设置页）里滑动，系统栏照旧 | 设计如此：钩子只认 ThinkOS 在前台；外部应用里的返回键是离开它的路 | — |
| 控制面板的 Wi-Fi 名称显示不出来 | 安卓 8.1 在位置服务关着时把名称换成 `<unknown ssid>` | 已改用当前网络信息里的名称；两处都读不到时只显示 IP |

## 14. 电视直播（ADR-038，`31-iptv.md` §9）

| 现象 | 原因 | 处理 |
|---|---|---|
| 一个频道的第一条线路是死的，画面黑三秒多才换到下一条 | 播放器对回 404 的 HLS 列表会再问三次（0 / 1 / 2 秒间隔）；没开“自动检测线路”时没人替它先看一眼 | 开着“自动检测线路”（默认开）：没测过的线路播前先用 `tos.media.probe` 看一眼，死的立刻跳过；测过的死线路在暂停期间根本不会被选到 |
| 节目单读不了，页脚写“节目单超过 1 MB”或“是压缩包” | `tos.http` 响应上限 1 MiB 且不解压 .gz；完整的 XMLTV 往往是几十 MB 的 .gz | 用小一点的 XMLTV，或给“每个频道一份 JSON”的地址模板（`{name}` `{id}` `{date}`） |
| 填了公网 `http://` 的频道列表，页脚写“无法加载频道列表” | `tos.http` 只放行私网明文（`06-plugins.md` §6.4）；这条规则只管列表，**线路**走媒体的规则，开了 `media.public_http` 就能播能测 | 列表用 https，或放到局域网里的 http 服务上 |
| 所有线路都测过之前，页脚一直写“正在检测线路 n / m” | 后台每 10 秒测一条，600 个频道、每个两三条线路要几个小时；收藏与最近看的先测 | 把“两次检测的间隔”调小（最小 3 秒），或不管它：要播的线路播前会先看一眼 |
| 一个源的线路用一次就作废（令牌地址），测过之后播不了 | 测试和播前一看都会用掉一次 | 关掉“自动检测线路”（同时放弃失效线路的自动暂停） |
| 台号变了 | 台号是合并后列表里的位置，列表更新或换了列表后会变；收藏按频道名的规范形式记，不受影响 | 无需处理 |
| 预置列表里很多频道点下去提示“已被拦截：互联网明文 HTTP 处于关闭状态” | 这份列表 768 条线路里 518 条是公网明文 http，321 个频道里 143 个一条 https 都没有；`media.public_http` 默认关（ADR-022） | 系统设置里打开“允许互联网明文 HTTP”（明文传输，风险自担），或换一份 https 的列表；默认没有替用户改 |
| 频道页页脚写“无法加载节目单” | 填的节目单地址设备连不上（例如 `epg.112114.xyz` 在这台设备的网络不通）；预置列表头里的 .gz 则不会去要 | 清空“节目单地址”，或换一个设备连得上的；怎么判断见 `31-iptv.md` §10.2 |
| 页脚写“无法加载频道列表”，预置的列表一直读不出来 | 设备没联网或连不上 `guovin.github.io`；APK 里没有列表的副本 | 会自动重试（30 秒、1、2、5、10 分钟，之后每半小时），读到之后每 6 小时再读、改设置时也读；读过一次之后离线也会显示上次存下的 |
| 只有央视频道有节目单，卫视和地方台没有 | 预置的节目单是央视网自己的央视节目表（`guides/cntv.lua`）；这份列表头里的节目单是 404，项目留的 XMLTV 是 8 月的，其他节目单服务设备连不上 | 见 `31-iptv.md` §10.2：卫视可以接（同一个服务，要一张名字对 id 的表），未做 |

## 15. 应用与返回手势（ADR-040，`33-apps-and-back.md`）

| 现象 | 原因 | 处理 |
|---|---|---|
| 应用里从屏幕最左缘右滑没有反应 | 只有栈里有东西（应用、接管页、设置页）时才起作用；翻页序列里同一个滑动是翻页。或者 `nav.back_enabled` 被关了。或者手指起点比 `nav.back_edge_dp`（默认 24dp，约 39 px）更靠里 | 起点放在最左边的窄条里；再不行调大 `nav.back_edge_dp`（16–48，隐藏项，Server 可下发） |
| 靠最左缘的轻点、长按晚一点才有反应 | 栈非空时，在这条窄条里按下的触摸要等到抬手或滑过 slop 才交给页面（要分清是不是返回） | 正常；条很窄，不放控件在最左 24dp 内 |
| 应用里左右滑没有任何东西动 | 设计如此：应用页、接管页、设置页的左右滑归内容自己，OS 不翻页，连边缘的橡皮筋也不做 | 用返回退出 |
| 打开应用时有一帧停顿 | 新页面在翻转开始前才建出来；时钟页翻走之前秒针由 `FrameGovernor` 提到 60 帧，但没量化测过 | 见 `33-apps-and-back.md` §8，性能测试要先告诉用户 |
| 系统动画开关设为 0 或开了“减少动画”时，打开应用没有翻转 | 设计如此：此时退回滑动或直接替换 | — |
| 横屏安装时左缘滑动闪出系统导航栏 | **未验证**：导航栏在哪一侧取决于安装方向；没装 `thinkos-immersive` 时可能和顶部下拉同源 | 装 `system/thinkos-immersive`（人工安装） |
| 应用声明了 `tos.back.intercept(true)` 却没有 `back` 处理，第一次返回“没反应” | 它被告知了，但什么也没做；2 秒后下一次返回会直接退出 | 应用的 bug：回到根层要 `tos.back.intercept(false)` |
