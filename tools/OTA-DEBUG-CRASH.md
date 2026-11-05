# OTA-5 owner-debug v2 崩溃演练材料

`tools/ota_debug_crash.py` 在所有者主机上准备一种独立实验材料
`ota5-owner-debug-crash-v2`。它验证已安装候选的策略能力报告，生成待签通道计划，
经所有者审阅后用既有通道钥匙签名。它不连接设备或网络，不构建、安装、发布、
生成钥匙或制造崩溃。

正式证书、公钥资产和本机签名配置见 [OWNER-RELEASE.md](OWNER-RELEASE.md)。
正常来源的公开 UI、安装和真实600秒健康流程见
[OTA5-UI.md](tests/OTA5-UI.md)；签名语义负例和序号消耗见
[OTA-NEGATIVE.md](OTA-NEGATIVE.md)。本工具不替代这些流程。

## 先决条件与设备边界

- OWN-6 的所有者证书、公钥资产和证书迁移已完成；候选与前驱使用同一所有者
  APK 证书。候选是 `moe.kiwi.thinkos` 的 **debuggable** APK，minSdk/targetSdk
  均27，只含真实 ELF32 ARM/armeabi-v7a 本机库。正式 release 的故障接口不可用。
- 候选实际 APK 内的 `assets/update/keys.json`、`system-keys.json` 与所有者公开
  导出逐字节一致。通道、仓库、系统各有独立 active/backup，共六把互异公钥和
  ID；生产 loader 必须实际接受这些资产，不能用下载根、额外 debug 根或
  `.regfixture` 测试根代替。
- 设备是 OWN-4 下已获授权的、闲置且单人使用的备用机。音量流1–5已经全部为0。
  **HUA0BWUT、它的连接别名和未知物理身份不可运行本演练。** 主机必须在启动
  instrumentation **之前** 核实物理身份；设备 phase 还会独立重查并拒绝 HUA。
- 已有 update/module pending、其备份、故障 marker、试用或恢复现场必须保留，
  不为通过前置而清除。候选的预安装、基线安排和任何证书迁移由原有授权流程
  单独处理；本手册不增加安装、系统重启、设置/清除 Device Owner 或持久模块权限。

## 已安装候选的只读能力报告

测试入口为现有 `UpdateInstrumentation` 的固定 phase
`debug_commit_fault_capability_v2`，只接受三个参数：

| 参数 | 要求 |
| --- | --- |
| `phase` | `debug_commit_fault_capability_v2` |
| `run_id` | 本次唯一的32位小写十六进制 |
| `physical_serial` | 已在主机核实的真实备用机物理序列号 |

目标必须是实际已安装的 `moe.kiwi.thinkos`，执行 UID 与目标 UID 相同，测试包与
候选相匹配。构建测试入口使用现有 `-PupdateProbe=true`；所有者签名仍使用
`android/keystore.properties`，按 OWNER-RELEASE 配置。这里不提供设备启动命令，
以免跳过备用机和既有事务的外层保护。

Android 在 phase 之前就会绑定普通 Application，已有 UpdateGuard、崩溃恢复与
debug 启动检查可能先运行。因此 `probe_starts_main=false`、
`probe_starts_runtime=false` 只描述 phase 自身，不保证整个 Application 启动无
副作用；有真实试用或故障现场时，不能靠 phase 内的拒绝检查保护它。

实际调用链是
[`DebugCommitFaultCapabilityProbe`](../android/app/src/androidTest/java/moe/kiwi/thinkos/DebugCommitFaultCapabilityProbe.kt)
→ 已安装目标的
[`DebugUpdateFault.readOnlyCompatibility`](../android/app/src/debug/java/moe/kiwi/thinkos/DebugUpdateFault.kt)
→ [`DebugCommitArm.compatibility`](../android/app/src/debug/java/moe/kiwi/thinkos/DebugCommitArm.kt)。
它检查全局故障策略始终 idle，但只对新的局部 RAM 对象执行 marker 编解码、
一次性提交准入和 TRIAL 判断，不创建真实 lease、marker、pending、安装会话或崩溃。

报告的完整执行绑定包括：

1. `ProductionRootsProbe` 前后读取实际已安装 APK 的 hash、size、version、code、
   signer 和六角色根资产，并确认一致。
2. 冻结目标 `packageCodePath` 的 canonical 路径，前后确认与当前 PM `sourceDir`
   相同；真实 `DebugUpdateFault` 的 classloader 必须与目标 Context 相同。
3. helper 在 UpdateIo 上用 `inspectInstalledForRecovery` 前后完整验证执行路径的
   APK 字节身份，与报告 tuple 完全相同，才给出
   `executing_apk_identity_verified` 和 `helper_target_classloader_equal`。
4. 实际生成并解析 schema2 marker，绑定候选完整 tuple，报告原始 marker 的
   base64/摘要及18项布尔结果：手动一次性提交和第1–3次 TRIAL 准入；重复、自动、
   错会话、过期、无 pending、未提交、INSTALLING、第4次、错身份/目录/说明及旧
   marker 的相应拒绝。

这证明的是 `policy_roundtrip_only`。候选的 DEX 类名、debuggable 标志、相同
version/code、旧 `debug_commit_fault_arm` 状态或单独 roots 报告都不足以替代它。
保存 instrumentation Bundle 中**完整的 `result` JSON**，包含外层
`phase/complete/checks` 和内层能力对象，供 `--capability-receipt` 使用；不要只
保存嵌套对象、日志摘要或人工补写成功字段。producer 读取 JSON 文件，不解析整段
instrumentation 文本。

