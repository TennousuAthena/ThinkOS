# 40 · 断电保护：拔电源线时怎么不伤分区

> 2026-11-03。所有者：这台设备没有电源键，关机靠拔线，突然断电有小概率损坏启动分区，要在软件里做保护，“这很重要”。本文记录查到的事实、方案、实验和还没验证的东西。代码在 `system/thinkos-power`，任务是 `36` 号文档的 PWR-1 到 PWR-4。
> 状态：守护进程和主机测试（28 项）已经有；**拔线实验还没做，所以阈值是依据平时的电压定的临时值，不能当作已经验证的保护**。

## 1. 问题

设备靠一根圆口电源线供电，没有电池，没有 RTC，也没有电源键。拔线时 eMMC 可能正在写（日志、SQLite、插件存储、下载），闪存控制器内部也可能正在整理映射表。突然断电在这种便宜的 eMMC 上有小概率损坏分区。另一位开发者的做法是在设置页放一个“关机”按钮，点了以后黑屏，等 10 秒拔线，再等 30 秒让芯片放电。那是让人先关机再拔，**拔线本身还是不受保护的**。

所有者的想法：板子上电容不小，原厂设计很可能是检测到电源线被拔就自动触发关机。

## 2. 查到的事实

### 2.1 硬件

| 事实 | 来源 |
|---|---|
| 供电是一个圆口（barrel jack）；USB-C 在屏幕边缘的橡胶塞下，只当数据口。设备出厂不带电源适配器的 eBay 卖家也有 | Home Assistant 社区 ROM 开发帖（FelixKa：“plug in the barrel connector to power it up”）、`jethac/lenovo-thinksmart-view` 的 `CHIP.md` |
| 没有电池；电源键没接线：`kpdpwr` 在设备树里被禁用，只有音量下接在 PMIC 的 `resin` 上 | `jethac/thinksmart-view-hal`、`CHIP.md` |
| PMIC 是 PM8953 + PMI8950；板上的充电器“no battery fitted”，Type-C 块“stays inert”，CC 脚没有 Rd | `CHIP.md`、`devices/usb/ANALYSIS.md` 的摘要 |
| 原厂的“拔线就关机”**没有找到资料**。联想的快速入门只说接上电源会自动开机；没有任何文档、论坛帖提到断电检测。 | 搜索 `ThinkSmart View CD-18781Y power loss`，XDA、Tao of Mac、manualslib、awesome-thinksmart 都没有 |

### 2.2 这台设备上读到的（HUA0BWUT，Lineage 15.1，内核 3.18.71）

| 事实 | 读法 | 含义 |
|---|---|---|
| `power_supply/battery` 有，是假的：Li-ion、50%、3.95 V，状态 Discharging | `/sys/class/power_supply/battery/uevent` | 电池事件不会因为拔线变化，`ACTION_POWER_DISCONNECTED` 之类的广播靠不住 |
| `power_supply/usb` 在线，类型 USB，500 mA；`usbin` 通道读 5.12 V | 同上，`qpnp-vadc-16/usbin` | 应该是插在 Mac 上的 USB 线的 VBUS，不是电源线；没拔过 USB 线验证，见实验 E1 |
| `dcin` 通道只有约 0.15 V | `qpnp-vadc-16/dcin` | PMIC 的 DC 输入没接圆口电源线，读不到电源线本身 |
| 没有专门的断电中断或 GPIO：`/proc/interrupts` 里 GPIO 中断只有 `fts_ts`、`volume_up`、`mute`、`camera_switch`；充电器的各个中断计数全是 0 | `/proc/interrupts` | 没有“电源线被拔”的事件可订阅 |
| **可以读系统主供电轨 VPH_PWR**：`qpnp-vadc-6/vph_pwr`，内容 `Result:<µV> Raw:<hex>`，通电时约 3.97–4.05 V | sysfs | 唯一可用的信号 |
| VPH_PWR 在 8 核满载 10 秒时没有下陷：空闲 3.971–4.026 V，满载 3.970–4.050 V | 2026-11-03 Mac 上采样（约 46 次/秒） | 负载不会造成误触发 |
| 最快采样：一次读取约 1.3 ms（p50 1324 µs，p95 1667 µs，最长 4.5 ms），可到每秒约 670 次；高速采样时的噪声 3.939–4.085 V | `powerd --probe --interval-ms 1` | 阈值留 130 mV 以上余量够 |
| **空闲一小时基线**：电源线和 USB 线都接着，ThinkOS 在前台，每 2 ms 一个样本共 1,518,821 个：最低 3.933 V、第 0.1 百分位 3.942 V、中位 3.999 V、第 99 百分位 4.032 V、最高 4.089 V；低于 3.9 V 的 0 个，低于 3.8 V 的 0 个；逐分钟中位数在 3.952–4.013 V 之间漂 | 2026-11-03 21:23–22:23，`artifacts/power/e2-probe-20261103-212342/baseline-summary.json`（原始 65 MB 没留） | 3.80 V 的触发线离平时最低值 133 mV，离中位 200 mV；漂移说明“上膛”要求的 3.90 V 也有余量 |
| 50 Hz 采样（每 20 ms 一次）占约 1.9% 的一个核（10 秒用了 0.19 秒 CPU） | `time powerd --dry-run --duration 10` | 可接受 |
| 内核自带的低电压监视 BCL：`vph_low_thresh_uv` 3.2 V、`vph_high_thresh_uv` 3.5 V，当前 `vph_state=2` | `/sys/devices/soc/soc:qcom,bcl` | 阈值离 4.0 V 太远（到 3.2 V 时留给收尾的时间所剩无几），不用它 |
| `CONFIG_MAGIC_SYSRQ=y`；`/proc/sys/kernel/sysrq` 是 0，但**写 `/proc/sysrq-trigger` 不受这个开关限制**（写 `h` 后内核日志出现 SysRq : HELP） | `/proc/config.gz`、实测 | `u`（紧急只读重挂载）、`o`（立即关机）可用 |
| `/data` 是 ext4（`data=ordered`，在线 `discard`），无加密（`ro.crypto.state=unsupported`），另有 `/cache`、`/persist` 读写分区 | `mount` | 要转只读的不止 `/data` |
| 原厂 vendor 的 rc 脚本里只有通用的 `sys.shutdown.requested` 处理，没有断电相关 | `/vendor/etc/init/hw/*.rc` | 没有找到原厂的断电处理；**没有解开原厂 system 分区和 boot.img 来查**，所以“原厂没有”不能下结论 |

