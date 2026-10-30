# 19 · 新机刷机与设置（Agent 全自动手册）

> 目标：用户拿来一台**全新（或变砖/要重置）的 ThinkSmart View CD-18781Y**，只需做少数物理动作并给出 Wi-Fi 信息，其余由 Agent 按本文自动完成，最终得到与 `02-device.md` §2 一致的设备，并装好 ThinkOS。
>
> 状态标记：✅ = 已在上一台设备上实测；⚠️ = 依据 Android/Magisk 通用行为推导，**未在本机实测**，执行时必须按「验证」步骤确认，失败就停下记录，不要硬试。

## 0. 授权范围与红线

`AGENTS.md` 默认把刷机、改系统分区划给人工。**本文是用户对「新机初始化」这一场景的明确授权**：仅当用户请求「刷新机 / 初始化设备」时，Agent 可执行本文列出的 `edl`、`adb sideload`、Magisk 模块安装与 `settings put` 命令。其他场景仍按 `AGENTS.md`。

无论何时都**禁止**：

- `adb root`、`adb disable-verity`、`adb remount`、直接写 `/system`（会冻屏或变砖）
- 刷入本文清单之外的镜像；`edl` 擦除/写入本文未列出的分区（尤其 `sbl1`、`aboot`、`tz`、`rpm`、`modem*`、`persist`、`devinfo`，除非是 §3 的整包 qfil）
- 联网下载固件/模型。所有材料已在本地（§1），缺了就停下问用户
- 设置 Device Owner（`dpm set-device-owner`）——仍由人工决定，见 `07-build-deploy.md` §4.3

## 1. 前置：材料与主机环境（Agent 自检）

材料目录（Mac）：`~/ThinkSmartView/`。逐项校验 SHA-256，**任何一项不符就停止**。

| 文件 | SHA-256 | 用途 |
|---|---|---|
| `CD-18781Y.200628.084.zip` | `4356762c02e6d74ee4bb0ef83c9e4dba910453bb5f14fa3ac9ed321ec4bfa686` | 官方固件包（`image/` 为其解包，112 个文件） |
| `lineage-15.1-20240531-UNOFFICIAL-starfire.zip` | `aaeb4e387a3b1395c7567d60b34b78ef6cacab9e90f9576946b69fb49e9d7660` | LineageOS 15.1 |
| `lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img` | `a1ee1abae40e9370fa97c7b9b50befa66ba0042859f4f89527de8e3cdba6588e` | recovery |
| `magisk_patched.img` | `f919a8fa3428cc8264f15e54082daf361805923ec0b595a64dd3d923d08f703f` | 已打 Magisk 27.0 补丁的 boot，**只对应上面这版 Lineage 的 `boot.img`**（21523752 字节，即 `boot_orig/boot.img`） |
| `Magisk-v27.0.apk` | `f511bd33d3242911d05b0939f910a3133ef2ba0e0ff1e098128f9f3cd0c16610` | Magisk 管理器 |
| `timesync-v1.0.zip` | `d9dc4e6fa17371aeffdc5acf3a55ccce19b0d835fc784880687b16912a05d28c` | 开机校时模块 |
| `busybox-ndk.zip` | `d6b0baeb4f224e994113d29c2d47e6df2413b6cd65fd8b88c2b7d53e0db26c09` | busybox 模块 |
| `vector.zip` | `b368ffb3424f6984db74e9491e47855dd93c8be2dddf7e1f893a8c7513abb428` | Vector（Xposed）API 102 |
| `viper-engine.zip` / `viper-arm.apk` | `78e1cb87…5d28` / `6a75c3da…2d72` | ViPER4Android，可选 |

```bash
cd ~/ThinkSmartView && shasum -a 256 CD-18781Y.200628.084.zip lineage-15.1-*.zip lineage-15.1-*-recovery.img \
  magisk_patched.img Magisk-v27.0.apk timesync-v1.0.zip busybox-ndk.zip vector.zip
which adb edl fastboot       # 期望：/opt/homebrew/bin/adb, ~/.local/bin/edl, /opt/homebrew/bin/fastboot
```

> 不要用 `LSPosed-*.zip` / `lsposed_new.zip`：已被 Vector 取代。

## 2. 需要用户做的事（Agent 开工前一次性问清）

Agent 无法自己完成的只有物理动作与凭据。**开始前一次性向用户确认，之后不再打扰**：

