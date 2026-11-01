# 02 · 设备事实与系统状态

> 本文件记录**已实测验证**的设备事实。开发时以本文为准，不要凭经验假设。

## 1. 硬件

| 项目 | 值 |
|---|---|
| 型号 | Lenovo ThinkSmart View **CD-18781Y** |
| 代号 | `starfire` |
| SoC | Qualcomm APQ8053 / MSM8953，8×Cortex-A53 @1.8GHz，14nm |
| GPU | Adreno 506 |
| 内存 | 2GB LPDDR3（实测 MemTotal ≈ 1.91GB，可用约 700MB） |
| 存储 | 8GB eMMC；`/data` 3.8GB（可用约 3.7GB），`/system` 1.4GB |
| 屏幕 | 8 英寸 IPS，**800×1280**（当前系统按竖屏渲染），DSI |
| 摄像头 | Samsung S5KC505A（**仅 Android 下可用**，Linux 无驱动） |
| 音频 | TAS5782M Class-D 功放 + 单麦克风（无麦阵） |
| 无线 | QCA9379（ath10k_sdio），Wi-Fi + 蓝牙 |
| 供电 | **无电池、无 RTC**，常插电 |
| USB | USB-C（藏在屏幕边缘橡胶塞下），经 USB 2.0 hub 连接电脑 |

## 2. 系统

| 项目 | 值 |
|---|---|
| 系统 | LineageOS 15.1（非官方 `starfire` 构建，20240531） |
| Android | 8.1.0 / API 27 |
| 构建类型 | `userdebug`，`ro.debuggable=1` |
| ABI | **仅 armeabi-v7a**（armeabi-v7a,armeabi；无 arm64） |
| Google 服务 | **无**（无 GMS / Play / FCM） |
| 指纹 | `lineage_starfire-userdebug 8.1.0 OPM7.181205.001 ... test-keys` |

### 系统级状态（已配置）

| 项 | 值 | 说明 |
|---|---|---|
| root | 已启用 | Magisk 27.0 + Vector 2.2（原 LSPosed 继任项目，API 102） |
| 屏幕常亮 | `stay_on_while_plugged_in=7`、`screen_off_timeout=2147483647` | 断电也不会熄屏 |
| 锁屏 | 已禁用 | `locksettings set-disabled true` |
| 电池图标 | 已隐藏 | `icon_blacklist=battery`（设备无电池） |
| 网络验证 | 指向 `connect.rom.miui.com/generate_204` | 避免因 Google 检测地址不通而报「网络有问题」 |
| 时间 | 开机自动校时 | Magisk 模块 `timesync` |

### 已安装的 Magisk 模块

| 模块 | 作用 |
|---|---|
| `timesync` | 开机从 HTTP Date 头校时（设备无 RTC） |
| `busybox-ndk` | busybox 工具集（注意：**不含 ntpd**） |
| `rv4a_engine` | ViPER4Android 音频引擎（可用可不用） |
| `zygisk_vector` | Vector（Xposed）框架，API 102 |
| `thinkos-cast`（**源码在本仓库 `system/`，尚未装进设备**） | Miracast Sink 的 root 守护进程（ADR-033）；装法见 `system/README.md` |

> 注意：通过 `su` 直接写 `/data/adb/**` 会被 Magisk 拦截（`Permission denied`）。需要装模块时用
> `su -c "magisk --install-module /sdcard/Download/xxx.zip"`，改配置用 `magisk --sqlite`。

## 3. 时间问题（已解决，但要知道背景）

设备**没有 RTC 电池**，断电后时间会退回 2008/2009 年。时间错误会导致：

- 所有 HTTPS 证书校验失败（表现为「浏览器打不开任何网页」）
- 网络验证失败（系统报「网络有问题」）

解决方式：Magisk 模块 `timesync`，开机等网络就绪后从 HTTP `Date` 头取时间写入系统时钟。