### 2.3 没有查到、也没法在软件里知道的

电源线一拔，**板子靠电容还能撑多久、VPH_PWR 先怎么掉**。VPH_PWR 是稳压后的 4.0 V 轨：输入电容放电时，稳压器会让它一直保持到输入掉到接近 4.0 V 才开始掉。如果是那样，轨上能看到的警告时间会比电容总共撑的时间短得多。这决定了软件“来不来得及”，只能实测（§5）。

## 3. 方案

守护进程 `powerd`（Magisk 模块 `thinkos-power`，C，armeabi-v7a，root），不放在应用里：要写 `/proc/sysrq-trigger`，而且应用崩了、卡了，保护也得在。

**盯什么**：VPH_PWR，默认每 20 ms 一次。

**什么时候触发**：连续 3 次低于 3.80 V，或者有一次低于 3.50 V。依据：平时 3.94–4.09 V（高速采样的极值），满载不下陷。这两个值是**临时值，等实验调**。

**触发以后（在剩下的时间里）**：

1. 提高自己的调度优先级；
2. `sync()`：把脏页刷下去；
3. 写 `u` 到 `/proc/sysrq-trigger`：内核强制把所有读写分区转成只读（先把日志刷完），之后没有任何写入，eMMC 在断电的那一刻是闲的；等 `/data` 在挂载表里变成 `ro`（最多 0.5 秒）；
4. 灭背光（`lcd-backlight`、`wled`）：省一点电，也让人看见“收到了”。

**之后继续看电压**：

| 电压 | 动作 |
|---|---|
| 回到 3.90 V 以上并保持 1 秒 | 虚惊：干净重启，让分区回到可写 |
| 掉到 3.40 V 以下，或者连续 0.5 秒读不到 | 写 `o`：由 PMIC 有序关机，不等它自己跌落 |
| 触发 30 秒后还在中间 | 供电不健康，关机 |

**不上膛**：开始 10 个样本的中位数低于 3.90 V（触发线加 100 mV）时，说明不是我们认识的板子或状态，不动作，每分钟重测一次。

**进程自己**：`mlockall`（触发时不因缺页去读 eMMC）、`oom_score_adj=-1000`、提前打开所有要写的文件、持有一把唤醒锁（保证系统不进挂起而停止采样）。

**模块**：`post-fs-data.sh` 在开机早期后台起守护进程（开机前一两分钟读写最多，这时断电最危险），退出后 2 秒拉起，退出码 3（读不到供电轨）则不再起。安装时检查 `vph_pwr` 在不在，不在就中止。没有 sepolicy 规则（设备是 permissive，enforcing 下没有验证）。不改 `/system`。

**误触发的代价**：电压闪一下低于 3.8 V 超过 60 ms，会有一次紧急只读，然后约一秒后判定恢复，干净重启一次（60–90 秒）。不会让设备停在关机状态：关机只在电压确实掉到 3.4 V 以下或 30 秒仍不恢复时。

### 判断逻辑的测试

`system/test/run-power.sh`：28 项。17 项用样本文件回放判断逻辑（噪声、去抖、一次深跌、恢复后重启、闪一下不算恢复、继续掉就关机、读失败、卡在中间、状态用完以后不再动作、不认识的电压轨、阈值顺序错误、垃圾样本）；11 项对着假的 sysfs 文件跑完整流程（含真的 `sync`、等 `/data` 变只读、`--dry-run` 什么都不改、sysrq 文件不存在、读不到供电轨退出码 3、`--probe`、接收端断开后 `--probe`/`--trace` 自己退出、SIGTERM）。设备上用同一份源码的 armeabi-v7a 版跑过 `--probe` 和 `--dry-run`。

