# 安装 ThinkOS（所有者操作）

适用 Lenovo ThinkSmart View CD-18781Y（starfire），Android 8.1/API 27、32 位 ARM。先阅读本页并保存恢复材料，再操作设备。刷机、清空数据、重启、持久安装 Magisk 模块由所有者执行；`provision.py` 默认只读取本地材料并输出计划。

本页和脚本没有完成任何实机刷机验收。正式签名材料、发布包与系统模块必须先完成 [所有者发布流程](../tools/OWNER-RELEASE.md) 的审核。当前开发机的 debug 包和开发证书不能当成正式发行包，也不能仅按文件名 `app-release.apk` 判断签名。

## 1. 准备离线材料

电脑需要本地 Python 3.9+、ADB；执行正式 APK 安装时还需已安装的 Android SDK build-tools 35.0.0（`android/local.properties` 的 `sdk.dir`）与 OpenSSL。刷机工具 EDL、recovery、固件与 Magisk 均由所有者预先准备，本工具不下载任何文件。

把固件材料放在仓库外，例如 `~/ThinkSmartView/`。本地来源记录及完整固定哈希在 [19 号文档 §1](19-provisioning-for-agent.md#1-前置材料与主机环境agent-自检)；原始恢复记录在 [设备文档 §5](02-device.md#5-设备救援人工操作agent-不要执行)。这些记录说明现有离线材料，不代表上游最新版本。脚本校验以下全部文件：

| 材料 | 固定 SHA-256 |
| --- | --- |
| `CD-18781Y.200628.084.zip` | `4356762c02e6d74ee4bb0ef83c9e4dba910453bb5f14fa3ac9ed321ec4bfa686` |
| `lineage-15.1-20240531-UNOFFICIAL-starfire.zip` | `aaeb4e387a3b1395c7567d60b34b78ef6cacab9e90f9576946b69fb49e9d7660` |
| `lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img` | `a1ee1abae40e9370fa97c7b9b50befa66ba0042859f4f89527de8e3cdba6588e` |
| `magisk_patched.img` | `f919a8fa3428cc8264f15e54082daf361805923ec0b595a64dd3d923d08f703f` |
| `Magisk-v27.0.apk` | `f511bd33d3242911d05b0939f910a3133ef2ba0e0ff1e098128f9f3cd0c16610` |
| `timesync-v1.0.zip` | `d9dc4e6fa17371aeffdc5acf3a55ccce19b0d835fc784880687b16912a05d28c` |
| `busybox-ndk.zip` | `d6b0baeb4f224e994113d29c2d47e6df2413b6cd65fd8b88c2b7d53e0db26c09` |
| `vector.zip` | `b368ffb3424f6984db74e9491e47855dd93c8be2dddf7e1f893a8c7513abb428` |

`magisk_patched.img` **仅对应上表这版 Lineage 的 boot，由 Magisk 27.0 修补**。其他 ROM、Magisk 版本、缩写哈希或同名替代文件都不能直接代用。固件、Lineage、Magisk、Vector 不随 ThinkOS 仓库或安装工具打包；保留各自原始许可证。缺失或哈希不符时停止，不联网补包。

```bash
python3 tools/provision.py resources --directory "$HOME/ThinkSmartView" --dry-run
```

该命令只读本地文件，不运行 `adb`、EDL、构建工具或任何子进程。材料路径必须为绝对路径、不能经过符号链接、不能放在任何 Git 仓库内。ZIP 解包应由所有者从已核对的原包完成，并核对提取出的 loader 与 XML；脚本不会自动解包或执行归档内脚本。

## 2. 选择安装路径

已有可启动的指定 Lineage 系统：先运行 `verify --dry-run` 审核读取命令，再由所有者运行 `verify`，之后可直接进入 APK/模块部署。`verify` 只读固定系统属性，不依据可能保留 Lenovo 值的 `ro.build.fingerprint` 判断系统版本。

```bash
python3 tools/provision.py verify --serial YOUR_SERIAL --dry-run
python3 tools/provision.py verify --serial YOUR_SERIAL
```

需要恢复官方基线、重装 ROM 的设备：所有者按 [19 号文档 §2–6](19-provisioning-for-agent.md) 逐步操作，先同意数据清空，再进入 EDL/recovery。任何错误都停止，不继续写下一分区。`adb root`、`adb remount`、`adb disable-verity`、直接写 `/system` 始终禁止。恢复入口、重启等待与 Magisk/Vector 兼容问题见 [已知问题](10-known-issues.md)。

批量复制已验收的参考机：用 [金镜像指南](golden-image-guide.md)。不要把官方整包 qfil 当成金镜像命令；官方 XML 会写 `persist`，硬件校准保护尚未验收。

## 3. 准备真正的发行产物

从所有者审核过的本地发布目录取得：

- 正式 APK、其 SHA-256、所有者 APK 证书的完整 SHA-256。
- 正式更新器 ZIP、其 SHA-256、**同包**原始七行 `.manifest` 和 DER `.sig`。
- 公钥文件 `system-keys.json`（只有公开 active/backup SPKI，不能传私钥或 keystore）。

这些参数来自本地实际已签产物与公开证书；不要从本文抄固定版本号。APK 版本在执行前由 `aapt` 从实际包读取，`apksigner` 核对证书、API27、不可调试和仅 armv7；OpenSSL 验证更新器原始 manifest 签名。更新器包身份、版本、大小、哈希和 ARM32 ELF 在计划阶段也会核对。没有正式公钥/签名时，只能完成源码与临时 fixture 验收，不能假称正式安装完成。

以下为完整计划示例，替换所有占位符；哈希不是密码，但必须来自可信的线下审核记录。命令不会生成正式密钥，也不接受私钥。

```bash
python3 tools/provision.py install \
  --serial YOUR_SERIAL --resources "$HOME/ThinkSmartView" \
  --apk /absolute/release/thinkos-VERSION.apk --apk-sha256 APK_SHA256 \
  --signer-sha256 OWNER_CERTIFICATE_SHA256 \
  --updater /absolute/release/thinkos-updater-VERSION.zip --updater-sha256 UPDATER_SHA256 \
  --updater-manifest /absolute/release/thinkos-updater-CODE.manifest \
  --updater-signature /absolute/release/thinkos-updater-CODE.manifest.sig \
  --system-keyring /absolute/public/system-keys.json \
  --language-permission grant --device-owner skip --dry-run
```

`--dry-run` 打印完整流程，包括人工刷机、语言授权、更新器安装/重启、设备管理选择和验收；不访问设备、不调用 SDK/OpenSSL、不创建报告或 XML。它会读文件、校验哈希，签名和 APK 元数据在报告中明确标为待主机验证。即使传 `--output` 也不会写文件。漏掉 `--dry-run` 仍是计划模式。

需要先审核实际 APK 版本和签名，可把 `--dry-run` 换成 `--check-host`：只在本地主机运行 `aapt`、`apksigner`、OpenSSL，输出已核实元数据；不接触设备、不安装，也不写报告文件。它与 `--dry-run`、`--apply-app` 互斥。

两个选择必须明确给出：

- `--language-permission grant`：应用阶段通过 ADB 授权 `CHANGE_CONFIGURATION`，让新手引导直接改系统语言；`skip` 保留安卓语言设置兜底，不伪称已授权。
- `--device-owner enable|skip|preserve`：`enable` 只输出所有者手动执行的 `dpm set-device-owner`；`skip` 不开启；`preserve` 保留既有管理状态。三者都不自动清除原来的 Device Owner，也不替所有者做清数据决定。先确认正式包能通过维护菜单移除管理，再选择启用。

## 4. 只执行应用阶段与批量部署

ROM、Magisk、系统模块已经由所有者完成后，把示例的 `install --resources …` 改成 `batch`，重复 `--serial`。先看默认计划，确认后显式加 `--apply-app`，才会执行：系统属性校验 → 同证书 `adb install -r` → 已选择的语言授权 → 电池白名单 → 打开 ThinkOS。最多同时处理四台；每个命令都指定序列号。

`--apply-app` **不会**执行计划里的 EDL、恢复出厂、卸载、降级、Device Owner、`su`、更新器安装或重启。更新器由所有者核对后手动推到 `/sdcard/Download`，再通过 Magisk 安装，按计划重启并检查状态。报告 `app_phase_only=true`、`complete=false` 会持续列出人工待办；应用安装成功不等于整机合格。

若旧安装证书不同，`install -r` 会失败并停止该台后续步骤；工具不会帮你卸载或擦数据。需要换签名时先走下面的退路。`--output /absolute/new-report.json` 仅在显式应用阶段创建新的主机报告，拒绝覆盖；不要把含设备序列号的报告公开发布。

## 5. 首次开机检查

保持设备音量为 0。断开 Server，临时断网，冷启动应直接进入新手引导；逐屏能操作，跳过后有完成设置入口。检查最左缘右滑返回、底缘上滑回首页、顶缘下拉 ThinkOS 控制栏，并在安卓外部应用中确认系统栏策略。检查更新器/投屏/沉浸模块真实状态，未知能力不标成已开启。保留实际 APK 哈希、序列号、日志、屏幕与失败记录。性能验收单独记录，不能用源码或模拟测试代替真机结果。

## 6. GPLv3 安装信息：移除管理、卸载与装自己的版本

完整版 APK 整体按 GPLv3 发布。交付时同时提供 GPLv3 文本、第三方声明、对应版本的 ThinkOS 源码、GPL 组件准确对应源码/校验和以及 [构建步骤](07-build-deploy.md)。正式发布附件由所有者发布工具核对；仓库自有源码仍按 MIT 授权。签名证书不是限制用户编译的理由。

1. 先备份想保留的应用设置和媒体；卸载会删除应用私有数据，且不能直接用新证书覆盖旧证书。备份里可能含配对信息，保存在私有离线路径，不进仓库或公开产物。
2. 若 ThinkOS 是 Device Owner：在左上区域双指长按约 5 秒进入原生“维护”，选择“移除设备管理”，读确认屏并在设备上真实点按确认。它移除管理身份、持久 HOME 与应用自动拉回，保留设置/插件，不替你卸载、清数据或移除系统模块。错误时按屏幕状态处理；已移除身份不会自动恢复。该正式包批准支路需要所有者另行实机验收；本轮统一测试不会清除现机管理身份。
3. 在 Magisk/Vector 中手动停用或卸载 ThinkOS 系统模块，并按各模块说明手动重启，使全局边缘钩子与系统栏策略不再依赖待卸载的应用。只移除 Device Owner 不等于卸载这些模块；恢复记录见 [system/README.md](../system/README.md)。
4. 在安卓应用信息页面卸载 ThinkOS，或由所有者执行 `adb -s YOUR_SERIAL uninstall moe.kiwi.thinkos`。若仍被管理策略拒绝，停止并检查真实 owner；不要通过改系统数据文件强拆，也不要把 `dpm remove-active-admin` 当成所有非 testOnly 包都能使用的退路。
5. 按对应源码的构建指南编译；仅目标 `armeabi-v7a`、minSdk/targetSdk 27。可自己生成并使用自己的签名证书。`-Pthinkos.ffmpeg=false` 可构建不含 GPL FFmpeg 扩展的变体；不能把该选项当作改变其他组件许可证的手段。
6. 安装自己的 APK：`adb -s YOUR_SERIAL install /absolute/path/to/your.apk`，再 `adb -s YOUR_SERIAL shell am start -n moe.kiwi.thinkos/.MainActivity`。首次作为普通应用即可离线运行；设备管理重新开启由所有者另行选择。自己的公钥/更新器应相互对应，否则继续使用离线手动安装，不能把官方根当成自己的私钥。

发布前必须由所有者用真正的正式签名包核实上述退出、卸载与自行编译安装路径，并交付所需安装信息。此处给出操作路径，不宣称当前真机已执行卸载或重刷。

实现依据：[AOSP Android 8.1 的 DevicePolicyManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java) 中，清持久 HOME 仍要求 owner 权限，必须早于清管理身份；清 owner 会重置部分策略并移除 admin，但后续步骤仍可能抛错。[同版 DevicePolicyManager API](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r1/core/java/android/app/admin/DevicePolicyManager.java) 明确该操作不可撤销、策略清理为尽力而为。因此 ThinkOS 先原子写私有退出事务，再清 HOME/身份，并在成功或异常后复读实际身份；仍为 owner 时尝试恢复策略，已非 owner 时保留退出状态并停止自动拉回。状态文件不是设置项，没有 Lua/Server 写入口，设置与插件不被清空。
