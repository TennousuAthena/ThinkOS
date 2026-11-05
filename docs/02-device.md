# 02 · 设备事实与系统状态

> 本文件记录**已实测验证**的设备事实。开发时以本文为准，不要凭经验假设。

前次普通引导检查点（2026-11-05）：0.1.28/1028已实际安装，主包78b483f5cfd703b39494cab1c779d9f92d87d232a334546722dfc3d15c1b365f，匹配test8be36d16e9b76c74a409da30511118bdb10d3a726b13fdfae5b21407cb9489b7。原9文件/更新树/首页/DO/admin/HOME/LockTask及五流0保持，同boot29无重启；临时wfdd仍运行，新包投屏页Miracast显示开。持久部署和真实手机/Windows配对仍待，见`artifacts/cast/CAST-1/0.1.28/qualification.json`。

当前交付检查点（2026-11-05）：0.1.30/1030已实际安装，主包bdd8fd7cec1657eb2f280d0eb832f17f75e9f8f02280cc5953381a07cfdf057e；匹配test7486c9c626a830c45f781c058a62992f7878fe88d130f98923dc2a584aab0789。原9文件/完整真实更新树/首页/管理/五流0保持，同boot29；临时wfdd继续运行，中文Miracast显示开。没有持久CAST模块或重启，真实手机/Windows配对仍待。见`artifacts/cast/CAST-1/0.1.30/qualification.json`。

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
| 供电 | **无电池、无 RTC**，常插电；圆口电源线，没有电源键，关机靠拔线。**软件读不到“电源线被拔”**（PMIC 的 DC 输入没接线，没有断电中断，Lineage 里的“电池”是假的），只能读系统主供电轨 VPH_PWR（`/sys/devices/soc/qpnp-vadc-6/vph_pwr`，通电时约 3.97–4.05 V）。拔线后它怎么掉、板子靠电容撑多久：**未测**，见 `40-power-loss.md` |
| USB | USB-C（藏在屏幕边缘橡胶塞下），经 USB 2.0 hub 连接电脑 |

## 2. 系统

| 项目 | 值 |
|---|---|
| 系统 | LineageOS 15.1（非官方 `starfire` 构建，20240531） |
| Android | 8.1.0 / API 27 |
| 构建类型 | `userdebug`，`ro.debuggable=1` |
| ABI | **仅 armeabi-v7a**（2026-11-03读取ro.product.cpu.abilist为armeabi-v7a；无arm64） |
| Google 服务 | **无**（无 GMS / Play / FCM） |
| 系统根证书 | **当前117张，2026-11-04已由Agent获授权安装并重启验证。** `thinkos-certs v2026.10.20` replace覆盖生效，系统文件与应用AndroidCAStore指纹一致；ISRG X1/X2、GTS R4三个官方HTTPS测试到预期根，boot_count28。先前ROM旧135张库存与add阶段205张均留证；未写系统分区。证据`artifacts/certs/CERT-3/activation-20261104/`，说明见`41-ca-trust.md` |
| ro.build.display.id | `lineage_starfire-userdebug 8.1.0 OPM7.181205.001 ccc9c75b83 test-keys` |
| ro.lineage.version | `15.1-20240531-UNOFFICIAL-starfire`；装机版本校验用它 |
| ro.build.fingerprint | `Lenovo/CD-18781Y/CD-18781Y:8.1.0/OPM1.171019.026/penghu06021417:user/release-keys`；仍是联想官方指纹，不能据此否定Lineage |

### 系统级状态（已配置）

最新安装（2026-11-05 22:00 起，HKT）：HUA0BWUT 装的是当前工作树的 debug 构建，版本仍是 0.1.30/1030（DEBUGGABLE、debug 证书，没有正式钥匙，所以 OTA 不会发请求），含自动选源、签名镜像列表、默认国内源 `update.sources`（CNB）和 debug 专用的 `fetch_probe` 命令。设备上更新组件回归 16 项通过，证据 `artifacts/update/REL-1/ota-device-final-20261105/`；渠道实测 CNB 724 kB/s、GitHub 12 kB/s，证据 `artifacts/update/REL-0/part1-20261105/`。音乐音量是 11（应用在 21:34 到 21:38 之间被人调过），测试期间临时静音后已恢复。下面几段是更早的检查点，版本号以此为准。