1. 设备已用**数据线**（非充电线）经 **USB 2.0 hub** 接到 Mac，并已通电。USB-C 口在屏幕边缘橡胶塞下
2. 用户在 §3 提示时，按 `02-device.md` §5 的方法**手动进入 EDL**（关机 → 同时按住两个音量键 → 上电，屏幕全黑）。Agent 会等待并检测 `05c6:9008`
3. Wi-Fi 的 SSID 与密码（Agent 用在 §6.2；不要写进仓库或日志）
4. 确认可以**清空设备全部数据**（整包 qfil 会擦除一切）

Agent 向用户的提示模板：

> 请把设备关机，同时按住两个音量键再接电，屏幕保持全黑，然后告诉我“好了”。我会检查电脑是否枚举到 9008。

## 3. 阶段 A：EDL 恢复官方基线

目的：恢复出厂分区表与固件，保证后面的 Lineage 在预期分区布局上。**新机默认执行**；仅当用户明确说“只重装 ROM”才可跳过。

检测（✅ 口径）：

```bash
system_profiler SPUSBDataType | grep -i -B2 -A6 -E "9008|QDL"   # 应见 05c6:9008（Qualcomm HS-USB QDLoader 9008）
```

没有枚举到：回到用户，换线 / 经 USB 2.0 hub / 重做按键，最多重试 3 次，之后停下报告。**不要**盲目写入。

整包刷写（✅ 来自 `02-device.md` §5，约 1 分钟）：

```bash
cd ~/ThinkSmartView
edl --loader=image/prog_emmc_firehose_8953_ddr.mbn qfil image/rawprogram_unsparse.xml image/patch0.xml image/ 2>&1 | tee /tmp/flash_stock.log
```

> `02-device.md` 里写的是相对路径形式（`qfil rawprogram_unsparse.xml patch0.xml image/`）。先在 `~/ThinkSmartView/image/` 下用该形式执行；若 `edl` 报找不到文件再换成上面的路径形式。参考成功日志：`~/ThinkSmartView/flash_stock.log`，结尾为一串 `patch` 与 `Done`。

验证：命令退出码为 0，日志无 `error` / `failed`。任何失败 → 停下，保留日志，交给用户。**不要在失败后继续写 recovery。**

## 4. 阶段 B：写入 recovery 与 aboot，刷 LineageOS

```bash
cd ~/ThinkSmartView
edl w aboot image/emmc_appsboot.mbn
edl w recovery lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img
```

✅ 以上两条来自 `02-device.md` §5。

### 4.1 进入 recovery ⚠️

- 先 `edl reset`（或让用户拔插电源）重启。进入 Lineage recovery 的具体按键/命令**未记录**。依次尝试：`adb reboot recovery`（设备已能起 adb 时）→ 让用户按“音量下 + 电源”类组合键。
- **不要猜多次**；30 分钟内进不去 recovery，停下问用户，并让用户把实际成功的按键写回本节。

进入后确认：`adb devices` 显示 `recovery` 或 `sideload` 状态。

### 4.2 刷 ROM ⚠️

在 recovery 里选 `Wipe → Format data / factory reset`，再 `Apply update → Apply from ADB`，然后：

```bash
adb sideload ~/ThinkSmartView/lineage-15.1-20240531-UNOFFICIAL-starfire.zip
```

sideload 在 47%/开头显示 `adb: failed to read command: Success` 一类提示是 Lineage 常见现象，以 recovery 屏幕上 “Install complete / 成功” 为准。**不要**在此刻刷 Gapps（本项目无 GMS）。

### 4.3 刷 Magisk 补丁 boot

写入的是已补丁的 boot，**不要现场再打补丁**：

```bash
edl w boot ~/ThinkSmartView/magisk_patched.img
edl reset
```

> 此镜像仅对应这一版 Lineage 的 `boot.img`。用户若换了别的 ROM 版本，必须停下：需要用新 `boot.img` 重新在 Magisk 里打补丁，属于另一流程，交人工。

进 EDL 需要用户再做一次按键动作（§2）。Agent 提示用户后检测 9008，通过后再写。若 Lineage 已启动，也可先 `adb reboot edl` ⚠️（未验证，失败就让用户按键）。

重启后等待：**冷启动到桌面 60–90 秒，adb 出现需 1–3 分钟**（`10-known-issues.md` §1），不要反复 `kill-server`。

