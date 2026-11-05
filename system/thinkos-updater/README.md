# ThinkOS Updater

这是设备上的 root 验签与安装服务。源码和构建由开发维护；持久安装及重启由所有者运行安装脚本。没有编译进所有者系统公钥时，HELLO/STATUS 可用，所有修改请求返回 `ERR keys-missing`。不会把测试钥匙当正式根。

构建：

```sh
system/build-updater.sh --keys-header /owner/public/updater_keys.h
system/build-updater.sh --host --keys-header /temporary/test-public.h
system/build-updater.sh --device-test --keys-header /temporary/test-public.h
```

正常模块只包含 API27 的 armv7 二进制；`--host`/`--device-test` 才编入临时根目录、测试执行器和认证夹具选项，测试二进制永不进入模块包。公钥头格式为 `UP_SYSTEM_KEY_COUNT`（1–2）及 `up_system_keys[][64]`（P-256 的大端 X||Y），由 `tools/gen_keys.sh` 准备。模块和脚本不读取私钥。

持久安装只由所有者在备用机上执行；`SPARE_SERIAL` 须替换成实际备用机ADB序列号或端点，不能填当前开发机：

```sh
system/install-updater.sh --keys-header /owner/public/updater_keys.h --serial SPARE_SERIAL
```

安装器没有默认设备，也不读取 `ANDROID_SERIAL` 兜底。参数为 `HUA0BWUT` 会在构建/ADB前拒绝；其它端点先只读核对真实 `ro.serialno`，空值、非法/未知身份以及指向 `HUA0BWUT` 的网络别名同样拒绝。F3故障/回滚演练也必须显式选择备用机并检查真实身份，不能在当前开发机演练；安装与重启仍是人工步骤。本示例不是运行授权，脚本不会自动重启。

协议 1 使用抽象 Unix 流 socket `@thinkos-updater`，每个连接一条 ASCII 命令与 LF。整行读取共用 2 秒期限，最多 256 字节。每个连接重新检查内核 peer UID 与 `packages.list`，只允许 root 和 ThinkOS。请求不能带用户路径或命令参数。

| 命令 | 文件描述符，顺序固定 |
|---|---|
| HELLO、STATUS、BEGIN、REBOOT、HEALTHY、ROLLBACK | 无 |
| STAGE | 清单、ASN.1 DER 签名、模块包 |
| INSTALL_APK | ThinkOS APK |

文件必须为只读的常规 FD；多余 FD、控制消息截断、管道、写描述符均拒绝。先复制到 root 私有目录，再校验原始清单签名和包摘要。协议不解释归档，不接受下载来源的公钥。只允许七个固定系统模块 ID。

`STATUS` 返回 `OK` 后的一行 JSON：protocol、keys、transaction、id、phase、result、rollback_attempted、modules。phase 为 0 空闲、1 已备份、2 已暂存、3 安装中、4 已请求重启、5 回滚中、6 失败。result 为 0 无、1 健康、2 已回滚、3 失败。模块项给出实际 code、已确认 floor、当前 staged code。

`/data/adb/thinkos/state.bin` 是固定长度、带版本与校验和的私有记录；不是应用或 Server 的数据格式。状态同步和同目录原子改名后才能执行安装，只有 HEALTHY 推进 code floor。安装中断不自动当成功。BEGIN 保存当前 ThinkOS APK及现存白名单模块；Vector 更新另先保存旧 APK 和数据库，更新及回滚后都重新同步真实 APK 路径。记录损坏会停止启动，不重置防回退记录。状态同步结果不确定时，本进程拒绝继续修改，保留备份待恢复。

APK 安装固定 `pm install -r -p moe.kiwi.thinkos`，Vector 固定 `moe.kiwi.thinkos.immersive`；`-p` 在 API27 的会话中锁定包名，签名与版本仍由 Android 验证。只有回滚旧包才使用 `-d`。执行器始终使用固定绝对路径和参数，不拼接 shell。重启须先有已暂存模块，并且一小时最多一次；不进行中的事务不能重启。

主机验收入口为 `system/test/run-updater.sh`，使用临时测试钥匙、私有目录及固定假执行器，不碰设备、不安装模块、不重启。该测试不替代实际 Magisk/Vector 安装与坏启动恢复验收。

显式事务、早期开机守卫、健康超时回滚和应用 UpdaterClient 的源码已接通；健康时长读设置schema（默认900秒）。正式系统公钥、持久部署与F3真实故障/回滚仍需备用机人工步骤及独立证据，主机检查不能代替这些验收，也不能据此在当前开发机安装或重启。