当前应用检查点（2026-11-05）：**0.1.27/1027已实际安装**。主包SHA-256 `f569cf5d4834d578dfd2351c569136186e396e2bd46a21e89af548881148a107`，匹配测试包 `136a7fe679f3576cff2c2f4be9165f690d9b78f464b2fea3aa9def182f26c90a`；同主进程、boot_count29，本轮未重启。`/data/local/tmp` 临时root wfdd已由生产入口采用，等待页Miracast显示“开”；脚本化Source的真实RTSP/RTP/MediaEngine接收、1280×720灰帧、静音AAC轨道及TEARDOWN释放共11项通过，截图已目视。仅Miracast开且DLNA/AirPlay关时，同一等待页随本次自有临时wfdd停止/重启自动显示缺少/开、无需重进，6项通过。接收专项原9文件字节保持，刷新专项三开关语义恢复且其他8文件字节保持，原UI与HOME/DO/admin/LockTask、五流0保持，无raw恢复。主机1098项JVM零失败/错误（1旧skip）、Python341、lint/双包构建、JNI/插件/字符串通过。临时进程不等于持久模块安装，未重启或验真实手机/Windows发现配对与画面，完整CAST-1/全系统手势/性能仍待。证据 `artifacts/cast/CAST-1/0.1.27/qualification.json`。

前次应用检查点（2026-11-05）：**0.1.26/1026已实际安装**。主包SHA-256 `62adbd54cffe966a6a2a74d68f226a6186d8aa2a2d0b531c1a72a72180c3f088`，匹配测试包 `6a6f58a25e9a845bd46a82f2407f4a372b8c44e94519788685269296f3f0231b`。首次主包已成功，但两包间短暂空焦点让守卫停止；只续装测试包后，原安装前9文件字节、完整更新树、原home.main/digital、zh-CN/方向10、DO/admin/HOME/LockTask与五流0均保持。设备本次恢复连接时已为boot_count29，Agent本轮没有重启。源码含原生缺Miracast模块准备/触屏确认与更新记录恢复；无updater分支中文横竖窗口、启用原子拒绝、取消/Back与普通更新共6项实机通过、原设置语义/UI/管理/静音恢复；认证计划/安装确认、系统模块、全系统新手势或完整WEL仍未完成。证据 `artifacts/update/MOD-2/0.1.26/installation.json`。独立REG调试双包已构建但未安装；性能继续后置。

前次应用检查点（2026-11-05）：**0.1.22/1022已实际安装**。主包SHA-256 `b36f5924d77c4be739ed1903dbbbcdf6c0b8be2f97dfbc14dbdd35d0e83c0b76`，当前生命周期测试包 `8ff7bed9705b3d0439982d578d1f8d8182748b021b631f749b2aa6157d68554f`；完整主流程最初配套测试包9eac6a64…另存。中文普通引导30交互/2原生保存恢复/5实际成功勾Surface通过；长按新旧两句重叠与维护标题孤字已修。真实失焦/尺寸变化旧触摸尾部6原生/4外层通过。1035项JVM（1旧skip）、Python214、插件验证、lint/双包构建通过，仅armv7。原进度/设置按语义恢复、原UI/DO/HOME/LockTask与静音恢复，矩阵原9文件字节未变；owned窗口/cache/视频清理，boot_count28。完整WEL的OWN-10/特权/新装/闲置及全系统新版钩子仍待，性能后置。证据 `artifacts/welcome/WEL-2/0.1.22/qualification.json`；15秒预览无音轨。