```bash
for i in $(seq 1 60); do adb devices | grep -q -w device && break; sleep 5; done
adb devices
adb shell getprop ro.build.fingerprint   # 期望含 lineage_starfire-userdebug 8.1.0
adb shell getprop ro.product.cpu.abilist # 期望只有 armeabi-v7a,armeabi
```

## 5. 阶段 C：Magisk 与模块

### 5.1 安装 Magisk 管理器 ✅

```bash
adb install -r ~/ThinkSmartView/Magisk-v27.0.apk
adb shell am start -n com.topjohnwu.magisk/.ui.MainActivity
```

首次启动 Magisk 可能提示“需要额外设置/修复运行环境”。用 `adb exec-out screencap -p > /tmp/shot.png` 看屏幕；按提示点击确认并让它重启（用 `adb shell input tap X Y`，坐标按截图推算，屏幕 800×1280）。**不要选择再次打补丁 boot 的选项。**

验证 root（✅）：

```bash
adb shell su -c id          # 若设备弹出授权，用截图 + input tap 点“允许”；期望 uid=0(root)
adb shell su -c "magisk -v" # 期望 27.0
```

首次 `su` 的超级用户授权弹窗是物理屏幕上的；Agent 通过 `screencap` 找到“允许/Grant”按钮后点击。

### 5.2 按顺序安装模块

先把文件推到设备，再用 Magisk 官方入口安装（✅，直接写 `/data/adb/**` 会被拦截）：

```bash
cd ~/ThinkSmartView
adb push busybox-ndk.zip timesync-v1.0.zip vector.zip /sdcard/Download/
adb shell su -c "magisk --install-module /sdcard/Download/busybox-ndk.zip"
adb shell su -c "magisk --install-module /sdcard/Download/timesync-v1.0.zip"
```

Vector 依赖 Zygisk ⚠️：先开启 Zygisk，再装 Vector。

```bash
adb shell su -c "magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('zygisk',1)\""
adb shell su -c "magisk --install-module /sdcard/Download/vector.zip"
```

`viper-engine.zip`、`viper-arm.apk` 为可选，默认**不装**。

重启并验证：

```bash
adb reboot
# 等 1–3 分钟让 adb 回来（见 §4.3 的轮询）
adb shell su -c "ls /data/adb/modules"          # 期望含 busybox-ndk timesync zygisk_vector
adb shell su -c "cat /data/adb/modules/zygisk_vector/module.prop | grep -E '^(id|version)='"
```

## 6. 阶段 D：系统设置

### 6.1 跳过开机向导并固化显示/锁屏设置

对照 `02-device.md` §2「系统级状态」。✅ 表示值来自实测，命令形式是 Android 8.1 标准 `settings`：

```bash
adb shell settings put global device_provisioned 1                 # ⚠️ 若仍停在向导
adb shell settings put secure user_setup_complete 1                # ⚠️
adb shell settings put global stay_on_while_plugged_in 7           # ✅ 值
adb shell settings put system screen_off_timeout 2147483647        # ✅ 值
adb shell locksettings set-disabled true                           # ✅
adb shell settings put secure icon_blacklist battery               # ✅ 值（设备无电池）
```

网络验证改指向国内可达地址（✅ 目标值：`connect.rom.miui.com/generate_204`；键名为 Android 8.1 的 captive portal 键 ⚠️，设置后要验证）：

```bash
adb shell settings put global captive_portal_http_url  http://connect.rom.miui.com/generate_204
adb shell settings put global captive_portal_https_url https://connect.rom.miui.com/generate_204
adb shell settings put global captive_portal_use_https 0           # ⚠️ 与上面两条一并验证
```

读回确认：

```bash
adb shell settings get global stay_on_while_plugged_in
adb shell settings get system screen_off_timeout
adb shell settings get global captive_portal_http_url
```

### 6.2 连接 Wi-Fi ⚠️

Android 8.1 的 `cmd wifi` **没有** `add-network`，所以用以下顺序，成功即停：

1. **UI 自动化（首选）**：`adb shell am start -a android.settings.WIFI_SETTINGS`，`screencap` 找到 SSID，`input tap` 进入，`input text '<密码>'`（空格用 `%s`，特殊字符先转义），点连接。每一步截图确认
2. **写 `WifiConfigStore.xml`（备选，root）**：未验证，涉及 `/data/misc/wifi/`，只有 1 失败且用户同意时才试

验证：

