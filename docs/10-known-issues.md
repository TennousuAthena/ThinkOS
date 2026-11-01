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