前次应用检查点（2026-11-05）：**0.1.21/1021已实际安装**，主包SHA-256 `3b375fb464fe4140e585a30c25dcb7f0b18bfdc7562f6edc22e97520f8f776c0`，当前测试包 `00a407ced8dd89caa92aa2166f57f4e0df829d290a60dbb28db0543f15be18a1`。修复真实手势成功勾在同步清空中首绘制前消失：OS仅暂存视觉交接，首绘制后沿用120ms，旧pointer结束才交接；Home仍立即退出，尺寸变化取消旧触摸尾。1035项JVM零失败（1旧skip）、Python214、lint/主包与测试包构建通过；首次投屏首画面3秒失败保留，原样定向及完整重跑通过，原因未确定。主包和测试包分别实际安装，原9文件/UI/管理/引导done1/静音保留，boot_count28。独立真实Stage4项与外层4项通过，Surface已目视确认长按与横滑成功勾；抬手交接/重复UP/局部Home同次退出通过，原数据/UI/静音恢复。完整当前包引导、尺寸/失焦旧触摸矩阵仍待重验。全系统新版钩子未加载，无模块安装/重启，性能后置。证据 `artifacts/welcome/WEL-2/0.1.21/qualification.json`。

前次应用检查点（2026-11-05）：**0.1.20/1020已实际安装**，主包SHA-256 `8b664725b51368031ff5cd83e1ce71c76167294b187dc32b9911e19c56658217`，当前配套测试包（probe2） `98fd4bbb7a03e53de5c5027a04b79180e1d34a074f33456199e5b9f757275ffe`；初次测试包b327b9b8…及失败另存。正式引导原生Hint新增六手势自动方向箭头和长按圈，真实触摸让位、关闭系统动画静态、失焦/隐藏/迟到回调/detach取消等16项原生检查、8项外层检查通过；八段无声Surface视频中7项自动分析、上下换样式由root直接逐帧复核双向，原自动失败保留。中文横竖屏目视通过；15秒预览无音轨。1035项JVM零失败（1旧skip）、214项Python、debug lint/构建与测试包构建通过，仅armv7；未变的Lua/JNI260、插件与11语字符串沿同源前轮证据。原9文件、界面/管理/动画设置/引导done1与音量0恢复，owned窗口/cache/设备视频清理，boot_count28。没有重开已完成引导。完整当前包引导未重验；发现真实成功打勾同栈被清，021私有提案尚未合入。**全系统新手势仍未加载**、无模块安装或重启；性能后置。见 `artifacts/welcome/WEL-2/0.1.20/qualification.json`。

前次应用检查点（2026-11-05）：**0.1.18/1018已实际安装**，主包SHA-256 `c9122e52f70d3877116b2df4da05a936419d0c71ab72f4d92cf5f85bfe87686f`，测试包 `29f2beff551904171e0dcb9af05cc61cff0e98d68ad88fa667c9e05a59552696`。修复中文引导将zh-Hans-CN/zh-Hant标签误当作换语言而跳安卓的问题；3标签实际Lua回归通过，当前包资产匹配，不冒称整套引导同包重验。原生地区页在IME收放、旋转和正反防烧屏位移下23项（含恢复）实机通过：侧导航栏78px时返回/搜索边界均为78px，选择CN/所有地区/取消正常；root目视中文截图通过。1030项JVM零失败（1旧skip）、debug lint/构建及测试包构建通过，仅armv7；未变的Python214、Lua/JNI260、插件与字符串检查沿用017同轮证据。016大面积遮挡与017位移后1px失败均保留，018修正绘制前缓存安全区域同步，不每帧查询窗口。原文件/UI/DO/HOME/LockTask/引导done1与音量0恢复，临时drift/helper清理，boot_count28。未装系统模块、未重启，**全局手势/系统栏抑制仍未生效**；F2仍为私有未整合提案，性能后置。证据 `artifacts/app/APP-9/0.1.18/qualification.json`。

前次应用检查点（2026-11-05）：**0.1.16/1016已实际安装**，主包SHA-256 `3a75da309f3ed9279a1805cd68314e0acbcc4dd8632563eed8142df4cafc2c7c`，测试包 `6e89ff996898708469967e29575736189e577172de0528c94589404788c9efb1`。新增“模式”设置、HH:mm/5分钟原生编辑和控制栏红移/睡眠；中文时间12、控制栏15项（均含恢复）实机通过，横竖屏冒号对齐已复验。实际零采样PCM播放→睡眠暂停→唤醒仍暂停→明确play再前进通过，12项含清理；44.1kHz/单声道、五路音量0，未声称压缩格式重验或非零音量硬件恢复。1025项JVM零失败（1旧skip）、214项Python、259项Lua/JNI、debug lint/构建通过，仅armv7；共享Go/常规system主机已按同轮源码通过。原设置/身份/引导done1/UI/DO/HOME/LockTask/静音均恢复，boot_count28；本轮listener/reverse/helper已清理。详见 `artifacts/mode/MODE-2/0.1.16/qualification.json`。睡眠仍是NIGHT最低亮度逻辑状态，黑屏/偷看与物理唤醒没有执行；全局新钩子仍未加载、旧IME栏遮挡未解决。OTA6独立复核与F1/F3源码修复已验，F2提案尚未整合；没有装系统模块或重启。

