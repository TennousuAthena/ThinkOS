# system/ — ThinkOS 的系统层

ThinkOS 不只是一个安卓应用，也是一套对安卓的整体改动（ADR-033）。需要系统权限、应用自己拿不到的能力，放在这里，做成 Magisk 模块（或，改的是系统框架里某个 Java 方法时，做成 Vector 模块）。应用一侧只通过一个很窄的接口和它说话，**没有模块时应用照常工作**（ADR-015）。

| 模块 | 作用 | 谁用 |
|---|---|---|
| `thinkos-cast` | root 守护进程：代应用向 wpa_supplicant 设置 Wi-Fi Display（Miracast）Sink 信息，让手机和电脑能把设备当作无线显示器发现 | `android/.../cast/miracast/` |
| `thinkos-immersive` | Vector（Xposed）模块1.2：全局抑制临时系统栏与键盘主动显示导航栏；原生左缘固定Back、底缘私有Home通知 | ADR-046全局手势 |
| `thinkos-shell` | Magisk开机固定事务1.1/协议2：授予ThinkOS悬浮窗、全局隐藏状态栏和导航栏；没有root守护进程或触摸读取器 | ADR-046全局控制栏与返回边缘 |
| `thinkos-certs` | Mozilla/curl 2026-10-20 的117张根证书，系统目录无痕覆盖；不运行守护进程 | `docs/41-ca-trust.md`；持久安装与重启归人工 |
| `thinkos-power` | root 守护进程：盯系统主供电轨 VPH_PWR，拔电源线掉压时 `sync`、紧急只读、灭背光，再看电压回来（重启）还是继续掉（关机）。**未装进设备，阈值待拔线实验** | `docs/40-power-loss.md`；不和应用说话，应用没有它也照常工作 |
| `thinkos-updater` | 独立系统根验签、固定七id的受限模块OTA与应用安装事务；应用只经窄接口提交只读FD，不`su` | `android/.../update/`；未持久部署，首次安装和模块外守卫归人工，仅备用机 |
| `timesync` | 已纳入源码的开机校时模块 | `system/timesync/`、`build-timesync.sh`；来源见该目录的`ORIGIN.json` |

## thinkos-updater

源码与构建已实现，不代表本机已启用模块OTA。固定七id是`thinkos-updater`、`thinkos-cast`、`thinkos-immersive`、`thinkos-shell`、`thinkos-certs`、`thinkos-power`、`timesync`；模块清单与包先从只读FD复制进root私有目录，再独立验证编入更新器的系统公钥、原始清单签名、包摘要和code防回退。协议不接受任意命令、路径或下载来的公钥，应用没有`su`；没有更新器时应用照常运行。

首次持久安装与重启由所有者按[更新器说明](thinkos-updater/README.md)执行，模块之外的稳定恢复程序、启动脚本及策略另按[恢复守卫说明](thinkos-updater/guard/README.md)部署，不能把仅安装ZIP当作守卫已到位。本轮只准备用机，`install-updater.sh`要求显式`--serial SPARE_SERIAL`，同时拒绝HUA0BWUT及其网络别名；这不是开发Agent安装或重启的授权。

```sh
system/build-updater.sh --keys-header /owner/public/updater_keys.h
# 以下仅由所有者在备用机执行；脚本不会重启
system/install-updater.sh --keys-header /owner/public/updater_keys.h --serial SPARE_SERIAL
```

后续产品更新由所有者预先配置的签名OTA链路经更新器完成，仍仅处理上述白名单。没有正式系统公钥时修改请求拒绝；无法读取状态、事务不明或恢复不确定时不推断成功、不重置防回退记录。完整协议、守卫与回滚边界见两个说明文件及`docs/34` §4.5–4.6。

已留证的范围是armv7/API27构建、host模拟执行器/独立验签，以及0.1.9临时keys0只读库存与无更新器UI：`artifacts/system/MOD-1/host-20261104/qualification.json`、`artifacts/update/MOD-2/0.1.9/qualification.json`。更新器和外置守卫未持久安装，正式系统根、真实升级/重启/健康/坏模块回滚仍待备用机；host夹具和临时STATUS不能代替这些验收。

## thinkos-power

