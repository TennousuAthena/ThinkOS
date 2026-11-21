# ThinkOS 金镜像与批量装机（人工刷写）

适用同型号 ThinkSmart View CD-18781Y。镜像只保存变化慢的 ROM、Magisk、Zygisk、Vector、系统模块；APK/插件在开机后用正式发行产物部署。本工具没有执行任何镜像提取、稀疏刷写或真机克隆验收。第一台写入必须所有者在场，EDL 一次只接一台。

镜像、GPT、Wi-Fi/ADB/配对备份一律留在仓库外的私有目录，例如 `~/ThinkSmartView/golden/`，不进 Git、`artifacts/`、CI 或发布附件。固件来源与固定哈希见 [安装指南](installation-guide.md#1-准备离线材料) 和 [19 号文档 §1](19-provisioning-for-agent.md#1-前置材料与主机环境agent-自检)。不要联网下载替代固件。

## 1. 参考机先验收，再清理

所有者逐项记录实际结果：

- 指定 Lineage `15.1-20240531-UNOFFICIAL-starfire`、API27、仅 armv7；不要依据保留的 Lenovo fingerprint 判断。
- Magisk/Zygisk/Vector 和实际要分发的 cast、immersive、updater 模块能工作。正式更新器编入所有者 active/backup 公钥；默认无钥匙构建会拒绝更新，不能冒充正式配置。
- 用正式 APK 检查离线引导、全局手势和维护退路，之后再清理应用数据。记录 Vector 所登记 APK 的实际路径，克隆后必须重新核对。
- 清理测试 APK（例如 `moe.kiwi.thinkos.test`、`moe.kiwi.thinkos.externalfixture`）；逐个核对包名后由所有者卸载，不批量猜测删除。
- 检查 `dumpsys device_policy` 的实际 Device Owner。参考机 `/data` 中的 owner 元数据也会被复制。选择 **none**：通过维护菜单移除管理，再取镜像；选择 **preserved**：明确接受管理状态复制到新机，并在每台核对。工具不能暗中开启或清除管理。`dpm list-owners` 在目标 ROM 是否可用先确认，不依赖命令名字推断结果。

所有者备份后执行 `pm clear moe.kiwi.thinkos`，确认 ThinkOS 私有目录中没有 `settings/local.json`、`files/cast/` 的身份/配对种子，且没有管理退出记录。**清理后不要再启动 ThinkOS**，否则会再次生成本机数据。不要人为设置 `onboarding.done`。每台第一次启动应生成自己的投屏身份并进入引导。

还要清除上一台 Wi-Fi 凭据、ADB 公钥、蓝牙配置、临时数据和日志。Android 8.1 的候选位置如下，**只是待在目标 ROM 核对的清单，不是自动删除脚本**：

| 内容 | 待核对位置/风险 |
| --- | --- |
| Wi-Fi | `/data/misc/wifi/WifiConfigStore.xml`；可能还有厂商/用户变体，不能仅删一个文件便宣称干净 |
| ADB 授权 | `/data/misc/adb/adb_keys`；清理后新机需要重新确认主机，不把授权复制给其他人 |
| 蓝牙 | `/data/misc/bluedroid/`；核对哪些是配对数据，保护每台硬件地址/校准信息 |
| 临时数据、日志 | `/data/local/tmp`、已确认的应用/系统日志；不能对未经核对的系统目录递归清空 |
| 硬件独有数据 | `persist`、调制解调器校准、Wi-Fi/蓝牙 MAC、传感器标定；**不取作公共写入镜像** |

核对路径、所有者、权限与恢复方法之后才由所有者清理；本轮不代执行。没有物理核验就不能在 manifest 的 `scrubbed` 写 `true`。

## 2. 先生成提取计划

loader 必须从已验证的官方 ZIP 解出，并保存它的完整本地 SHA-256；不要凭同名文件或自动 loader 搜索选取。工具只读取 loader 并核对传入哈希。

```bash
python3 tools/provision.py capture-plan \
  --serial REFERENCE_SERIAL --images "$HOME/ThinkSmartView/golden" \
  --loader "$HOME/ThinkSmartView/image/prog_emmc_firehose_8953_ddr.mbn" \
  --loader-sha256 REVIEWED_LOADER_SHA256 --device-owner none --dry-run
```

默认与显式 `--dry-run` 相同：不创建目录、不运行 ADB/EDL、不清数据。计划包含清理前提、`edl printgpt`、boot/recovery/system/userdata 的 `edl r`。只有所有者另行授权并手动执行这些命令。先保存完整原始 GPT 输出，再读分区；任何读取失败都停止，不拿部分文件制作镜像。

若保存 cache 镜像，也必须从该参考机读取并与 GPT 尺寸核对。默认不包含 aboot；“是否必须”尚未验证。vendor 只有与官方对应数据逐字节比较确认不同、并记录 stock 哈希后才能显式加入。不要把 stock 分片 XML 的某个 `system_1.img` 长度当成整个 system 分区大小。

## 3. 记录完整 GPT 几何

把参考机 `printgpt` 的 **全部分区** 起始扇区、扇区数、物理分区和磁盘总扇区数转录为 `reference-gpt.json`，由另一遍人工核对。当前工具不猜测不同版本 EDL 的文本格式，不从进度日志或分片 XML 自动推算几何。JSON 结构如下（数字必须换成真实值；这里的几何不适合刷机）：

```json
{
  "schema": 1,
  "sector_size": 512,
  "disk_sectors": 200,
  "partitions": [
    {"name": "boot", "physical_partition": 0, "start_sector": 34, "sectors": 1},
    {"name": "recovery", "physical_partition": 0, "start_sector": 35, "sectors": 1},
    {"name": "system", "physical_partition": 0, "start_sector": 36, "sectors": 1},
    {"name": "userdata", "physical_partition": 0, "start_sector": 37, "sectors": 1}
  ]
}
```

工具要求 512 字节扇区、物理分区 0、合法磁盘边界、分区不重叠，拒绝重复字段/分区。每台待写设备重新保存原始 GPT、转录完整 `target-gpt.json`；除列表顺序外，任何磁盘大小/分区几何差异都拒绝生成写入计划。不能用复制参考 JSON 冒充实际读到的目标 GPT。

原始 GPT 日志和标准 JSON 都保留在私有镜像目录。manifest 的 `reference_gpt_sha256` 是 **JSON 原字节** SHA-256，防止审核后被改；目标比较则按完整几何，不依赖 JSON 排版一致。

## 4. 镜像 manifest 与稀疏文件

逐个计算 SHA-256 与文件大小，制作同目录的 `golden.json`。示例需替换所有占位符，boot/recovery/system/userdata 四项全部必需；cache 可选。

```json
{
  "schema": 1,
  "lineage": "15.1-20240531-UNOFFICIAL-starfire",
  "reference_gpt_sha256": "FULL_SHA256_OF_REFERENCE_GPT_JSON",
  "device_owner": "none",
  "scrubbed": {
    "thinkos_data": true, "wifi": true, "adb_keys": true,
    "bluetooth": true, "temporary_logs": true,
    "test_apps": true, "device_owner": true
  },
  "images": {
    "boot": {"file": "boot.img", "sha256": "FULL_SHA256", "size": 0, "format": "raw"},
    "recovery": {"file": "recovery.img", "sha256": "FULL_SHA256", "size": 0, "format": "raw"},
    "system": {"file": "system.img", "sha256": "FULL_SHA256", "size": 0, "format": "raw"},
    "userdata": {"file": "userdata.img", "sha256": "FULL_SHA256", "size": 0, "format": "raw"}
  }
}
```

`size` 是实际文件字节数，不能填 0；文件名只允许本目录直接普通文件，不允许子目录、符号链接或 `../`。原始镜像大小必须恰好等于参考 GPT 分区大小。脚本流式校验，不解包挂载 userdata，也不宣称能扫描出所有密码。

可由所有者用预先安装的本地 Android sparse 工具压缩 userdata：保留原始 `userdata.img`，先核对本地 `img2simg` / `simg2img` 的命令用法，再执行转换并还原到另一文件，比原始 SHA-256。仅当还原哈希相同，manifest 才改 `file`、`sha256`、`size` 与 `format: "android-sparse"`。工具检查 sparse header/chunk/展开尺寸边界；这只能证明结构一致，**不能证明目标 EDL 的 sparse 写入可用**。`wl --skip` 与 sparse qfil 仍待所有者首台验证，默认不执行，也不推荐用不明参数绕过。

若确实验证必须写 aboot，需 `--include-aboot`，manifest 增加 `aboot_required_verified: true` 并保留验证记录。vendor 则需 `--include-vendor`、`vendor_stock_sha256`，且该 stock 哈希必须不同于捕获镜像。两个标记只是所有者声明，不能代替证据。

## 5. 生成只写白名单分区的 XML

```bash
python3 tools/provision.py golden-plan \
  --images "$HOME/ThinkSmartView/golden" \
  --manifest "$HOME/ThinkSmartView/golden/golden.json" \
  --reference-gpt "$HOME/ThinkSmartView/golden/reference-gpt.json" \
  --target-gpt "$HOME/ThinkSmartView/golden/target-gpt.json" \
  --output-xml "$HOME/ThinkSmartView/golden/rawprogram-thinkos.xml" \
  --loader "$HOME/ThinkSmartView/image/prog_emmc_firehose_8953_ddr.mbn" \
  --loader-sha256 REVIEWED_LOADER_SHA256 --device-owner none --dry-run
```

默认把 XML 放进标准输出 JSON，绝不写文件/访问设备。审核后把 `--dry-run` 改 `--write-plan`，只创建新的本地 XML，拒绝覆盖。该选项不是刷写开关；没有 `--execute-flash`，工具从不运行 EDL。

生成 XML 只包含完整参考镜像的 boot、recovery、system、userdata，可选 cache、明确验证后的 aboot/vendor。它依据已核对 GPT 生成全分区 program 记录；与官方 XML 对比仅参考字段格式，不沿用原包的分片偏移或文件名。

始终排除 `persist`、modem/dsp/sec、`sbl1`、rpm、tz、devcfg、keymaster、cmnlib、lksecapp 及其备份/其他未白名单分区。工具也不接受执行型 XML 输入、DOCTYPE 或任意 patch；它只输出自己的有限 XML。**不要把官方 `patch0.xml` 搭配本 XML 使用**，它包含 GPT 写入。若 EDL 要求 patch 文件，只能由所有者核对本地版本接受的空/无修改补丁形式，再使用；工具输出占位符，不伪造一个已在真机证实的参数组合。

## 6. 所有者首台刷写与验收

首台所有者在场：确认数据可丢弃 → USB 2.0 hub → 手动进 EDL → 比对真实 GPT → 审核生成 XML、loader 和本地 EDL 参数 → 人工 qfil → 人工 reset。失败立即停止，保留私有日志，不转而执行官方全包，也不重复猜参数。批量 EDL 并行尚未验证，一次一台。

启动后按 [安装指南](installation-guide.md#4-只执行应用阶段与批量部署) 用 `batch --serial A --serial B … --dry-run` 审核正式 APK 部署计划，再明确 `--apply-app`。以下都必须记录真实结果：

1. 指定 Lineage/API27/armv7，引导自动出现；无 Server、离线可用。
2. Magisk、Zygisk、所有模块及 Vector 的登记 APK 路径有效；更新器实际版本、公钥与健康状态正确。
3. 至少两台开启投屏后 DLNA UUID/AirPlay deviceid 不同，重启后各自保持。Wi-Fi/蓝牙硬件地址不同、功能正常，传感器与校准数据正常。
4. 没有参考机的 Wi-Fi/ADB/蓝牙配对与 ThinkOS 配对数据；管理状态符合所有者选择。
5. 从进 EDL 到进入引导的真实用时，与单机流程比较。没有测量就留空，不给推测用时。

完成这些物理验证前，生成 XML/哈希、应用安装成功或临时 fixture 通过都不能标记 WEL-5 刷机验收完成。金镜像更新时重新走清理与首台验收；旧镜像保留版本和恢复记录，不把新 APK 直接混入已清理的参考 userdata。

## 7. 2026-11-06 第一次实际取镜像（参考机 44fec07a）

**参考机状态：**按 `docs/19` §12 刷好，装了全部 ThinkOS 模块和正式证书的 0.1.33，Device Owner 已设（`preserved`），默认时区 Asia/Shanghai、语言 zh-Hans-CN，没有保存的 Wi-Fi、没有 adb 信任（所有者决定不带）。取镜像用 `edl r`，文件在 `~/ThinkSmartView/golden/`（仓库外，700 权限）。

**清理顺序**（脚本 `tools/golden_clean.sh`）：先 `am force-stop`，再 `stop` 停掉安卓框架（框架还活着，关机时可能把 Wi-Fi 配置写回去），然后删 ThinkOS 数据、Wi-Fi、adb 信任、蓝牙配置和 `persist.service.bdroid.bdaddr`、netstats、模块 zip 和临时文件，最后把 `/data` 的空闲空间写满零（约 3.7 GB，70 秒）再删掉，让已删文件的残留字节被覆盖。完了直接 `adb reboot edl`；adb 信任删掉后这条连接还认，这个顺序在这台上走通了。

**取镜像后扫描（只用字符串，不能证明全部干净）：**userdata 里没有 `PreSharedKey`、没有 Mac 用户名的 adb 公钥；但找到 4 处所有者家里 Wi-Fi 的名字，位置是 `/data/system/netstats` 的用量统计（魔数 `ANET`），这类文件按网络名记账。已把这 4 处在镜像里按同样长度改成占位字符（只改字节，没动结构），扫描确认为 0；脚本已补上删 netstats，下次从源头清掉。还看到 `bluetooth_address` 存在 `settings_secure.xml` 里（参考机自己的蓝牙地址）、以及某个属性转储文件里带着随机的 `persist.service.bdroid.bdaddr`，没查出是哪个文件。**第二台刷好后要核对蓝牙地址是不是各自的。**

**大小：**boot 32 MiB、recovery 32 MiB、system 1.5 GiB、userdata 4290223616 字节（原始，没有压缩）。读取速度约 33 MB/s。哈希在 `golden.json`。

**没有 sparse 工具（`img2simg`）也不需要：**写入用 `edl w` 逐个分区写原始镜像，这个机制今天已经用过（aboot、recovery、boot 都写成功）。**还没验证的：**`edl` 的稀疏写入、`golden-plan` 生成的 XML 配 qfil 的补丁写法、第二台的 GPT 是否与参考机完全一致、第二台出厂的 `aboot` 和 `vendor` 是否需要进镜像。

**冒烟测试（2026-11-06，写回参考机本身）：**用 `edl w` 逐个写 boot、recovery、system、userdata（写前核对四个哈希和分区表与参考机一致），复位后约 25 秒 adb 出现，状态是“未授权”（符合不带 adb 信任；点允许后要 `adb kill-server` 重连才变成 `device`）。开机正常：时区 Asia/Shanghai、语言 zh-Hans-CN，Device Owner 在、ThinkOS 锁定在前台，九个模块都在，`powerd`、`updaterd`、`wfdd` 在跑，保存的 Wi-Fi 为空（所有者自己连上后天气能取到）。这一次没有测：引导画面本身（所有者看的）、系统栏、蓝牙地址、更新器收更新。
**交付前的状态：**冒烟之后又写回一次，并读回四个分区，哈希都与 `golden.json` 一致，**没有复位**，机器停在 EDL（黑屏）。没有电池，断电再上电就是第一次开机，等于用户拿到手的状态。

## 8. 2026-11-06 第二台：vendor 必须进镜像

**发现：**Lineage 的刷机包自带 vendor 镜像（`vendor.new.dat.br` 加 `vendor.transfer.list`，`updater-script` 里 `block_image_update("…/vendor", …)`）。所以第 4 节“四个分区的镜像”只在**已经刷过 Lineage 包的机器**上能用（第一台冒烟测试就是写回它自己，所以没暴露）；出厂机或只刷了官方整包的机器，vendor 还是官方的，不对。**金镜像必须带 vendor。**

**做法：**`tools/rebuild_vendor_image.py ZIP 输出目录` 把包里的块数据还原成 384 MiB 的原始 vendor 镜像（ext4，卷标 `vendor`，4 KiB 块，`new` 块总数和解压后大小一致），放在 `~/ThinkSmartView/golden/vendor.img`。用同一个脚本重做的结果与实际刷进去的字节完全一致，写入后读回哈希一致。**没验证：**它和第一台经 recovery 刷出来的 vendor 是否逐字节相同（第一台已送出，没有读过它的 vendor）。

**第二台实际走的步骤（出厂固件 `Lenovo_Kingston_OS_DV_190904_V0.0.31-factory`，`adb reboot edl` 直接进 EDL，序列号也是 `44fec07a`，不唯一）：**先 `edl r` 备份它自己的 `persist`、`modemst1/2`、`fsc`、`fsg`、`ssd`、`sec`、`devinfo`，分区表与参考机一致；官方整包 qfil（和第一台一样，也会覆盖 persist 开头约 4.6 MB）；`edl w aboot`；`edl w vendor`（上面还原的镜像）；然后 boot、recovery、system、userdata 四个金镜像；`edl reset`，约 20 秒 adb 出现，状态未授权（符合不带 adb 信任）。总共不到 10 分钟，全程不用碰屏幕。

## 9. 2026-11-21 第三台装机与重新取 userdata（序列号同为 44fec07a）

**装机：**照 §8 的步骤（备份独有分区到 `ThinkSmartView/backup-unit3-20261120-1945/`、官方整包 qfil、`aboot`、`vendor`、四个金镜像，逐个读回核对），不到 10 分钟。分区表与参考机一致。

**OTA 验收（同一台，全程记录在 `docs/10` §16）：**应用 OTA、模块 OTA、更新器自我更新和守卫轮换都在这台上真机走通；坏模块演练（0.1.44 的电源模块 v0.6，只在有 `/data/adb/thinkos-drill-bad-boot` 时让开机停在 zygote）：开机卡住 → 模拟断电重启 → 守卫在挂载模块前恢复 v0.5 → 开机完成后退回旧 APK 并自动重启一次 → 正常。演练标记已删除。

**新的 userdata：**机上为稳定版 0.1.45、更新器 v1.5（守卫 `current` 为 v1.5，`previous` 为 11 月 6 日那份）、电源模块 v0.5，Device Owner 照旧（`preserved`），时区 Asia/Shanghai、语言 zh-Hans-CN。用 `tools/golden_clean.sh` 清理后直接 `adb reboot edl`，`edl r userdata`；boot、recovery、system、vendor 读出与原金镜像逐字节相同，未替换。`golden.json` 的 userdata 摘要已更新为 `1fe92733…d81a`，旧的 userdata 与清单保留在 `golden/old-20261106/`。**旧金镜像里的更新器是 v1.1，装应用时用 `pm install -p` 必定失败（`docs/10` §16），以后新机一律用这份新的。**

**扫描（字符串）：**没有 Wi-Fi 名、`PreSharedKey`、Mac 主机名。仍有：timesync 模块描述里的 `author=` 是所有者用户名（11 月 6 日起就在）；Magisk 的 su 记录里有调试时执行过的命令（只有命令和路径）。**下次清理前**应在机器还开着时清掉 Magisk 的 su 记录，`golden_clean.sh` 尚未包含这一步（未验证具体做法，不要盲加）。

**交付状态：**读出后没有复位，机器停在 EDL（黑屏）；断电再上电就是第一次开机，进入引导、生成本机的投屏身份。