报告是所有者保存的本地观察，不是硬件远程证明。保留原始采集记录和其 SHA256；
producer 会把每个 receipt 字节纳入审阅 fingerprint。主机测试中的合成 receipt
只验证解析和拒绝逻辑，不是设备能力证据。

## 离线 plan

准备以下仓库外、非符号链接输入：

- 已完成上述只读预资格验证的 owner-debug 候选 APK、精确版本号和完整能力 JSON。
- 同一所有者的公开导出目录：`keys.json`、`system-keys.json`、`updater_keys.h`、
  `key-inventory.json`、`apk-certificate.der`；源码公钥资产也须逐字节相同。
- 已认证的 APK-only 前驱 catalog 及相邻 `<catalog>.sig`。其通道签名有效、尚未
  过期、`modules=[]`、signer 相同，候选 code 严格增加。
- 显式的新 seq、published/expiry 毫秒时间、HTTPS 源码地址、数值私网 HTTP 根
  和全新输出目录。seq 必须高于前驱，也须由所有者结合设备已消费的最高 seq
  选择；本离线工具不读取设备 floor。HTTP 根仅允许 RFC1918 IPv4/IPv6 ULA，
  无 userinfo/query/fragment，不接受公网或 loopback 代替真实 LAN。

下列路径和版本仅展示参数；`OTA_NEXT_SEQ`、`OTA_PUBLISHED_MS`、`OTA_EXPIRES_MS`
由所有者按真实材料明确填写，publication 不晚于当前时间、expiry 晚于当前时间：

```sh
python3 -B tools/ota_debug_crash.py \
  --apk /owner/candidate/owner-debug.apk --version 0.2.1 \
  --public-dir /owner/public \
  --capability-receipt /owner/evidence/candidate-v2-result.json \
  --previous-catalog /owner/previous/beta.json \
  --seq "$OTA_NEXT_SEQ" \
  --published-ms "$OTA_PUBLISHED_MS" --expires-ms "$OTA_EXPIRES_MS" \
  --source-url https://github.com/OWNER/ThinkOS/tree/v0.2.1 \
  --lan-root http://192.168.1.20:8765/ota/crash-v2 \
  --slot active --output /owner/material/new-crash-v2
```

默认 plan 使用有界的公开输入快照，校验真实 APK 签名、ABI、版本和资产、前驱
原始签名、执行身份及全部18项结果。它不读取私钥、不索取口令、不签名，不创建
指定输出目录，也不启动服务。CI 中连 plan 也拒绝。审阅输出的 `input_sha256`、
APK tuple、receipt 摘要、公钥摘要、seq、channel、mirror 和三个预期文件名。

## 审阅后签名

由所有者在本机重复**完全相同的 plan 参数**，追加：

```text
--execute --reviewed-sha256 <刚审阅的 input_sha256>
--private-key /owner/private/channel-active.key.pem
--password-fd 3
```

`--slot backup` 须从 plan 起就明确选用，并提供对应的既有加密 channel backup
钥匙。私钥位于仓库外、所有者控制的0700目录/0600文件；registry/system 钥匙或
不匹配的 channel 钥匙会拒绝。工具不生成或修复钥匙。

口令通过所有者已打开的私有 FD3及以上传入；`--password-fd 3` 是两个 argv token，
不是口令参数。输入上限4096字节、至少12字符，不允许换行内容/NUL。若省略该
参数，只允许所有者的交互 TTY 隐藏输入两次。工具只在签名子进程的短暂环境中
传递口令，不写入报告/输出文件。不要把私钥或口令放进仓库、CI、命令行或日志。

execute 在读私钥之前和签名之后重新捕获并验证公开输入及 fingerprint。输出
是全新私有目录里的三个文件：`owner-debug-crash-v2-<version>.apk`、
`<channel>.json.sig`、最后提交的 `<channel>.json`；只复制已核原始字节，不复制
receipt、报告、钥匙或源码。保存 CLI JSON 到输出目录**之外**。失败保留部分产物，
不覆盖、不自动清理或重试。

成功状态为 `prepared`、`artifact_kind=ota5-owner-debug-crash-v2`，仍然
`published=false`、`normal_release_eligible=false`。普通 Ota5Origin/正常 OTA5 UI
runner 拒绝这个实验 kind；目前没有据此自动执行真实 crash 的普通入口，不能改
kind 或塞进任意 HTTP 目录绕过。

## 仍需独立真实验收

只读资格报告不证明旧基线能正确 arm，也不证明实际 PackageInstaller commit、
真实三次启动崩溃、第4进程回滚或黑名单。真实 debug 路径只允许同一次精确手动
事务在最终 staging/活会话准入后写 schema2 marker；启动还须完整已装 APK 身份
和最后重读的真实 TRIAL 匹配。release 的 arm/compatibility 抛不支持，
beforeCommit/check 为 no-op。

后续备用机演练需另行绑定真实基线 N、相同已预资格的 N+1 字节、原生最终点按和
现有事务生命周期。不得缩短600秒健康门槛，不 raw restore 或清除 seq、floor、
failure、blocked、pending、marker、恢复或 native 历史来重跑旧材料。所有输出仍
明确 `runtime_crash_verified=false`、`actual_commit_verified=false`、
`baseline_arm_capability_verified=false`、`ota5_device_acceptance_complete=false`；
这些值只有相应的独立真实证据才能在验收记录中收口。