```
thinkos-power/
  module.prop         Magisk 模块说明（id thinkos-power）
  customize.sh        安装检查：只接受 32 位 ARM、API 27+，读得到 vph_pwr 才装
  post-fs-data.sh     开机早期后台起守护进程，退出后 2 秒拉起；退出码 3（读不到供电轨）则不再起
  src/powerd.c        守护进程
  META-INF/           Magisk 模块包的标准安装器
build-power.sh        构建（armeabi-v7a 模块包；--host 出本机测试版）
install-power.sh      构建并装到设备（人工执行）
test/run-power.sh     主机测试，28 项：17 项回放样本验证判断逻辑，11 项对着假的 sysfs 跑完整流程
```

设备没有电源键，关机靠拔线，突然断电有小概率伤分区。板子上没有“断电”信号（PMIC 的 DC 输入没接电源线，没有专门的中断），能读的是系统主供电轨 `/sys/devices/soc/qpnp-vadc-6/vph_pwr`（µV，通电时约 3.97–4.05 V）。`powerd` 每 20 ms 读一次：连续 3 次低于 3.80 V（或一次低于 3.50 V）就触发，触发后 `sync()`、写 `/proc/sysrq-trigger` 的 `u`（强制所有读写分区转只读）、灭背光；之后电压回到 3.90 V 以上保持 1 秒就干净重启，掉到 3.40 V 以下或 30 秒仍不恢复就写 `o` 关机。事实、方案、为什么不用 BCL 和电池广播，见 `docs/40-power-loss.md`。

```bash
system/build-power.sh                 # system/build/thinkos-power-v0.1.zip
system/test/run-power.sh              # 28 项
python3 -I tools/tests/power_device.py   # 真机上 11 项，对着假的供电轨，不碰真实的
system/install-power.sh               # 装到设备（人工）；装完重启
```

用法（调试，不持久；程序放 `/data/local/tmp`，重启即消失）：

```bash
adb push system/build/powerd-armv7 /data/local/tmp/powerd
adb shell 'su -c "/data/local/tmp/powerd --probe --interval-ms 2 --duration 5"'   # 只记录电压：毫秒 µV 读取耗时
adb shell 'su -c "/data/local/tmp/powerd --dry-run --duration 10"'                 # 照常判断、写日志，不写 sysrq、不灭背光、不重启
python3 -I tools/power_trace.py start                                              # 拔电实验（要人拔线），见 docs/40 §5
```

### 已知限制

- **阈值是临时值**，依据平时电压定，没有拔线实验的曲线（`docs/40` §5，任务 PWR-1）。VPH_PWR 是稳压后的轨，输入电容放电时它可能一直保持到最后才塌，那样轨上能看到的警告会很短。
- 在设备上只用假的供电轨和假的 sysrq 文件跑过完整流程；真实的 `u` 会让 `/data` 变只读、要重启才恢复，没有在开发机上试过，第一次是拔线实验 E3。
- SELinux enforcing 下没有验证（设备是 permissive）。
- 采样占约 2% 的一个核，没有测对时钟的影响（ADR-029）。

## thinkos-shell（v1.1，协议2）

配合thinkos-immersive 1.2与支持ADR-046的新版APK，全系统隐藏安卓状态栏和导航栏，抑制边缘临时栏；左缘返回，底缘回home首页，顶缘为ThinkOS控制栏。APK声明global_gestures元数据2，模块还检查悬浮窗appop、完整隐藏策略与两条原生手势钩子都已就绪。旧APK、旧Shell、权限撤销或Back注入失败时恢复安卓出口，不能把兼容降级当作全局目标完成。

shell.sh只操作固定ThinkOS包的SYSTEM_ALERT_WINDOW、policy_control和运行期协议标志sys.thinkos.shell=2；保存root私有状态，在写入失败时回滚。完成校验后，给不导出的ShellChangedReceiver发送固定刷新通知，避免属性最后写入却没有ContentObserver导致已在后台的窗口漏刷新；通知不携带输入/导航请求，旧APK缺接收者也不回滚已成功策略。还原后同样通知。模块只做启动事务，不启动root触摸读取器、不覆盖/system。原状态在/data/adb/thinkos-shell-state跨重启保留，升级不覆盖；撤销只还原仍属于模块的值，保留后续人工修改。应用不su、不写这些系统策略。