## 4. 不做的事

- **不用电池广播**：电池是假的，拔线时不变。
- **不在应用里做**：见上。
- **不改内核、不改设备树**：也不动 `/system`。要早一点收到警告（比如把 PMIC 的 ADC 门限比较器配成硬件中断），需要改内核，不在这一轮。
- **不关机按钮**：拔线本身受保护以后就不需要先关机再拔。但若要一个关机按钮（比如要把设备挪走、不想它重启），应用里没法关机（`DevicePolicyManager.reboot` 只能重启），要让这个守护进程多听一条 `POWEROFF` 命令，见 PWR-4。

## 5. 实验（要人拔线）

目的：量出拔线以后 VPH_PWR 怎么掉、设备还能撑多久，这决定阈值和能做多少收尾。工具：`tools/power_trace.py`。采样走 Wi-Fi 实时传回 Mac，设备断电前最后几毫秒也有记录；不写设备的分区，设备上的东西在 `/data/local/tmp`，重启即消失。

**E1（安全）USB 线给不给板子供电**：先拔 USB 数据线，看 `usbin` 是否掉到 0、设备是否继续运行。如果 `usbin` 掉到 0 而设备继续运行，说明 USB 线不供电，实验里不必管它。如果设备断了，说明 USB 线在供电，那接着做 E2 时必须先拔 USB 线。

**E2（会硬断电一次，和平时拔线一样）拔电源线**，不带保护，只记录：

1. `python3 -I tools/power_trace.py start`（probe 模式，每 2 ms 一个样本）；
2. 拔 USB 线，等 20 秒以上；
3. 拔电源线，不用管它，约 10 秒后重新接上；
4. 脚本发现设备 4 秒没有数据，打印摘要：从多少伏、几毫秒掉到几伏，最后一个样本的时间。

**E3（硬断电第二次）带保护拔线**：`--mode guard`，同样的步骤，但这次守护进程是真的上膛，日志里有 `tripped`、`sync done +N us`、`/data is read-only +N us`、`flush finished +N us`，对照 E2 的掉电曲线就知道收尾来不来得及。开机后检查 `dmesg | grep EXT4-fs`：干净则没有 `recovery`/`orphan`。

每次拔线都是一次真实的突然断电，风险和平时拔线相同。实验前脚本会 `sync`；设备尽量闲着。

### 实验结果

（待填。E1、E2、E3 各一次，原始数据放 `artifacts/power/`。）

2026-11-03 21:23 启动过一次记录，等了 1 小时没有人拔线，到时间被后台任务上限停掉；E1、E2 都没做。副产品是上面表里的空闲一小时基线。

## 6. 没有验证的

- 拔线以后 VPH_PWR 的曲线和设备撑的时间（§2.3，实验 E2）。**在这之前，3.80 V 的触发线、20 ms 的间隔、各个收尾步骤是否来得及，都只是推测。**
- `sync()` 加 `sysrq u` 在触发后实际要多久（实验 E3 才有数字）。在设备上没有试过 `u`，因为它会让 `/data` 变成只读，要重启才能恢复。
- 触发后 `reboot` 路径、`o` 关机路径在真机上的行为（主机测试用假命令代替）。
- SELinux enforcing 下守护进程能否读 `vph_pwr`、写 `sysrq-trigger`（设备是 permissive）。
- 开机早期（`post-fs-data`）启动是否一切正常；Magisk 对这个阶段的脚本有超时，本脚本在后台起，立刻返回。
- 持有唤醒锁对待机功耗和 ThinkOS 注意力策略的影响（设备常插电，预期没有）。
- 50 Hz 采样对时钟（ADR-029）的影响：占约 2% 的一个核，没有跑 `clock_accuracy_device.py`；跑之前要先告知所有者。
- 原厂 system 分区和 boot.img 没有解开分析，不能排除原厂有我们没看到的断电处理。
- 另一位开发者说的“突然断电会损坏启动分区”：没有在本设备上复现过，也不知道是哪个分区、什么机制。守护进程保护的是“断电时没有写入”，不是针对某个分区。

## 7. 来源

- Home Assistant 社区：[Lenovo ThinkSmart View ROM/OS Development](https://community.home-assistant.io/t/lenovo-thinksmart-view-rom-os-development/676324)
- [jethac/thinksmart-view-hal](https://github.com/jethac/thinksmart-view-hal) 及其指向的 `jethac/lenovo-thinksmart-view`（`CHIP.md`）
- [awesome-thinksmart](https://github.com/r4ym0n/awesome-thinksmart)（内核源码 [Starfire-development/android_kernel_lenovo_apq8053](https://github.com/Starfire-development/android_kernel_lenovo_apq8053)）
- 联想快速入门：[manualslib](https://www.manualslib.com/manual/2182628/Lenovo-Thinksmart-View-Cd-18781y.html)