- 脚本：`/data/adb/modules/timesync/service.sh`
- 日志：`/data/local/tmp/time-sync.log`
- 行为：最多重试 30 轮（约 2.5 分钟），源为 baidu/taobao/qq/https baidu

**应用开发注意**：开机后前 1–2 分钟时间可能是错的。任何依赖时间的 UI（时钟、日程）应在时间未校准时显示占位符，不要显示 2008 年。

## 4. 开发连接

```bash
adb devices          # HUA0BWUT  device
adb shell getprop ro.product.model
```

- 序列号：`HUA0BWUT`
- 连接路径：设备 → USB 2.0 hub → Mac（直接用 USB 3.x 口可能出现不稳定）
- **重启后 adb 可能延迟 1–3 分钟才出现**，不要反复 kill-server
- 截屏：`adb exec-out screencap -p > /tmp/shot.png`

## 5. 设备救援（人工操作，Agent 不要执行）

### 恢复环境材料（在 Mac 的 `~/ThinkSmartView/`）

| 文件 | 用途 |
|---|---|
| `image/` | 官方固件 200628.084 解包（112 个文件） |
| `lineage-15.1-20240531-UNOFFICIAL-starfire.zip` | LineageOS 系统包 |
| `lineage-15.1-...-recovery.img` | recovery |
| `boot_orig/boot.img` | 原始 boot |
| `boot_backup_current.img` | 刷 Magisk 前的 boot 备份 |
| `magisk_patched.img` | 打过 Magisk 补丁的 boot |

### 进 EDL（高通 9008）

关机 → **同时按住两个音量键** → 插电/上电 → 屏幕保持全黑，电脑应枚举到 `05c6:9008`。

### 刷写命令（仅人工使用）

```bash
edl --loader=image/prog_emmc_firehose_8953_ddr.mbn qfil rawprogram_unsparse.xml patch0.xml image/
edl w aboot image/emmc_appsboot.mbn
edl w recovery lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img
```

### 恢复出厂线刷

EDL 下重刷官方固件包（`qfil` 一条命令，约 1 分钟），再按需重刷 LineageOS + Magisk。

## 6. 显示与相机限制

- 屏幕是 **LCD**，长期显示静态内容可能轻微残影 → 待机动画应缓慢移动
- 摄像头为单目定焦，**低光噪点明显**，人脸/手势检测在暗环境会失效
- 摄像头视角约 60°，桌面使用时有效交互距离 **40–80cm**
- 无陀螺仪；加速度计 **BMA255**（含敲击检测），支持自动旋转
- 输入法：已确认有 AOSP LatinIME，设置页 `text` 项可在设备上输入（2026-10-30 交接实测）。
- 字体：ROM 已有阿拉伯语、孟加拉语、天城文、CJK、泰文、希伯来文的字体。
- 时区：ROM tzdata 为 2021a；应用随 APK 携带自己的 IANA 规则，见 ADR-019，不修改系统分区。

### 传感器（2026-10-29 `dumpsys sensorservice` 实测）

| 传感器 | 型号 | Android 类型 | 用途 |
|---|---|---|---|
| 加速度计 | Bosch BMA255 | `accelerometer` | 方向判断、自动旋转 |
| 接近 | Capella VCNL4200 PRX | `proximity` | 有人靠近的零成本预触发（有效距离待实测） |
| 环境光 | Capella VCNL4200 ALS | `light` | 自动亮度、夜间模式 |
| 其他 | QTI 显著运动 / 倾斜 / 粗粒度运动分类等 | 厂商类型 | 暂不使用 |

均无需权限即可通过 `SensorManager` 读取。

## 7. 性能基线（实测参考）

| 场景 | 观测 |
|---|---|
| 系统冷启动到桌面 | ~60–90 秒 |
| adb 恢复可用 | 重启后 1–3 分钟 |
| 播放 1080p H.264 | 硬件解码可用（Venus V4L2） |
| 空闲内存 | ~100–200MB 可用 |

> 结论：**UI 必须轻**。不要引入大图片资源、不要一次性加载大量音乐/相册缩略图。