构建：system/test/run-shell.sh、system/build-shell.sh，产物system/build/thinkos-shell-v1.1.zip；system/test/run-immersive.sh、system/build-immersive.sh生成thinkos-immersive-1.2.apk。ZIP只支持Magisk应用/CLI，不包含recovery安装器，依据[Magisk模块格式](https://topjohnwu.github.io/Magisk/guides.html#magisk-module-installer)。本项目MIT代码，无新增第三方库、原生库或GPL来源复制。

持久步骤仅由人工执行：先装新版ThinkOS APK，再运行system/install-immersive.sh和system/install-shell.sh HUA0BWUT，按安装说明启用系统框架作用域，最后人工重启。脚本不会自动重启。不要在当前1.0模块上把全局模式当作已生效；安装后的真实系统回调/临时栏验证仍待人工步骤。

卸载Magisk模块并重启会运行uninstall.sh。只想禁用时，人工先运行`adb shell "su -c 'sh /data/adb/modules/thinkos-shell/shell.sh restore /data/adb/thinkos-shell-state'"`再禁用、重启。Agent没有执行这些持久步骤。

临时功能测试：apps_foreign_panel_device.py --home-signal在/data/local/tmp独立目录运行相同apply/restore，只验证窗口、媒体和私有Home接收者，不加载系统框架钩子；原策略/appop/属性、十文件精确还原。global_home_device.py检查ThinkOS自己的真实底缘Home，global_back_device.py以临时app_process验证同一Back发射函数的API27行为及权限边界，不能代替实际系统手势钩子验收。媒体音量必须为0，不碰静音/音量控件。默认中文，--all-locales不是本轮主验收。

## thinkos-cast

```
thinkos-cast/
  module.prop       Magisk 模块说明（id thinkos-cast）
  customize.sh      安装检查：只接受 32 位 ARM、API 27+，设权限
  service.sh        开机后拉起守护进程，退出后 3 秒重启
  sepolicy.rule     一条规则：wpa_supplicant 回复守护进程（permissive 的设备上只记日志，enforcing 下会被拒）
  src/wfdd.c        守护进程
  META-INF/         Magisk 模块包的标准安装器
build.sh            构建（armv7 模块包；--host 出本机测试版）
install.sh          构建并装到设备（人工执行）
test/               对着假的 wpa_supplicant 的主机测试
```

### 为什么需要它

手机和 Windows 靠 Wi-Fi Direct 探测响应里的 **WFD 信息元素**发现无线显示器。这个元素只能由 wpa_supplicant 带出去；应用层的 `WifiP2pManager.setWFDInfo()` 需要 signature 级的 `CONFIGURE_WIFI_DISPLAY`，ThinkOS 的 APK 没有平台签名，这台设备上的 `su` 也只放行 adb shell（uid 2000）。所以由一个 root 守护进程代发命令。

### 守护进程说什么、听谁的

在抽象 socket `@thinkos-wfd` 上收一行一条的文本命令（最长 256 字节，整行 2 秒内必须到齐，一次一个连接）：

| 请求 | 做什么 | 回答 |
|---|---|---|
| `HELLO` | 探活 | `OK thinkos-wfdd 1` |
| `SINK_ON <控制端口> <设备名>` | `SET wifi_display 1`、`WFD_SUBELEM_SET 0 <主 Sink，会话可用，端口，50 Mbps>`、`SET device_name`、`P2P_EXT_LISTEN 500 2500` | `OK`，或 `ERR bad-port` / `bad-name` / `wpa-down` / `wpa-silent` / `wpa-refused` |
| `SINK_OFF` | 撤销以上，并移除 P2P 组 | `OK` |
| `STATUS` | 当前状态 | `OK sink=on port=7236 wpa=up wfd=000600111c440032 name=…` |

只接受 root 与 ThinkOS 应用（`SO_PEERCRED` 查 uid，包名从 `/data/system/packages.list` 查，每次连接重新查）；别人得到 `ERR denied`，什么也不会发给 wpa_supplicant。名字只收 1–32 字节的可见字符，不含 `"`、`\`、`` ` ``、`$`。不是通用的 wpa 代理：没有任何办法让它发别的命令。

### 构建、测试、安装

```bash
system/build.sh                       # system/build/thinkos-cast-v1.0.zip
system/test/run.sh                    # 18 项，对着假的 wpa_supplicant
system/install.sh                     # 装到设备（人工）；装完重启
```

开发时不想装，可以不持久地跑同一份程序（重启后消失）：

```bash
adb push system/build/wfdd-armv7 /data/local/tmp/wfdd-test
adb shell 'su -c "nohup /data/local/tmp/wfdd-test --listen @thinkos-wfd >/dev/null 2>&1 &"'
adb shell 'su -c "kill $(pidof wfdd-test)"'     # 停（会先撤销 Sink）
```

日志在 logcat，标签 `ThinkOS-wfdd`。

### 已知限制

- SELinux enforcing 下没有验证（设备是 permissive）。
- 只在 Lineage 15.1（API 27）、Magisk 27、`wpa_supplicant` 的控制 socket 在 `/data/misc/wifi/sockets/p2p0` 的设备上试过。路径不同时用 `--wpa-socket`。
- 守护进程不处理 P2P 连接请求：确认框由 Android 的 Wi-Fi Direct 栈弹出。

## thinkos-immersive

```
thinkos-immersive/
  AndroidManifest.xml            Vector 模块声明（xposedmodule、作用范围 = 系统框架）
  assets/xposed_init             入口类
  res/values/xposed.xml          推荐作用范围：android（系统框架）
  src/.../BarsHook.java          临时栏与系统栏可见标志钩子
  src/.../BottomSwipeHook.java   原生左/底缘回调安装，底缘私有Home通知
  src/.../BackSwipeHook.java     固定Back、焦点复核、弹层/键盘边界
  src/.../GlobalMode.java        Shell/APK/权限/钩子的就绪条件
  stubs/                         Xposed API 的替身：只参与编译和主机测试，不进 APK（真类由设备上的 Vector 提供）
  test/                          主机测试：假的 PhoneWindowManager 与窗口
build-immersive.sh               构建（javac + d8 + aapt2 + 调试密钥签名，不用 Gradle）→ build/thinkos-immersive-1.2.apk
install-immersive.sh             装 APK 到设备（人工执行）
test/run-immersive.sh            主机测试，63项（兼容栏、通知、停止包唤醒、全局标志/焦点/弹层/键盘/权限）
```

### 为什么需要它

安卓在屏幕上沿或下沿（也包括横屏时的侧边）向里滑动时，会临时唤出状态栏和导航栏。这件事在系统框架里、在触摸到达任何窗口之前决定（`PhoneWindowManager.requestTransientBars`），应用无权拒绝：要免于它需要平台签名的 `STATUS_BAR` 权限。Device Owner、Lock Task（API 27 没有 `setLockTaskFeatures`）、沉浸式标志、`policy_control` 都做不到。操作栏恰好从这条边缘拉下来，每次都闪一下两条栏。

### 它做什么

钩住 `PhoneWindowManager.requestTransientBars`（顶、底、两侧的边缘滑动都经过这一个方法）。兼容模式只抑制ThinkOS自己的临时栏；协议2全局模式抑制所有应用的临时栏，并在updateSystemBarsLw计算前后保留全屏/隐藏导航/粘性沉浸式标志、去除临时显示标志，覆盖Android 8.1键盘出现时主动清掉HIDE_NAVIGATION的路径。全局模式要求完整隐藏策略、新APK元数据、悬浮窗权限以及全部必要钩子就绪；缺失或Back桥失败时恢复安卓出口。

目标方法已在这台设备的 `services.vdex` 里核对过：`PhoneWindowManager.requestTransientBars(WindowManagerPolicy$WindowState)V`、`getOwningPackage()`、字段 `mFocusedWindow` 都在。

### 构建、测试、安装

```bash
system/test/run-immersive.sh          # 主机测试，不需要设备
system/build-immersive.sh             # system/build/thinkos-immersive-1.2.apk（已签名）
system/install-immersive.sh           # 装到设备（人工）
```

安装脚本装好 APK 后在 Vector 里登记：守护进程在线时（第一次装）用它自带的命令行启用并把作用范围设为系统框架（`su -c '/data/adb/lspd/cli modules enable moe.kiwi.thinkos.immersive'`、`… scope set moe.kiwi.thinkos.immersive system/0'`）；不在线时（重装，见下面的已知限制）直接更正它数据库里记的路径。**更新模块请总是用这个脚本**，不要手动 `adb install -r`。重启设备后生效，Vector 的模块日志里有一行 `ThinkOS-immersive: hooked 1 requestTransientBars method(s)`（`su -c 'grep -rh ThinkOS-immersive /data/adb/lspd/log'`）。验证：`python3 tools/tests/immersive_device.py`（没装模块时顶部与底部的 4 项滑动检查会失败，那是基线；装好后 6/6）。撤销：`cli modules disable` 后重启，或 `adb uninstall moe.kiwi.thinkos.immersive`。

### 1.2 全局左缘和底缘（ADR-046）

从SystemGesturesPointerEventListener构造实例取得实际mCallbacks类，每类安装一次onSwipeFromLeft和onSwipeFromBottom。左缘只在system_server注入固定KEYCODE_BACK的DOWN/UP，排队后复核焦点；不存在接收应用注入请求的接口。ThinkOS基础窗口交给ThinkStage；原生弹窗和IME触摸区使用系统Back。外部全屏PanelHost由自己的边缘手势先收起，模块读取窗口记录避免再返回底下的应用。物理左缘的透明窄条只预留触摸，不注入、不执行外部动作，避免应用抽屉和系统Back同时响应。

底缘发送显式、无内容、前台优先的广播到不导出的EdgeReturnReceiver，使用system_server自己的用户。全局模式下包括ThinkOS、原生对话框和SystemUI，旧模式保留原来的排除条件。接收者关闭面板/维护弹窗，清空NavStack，到home角色首页，以singleTask/CLEAR_TOP复用MainActivity并移除其上方的权限Activity；冷进程也能启动。隐藏兼容设置nav.back_enabled/nav.up_enabled不能关闭协议2的必要出口。

源码、63项主机检查和1.2 APK构建已通过，见artifacts/app/APP-8/global/。**1.2及Shell未持久安装到真机，没有重启，系统回调联动尚未验证。**既有1.0的设备6/6只证明原先的ThinkOS前台临时栏抑制。

```bash
python3 tools/tests/global_home_device.py --output artifacts/app/APP-8/global/module-own-home
python3 tools/tests/global_back_device.py --native-gestures --output artifacts/app/APP-8/global/module-device
```

上面的 own-home 仅覆盖ThinkOS自己的真实输入。global_back_device.py不加载钩子，用临时app_process验证相同Back发射函数、普通UID权限边界及debug同UID私有Home入口；apps_foreign_panel_device.py --home-signal只临时设置窗口策略，验证叠层/视频，不证明ROM钩子已加载。旧up_gesture_device.py的launcher/原页面语义不再用作ADR-046验收。系统日志应包含requestTransientBars、updateSystemBarsLw、onSwipeFromBottom、onSwipeFromLeft四类hook；原生回调约500ms内的快速滑动有效，横屏仍按物理边缘。

### 已知限制

- **已在设备上验证**（2026-11-02，Lineage 15.1 / Vector 2.2）：启用并重启后钩子加载，`immersive_device.py` 6/6，面板下拉时没有任何栏；安卓自己的设置页里状态栏和返回 / 主页 / 最近三个按钮照旧。
- **Vector 2.2 在 Android 8.1 上，只要有一个启用的模块，它的守护进程就会在每次开机后一两秒崩掉**（`NoSuchMethodError: PackageInfo.getLongVersionCode()`，这个方法 API 28 才有；没有模块时不崩，与本模块无关，任何模块都一样）。钩子不受影响：system_server 在启动之初就拿到了模块列表，早于崩溃。后果是：开机后 Vector 管理器和 `cli` 都连不上（`Socket Failure: Connection refused`），也没人更新它记的模块路径。所以重装 APK 后（`/data/app` 里的目录名会变）必须更正路径，`install-immersive.sh` 在守护进程不在线时直接改它数据库里的 `apk_path`（先备份到设备的 `/data/local/tmp/modules_config.db.bak`）；手动 `adb install -r` 会让下次开机钩子找不到文件，验收时就是 2/6。要看模块是否登记：`su -c "sqlite3 /data/adb/lspd/config/modules_config.db 'select * from modules'"`。
- 1.0历史行为只抑制ThinkOS的临时栏；1.2协议2全局行为须人工更新并重启后另行验收，不能引用1.0记录宣称外部应用已覆盖。
- 目标是 Lineage 15.1（API 27）的 `PhoneWindowManager`；别的 Android 版本方法名或字段不同时模块不起作用，也不会出错。


## thinkos-certs（v2026.10.20）

HUA0BWUT已在2026-11-04由Agent按所有者本次明确授权安装并重启：add205张通过后，当前replace117张已生效（boot_count28），文件/AndroidCAStore与ISRG X1/X2、GTS R4原生HTTPS均核对通过。E0在Magisk实际关闭再开启CA模块，其他模块未更改。证据 `artifacts/certs/CERT-3/activation-20261104/`；下方人工步骤是原通用流程，不代表本机仍待安装。

源码中的117张证书来自固定curl/Mozilla包及对应NSS certdata.txt。排除所有服务器非TRUSTED_DELEGATOR及带distrust-after日期的根；当前包排除Izenpe.com。证书数据为MPL-2.0，生成/构建/安装检查代码为MIT；META-INF标准安装器为原样Magisk GPLv3模板，来源与许可证随包。

```bash
python3 -I tools/vendor_system_cacerts.py artifacts/certs/CERT-1/source/cacert-2026-10-20.crt \
  --sha256 796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe \
  --certdata artifacts/certs/CERT-1/source/certdata.txt --retrieved-on 2026-11-04 --as-of 2026-11-04 --check
system/test/run-certs.sh
system/build-certs.sh --mode add
system/build-certs.sh --mode replace
```

默认replace是所有者决定的终态：目录放空的.replace标记，平台最终只见117张。add仅供E1验证：保留原135张，再增加70张，共205张。原库有两处相同主题、不同DER的文件名冲突（0d69c7e1、d16a5865）；add包把新证书安排在.1，旧.0保留，否则直接覆盖只会剩203张。install-layout.json记录包内映射，certs-source.json记录规范生成结果。add安装前以baseline-sha256.txt逐文件核对原ROM库，不匹配就中止；不能把add包装到已经变化的库上。replace不依赖旧库，输出117张。两种ZIP固定排序、时间和权限，可复现；没有开机脚本、守护进程或SELinux规则。

以下是**人工**步骤，Agent未执行：先按docs/41 §5的E0演练Magisk撤销入口；可先运行system/install-certs.sh HUA0BWUT --mode add，人工重启后运行python3 -I tools/tests/certs_device.py --expect add --output artifacts/certs/CERT-3/E1；再以replace重复E2。脚本本身不重启。设备核验是只读的，检查文件DER/PEM摘要、权限/上下文及debug trust_roots返回的AndroidCAStore数量和排序指纹摘要。仅baseline通过不代表E0撤销演练、E1/E2或公网握手已经通过。

批量：E1/E2通过后再制作WEL-5参考机镜像。既有设备每台由人工安装replace并重启，再用certs_device.py --serial <序列号> --expect replace --output <该设备独立目录>核对；不直接写/system或/data/adb/modules。

刷新：先保留上一版certs-source.json，人工取得新版curl包和匹配certdata.txt并核对来源/哈希；运行生成工具（新的--sha256、--retrieved-on和--diff <旧清单>），审阅增加/移除/一年内到期和distrust-after报告；同步module.prop版本与versionCode，主机测试与单机E1/E2后才发布。MOD-1完成前人工装，之后走签名OTA。目录契约与root更新器均固定同一组七个官方模块，见上方thinkos-updater一节；root验签/事务已有源码和host证据，但未持久部署，不能把契约校验或host结果当作真实模块OTA通过。


CERT-3联网检查：人工激活add/replace并重启后，先运行certs_device.py，再运行python3 -I tools/tests/certs_tls_device.py --expect add（或replace）--output <新证据目录>。后者会正常安装测试APK、保护/恢复十份应用文件，以设备nc预检后执行应用UID的系统原生TLS；不激活模块、不重启、不播放声音。baseline已实测ISRG X1通过，ISRG X2/GTS R4缺信任根；不能把它当作模块通过。后续add/replace检查要求三站点实际验证到对应根，交叉签名到旧根不算。站点、根指纹与CCADB来源已固定在tools/tests/fixtures/certs-tls/sites.json。
