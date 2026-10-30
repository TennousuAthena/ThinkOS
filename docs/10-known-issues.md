# 10 · 已知问题与规避

> 这些都是**在本设备上实际踩过**的坑。遇到问题时先查这里。

## 1. 设备连接

| 现象 | 原因 | 处理 |
|---|---|---|
| `adb devices` 空，但设备亮着 | 重启后 adbd 启动慢 | 等 1–3 分钟，不要反复 `kill-server` |
| 设备不枚举 | USB 线是充电线 / 直插 USB3 口 | 换数据线，经 USB 2.0 hub |
| `adb install` 报 `device offline` | 传输中断 | 重新插拔，等设备重新授权 |

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