```bash
adb shell dumpsys wifi | grep -E "Wi-Fi is|mNetworkInfo" | head
adb shell ping -c 2 223.5.5.5
```

**不要把密码输出到仓库、日志或最终报告里。**

### 6.3 校时 ✅

设备无 RTC，`timesync` 在联网后 1–2 分钟内校时。验证：

```bash
adb shell date                               # 年份应 ≥ 2026
adb shell cat /data/local/tmp/time-sync.log  # 应有 "got from ..." 与 "done"
```

年份仍是 2008/2009：确认 Wi-Fi 已通；`adb shell su -c "sh /data/adb/modules/timesync/service.sh"` 手动再跑一次。

## 7. 阶段 E：安装 ThinkOS

```bash
cd /Users/takuenn/Workspace/mobileApp/ThinkOS
tools/deploy.sh          # assembleDebug + install -r + set-home-activity + 启动（JDK 17/21 自动选择）
```

`deploy.sh` 默认序列号 `HUA0BWUT`。**新机序列号不同**，务必先 `adb devices` 取得序列号，再 `ANDROID_SERIAL=<新序列号> tools/deploy.sh`。如桌面上只有一台设备，也可用该变量避免误装到别的设备。

后台保活（`07-build-deploy.md` §6）：

```bash
adb -s <序列号> shell dumpsys deviceidle whitelist +moe.kiwi.thinkos
```

## 8. 阶段 F：验收清单

全部通过才能报告“完成”，每项给出命令输出或截图作为证据：

| # | 检查 | 命令 / 期望 |
|---|---|---|
| 1 | 系统版本 | `getprop ro.build.fingerprint` 含 `lineage_starfire-userdebug 8.1.0` |
| 2 | ABI | `getprop ro.product.cpu.abilist` 只有 `armeabi-v7a,armeabi` |
| 3 | root | `su -c id` → `uid=0(root)` |
| 4 | 模块 | `/data/adb/modules` 含 `busybox-ndk`、`timesync`、`zygisk_vector` |
| 5 | 常亮 | `stay_on_while_plugged_in=7`，`screen_off_timeout=2147483647` |
| 6 | 锁屏 | `dumpsys window \| grep -i keyguard` 无显示锁屏；截图是桌面而非锁屏 |
| 7 | 网络 | `ping 223.5.5.5` 成功；系统无“网络有问题”提示 |
| 8 | 时间 | `date` 年份 ≥ 2026 |
| 9 | ThinkOS | `dumpsys activity activities \| grep mResumedActivity` 含 `moe.kiwi.thinkos/.MainActivity` |
| 10 | Home 键 | `adb shell input keyevent KEYCODE_HOME` 后前台仍是 ThinkOS |
| 11 | 截图 | `adb exec-out screencap -p > /tmp/shot.png`，看过确认界面正常 |
| 12 | 冷启动 | 断网重启后 ThinkOS < 3s（`AGENTS.md` 验收第 3 条；可在最后一步验证，需用户同意拔掉网络） |

## 9. 停下并求助的条件

满足任一条立刻停手，写明“做到哪一步、完整报错、已尝试的方法”，不要继续写分区：

- EDL 枚举不到 9008，重试 3 次后仍失败
- 任一 SHA-256 与 §1 不符
- qfil / `edl w` 报错或中途中断
- 进不了 recovery（超过 30 分钟）
- `magisk -v` 不是 27.0，或 `su` 无法获得 root
- 设备反复重启、卡在开机动画超过 10 分钟
- 需要任何本文未列出的命令才能继续

## 10. 一次完整成功流程（给 Agent 的速览）

```
问用户 §2 四件事 → 校验材料 §1
→ 用户进 EDL → qfil 整包 §3
→ edl w aboot / recovery §4 → 进 recovery → 清数据 + sideload Lineage
→ 用户再进 EDL → edl w boot magisk_patched.img → edl reset §4.3
→ 等 adb → 装 Magisk APK，确认 root §5.1
→ 开 Zygisk，装 busybox / timesync / vector，重启 §5.2
→ settings 固化 + 连 Wi-Fi §6 → deploy.sh 装 ThinkOS §7 → 验收 §8
```

用户在全程只需要：接线、两次按键进 EDL（可能加一次进 recovery）、提供 Wi-Fi 密码。

## 11. 维护本文

每次真实走完这个流程，把 ⚠️ 项改成 ✅（写日期和结果），把没记录的按键/坐标补上。这是该文档的主要价值来源。