前次应用检查点（2026-11-05）：**0.1.14/1014已实际安装**，主包SHA-256 `b108d710b4d40de3fa24e85ce77fba52a2d2a8597378bd21ce6be9eada6bd28c`，测试包 `f8c6f77bbd0ee8047d21c895f3dcee990e697b3d53ae645b0eb72d0af4b93d36`。中文六类设置图标、横竖屏和浅/深/红移三配色、红移标签已实机通过并目视核对；地区搜索/选择CN、所有地区空值、取消7项通过。947项JVM零失败（1旧skip）、199项Python、253项Lua/JNI、共享Go、lint/构建通过；仅armv7。后IME横屏地区页仍有安卓导航栏遮挡，不能算完整排版通过；新版系统钩子仍未加载。原设置/界面/身份/DO/HOME/LockTask/五路静音恢复，boot_count仍28。详见 `artifacts/app/APP-9/0.1.14/qualification.json`；旧失败保留。BT0仅主机模块构建/7项及常规7+18项通过，未装未开蓝牙。

前次应用检查点（2026-11-05）：**0.1.13/1013已实际安装**，主包SHA-256 `3b86c9b2a1ecb5bcae8b314aaf3a1dfb1540bcb66579720bb672d09c57bbc80e`，测试包 `37b5684e1edcc9dd69cf60422df8660f4934ccb3adbb92bd54ee8c65ad72c3da`。939项JVM零失败（1旧跳过）、194项Python、252项Lua/JNI、debug lint/构建通过；空列表仅“更多”时的提示已修复。真机本地目录18项完整通过；同包系统DNS/命名HTTPS 200/100条、生产Lua缓存98台，实际原生点选MP3（44.1kHz）、AAC与HLS（48kHz）分别解码且进度前进，五路音量始终0。三格式为两个报告的分层证据，旧失败未抹绿；播放/四原文件/身份/原界面与DO/admin/HOME/LockTask均恢复，未重置引导完成进度。证据 `artifacts/app/APP-9/0.1.13/qualification.json`。全局新系统钩子仍未加载。

前次目录检查点（2026-11-05，0.1.12证据仅对当时包）：**0.1.12/1012已实际安装**，主包SHA-256 `202a2de06131ff2313aaa6c4f003dfba75f72db9852daec21cede7840efd990a`，测试包 `f9f9307340f571b46557abf7360c62a6ab1e41f5d8aab0eb4e88fee640f4341b`。939项JVM、194项Python、251项Lua/JNI通过；新增公开API10系统DNS发现，不放宽HTTPS。真机命名节点默认HTTPS取得200/100条，生产Lua缓存98台并显示；本地99→198分页、三tag分类/Back通过，国家空列表仅More时缺少空提示待修。原设置/radio/welcome/身份、界面、DO/admin/HOME/LockTask与静音均已恢复；引导完成值保持1，没有重置用户进度。恢复启动时目录未发布的旧失败保留，独立恢复已核对。证据 `artifacts/app/APP-9/0.1.12/qualification.json`。

前次引导检查点（2026-11-05，0.1.11证据仅对当时包）：**0.1.11/1011已实际安装**，API10；主包SHA-256 `dc3f8e7f9dba1e3656aae15fe5d199f519925cca0672502b85da872d603bbd92`，测试包 `56a22543de0ba06b2b7c200603de786a79e3b6dde5507f470de91396476e6d2b`。930项JVM零失败（1旧跳过）、194项Python、242项Lua/JNI、debug lint/构建和69项immersive主机检查通过。修复引导Home练习记账、同进程“完成设置”续接、迟到点击和冷catalog显式Home；系统栏故障回退修复只有源码/主机验收，新系统钩子未加载。安装后原五文件、clock.face/solar、方向与holds、DO/admin/HOME/LockTask均恢复，五音频流0，boot_count仍28；8项当前包真实页面检查已通过（含同进程Home续接），原清理失败保留；另行受控恢复四原文件与原UI/管理后，已显式重新打开正式引导首屏供体验。全系统新钩子仍未加载。证据 `artifacts/welcome/WEL-2/0.1.11/installation.json` 和 `qualification.json`。

前次应用功能检查点（2026-11-04，0.1.10证据仅对应当时包）：0.1.10/1010、API10已实际安装，debug主包SHA-256为`2784f3ceb5067fd9e15e1aaca456f2522eceaab201a9043deebd80c0603643f9`，当前HTTP诊断测试包为`b68ad19ebf050a3517b33c2f1584c685fd4a4c970c8c150b6d39872bedf19433`（只更新测试APK；此前网络门禁7项使用22c00efb…测试包），均与设备摘要一致，主包仅armv7。927项JVM零失败（1旧跳过）、184项Python、239项Lua/JNI及debug/release lint和构建通过。电台目录/分类/健康/离线缓存及通用public_only已实现，目录页面与公网台播放继续待验；真实API27网络门禁7项通过，包含IPv6、受限请求零TCP和5次普通LAN对照，未播放或解码声音。原clock.face/solar、方向10、zh-CN、全部holds、safeMode=false与五份设置/welcome/投屏身份/管理记录已恢复；DO、自身admin、默认HOME和LockTask保留，音量1–5全0，boot_count28、开机和系统声音0。证据`artifacts/app/APP-9/0.1.10/`。CA117张仍生效；更新器和外置guard未持久安装。前一0.1.9的中文七目标/无更新器与临时keys0真实库存横竖屏、管理取消/Back已分层验收并清理，见`artifacts/update/MOD-2/0.1.9/qualification.json`；不等于模块升级/重启/回滚通过。0.1.8签名插件安装/隔离/打开/卸载16项仍见`artifacts/registry/REG-2/0.1.8/qualification.json`，十分钟试用、恶意样本和16数量上限待验。引导45项与Hub入口11项证据仍属0.1.7，分别见`artifacts/welcome/WEL-2/0.1.7/qualification.json`和`artifacts/registry/REG-2/0.1.7/entry-3/`，不冒称当前包复验。性能后置。

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
| `thinkos-certs`（2026-11-04已安装并重启生效，`v2026.10.20`、replace模式） | 系统CA已整体替换为Mozilla/curl的117张；文件摘要、权限/上下文、应用AndroidCAStore和ISRG X1/X2、GTS R4原生HTTPS均通过。原135张不再是生效库。证据 `artifacts/certs/CERT-3/activation-20261104/` |
| `thinkos-power`（**源码在本仓库 `system/`，尚未装进设备，阈值待拔线实验**） | 断电保护：盯系统主供电轨 VPH_PWR，拔电源线掉压时刷盘并紧急只读；见 `docs/40-power-loss.md` |
| `thinkos-cast`（**未持久安装；当前会话临时运行**） | 0.1.27已采用`/data/local/tmp`的root守护进程；11项脚本化接收检查通过，未重启/真实发送端验收。证据`artifacts/cast/CAST-1/0.1.27/`；持久装法见`system/README.md`（OWN-28） |
| `thinkos-immersive`（Vector 模块 APK `moe.kiwi.thinkos.immersive`，**2026-11-02 已装并启用，作用范围 = 系统框架**；源码在本仓库 `system/`） | ThinkOS 在前台时，边缘滑动不再唤出安卓的状态栏和导航栏（ADR-037）；装法与撤销见 `system/README.md`。**注意**：Vector 2.2 在这台设备（API 27）上，只要有启用的模块，守护进程每次开机后一两秒就会崩，管理器和 `cli` 之后连不上；已加载的钩子照常工作（`10-known-issues.md` §13） |

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
