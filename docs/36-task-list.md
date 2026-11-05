# 36 · 未完成工作：任务清单与验收计划

> 2026-11-03。写给接手实现的 Agent。范围：到今天为止讨论定下、代码里还没有的东西。本文只派活和定验收，设计细节在各任务写明的文档里。
> 现状核对于 2026-11-03 01:30（HKT）：`update/`、`plugins/welcome`、`plugins/hub`、`plugins/apps`、`plugins/radio`、`system/thinkos-updater`、`tos.update`、`tos.plugins`、`tos.apps`、`onboarding` 角色、`thinkos.fairplay` 开关都还不存在。
> 写这份清单时没有跑构建和设备测试。现状来自读代码、读已有截图和脚本统计，截图可能比工作区旧。

> 本轮执行约束（2026-11-03，所有者）：无人值守，不提问；暂不实现 Server 中的内容。需要人工决定或亲手操作的条目保留待办，其余开发、真机调试继续。
> 执行优先级更新（2026-11-03，所有者）：先实现完整功能并做真机功能验收，再统一解决性能问题。性能门槛继续保留待验，不作为当前功能开发的阻断项。
> 真机与静音更新（2026-11-04，所有者）：继续无人值守，不提问；实现后要安装新包并做真机功能测试，不能长期以曾经体验预览为由避开设备。所有者睡觉期间不允许发出声音；功能优先、性能后置与Server暂不实现继续有效。任务按最新文档实施。
> 排版优先级更新（2026-11-03，所有者）：主要检查和处理中文；英文、RTL及更多语言不纳入本轮排版的主要考虑。已有翻译表保持一致，但不再以扩展语言排版问题阻断中文功能收口。
> 全局手势更新（2026-11-03，所有者）：整个系统屏蔽安卓通知/快捷设置与导航栏；左缘右滑返回、底缘上滑回ThinkOS首页、顶缘下拉ThinkOS控制栏。取代DEC-8的返回目标与外部系统栏保留方案，具体实现和验收见`docs/38-global-gestures.md`（ADR-046）。
> CA实施授权更新（2026-11-04，所有者）：明确要求Agent完成CA模块安装、设备重启及系统信任库/HTTPS验证，不需要所有者操作屏幕。此授权覆盖原人工边界中本次CA任务所需的安装和重启；保持音量0，不扩展为安装其他系统模块或更改Device Owner。
> 本轮目标更新（2026-11-05，所有者）：完成任务清单开发调试。新增的 SET-07、CAST、MODE、BT（§11D 到 §11F，SET-07 在 SET 组）按 §0.7 做。OWN-25 到 27 暂按推荐值执行（DEC-9 到 DEC-11）；OWN-28 没有授权，装模块和重启仍归人工。
> 架构审计更新（2026-11-05）：OTA、MOD、REG 的代码基本完成，真机分段测过，但从来没有用真实钥匙和真实地址跑通过。新增 OTA-5、OTA-6、REG-2b，补 MOD-1、OTA-4、REG-1 的条目，插件目录签名方式暂按 DEC-12；证书迁移（OWN-6）要在 WEL-5 取金镜像之前完成。顺序见 §0.7。
> 首发交付更新（2026-11-05，所有者）：要快速交付一版固件，重点是 OTA（APK 和系统模块自动验证、自动更新）；分发渠道是 GitHub release 加国内源 CNB（`https://cnb.cool/kiwimoe/ThinkOS`）。新增 §11G REL，把审计缺口串成一条发布路径；渠道设计见 `docs/34` §4.10。首发这一版是“装上去”的（现有机器是 debug 签名，不能被正式签名的包覆盖更新），从首发起才靠 OTA。

> 当前交付检查点（2026-11-05）：**0.1.30/1030已实际安装**，修复同运行期拒绝文件读取权限后播放器audio/video顺序扫描反复弹窗，7项真实broker回归；1121/1111项debug/release JVM零失败/错误（各1旧skip）、完整Python385、双lint/主包及test构建通过，默认包不含WebRTC。安装原9文件/完整真实更新树/首页/管理/五流0与临时wfdd保持；新包中文Miracast仍开。REG独立026真实602秒/权限/媒体/冷恢复组合补验已保留所有失败并恢复原900，非完整REG2b；真实030拒绝UI未取得有效拒绝操作见证，不计通过；旧失败与未知权限变化保留，临时helper已清。见`artifacts/media/storage-denial/0.1.30/qualification.json`、`artifacts/cast/CAST-1/0.1.30/qualification.json`。

> 前次普通引导交付检查点（2026-11-05）：**0.1.28/1028已实际安装**，引导地区单位保留与时区失败回退源码已打包，1098项JVM零失败/错误（1旧skip）、268原生PASS组、Python353及构建/lint/插件/字符串通过。安装原9文件/完整真实更新树/首页/管理/静音与临时wfdd保持；新包中文投屏页Miracast仍显示“开”，无持久模块/重启或真实手机配对。原完成引导未重开，新分支UI仍待；独立026夹具已完成两段真实600秒及权限/媒体/普通冷Main恢复的分段组合验收；v1缺启动stdout、原失败均保留，恢复补测已保存预哈希和真实新进程stdout。正式返回时间900语义还原，原数据/管理/五流0保持，完整REG-2b仍待。证据 `artifacts/welcome/WEL-2/0.1.28/qualification.json`、`artifacts/cast/CAST-1/0.1.28/qualification.json`。

> 前次投屏交付检查点（2026-11-05）：**0.1.27/1027已实际安装**，主包及匹配测试包摘要见 `artifacts/cast/CAST-1/0.1.27/qualification.json`。同主进程、boot_count29；临时非持久wfdd由生产入口采用，等待页Miracast显示“开”，脚本化Source的RTSP/RTP/MediaEngine灰帧、静音AAC轨道及TEARDOWN释放共11项通过；仅Miracast开启的原页在临时wfdd停止/重启后自动显示缺少/开，6项通过。接收专项原9文件字节、刷新专项三开关语义/其他8文件字节及原UI/管理/五流0保持，无重启或raw恢复。主机1098项JVM零失败/错误（1旧skip）、Python341、lint/双包构建及JNI/插件/字符串通过。持久安装/重启、真实手机/Windows发现配对与画面、完整CAST-1与全系统手势仍待，性能后置。

> 0.1.26交付检查点（2026-11-05）：**0.1.26/1026已实际安装**，主包摘要与固定产物一致，匹配测试包含只读CALL能力探针。首次主包成功后短暂空焦点导致外层停止；已核实状态并只续装测试包，原9文件逐字节、完整真实更新树、原首页/管理与五流0全部保持。上线时已读到boot_count29，本轮未重启。主机1089项JVM零失败/错误（1旧skip）、新增能力策略4项及测试包编译、后续Python336项通过；独立regFixture包已构建但未安装。见 `artifacts/update/MOD-2/0.1.26/qualification.json`；无updater中文窗口6项已实机通过；认证计划/安装确认、损坏记录恢复、模块安装和全系统手势仍待。

> 0.1.22引导检查点（2026-11-05）：**0.1.22/1022已装机**。中文普通引导从问候到完成30项实际交互与2项原生保存/恢复通过，Home真实退出后同进程续长按；五种非Home成功勾均由真实Surface确认。已修成功反馈期间新旧提示重叠及维护说明32sp孤字换行；实际失焦/尺寸变化两项旧UP边界纳入6项原生/4项外层检查并通过。原进度/设置按语义恢复、UI/管理/静音恢复，独立矩阵原9文件字节未变。完整WEL特权/新装/闲置门槛及全系统新版钩子仍待；证据 `artifacts/welcome/WEL-2/0.1.22/qualification.json`。上方11月3日“尚不存在”为历史快照。

## 0. 接手须知

### 0.1 怎么用

1. 先读 `AGENTS.md`、本节、§1，再读任务写明的文档。
2. 按 §1.1 的顺序做，一次一个任务。做完勾选，并在任务下面加一行：`结果：日期，通过或失败，证据路径`。
3. 本文和别的文档冲突时以本文为准，并在做这个任务时把那份文档改对。`AGENTS.md` 的硬约束高于本文。
4. `docs/08-decisions.md` 里已有结论的不要重新讨论。某条做不到就停下，写明原因和证据，不要悄悄降标准。
5. 行号是今天的。别的 worker 在同一棵树上工作，动手前重新搜索。
6. 标“人工”的步骤你不做：把命令和检查方法写好，交给所有者。

### 0.2 不能做的事

| 类别 | 不能做 | 遇到时 |
|---|---|---|
| 设备系统 | `adb root`、`adb disable-verity`、`adb remount`、直接写 `/system`、刷机、把模块装进 `/data/adb/modules`、重启设备、设置或清除 Device Owner | 写好步骤，交给人工 |
| 对外动作 | 提交、推送、开 PR、建 GitHub 仓库、改仓库可见性、发 Release | 每次都要所有者明确同意 |
| 钥匙 | 正式证书、通道钥匙、系统钥匙、仓库钥匙和它们的口令进仓库、CI 或日志 | 只写生成脚本，由所有者运行 |
| 架构 | 应用里加 `su`；WebView、Compose、GMS；只有 arm64 的依赖；设备上跑大模型；设备端 Git / JGit；给插件开私有通道；Lua 开放 `io`、`os`、`package`、`debug`；依赖 Home Assistant；把 Server 在线当前提；引入第二个播放器 | 换做法 |
| Mac 界面 | 点击或用脚本操作 Mac 的窗口 | 请所有者点，事后读日志 |
| 性能测试 | 没告诉所有者就跑；功能还没在设备上看到效果就跑 | 先确认功能，再告知，再跑 |

### 0.3 工作规矩

- **同一棵树上有别人**：动手前 `git status`，改文件前重读。不用 `git stash`、`git checkout --`、`git reset --hard`、`git clean`。不还原别人的改动。
- **设备只有一台**，序列号 `HUA0BWUT`。先 `export ADB_MDNS=0`。跑设备脚本前看前台：`adb shell dumpsys activity activities | grep mResumedActivity`。前台不是 `moe.kiwi.thinkos` 就可能有人在用，等一等或问所有者。设备重启后 adb 要 2–3 分钟才恢复。
- **网络**：Mac 走代理，它能连不代表设备能连。外部地址用 `adb shell 'nc -w 5 -z 主机 443'` 判断。
- **契约**：先改 `shared/schema/`，再跑 `python3 shared/gen/gen_kotlin.py && python3 shared/gen/gen_go.py`，不手写生成文件。新增词汇在 `shared/schema/plugin/layouts.json` 的 `x-thinkos-api` 登记。
- **API 号**：接手时为 8。本轮 UI-03 的设置 `warn` / `placeholder` 首先使用 9，当前共享契约已收口为 API 10（设备设置、更新及本轮公开词汇）；后续沿用 10，不再另升。
- **系统字符串**：来源是 `shared/i18n/system/*.json`，`strings.xml` 由 `python3 tools/gen_android_strings.py` 生成。Schema 里的 `@setting.cast.enabled` 对应键 `setting_cast_enabled`。
- **11 种语言**：`zh-Hans`、`zh-Hant`、`en`、`es`、`fr`、`pt`、`ru`、`ar`、`hi`、`bn`、`ur`。增删或改写一个键就要改全部 11 份，插件表和系统表都一样。本文给的是 `zh-Hans`，其它语言按同样的意思和长度重译，不保留被删掉的半句。
- **可配置项**走设置 schema（ADR-017），不写死，不另建 SharedPreferences。
- **时钟优先**（ADR-029）：新动画、每帧工作和主线程工作，先判断会不会拖慢时钟。Lua 永远不在主线程。
- **界面文字和视觉**一律按 §3.1、§3.2，包括本清单新增的所有界面。

### 0.4 每个任务都要过的检查

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; export ADB_MDNS=0
(cd android && ./gradlew --offline testDebugUnitTest lintDebug assembleDebug)
python3 -I -m unittest discover -s tools/tests
android/app/src/testNative/run.sh
python3 tools/package_plugins.py --validate-only
python3 tools/gen_android_strings.py --check
(cd server && go test ./...)     # 动了 shared/ 时
system/test/run.sh               # 动了 system/ 时
```

动了界面或运行时的任务，再过 `AGENTS.md` 的五条：

1. `assembleDebug` 通过。
2. `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` 成功。
3. 断网、无 Server，冷启动 < 3 秒（`tools/tests/pre_m1_cold_start.py`）。
4. 待机 10 分钟 janky < 5%（`tools/bench.sh 600`，属于性能测试，先告知）。
5. `unzip -l app-debug.apk | grep lib/` 只有 `lib/armeabi-v7a/`。

碰到时钟路径（`ClockView`、`ThinkStage`、`FrameGovernor`、主线程新任务）的，加跑 `tools/tests/clock_accuracy_device.py`，先告知。判据：连续 60 秒换秒 p95 ≤ 40 ms、最大 ≤ 80 ms、不漏秒。

### 0.5 证据和汇报

- 证据放 `artifacts/<组名小写>/<任务 ID>/`。截图只留能说明结论的，日志留原文。
- 汇报四件事：做了什么、怎么验证的、哪些没验证、要所有者做什么。测试失败贴输出。不写“应该没问题”。

### 0.6 决定

**已经定下，直接照做**（所有者可以推翻）：

| ID | 决定 | 影响 |
|---|---|---|
| DEC-1 | §3.1 的界面规则是用户 2026-11-03 定的硬规则：“严格避免”，“有则改之，无则加勉” | UI，以及之后所有新界面 |
| DEC-2 | 变暗和休眠时间放回设备，用档位选 | SET-03 |
| DEC-3 | 横屏两栏要做，排在设置组最后 | SET-06 |
| DEC-4 | 手势练习进第一个公开版；Wi-Fi 内嵌列表放到 WEL-2 之后；文字大小不进引导，除非 WEL-0 看下来界面撑得住 | WEL |
| DEC-5 | 安卓应用页用图标网格，实现原生 `grid`，只列有桌面入口的安卓 App（用户 2026-11-03 决定，取代原来的“用 `list`，不实现 `grid`”） | APP-1、APP-5 |
| DEC-6 | 用户可见的词：`专用设备模式` 改成 `设备管理`，`无线局域网` 改成 `Wi-Fi`，`发送端` 不出现在界面上 | WEL、OTA、UI |
| DEC-7 | `LICENSE` 版权人暂写 `TennousuAthena`；`cast/airplay/` 的 6 个文件按 MIT 标（ADR-043） | LIC |
| DEC-8（已被ADR-046取代） | 原规则：从最底下上滑是全局手势：ThinkOS 里回到“应用”磁贴页；安卓 App 里回到离开时的页面，从安卓应用页打开的就是安卓应用页（用户 2026-11-03 决定，ADR-045） | APP-6、APP-7、APP-8、WEL |
| DEC-9（暂定，2026-11-05，所有者可推翻） | “夜间”改称“红移”，内部名字不改；`attention.to_asleep_s` 的标签改为“无人变暗”（OWN-25，`docs/45` §1） | MODE-1 |
| DEC-10（暂定，同上） | 睡眠：默认关；23:00–07:00；触摸唤醒默认双击；睡眠中没有任何例外；手动切换保持到下一个切换点；红移不限制音量（OWN-26，`docs/45` §11） | MODE-2、MODE-3 |
| DEC-11（暂定，同上） | 蓝牙：120 秒配对窗口、最多 4 个已配对手机、屏幕上显示数字比较、同时只保持一个手机（OWN-27，`docs/46` §5） | BT-1 |
| DEC-12（暂定，2026-11-05，所有者可推翻） | 插件目录的签名：CI 只校验并生成未签名目录和 PR，所有者审阅后在本机一条命令签名并发布（OWN-29 的方案 C；不需要改 `AGENTS.md`） | REG-1 |
| DEC-13（暂定，2026-11-05，所有者可推翻） | 分发渠道：GitHub release 加 CNB（国内源，所有者给定）。目录和大文件都用 release 附件，两个站点布局相同；`update.sources` 里国内源在前；不把 `raw.githubusercontent.com`、jsDelivr、GitHub Pages 放进默认源（这台设备所在网络上时通时不通或不通）；首发目录不带模块项，APK 先走 OTA（OWN-30，`docs/34` §4.10） | REL |

**要所有者定**（没定就不要开始被卡住的任务）：

| ID | 问题 | 推荐 | 卡住 |
|---|---|---|---|
| OWN-1 | 可以把 API 8 这批拆成提交吗 | 可以，在开始 UI 组之前 | BASE-01 |
| OWN-2 | `docs/32-open-ui.md` §8 的四问和附录 A 的 `AGENTS.md` 改动 | 见该文档 | OPEN 全组 |
| OWN-3 | 系统钥匙怎么保管 | 硬件钥匙加一把离线备份 | OTA-1 的钥匙步骤、MOD-1 |
| OWN-4 | 要不要第二台设备做破坏性测试 | 要；只有一台时这项测试所有者要在场 | OTA-0 第 7 项、MOD-2 第 6 条验收 |
| OWN-5 | 什么时候公开，怎么公开 | 先用局域网镜像跑通；上线时新建干净历史的公开仓库 | PUB |
| OWN-6 | 现在换正式证书吗（2026-11-05 审计：设备上是 debug 证书且带 DEBUGGABLE；API 27 不能换证书，迁移要卸载重装；每多刷一台 debug 证书的机器，以后就多一次手工迁移） | 现在换，操作要所有者亲手做；必须在 WEL-5 取金镜像之前完成 | OTA-1 迁移、OTA-5、WEL-5 |
| OWN-7 | 插件审核到什么程度 | 第一批全部人工审核，`community` 档默认隐藏 | REG-1 |
| OWN-8 | 第三方插件的许可证政策 | 不强制 GPL，只要求允许再分发 | REG-1 |
| OWN-9 | 仓库放在哪个账号下 | 建 GitHub 组织放 `thinkos-registry` | REG-1 推送、PUB |
| OWN-10 | 引导里能一键开 Device Owner 吗 | 能，退路同时上 | WEL-2 |
| OWN-11 | 引导的视觉方向 | 看 WEL-0 的两张效果图再定 | WEL-1 |
| OWN-12 | 法律和条款：`playfair` 逆向风险、ADR-043 的灰色地带、Open-Meteo 免费版只限非商业、Bing 每日一图、jsDelivr 条款 | 公开前问律师 | PUB |
| OWN-13 | 版权人确认 | — | PUB |
| OWN-14 | radio-browser 要不要预置成 `radio` 的默认来源（用户 2026-11-03 已说要用它做全球来源；预置会推翻“默认无电台”，`04` §352、`06` 的 radio 行、`29` 广播行、`09` 广播行、APP-3 都要同步） | 预置：它是目录不是频道，和 `iptv` 预置一个列表地址同类 | APP-9 第 7 项 |
| OWN-15 | 点击上报（`/json/url/{uuid}`）默认开还是关：官方要求每次播放都报，但等于把设备 IP 和所听电台发给第三方，与 `35` 的“不收集任何数据”冲突 | 默认关，设置里可开 | APP-9 第 6 项 |
| OWN-16 | 批量装机的镜像里带不带 Wi-Fi 凭据 | 不带，由引导的 Wi-Fi 屏处理；一批机器在同一个网络且数量多时才带，镜像放仓库外 | WEL-5 第 2 项 |
| OWN-17 | **已定（所有者 2026-11-03）：整个换成 Mozilla 的 117 张。** 原问题：设备根证书库的终态，只加（留下现有 135 张，再加缺的 70 张，共 205 张，含 21 张已过期、66 张 Mozilla 已移除的），还是整个换（118 张去掉带 distrust-after 日期的 Izenpe，`docs/41` §3、§4.3）。验证仍建议分两步：先只加后换，见 CERT-3 | — | CERT-3 的 E2 可以做 |
| OWN-18 | **已定（所有者 2026-11-03）：不需要。** 原问题：ThinkOS 要不要自带一份信任根（NSC 的 `@raw`）。结论：CERT-6 取消，没装模块的设备不另外补 | — | CERT-6 已取消 |
| OWN-19 | **暂缓（跨局域网，2026-11-04）：**通话的连接层与会合（交换地址、响铃、在线状态，`docs/43` §6）：路 A 自己的信令服务器加 WebRTC / 路 B′ EasyTier 去 VPN 化（服务器是一个 EasyTier 共享节点）/ 路 C 公共设施 | 先做 CALL-0b 对比实验再定；实验前倾向路 A，连接层做成可替换的接口 | CALL-0b、CALL-4 |
| OWN-20 | **暂缓（跨局域网，2026-11-04）：**打洞失败时，要不要让这台服务器兜底转发（TURN）。“只帮打洞”意味着不转发，两端都是对称型 NAT 的配对永远打不通，界面明说“无法直连” | 首发不兜底，用 CALL-4 的真实成功率再决定 | CALL-3、CALL-4 |
| OWN-21 | 通话对端只有 ThinkOS 设备，还是也要手机、浏览器（有了自己的服务器，网页端可行，但要另做网页和部署） | 首发只做设备 | CALL-2 |
| OWN-22 | WebRTC 库选 LiveKit 还是 Stream（许可、软件 H.264、体积、实测） | 先定在 CALL-0 之后 | CALL-2 |
| OWN-23 | **暂缓（跨局域网，2026-11-04）：**若 CALL-0b 显示 EasyTier（去 VPN 化）明显更能打通：要不要把它作为所有跨地点功能的底座（通话、OTA 缓存、配置同步、远程运维），而不只是通话 | 看 CALL-0b 的数据 | — |
| OWN-24 | **暂缓（跨局域网，2026-11-04）：**会合服务器放哪、谁运维、要不要域名和备案、用 IP 加固定证书还是域名（我没有服务器和账号，不能代办） | 所有者定 | CALL-4 |
| OWN-25 | **暂按推荐执行（DEC-9，所有者可推翻）：**命名：“红移”取代所有“夜间”（`docs/45` §1）；`attention.to_asleep_s` 的用户标签改为“无人变暗”（和“睡眠”撞词） | 同意 | MODE-1（已放行） |
| OWN-26 | **暂按推荐执行（DEC-10，所有者可推翻）：**睡眠模式的默认和例外（`docs/45` §11）：默认关；23:00–07:00；触摸唤醒默认双击；睡眠中没有任何例外（来电、紧急通知、闹钟）；手动切换保持到下一个切换点；红移不限制音量 | 同意 | MODE-2、MODE-3（已放行） |
| OWN-27 | **暂按推荐执行（DEC-11，所有者可推翻）：**蓝牙配对策略（`docs/46` §5）：120 秒配对窗口、最多 4 个手机、屏幕上显示数字比较；同时只保持一个手机 | 同意 | BT-1（BT-0 通过后） |
| OWN-28 | **未授权（2026-11-05 仍无答复，按人工处理，见 §0.7）：**授权 Agent 安装 `thinkos-cast`（以及之后的 `thinkos-btsink`）并重启设备，或由所有者自己执行（`AGENTS.md`：持久安装和重启归人工；CERT 那次是明确授权过的，且写明不扩展到其它模块） | 授权一次，或自己装；两个模块同一次重启 | CAST-1 第 2 步、BT-0 第 3 步 |
| OWN-29 | **暂按推荐执行（DEC-12，所有者可推翻）：**插件目录的签名方式。`docs/34` §5 设计的是受保护 CI 自动签名，`AGENTS.md` 和 §0.2 禁止钥匙进 CI。A 所有者在主机上手动签并发布（现状）；B 在受保护 CI 里放仓库钥匙（要改 `AGENTS.md`）；C CI 只校验并生成未签名目录和 PR，所有者审阅后本机一条命令签名发布 | C | REG-1 |
| OWN-30 | **暂按推荐执行（DEC-13，所有者可推翻）：**渠道布局与顺序。国内源 CNB（`https://cnb.cool/kiwimoe/ThinkOS`，已由所有者给定）在前，GitHub 在后；目录放滚动版本 `ota-<通道>` 的附件，APK、模块、源码包放版本 `v<版本>` 的附件；首发目录不带模块。GitHub 公开仓库的名字待 OWN-31 | 同意 | REL-1 |
| OWN-31 | 公开源码仓库：新建干净历史的公开仓库（OWN-5 原推荐；CNB 的 `kiwimoe/ThinkOS` 已是公开的空仓库，GitHub 另建一个），还是把现有仓库改成公开（要先清 2319 个验收产物和提交历史里的个人邮箱）。GPLv3 要求源码和二进制同处提供，`release.py` 要求 `--source-url` 指向带该版本 tag 的公开仓库 | 新建干净历史的公开仓库 | REL-3、REL-5 |
| OWN-32 | 首发要不要带 FairPlay（playfair 逆向实现，OWN-12 的法律问题没有结论） | 首发不带（`-Pthinkos.fairplay=false`），OWN-12 有结论后再开 | REL-4 |
| OWN-33 | 渠道预演第二部分和正式发布要往 CNB、GitHub 创建 release 并上传：是你本机运行（`tools/publish.py`，令牌放你的环境变量），还是授权 Agent 用你给的最小权限令牌做**测试 release**（测完删除）；正式发布一律你来 | 测试 release 可授权，正式发布你来 | REL-0 第二部分、REL-2 |

### 0.7 本轮收口：目标、被人工挡住时怎么办

**目标**（所有者 2026-11-05）：完成任务清单的开发和调试。“完成”指两种情况之一：任务下的勾选清单全部通过且按 §0.5 留了证据；或者被下面写明的人工步骤挡住，而人工步骤已经写好、交给所有者。

**被挡住时不空等，也不提问**（第 7 行的无人值守约束仍有效）：

1. 把人工步骤写进 `artifacts/<组名小写>/<任务 ID>/human-steps.md`：命令、预期输出、检查方法、出问题怎么回滚。
2. 在任务下加一行 `等人工：<要所有者做什么>`，马上做下一个能做的任务。
3. 收尾时把所有 `等人工` 汇总成一份清单交给所有者，放在汇报的第四项里。

**新增任务哪些现在能做、哪些要人工：**

| 任务 | 现在能做 | 要人工 | 通不过时 |
|---|---|---|---|
| SET-07 | 全部，含真机截图 | — | — |
| CAST-1 | 第 1 步；写好第 2 步的人工步骤 | 第 2 步安装与重启（OWN-28）；第 3 步等装好后可做；第 4 步要手机和电脑 | — |
| MODE-1 | 全部，含改名（DEC-9） | — | — |
| MODE-2 | 全部，含 `time` 控件 | — | — |
| MODE-0 | 写好脚本并在主机上自测 | 看屏幕是否全黑、用手指碰屏幕，所有者在场时才跑 | 背光 0 不黑，或黑屏下触摸失效：MODE-3 的黑屏部分不写，静音和拒绝部分照做，原因写回 `docs/45` §12 |
| MODE-3 | 静音、拒绝、接线和主机测试 | 黑屏效果等 MODE-0 通过 | 同上 |
| BT-0 | 第 1、2 步（读设备、做模块、主机测试）；写好人工步骤 | 第 3 步安装与重启（OWN-28）；第 4、5 步要手机且会出声，所有者在场 | 不通过：BT-1 到 BT-3 不做，原因写回 `docs/46` §6 |
| BT-1 到 BT-3 | BT-0 通过后 | BT-3 要手机 | — |
| OTA-5 | 写好演练脚本和人工步骤；用已有夹具预演拒绝路径 | 生成钥匙、放公钥资产、证书迁移（OWN-6，所有者亲手）；钥匙到位后的演练 Agent 自己做 | 钥匙没到位：只做脚本和拒绝路径，标 `等人工` |
| OTA-6 | 全部（只读复核，不改产品代码） | — | — |
| MOD-1 修测试、OTA-4 补充 | 全部 | — | — |
| REG-2b | 全部（调试包加内存测试目录，不需要正式钥匙） | — | — |
| REG-1 签名方式 | 文档与工作流模板（DEC-12） | OWN-29 有不同答复时改 | — |
| REL-0 | 第一部分（只读下载别人的公开附件，不执行不保存） | 第二部分要初始化 CNB 仓库并授权或代传测试 release（OWN-33） | 第二部分标 `等人工` |
| REL-1、REL-4 | 全部（占位符、默认源、构建参数、说明文案） | OWN-31 的 GitHub 仓库名 | 地址未定的条目留空，不填猜的 |
| REL-2 | 工具与主机测试（本机假服务，不发外网请求） | 真实上传由所有者运行 | — |
| REL-3 | 导出脚本、扫描、`SOURCE_CODE_URL` | 建仓库、推送、tag 归人工（OWN-31） | — |
| REL-5、REL-6 | 写人工步骤和门槛清单 | 提交（OWN-1）、钥匙与证书迁移（OWN-6）、备用机（OWN-4）、正式发布 | `等人工` |

**建议顺序**（其它组仍按 §1.1）：SET-07 和 MODE-1 → CAST-1 第 1 步、BT-0 第 1–2 步，交出人工步骤 → MODE-2 → MODE-0 脚本 → MODE-3 的静音与拒绝 → 其余等人工的项。

**OTA、MOD、REG 收口的顺序：**现在能做的先做：OTA-6 复核、MOD-1 修不稳定的测试、REG-2b、OTA-4 补充、REG-1 的文档。要人工的：OWN-6 钥匙与证书迁移，先于 OTA-5 和 WEL-5；OTA-5 在钥匙到位后做。模块更新器和恢复守卫只装在备用机上（OWN-4），不装在 HUA0BWUT 上，坏模块演练有变砖风险。

**首发交付（2026-11-05 起优先）：**OTA、MOD、REG 之外，先做 §11G REL。顺序：REL-0 第一部分、REL-1、REL-4 → OTA-6 剩下的 F2、REL-2、REL-3 → 钥匙到位后 OTA-5 → REL-0 第二部分 → REL-5。MOD-2 的备用机演练不挡首发：首发目录不带模块，模块自动更新在演练通过后随下一版加入。

**安全与静音：**

- 所有者睡觉期间不允许发出声音（第 9 行）。会出声的步骤（BT-0 第 4 步、BT-3、CAST-1 第 4 步）只在所有者说明在场后才做，其它时候五个音频流保持 0。睡眠的静音检查用 `dumpsys audio` 看状态，不要靠放声音。
- MODE-0 动背光之前，先读出并保存 `/sys/class/leds/lcd-backlight/brightness` 的原值，并在设备上起一个定时回写（`/data/local/tmp` 里的脚本，不持久，默认 20 秒），确认回写生效后才继续。这样黑屏后触摸失效也不用拔电。
- 设备只有一台：先看前台，不同时跑两个设备脚本（§0.3）。

## 1. 总览

### 1.1 顺序

| 顺序 | 组 | 内容 | 前置 | 设备 |
|---|---|---|---|---|
| 1 | BASE | API 8 收口、ADR-040 欠的验证、文档漂移 | BASE-01 要 OWN-1，其余不用 | 要 |
| 1b | PWR | 断电保护（所有者 2026-11-03：“这很重要”） | 拔线实验要人拔线，装模块要人装 | 要，部分人工 |
| 1c | CERT | 系统根证书更新（所有者 2026-11-03：设备的 CA 是很老的一套） | 本次安装和重启已明确授权Agent并完成 | 要，回归部分待补 |
| 1d | CALL | 设备到设备的音视频通话（所有者 2026-11-04：推翻“不做视频通话”；先只做局域网内的互相发现、呼叫、通话、视频，跨局域网暂缓） | 先不需要服务器和第二个网络；要至少两台实体机在同一 Wi-Fi 下验证；发热和性能测试先告知 | 要 |
| 1e | CAST | Miracast 提示“缺少系统模块”：`thinkos-cast` 没装进设备 | 装模块和重启要人工或明确授权（OWN-28） | 要，部分人工 |
| 1f | MODE | 模式：白天、红移（原“夜间”）、睡眠（所有者 2026-11-04） | 性能测试先告知 | 要 |
| 1g | BT | 蓝牙音箱（所有者 2026-11-04） | 叠加层模块的安装和重启要人工；手机由所有者提供 | 要，部分人工 |
| 1h | REL | 首发交付与 OTA 渠道（所有者 2026-11-05：GitHub release 加 CNB） | OWN-1 提交、OWN-6 钥匙与证书迁移、OWN-31 公开仓库、OWN-32 FairPlay；上传、推送、tag、Release 由所有者做 | 要，部分人工 |
| 2 | UI | 界面文字和视觉克制 | 最好在 BASE-01 之后。所有者还没答复时可以先做，只是改动会和没提交的 API 8 混在一起 | 要 |
| 3 | NAV | 设置里的返回 | — | 要 |
| 4 | SET | 设置页结构 | UI-03、UI-14、NAV-02 | 要 |
| 5 | LIC | 许可证发布前清单 | — | 少量 |
| 6 | OTA | APK 的 OTA；审计后加 OTA-5 端到端演练、OTA-6 独立复核 | OWN-3、OWN-6 | 要 |
| 7 | MOD | 系统模块 OTA；更新器和守卫只装在备用机（OWN-4） | OTA-2 | 要，部分人工 |
| 8 | REG | 插件仓库；审计后加 REG-2b，签名方式见 DEC-12 | OTA-2 | 要 |
| 9 | WEL | 新用户引导；装机与批量装机（WEL-3 到 WEL-5） | NAV-01、UI 组；WEL-2 要 MOD-1；WEL-5 要 WEL-4、BASE-05 | 要，刷机人工 |
| 10 | APP | 应用列表、打开安卓 App、广播；安卓应用页与底边上滑（`docs/37`）；电台目录（APP-9，`docs/39`） | UI 组 | 要 |
| 11 | PUB | 公开发布 | 以上全部，OWN-5、OWN-12 | 人工为主 |
| 暂停 | OPEN | 开放界面（ADR-039） | OWN-2 | 要 |
| 不在本轮 | DEBT | 其它欠账，防止遗漏 | 所有者点名才做 | — |

REG 和 WEL 可以并行。APP 和 LIC 随时可以插入。

### 1.2 和其它文档的阶段名对照

`docs/34` 的 `M1`、`M2` 和路线图的 `M1`、`M2` 撞名，本文统一改叫：

| 本文 | 文档里的阶段 | 文档 |
|---|---|---|
| OTA-0、OTA-1、OTA-2、OTA-3 | S0、O1、O2、O3 | `34` §8 |
| MOD-1、MOD-2 | M1 更新器、M2 模块 OTA | `34` §8 |
| REG-1、REG-2、REG-3 | R1、R2、R4 | `34` §8 |
| WEL-0 到 WEL-3 | W0 到 W3 | `35` §5 |
| APP-1、APP-2、APP-3、APP-4 | 阶段 2 余下、阶段 4 前半、阶段 3 余下、阶段 4 后半 | `29` §8 |
| OPEN-P0 到 OPEN-P5、OPEN-S1 | P0 到 P5、S1 | `32` §6 |

## 2. BASE：收口

### BASE-01 把 API 8 这批拆成提交（需要 OWN-1）

现状：工作区 209 个改动路径（119 个已修改，90 个未跟踪），`feat/tiles` 和 `main` 没有提交差异，分支名和内容对不上。

1. 请别的 worker 停手，否则树对不上。记下基准：`git status --short > /tmp/thinkos-before.txt`；用临时索引取整棵树的哈希，包括未跟踪文件，不动工作区和真实索引：`GIT_INDEX_FILE=/tmp/thinkos-index git add -A && GIT_INDEX_FILE=/tmp/thinkos-index git write-tree`。
2. 建议的提交顺序：ADR-035 操作栏，ADR-036 音乐界面，ADR-037 沉浸式系统层，ADR-038 电视，ADR-040 应用与返回，最后一个提交放文档（ADR-029、039、041、042、043，`docs/32`–`36`，`LICENSE`、`LICENSE-GPL-3.0.md`，`THIRD_PARTY_NOTICES.md`）。
3. 交互式 `git add` 不可用，按文件拆。一个文件混了几个 ADR 时放进最后改它的那个提交，并写进提交说明。分支名问所有者。

验收：

- [ ] 所有者同意后才开始，全程没有推送。
- [ ] `git rev-parse HEAD^{tree}` 等于第 1 步的树哈希。
- [ ] 提交内容里没有私钥、keystore、口令、令牌：`git log -p main..HEAD | grep -E 'BEGIN .*PRIVATE|storePassword|keyPassword'` 无结果。
- [ ] §0.4 全部通过。

### BASE-02 补做 ADR-040 欠的验证

来自 `docs/33-apps-and-back.md` §8。

- [ ] 重跑 `player_device.py`、`media_ui_device.py`、`iptv_device.py`（前两个要 `tools/media/gen_samples.sh` 和测试源站）。
- [ ] 屏幕最边缘的触摸灵敏度：从 x = 2、4、6、10 起手各试 10 次，记录触发率。不够就调 `nav.back_edge_dp` 的默认值，先报告。
- [ ] 横屏安装方向：系统导航栏的位置；没装 `thinkos-immersive` 时左缘滑动会不会闪出系统栏。
- [ ] 靠左缘的可拖动控件：进度条、滑块、表单行逐个量左缘留白 ≥ `nav.back_edge_dp`，列表写回 `33` 号文档。
- [ ] 阿拉伯语、乌尔都语下：手势仍在物理最左，箭头方向正确。
- [ ] 性能（先告知）：`gfxinfo` 十分钟 janky < 5%；冷启动 < 3 秒；`clock_accuracy_device.py` 覆盖时钟页翻走和返回的动画期间。
- [ ] 首次拖动返回到时钟页时有没有卡（`33` §9）。卡就改成提前 `queuePreview`。

### BASE-03 修文档漂移

每条都先核对，已经对了就跳过。

结果：2026-11-03，通过，`artifacts/base/BASE-03/report.md`；选择器描述已随 NAV-01 实现纠正，行为另行上机验收。

| 位置 | 问题 | 改成 |
|---|---|---|
| `docs/08-decisions.md` ADR-035 | 还写“草案、尚未实现” | 阶段 1 已实现，阶段 2 部分实现，指向 `29` §8 |
| ADR-036 末尾、`docs/30-player-ui.md` §6 第 6 条 | “API 8 冲突待商量” | 已解决，都在 API 8 |
| `docs/03-architecture.md` §3.1–§3.3 | `AppGraph`、`Reducer`、`Effects`、`SyncAgent`、`LiveLink`、`PerceptionManager` 代码里没有 | 按实际写 `RuntimeHost`、`Store.update{}`、各 Policy；还没实现的标“计划” |
| `docs/03-architecture.md` §3.17 | 依赖表列了 Lottie、serialization、MediaPipe | 按 `android/app/build.gradle.kts` 改，计划的标“计划” |
| `AGENTS.md:49`、`README.md:75` | `go run ./cmd/thinkos-server`，但 `server/cmd/thinkos-server` 是空目录 | 写明 Server 还没有入口程序 |
| `AGENTS.md:36`、`:82`、`docs/04-ui-spec.md` §6 | 提到 Lottie，实际不是依赖，`assets/lottie/` 是空的 | 写明目前没有用 Lottie |
| `README.md` | “当前状态”缺操作栏、电视、音乐界面、应用；文档索引停在 19 号 | 补上，索引补到 36 |
| `docs/33-apps-and-back.md` §4.1 第 2 条 | 说时区、地点选择是安卓对话框 | NAV-01 一起改 |
| `docs/29-panel-and-apps.md` §6 | `apps.return_on_sleep` 代码里叫 `apps.return_after_s` | 按代码改 |
| `docs/09-roadmap.md` | M0、M0.5 的复选框没跟上 | 只按已有证据勾，没证据的不勾 |

验收：

- [x] 表里每一行都有结论（已改，或已经对了）。

### BASE-04 拆 `PluginRuntime` 和 `RuntimeHost` 的接缝（不改行为）

结果：2026-11-03，主机验收通过，`artifacts/base/BASE-04/report.md`；61入口/42段原文分支保持一致，JVM596项未减少、Python64/native/lint/assemble通过。设备回归与通用性能待HUA0BWUT连接恢复，暂不勾整体验收。

现状：`PluginRuntime.kt` 1046 行，`host()` 的 `when` 约 190 行；`RuntimeHost.Observer` 混着 Kiosk、面板、方向、存储权限和磁贴动画。OTA、REG、WEL、APP 都要往这里加接口，建议在 OTA-3 之前做。

1. 把 `host()` 按能力拆成小对象（设备、设置、媒体、应用、时间等），`PluginRuntime` 只负责分派和权限入口。
2. 把 `Observer` 按用途拆成几个小接口。

验收：

- [ ] 只有移动，没有行为变化：JVM 测试数不减少，§0.4 全过。
- [ ] `nav_back_device.py`、`panel_device.py`、`public_data_device.py` 通过。

### BASE-05 装机文档里的版本判据（WEL-5 的前置）

结果：2026-11-03，通过。`docs/19`两处判据与`docs/02`字段已纠正；只读批量校验入口`tools/provision.py verify --serial …`使用ro.lineage.version、API27和纯armv7判据。HUA0BWUT实际读到ABI为armeabi-v7a，不强制要求历史记录里的armeabi兼容别名；主机6个新用例及Python80项通过。证据`artifacts/base/BASE-05/`。完整装机/金镜像流程仍属于WEL-3/WEL-5，尚未实现。

2026-11-03 在 HUA0BWUT 上读到：`ro.build.fingerprint`、`ro.bootimage.build.fingerprint`、`ro.vendor.build.fingerprint` 都是联想官方的 `Lenovo/CD-18781Y/CD-18781Y:8.1.0/OPM1.171019.026/penghu06021417:user/release-keys`；Lineage 的标识在 `ro.build.display.id`（`lineage_starfire-userdebug 8.1.0 OPM7.181205.001 … test-keys`）和 `ro.lineage.version`（`15.1-20240531-UNOFFICIAL-starfire`）。每条先核对，已经对了就跳过。

| 位置 | 问题 | 改成 |
|---|---|---|
| `docs/19-provisioning-for-agent.md` §4.3 和 §8 第 1 项 | 用 `ro.build.fingerprint` 含 `lineage_starfire-userdebug 8.1.0` 判断 Lineage，在这台设备上读到的是官方指纹，判据永远不成立 | 查 `ro.lineage.version`；ABI 一项不变 |
| `docs/02-device.md` §2 “指纹”一行 | 把 display id 当指纹写 | 拆成 `ro.build.display.id` 和 `ro.lineage.version`，注明 `ro.build.fingerprint` 仍是官方的 |

验收：

- [x] 表里每一行都有结论。
- [x] 新判据在 HUA0BWUT 上读得到，批量校验脚本（WEL-5）用它。

## 3. UI：界面克制

用户原话：“界面语言太啰嗦了。严查此问题：加一堆解释性的描述文字，画蛇添足”，然后列了下面的清单，“严格避免”。

### 3.1 规则与现状

| 用户禁止 | 这里的规则 | 现状 |
|---|---|---|
| 到处都是标签和斜体字 | 不用斜体；状态不做成小标签 | 代码里没有斜体，守住 |
| Instrument Serif、Space Grotesk、Bricolage Grotesque | 只用系统字体，APK 不带字体文件 | 字体文件 0 个，守住 |
| 滥用等宽字体 | 不用 `monospace`；数字用 `tnum` | 守住 |
| 细节多到过头：分割线、多层阴影、半透明叠色、带边框的容器 | 列表不画分割线；不做容器外框；阴影只用于照片和视频上的文字；半透明只用于视频和照片上的遮罩 | 设置行有分割线；时区搜索框有外框；呼吸圆透过列表显示 |
| 图表总是超详细 | 一条线、一块填充，没有基线、网格和坐标轴 | 天气图表有基线，下面还有一行时间范围 |
| 文案口语化或自造词 | 用通用叫法，不造词 | `此刻`、`专用设备模式`、`发送端`、`看门狗` |
| 代码面包屑、章节编号、标题上方的小眉标 | 不用 | 时钟卡片的“此刻”是眉标 |
| 全大写的小标题 | 不用 | 视图没有用 `upper`，守住 |
| 用中圆点当分隔符 | 禁用 `·`、`•`。同一行的几个值用两个空格分开，句子里用逗号或顿号 | 约 27 行（系统表 6、插件表 3、Lua 和视图 9、Kotlin 9），其它语言同样 |
| 正文塞 Emoji，堆没用的文字 | 不用 Emoji | 没有 Emoji，守住（`✓`、`♪` 是控件标记） |
| hero 字体大得离谱 | 最大 72sp。时钟表盘按画布比例，它就是产品本身，不算 hero | 守住；引导第 8 屏不能超过 72sp |
| 解释性的描述文字 | 默认没有，见 §3.2 | 可见设置 70 行，22 行带说明，共 1147 字 |
| 嵌套没做同心圆角 | 内圆角 = 外圆角 − 内边距 | 没逐个核对，UI-13 |
| 线条粗细不统一 | 分隔和描边 1 物理像素；图标和曲线 2dp；进度条和滑块轨道 4dp | 1dp、1.25dp、1.4dp、1.5dp、2dp、2.4dp、3dp、4dp、5dp、6dp 都有 |
| 字号超过 4 种，最小低于 12px | 只有 72、32、18、14sp 四档 | 至少 17 种：10、11、12、13、14、15、16、17、18、20、22、24、28、32、36、56、72；还有 11–72 的自动缩放 |
| 卡片左边缘的彩色竖线 | 不用 | 静态搜索没找到，UI-17 走查时再看一遍 |
| 下拉菜单按钮的下拉小图标 | 选项行不画箭头 | `FormView` 的 `choice` 和 `text` 行有 `›`，展开时还转 90° |

**例外**：

- **时钟表盘**（`ClockView`、`AeroClockView`）的数字和指针按画布比例，线宽也不受限。
- **Aero 桌面**（ADR-026）是用户要的 Windows 7 致敬：玻璃、边框、渐变、阴影保留，文字仍然只用四档字号，也不用中圆点。
- **磁贴开始屏**的渐变背景和光带（ADR-028）保留。
- **画成图标的字形**（快进的“10”、封面占位图的大字）和**视频字幕**不算界面字号。
- **署名和许可声明**是合规内容，不算废话，不删：`Open-Meteo.com`、`CC BY 4.0`、GeoNames、摄影署名。
- **诊断页**可以显示技术数据。

### 3.2 文案规则

1. 界面只写三样东西：这是什么、现在是什么状态、能做什么。不解释原理。
2. 设置行只有标签和当前值。格式要求放进输入框的占位符，输错了由校验报错。只有打开后有风险的开关才在打开时显示一句 `warn`。
3. 正常状态不显示状态文字。只有异常时显示一句，说结果，不说原因链。
4. 空状态一句话：没有什么，或者下一步做什么。
5. 不用“请”“您”“你”和感叹号。单句结尾不加句号。
6. 不造词。协议和产品用通用名：DLNA、AirPlay、Miracast、Wi-Fi。
7. `zh-Hans` 长度上限：设置标签 12 字，其它界面文字 20 字。超了就改短，不换行凑。
8. 同一列表每行图标都一样时不画图标。同一信息在一屏上只出现一次。

### 3.3 任务

#### UI-00 拍“改之前”的截图

结果：2026-11-03，通过，`artifacts/ui/UI-00/report.md` 和 `index.json`，264 张改前实机截图，状态还原通过

1. 写 `tools/tests/ui_walkthrough.py`：用调试广播（`page`、`orientation`、`locale`、`theme`）走一遍下表的页面，截图存到指定目录，结束时恢复原来的语言、方向和主题。
2. 页面：`home.main`；`clock` 四个表盘和 `sunrise`、`moon`、日历；`weather.detail`；相册轮播；`tiles.main`、`tiles.apps`；`aero`；`player.library` 各标签；`player.audio`；`cast.home`；`iptv.channels` 各标签；`iptv.live`（用 `iptv_device.py` 的本地测试台）；操作栏；系统设置和每个插件的设置；时区选择器；地点选择器；救援菜单；诊断框；安全模式横幅。
3. 组合：竖屏和横屏；深色；`zh-CN`。再加 `en`、`ar` 各一遍竖屏，再加浅色和夜间各一遍竖屏。

验收：

- [x] 脚本可重复运行，结束后设备状态和开始时相同。
- [x] `artifacts/ui/UI-00/` 有全部截图，文件名含页面、方向、语言、主题。

夜间的首页与相册按既定策略实际返回时钟，截图和索引如实记录，不绕过路由。

#### UI-01 把规则写进规范

结果：2026-11-03，通过，`artifacts/ui/UI-01/report.md`

1. `docs/08-decisions.md` 追加 ADR-044“界面克制”：§3.1、§3.2 的规则和例外，来源写用户原话。先查编号没被占。
2. `docs/04-ui-spec.md`：
   - §2 字号表改成四档（UI-11）。
   - §3 加线宽（UI-12）和同心圆角（UI-13）。
   - §4 第 135 行的 `12sp 署名` 改 14sp；第 151、159 行的 22sp 改 18sp。
   - §8 最小字号 14sp 不变。
   - §10 删掉圆点、`help`、行尾 `›` 的说法，改成 UI-03、UI-14 的新规则。
   - 磁贴一节第 308、310 行的字号改成四档。
   - 新增 §11“界面文字”，放 §3.2。
3. `AGENTS.md` 的代码约定加一条：界面文字和视觉按 ADR-044，新界面默认没有说明文字，字号只有四档。
4. `docs/06-plugins.md` §11.1 写明 `help`、`warn`、`placeholder` 的规则（UI-03）。
5. 按 DEC-6 改 `docs/09-roadmap.md`、`docs/34-ota-and-plugin-registry.md`、`docs/35-onboarding.md` 里的词。ADR-041、ADR-042 的原文不动，在标题下加一句注明。

验收：

- [x] `grep -n '22sp\|12sp\|10sp\|13sp' docs/04-ui-spec.md` 无结果，或每一处都有理由。
- [x] ADR-044 写明例外。

#### UI-02 系统字符串

结果：2026-11-03，本项核对通过，`artifacts/ui/UI-17/` 的维护、诊断与横幅截图；11 × 175 系统字符串校验通过。横幅为无持久副作用的预览。通用性能门槛待统一实测。

改 `shared/i18n/system/*.json`，然后跑 `tools/gen_android_strings.py`。

| 键 | 现在 | 改成 |
|---|---|---|
| `service_body` | 时钟与插件在设备上独立运行 | 删除；`KioskService.kt:42` 不再 `setContentText` |
| `safe_mode_banner` | 安全模式 · 仅运行内置时钟 | 安全模式 |
| `maintenance_title` | ThinkOS · 维护 | 维护 |
| `maintenance_android_settings` | 打开系统设置，暂停看门狗 5 分钟 | 打开安卓设置 |
| `maintenance_exit_safe_mode` | 清除安全模式并重启 | 退出安全模式 |
| `maintenance_diag_body` | 版本 {1}\n{2} · {3}\n页面 {4}\n安全模式：{5}\n本地运行 · 无需 Server\n\n{6} | 版本 {1}\n{2}  {3}\n页面 {4}\n安全模式 {5}\n\n{6} |
| `form_place_hint` | 搜索你所在的城市、区或县，坐标会自动填入。 | 删除；`PlacePicker.kt:73,159,167` 没内容时隐藏这一行 |
| `form_place_empty` | 未找到地点，换个名称试试。 | 未找到地点 |
| `form_place_error` | 在线搜索暂不可用，仅显示设备内置的时区城市。 | 无法联网搜索 |
| `form_place_invalid` | 该地点缺少可用的时区。 | 该地点没有时区 |
| `form_place_attribution` | 搜索：Open-Meteo · 地点数据：GeoNames，CC BY 4.0 | `Open-Meteo  GeoNames  CC BY 4.0` |
| `setting_theme_mode_auto` | 自动，环境光 | 自动（环境光） |
| `setting_location_mode_auto` | 时区代表城市，近似 | 按时区 |
| `setting_location_mode_manual` | 选择地点 / 手填坐标 | 手动 |
| `setting_server_url` | Server 地址，留空 = 自动发现 | Server 地址 |
| `setting_time_ntp_servers` | NTP 服务器，逗号分隔 | NTP 服务器 |
| `setting_units_system_metric` | 公制 · °C | 公制（°C） |
| `setting_units_system_imperial` | 英制 · °F | 英制（°F） |
| `setting_cast_enabled` | 用 DLNA 接收投屏 | DLNA |
| `setting_cast_airplay` | 用 AirPlay 接收投屏 | AirPlay |
| `setting_cast_airplay_mirror` | AirPlay 屏幕镜像 | 屏幕镜像 |
| `setting_cast_miracast` | 用 Miracast 接收投屏 | Miracast |
| `setting_cast_name` | 投屏时显示的设备名称 | 设备名称 |
| `setting_nav_back_enabled` | 从左边缘右滑返回 | 左边缘右滑返回 |
| `media.reconnecting` | 正在重新连接，第 {1} 次尝试 | 正在重新连接（调用处去掉参数） |
| `media.error_network` | 网络异常，请检查连接 | 网络异常 |
| `media.error_policy` | 已拦截：明文 HTTP 只允许在局域网内使用 | 已拦截明文 HTTP |
| `media.warning_audio` | 无法解码 {1} 音频，将无声播放 | 无法解码 {1} 音频 |
| `apps_open_failed` | 无法打开{0} | 无法打开 {1}（占位符从 1 开始，现在生成的是 `%0$s`） |

验收：

- [x] 11 种语言都改了，`gen_android_strings.py --check` 通过。
- [x] `grep -rn '·' shared/i18n/system/` 无结果。
- [x] 救援菜单、诊断框、安全模式横幅的截图和表一致。

#### UI-03 设置说明：删 `help`，加 `warn` 和 `placeholder`

结果：2026-11-03，本项功能通过，`artifacts/ui/UI-03/results.json`；第三方 help 横竖屏验证见 `artifacts/ui/UI-14-feedback-final.log`。通用性能门槛待统一实测。

现状：`help` 在标签下面整段显示，22 行共 1147 字。

**新字段**：

| 字段 | 用在 | 行为 |
|---|---|---|
| `warn` | `bool` | 开关打开时在标签下显示一行，次文字色；关着时不显示 |
| `placeholder` | `text` | 编辑对话框里输入框的 `hint`；列表行上不显示 |

`help` 继续解析和显示（API 1–8 的第三方插件不能坏），内置插件不再用。

**要改的文件**：`shared/schema/plugin/manifest.json` 的 `$defs/setting`；`shared/schema/settings/system.json`；`shared/schema/settings/vectors.json`；`shared/gen/` 重新生成；`settings/SettingField.kt` 的 `COMMON`、`ALLOWED`；`settings/SettingsDescriber.kt`；`ui/form/FormView.kt`（`help` 视图、`editText`）；`docs/06-plugins.md` §11.1。

**系统设置**：

| 键 | 处理 |
|---|---|
| `panel.enabled`、`nav.back_enabled`、`apps.android`、`apps.return_after_s`、`cast.airplay_mirror` | 删 `help` 和对应的 11 份字符串 |
| `cast.enabled`、`cast.airplay` | 删 `help`，`warn`：同一网络里的任何人都能投屏 |
| `cast.miracast` | 删 `help`，`warn`：附近的任何人都能投屏 |
| `media.public_http` | 加 `warn`：视频地址和内容不加密 |

`setting_cast_airplay_help` 里的“暂不支持屏幕镜像”已经不对了，别保留。

**插件设置**：

| 插件 | 键 | 处理 |
|---|---|---|
| `iptv` | `source_1`–`source_4` | 删 `help`，`placeholder`：M3U 或 JSON 地址 |
| `iptv` | `epg_url` | 删 `help`，`placeholder`：XMLTV 或 JSON 地址 |
| `iptv` | `epg_zone` | 删 `help`，`placeholder`：Asia/Shanghai |
| `iptv` | `cooldown` | 删 `help` |
| `player` | `stream_url` | 删 `help`，`placeholder`：http、https 或 rtsp 地址 |
| `player` | `playlist_url` | 删 `help`，`placeholder`：M3U 地址 |
| `weather` | `endpoint` | 删 `help`，`placeholder`：Open-Meteo 兼容地址 |
| `gallery` | `local_dir` | 删 `help`，`placeholder`：默认目录 |
| `gallery` | `pan`、`caption`、`pan_seconds` | 删 `help` |

被删的说明里有用的内容要留在给人看的文档里：`json:` 前缀、`{name}`、`{id}`、`{date}` 模板、节目单 1 MB 上限、央视网节目表地址写进 `docs/31-iptv.md`；相册目录的默认位置写进 `docs/21-gallery-handoff.md`。

验收：

- [x] 内置插件和 `system.json` 里 `help` 的数量是 0。
- [x] JVM：`warn` 只能用在 `bool`，`placeholder` 只能用在 `text`，用错被拒绝；`vectors.json` 加了正反例。
- [x] 旧写法的 `help` 仍然显示：`tools/tests/fixtures/feedback-ui-probe` 验证。
- [x] 设备：打开 DLNA 后出现一句，关掉后消失；编辑 `epg_url` 时输入框有占位符。
- [x] 截图：系统设置的投屏区没有长段文字。

#### UI-04 操作栏

结果：2026-11-03，操作栏 32/32 通过，`artifacts/ui/UI-04-device.log`；100% 双端点补图与通用性能门槛待完成

改 `plugins/panel/main.lua`、`plugins/panel/i18n/*`、`ui/PanelComponents.kt`。

| 位置 | 现在 | 改成 |
|---|---|---|
| 底部一行 | `PDCN_5G  192.168.2.167 · 网络设置` | `网络设置`。网络名已经在 Wi-Fi 按钮下面，IP 只放诊断框 |
| `network.footer` | {status} · 网络设置 | 网络设置（去掉参数） |
| `wifi.off`、`wifi.searching`、`network.none` | Wi-Fi 已关、正在搜索、未连接网络 | 删除。按钮文字：连上时是网络名，否则 `Wi-Fi`；开和关靠图标和按钮状态 |
| 滑块右侧的百分比 | `100%` 被圆点压住 | 删除；滑块轨道延伸到右边距。无障碍描述保留百分比 |

验收：

- [x] `panel_device.py` 通过（第 127、226 行找底部一行的逻辑按新文字改）。
- [ ] 截图：亮度和音量滑到 100% 时圆点不压任何东西；底部只有“网络设置”，点了打开安卓 Wi-Fi 页。

#### UI-05 天气

结果：2026-11-03，开发中；主机检查见 `artifacts/ui/`，设备验收未全部完成，暂不勾选

改 `plugins/weather/main.lua`、`views/detail.json`、`views/now.json`、`i18n/*`、`ui/BuiltinComponents.kt`（图表）。

| 位置 | 现在 | 改成 |
|---|---|---|
| 状态文字 | `更新于19:56 · 0分钟前` | 数据新鲜时不显示。`state.stale` 为真时显示 `更新于19:56`，前面加 `离线` 或 `数据已过期` 时用两个空格分开 |
| 卡片第 4 行 `cardInfo` | `香港 · 更新于19:56 · 0分钟前` | 新鲜时只有地点；否则地点和状态之间两个空格 |
| `summary` | `晴 · ↑30° ↓23°` | `晴  ↑30° ↓23°` |
| `metrics` | `体感31° · 湿度66%` 换行 `风速9 km/h` | 一行：`体感31°  湿度66%  风速9 km/h` |
| 每日副标题 | `阴 · 降水0%` | `阴`；降水 ≥ 20% 时 `小雨  降水41%` |
| `hourly` | 未来24小时 · {unit} | 未来24小时 |
| 图表下的时间范围 `hourlyRange` | `17:00 → 16:00` | 删除这一行和对应状态 |
| 列表标题 `@daily` | 未来5天 | 删除这一行和键 `daily`；星期几已经说明了 |
| 图表基线 | `BuiltinComponents.kt:142–143` | 删除 |
| 署名 | `Open-Meteo.com · CC BY 4.0` | `Open-Meteo.com  CC BY 4.0`；卡片上的 `Open-Meteo.com` 保留（`docs/20-weather-handoff.md` §数据源） |
| `unavailable`、`retrying` | 暂时无法获取天气；将自动重试 | 无法获取天气；删除 `retrying` |
| `age` | {count}分钟前 | 删除 |
| `time_unknown` | 等待时间同步 | 等待校时 |
| `help.endpoint` | 见 UI-03 | 删除 |

验收：

- [ ] `tools/tests/weather_test.lua` 按新文案更新后通过（第 88、115 行附近断言状态文字）；`weather_ui.py`、`weather_device.py` 通过。
- [ ] 断网后卡片显示 `离线  更新于…`，恢复后消失。
- [ ] `views/forecast.json` 和磁贴不受影响。
- [ ] Aero 的天气窗口里地点那行不再被截断（旧截图 `artifacts/clock/regress/aero-start-menu.png` 被截断了）。
- [ ] 数据新鲜时，每分钟的定时刷新不改变屏幕上的文字。

#### UI-06 时钟与首页

结果：2026-11-03，开发中；主机检查见 `artifacts/ui/`，设备验收未全部完成，暂不勾选

| 位置 | 现在 | 改成 |
|---|---|---|
| `plugins/clock/views/now.json` | 时间上方 `@now`（“此刻”） | 删除这一行，删除 11 份 `now` 键 |
| `plugins/clock/views/calendar.json` | 底部有日历图标 | 删除图标；月、日、星期保留 |
| `plugins/home/manifest.json` | `"name": "首页"`、`"title": "此刻"` 写死中文，没有 `i18n/` | `"name": "@name"`、`"title": "@title"`，加 11 份 `i18n`，都译成“首页” |

验收：

- [ ] 首页卡片截图没有“此刻”；英语、阿拉伯语下首页没有中文。
- [ ] `clock_accuracy_device.py` 不跑也可以（只删了文字行），但 `tile_regression.py` 和 `public_data_device.py` 要过。

#### UI-07 投屏页

结果：2026-11-03，本项功能通过，`artifacts/ui/UI-07-final/results.json`、`UI-07-device-final.log`；修复开关与网络变化不刷新状态，实际断开 Wi-Fi 并还原。返回 56/56 见 `artifacts/nav/NAV-01-final/report.md`。通用性能门槛待统一实测

改 `plugins/cast/main.lua`、`i18n/*`，以及 `docs/33-apps-and-back.md` §6 的描述。

| 键 | 现在 | 改成 |
|---|---|---|
| `page.home` | 投屏到这块屏幕 | 投屏 |
| `hint.dlna` | 视频 App、Windows“投放到设备” | 视频 App、Windows |
| `hint.miracast` | 安卓手机和 Windows 的屏幕投射 | 安卓手机、Windows |
| `footer.on` | 手机或电脑连着同一个 Wi-Fi，在投屏或 AirPlay 菜单里选择“{name}”。 | 删除；标题已经写了设备名 |
| `footer.none` | 投屏没有开启。点一行进入设置，把它打开。 | 删除；每行都写着“关” |
| `footer.offline` | 这台设备没有连上 Wi-Fi，发送端找不到它。请先连上 Wi-Fi。 | 未连接 Wi-Fi |

验收：

- [x] 三种状态的截图：全开、全关、断网。只有断网时底部有文字。
- [x] 底部没字时不留空白占位，列表布局不跳。
- [x] `nav_back_device.py` 通过（它打开 `cast.home`）。

#### UI-08 电视

结果：2026-11-03，开发中；主机检查见 `artifacts/ui/`，设备验收未全部完成，暂不勾选

改 `plugins/iptv/main.lua`、`i18n/*`、`ui/TvOverlay.kt`、`tools/tests/iptv_test.lua`、`tools/tests/iptv_device.py`。

| 位置 | 现在 | 改成 |
|---|---|---|
| 频道行图标（`main.lua:241`、`:311`） | 每行都是同一个电视图标 | 不画 |
| 频道行右侧（`main.lua:232` 起） | `3/3` | 全部可用时不显示；有失效线路时显示 `2/3` |
| `footer.summary` | 共 {channels} 个频道 | 删除 |
| `footer.testing`、`footer.paused` | 正在检测线路…；{total} 条线路中有… | 删除，后台状态不上屏 |
| `footer.empty` | 请在此插件的设置里添加频道列表 | 长按进入设置添加频道列表 |
| `footer.empty_favorites` | 还没有收藏。打开一个频道，点“收藏”。 | 没有收藏 |
| `footer.empty_recent` | 还没有看过任何频道 | 没有最近观看 |
| `notice.sources_failed` | 无法加载频道列表，显示的是上次保存的 | 无法更新频道列表 |
| `notice.all_failed` | 这个频道的线路现在都不能用。请稍后再试，或换一个频道。 | 这个频道的线路都不可用 |
| `notice.switched` | 已切换到线路 {line} / {lines} | 线路 {line}/{lines} |
| `notice.policy` | 已被拦截：互联网明文 HTTP 处于关闭状态。请在系统设置里…（60 字） | 已拦截明文 HTTP，可在设置里允许 |
| `help.*` | 见 UI-03 | 删除 |
| 电视浮层的台号（`TvOverlay.kt:39–41`） | 半透明圆角底 | 只有数字，没有底 |
| 电视浮层的分组名（`:48`） | 频道名旁边的 `News` | 删除 |

验收：

- [ ] `iptv_test.lua`、`iptv_native.sh`、`iptv_device.py` 按新行为更新后通过（第 322 行找底部文字的检查要改）。
- [ ] 上千频道的列表滚动不变慢：行结构只是少了图标。
- [ ] 截图：频道列表、分组、收藏为空、最近为空、直播浮层。

#### UI-09 播放器

结果：2026-11-03，开发中；主机检查见 `artifacts/ui/`，设备验收未全部完成，暂不勾选

改 `plugins/player/main.lua`、`i18n/*`、`ui/NowPlayingModel.kt`、`docs/30-player-ui.md`。

| 位置 | 现在 | 改成 |
|---|---|---|
| `hint.cycle` | 轻点标题切换到“{next}” | 删除，已经没人用（`30` §6 第 5 条） |
| `empty.music`、`empty.video` | 在 {dir} 里没有找到音乐 | {dir} 里没有音乐（视频同理） |
| `empty.recent` | 还没有播放过任何内容 | 没有最近播放 |
| `empty.streams` | 请在此插件的设置里添加串流地址或播放列表 | 长按进入设置添加地址 |
| `setting.resume` | 从上次停下的位置继续 | 从上次位置继续 |
| `error.network` | 网络异常，请检查连接 | 网络异常 |
| `error.policy` | 已拦截：明文 HTTP 只允许在局域网内使用 | 已拦截明文 HTTP |
| 列表图标（`main.lua:57`、`:65`） | 音乐、视频、串流标签里每行都一样 | 不画；“最近播放”音频和视频混排，保留 |
| 格式行（`NowPlayingModel.kt:127`） | `FLAC · 24-bit · 48 kHz` | `FLAC  24-bit  48 kHz`；只在整页 `full` 显示，这是 ADR-036 的设计，不删 |

验收：

- [ ] `player_test.lua`、`player_native.sh`、`player_ui_device.py`、`NowPlayingModelTest` 更新后通过。
- [ ] 截图：整页横竖屏、一行 `bar`、首页卡片、各标签的空状态。

#### UI-10 相册

结果：2026-11-03，开发中；主机检查见 `artifacts/ui/`，设备验收未全部完成，暂不勾选

改 `plugins/gallery/i18n/*` 和 `plugins/gallery/main.lua`。

| 键 | 现在 | 改成 |
|---|---|---|
| `empty` | 请将照片放入相册目录 | 相册目录里没有照片 |
| `offline` | 离线 · 展示已保存的照片 | 离线 |
| `bing_unavailable` | 暂时无法获取每日图片，稍后重试 | 无法获取每日图片 |
| `waiting_clock` | 等待设备校时 | 等待校时 |
| `setting.pan` | 平移展示整张照片 | 平移显示 |
| `setting.wallpaper_only` | 跳过 wp=false 的图片 | 只要壁纸图片 |
| `help.*` | 见 UI-03 | 删除 |

验收：

- [ ] `gallery_test.lua`、`gallery_device.py`、`bing_test.lua` 通过。
- [ ] 图片署名不受影响，仍然常驻（`docs/21-gallery-handoff.md`）。

#### UI-11 四档字号

结果：2026-11-03，静态、几何与 11 语言截图核对通过，`artifacts/ui/UI-11/report.md`；性能待测

加 `ui/TextScale.kt`：`DISPLAY = 72f`、`TITLE = 32f`、`BODY = 18f`、`CAPTION = 14f`。所有界面文字只用它。放不下时降档或省略，不缩小，不新增档。

| 位置 | 现在 | 改成 |
|---|---|---|
| `PageView.kt:135`、`:315` 文字样式 | `display` 72 / 紧凑 56；`title` 32 / 紧凑 24；正文 22 / 紧凑 18；`caption` 14 | `display` 72，紧凑时 32；`title` 32，紧凑时 18；正文 18；`caption` 14 |
| `PageView.kt:603` 磁贴页标题 | Thin，最大 40 | Thin，32 |
| `TileView.kt:75–77`、`:315–339` | 名称 10、12、13；详情 11、13；照片标题 15、17；数值自动缩放 12–72 | 名称和详情 14；照片标题 18；数值用 `setAutoSizeTextTypeUniformWithPresetSizes`，预设 14、18、32、72，small 不超过 32 |
| `BuiltinComponents.kt:301–303` 列表行 | 标题 17、20；副标题 13、15；右侧 15、18 | 标题 18；副标题 14；右侧 14。行高不变 |
| `BuiltinComponents.kt:98`、`:246` 画布文字 | 按 dp 和比例算 | 18 或 14，用 `scaledDensity` |
| `MediaComponents.kt:192,206,225` | 18、17、20 | 18 |
| `MediaWidgets.kt:241` 进度条时间 | `13f * dp` | 14，用 `scaledDensity` |
| `MediaWidgets.kt:464,466` | 14、16 | 14、18 |
| `NowPlaying.kt:346–350`、`NowPlayingModel.kt` | 标题自动缩放 11–28，艺术家 13、14、18 | `full`：标题预设 18、32，艺术家 18。`bar` 和 `card`：标题 18，艺术家 14 |
| `NowPlaying.kt:619–621` 队列行 | 14、17、13 | 14、18、14 |
| `TvOverlay.kt:39–80` | 18、20、14、16、13、14 | 台号和频道名 18；节目 18；其余 14 |
| `ThinkStage.kt:43–44` | 提示 16；启动字 36 | 18；32 |
| `ImageComponents.kt` 照片文字 | 标题 18，署名 12 | 18，14 |
| `form/FormView.kt:248,310` | 箭头 22；`−` `+` 24 | 18 |
| `form/TimezonePicker.kt:66–67`、`form/PlacePicker.kt:57–58,71–72` | 返回 28；标题 22；搜索按钮 24；提示 15 | 返回和标题 32；搜索按钮 18；提示 14 |
| `AeroHomeView.kt:97` 和它的调用处 | 多种 | 四档 |

验收：

- [x] UI-16 的字号检查通过，白名单只有 §3.1 的例外。
- [x] `RenderGeometryTest`、`NowPlayingModelTest`（12 种页面尺寸）、`PanelGeometryTest`、`NowPlayingPreviewTest` 更新后通过，每部分都不越界。
- [x] 11 种语言的 `tiles.main` 截图：small 磁贴名称放不下时省略，不出框。把被省略的语言和名称列出来报告。
- [x] 首页横屏：紧凑卡片的温度降到 32 后如果观感不行，报告并附截图，不要自己加第五档。
- [ ] 性能（先告知）：`tile_perf.py`、AWAKE 和 AMBIENT 待机不超过基线 +20%。

#### UI-12 线宽

结果：2026-11-03，本项核对通过；`artifacts/ui/UI-12/report.md`、`stroke-audit.txt`。通用性能门槛待统一实测。

加 `ui/Stroke.kt`：`hairline()` 返回 1 物理像素；`ICON = 2dp`；`TRACK = 4dp`。这块屏 density 1.625，0.5dp 画出来就是 1 像素。

| 位置 | 现在 | 改成 |
|---|---|---|
| `BuiltinComponents.kt:88` 进度 | `min(6dp, …)` | 4dp |
| `BuiltinComponents.kt:144` 曲线 | 3dp | 2dp |
| `BuiltinComponents.kt:306` 列表迷你进度 | 3dp | 4dp |
| `DaylightPainter.kt:15` | 5dp | 4dp |
| `MediaWidgets.kt:438` 加载圈 | 3dp | 2dp |
| `ThinkStage.kt:922` 返回箭头 | 2.4dp | 2dp |
| `ThinkStage.kt:948` 唤醒波纹 | 1.5dp | 2dp |
| `PanelComponents.kt:190`、`form/FormWidgets.kt:116`、`NowPlaying.kt:476` | 4dp | 用常量 |

不改：`IconView` 的图集线宽（按画布单位）、`ClockView`、`AeroClockView`、`AeroHomeView`、字幕描边（`MediaWidgets.kt:404`）。

验收：

- [x] `grep -rn 'strokeWidth' ui/` 的每一处要么用常量，要么在白名单里。
- [x] 时钟页截图和改之前比，表盘没有变化。

#### UI-13 同心圆角

结果：2026-11-03，本项核对通过；`docs/04-ui-spec.md` §3，`artifacts/ui/UI-13-device-retest.log` 8/8，四组栈外变化均为 0px。通用性能门槛待统一实测

逐个核对嵌套关系，把结果写成表放进 `docs/04-ui-spec.md` §3：外圆角、内边距、内圆角。

要查：`Tabs`（`Tabs.kt:26`、`:75`，24 和 20）；栈卡片（32dp）和里面的图片、`nowplaying` 封面；操作栏底部圆角和里面的内容；相册预览卡片；列表高亮（`BuiltinComponents` 里 14dp）在有底色容器里时。Aero 不查。

验收：

- [x] 表里每一行都满足内圆角 = 外圆角 − 内边距，不满足的改了。
- [x] 栈翻页的圆角蒙版不受影响：`stack_mask_device.py` 通过。

#### UI-14 去掉多余的装饰

结果：2026-11-03，六类设备脚本通过，共66项，证据 `artifacts/ui/UI-14/`（settings、system-retest、feedback-final、session-final、timezone-final3、place-final4）；确认重置及地点六键联动见 `artifacts/ui/UI-03/results.json`。静态走查见 UI-17，拖动时呼吸圆不闪与通用性能仍待补验

1. **设置行分隔线**：删 `FormView.kt` 的 `divider`。分组之间靠间距。
2. **行尾箭头**：`choice`、`text` 不画，也不再转 90°。只有 `link` 画，因为它真的去另一页。
3. **小圆点和它占的 56dp**：删 `dot`、`dotMark`，标签和分组标题对齐到行首。删 `form_reset_hint`。
4. **恢复默认**：放到每个设置页底部，只在这一页有改动时出现。`link` 行加可选字段 `confirm`，`FormView` 用已有的 `AlertDialog` 确认，确认后才发事件。Lua 调 `tos.settings.reset(scope)`。`settings` 插件加键 `reset`（恢复默认）、`reset.confirm`（恢复这一页的默认设置？）。
5. **呼吸圆**：含 `form`、`list`、`tabs` 的页面把它盖住。`ThinkStage.syncAmbient`（`:370`）现在只看 `opaque`，加一个判断。首页和时钟页保留。拖动翻页时的行为不变（`:382`、`:636`）。
6. **时区搜索框外框**：`TimezonePicker.kt:168` 删掉描边。
7. **翻页圆点**：`system.pageCount` 已经按 `router.ownsSideways` 判断，截图确认设置页和应用页没有圆点。没有就不用改。

验收：

- [x] `settings_ui.py`、`system_settings_ui.py`、`settings_feedback_ui.py`、`settings_session_ui.py`、`timezone_ui.py`、`place_ui.py` 更新后通过。
- [x] 设备：改一项后底部出现“恢复默认”；点了先问，取消不变，确认后回到默认，这一行消失。
- [x] 地点设置恢复默认时 6 个相关键一起清掉（现在 `form.reset` 对 `place` 有特殊处理，保持这个结果）。
- [ ] 截图：`cast.home`、`iptv.channels`、`player.library`、`weather.detail`、设置页没有呼吸圆；首页还有。
- [ ] 进出这些页面、拖动翻页时呼吸圆不闪。

#### UI-15 扫掉剩下的中圆点

结果：2026-11-03，通过，`artifacts/ui/UI-15/`、`artifacts/ui/python-ui.log`；57 项 Python 检查通过

`ui/TileView.kt:415`、`ui/ClockView.kt:370–371`、`ui/form/TimezonePicker.kt:219`、`ui/form/FormView.kt:354`（无障碍描述）、`location/PlaceSearch.kt:75`、`ui/NowPlayingModel.kt:127`、`plugins/weather/views/detail.json:35`，以及 11 种语言的所有表。

同一行的几个值用两个空格；地名这种列表用顿号或逗号。`SettingsDescriber.kt:73` 的 `•` 是密码掩码，保留。

验收：

- [x] 除密码掩码外，`grep -rn '[·•]'` 在 `android/app/src/main/java`（注释除外）、`plugins`、`shared/i18n` 无结果。

#### UI-16 防回归检查（“无则加勉”）

结果：2026-11-03，通过，`artifacts/ui/UI-16/`、`artifacts/ui/python-ui.log`；57 项 Python 检查通过

写 `tools/tests/test_ui_restraint.py`，`unittest discover` 能发现，不要设备。

| 检查 | 范围 | 规则 |
|---|---|---|
| 分隔符 | 11 种语言的全部字符串表、`plugins/**/*.lua`、视图 JSON | 没有 `·`、`•` |
| 语气 | `zh-Hans` 表 | 没有 `请`、`您`、`你`、`！`；单句不以 `。` 结尾 |
| 造词 | `zh-Hans` 表 | 没有 `此刻`、`专用设备`、`无线局域网`、`发送端`、`看门狗` |
| 长度 | `zh-Hans` 表 | `setting` 开头的键 ≤ 12 字，其它 ≤ 20 字；白名单：`maintenance_diag_body`、署名 |
| 说明 | `system.json`、`plugins/*/manifest.json` | `help` 数量 0；`warn` 只在 `bool`，≤ 20 字 |
| 字号 | `android/.../ui/**/*.kt`、`shell/` | `textSize` 字面量只能是四档；白名单写明文件和原因 |
| 字体 | 同上，加 `res/`、`assets/` | 没有 `ITALIC`、`MONOSPACE`、`SERIF`、`setAllCaps`、`createFromAsset`；没有字体文件 |
| 阴影 | 同上 | `setShadowLayer`、`elevation`、`BlurMaskFilter` 只在白名单里（照片文字、Aero） |
| 大写 | 视图 JSON | 没有 `upper` 过滤器 |
| Emoji | 字符串表和视图 | 没有 |

验收：

- [x] 在现在的代码上它会失败，改完后通过。加一个测试文件夹里的故意违规样本，确认检查真的能抓到。
- [x] 写进 `tools/README.md` 和 `AGENTS.md` 的提交前自查。

#### UI-17 走查

结果：2026-11-03，页面表已完成，见 `artifacts/ui/UI-17/page-review.md` 与 `comparison.html`；所有者审阅与性能待办，表内列明旧截图和未验证项。

1. 用 UI-00 的脚本再拍一遍，存到 `artifacts/ui/UI-17/`。
2. 对照 §3.1 每一行逐页看。特别看：左边缘彩色竖线、标题上方的小字、一屏上重复的信息。
3. 把前后对比图交给所有者。

验收：

- [x] 一张表：页面、发现的问题、处理结果。没问题的页面也要列。
- [ ] 所有者看过前后对比。这一条不能由 Agent 自己勾。
- [ ] 性能（先告知）：AWAKE 和 AMBIENT 待机、冷启动、`clock_accuracy_device.py` 不回归。

## 4. NAV：设置里的返回

#### NAV-01 选择器和展开项先收返回

结果：2026-11-03，功能验收通过，56/56 项；`artifacts/nav/NAV-01-final/report.md`。通用性能门槛待统一实测。

现状：时区和地点选择器是 `FormView` 里的叠层（`openPicker`、`openPlacePicker`），不是安卓对话框。`ComponentGroup.back()`（`PageView.kt:276`）只问 `renderer`，`FormView` 没有 renderer，所以选择器开着时按返回会退出整个设置页。`33` 号文档 §4.1 第 2 条写错了。

1. `FormView` 加 `back(): Boolean`：选择器开着就关选择器；有展开的选项就收起；否则返回 `false`。
2. `ComponentGroup.back()` 同时问 `FormView`。
3. 手势：`ThinkStage.backSwipe`（约 `:674`）提交时直接调 `router.back`，没有先问 `current?.back()`。手势和 Back 键要走同一条路（`33` §4.1）。起手时如果页面有自己的东西要关，不预览上一层，只做橡皮筋位移，和声明了拦截的应用一样。Aero 开始菜单开着时也走这里。
4. 改 `33` 号文档 §4.1。

验收：

- [x] `nav_back_device.py` 加用例并通过：时区选择器开着时，Back 键和边缘滑都只关选择器，再一次才关设置；地点选择器同样；展开的选项同样；Aero 开始菜单开着时边缘滑只收起菜单。
- [x] 文字输入对话框开着时 Back 由安卓处理，不受影响。
- [x] 选择器开着时 30 秒自动关设置的计时照旧。

#### NAV-02 设置内部换页进返回

结果：2026-11-03，功能验收通过，56/56 项；`artifacts/nav/NAV-01-final/report.md`。通用性能门槛待统一实测。

现状：从系统页进相册是 `settings` 插件自己换内容，返回会直接退出设置。`DeckRouter.back` 写死了 `interceptable = top.kind != SETTINGS`，`topOwner()` 也排除了设置层。`tos.back.intercept` 没有按 api 限制（`PluginRuntime.kt:569`），`settings` 的 `api: 1` 不用改。

1. 去掉这两处限制。`BackGate` 的 2 秒兜底同样适用，写坏的设置插件困不住人。
2. `plugins/settings/main.lua`：维护 scope 栈；进下一层时 `tos.back.intercept(true)`；收到 `back` 弹一层并重新渲染；回到入口那一层时 `tos.back.intercept(false)`。`page.leave` 后拦截自动作废，`page.enter` 时重建。
3. “完成”仍然退出整个设置。长按进入时 `params.scope` 就是栈底。
4. 改 `33` §4.1、§4.2，写明设置层也能拦截返回。§3 的“设置页仍然拦住一切”说的是 `open`、`requestFocus` 和翻页，不变。

验收：

- [x] 设备脚本：系统页 → 相册 → Back 回系统页 → Back 退出设置。长按天气进入 → Back 直接退出。
- [x] 边缘滑和 Back 键行为相同。
- [x] 把 `settings` 的 `back` 处理临时改坏（调试包推送）：2 秒后下一次返回能退出。注意 `system.settings` 只给内置副本，推送版拿不到权限，用单元测试或测试 APK 验证。
- [x] `BackGateTest`、`NavStackTest` 加用例。

## 5. SET：设置页结构

来源：2026-11-02 的讨论，之前只在聊天里，这里是第一次落到文档。结构放在 `settings` 插件和 schema，原生只负责画控件。不做搜索。外观不开放（ADR-017）。

#### SET-01 小修

结果：2026-11-03，本项功能通过；相册回顶部见 `artifacts/nav/NAV-01-final/report.md`，分组相邻检查见 `tools/tests/test_plugin_packages.py`，隐藏字段与下发合法性见 `SystemSettingsVisibilityTest`。通用性能门槛待统一实测

1. 换页时回到顶部：`FormView.setHeader` 发现标题变了就 `scroll.scrollTo(0, 0)`。不要放在 `setFields` 的签名判断里，否则改一个开关也会跳回顶部。
2. 同组的行排在一起：相册 `scan_seconds`（`group.source`）排在 `display` 组后面，所以分组标题重复。调整 `plugins/gallery/manifest.json` 的顺序；`pan_seconds`、`pan_direction` 也放回 `display` 中间。
3. 隐藏没有代码读取的项：`server.url`、`server.token`、`apps.android`。`apps.android` 等 APP-1 做完再放出来，`server.*` 等 M2。
4. `SystemSettingsVisibilityTest` 的期望集合同步改。

验收：

- [x] 从系统页底部点进相册，停在相册顶部。
- [x] 相册分组标题不重复；`test_plugin_packages.py` 加检查：同一 `group` 的字段必须相邻。
- [x] 系统设置里看不到这 3 项，Server 下发仍然合法（现有测试）。

#### SET-02 schema 加声明

结果：2026-11-03，主机功能通过，真机待连接恢复；证据 `artifacts/set/SET-02/report.md`。系统分类与 Lua 路由留 SET-03；通用性能检查待实测。

| 字段 | 位置 | 含义 |
|---|---|---|
| `sections` | `system.json` 顶层 | `[{id, label}]`，决定分类和顺序 |
| `section` | 系统设置字段 | 属于哪个分类 |
| `level` | 所有字段 | `normal`（默认）、`advanced`、`hidden`。布尔 `hidden` 继续接受，等于 `level: hidden`，两个不能同时写 |
| `depends` | 所有字段 | `{"key": "cast.airplay", "value": true}` 或 `{"key": "source", "in": ["bing", "all"]}`；SET-03补 `{"key":"source_1","not":""}` 表示非空。三者互斥，依赖链全部满足才显示，值保留 |
| `format` | `number` | 现在只有 `duration`（值的单位是秒），显示成 `30 秒`、`15 分钟`、`1 小时`；可选 `zero` 指定 0 显示什么 |
| `presets` | `number` | 档位，例如 `[30, 120, 300, 600]`。有档位时用内联单选，不用滑块 |
| `related` | manifest 顶层 | 这个插件相关的系统设置键，显示在它的设置页最上面。只能是存在、可见、非 `secret` 的键。插件仍然不能读写 |
| `link` 行、`confirm` | `form` 的 `fields` | 现在 `link` 只存在于 `settings/main.lua` 和 `FormView.activate`，schema 和文档都没有，写成契约 |

要改：`shared/schema/plugin/manifest.json`、`layouts.json`（`x-thinkos-api`）、`shared/schema/settings/system.json` 和 `vectors.json`、`shared/gen/`、`settings/SettingField.kt`、`SettingsStore.kt`（`visibleFields`）、`SettingsDescriber.kt`、`tools/package_plugins.py`、`docs/04-ui-spec.md` §9–§10、`docs/06-plugins.md` §11。

`depends` 由 `SettingsDescriber` 判断，Lua 不需要懂每一项的含义。

验收：

- [x] 正反例都进 `vectors.json`；Kotlin 和 Python 校验结果一致。
- [ ] `depends` 指向不存在的键、形成循环、`related` 指向 `secret` 或隐藏项：安装时拒绝。
- [ ] 主开关关掉时依赖项收起，打开时恢复原值。
- [ ] 旧 manifest 不改也能安装（共享 API 1 正例与现有插件校验通过；真实设备重新安装待连接恢复）。

#### SET-03 分类和层级

结果：2026-11-03，本项功能通过；原生输入、计时、城市/单位联动、related-only路由、重启保留和指定脚本均有新版APK设备证据。真实注意力计时5.33/31.13/60.85秒，四文件逐字节还原。三次断网完整绘制2.801/2.859/2.800秒；其余性能继续验收。证据 `artifacts/set/SET-03/report.md`。

`system.json` 填上 `section` 和 `level`：

| 分类 | 常用项 | 高级 |
|---|---|---|
| 显示 | `theme.mode`、`theme.night`、`display.brightness_max`、`display.orientation`、`attention.awake_to_ambient_s`、`attention.to_asleep_s`、`motion.clock` | `attention.engaged_idle_s`、`attention.ambient_page_after_s` |
| 位置与单位 | `location.name`、`units.system`、`units.wind` | — |
| 桌面 | `tiles.live`、`tiles.interval`、`tiles.background`、`panel.enabled`、`nav.back_enabled` | — |
| 媒体与投屏 | `media.keep_awake_video`、`cast.enabled`、`cast.airplay`、`cast.airplay_mirror`（依赖 `cast.airplay`）、`cast.miracast`、`cast.name` | — |
| 系统 | `kiosk.lock`、`media.public_http`、`apps.return_after_s`；链接：关于本机（LIC-03）、打开安卓设置；OTA 做完后加软件更新 | — |
| 插件 | 动态列出有设置的插件 | — |

其余继续隐藏：`theme.*_lux`、`location.*` 的坐标项、`panel.edge_dp`、`panel.idle_s`、`nav.back_edge_dp`、`apps.hidden`、`time.*`、`media.buffer_*`、`server.*`、`apps.android`。

时长字段：

| 键 | `format` | `presets`（秒） | `zero` |
|---|---|---|---|
| `attention.awake_to_ambient_s` | `duration` | 30、60、120、300、600 | — |
| `attention.to_asleep_s` | `duration` | 60、180、300、600、1800 | — |
| `apps.return_after_s` | `duration` | 0、300、900、1800、3600 | 关闭 |

字符串：`setting_attention_awake_to_ambient_s` 改成“变暗时间”，`setting_attention_to_asleep_s` 改成“休眠时间”。分类名按上表。

`plugins/settings/main.lua`：

1. 系统页是分类列表，点进去是这一类的项，最下面“高级”进这一类的高级项（有才显示）。
2. 分类和层级都用 NAV-02 的栈，返回一层一层退。
3. `插件` 分类点进去是插件的设置页。

插件设置分级：

| 插件 | 常用 | 高级 | 隐藏 |
|---|---|---|---|
| `weather` | `interval` | `stale_after`、`endpoint`、`high_wind`、`heat`、`cold` | `retry_initial`、`retry_max`、`clock_retry` |
| `gallery` | `source`、`market`（依赖 source 含 bing）、`local_dir`（依赖 source 含 local）、`interval`、`transition`、`pan`、`shuffle`、`caption` | `refresh_hours`、`history`、`duration`、`pan_speed`、`caption_hold`、`scan_seconds`、`pan_seconds`、`pan_direction` | 现有隐藏项 |
| `iptv` | `source_1`–`source_4`（后一个依赖前一个非空） | `epg_url`、`epg_zone`、`test`、`test_gap`、`cooldown`、`recheck` | — |
| `player` | 全部 | — | — |
| `clock` | 全部 | — | — |

`related`：`weather` → `location.name`、`units.system`、`units.wind`；`tiles` → `tiles.live`、`tiles.interval`、`tiles.background`；`cast` → 投屏的 5 项；`clock` → `motion.clock`；`iptv`、`player` → `media.public_http`。

Lua 写入时按键判断范围：键里有 `.` 就是 `system`，否则是当前插件。

长按直达：长按磁贴页进 `tiles` 的设置页，长按投屏页进 `cast` 的；`cast.home` 里点一行也进 `cast` 的，不再进系统页。

验收：

- [x] 系统页竖屏一屏放得下分类，不需要滚动。
- [x] 天气的设置页最上面是城市和单位，改了立刻生效，写进系统范围。
- [x] `tiles` 和 `cast` 没有自己的字段也有设置页。
- [x] 变暗和休眠时间在设备上能改，下一次计时按新值，重启保留；不在档位里的下发值也能正确显示。
- [x] 自动返回显示“15 分钟”和“关闭”，不显示 `900 s`。
- [x] `SystemSettingsVisibilityTest` 的期望和注释同步（变暗和休眠不再是“只由 Server 下发”）。
- [x] `settings_ui.py`、`system_settings_ui.py`、`weather_ui.py`、`settings_session_ui.py` 更新后通过。
- [x] 改 `docs/04-ui-spec.md` §10 的入口和退出描述。

#### SET-04 控件

结果：2026-11-03，本项功能通过。亮度/数字9项见 `artifacts/set/SET-04/device-reconnect2/results.json`；实际按住时不保存、取消恢复、松手提交。纵向起手在最新APK上实际滚动且保存值不变，见 `artifacts/set/SET-03/settings-device/results.json`；四文件逐字节还原。通用性能正在补验。

1. **紧凑数值行**：没有 `presets` 的 `number` 默认一行：标签和值。点开才显示滑块和 `−` `+`，和 `choice` 的展开共用 `expanded`，一次只开一项。
2. **亮度跟手**：`display.brightness_max` 拖动时直接走 `DeviceLevels`，和操作栏滑块一样；松手才写设置。不要把 `FormView` 和某个键写死在一起，给字段加描述器算出来的 `preview` 标记。
3. 去掉行内的单位原文，`display` 由描述器格式化。

验收：

- [x] 拖动亮度时屏幕跟着变，取消手势恢复原值。
- [x] 其它数值仍然只在松手时提交。
- [x] 纵向起手仍然交给滚动（`settings_ui.py`）。
- [x] `docs/04-ui-spec.md` §10 的控件描述同步。

#### SET-05 系统分类的入口

结果：2026-11-03，本项功能通过，`artifacts/set/SET-03/routes-device2/results.json`：原生系统分类打开真实安卓设置，Back回到ThinkOS，Lock Task恢复；未改变默认桌面或安装模块。四文件逐字节还原，音量0。

“打开安卓设置”走现有的 `tos.device.openAndroid("settings")`，`settings` 插件要加 `system.device` 权限。Lock Task 的离开和回来沿用 `panel_device.py` 的检查。

验收：

- [x] 打开后能回来，Lock Task 恢复。

#### SET-06 横屏两栏（最后做）

结果：2026-11-03，真机38项通过，11语言横屏、独立滚动、RTL、旋转与返回均已验证；四文件逐字节还原。修复设置重复描述器调用超CPU预算，未提高预算。最新JVM612/Python71/完整native/lint/assemble通过。竖屏严格像素比较仍有少量RGB差1的记录，性能未运行；详见 `artifacts/set/SET-06/report.md`。

现在横屏两侧共闲着 228dp。宽度 ≥ 700dp 时，`FormView` 左边是分类（约 240dp），右边是内容。竖屏不变。

1. `form` 组件加可选字段 `nav`（分类列表和当前项）。不加新版式，不加新组件。
2. 两栏各自滚动。点左边不进返回栈；“高级”和插件页仍然进。
3. RTL 镜像。

验收：

- [x] 横屏 11 种语言截图，分类名不换行。
- [x] 横竖屏切换时当前分类不丢。
- [ ] 竖屏截图和做这个任务之前一致。
- [ ] 性能（先告知）：打开设置不超过 AWAKE 基线 +20%。

#### SET-07 一级菜单图标

结果：2026-11-05，0.1.14已装。中文六分类真实Iconfont图标、横竖屏、浅/深/红移三色均实机与目视通过；证据 `artifacts/app/APP-9/0.1.14/menu-final-1/`（11项含恢复）。11语截图、插件/高级页专项与性能仍待，不能勾整组。

所有者 2026-11-04：设置的一级菜单要加图标，图标还是从 Iconfont（icon font）里找。

**现状**（2026-11-04 读代码）：

- 一级菜单是 `settings` 插件根页的 `link` 行（`plugins/settings/main.lua` 按 `system.json` 的 `sections` 逐个 `link(...)`）；横屏两栏时左边是同一份分类列表（`formNav`）。
- 两种描述符都**没有图标字段**（`shared/schema/plugin/layouts.json` 的 `formLink`、`formNav.items`），`FormView` 也不画行图标。
- 现在 6 个分类：显示、位置与单位、桌面、媒体与投屏、系统、插件。计划里还会加：模式（`45`）、蓝牙（`46`），通话（`44`）可能也要。
- **ADR-044 不反对图标：**它只写“同一列表图标全相同时不画”，图标描边 2dp。分类各不相同，符合规则。
- **图标来源规则**（`assets/README.md`、`THIRD_PARTY_NOTICES.md`）：不自己画；取自 Iconfont 合集“Lucide”（合集 54062，上传者“逆态度”，ISC）；SVG 放 `assets/icons/lucide/`，在 `glyphs.json` 登记 Iconfont 编号，`tools/gen_glyphs.py` 生成矢量。已有 `cast`、`moon`、`sun`、`brightness`、`settings` 等 23 个。

**提议的图标：**

| 分类 | Lucide 名 | 是否已有 |
|---|---|---|
| 显示 | `monitor` | 新 |
| 位置与单位 | `map-pin` | 新 |
| 桌面 | `house` | 新（`layout-grid` 已作应用页的 `apps`） |
| 媒体与投屏 | `cast` | 已有 |
| 系统 | `sliders-horizontal` | 新（齿轮 `settings` 已是“设置”入口，分类里不重复） |
| 插件 | `puzzle` | 新 |
| 模式 | `moon-star` | 新；控制面板的红移用 `sunset`（新），睡眠用 `moon`（已有） |
| 蓝牙 | `bluetooth` | 新 |
| 通话 | `video` | 新，通话做完后再加 |

上游 lucide.dev 里这些名字都存在（逐个请求 `icons/<名字>.svg` 都返回 200）。**它们在 Iconfont 合集 54062 里有没有，我没法确认：**页面要 JavaScript 或登录，抓取只得到标题。

| # | 内容 |
|---|---|
| 1 | 词汇：`formLink.icon`、`formNav.items[].icon`（可选，名称格式 `^[a-z][a-z0-9-]*$`，必须在图标词汇表里）；在 `x-thinkos-api` 登记，沿用当前 API 号（§0.3：发布前不再升）；按 §0.3 重新生成 Kotlin 和 Go；Go 与 JVM 的 schema 测试 |
| 2 | `system.json` 的 `sections[]` 加可选 `icon`；设置插件把它带到 `link` 和 `nav`；插件自己的设置页不加图标 |
| 3 | 图标：先在 Iconfont 合集 54062 里找上面的名字，找到的记 Iconfont 编号；合集里没有的，在 Iconfont 里搜同名的 Lucide；都没有才用 lucide.dev 上游的同名 SVG（`THIRD_PARTY_NOTICES.md` 已写明可以，要在 `glyphs.json` 里注明来源）。放进 `assets/icons/lucide/`，登记，`python3 tools/gen_glyphs.py`，再 `--check`；跑 `GlyphsTest`；更新 `THIRD_PARTY_NOTICES.md` 的图标清单。**注：**`assets/README.md` 说要把名字加进 `IconView.kt` 的 `staticNames`，我在 `android/` 里搜不到这个名字，这一条可能已过期，以 `gen_glyphs.py` 生成的 `GlyphResources.kt` 和 `GlyphsTest` 为准 |
| 4 | `FormView`：行首 24dp 图标（2dp 描边），与文字间距 16dp；颜色平时用次要文字色，横屏左栏选中项用强调文字色（现有选中规则）；行高不变；RTL 镜像；图标跟随红移滤镜（根层滤镜自动）。**一级分类要么全有图标要么全没有**，不画占位 |
| 5 | 测试：`test_ui_restraint.py` 加检查（一级分类图标存在、互不相同、都在词汇表里）；`ui_walkthrough.py` 重拍设置根页（竖屏、横屏两栏；浅色、深色、红移；11 种语言含阿拉伯语和乌尔都语） |
| 6 | 文档：`docs/04` 的设置结构一节、`docs/06` 的词汇表、`assets/README.md` |

验收：

- [x] 一级菜单每行有图标，互不相同，竖屏和横屏两栏都有。
- [ ] 11 种语言的截图（含从右到左）：图标在文字的起始侧，不遮挡、不裁切。
- [x] 插件页、高级页等其它设置页外观不变。

结果：2026-11-05，通过中文专项。0.1.22实际插件概览/滚动、时钟关联与自有字段、系统高级两字段，横竖8图及Back正常，11项含恢复通过；root目视确认内容行无新增分类图标或占位，横屏左栏分类图标按设计保留。原9文件字节/UI/管理/静音还原。首轮XML叶子真假判断失败已留证并修正外层脚本，未改应用。证据 `artifacts/set/SET-07/0.1.22-other-pages-2/visual-qualification.json`；11语/RTL与性能仍不勾。
- [ ] 图标许可已登记；冷启动和设置页滚动不回归（性能后置）。

## 6. LIC：许可证

设计见 ADR-043。根目录的 `LICENSE`、`LICENSE-GPL-3.0.md`、`THIRD_PARTY_NOTICES.md` 已经有了。

#### LIC-01 `-Pthinkos.fairplay=false`

结果：2026-11-03，双构建及库清单核对通过，`artifacts/lic/LIC-01/build-results-current.json`；设备 opt-out 与默认镜像回归待连接恢复。

1. `android/app/build.gradle.kts` 读这个属性，传 CMake 参数。
2. `android/app/src/main/cpp/CMakeLists.txt`：参数关掉时不编 `thinkos_playfair`。
3. `PlayFair.kt` 找不到库时已经返回不可用，`CastHost.kt:73` 已经会隐藏镜像，确认不用再改。
4. 写进 `docs/07-build-deploy.md`、`docs/28-casting.md` §15 那段。

验收：

- [x] `./gradlew -Pthinkos.fairplay=false -Pthinkos.ffmpeg=false assembleDebug` 通过，APK 里没有 `libthinkos_playfair.so` 和 `libffmpegJNI.so`。
- [ ] 这个包装到设备上：`cast_airplay_device.py` 视频推送通过，镜像不对外宣告；AC-3 提示无法解码，画面照播。
- [ ] 默认构建行为不变：`cast_airplay_mirror_device.py` 通过。

#### LIC-02 改过时的注释

结果：2026-11-03，注释核对通过，`artifacts/lic/LIC-02/report.json`；不改变构建行为。

- 构建脚本已写明：完整版带 FFmpeg 并按 GPLv3 发布（ADR-043），可用 `-Pthinkos.ffmpeg=false` 省略。
- FairPlay JNI 已明确此文件是 GPLv3，项目其它源码为 MIT，指向根目录 LICENSE 与 ADR-043。

验收：

- [x] 源码和文档中不再保留过时的整库许可与分发注释；ADR-034 的历史标题保留。

#### LIC-03 关于页和许可证文本

结果：2026-11-04，当前APK五份法律文本32项真机检查通过，含MPL新入口、第三方说明纯文本（无Markdown源码、表格条目化、链接地址保留）、全文滚动/返回、权限和插件全停时维护入口。截图已目视检查，原始打包字节保持一致。先前25/29项为历史检查点；最新c0498b6f…已安装并复测，当前证据 `artifacts/certs/CERT-3/legal-final/`。不含GPL的MIT声明和正式引导入口仍未验。

补充：2026-11-03，OTA-2加入MPL-2.0根证书数据，关于页新增第五份完整法律文本与11语言入口，主机APK资源原字节核对通过。新MPL入口尚未上机验收；早期25项只覆盖当时四份文本。见`artifacts/update/OTA-2/roots/report.md`。

现状：APK 除了 OkHttp 自带的一个 `NOTICE`，没有任何许可证文本，也没有关于页。GPLv3 第 5 条 d 要求交互界面显示 Appropriate Legal Notices。

1. 构建时把 `LICENSE`、`LICENSE-GPL-3.0.md`、Apache-2.0 全文（新增 `LICENSE-APACHE-2.0.txt`）、`THIRD_PARTY_NOTICES.md` 放进 `assets/legal/`，由 Gradle 任务复制，不手工维护第二份。Apache-2.0 全文从 apache.org 取。
2. 原生页面，不依赖插件：
   - 第一屏：版本号；版权行（读 `LICENSE` 第 3 行）；`本软件按 GNU GPL 第 3 版发布，不提供任何担保，可以按此许可证再分发`；四个条目：GPL 全文、MIT、Apache-2.0、第三方说明；源码地址。
   - 点条目看可读纯文本，可滚动；Markdown源文件显示前去格式化，表格转为条目、链接保留可读地址，不显示Markdown源码。打包原始法律文件的字节不变。
3. 入口：救援菜单加“许可证”；设置“系统”分类的“关于本机”（给 `settings` 一个新接口 `tos.settings.about()`，要 `system.settings`）；引导第 5 屏。
4. 不含 GPL 的构建（LIC-01 两个开关都关）第一屏改说 MIT：用 `BuildConfig` 判断。
5. 文字进 11 种语言，法律全文不翻译。

验收：

- [x] `unzip -l app-debug.apk | grep legal` 有 4 个文件。
- [x] 救援菜单和设置页都能打开，四个条目都能读到底。
- [x] 所有插件禁用时救援菜单的入口仍然可用。
- [x] `tos.settings.about` 没有权限的插件调用被拒绝。

#### LIC-04 FFmpeg 扩展的源码存档

结果：2026-11-03，通过，`artifacts/lic/LIC-04/report.md`；14,524 个上游 blob 校验相等，在线后与离线重建的完整 tar.gz 摘要一致。发布附件已准备，未发 Release。

1. 写 `tools/archive_ffmpeg_source.sh`：取 `jellyfin/jellyfin-androidx-media` 标签 `v1.9.0+1` 和两个子模块（`media`、`ffmpeg`，后者是 `release/6.0`），打成 tar，算 SHA-256。子模块指向的提交写进输出。
2. 发布流程把它和 GPLv3 全文附到每个 Release（`34` §4.9）。

验收：

- [x] tar 里有构建脚本、`media/libraries/decoder_ffmpeg`、`ffmpeg/configure`。
- [x] 脚本可重复，SHA-256 写进 `THIRD_PARTY_NOTICES.md`。

#### LIC-05 查没查完的许可证（只记事实，不下结论）

结果：2026-11-03，事实核对通过，`artifacts/lic/LIC-05/report.md`；16个缺失POM与Guava继承声明已补证，服务条款已读。VSOP87官方说明未明确许可，按事实保留缺口；法律判断和公开发布结论仍留OWN-12。

| 项 | 做什么 |
|---|---|
| 16 个 POM 不在本地缓存的模块（列表：`./gradlew -q --offline :app:dependencies --configuration releaseRuntimeClasspath`） | 取各自的 POM 或项目 LICENSE，确认许可证，更新 `THIRD_PARTY_NOTICES.md` 那一行 |
| Suntimes | 查许可证，写进“只读参考” |
| VSOP87D（IMCCE） | 查使用条款 |
| Open-Meteo、GeoNames、Bing 每日一图 | 读条款，摘原文和链接，写进 ADR-043 的“已核对” |
| jsDelivr | 读使用条款（之前页面没有打开），写进 `34` §4.2 |

验收：

- [x] 每项有链接和摘录，结论留给所有者（OWN-12）。

#### LIC-06 人工

- [ ] 所有者确认版权人（OWN-13），改 `LICENSE` 第 3 行。
- [ ] 所有者评估 ADR-043 的灰色地带，公开前问律师（OWN-12）。

## 7. OTA：APK 更新

设计见 `docs/34-ota-and-plugin-registry.md` §2–§4、§6。先把 APK 的 OTA 做通，之后任何问题都能推修复。

**审计结论（架构，2026-11-05；OTA、MOD、REG 共用）：**代码大体写完，但发布链路从来没有用真实钥匙和地址跑通过。

- 核对过的事实：APK 里没有正式公钥（`android/app/src/main/assets/update/keys.json` 不存在，`UpdateKeys.load` 会失败）；`update.mirror` 和 `update.sources` 默认为空，代码里没有内置地址，所以真实构建不会发更新请求。设备上是 0.1.13/1013，`DEBUGGABLE`，debug 证书，v2 签名。设备上没有 `thinkos-updater`，也没有恢复守卫。
- 跑过的：Python 194 项通过；`system/test/run-updater.sh` 44 项，第一次跑失败 1 项（`test_pm_uses_real_fixed_java_prefix…`），隔离重跑 3 次和整套重跑都通过，判断为时序不稳；JVM 测试结果 939 项 0 失败 1 跳过（更新、目录、仓库相关 220 项，没有重跑 Gradle）。
- 没做的：没有逐行审密码学代码，见 OTA-6。
- 缺口对应的任务：OTA-5（端到端演练）、OTA-6（独立复核）、MOD-1 修不稳定的测试、OTA-4 补充（文档）、REG-2b（剩余真机验收和单元测试）、REG-1 的签名方式（DEC-12）。

#### OTA-0 上机验证（不入库的原型）

2026-11-04补充：第1项已在HUA0BWUT实际通过，应用UID10074（既有Device Owner）提交会话218872014、507508005，分别将1001/0.1.1升级为1002/0.1.2、1003/0.1.3。没有确认输入、候选包未通过shell/root安装、没有重启；实际APK摘要、新ART进程、MY_PACKAGE_REPLACED自动前台、Lock Task、HOME和升级前的数据均已核对。两次测试的应用回调未落盘，原自动观察器因此失败，原始失败保留；独立平台会话/新包/进程/文件观察在还原前完成。第二次明确看到回调进程刚启动就被后续pkg removed结束，正式实现不能仅等回调或因此重复安装。证据 `artifacts/update/OTA-0/self-update/`、`self-update-callback/`。完整7项仍未完成，性能按所有者要求后置。

和正在用设备的人错开。结论写回 `34` 号文档，把“未验证”改掉。

第4项功能冒烟已通过（2026-11-04）：0.1.3/1003非debuggable release在真机16项通过，自动返回前台、原生Runtime/Lua、冷启动功能、Device Owner、Lock Task和Home均正常。debug/release同一本地证书，双向覆盖未卸载；外围12份文件和投屏身份还原，5条音量流全0。证据 `artifacts/update/OTA-0/release/`。首次观察器错误地要求API27的dumpsys列出持久表，失败保留；也修复了重复注册会积累HOME规则的问题，实际只读核对最终只有一条。没有跑启动耗时/帧率性能测试，没有生成或迁移正式证书。

第2项能力验证已通过（2026-11-04）：应用UID/Device Owner实际以setAllowDowngrade将debug 1003降到1002（19项）；shell pm -r -d的debug降级观察11项、非debuggable release间降级9项均通过。完成后均覆盖回正常1003，并还原12份文件、投屏身份，音量流1–5全0。源版本没有随测试降回0.1.2。证据 `artifacts/update/OTA-0/downgrade/`。两次提交前原型错误和首次shell观察器错误均保留；shell会话的appPackageName可以是null，sizeBytes是含本机库展开的估值，已改成用新会话ID/UID/标志与实际APK版本/摘要结合判断，并补负例测试。尚未实现生产回滚引擎，也未做故意崩溃三次的回滚；正式证书仍未迁移。

| # | 验证 | 不成立时 |
|---|---|---|
| 1 | Device Owner 静默自更新：`PackageInstaller` 会话提交后不弹框，进程被替换，`BootReceiver` 拉起，Lock Task 恢复，默认桌面还在 | 重新设计安装方式，先报告 |
| 2 | 降级：`setAllowDowngrade`（隐藏）和 `pm install -d` | 回滚只靠模块守卫 |
| 3 | 中断时长：dexopt、进程替换、重启冷启动各多少秒 | 写进 §4.4 |
| 4 | `assembleRelease` 的包能不能正常跑（设备脚本依赖 `run-as`，要另写冒烟） | 先修 |
| 5 | 一周内不同时段的下载速度：GitHub Release、raw、jsDelivr、`gitee.com`、`registry.npmmirror.com`，在设备上量 | 调候选源顺序 |
| 6 | 守护进程里调 `magisk --install-module`、改 Vector 数据库（**人工或所有者明确授权**） | 重新设计 MOD |
| 7 | 故意做坏模块看启动守卫（**有变砖风险，要 OWN-4**） | — |

验收：

- [ ] 7 项都有结果和证据。第 1 项不成立时不进入 OTA-3。

#### OTA-1 发布基础

2026-11-04补充：第1项已实现，版本名统一来自android/gradle.properties的thinkos.version；0.1.1对应1001，已正常更新安装到真机，同一debug签名与数据保持。Gradle实际验证坏输入拒绝和Int边界；正式钥匙/签名迁移等其余项仍未完成。证据 `artifacts/update/OTA-2/packages/`。

2026-11-04后续：正常版本已随实际自更新升到0.1.3/1003。第2/6/9项的本地实现已完成并上机：签名配置缺失时双构建共用debug证书且警告，非法配置失败；既有Device Owner持久HOME只保留自身一条规则；独立release_smoke.py不用私有数据/调试接收器/root，非debuggable release真机16项通过。证据 `artifacts/update/OTA-0/release/`。这些是正式钥匙缺失时的本地构建验收，OWN-3/OWN-6、正式签名配置的有效钥匙分支、迁移和其它条目仍待办。

| # | 内容 | 位置 |
|---|---|---|
| 1 | 版本号：`versionCode = 主 × 1000000 + 次 × 1000 + 修订`，`versionName` 同源。已由原`2 / 0.1.1`改为`1001 / 0.1.1`；版本名来自gradle.properties，单调递增 | `android/app/build.gradle.kts` |
| 2 | 签名：读未跟踪的 `keystore.properties`；缺失时回退 debug 证书并警告；debug 和 release 都用正式证书 | 同上；`.gitignore` 加 `keystore.properties`、`*.p12`、`*.key` |
| 3 | 钥匙脚本 `tools/gen_keys.sh`：生成 keystore 和三把 P-256（通道、系统、仓库）加冷备，只输出公钥，私钥写到所有者指定的离线路径。**所有者运行** | `tools/` |
| 4 | 公钥文件 `android/app/src/main/assets/update/keys.json`（通道、仓库）和 `android/app/src/main/assets/update/system-keys.json`（独立系统根）；由所有者按 `tools/OWNER-RELEASE.md` 原字节部署，调试测试根不替代正式根 | `android/app/src/main/assets/update/` |
| 5 | `tools/release.sh`，流程见 `34` §4.9，包括 LIC-04 的附件。CI 只跑测试，不碰钥匙 | `tools/` |
| 6 | Device Owner 下 `addPersistentPreferredActivity`，把 ThinkOS 固定为 Home | `shell/KioskPolicy.kt` |
| 7 | 数据版本号：设置和插件状态带版本；同一次版本内不破坏旧格式 | `settings/`、`plugin/` |
| 8 | 救援菜单加“移除设备管理权限”（`clearDeviceOwnerApp`，现在只有 debug 的 `kiosk_surrender`），要确认 | `MainActivity.kt`、`KioskPolicy.kt` |
| 9 | `tools/tests/release_smoke.py`：不用 `run-as` 和 `DebugReceiver`，只靠 `am`、`dumpsys`、`logcat`、截图 | `tools/tests/` |
| 10 | 证书迁移手册，步骤见 `34` §4.8。**人工，要 OWN-6** | `docs/07-build-deploy.md` |

`34` 里“退出专用设备模式”的说法按 DEC-6 改。

验收：

- [ ] 正式包在设备上过 `AGENTS.md` 五条。
- [ ] debug 和 release 能互相 `adb install -r`。
- [ ] `adb install -r` 之后按 Home 还回 ThinkOS。
- [ ] 救援菜单能移除设备管理权限，之后可以卸载；重新设置由人工做。
- [ ] 仓库和构建产物里没有私钥：`git grep -n 'BEGIN .*PRIVATE'` 无结果，`keystore.properties` 被忽略。

#### OTA-2 下载与验签

最新运行期检查点（2026-11-04）：9831f52e…已安装，0.1.1/1001。系统设置、单调定时/随机检查/退避、默认网络与可信时间、RuntimeHost真实媒体/投屏适配器/外部前台已接通；控制器的5秒点按时间门禁已实现，实际tos.update权限与点按入口仍缺。真机37项、JVM781（0失败1跳过）、Python103、lint/主包/测试包构建通过。真实视频在64KiB取消传输，暂停与投屏会话/外部设置阻止下载，退出后自动Range续传完整APK并原生验证READY；投屏验证覆盖EngineCastPlayer会话，未重做各投屏协议握手。中文设置横竖屏8项与法律UI32项通过（渲染代码未变），当前仍无正式钥匙与分发地址，默认不发OTA请求；自动安装隐藏，安装器/健康/回滚未实现。证据 artifacts/update/OTA-2/runtime/。

2026-11-04最新工作流检查点：e80baa65…已安装（0.1.1/1001）。认证缓存/ETag、六阶段持久状态和UpdateSession检查→下载→包验证→恢复已实现；JVM767（0失败1跳过）、Python103、Go/lint/构建通过，真机30项含新进程从64KiB恢复完整APK、READY重验、坏缓存/文件撤销就绪与受控环境流取消/恢复。RuntimeHost设置/定时/真实MediaEngine与Cast接线、手动授权/API/界面以及安装回滚仍缺；视频门控项不因此勾完成。证据 `artifacts/update/OTA-2/state/`，下面fded/972f等为历史。

当前补充（2026-11-04）：fdedb2be…已安装，版本0.1.1/1001。UpdatePackageVerifier及downloadVerifiedApk已实现，真机23项含真实完整新版APK/坏证书/错包名/篡改v2/非APK/不支持ABI、HTTP目录下载到原生验证和64KiB强杀续传均通过。JVM745（0失败1跳过）、Python103、lint/主包/测试包通过；状态/缓存/调度与安装仍缺。当前证据 `artifacts/update/OTA-2/packages/`；下面972f/737等为历史检查点。

结果：2026-11-04，整项仍未完成，已安装并开始真机收口。当前APK 972f7f2a…，API27/应用UID实际生产类14项通过：16张CA资源/原生信任管理器（系统135、合并144）、精确JSON/P-256/角色验签、私有序号、真实本地HTTP目录到文件下载、超限/公网明文拒绝、TLS正常/错主机名/陌生CA/过期拒绝，以及强杀应用后不同ART进程从64KiB续传。十文件和投屏身份保留、临时目录清理、既有test包保留、媒体音量0。主机JVM737（0失败1跳过）、Python83、Native189、lint/assemble/测试APK构建通过。正式公钥未部署；UpdateState/缓存/定时/媒体调度、包/签名验证与安装回滚仍缺；真实断电、正式性能和公网源验收未做。证据 `artifacts/update/OTA-2/device-20261104/`。

新包 `android/.../update/`，设计见 `34` §3。

| 文件 | 职责 | 要点 |
|---|---|---|
| `SignedDocument` | 验签再解析 | `Signature("SHA256withECDSA")`；先验原始字节 |
| `UpdateKeys` | 读 `keys.json` | 一把在用，一把冷备 |
| `Fetcher` | 下载 | 新的 OkHttp：连接 15 秒、读取 30 秒；不发 `Accept-Language`、Cookie；`User-Agent: ThinkOS/<版本>`；公网只走 HTTPS，重定向不降级；明文只允许私网（`net/LocalNetworkPolicy`）；候选地址逐个试；1 MiB 分段，`.part` 续传；上限：目录 1 MiB、签名 1 KiB、清单 64 KiB、APK 64 MiB、模块 16 MiB |
| 根证书 | 自带一小份 | GitHub、Fastly、jsDelivr 的根和下一代 |
| `UpdateSchedule` | 什么时候做 | 纯 Kotlin，时间由参数传入，见 `34` §3.4 |
| `UpdateState` | 状态机 | IDLE、AVAILABLE、DOWNLOADING、READY、INSTALLING、FAILED；`seq` 只增不减，落盘 |

全部在 IO 线程、`Thread.MIN_PRIORITY`。不用 `tos.http`。

实际测试入口为 `tools/tests/ota_device.py`；APK验证夹具由 `tools/tests/ota_package_fixtures.py` 在主机生成，通过 `--packages` 交给设备只读验证。目录签名/本地HTTP源由 `android/app/src/testSupport/java/moe/kiwi/thinkos/update/UpdateFixture.kt`、`UpdateTestOrigin.kt` 及androidTest各phase提供，设备组件测试使用设备内回环源；不存在独立的 `tools/tests/ota_server.py`，不需要为该入口搭建主机HTTP服务或 `adb reverse`。各phase的实际范围见 `artifacts/update/OTA-2/`；这些夹具不替代OTA-5真实钥匙/来源演练。

验收（`34` §8）：

- [ ] 断网、无 Server：设备行为和没有这个功能时一样。
- [ ] 下载中拔网、杀进程、断电（断电人工）：恢复后续传，不重下。
- [ ] 坏签名、旧 `seq`、错哈希、超大响应、错设备、低版本：全部拒绝，设备不受影响。JVM 有畸形输入测试。
- [ ] 视频和投屏中不下载。
- [ ] 全程 `clock_accuracy_device.py` 不回归（先告知）。
- [ ] 冷启动 < 3 秒不回归。
- [ ] 请求里没有设备标识：测试台记录请求头，断言。

#### OTA-3 免提示安装

最新功能检查点（2026-11-04）：0.1.4/1004、API10，APK 3293b321…已实际安装，与正常工作区构建一致。生产pending/旧包同步备份、应用UID PackageInstaller、第一行UpdateGuard、健康计时/降级/黑名单、一次性安全模式清除，以及手动install接口/原生按钮已实现。真机实际三个新版进程在Application中崩溃，第四次只降级一次，旧包摘要/黑名单/DO/HOME/Lock Task和数据核对通过（25项）；修复安装后实际完整等待600秒，自动退出安全模式并重启正常（27项）。该两项专用instrumentation驱动生产安装器，不能据此宣称所有真实来源/UI路径已验收。最终同一debug包94项通过（更新组件/安装事务/运行期45、入口16、法律33），最新非debuggable release另16项通过；JVM817（0失败1跳过）、Python110、JNI/Lua桥34、Go契约、双variant lint/构建通过。第三方声明纯文本、标题一次，5份许可原文资源不变。12份原文件/投屏身份/原页面和环境还原，音量流1–5全0、音效关闭、safeMode=false，开发覆盖为空、HOME规则一条。首次重复栈计数和第四进程时区初始化问题、一次release测试前置条件失败均保留原记录并已修正重验。安静时段自动安装运行期、一天结果提示、正式钥匙/来源、无DO确认流程上机、模块更新器仍缺，自动安装保持隐藏，整项不勾；性能后置。证据 `artifacts/update/OTA-3/install/report.md`、`qualification.json`、`final-audit.json`。

前一功能检查点（2026-11-04）：0.1.3/1003、API10，APK f2e1f477…已实际安装。同一最终包88项真机通过（更新组件/续传/运行期39、更新入口16、法律33）；JVM798（0失败1跳过）、Python109、Native194、双variant lint/构建通过。第三方声明以纯文本显示，已移除正文重复一级标题，5份许可原文资源字节不改。12份原文件、投屏身份与原页面/方向/语言还原，音量流1–5为0、音效关闭、safeMode=false，无开发覆盖和当前插件隔离；Device Owner/Lock Task/HOME正常，持久HOME仅一条。该检查点当时仍缺生产安装器/健康/回滚；后续实现见上方最新记录。证据 `artifacts/update/OTA-0/report.md`、`notice-final/`。

API与恢复页初始检查点（2026-11-04）：11dac015…已安装，0.1.1/1001、API10。tos.update.status/open/check/download、权限过滤事件、真实点按信用和原生更新页已实现；声明system.update仍不能让开发副本获得权限。设置实际点击、插件全停、真实安全模式下恢复入口均通过，12份文件还原且safeMode=false。新包87项真机检查（更新39、入口16、法律32）；JVM797（0失败1跳过）、Python105、Native194、Go/lint/构建通过，音量0。当前无正式钥匙/来源，页面显示更新暂不可用；install方法/按钮没有开放，生产安装/健康/回滚与hub、正式引导仍缺。证据 artifacts/update/OTA-3/api/。

范围说明：第1/2/3项已有上述生产代码与主要真机路径；回滚失败等支路和真实来源/界面端到端仍需补验。第6项已含install方法与原生按钮，沿原权限/真实点按门禁；第7项原生检查/下载入口可用，完整修复安装主要由本轮专用instrumentation验证。第4项运行期、第8项和第9项无DO真机确认，以及自动安装验收仍待做；不能据这些局部证据勾完整OTA-3。

设计见 `34` §4.3、§4.4、§4.6。

| # | 内容 |
|---|---|
| 1 | `PackageInstaller` 安装；写 `update/pending.json`；保存 `previous.apk` |
| 2 | `UpdateGuard`：`ThinkApplication.onCreate` 第一行，比 `CrashRecovery.install` 和加载原生库都早；`attempts` 落盘；健康条件：首帧、目录发布、连续 10 分钟无崩溃；`attempts > 3` 回滚，失败进安全模式；版本进黑名单 |
| 3 | 修复版装上后自动退出安全模式（`CrashRecovery` 现在是粘性的） |
| 4 | 安静时段（纯 Kotlin，有测试）：3–6 点；连续 3 天等不到放宽到 ASLEEP ≥ 1 小时；最近 10 分钟没触摸；没有媒体和投屏；设置页和引导没开；没打开外部 App；空间 ≥ 3 倍 APK。时间不可信时只看 ASLEEP |
| 5 | 系统设置（`system.json`、11 种语言，分类“系统”）：`update.enabled`（自动检查和下载）、`update.auto_install`（自动安装更新）、`update.channel`（更新通道）、`update.mirror`（镜像地址，高级）、`update.check_hours`、`update.install_hour`（隐藏）。默认值见 `34` §5.8 |
| 6 | 接口：`tos.update.status()`、事件 `update`、`check()`、`download()`、`install()`，权限 `system.update`，后三个只能在点按后 5 秒内调用 |
| 7 | 救援菜单加“检查更新”，原生，不依赖插件；安全模式里也能用 |
| 8 | 装完后设置页和操作栏显示“已更新到 0.3.0”和更新说明，保留一天。同一版本失败 3 次不再自动试，显示原因 |
| 9 | 没有 Device Owner 也没有更新器时，manifest 要有 `REQUEST_INSTALL_PACKAGES`，走系统确认框 |

更新相关文案按 §3.2：开关不带说明；状态只写结果，比如“已是最新版本”“已更新到 0.3.0”“安装失败”。

验收（`34` §8）：

- [ ] 开着“自动安装更新”：不碰设备，在安静时段自动装完，没有任何弹窗。
- [ ] 关着：有提示，一点就装，也没有系统确认框。
- [ ] 静默安装后：Device Owner、Lock Task、默认桌面（按 Home 验证）、设置、插件状态、收藏都还在；操作栏显示“已更新”。
- [ ] 新版启动即崩（测试包）：3 次后回到旧版，这个版本不再下载。
- [ ] 安全模式下能检查并安装修复版，之后自动退出安全模式。
- [ ] 视频和投屏中不安装。
- [ ] 冷启动和时钟度量不回归（先告知）。

#### OTA-4 改文档

按 `34` §7 的表逐行修订：ADR-015、ADR-018 第 2 条、`05-protocol.md` §11、ADR-033、`system/README.md`、`03-architecture.md` §4.3、`05-protocol.md` §3.2、ADR-035、`06-plugins.md` §6.3、`07-build-deploy.md` §8、`19-provisioning-for-agent.md`、`AGENTS.md`。`34` 和 ADR-041 的状态从“草案”改成实际情况。

审计补充（2026-11-05）：

- ADR-041 的状态改成实际情况；`docs/34` 文件头的八段“最新检查点”收成一张状态表（任务、已实现、真机验证到哪、缺什么），旧检查点留在 `artifacts/` 的报告里。
- OTA-1 第 4 项的公钥路径改为 `android/app/src/main/assets/update/keys.json` 和 `system-keys.json`（以 `tools/OWNER-RELEASE.md` 为准）。
- OTA-2 的测试台 `tools/tests/ota_server.py` 不存在，实际用的是 `ota_device.py` 和 `ota_package_fixtures.py`，本文改成实际名字。
- `tools/OWNER-RELEASE.md` 提到的 `OWNER-PUBLISH.md` 是 `tools/release.py` 在准备目录里生成的，不在仓库里，文档里写明。

验收：

- [x] 表里每一行都改了。

结果：2026-11-05，通过文档修订范围。已逐行对照34 §7九组位置，纠正独立签名分发、来源隔离设计边界、静默资格、公开权限、发布准备与正式新机路径。AGENTS只增加构建/分发事实，全部原权限与硬约束字节保留；本目标OTA-4明确列出其修订，未据此扩展任何设备操作。时间分支按高优先级36统一，未改策略代码。逐行证据 `artifacts/update/OTA-4/table-review-20261105.md`；不等于正式OTA/模块/Server对齐功能通过。
- [x] `34` 文件头不超过 10 行；上面四条改好。

结果：2026-11-05，通过本子项。状态表10行，旧头留档，ADR-041、公钥双路径、实际夹具与OWNER-PUBLISH生成说明已按源码纠正。见 `artifacts/update/OTA-4/docs-status-20261105.json`；§7全表与AGENTS仍待逐项收口。

#### OTA-5 端到端演练（真实钥匙加局域网镜像）

2026-11-05演练工具增量：已实现实际候选APK的只读v2兼容phase（完整执行中APK身份/classloader/6正式roots绑定、18条内存向量，无真实arm/commit/crash）与独立owner-debug材料producer；正常公开来源CHECK/DOWNLOAD/真实INSTALL/新PID真实600秒观察runner已落源码，HUA/别名拒，默认plan不访问ADB。42项材料/前置/Origin/负例与13项UI/schema/layer测试、全Python413、JVM debug1138/release1126（各1旧skip，零失败/错误）、双lint/构建通过。修正实际settings合法小数被catalog整数parser误拒，两固定层专用finite parser，不放宽catalog/ledger。未用正式钥匙/备用机执行，兼容receipt fixtures为合成输入；自动/完整负例/crashorigin与真实回滚仍待，见`artifacts/update/OTA-5/runner-preparation-20261105/qualification.json`及`tools/tests/OTA5-UI.md`。

2026-11-05固定签名语义负例增量：独立`tools/ota_negative.py`已实现，8项真实一次性PKCS12/P256/APK签名及CLI回归通过。仅从重新认证的APK-only正常LAN材料生成通道不符/包摘要不符两类；默认plan无私钥/口令/签名/指定输出/socket，显式执行绑定审核输入摘要，正常release/Origin规则未改。摘要负例可能推进seq，后续正常目录必须更高，不清账本。未用正式钥匙或设备，owner-debug crash v2与完整演练runner仍待，见`artifacts/update/OTA-5/semantic-negative-20261105/qualification.json`。

2026-11-05前置工具增量：新增`tools/tests/ota5_device.py`，以真实catalog角色验签、实际SDK/ARM32与owner公钥导出绑定已保存的production_roots结果；默认plan无ADB，显式preflight也仅固定只读备用机诊断，拒HUA及其别名。新增9项真实一次性签名/SDK边界和完整Python353项通过；test-only正式根加载phase已编译，未在真机执行。它不证明当前账本静止、不启动origin/instrument、不授权安装；完整来源UI、健康/自动、负例/crash与回滚runner仍待。证据 `artifacts/update/OTA-5/preflight-20261105/qualification.json`。

2026-11-05来源演练准备：新增只读固定LAN fault origin，16项真实socket验证目录/签名1字节破坏、超限声明、一次64KiB断流后真实Range拼接摘要、已命名白名单与敏感header脱敏；没有HTTP控制/签名端点，不接设备/钥匙。它依赖上游ota_lan认证输出，不能把调用者自造hash表当签名权威；任意无标签serial不能普遍识别。完整生产来源driver和正式负例/crash材料仍待，见 `artifacts/update/OTA-5/origin-20261105/qualification.json`。

2026-11-05主机实现增量：新增 `tools/ota_lan.py`，真实已签release材料校验后只改LAN URLs/seq并用独立channel根重签；模块7行清单和system签名原字节保留。私有FD快照使SDK身份/验签/摘要/复制绑定同字节，真实源APK交换与FIFO替换均拒绝；实际Vector包正反例和完整CLI已验。20项专项与完整Python288项通过，未读取正式钥匙、未接设备或发布；完整设备runner/正式拒绝样本/显式crash材料类型仍待Agent实现，不勾OTA-5。证据 `artifacts/update/OTA-5/lan-preparation-20261105/qualification.json`。

等人工：OWN-6正式钥匙与证书迁移、本地正式release签名、无Device Owner备用机；已写[步骤与检查](../artifacts/update/OTA-5/human-steps.md)。完整LAN设备runner、坏版本来源演练仍属Agent待实现，不能以人工步骤替代。

审计时发布链路没有用真实钥匙跑过，本项补上这一次。不需要先定公开地址（OWN-5、OWN-9）：局域网明文镜像本来就允许（`net/LocalNetworkPolicy`，`UpdateRuntime` 里写明局域网镜像可用）。

| # | 内容 | 谁 |
|---|---|---|
| 1 | 运行 `tools/gen_keys.sh`（步骤见 `tools/OWNER-RELEASE.md`），把 `keys.json`、`system-keys.json` 原字节放进 `android/app/src/main/assets/update/`；完成证书迁移（OWN-6，`34` §4.8，设备管理权限先放弃再重设） | **人工** |
| 2 | 构建 debug 和 release，确认两个包里都带这两份资产；设备上 `UpdateKeys.load` 成功（日志或诊断） | Agent |
| 3 | `tools/release.sh prepare` 本地准备相邻两个版本（N 和 N+1，同通道，`seq` 递增），产物放仓库外；读私钥和口令，所有者运行 | **人工** |
| 4 | Mac 上用只读静态服务器把准备目录放到局域网地址；设备 `update.mirror` 指向它（局域网明文，只在演练期间） | Agent |
| 5 | 正常路径：设备装 N，检查、下载、验证、免提示安装、重启后健康、显示“已更新”；Device Owner、Lock Task、默认桌面、设置、插件状态保留 | Agent |
| 6 | 拒绝路径（目录和包各改一处）：改一个字节、旧 `seq`、系统钥匙签的通道目录、错通道、包名或证书不符、超大响应、断流后续传。设备都不受影响 | Agent，复用 `ota_package_fixtures.py` |
| 7 | 自动安装：`update.auto_install` 开着，安静时段条件满足时自动装完，没有弹窗（能注入时间或 ASLEEP 就注入，不能就等到 3 点；时间不可信时只看 ASLEEP） | Agent |
| 8 | 没有 Device Owner 的路径（`OTA-3` 第 9 项，走系统确认框）：备用机上做，没有备用机就写 `等人工` | 备用机 |
| 9 | 回滚：N+1 做成启动即崩的包（`OTA-3` 已有的 instrumentation，debug 变体加正式证书签名），经真实目录来源装上，3 次后回到 N，N+1 进黑名单，不再下载 | Agent |

验收：

- [ ] 第 2、5、6、7、9 项在真机上有证据，放 `artifacts/update/OTA-5/`；没做的写明原因。
- [ ] 全程没有推送、没有 tag、没有 Release；设备上装的包与准备目录的 `SHA256SUMS` 一致。
- [ ] 请求头里没有设备标识（沿用 OTA-2 的断言）。
- [ ] 演练后恢复：`update.mirror` 清空，记录当前证书状态；不跑性能测试。
- [ ] 通过后 `docs/34`、`docs/02` 里对应的“未验证”改成实测。

#### OTA-6 独立复核（第一次对外发布前必做，只读）

由没写过这些代码的 Agent 会话，或所有者找的人来做。**不改产品代码**，只出报告 `artifacts/update/OTA-6/review.md`：每条发现写文件和行号、触发步骤、严重度、建议；修复另开任务。可以新增畸形输入测试，放在测试目录。

| 范围 | 重点 |
|---|---|
| 验签（`SignedDocument`、`UpdateKeys`、`UpdateModels`） | 先验后解析；DER 格式与算法固定；通道、仓库、系统三类钥匙不能互换；`seq` 和 `code` 防回退在什么时候落盘；时钟不可信时的行为 |
| 包验证与下载（`UpdatePackageVerifier`、`Fetcher`、`UpdateHttpClient`） | 包名、版本单调、签名证书与已装一致、ABI、zip 路径与大小；下载完成到安装之间文件被换掉；公网只走 HTTPS、重定向不降级；私网明文的判断（非 `10.x` 的私网段、IPv6 ULA、域名解析到私网）；候选源回退不能绕过验签 |
| 安装事务（`UpdateInstaller`、`UpdateInstallStore`、`UpdateGuard`） | 任意一步崩溃后能恢复；`pending.json` 与 `previous.apk` 损坏时守卫会不会让设备永远卡在安全模式或循环回滚 |
| 系统层（`system/thinkos-updater/src`：`updaterd.c`、`module_verify.c`、`guard_policy.c`，守卫脚本） | `SO_PEERCRED` 加 `packages.list` 的判定（UID 复用、包名伪造）；`SCM_RIGHTS` 传来的文件先复制再验；`execve` 固定参数；id 白名单；`micro-ecc` 固定提交；整数溢出和超长行；`post-fs-data.d` 脚本的路径与权限 |
| 插件仓库（`RegistryRepository`、`RegistryPolicy`、`RegistryModels`，`tools/registry*.py`） | 确认记录先于执行；权限增加的判断；吊销；试用回退；`registry-state.json` 被改后的行为；Lua 侧有没有绕过原生确认的路；Git 对象读取、符号链接、大小写冲突、子进程限制 |
| 发布工具（`gen_keys.py`、`release.py`、`release_tools.py`） | 私钥和口令会不会进日志、命令行或仓库；CI 里拒绝钥匙操作 |

验收：

- [x] 报告写完；每个严重和高的发现有对应的修复任务，或写明不修的理由并经所有者同意。
- [x] 报告写明没看的范围。没有发现问题不等于没有问题。


结果：2026-11-05，独立只读复核已完成，范围与未看项见 `artifacts/update/OTA-6/review.md`；三项发现分别跟踪如下。F1/F3源码修复及主机证据见 `artifacts/update/OTA-6/fixes-host.json`，F2仍开放。此处不验收真实钥匙/完整OTA或发布。

#### OTA6-FIX-1 吊销回退与认证缓存中断

- [x] 所有仓库激活与回退共享精确id+digest吊销门禁，吊销/不可用previous保留候选和证据并移除运行实例。
- [x] 已接受ledger但认证cache缺/坏/落后时暂停仓库激活；重获有效目录才恢复，正常有效缓存继续离线启动。
- [x] 10项事件驱动JVM回归通过（重建仓库实例、ledger/cache写入中断与恢复）；真实600秒试用未缩短。

结果：2026-11-05，源码、JVM及独立复核通过，包含在0.1.16；`followup-2.md` 和 `fixes-host.json`。真机全生命周期仍归REG-2b，不以这里的JVM替代。

#### OTA6-FIX-2 损坏APK事务的原生恢复

2026-11-05中文窗口实机增量：F030独立UID、已装修订test416db8…完成真实竖/横READY与按钮完整可见、ACTION_CLICK负例、一次实际触摸隔离、4项原生+4项外层检查。只操作自有cache损坏样本，真实update/registry/native等历史保持，token/reverse与临时唤醒保持均清理，五流0。已确认此前首触失败/表盘漂移来自实际ASLEEP自动角色切换，非外部操作的证明；原失败保留。完整生产Runtime准入/重开与后续健康退休仍待，不勾整项，见`artifacts/update/OTA6-FIX-2/ui-030-20261105/qualification.json`。

2026-11-05窗口准备增量：真实Settings过渡已消除独立包instrumentation冷启动被原锁定模式拦截的问题；旧探针在READY前因异步自有媒体状态检查失败，未点隔离。已修为只读等registry/v3提交、同Main验证并Stop，构建及修订test416db8…安装完成，尚未实机验证修订窗口，不计通过。原应用层已真实Back恢复，管理/静音保持；随后外部页面/表盘变化照现值保留，见`artifacts/update/OTA6-FIX-2/ui-setup-030-20261105/qualification.json`。

2026-11-05实际API27增量：F030独立UID验证真实updater不存在、ATOMIC_MOVE保持inode/fileKey、目录fsync和文件原字节；正式UI/同PID、两包耐久历史/权限/静音保持，自有cache清理。源码已随030安装，旧024未安装说明为历史；真实中文窗口、生产Runtime准入/重开与健康退休仍待，见`artifacts/update/OTA6-FIX-2/api27-030-20261105/qualification.json`。

- [ ] 合入并验收保留损坏证据、拒绝并发覆盖、不误回滚、保持history/failure/seq的原生恢复流程。
- [ ] 正常设备、未知root/未决会话、隔离中断和后续健康退休记录均有对应验证。

结果：2026-11-05，**0.1.24/1024生产已整合并编译，尚未安装**。原生恢复入口、固定JNI探测、双锁/损坏原件归档与历史保护、健康ACK同锁退休已接入；22项针对性JVM（文件12/策略5/真实Controller取消5）通过，完整1057项0失败/错误（1旧skip），Python214、共享Go契约、debug主包/测试包/lint/插件校验通过；只含armv7，APK manifest min/target27，新JNI符号已打入。首次两项样本notes不合法与异常身份断言失败均保留，未放宽生产schema。真机ADB清单空且USB未识别HUA，已知地址无路由：API27实际socket/文件系统、真实中文触摸/横竖与后续健康退休未跑，不冒称实机通过；本机最后确认安装为0.1.23。独有cache组件探针和外driver已编译/语法检查，只用于接回后验收，不改真实更新事务；两项保持开放。证据 `artifacts/update/OTA6-FIX-2/0.1.24/qualification.json`。

候选包更新：F2源码继续包含于待装0.1.26，后续实际API27/窗口/健康退休验证仍开放；本轮新增模块功能不替代F2验收。

#### OTA6-FIX-3 备用机安装边界

- [x] 安装器必须显式选择备用机，拒绝HUA0BWUT、映射到其真实串号的ADB别名及未知身份，全部早于构建/推送/安装。
- [x] 4项Fake CLI回归通过，原脚本在隔离环境执行；不读取环境默认选主设备。

结果：2026-11-05，源码、主机与独立复核通过，`system/install-updater.sh`、`tools/tests/test_updater_spare_target.py`、`fixes-host.json`。未在主设备执行真实安装器，不改变人工安装/重启边界。

## 8. MOD：系统模块

设计见 `34` §4.5、§4.6。源码、构建、主机测试归你；持久安装由人工做。

#### MOD-1 更新器

等人工：正式系统根与OWN-4备用机的更新器/外置守卫部署、重启；见[部署前置与回滚](../artifacts/system/MOD-1/human-steps.md)。HUA0BWUT不装更新器/守卫；原不稳定测试的确切因果仍待Agent查明。

2026-11-05主机增量：现有44项完整host套件连续10次全部通过（440项），假执行器显式禁用ambient Python site/.pth，并逐子进程验证；首次单次STATUS仍严格验证真实code/active及PM+SQLite共享1800ms，不预热、不sleep、不重试。加入fixture前置与失败诊断。原审计首次失败的确切成因仍未证实，因而下方因果收口待办保留；未更改生产预算/设备模块。证据 `artifacts/system/MOD-1/host-20261105/qualification.json`。

2026-11-04开发与主机检查点：守护进程、固定七id、私有事务/备份/防回退、原始文本P-256验签、语言与时区setup命令已实现。统一26项host集成、8项OpenSSL交叉验签/畸形输入/SHA边界（ASan/UBSan）及纯C守卫策略通过；API27 armv7生产包已构建。正式公钥仍为0，分发事务拒绝；没有安装到设备，不把host假执行器当真机升级。证据`artifacts/system/MOD-1/host-20261104/`。

| # | 内容 |
|---|---|
| 1 | `system/thinkos-updater`，结构照 `system/thinkos-cast`：C 守护进程、`service.sh`、`customize.sh`、`module.prop`、主机测试 |
| 2 | 抽象 socket `@thinkos-updater`；`SO_PEERCRED` 加 `packages.list`，只认 root 和 ThinkOS；一行 ≤ 256 字节，2 秒读完；文件用 `SCM_RIGHTS` 传，不传路径 |
| 3 | 请求：`HELLO`、`STATUS`、`BEGIN`、`STAGE`、`INSTALL_APK`、`REBOOT`、`HEALTHY`、`ROLLBACK`，回答见 `34` §4.5。没有“执行命令”，固定程序、绝对路径、`execve` |
| 4 | 验签：先验再解析；只解析已验证的清单文本；不解析 zip，只比 sha256；vendor `micro-ecc`（BSD-2-Clause，固定提交）和一份 SHA-256；登记到 `THIRD_PARTY_NOTICES.md` |
| 5 | id 白名单编译进去：`thinkos-updater`、`thinkos-cast`、`thinkos-immersive`、`thinkos-shell`、`thinkos-certs`、`thinkos-power`、`timesync`（CERT-4）。防回退记录在 `/data/adb/thinkos/` |
| 6 | 把 `timesync` 收进 `system/`：源码现在只在 `~/ThinkSmartView/timesync-v1.0.zip`，取不到就向所有者要 |
| 7 | 模块构建和系统钥匙签名进 `tools/release.sh`；安装脚本写好，**人工运行**，要 OWN-3 |

验收（`34` §8）：

- [x] 主机测试：坏签名、别的钥匙、旧 `code`、不在白名单的 id、超大文件、畸形清单全部拒绝；验签结果和 `openssl` 一致。
- [ ] 模拟被攻破的应用，对 socket 发任意内容：装不了未签名的东西；别的 uid 得到 `ERR denied`；重启被限速。
- [ ] `system/test/run.sh` 通过；产物只有 armv7。
- [ ] 修不稳定的主机测试（2026-11-05 审计）：`test_pm_uses_real_fixed_java_prefix_and_private_classpath_for_every_operation` 在整套第一次跑时失败过一次（`module_status('thinkos-immersive')['active']` 为 False），隔离重跑 3 次、整套重跑都通过。找出时序依赖，改成等待明确条件而不是靠运气；连续整套跑 10 次全部通过。

#### MOD-2 模块 OTA

2026-11-05实机增量：026当前包在真实无updater/WFD条件下，中文横竖原生“组件状态未知”窗口、false→true公开启用落盘前拒绝、取消/Back与普通更新无错误READY，6项通过且截图已目视。原true通过既有debug schema入口仅作本次测试撤销，不算公开启用或安装成功；另两个cast开关不写，原local语义/server与身份字节/UI/管理/同PID/静音恢复。未伪造认证目标、未安装模块/重启，不勾全MOD-2。证据 `artifacts/update/MOD-2/0.1.26/unavailable-ui-1/qualification.json`。

等人工：OWN-4备用机、正式根与模块签名、持久部署及有风险的坏模块演练；见[模块演练步骤](../artifacts/update/MOD-2/human-steps.md)。坏模块驱动和实际模块安装/重启/健康证据仍未完成。

2026-11-05功能接线增量：0.1.26已整合缺Miracast组件原生流程，公开set/setMany/reset提交前拒绝未批准启用且保持原子性；真实已有模块正常启用，原请求revision防迟到检查覆盖新关闭。原生准备只产RAM精确计划及认证缓存，不写普通READY；第二次新鲜物理触屏确认实际APK/全部选中模块后，才持久feature意图并复用现事务。取消未提交计划/失焦/旋转/Home失效，返回已提交安装不取消事务；普通更新重新验共享floor/cache后才放行，失败不沿用旧READY。已补25项JVM及4项真实Bootstrap/JNI返回边界回归，完整1089/268与主机构建通过；初次重复suspend IO包裹编译失败、两例JUnit返回类型错误均保留并修正。**当时仅主机通过；现已装机且无updater中文拒绝窗口6项通过，认证目标/安装触屏与模块重启仍未验**，下列整体验收不勾。证据 `artifacts/update/MOD-2/0.1.26/qualification.json`。

2026-11-04组件界面补验：0.1.9签名目录七项目标的中文横竖屏滚动、底部按钮、ACTION_CLICK不能制造触屏资格通过（host 6项、实际应用4项，零安装包请求）。无更新器公开入口两方向通过；临时无钥匙更新器的真实root库存与生产应用UID逐项吻合，两方向七组件通过，CA为20261020、immersive/timesync为1，另四项未安装。初轮脚本在设置闲置返回、Android oat缓存与重复恢复PID处理处停止，失败保留；分层恢复后已证实进程/自有目录删除、公开库存回unavailable、原时钟/方向/五文件/静音还原。证据`artifacts/update/MOD-2/0.1.9/qualification.json`、`targets-1/`、`inventory-1/`及`inventory-read-only-1/`和各recovery目录。不等于真实模块升级、重启或回滚通过。

2026-11-04独立库存补充：0.1.9/1009已安装（SHA74cc263…），908项JVM零失败（1跳过）、176项Python、211项Lua/JNI及双构建lint/assemble通过。新增与认证更新目标分离的只读inventory、系统设置“系统组件”入口；无更新器真实公开状态已验证为空库存/无法自动更新、无虚构目标与安装资格。Root可读但keys0时的真实库存和中文横竖屏尚待验。管理退出又补了owner已清但自身admin尚未异步移除的分支，只有实际admin inactive且其余清理成功才可提示卸载；21项纯事务故障测试通过，未对本机执行退出。证据`artifacts/update/MOD-2/0.1.9/`，不勾完整模块OTA。

2026-11-04继续开发检查点：0.1.8/1008主包已实际安装，893项JVM零失败（1跳过）、176项Python、210项Lua/JNI、debug/release lint与构建通过。应用已接独立系统清单验签、持久事务意图、APK-only免重启路径、模块自己的重启证据和完整健康期、已确认结果及逐组件原生UI；BEGIN/ROLLBACK发送前退出、外部重启、HEALTHY拒绝和回执身份窗口已补恢复策略。更新器44项host通过，临时API27/armv7程序真实三次读到immersive1、CA20261020、timesync1及active=true；修复了pm无shebang需直启固定app_process、固定Android环境和Vector正常0660权限的兼容问题。此处仅临时只读状态验证，未持久安装守卫或更新器、未执行模块升级/重启/回滚，也未验收真实七组件中文界面。策略仍来自settings schema，整项不勾。证据`artifacts/update/MOD-2/0.1.8/qualification.json`、`status-0.1.8-dev-3/`。

| # | 内容 |
|---|---|
| 1 | 应用侧 `update/UpdaterClient`；`HELLO` 协商版本 |
| 2 | 事务：`BEGIN` 备份，逐个 `STAGE`，装 APK，有模块变化才 `REBOOT`，新版健康后 `HEALTHY` |
| 3 | 启动守卫：`/data/adb/post-fs-data.d/` 的极小脚本，模块之外；事务开着且上次没走到 `boot_completed` 就恢复备份；用 `resetprop` 不用 `setprop` |
| 4 | 开机 15 分钟内没有 `HEALTHY`：`pm install -r -d` 回旧 APK，恢复模块，重启一次，记录失败。每个事务只自动回滚一次 |
| 5 | `vector-apk`：`pm install -r`，同步 Vector 数据库的 `apk_path`（把 `install-immersive.sh` 的做法搬进来，先备份） |
| 6 | `policy`：`always`、`installed`、`feature`。打开“Miracast”而模块没装时，提示后自动装 |
| 7 | 设置“系统”分类显示系统组件状态：有更新器时列版本；没有时显示“无法自动更新”。怎么装写进文档，不写在界面上 |

验收（`34` §8）：

- [ ] `thinkos-cast` 升级：自动、一次重启、`cast_miracast_device.py` 通过。
- [ ] `thinkos-immersive` 升级：`immersive_device.py` 6/6。
- [ ] 打开 Miracast 而模块没装：提示后自动装好，重启后可用。
- [ ] 坏模块让系统起不来：下一次开机恢复备份。新 APK 一直不健康：15 分钟后回滚。**要 OWN-4，所有者在场。**
- [ ] 新 APK 配旧模块、旧 APK 配新模块都能工作。
- [ ] 没有更新器：APK 的 OTA 照常。
- [ ] 更新器更新自己成功，之后还能更新别的模块。

## 9. REG：插件仓库

设计见 `34` §5。

#### REG-1 仓库与 CI

2026-11-04本地检查点：bare数据校验、原版权法律文件随插件分发、三版保留、不可变blob、tag移动/权限增加审核请求、owner主机签名与无钥匙CI模板已实现；统一12项隔离本地Git/OpenSSL测试通过，证据`artifacts/registry/REG-1/`。不创建远程仓库/PR/issue/Secrets，不生成正式钥匙。§0.2禁止正式钥匙进入CI，优先于34号文档的自动CI签名方案；当前签名方式以DEC-12为准，由所有者主机签原始目录字节，不能把模板当已上线工作流。

先在本地目录做，不推送。建 GitHub 仓库要 OWN-9。

2026-11-05本地实现补充：`scan-tags.yml` 的只读 prepare 自动校验 community并产 unsigned，独立可信main review job绑定同run artifact ID/摘要及固定工具commit，再运行 `registry_review.py` 真正创建/更新权限审核PR与来源完整性issue；fork PR不运行writer。`registry_owner.py publish` 默认plan，所有者审阅输出的 `input_sha256` 后，加 `--reviewed-sha256` 和 `--execute` 一条命令签名/CAS发布，必须显式给 `--expected-dist` 完整SHA或首次 `--initial`；签名前重取验历史，内容A→签名目录B固定immutable地址，推后读回、未知结果不重试。CI仍无签名私钥，community仍不逐项目人工审核。这里只记录本地工具与未启用模板；真实远端PR/issue/签名发布未执行，正式钥匙待OWN-6、仓库待OWN-9，不据此勾完整REG-1或线上发布验收。

| # | 内容 |
|---|---|
| 1 | 仓库结构：`main`（人审的 `plugins/<id>.json` 和工作流）、`dist`（CI 准备 unsigned，所有者主机签名发布）、`ota`（`release.sh` 推） |
| 2 | 校验脚本，复用 `tools/package_plugins.py` 的 `validate_plugin` 和 `luac -p`；bare 克隆不检出，`ls-tree` 和 `cat-file` 读对象；`GIT_ALLOW_PROTOCOL=https`；钩子目录为空 |
| 3 | 仓库规则：id 没被占且不是内置 id；不得声明 `system.*` 和角色；有 SPDX 许可证且允许再分发；每个权限有 `why` |
| 4 | 目录生成和签名：`registry/index.json`、`.sig`、`registry/files/<digest>.json`、`blobs/ab/<sha256>`；每个插件留最近 3 个版本 |
| 5 | 工作流：校验 PR 用只读 `pull_request`，不用 `pull_request_target`；CI 只产 unsigned，可信 main 的独立 review job 开权限审核 PR/来源完整性 issue；签名与 dist 发布只在所有者主机。每天扫 tag：新 tag 且权限没变自动进 unsigned，权限增加开 PR，tag 被移动开 issue |
| 6 | 官方插件作为第一批内容 |
| 7 | 作者文档：`store.json`、`store/`，流程见 `34` §5.3 |

验收：

- [x] 恶意样本全部被拒：符号链接、子模块、`..`、大小写冲突路径、超限文件、声明 `system.*`、声明角色、没有许可证、缺 `why`、Lua 语法错误。
- [x] 校验过程没有执行仓库里的任何东西（测试：样本带钩子和可执行脚本，运行后没有副作用）。
- [ ] tag 被强推不更新；权限增加开 PR。
- [x] 生成的目录用 `openssl` 能验过。
- [x] 签名方式按 DEC-12：`docs/34` §5.2、§5.4 和 `registry/main/.github/workflows/` 改成“CI 只校验并生成未签名目录和 PR；所有者审阅后在本机一条命令签名并发布”；核对现有主机签名命令是否满足，不满足就补。钥匙仍不进 CI。

结果：2026-11-05，通过DEC-12本地文档/模板/命令子项。完整Python268项通过；新增community16、writer13、实际workflow绑定4、owner21项使用隔离HTTP/本地bare/一次性签名夹具，含完整CLI签名/CAS与未知结果拒绝重试。两项首次夹具失败保留并修夹具。真实GitHub写入/正式钥匙/线上发布未执行，整组仍不勾。证据 `artifacts/registry/REG-1/dec12-20261105/qualification.json`。

等人工：OWN-6正式钥匙/证书迁移、OWN-9组织仓库与逐次远端动作授权；具体启用、plan/签名发布、未知结果调查及单调seq撤回见 `artifacts/registry/REG-1/human-steps.md`。未执行。

#### REG-2 设备安装

2026-11-04真实签名安装补充：0.1.8/1008、SHA59a1b857…已通过16项实际UI/文件/日志检查：RAM仓库角色签名目录、来源/权限原生确认、ACTION_CLICK不批准且不下载、真实触屏才安装、Lua VM启动、七个管理API与事件隔离、打开/返回及公开卸载均通过。目录成功刷新误报失败已修复（JNI JSON null不能当Lua错误字符串）；测试夹具runner/自动化连接/自身配置读取及详情返回行在屏幕外的观察问题均保留失败记录后修正。最后5项在同一实际App UID/进程中续验，原16项及17文件原字节恢复/新直播缓存保留的证据见`artifacts/registry/REG-2/0.1.8/qualification.json`；不是一次无失败的全新跑。完整生命周期、权限升级、真实600秒试用、吊销/恶意样本/16上限仍待验，下面整体验收不勾。

2026-11-04局部功能检查点：第三来源、目录/文件签名与摘要、权限原生触屏确认、试用回退/吊销、首帧后启动、Hub已实现；847项JVM（0失败0错误1跳过）、lint/构建通过。已安装0.1.7，真实Hub入口11项通过，包括磁贴/三标签/无配置状态/原生系统更新/目录设置/系统设置入口，证据`artifacts/registry/REG-2/0.1.7/entry-3/`。修复了无配置误报失败、设置入口错误应用key与导航拒绝；试用中叠加升级现拒绝，避免把未健康上一版当已健康。完整签名安装/新增权限/十分钟试用/吊销/恶意样本/16上限真机仍待验，下面整体验收不勾。

| # | 内容 |
|---|---|
| 1 | 第三来源：`filesDir/plugins/registry-state.json`，记录来源、提交、已确认权限、自动更新、上一版摘要。同 id 版本高的生效，一样高用内置 |
| 2 | 目录和文件的获取在 OS（`update/` 内核），IO 线程，只有 `activate` 回插件线程 |
| 3 | 原生确认框：名称、`repo@commit`、档位、逐项权限和 `why`、大小。只接受屏幕触摸。新增权限再确认。全有或全无 |
| 4 | §5.6 的缺口全部补上：仓库插件不得持有角色（CI 和设备都查），顺带校验 `settings` 角色；仓库插件的页面一律按应用页；首帧之后分批启动，上限 16 个；`restoreFactory` 同时停用仓库插件；安全模式忽略仓库插件 |
| 5 | 试用期：更新后 10 分钟内重启、冷却或原生不洁退出，回到上一版，这个摘要不再自动装 |
| 6 | 吊销：`revoked` 命中已安装摘要就停用并显示原因 |
| 7 | 接口：`tos.plugins.catalog`、`installed`、`open`、`install`、`update`、`uninstall`、`setAuto`，事件 `plugins`，权限 `system.plugins`，只给内置副本 |
| 8 | 系统设置：`plugins.auto_update`（`off`、`official`、`all`）、`plugins.community` |
| 9 | 内置插件 `hub`（插件中心）：应用页，标签“发现”“已安装”“更新”；只用 `tabs`、`list`、`text`、`image`；系统更新也放在这里；入口磁贴放 `tiles.apps` |

验收（`34` §8）：

- [ ] 从目录装一个插件：确认框显示来源和权限；不重启应用；断网、关 Server 后照常运行。
- [ ] 新版增加权限：确认前不生效。
- [ ] 新版启动即报错：保留旧版。运行中反复崩溃：10 分钟内回到上一版。
- [ ] 第三方插件声明 `home` 或 `settings`：被拒绝。
- [ ] 装满 16 个后冷启动 < 3 秒、时钟度量不回归（先告知）。
- [ ] 吊销：已安装的被停用并显示原因。救援菜单“恢复出厂插件”清除全部仓库插件。
- [ ] 第三方插件调 `tos.plugins.*` 被拒绝。

#### REG-2b 剩余真机验收与单元测试

2026-11-05验收准备增量（历史，当时尚未安装/运行）：同源码独立 `.regfixture` 双包已真实构建，实际APK API27/armv7 ELF/同签名、无HOME、禁BOOT、固定公钥和普通Kiosk/Recovery链已核；正常026主包摘要未变。主机独立签名origin与单调目录、真实HTTP均已验，8＋9项专项通过。窄生命周期driver保持真实两段600秒、公开Hub/触屏批准、关闭origin后普通冷Main恢复与完整cache/floor保留；20项主机边界及完整Python341项通过，清理失败不能标完成。**当时独立包未安装/未跑；准备证据不代表正式UID/DO/全局手势、Alarm自主或原生不洁通过**；本组实机勾仍保留。证据 `artifacts/registry/REG-2b/fixture-build-20261105/qualification.json`、`fixture-driver-host-20261105/qualification.json`。

2026-11-05独立夹具实机组合：固定026夹具在真实Runtime/VM、APK固定公钥下，v1实际600秒转正后因晚查环形启动日志而保留失败；同进程健康record/VM与签名程序自写store补验不当作stdout。v3公开新增权限取消/确认、真实暂停视频count1/index1及公开停止、即时启动见证和602.153秒健康通过。v4真实执行后关闭Origin、原公开网格起新PID恢复v3；首轮缺预哈希/启动stdout的限制保留。随后同根显式追加正常1.0.5、不清blocked1.0.3，以冷前持久SHA和真实live启动行补测：cache/ledger相等、健康v3及blocked1.0.3/1.0.5保持、冷队列公开停止、原树及owned清理通过。正式App在v1→v3间027升028，夹具始终026；原900经真实schema reset还原、local/server语义/其他8文件字节/原完整树/HOME管理/五流0保持。Allow实际点击0，不称授予存储权限。所有原失败未改，组合complete仅此独立功能范围；正式UID/DO、原生不洁/自主恢复、恶意/吊销/16/性能等仍待，下列整体验收不勾。证据 `artifacts/registry/REG-2b/lifecycle-20261105/qualification.json`。

审计时 REG-2 的 16 项真机检查已通过（0.1.8，内存里的测试目录），它自己记录的缺口如下。不需要正式钥匙，沿用 0.1.8 的夹具。

- [ ] 真实 600 秒试用：更新后 10 分钟内重启、冷却或原生不洁退出，回到上一版，这个摘要不再自动装；10 分钟无事则转正。
- [ ] 恶意清单和损坏 blob（符号链接、`..`、超限文件、摘要不符）：设备拒绝，已装的不受影响。
- [ ] 装满 16 个仓库插件：冷启动 < 3 秒（`tools/tests/pre_m1_cold_start.py`），时钟度量不回归（先告知）。
- [ ] 安全模式忽略仓库插件；原生不洁退出路径。
- [ ] 逐项对照 REG-2 的验收清单，没有证据的补证据：新增权限确认前不生效、第三方声明 `home` 或 `settings` 被拒、吊销停用并显示原因、“恢复出厂插件”清除全部仓库插件、第三方插件调 `tos.plugins.*` 被拒。
- [x] `RegistryRepository` 的 JVM 单元测试：假时钟和假文件系统；确认记录先于执行；权限增加要重新确认；试用中拒绝叠加升级；重启中断则回滚；吊销命中；16 个上限；状态文件损坏时降级为没有仓库插件，不崩溃、不卡启动。

JVM补验（2026-11-05）：Repository17项＋Policy7项零失败，完整1064项0失败/错误（1旧skip）。真实公共catalog/mutate/start配受控文件接口与隔离临时目录，确认拒绝/挂起不下载不执行、确认集合先落盘、权限重新确认、15→16→拒17、坏状态零写且保留版本目录、试用叠加/重启回滚/吊销均有回归；最小Environment时钟默认仍为真实delay/System.currentTimeMillis，虚拟599999/600000验证不改600s设置，不能替代实机两段真实600秒。源码已随待装0.1.25构建；设备仍离线，这里只勾JVM项，整组和性能项不勾。证据 `artifacts/registry/REG-2b/jvm-20261105/qualification.json`。

证据放 `artifacts/registry/REG-2b/`。通过后更新 REG-2 的勾选和 `docs/34`。

#### REG-3 社区档

`community` 档：CI 扫 GitHub topic `thinkos-plugin`，自动校验后列入，设备默认不显示。吊销演练一次。

验收：

- [ ] `plugins.community` 关着时看不到；开着时有明显的档位区分。
- [ ] 吊销流程走通，写进作者文档。

## 10. WEL：新用户引导

设计见 `docs/35-onboarding.md`。文案以本节为准，`35` §4.4、§4.7 的文案作废，做 WEL-1 时把文档改对。

#### WEL-0 上机验证和效果图

结果：2026-11-03，首屏与软件更新屏的原生真机效果图已准备，11语言×竖横屏共44画面文字边界通过；真实系统语言/RTL27项、跨UTC偏移系统时区24项通过（UTC+14/−11跨日期、可见时钟/太阳时刻、真实广播、进程保留）。1.3倍系统字号48画面已采集，中英阿语/竖横屏；磁贴标签/相册长文有裁切，可行性未通过，按DEC-4不加入文字大小设置。原语言列表/持久化属性/时区/字号及八文件还原、音量0。只有debug视觉原型，正式流程未实现；OWN-11视觉决定和新机默认值仍待所有者。见 `artifacts/welcome/WEL-0/report.md`。

- [x] 真实切换语言，含从右到左（`LocalePicker.updateLocales`）。shell UID2000已有权限，en-US/ar-EG/ur-PK共27项通过；没有`pm grant`，应用UID授权仍由人工做。证据 `artifacts/welcome/WEL-0/locale-persistent/results.json`。
- [x] 换到不同 UTC 偏移的时区后，时钟、日期、日出日落是否立刻跟上（`service call alarm 3 s16 <id>`，用 adb shell 的 root）。UTC+0/+14/−11与香港来回切换24项通过，测完恢复原值；证据 `artifacts/welcome/WEL-0/timezone-date-crossing/results.json`。
- [x] 放大系统字号后的界面。真实font_scale=1.3，48画面采集/恢复完成；磁贴标签和相册长文裁切，不能认作字号功能通过，按DEC-4暂不加文字大小设置。见 `artifacts/welcome/WEL-0/font-verified/qualification.json`。
- [ ] 新机的默认时区和语言（要所有者提供或人工重置，否则标“未验证”）。
- [x] 第 1 屏和第 7 屏的真机效果图，给所有者定（OWN-11）。

结论写回 `35` 号文档。

#### WEL-1 骨架

2026-11-04实施结果：所有者指出原欢迎屏点击即退出，要求先做可走通的正式引导，并要求多subagent并行开发、单agent统一验收。三个subagent分别开发核心/契约、原生UI/练习和welcome/settings插件，主agent整合并统一验收。最终0.1.6/1006、API10实际安装，APK c77fe6c2…；JVM832（0失败1跳过）、Python111、原生Lua/JNI203、Go契约、lint/构建通过。真机断网全流程44项通过：空白点按进入语言、地区/选择器返回、跳过Wi-Fi、原生法律页、更新选项真实写入、位置时间hero、中文外观横竖屏、六种实际手势、Home后新进程自动续跑、完成后重启不弹出、设置重跑与公开数据补遗漏。修复外观重复读取描述导致沙箱异常，以及默认done=0被省略导致误迁移，失败记录保留。12份原文件及welcome存储先精确还原、Wi-Fi恢复、五路音量0；随后显式重新打开正式引导供体验。没有更新器，设备管理页跳过、系统时区走安卓日期页；真实新机/语言授权与模块能力、30分钟超时、全部语言排版及性能仍未整体验收，故不勾整项。证据 `artifacts/welcome/WEL-1/report.md`、`device-6/results.json`。

| # | 内容 | 位置 |
|---|---|---|
| 1 | 角色 `onboarding`，要 `system.settings`，只取内置版本 | `shared/schema/plugin/manifest.json`、`PluginRuntime.kt` |
| 2 | `NavStack.Kind.ONBOARDING`：返回可拦截；Home和物理底边上滑按ADR-046算“稍后设置”，保存进度后回home首页；`tos.page.focus`不生效；`ambient`、`sleep`不切换；保持ENGAGED；半小时没人碰就退出 | `core/NavStack.kt`、`core/DeckRouter.kt` |
| 3 | 触发：隐藏设置 `onboarding.done`；`setPages` 第一次拿到目录时判断；不在安全模式；已有 `settings/local.json` 的老设备直接设成当前版本；半途退出下次再问，最多 3 次 | `DeckRouter.kt`、`system.json` |
| 4 | `form` 版式加 `hero` 槽位；`form` 组件加 `subtitle`、`primary`、`secondary`、`back`；有 `primary` 时标题居中，底部画按钮，点击发 `tap`。设置页不带这些字段，长相不变 | `layouts.json`、`PageView.kt`、`FormView.kt` |
| 5 | `source: "region"` 选择器，结构照 `TimezonePicker`；`ZoneNames.country` 出地区名；可搜索 | `ui/form/` |
| 6 | `tos.tz.zones(region)`；`tos.device.openAndroid` 加 `locale`、`date`、`accessibility` | `PluginRuntime.kt` |
| 7 | 插件 `plugins/welcome`：全部屏（手势先用静态文字，时区先用安卓日期页）；`tos.store` 记进度；跳过的屏经公开数据告诉 `settings`，设置页顶部一行“完成设置”，点进去只走这几屏；单位：美国、利比里亚、缅甸设英制；11 种语言 | `plugins/welcome/` |
| 8 | 设置“系统”分类加“重新运行新手引导”；调试命令重置 `onboarding.done` | `settings/main.lua`、`DebugReceiver.kt` |
| 9 | `tools/tests/onboarding_device.py` | `tools/tests/` |

**每屏的文字**（一屏一件事，不显示进度条）：

| # | 标题 | 正文 | 主操作 | 次操作 | 自动跳过 |
|---|---|---|---|---|---|
| 1 | 11 种语言的问候轮流 | — | 轻点任意处 | 跳过设置、辅助功能 | — |
| 2 | 语言 | 列表 | 点一行 | — | — |
| 3 | 地区 | 推荐项和全部地区，可搜索 | 点一行 | — | — |
| 3a | 时区 | 列表：城市和当前时间 | 点一行 | — | 只有一个时区 |
| 4 | Wi-Fi | 当前状态 | 连接 Wi-Fi | 跳过 | 已联网 |
| 5 | 数据与隐私 | 不收集任何数据 | 继续 | 许可证 | — |
| 6 | 设备管理 | Home、最近任务和状态栏归 ThinkOS | 开启 | 跳过 | 已开启，或没有更新器 |
| 7 | 软件更新 | 夜间自动安装，可能重启一次 | 继续 | 只自动下载 | OTA 还没做时 |
| 8 | 位置 | 选城市；`hero` 显示当前时间，不超过 72sp | 继续 | 跳过 | — |
| 9 | 外观 | 主题、红移、屏幕方向三行 | 继续 | — | — |
| 10 | 手势 | 一次一句：左右滑动换页；上下滑动换样式；从顶部下拉打开控制面板；从左边缘右滑返回；从底部上滑回到首页；长按进入设置；双指长按左上角 5 秒打开维护菜单 | 做对自动下一个；Home始终按ADR-046退出并保留进度 | 跳过 | — |
| 11 | 欢迎使用 ThinkOS | — | 开始使用 | — | — |

第 1 屏和第 10 屏不用表单。一路点继续超过一分钟就砍（`35` §7），上真机计时。

验收：`35` §6 的第 1–5、9、11、13、14 条，第 10 条等 OTA-3 做完再验，另外：

- [ ] 第 4 屏不写“没有网络也能用”，第 8 屏不写“选好后返回”，第 5 屏只有一句。
- [ ] 设置页截图和 WEL-1 之前一致（`form` 新字段不影响它）。
- [x] `test_ui_restraint.py` 覆盖 `plugins/welcome`。

结果：2026-11-05，通过此覆盖子项。测试显式核对welcome中文文案/11语键集及hello/gestures视图，并扫描全部出厂脚本/视图；022本轮Python214项通过，包内维护正文样式与源码字节一致。见 `artifacts/welcome/WEL-2/0.1.22/python-1.log`。

#### WEL-2 练习与能力

2026-11-05地区分支实机增量：固定F030独立UID与已装test416db8…，真实welcome Lua/Bootstrap、SettingsStore缓存与中文原生Form/RegionPicker完成两次真实点按；US/imperial选中国后进入Wi-Fi，region事件1次、units写入0次。3项原生与3项外层通过，Root目检三张中文截图；正式done1未重开，原同PID/界面/耐久历史/管理/五流0保持，两包临时唤醒保持及自有reverse均清除。首轮因API27重复resumed行在instrumentation前停止，原false保留；去重只接受同组件，focus仍严格。仅cache组件范围，设备能力为明确stub，不替代新机/特权或全流程，见`artifacts/welcome/WEL-2/region-030-20261105/qualification.json`。

2026-11-05候选增量：0.1.28修复其它地区强写metric覆盖已有单位偏好，以及原生时区写入失败后转安卓日期设置；同次访问generation拒绝Home/Back/同页重入后的迟到RPC，未放宽原生写入门禁。15个新增Lua事件场景（含真实协程挂起）、268项原生PASS组、1098项JVM零失败/错误（1旧skip）、完整Python353项及构建/lint/插件/字符串通过。主包与test已固定且**实际安装0.1.28**；在REG首轮健康完成后正常Home返回再安装，原9文件/更新树/管理/静音保持，没有重开原done1。真实特权/安卓回退窗口与OWN-10仍待。证据 `artifacts/welcome/WEL-2/0.1.28/qualification.json`。

等人工：OWN-10设备管理开启方案未定；特权实机需备用机更新器，见[引导剩余门槛](../artifacts/welcome/WEL-2/human-steps.md)。DEVICE_OWNER_ON客户端和引导接线仍缺源码，不能仅记为等上机。

2026-11-05当前包：**0.1.22/1022已装机**。普通中文主流程30项及原生保存/恢复2项通过；完整实际UI连贯到最终“开始使用”，Home退出后从“完成设置”同进程续长按。五种成功勾真实Surface通过；长按反馈只显示当前句，维护说明18sp单行，原新旧两句重叠/孤字缺陷的021录屏保留。真实owned Dialog失焦与窗口尺寸变化后旧UP不推进、新手势正常、页面恢复显示，6项原生与4项外层通过。原进度与四项设置按语义恢复、原UI/管理/静音恢复；两个021外层窗口观察失败保留，022受控启动/结束一次完整通过。见 `artifacts/welcome/WEL-2/0.1.22/qualification.json` 与15秒无声 `welcome-preview.mp4`。

明确剩余：OWN-10尚未决定；第3项DEVICE_OWNER_ON在daemon仍固定unavailable，客户端/公开mutator和第6屏管理开启接线尚未写，不是仅等待模块安装。时区/语言固定桥已有源码，但特权实机需备用机更新器；不在HUA安装。新装默认、中断冷重启、30min闲置及性能尚未全部验收，完整WEL不勾。

2026-11-05当前包增量：**0.1.20/1020已装**，六手势由原生方向箭头自动示范，长按圈缩放；触摸暂停/抬起恢复、关闭系统动画静态、失焦/隐藏/完成/detach与旧回调取消等16项组件检查通过。中文横竖屏及八段真实Surface录屏已验：7项自动分析，上下双向通过原始关键帧目视复核，保留自动分割失败。原9文件/UI/DO/HOME/LockTask/动画设置/引导done1/静音恢复，设备owned窗口/cache/视频清理；没有重开引导。这只证明组件，不冒称角色授权、真实识别/完整引导或全系统手势重验。1035项JVM（1旧skip）、Python214、lint/构建/测试包通过；未变的Lua/JNI260与插件/i18n沿同源前轮。证据 `artifacts/welcome/WEL-2/0.1.20/qualification.json`；15秒无声预览 `gesture-demo-preview.mp4`。

历史缺陷（已由021修复同栈清空、022完成真实流程与Surface复验）：`reportPractice→RuntimeHost.practiceCompleted→setPractice(null)` 曾在首绘制前清空成功勾；原证据保留，不用隔离Hint检查替代整套实际流程。

2026-11-05当前包增量：0.1.18/1018已装。当前语言按公开LocaleChain.script匹配简繁中文，zh-Hans-CN/zh-Hant-TW/zh-Hant-HK选择已有语言直接续地区，保留原地区；真正简繁切换仍走原权限/安卓回退。3标签实际Lua回归通过，当前APK包含一致资产；未重置已有onboarding.done1/进度，未将此回归当作整套当前引导真机通过。自动手势示范与整套当前包验收继续待办。见 `artifacts/app/APP-9/0.1.18/qualification.json`。

2026-11-05可见流程修复：0.1.11/1011已实际安装，修复Home练习先记账再退出、同进程“完成设置”续当前步骤、隐藏/旧页面迟到点击，以及冷catalog显式Home取消本进程自动弹出。930项JVM零失败（1旧跳过）、184项Python、242项Lua/JNI、debug lint/构建通过；两agent交叉复核。五份原文件、原界面/方向/holds、DO/admin/HOME/LockTask与静音核对通过。当前包8项真实页面/receiver续接检查已通过，包含同进程设置续长按课、实际结束与取消重开；原清理等待失败保留，单独受控恢复原四文件/UI/管理后已显式打开首屏供体验，不冒充完整旧45项重验；新immersive故障回退69项主机检查通过，但未加载新系统钩子。证据 `artifacts/welcome/WEL-2/0.1.11/`。

2026-11-04继续实现：UpdaterClient固定root socket、原生peer UID验证、2秒有界请求、SET_TIMEZONE/GRANT_LANGUAGE异步桥与ROM ID/三个别名已完成；无模块继续走安卓入口。0.1.7实际45项断网引导通过，修复了手动重跑未重置旧三次提示额度的问题；最终同包复验记录见`artifacts/welcome/WEL-2/0.1.7/`。没有安装更新器，没有授予语言权限或设置/清除Device Owner；特权系统设置仍待上机。

| # | 内容 |
|---|---|
| 1 | 练习模式：ThinkStage在横向翻页、纵向切换、下拉、返回手势、长按判定点照常识别但不执行，只报告；底缘Home遵循ADR-046，记录对应练习后退出并保存进度，不能吞掉全局出口。画面归OS，文字归插件；只在持有onboarding角色的页面有效；系统关闭动画时静态箭头 |
| 2 | `tos.onboarding.practice(name, text)`、`stop()`、事件 `practice` |
| 3 | 更新器加三条固定命令（要 MOD-1）：`SET_TIMEZONE <id>`（`[A-Za-z0-9_+/-]`，≤ 64 字节，执行后读回 `persist.sys.timezone`，不一致就改回并回 `ERR zone-unknown`；只对 API 27 成立，其它回 `ERR unsupported`）、`DEVICE_OWNER_ON`（要 OWN-10）、`GRANT_LANGUAGE` |
| 4 | `tos.device.setTimeZone(id)`、`canSetTimeZone()`：只能在点按后 5 秒内调用 |
| 5 | 防 GMT：ID 先查 `TimeZone.getAvailableIDs()`，不在就查替代表（`Europe/Kyiv` → `Europe/Kiev`，`America/Ciudad_Juarez` → `America/Denver`，`America/Coyhaique` → `America/Punta_Arenas`），表里也没有就走安卓日期页 |
| 6 | `tos.device.setLocale(tag)`、`canSetLocale()`；manifest 声明 `CHANGE_CONFIGURATION`；没有权限时打开安卓语言页 |
| 7 | 第 6 屏接上 `DEVICE_OWNER_ON`；退路用 OTA-1 第 8 项 |

验收：`35` §6 的第 6、7、8、12 条，另加：

- [x] 单元测试：替代表三对用 tzdata 2026e 逐小时比 2026–2037 年，UTC 偏移完全相同。
- [ ] 选 `Europe/Kyiv` 等 3 个 ROM 没有的时区，系统时区不会变成 GMT。
- [x] 主机测试：`SET_TIMEZONE` 的畸形输入全部拒绝。
- [x] 练习期间不真的翻页或打开面板；做错不前进。
- [ ] `clock_accuracy_device.py` 不回归（先告知）。

#### WEL-3 装机

等人工：在确认正式证书及参考机后，由所有者执行刷机/安装/重启，按[装机检查](../artifacts/welcome/WEL-3/human-steps.md)留证；dry-run不代替整机安装。

`tools/provision.py` 和面向普通人的安装指南，包住 `19-provisioning-for-agent.md` 的步骤。

1. 校验 SHA-256；Lenovo 固件、Magisk、Lineage、Vector 不打包，只给来源说明和哈希；`magisk_patched.img` 写死版本。
2. 最后装 release APK 和更新器，授权 `CHANGE_CONFIGURATION`，询问是否开启设备管理。
3. 要 OTA-1、MOD-1。

验收：

- [x] 脚本在 `--dry-run` 下打印全部步骤，不碰设备。
- [ ] 真机走一遍由所有者操作（刷机是人工）。
- [x] 指南写明 GPLv3 的“安装信息”：怎么卸载并装自己编译的版本（`34` §9）。

结果：2026-11-05，通过dry-run行为与指南文档两个子项。完整Python268项覆盖实际main参数解析与完整安装计划，fixture验证人工阶段齐全、subprocess零调用及默认不写报告；指南§6列明解除管理、系统模块退出、卸载、自编译/签名/安装。仅本地fixture与文档，不证明正式包签名、卸载、刷机或整机验收。见 `artifacts/welcome/WEL-3/host-qualification-20261105.json`。

#### WEL-4 投屏身份不再用 ANDROID_ID（WEL-5 的前置）

等人工：真实清数据、重启和两台设备身份比对；见[身份验收步骤](../artifacts/welcome/WEL-4/human-steps.md)。当前HUA用户数据未清除。

结果：2026-11-03，代码、主机与非破坏性设备检查通过，整项仍保留人工验收。APK21d9a3ea…，主机6项、真实API27/armv7生产类11项、DLNA静音范围31项、AirPlay37项通过；实际种子在应用进程重启中保持不变，权限600/长度32，已有AirPlay配对密钥不变。十文件还原，媒体音量0。DLNA三项非零会话音量/静音切换按用户要求跳过；没有对现有设备pm clear、系统重启或两台克隆验证，不用隔离cache目录与ART进程检查冒充这些步骤。见`artifacts/welcome/WEL-4/report.md`。

用户 2026-11-03 问：能不能在引导里随机生成安卓 ID（刷机就是让设备回到初始状态，开机后自动进入引导）。结论：

- 真正的Settings.Secure.ANDROID_ID由系统管理，应用不修改，也不加su。迁移前CastHost把它用作DLNA UUID和AirPlay deviceid的种子；现在改为ThinkOS自己的随机种子，代码与验证见上面的结果。
- 不放进引导：引导可以跳过，批量部署还可以直接把 `onboarding.done` 设成 1；引导是 Lua 插件（ADR-039），不该写设备身份。改成第一次用到时生成，和引导出不出现无关。
- 为什么要改：克隆 `/data` 的镜像（WEL-5）会把系统给的 `ANDROID_ID` 一起带到每一台，同一个局域网里的几台会撞同一个 AirPlay deviceid 和 DLNA UUID。这一点按 Android 8.0 以后 ANDROID_ID 由 `/data/system/users/0/settings_ssaid.xml` 里的密钥派生推断，**没有在设备上核对**（比对两台的 `settings get secure android_id`）。种子只在 ThinkOS 自己的数据里，不会被带过去。

| # | 内容 |
|---|---|
| 1 | 新类（`cast/` 下）`CastIdentity.load(file)`：读 `files/cast/identity-seed`，32 字节，大小不对就用 `SecureRandom` 重新生成，只有所有者可读写，做法照 `AirPlayIdentity.load`（`cast/airplay/AirPlayPairing.kt`）。不和 AirPlay 的 ed25519 种子共用文件，DLNA 不依赖 AirPlay |
| 2 | DLNA UUID 和 AirPlay deviceid 都从这个种子派生，去掉对 `ANDROID_ID` 的读取。不加权限，不进 Lua，不进设置 schema（它不是用户可配置项） |
| 3 | JVM 测试：两个空目录得到不同的 id；同一目录再读得到同一个；种子文件被截断或写坏时重新生成。仓库里现有测试没有引用 `ANDROID_ID` |
| 4 | `docs/28-casting.md` §16 写明身份来源；ADR-031 加一行 |

代价：现有开发机升级后 DLNA UUID 和 AirPlay deviceid 变一次，发送端会当作新设备。投屏默认关、尚未发布，接受。`CastHost.kt` 现在是未提交的改动，别的 worker 也在动，改前重读。

验收：

- [x] 搜不到对 `ANDROID_ID` 的读取。
- [x] 主机测试：不同目录 id 不同，同一目录不变，坏文件重新生成。
- [ ] HUA0BWUT：`pm clear moe.kiwi.thinkos` 后打开投屏，id 变了，重启后不变（测完把投屏设置放回）。
- [ ] `cast_dlna_device.py`、`cast_airplay_device.py` 不回归。

#### WEL-5 批量装机（金镜像）

等人工：OWN-6正式证书迁移、成品参考机、取镜像/EDL刷机及两台设备；见[金镜像前置与验收](../artifacts/welcome/WEL-5/human-steps.md)。不能从当前debug开发机直接取成品镜像。

用户 2026-11-03：手上有很多台实体机，要快速把整套 ThinkOS 刷进去；刷完设备处于初始状态，开机自动进入引导。设计和理由在 `docs/35` §5 “W3 批量部署”。做法是先把一台参考机做成成品，读出它的分区当“金镜像”，其余机器只需一次 EDL 写入，不再重走 `19` 号文档的两次 EDL、一次 recovery sideload 和点屏幕。

边界：刷机、写分区、装模块、重启归人工（`AGENTS.md`）；`19` 号文档只在所有者明确说“初始化新机”时授权 Agent 执行其中的命令。这里 Agent 写脚本和校验，EDL 那一步由所有者执行。

前置（2026-11-05 审计）：参考机上的 ThinkOS 必须用正式证书签名（OWN-6、OTA-1 第 10 项、OTA-5 第 1 项）。`/data` 会连同已装的 APK 一起克隆，API 27 不能换证书，用 debug 证书取的镜像，以后的 OTA 覆盖安装会因证书不符失败，每台都要手工卸载重装。

| # | 内容 | 谁 |
|---|---|---|
| 1 | 参考机补成成品：装好 `thinkos-cast`（`02` 号文档写明它还没装进设备）、`thinkos-immersive`、要发的 APK 和更新器（要 OTA-1、MOD-1）；删掉测试残留，HUA0BWUT 上有 `moe.kiwi.thinkos.test`、`moe.kiwi.thinkos.externalfixture`；核对 `dpm list-owners`，参考机若是 Device Owner，`device_owner_2.xml` 会随 `/data` 克隆过去，与 OWN-10（引导里开）冲突，**没核对**，取镜像前要放弃或决定保留 | 人工 |
| 2 | 取镜像前清理：`pm clear moe.kiwi.thinkos`（保证没有 `settings/local.json`，否则被当成老设备，引导不出现，`35` §4.2；也保证没有 `files/cast/`，WEL-4 的种子才各台不同）；Wi-Fi 凭据（OWN-16）、adb 公钥、蓝牙配置、`/data/local/tmp` 和日志。路径用 Android 8.1 的通常位置（`/data/misc/wifi/WifiConfigStore.xml`、`/data/misc/adb/adb_keys`、`/data/misc/bluedroid/`），在 HUA0BWUT 上核对后才写进脚本。不要设 `onboarding.done` | 人工，Agent 写清单 |
| 3 | 读出：`edl r` 读 boot、recovery、system、userdata，userdata 转稀疏只写已用块（`/data` 约 0.5 GB，已核对 `ro.crypto.state=unsupported`，没有加密）；记 `edl printgpt`；每个文件的 SHA-256 进清单。`edl` 的稀疏写入、`wl --skip` 是否可用没验证 | 人工，Agent 写脚本 |
| 4 | 自定义 `rawprogram` XML：照 `~/ThinkSmartView/image/rawprogram_unsparse.xml` 改，只含 aboot（是否必须没验证）、boot、recovery、system、userdata、cache。**不含** `persist`、`modem`、`dsp`、`sec` 和引导链（`sbl1`、`rpm`、`tz`、`devcfg`、`keymaster`、`cmnlib`、`lksecapp`）。官方整包 qfil 会把 `persist` 写成通用的 `persist_1.img`，每台的校准数据会不会因此丢失没验证，所以对已是出厂固件的新机不跑整包。`vendor` 是否与官方包逐字节相同要比一次，不同则也进镜像 | Agent |
| 5 | 每台流程：人工进 EDL（唯一的手工动作）→ `edl printgpt` 与参考机比对，不一致就停 → 自定义 XML 的 `edl qfil` → `edl reset`。EDL 一次一台；一台 Mac 能不能按 USB 口并行没验证。刷这一台时手动给下一台进 EDL | 人工，脚本辅助 |
| 6 | 开机后按序列号并行跑：校验（见验收）、`ANDROID_SERIAL=<序列号> tools/deploy.sh --no-build`（APK 先编译一次）、`dumpsys deviceidle whitelist +moe.kiwi.thinkos`。`tools/provision.py`（WEL-3）加批量子命令，支持 `--dry-run` | Agent |
| 7 | 镜像放仓库外，如 `~/ThinkSmartView/golden/`，不进 git，不进 `artifacts/`；PUB 的扫描要覆盖这条 | — |
| 8 | 镜像只放变化慢的层：ROM、Magisk、Zygisk、Vector、模块。APK 和插件走 deploy 或 OTA；模块变了才重做镜像 | — |

验收（在第一台上，所有者在场）：

- [ ] 自定义 XML 只写那几个分区后能开机；WEL-1 做完后直接进引导，之前进首页。
- [ ] 克隆的 `/data` 里 Magisk、Zygisk 和模块都在（预期如此，没验证）：`su -c id` 是 root；`/data/adb/modules` 含 `busybox-ndk`、`timesync`、`zygisk_vector`、`thinkos-cast`；Vector 里登记的 `moe.kiwi.thinkos.immersive` 路径有效（`/data/app` 目录名随克隆原样保留，预期不用修，没验证）；`immersive_device.py` 6/6。
- [ ] `ro.lineage.version` 是 `15.1-20240531-UNOFFICIAL-starfire`（不用 `ro.build.fingerprint`，BASE-05）；ABI必须含armeabi-v7a，其余只允许armeabi兼容别名，无arm64/x86；HUA0BWUT当前实际值为armeabi-v7a。
- [ ] 刷两台，WEL-4 之后打开投屏：DLNA UUID 和 AirPlay deviceid 两台不同；Wi-Fi、蓝牙地址各自不同且功能正常；传感器正常（`persist` 没动）。
- [ ] 镜像里没有上一台的 Wi-Fi 凭据（除非 OWN-16 另定）、adb 公钥、ThinkOS 配对数据。
- [ ] 记一台从进 EDL 到进引导的实际用时，和 `19` 号文档的流程对比。本文没有任何用时的数字。
- [ ] `--dry-run` 打印全部步骤，不碰设备。

## 11. APP：应用列表、安卓 App、广播

设计见 `docs/29-panel-and-apps.md` §4、§5。

#### APP-1 应用列表

结果：2026-11-03，本项功能通过。APP-5真机48项、100项网格3项、目录2项及直接open权限4项通过：公开目录3个ThinkOS/18个安卓，关闭安卓后3/0；第三方API和app图标拒绝；返回与隐藏/开关/包广播均已验证。最新JVM617/Python71/native/lint/assemble/共享Go契约通过。性能按所有者新优先级后置。证据 `artifacts/app/APP-5/report.md`。

| # | 内容 |
|---|---|
| 1 | `tos.apps.list()`、`tos.apps.open(id)`，权限 `system.apps`，只给内置副本 |
| 2 | 图片地址 `app:<包名>`：只由 OS 取图标、解码、缓存，插件拿不到字节（ADR-024） |
| 3 | `plugins/apps`：页面 `apps.main`，磁贴 `apps.tile`；APP-5已实现只列安卓App的原生网格（DEC-5） |
| 4 | `apps.android`、`apps.hidden`已接上；`apps.android`已放回设置 |
| 5 | `grid` 由 APP-5 实现 |

验收（设备部分和 APP-5 一起做）：

- [x] `tos.apps.list()` 返回 ThinkOS 应用和有桌面入口的安卓 App，去掉 ThinkOS 自己和隐藏项。
- [x] 关掉 `apps.android` 后 `tos.apps.list()` 只剩 ThinkOS 应用。
- [x] 第三方插件调 `tos.apps.*` 被拒绝。
- [x] 应用不进翻页序列，返回回到进入前的页面。

#### APP-2 打开安卓 App

结果：2026-11-03，功能14项通过，`artifacts/app/APP-2/report.md`：Back拒绝仍可实际点Home返回、常亮7、真实启动权限拒绝回传与重锁、视频暂停、真实镜像结束、局域网投屏移回原任务、静音AudioTrack持续96秒豁免超时及停音后53秒自动返回。前置13项含不同UID叠层亮度/顶边和真实音频焦点已验；产品叠层归APP-4。JVM632（0失败1跳过）/Python74/native/lint/assemble通过，十文件/系统值还原、音量0；性能后置。

已有：`KioskState.enterForeign`、`MainActivity.launchOutside`、`apps.return_after_s`。要补：

| # | 内容 |
|---|---|
| 1 | `tos.apps.open` 走 `launchOutside`；停止 Lock Task，继续屏蔽通知栏 |
| 2 | Device Owner 设 `STAY_ON_WHILE_PLUGGED_IN`；外部 App 期间亮度滑块的处理（`29` §5.2） |
| 3 | 启动外部 App 时暂停自己的视频、结束镜像；外部 App 运行时有人投屏，把 ThinkOS 拉回前台 |
| 4 | 打不开时回到 ThinkOS，提示 `apps_open_failed` |
| 5 | 先验证 `29` §7 的第 4–7 条，结论写回 |

验收：

- [x] 打开任意 App 都能回来；不响应返回键的 App 也困不住人。独立APK故意忽略Back，竖横屏实际点导航栏Home返回；未穷举全部第三方应用。
- [x] 到时间自动回来，正在放声音时不回。真实静音AudioTrack活动96秒，停音后看门狗53秒返回。
- [x] 回来后 Lock Task 恢复。
- [x] 没装 `thinkos-shell` 时功能正常降级。

#### APP-3 广播

新插件 `plugins/radio`：网络电台，M3U、PLS 列表，收藏，`focus` 版式显示台名和当前曲目，返回后继续播放，操作栏可控制。用现有词汇，不加新组件。不内置电台。先验证 ICY 曲目信息能否进 `media.title`（`29` §7 第 8 条）。

结果：2026-11-03，功能验收19项通过（`artifacts/app/APP-3/device-direct/`）：实际M3U/PLS播放、ICY曲名、收藏/重启保留、返回后台播放、原生操作栏暂停/恢复、明文拦截提示；301秒运行含190秒503故障，经历重试耗尽并自动恢复，PID不变。单独ICY前置4项、原生Lua9项、JVM629（0失败1跳过）/Python74/native/lint/assemble/13插件通过；最终UI修复APK另通过11语言×竖横屏×两页44项，修复法语直播标签压进度条，见APP-3报告的APK对应关系。十文件还原、音量0。小时级长稳与性能未验。

验收：

- [x] 长时间运行断线自动重连。五分钟功能故障验证通过，190秒503故障后恢复；小时级长稳尚未验证，不是性能验收。
- [x] 明文被拦时提示 `已拦截明文 HTTP，可在设置里允许`。
- [x] Lua 单元测试覆盖 M3U、PLS 解析和畸形输入。
- [x] 不依赖公网电台：测试台用 `tools/media/icy.py`，静音MP3、ICY、M3U和PLS。

#### APP-4 外部 App 里也能下拉（风险最高）

`TYPE_APPLICATION_OVERLAY` 窄条接手势；`PanelHost` 放进叠层窗口；`SYSTEM_ALERT_WINDOW`；Magisk 模块 `system/thinkos-shell`（协议2全局隐藏状态栏和导航栏，人工安装），配合immersive1.2的全局临时栏与固定Back钩子；更新器白名单加它。遵循所有者新增的ADR-046（`docs/38-global-gestures.md`）。`29` §7 的第5、6条前置已有设备证据，不走读触摸设备的方案。

结果：2026-11-03，APP-4旧方案中文28项、ADR-046临时协议2窗口最终37项已通过；全局系统钩子未加载，整项不勾完成。对应APK和分层证据见`artifacts/app/APP-8/global/report.md`；原始失败保留，真实全局系统栏/外部Left与Bottom仍待人工安装模块后的验收。

等人工：按[全系统手势人工步骤](../artifacts/app/APP-8/human-steps.md)核对原Vector scope、安装已构建的immersive1.2和shell1.1并重启，再验外部控制栏和系统栏；当前未宣称已部署。

验收：

- [ ] 外部 App 里能下拉，外部 App 不暂停，视频继续播。
- [ ] 没装模块时不能下拉，用 Home 回来，其它功能不受影响。

#### APP-5 到 APP-8 安卓应用页与底边上滑

内容和验收都在 `docs/37-android-apps-and-up-gesture.md` §4，结果也写在那里。顺序：APP-5 → APP-7 → APP-6 → APP-8。

| 任务 | 内容 | 前置 |
|---|---|---|
| APP-5 | “安卓应用”卡片和页面、原生 `grid`；APP-1 的设备验收一起做 | APP-1 的代码 |
| APP-7 | 从安卓 App 回来时保住页面 | — |
| APP-6 | ThinkOS里的底边上滑按ADR-046回home首页；角色launcher和操作栏“应用”按钮仍是应用磁贴页 | — |
| APP-8 | ADR-046全局手势：immersive1.2、shell1.1/协议2和私有Home接收者；左缘真实Back，底缘回home，顶缘同一控制栏。安装和重启是人工 | APP-6、APP-7 |

等人工：APP-8的持久安装和重启由所有者按[人工步骤与回滚](../artifacts/app/APP-8/human-steps.md)执行；须取得本次启动四类hook、协议2、物理边缘及IME/权限窗口证据，不能用临时夹具或旧日志替代。

#### APP-9 电台目录：radio-browser（全球来源）

最新修复检查点（2026-11-05）：**0.1.23/1023已实际安装**。修复手动M3U分类误过滤收藏：在甲组筛选后打开收藏，乙组已收藏电台仍显示；点击第二标签仍沿原设计清分类显示全部。主机生产Lua沙箱47项通过，真机armv7同一事件回归旧版138行失败、修复版通过；APK内radio源码摘要一致。真机完整Lua套件35项后遇既有大输入CPU预算失败，原日志保留，未调预算，性能后置；不把独立沙箱对照称为真实UI点按或APP-9整体验收。JVM1035（0失败/错误、1旧跳过）、Python214、debug构建/lint/插件校验通过；安装后原9份文件、页面、DO/HOME/LockTask、音量0和boot_count28保留。证据 `artifacts/app/APP-9/0.1.23/qualification.json`。

当前检查点：2026-11-05，**0.1.18已装**。中文原生地区选择器键盘收放、旋转、正反防烧屏位移、CN/所有地区选择、取消/重开及精确恢复23项实机通过；导航栏左缘78px时返回/搜索均从78px开始，中文截图目视通过。三种原生picker共享安全区域源码；本轮设备矩阵只针对地区，不冒称时区/地点已重验。1030项JVM零失败（1旧skip）、debug lint/构建/测试包通过；未变的Python214、Lua/JNI260/插件/字符串检查沿用017同轮。旧016大面积遮挡、017父容器位移后的1px失败已保留。原文件/界面/管理/静音恢复，无模块安装或重启；本修复避让旧导航栏，**不代表全局系统栏禁用已完成**。证据 `artifacts/app/APP-9/0.1.18/qualification.json`。

前次检查点：2026-11-05，0.1.14已装；country已用公开source=region离线本地化选择，CN/所有地区空串/取消实际通过；分类硬上限3次已改且Lua回归通过。横屏地区页在IME/旋转后出现旧系统导航栏遮挡，完整排版未通过；当前全局模块仍旧版。菜单及原设置/静音恢复见 `artifacts/app/APP-9/0.1.14/qualification.json`。以下0.1.13记录为历史对应包证据，公网格式/本地目录矩阵没有冒称本轮重跑。

2026-11-05当前检查点：0.1.13/1013已安装，939项JVM、194项Python、252项Lua/JNI（radio46）及debug lint/构建通过。官方入口已改为系统DNS发现命名节点，默认证书/3秒超时下真实200/100条；生产Lua缓存98台并原生显示。本地目录18项通过（99→198分页、三tag分类/Back、国家/空提示、非法地址、503保留、真新进程离线缓存）。正式JSON目录的MP3、AAC、HLS均经真实点台、解码器/采样率及连续进度验证，音量0并恢复；证据跨public-playback-2与public-playback-hls-1，前者因旧HLS选择器同名拒绝仍保持整体失败，不合并改绿。阶段0独立M3U路径、播放中的关闭上报请求日志、完整健康/收藏重连/主机联动、所有分类设备覆盖和国家选择器仍未整体验完。详见 `artifacts/app/APP-9/0.1.13/qualification.json`。

2026-11-04安装与网络补验：最终0.1.10主包SHA2784f3ce…/测试包22c00efb…已实际安装，原五文件/时钟/方向/DO/admin/HOME/LockTask与静音还原。交叉审的unknown退避、收藏请求与旧播放会话、外层256KiB总预算、异步policy提示、第三tag top-K及重复pattern扫描均已修复；927项JVM、184项Python、239项Lua/JNI（radio36例）和双lint/构建通过。真实网络门禁7项含IPv6通过，私网受限请求零TCP、普通LAN5次对照成功，线程/端口/夹具均已清理；不宣称公网台/解码/HLS/DASH/字幕设备链路已过。官方数据声明也已核实并登记。目录分页/分类/国家/离线缓存的真实页面验收继续，整项不勾完成。证据`artifacts/app/APP-9/0.1.10/qualification.json`及`media-public-policy/results.json`。

2026-11-04开发检查点：目录分页/分类/离线缓存/健康与切换、收藏重连、公开引导地区订阅及通用API10 `public_only`网络限制已实现；后者包含HTTPS、DNS全答案、实际连接、重定向及HLS/DASH媒体/字幕子资源限制，并修复探测跨源凭据传递。0.1.10/1010的927项JVM零失败（1旧跳过）、184项Python、Lua/JNI及双构建lint/assemble、生成Go契约通过；首轮编译/分组错误修复后重验。此为安装前检查点；实际安装与网络补验见上一段，目录真机链路继续待验，证据`artifacts/app/APP-9/0.1.10/`。目录与点击上报暂默认关闭，OWN-14/15未决；数据声明已于后续核实，目录实际公网MP3/AAC/HLS、TLS/超时/探测误判及同host联动未宣称通过。

用户 2026-11-03：想用 radio-browser 作为全球电台来源。评估、实测数字和风险在 `docs/39-radio-directory.md`，这里只列要做的和验收。依赖 APP-3 的 `radio`。

| # | 内容 |
|---|---|
| 1 | 阶段0独立M3U入口仍需复验：先按官方发现流程取得命名HTTPS节点，再临时将`radio.source`设为`https://<发现的命名节点>/m3u/stations/search?countrycode=DE&hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=200`。聚合`all`只作DNS入口；不把单台写死为产品默认。测完还原。0.1.13三格式成功来自正式JSON目录，不冒充该M3U路径已测 |
| 2 | 设备上用真实的 `tos.http` 验证：TLS（证书链是 Let’s Encrypt Root YR，由服务器带上的 ISRG Root X1 交叉签名，设备信任库有 X1，只读过没跑过）；3 秒读超时够不够（设备到德国服务器 ICMP 约 232 ms）；`User-Agent`（官方要求写应用名和版本，`tos.http` 没有就用 `opts.headers` 设 `ThinkOS/<版本>`）；`#RADIOBROWSERUUID` 注释行是否被 `m3u.lua` 忽略（读代码判断，没跑过） |
| 3 | 阶段 1：来源模块 `plugins/radio/sources/radiobrowser.lua`，照 `iptv` 的列表源做法，不把格式写进 `main.lua`。用 JSON 以保留 `stationuuid`；每页 100 个，因为 200 个台的 JSON 约 244 KB，贴着 `main.lua` 的 256 KiB 解析上限；以 `all.api.radio-browser.info` 做系统DNS发现入口，正反向确认后对命名节点走HTTPS，失败切换；不把聚合名直接当HTTPS origin，也不写死单台服务器 |
| 4 | 过滤：只收 https（查询加 `is_https=true`，客户端再验一次）；不收私网、环回、链路本地主机；`hidebroken=true`；按国家取点击最多的；入口不做全库关键词搜索；不显示 favicon、不加说明文字（ADR-044） |
| 5 | 失效电台（所有者 2026-11-03：连不上无所谓，失效的不放，或者自动换下一台）。照 `iptv` 的线路健康做（`31` §5；`plugins/iptv/health.lua` 是纯函数，可以直接照搬）：① 目录已标坏的不取（`hidebroken=true`）；这台设备连不上的，用 `tos.media.probe` 测，失败就暂停（第一次 10 分钟，连续失败每次乘 3，最长一天，到期自动再测），暂停期间不出现在“全部”“分类”里，也不会被自动选中。只是暂停不是删除，因为这只说明这台设备此刻连不上；② 播前一看：没测过的台交给播放器之前先探测一次，死台不让播放器白等；③ 播放中失败（引擎重试用完）：在“全部”“分类”里自动换到列表里的下一台，连续换到第 5 台还不行就停下并提示，免得整个网断了时一直换；在“收藏”里仍是原来的断线重连，因为收藏是用户点名的台；④ 后台测试：每 `test_gap` 秒测一个，顺序是暂停到期的、没测过的、测过太久的，当前列表前面的和收藏的先测；屏幕 ASLEEP 和正在播的那个不测；对第三方服务器要客气，后台一次只开一个；`policy`、`unsupported` 是“测不了”，不算坏；⑤ 同一主机：`dns`、`tls`、`network`、`timeout` 说的是整个主机连不上（例如这台设备连不上的托管平台 `stream.zeno.fm`），可以连带暂停同一主机的其余台，不用逐个测；`http`、`format` 只算这一台。第 ⑤ 点没验证；⑥ 状态存 `tos.store`，按地址哈希，≤900 条；设置 `test`、`test_gap`、`cooldown`、`recheck` 和 `iptv` 一样走 schema，11 种语言。另外刷新目录失败时保留上一份列表（现在只显示 `list_failed`，没有缓存）；收藏存 uuid 加地址，目录连不上也照常播 |
| 6 | 点击上报 `/json/url/{stationuuid}`：做成设置开关，默认值见 OWN-15（推荐关）；失败不提示；设置走 schema，11 种语言 |
| 7 | 默认值：国家默认取引导里选的地区（WEL-1），没有就在设置里选；是否预置为默认来源看 OWN-14，定了才改 `04` §352、`06` 的 radio 行、`29` 广播行、`09` 广播行和 APP-3 的“不内置电台” |
| 8 | 测试：`tools/media/` 加一个假的 radio-browser 服务（M3U、JSON、5xx、慢响应、超过 1 MiB、畸形、http 和私网地址、重复台）；原生 Lua 测试覆盖来源模块的畸形输入；设备测试沿用 `radio_device.py` 的十文件恢复规则；自动测试不依赖公网目录 |
| 9 | 许可：独立实现API客户端，没有复制服务端AGPL代码；2026-11-04已核实官方首页及新增站点页数据声明，维护者拥有的汇集目录权利交付公共领域，台方流媒体/favicon/网页内容另归电台。已登记 `THIRD_PARTY_NOTICES.md`，原始响应摘要见 `artifacts/app/APP-9/0.1.10/radio-browser-license.json`；不虚构CC0，OWN-12其它公开前法律判断保留 |
| 10 | 分类（所有者 2026-11-03：要像电视那样，给它做一个大概的分类）。像 `iptv` 的“分组”：标签变成“收藏”“全部”“分类”，“分类”列出分类，点一个只看它（中间的标签随之显示分类名，再点取消），返回先关分类再离开（照 `iptv/main.lua` 的 `holdBack`）。radio-browser 没有分类字段，只有任何人随便填的标签：点击最多的是 pop 6156、music 5028、news 3186、rock 3120、radio 2333、talk 1854、dance 1606、classical 1452、oldies 1433、jazz 1267，再往后混着 `méxico`、`estación`、`moi merino` 这样的地区、语种和站名，所以分类由我们定：固定十来个，每个对应几个标签（含西语、法语等常见写法），名字 11 种语言。初稿：新闻谈话、流行、摇滚、古典、爵士蓝调、电子舞曲、乡村民谣、嘻哈、怀旧老歌、宗教、体育、本地社区；映射表放 `plugins/radio/categories.lua`，初稿没有对照数据验证，做的时候用 `/json/tags` 调。取法：点开一个分类时才请求，每个标签一次（`tag=…&tagExact=true`，加国家、`hidebroken`、`is_https`、`order=clickcount`），按 `stationuuid` 合并去重，最多 3 次请求，结果按分类缓存；不显示数量（还没取）；没有“其他”，“全部”就是全部。范围：`country` 设置为空就是全球，否则只在所选国家里取；`country` 用引导的地区选择器（WEL-1 第 5 项）选，国家名是本地化的，没有它之前先用 ISO 代码；不另加“国家”标签 |
| 11 | 已实现官方DNS发现：每刷新一次解析，最多8个正向确认的合法命名节点，随机起点、会话优先、失败切换，全部列表总8次GET预算、分类含失败总3次（0.1.14修正，生产Lua/JNI回归通过）；自选镜像原样直连。0.1.13真机发现de1并实际HTTPS200；其它节点的可用性仍不保证，未用硬编码备用池 |

验收：

- [ ] 阶段 0：设备上拉到列表，能播至少一个 MP3、一个 AAC、一个 HLS 台（媒体音量 0）。
- [x] 列表里没有 http 和私网地址；`radiobrowser.lua` 的畸形输入测试通过。2026-11-05，0.1.13真实非法地址过滤及46项radio原生Lua用例；`artifacts/app/APP-9/0.1.13/`。
- [ ] 断开目录后：上一份列表还在，收藏照常播。
- [ ] 测试台里放死台（404、200 的网页、连不上、超时）：第一次播放前被探测，失败后暂停并从“全部”“分类”消失，到期再测；播放中失败自动换到下一台，连续 5 台后停下提示；收藏里的台失败仍是断线重连。
- [ ] 同一主机的连不上被连带暂停（第 5 项 ⑤，没验证，不成立就删掉这一点）。
- [ ] 点一个分类只出现该分类的台，返回先退出分类；分类名 11 种语言；映射表的单元测试覆盖每个分类和多语种写法；没有分类用到的标签落到别处。
- [ ] 后台测试不拖慢时钟（ADR-029），在 `iptv` 同样的条件下不测（ASLEEP、正在播的那个）；跑性能前先告知。
- [ ] 点击上报关闭时，设备不向 radio-browser 发 `/json/url`（测试台的请求日志核对）。
- [x] 数据声明已查清并登记：官方首页及新增站点页原始模块声明维护者拥有的目录数据权利进入公共领域，台方流媒体内容另归电台；`THIRD_PARTY_NOTICES.md`及`artifacts/app/APP-9/0.1.10/radio-browser-license.json`。
- [ ] 不做性能测试；功能确认后先告知所有者。

状态以本节0.1.13检查点为准，证据见 `artifacts/app/APP-9/0.1.13/qualification.json`。0.1.10固定聚合名握手重置属于保留的历史失败；当前命名节点HTTPS与正式JSON目录的三种媒体格式已实际通过，仍不宣称所有线路、独立M3U路径、完整健康/点击矩阵和APP-9整项完成。分类失败轮换的总请求数还须从当前8收至原要求的3；下一版补丁与地区选择器补丁已准备、尚未整合。不跑性能。

## 11A. PWR：断电保护

设计、事实和实验步骤在 `docs/40-power-loss.md`。设备没有电源键，关机靠拔线，突然断电有小概率伤分区（所有者 2026-11-03 要求实现，“这很重要”）。思路：盯系统主供电轨 VPH_PWR，掉压就 `sync`、紧急只读、灭背光，再看电压回来（重启）还是继续掉（关机）。

状态：2026-11-03，守护进程 `system/thinkos-power`、模块包、主机测试 28 项（`system/test/run-power.sh`）和真机上对着假供电轨的 11 项（`tools/tests/power_device.py`）已有；拔线实验工具 `tools/power_trace.py` 已空跑通过（不拔线，每秒约 430 个样本传回 Mac）。2026-11-03 21:23 启动过一次记录，等了 1 小时没有人拔线（只得到空闲一小时基线，见 `docs/40` §2.2）。**拔线实验和装进设备都还没做，现在的阈值（触发 3.80 V、间隔 20 ms）是依据平时电压定的临时值。**

#### PWR-1 拔线实验（人拔线，Agent 记录）

等人工：所有者在场实际拔线，按[E1–E3记录与恢复](../artifacts/power/PWR-1/human-steps.md)执行；无人值守不制造断电。

`docs/40` §5。E1：拔 USB 线看 `usbin` 和设备有没有变化；E2：不带保护拔电源线，记录 VPH_PWR 的曲线和设备撑多久；E3：带保护（`--mode guard`）再拔一次，记录收尾每一步花多少时间。每次拔线都是一次真实的突然断电，和平时拔线的风险一样。

- [ ] E1：USB 线给不给板子供电（`usbin` 掉不掉，设备还在不在）。
- [ ] E2：从开始下掉到最后一个样本多少毫秒；掉过 3.8 V、3.5 V、3.4 V 各在第几毫秒。
- [ ] E3：`tripped` 到 `flush finished` 多少微秒；`/data` 是否在断电前变成只读；开机后 `dmesg | grep EXT4-fs` 没有 recovery。
- [ ] 结果填回 `docs/40` §5，原始数据放 `artifacts/power/`。

#### PWR-2 按实测定阈值并装进设备（装模块人工）

等人工：E2真实曲线后由Agent定阈值重建，通过E3后人工持久安装与重启；见[安装前置](../artifacts/power/PWR-2/human-steps.md)。当前阈值仍是临时值。

- [ ] 用 E2 的曲线调 `powerd.c` 的默认值：触发线、去抖次数、采样间隔、`off`、`hold`。如果 VPH_PWR 从稳压的 4.0 V 一路保持到最后才塌，说明轨上的警告太短，要写进 `docs/40` 并让所有者决定怎么办（见下面的“如果来不及”）。
- [ ] 主机测试和设备测试重跑通过。
- [ ] 人工：`system/install-power.sh`，重启；`adb shell 'su -c "pgrep -l powerd"'` 有进程，`adb logcat -s ThinkOS-powerd:V` 有 `armed`。

**如果来不及**（收尾比电容撑的时间长）：写下数字，选其一交所有者决定：只做 `sysrq u` 不做 `sync`、降低触发线之前先停掉 ThinkOS 的写入（要应用配合，经 `@thinkos-power` 通知）、改内核用 PMIC 的硬件比较器、加外部电容（硬件）。

#### PWR-3 带保护的真机验收（人工拔线）

等人工：保护模块部署后所有者在场拔线，按[实际断电验收](../artifacts/power/PWR-3/human-steps.md)留证；假供电轨测试不代替真实断电。

- [ ] 装好以后连拔 5 次（每次间隔等设备完全起来），开机后 `dmesg | grep EXT4-fs` 都没有 recovery；`/data`、`/cache`、`/persist` 没有被修复过。
- [ ] 视频播放中、下载中、设置保存中各拔一次。
- [ ] 一次只把电压拉低一下又恢复（把电源线拔了立刻插回）：设备应该自己干净重启，不是卡住、也不是关机。
- [ ] 平时不误触发：连续运行 24 小时 `logcat -s ThinkOS-powerd` 没有 `tripped`。
- [ ] 时钟不受影响：`clock_accuracy_device.py`（先告知所有者）。
- [ ] 不装模块时 ThinkOS 照常工作（ADR-015、ADR-033）。

#### PWR-4 可选：状态与手动关机（要所有者定）

另一位开发者的方案是设置页底部有一个“关机”按钮。拔线受保护以后，平时不需要先关机再拔；但想把设备挪走又不想它重启时，需要一个关机。应用里没法关机（`DevicePolicyManager.reboot` 只能重启），所以要让 `powerd` 多听一条命令。

- [ ] 所有者定：要不要这个按钮；设置页要不要显示“断电保护：已启用 / 未安装”。
- [ ] 若要：`@thinkos-power` 抽象 socket，只接 ThinkOS 应用和 root（仿 `thinkos-cast` 的 `SO_PEERCRED`），命令 `HELLO`、`STATUS`、`POWEROFF`；`tos.device.powerOff()` 加确认；界面按 ADR-044，不加说明文字。
- [ ] 没有模块时按钮不出现，设置页不报错。

#### PWR-5 可选：查原厂有没有断电处理

所有者的判断是原厂设计会在拔线时自动关机。目前没有找到证据（`docs/40` §2）。可以解开原厂固件（`~/ThinkSmartView/image/` 里的 `system_*.img`、`boot.img`）找：先按 `rawprogram_unsparse.xml` 把分块拼成原始分区，再读 ext4。Mac 上没有 ext4 工具，不联网下载；写一个只读的小解析器，或所有者提供工具。

- [ ] 找到则写进 `docs/40`，并看能否直接利用（比如它读的信号比 VPH_PWR 更早）。
- [ ] 找不到则在 `docs/40` §6 划掉“不能排除”那一条。

## 11B. CERT：系统根证书更新

设计、实测和选择在 `docs/41-ca-trust.md`。原设备根证书库是 ROM 冻结的 2015 年的一套：135 张，21 张已过期，Mozilla 2026-10-20 的 118 张里缺 70 张。做法：Magisk 模块 `thinkos-certs` 覆盖 `/system/etc/security/cacerts`，来源是 OTA-2 已经钉住的 curl/Mozilla 2026-10-20 包。不写 `/system`；本次安装和重启已由所有者明确授权Agent并完成。

最新生效状态（2026-11-04）：所有者明确授权Agent安装及重启，E0/E1/E2已通过，当前117张replace系统库及三个官方站点预期TLS根均验证成功，详见下方CERT-3与`artifacts/certs/CERT-3/activation-20261104/`。完整媒体/公开数据回归仍待做，不勾整组。

安装前检查点（2026-11-04早期）：CERT-1/2生成、模块与主机检查已实现，CERT-5许可已登记；目录契约已加入certs/power。真机已解析/验签全部117张PEM，且只读核对原135张文件与AndroidCAStore摘要一致。当时未安装模块、未重启。证据在 `artifacts/certs/CERT-1/`–`CERT-3/`。安装前CCADB的ISRG X2/GTS R4官方站点在TCP可达时原生TLS均报缺少信任根，ISRG X1对照通过；该失败现已被新库修复。原始基线仍保留在 `artifacts/certs/CERT-3/tls-baseline-20261104/`，不能把它当作当前状态。

#### CERT-1 生成工具（主机，不碰设备）

| # | 内容 |
|---|---|
| 1 | `tools/vendor_system_cacerts.py <cacert.pem> --sha256 <钉住的值>`：不联网、不碰私钥、不碰设备，照 `tools/vendor_update_roots.py`。校验输入哈希，解析每张，按旧式主题哈希命名（主题 DER 的 MD5 前 4 字节、小端、8 位十六进制，同哈希依次 `.0` `.1`，编号必须连续：Conscrypt 从 0 往上找，遇到第一个不存在的就停），输出 `system/thinkos-certs/system/etc/security/cacerts/` 和 `system/thinkos-certs/certs-source.json` |
| 2 | 同样的输入出同样的字节；文件只放一段 PEM（Conscrypt 8.1 源码里用 `CertificateFactory` 读，PEM、DER 都行；设备上没跑过，CERT-3 的 E1 验） |
| 3 | `--diff <上一版 certs-source.json>`：打印加了谁、删了谁、一年内到期的根 |
| 4 | 拒绝：哈希不符、解析失败、重复、已过期、不是 CA、不是自签名；同时读 Mozilla 的 `certdata.txt`（SHA-256 要和包头 `SHA256:` 对上），服务器信任不是 `TRUSTED_DELEGATOR` 或带 `CKA_NSS_SERVER_DISTRUST_AFTER` 日期的不收并列出。curl 的脚本不处理这个日期；2026-11-03 查到 Izenpe.com（2026-04-15）在 118 张里，排除后是 117 张 |
| 5 | 主机测试 `tools/tests/test_system_cacerts.py`，用 `artifacts/certs/CERT-0/device-store-2026-11-03.tsv` 当夹具 |

验收：

- [x] 重复生成，字节相同。
- [x] 117 张全部能被 `openssl x509` 解析，无重复、无过期、没有 distrust-after 日期；Izenpe 不在里面。
- [x] 同哈希的证书编号连续（用一对人造的同主题证书测）；`certdata.txt` 的哈希和包头不符时拒绝。
- [x] 旧库与源包共有48张的旧式文件名算法全部匹配；最终输出保留其中47张，Izenpe按策略排除。
- [x] “必须有”和“必须没有”的名单（`docs/41` §5）都通过。
- [x] 刷新演练：喂一个假的新版包，`--diff` 的输出正确。

#### CERT-2 模块

| # | 内容 |
|---|---|
| 1 | `system/thinkos-certs/`：`module.prop`（id `thinkos-certs`，version 取包日期如 `v2026.10.20`，versionCode `20261020`）、`customize.sh`、`META-INF`（照 `thinkos-cast`）、`LICENSE-MPL-2.0.txt`、`certs-source.json`、`system/etc/security/cacerts/*` |
| 2 | `system/build-certs.sh [--mode add\|replace]`：replace 在 `cacerts` 目录里放空文件 `.replace`，add 不放；出 `system/build/thinkos-certs-<version>-<mode>.zip`，可复现 |
| 3 | `customize.sh`：API 27+；不少于 100 张、文件名合 `^[0-9a-f]{8}\.[0-9]+$`、第一行是 `-----BEGIN CERTIFICATE-----`，否则 `abort`；权限 0644、上下文 `u:object_r:system_file:s0`。没有可执行文件，没有 sepolicy |
| 4 | `system/install-certs.sh [序列号] [--mode ...]`，同 `install.sh`；本次已授权Agent执行 |
| 5 | `system/test/run-certs.sh`：解压 zip 做同样的检查；对坏包（空目录、少于 100 张、名字不对、不是 PEM）都 abort |

验收：

- [x] 主机测试通过；zip 不超过 `34` 号文档对模块的 16 MiB 上限（预期约 0.2 MB）。

补充：add与replace的包分别约198KiB。add对照旧135张分配文件名：GlobalSign R4与Firmaprofesional的新DER分别放0d69c7e1.1、d16a5865.1，保留原.0，最终205张；直接合并规范117张会误覆盖成203张。安装前逐文件核对原库，变化则中止；每个包的install-layout.json保留映射。主机16项、全Python99项通过。

#### CERT-3 单机验证（本次安装和重启已明确授权Agent）

`docs/41` §5。2026-11-04所有者明确要求Agent完成安装、重启和信任库/HTTPS验证。本机E0/E1/E2已通过：当前117张replace、boot_count28；两个阶段三个官方站点原生TLS均到预期根。音量全0，启动音效关闭，其他模块不变。证据 `artifacts/certs/CERT-3/activation-20261104/`。

- [x] E0：已保存旧135张库存；安装暂存后、激活前在Magisk实际关闭再开启CA模块，证明可撤销；没有切换其他模块。
- [x] E1：Agent执行 `--mode add` 并重启；205张文件及应用信任库的数量、指纹、权限/上下文均吻合，三个HTTPS预期根通过。
- [x] E2：Agent执行 `--mode replace` 并重启；117张文件及应用信任库完全吻合，三个HTTPS预期根通过。
- [ ] 两步之后各跑一次不回归：`public_data_device.py`、radio、player；`logcat` 没有新增的 `SSLHandshakeException`、`CertPathValidatorException`。
- [x] 结果和原始数据放 `artifacts/certs/CERT-3/activation-20261104/`；E2公共数据13项/广播19项/播放器18项已通过并恢复设置；E1阶段媒体回归未跑，整组仍不宣称完成。首次公共数据离线图片页发生一次Adreno/HWUI原生崩溃，原始栈保留、根因待复现，不能归因CA或认定已修复。

#### CERT-4 批量和以后的更新

| # | 内容 |
|---|---|
| 1 | WEL-5 的金镜像：参考机上装好（E1、E2 通过以后），镜像里就带着，新机不再单独装。已经刷好的机器每台人工跑 `system/install-certs.sh <序列号>`，重启，`certs_device.py` 逐台检查 |
| 2 | MOD-1：更新器的 id 白名单（`34` 号文档）要加 `thinkos-certs`。白名单编译进守护进程，以后加要先发一版更新器，所以第一版就带上。`thinkos-power` 目前也不在名单里，一并加 |
| 3 | 刷新流程：新包出来 → CERT-1 → 新版本 → 单机验证 → OTA；没有更新器以前是人工装。写进 `system/README.md` |

当前CERT-4已有契约/客户端与root守护进程编译白名单：七项为updater、cast、immersive、shell、certs、power、timesync；host检查与armv7构建通过，root更新器未安装。WEL-5金镜像尚未制作，不能把单机CA激活当作批量部署完成。

#### CERT-5 许可登记

- [x] `THIRD_PARTY_NOTICES.md` 里“OTA TLS 根证书子集（16 张）”一行改成包含系统模块的整套 117 张；`07-build-deploy.md` 里“MPL-2.0只用于OTA…子集”的说法同步。
- [x] 模块包带 `LICENSE-MPL-2.0.txt` 和来源。法律判断归 OWN-12，不在这里下结论。

#### CERT-6 ThinkOS 自带信任根（已取消）

所有者 2026-11-03 定为不需要（OWN-18）。不做：`network_security_config.xml` 保持只信 `system`；其它联网代码（`PluginHttp`、`MediaHttpClient`、媒体）继续用系统库。没装 `thinkos-certs` 模块的设备拿不到新 CA，这是接受的代价（ADR-015、ADR-033：模块是可选的）。OTA 更新器已有的 16 张子集（`update/UpdateTrust.kt`，OTA-2）不受影响，照旧。

## 11C. CALL：设备到设备的音视频通话

设计、实测和调研在 `docs/43-video-call.md`，决定在 ADR-047。所有者 2026-11-04（后面的取代前面的）：推翻“不做视频通话”；要点对点、不要重新造轮子；**可以提供一个服务器，但只用来帮大家打洞（会合），不转发音视频**；跨网用 STUN，STUN 要做成列表，不能只依赖 Google。开发和测试由开发 Agent 做，服务器的部署和运维是所有者的事（OWN-24）。 完整实施方案（架构、阶段与门槛、风险、所有者要做的事）在 `docs/44-call-plan.md`。

状态：2026-11-04 只做了调研和设备上的小包探测，代码没动，库没选，服务器没建。 **2026-11-04 范围收窄（所有者）：先不考虑跨局域网，只做局域网内的互相发现、呼叫、通话、视频。不需要服务器、STUN、第二个网络。CALL-0b、CALL-1、CALL-3、CALL-4 和 OWN-19、OWN-20、OWN-23、OWN-24 暂缓。**

#### CALL-0 真机验证（先做，不做产品）

2026-11-05实际硬编单帧通过：固定standard ARMv7 test57976…在F030/API27新PID3719，SDK实际HardwareVideoEncoder与底层MediaCodec均为OMX.qcom.video.encoder.avc，byte-buffer颜色21实际接受，一帧1280×720灰色I420得到3639字节关键帧、SDK release OK、worker结束。10项策略/跨线程终态JVM与独立只读复核通过；原同PID/held界面、两包全部耐久树/权限/五流0保持，临时hold清null、自有token清理；平台结束仪器进程，agent未发后置kill。未开CAM/MIC/EGL/PCFactory或网络，maxFramerate=30仅配置，不等于720p30采集/持续编码；未测解码渲染/码率控制/通话，库选择仍待，见`artifacts/call/CALL-0/h264-one-frame-030-20261105/qualification.json`。

2026-11-05修订实机通过：修正探针对合法null元数据的接收，10项JVM边界通过；同固定standard ARMv7库在F030两个新进程完成四工厂完整枚举与API27 updater缺失/ATOMIC_MOVE/目录fsync/fileKey。两个decoder各实际6条null，原失败保留；正式当前UI/同PID、两包耐久历史/权限与五流0、已知token清理均核实。未创建codec/相机/麦克风/通话，不等于硬编选择或CALL-0整体通过，见`artifacts/call/CALL-0/native-factory-030-20261105/qualification.json`。

2026-11-05显式原生装载首轮：独立F030测试包内固定standard ARMv7库已在真实API27 UID装载并执行JNI_OnLoad；SoftwareEncoder列出VP8/AV1/VP9，DefaultEncoder另列H264/H265，但两个Decoder枚举抛异常，完整探针失败保留，正在核查可空元数据接收。未打开相机/麦克风或创建codec/通话，不把列表当实际编解码选择；正式页面随后变化，API27续段未进入，原数据完整不变尚未资格核验，不作旧值回写。见`artifacts/call/CALL-0/native-factory-030-first-20261105/qualification.json`。

2026-11-05测试入口准备（当时尚未运行）：新增test-only `call_capabilities` 固定phase，应用视角Camera2与AVC有界元数据枚举，不openCamera/createCodec/采音/改权限。4项元数据策略JVM与测试APK编译通过；实际未安装/运行，名称仅启发式、不能证明WebRTC选硬件或720p30采集。证据 `artifacts/call/CALL-0/capability-probe-20261105/qualification.json`。

2026-11-05实际元数据增量：独立`.regfixture` 0.1.26固定主/测试双包已安装，test-only枚举在普通Main启动前完成；正式027的原9文件字节/界面/管理/同进程同boot/五流0保持。相机front、hardware level_3，YUV与PRIVATE输出元数据宣告720p30；QCOM AVC encoder/decoder元数据支持720p30，Google H.264 encoder元数据不支持720p，不作为实际codec创建失败结论。没有camera权限、未打开相机/麦克风或创建codec/WebRTC；不是正式UID实测，不勾720p30采集/通话、native库实例、性能或库选择。首次只读pm缺失exit1在任何安装前停止，v2仅修只读缺失处理后完成。证据 `artifacts/call/CALL-0/actual-metadata-20261105/qualification.json`。

2026-11-05静态补验：官方Maven固定150.7871.04三个变体的摘要/armv7 ELF/minSdk/Java输入与JmDNS3.6.5/SLF4J-api2.0.7已核；四组离线D8均exit0、原日志为空（min-api27、android-35库桩）。固定构建配方三变体均关闭内置软件H.264，不把标准版software codecs或平台MediaCodec软件解码混为内置H.264。AAR/嵌套JAR未发现LICENSE/NOTICE；成品→实际构建run绑定与完整第三方清单仍缺，最终库/变体未选、无依赖改动。无设备通话/发现/装载或性能，CALL-0不勾；见`artifacts/call/CALL-0/libraries-20261105/qualification.json`及`docs/43` §2。

| # | 内容 |
|---|---|
| 1 | 在应用里用 `CameraCharacteristics` 复核硬件级别（`dumpsys` 报 3）；采 720p30，记 CPU |
| 2 | 用候选 WebRTC 库做本机回环：摄像头到编码到解码渲染。看 WebRTC 是否选中 `OMX.qcom.video.encoder.avc`、设备接受的颜色格式、码率控制 |
| 3 | 回声：喇叭和麦克风同机，对比平台 `VOICE_COMMUNICATION` 的 `aec` / `ns` 和 WebRTC 软件回声消除的效果与 CPU |
| 4 | 持续十分钟以上的采集加编码：温度、降频、掉帧（性能测试，先告知所有者）；`thermal-engine.conf` 为空、`msm_thermal` 关闭，读不出门限，只能实测 |
| 5 | .so 加载耗时和 APK 增量（armv7 约 6.6–6.8 MB）；冷启动不得受影响 |
| 6 | 核对 LiveKit 标准版是否含软件 H.264、libwebrtc 内含第三方库的许可清单；在 `android`、`android-prefixed`、`android-prefixed-stripped` 三个变体里选（stripped裁剪部分codec；三者固定配方均无内置软件H.264，真机能力仍待）；核对JmDNS许可/包体积及运行依赖 |
| 7 | 发现验证（两台真机，同一 Wi-Fi）：JmDNS（`org.jmdns:jmdns` 3.6.5）互相发现，目标不超过 5 秒、重启 20 次全部成功、24 小时后仍可被发现（提议值）；通不过就换 SSDP 风格多播（复用 `Ssdp.kt` 的报文逻辑和已验证的多播收发）；**不用 NsdManager 浏览**；核对与系统 mdnsd（AirPlay 用 NsdManager 注册）并存时的主机名冲突；记录路由器开 AP 隔离时的表现 |
| 8 | 局域网 ICE：两台真机，`iceServers` 为空，只用本机地址候选，等收集完成再发 SDP；记录收集和建立耗时；`disableIPv6OnWifi` 的影响（本机 IPv6 只有 `fd05:` 内网地址）；确认候选是明文 IP（Java 接口里没有 mDNS 混淆选项，已查） |
| 9 | 方向与镜像：前置摄像头朝向 270°、屏幕横屏倒装（`docs/07` §9），对端看到的画面必须是正的，本地预览要镜像；音效库（`/vendor/lib/soundfx/` 里的 JamesDSP、ViPER）对通话音频和回声消除的影响 |

验收：

- [ ] 每一项有数据或明确的“做不到”，放 `artifacts/call/CALL-0/`。
- [ ] 结论写回 `docs/43` §9，把“没验证的”逐项划掉或改成结论。

#### CALL-0b 打洞对比实验（EasyTier 去 VPN 化 与 WebRTC ICE）——暂缓（跨局域网）

背景：只交换地址不保证打得通，成败取决于 NAT 和防火墙，也就是打洞手段。EasyTier 的手段更多（`docs/43` §6.3），但两边都没有成功率数据。这个实验决定连接层走路 A 还是路 B′。

| # | 内容 |
|---|---|
| 1 | 至少两个不同的网络：设备在家里的网络，另一端接手机热点（常见运营商级 NAT）；再加第三个网络（所有者提供，例如另一处宽带）。机器和网络由所有者提供（OWN-24） |
| 2 | EasyTier 侧：官方 `easytier-core`，或第三方 FFI 的 armv7 构建（来源和 SHA-256 要记录），`--no-tun`，用 `--port-forward` 的 UDP 转发；记录无 TUN 时对端由谁接收、`--socks5` 是否支持 UDP |
| 3 | 会合：一台 EasyTier 共享节点，开 `disable_relay_data`（版本要含 PR #2188）；记录关闭转发后打洞协调是否照常 |
| 4 | WebRTC 侧：同样的网络组合下的 ICE（这里不评估信令方案，会合用最简单的手段） |
| 5 | 每种组合至少试 20 次，记录成功率、建立耗时、失败原因；打洞期间在路由器或另一台设备上看连接数（issue #289 的担忧） |
| 6 | 去掉 `tun` 特性后为 armv7 安卓编译：能否编过、核心体积、常驻内存和 CPU（性能测试，先告知所有者） |
| 7 | 只有路 B′ 有希望时才做：WebRTC 经本机回环转发器跑在 EasyTier 上的 MTU、延迟、CPU |

验收：

- [ ] 每种网络组合有两种方式的成功率，放 `artifacts/call/CALL-0b/`。
- [ ] 结论写回 `docs/43` §6.5，OWN-19 据此决定。
- [ ] 实验用的服务器、账号和网络由所有者提供，实验结束拆掉。

#### CALL-1 STUN 列表——暂缓（跨局域网）

| # | 内容 |
|---|---|
| 1 | `tools/vendor_stun_list.py <已下载的列表>`：不联网，输出内置列表、来源、日期、SHA-256，同输入同输出 |
| 2 | 健康评分模块（纯函数，照 `plugins/iptv/health.lua`）：失败暂停、连续失败退避、多次判断、到期再测 |
| 3 | 通话前并行问 3–4 个，谁先回用谁；Google 不是默认；列表和阈值走设置 schema（ADR-017），11 种语言 |
| 4 | 经 `docs/34` 的签名目录更新列表，不依赖会合服务器 |

验收：

- [ ] 主机测试：同输入同字节；畸形列表（超长行、非法主机名、重复、空）全部拒绝或跳过。
- [ ] 设备上：内置列表里至少 N 个有回复（2026-11-04 实测 107 / 216）；断掉 Google 后仍能得到公网地址。
- [ ] 间隔几小时再测一次，记录哪些服务器时有时无。
- [ ] 自动测试不依赖公网，用本地假 STUN 服务。

#### CALL-2a 局域网发现与配对（CALL-2 的前置）

| # | 内容 |
|---|---|
| 1 | 设置项 `call.enabled`（默认关）和设备名（沿用 `cast.name` 的做法，默认带种子的后几位，避免名单里一排 ThinkOS）；走设置 schema，11 种语言 |
| 2 | 局域网发现：JmDNS，服务类型 `_thinkos-peer._tcp`（不用 Server 的 `_thinkos._tcp`），备选 SSDP 风格多播（`PeerDiscovery` 接口）；**不用 NsdManager 浏览**；TXT 只放协议版本、名字、公钥指纹前 8 字节；默认关，关时不监听、不通告、不持多播锁；只服务私网来源，解析有界，要畸形输入测试；兜底：上次地址直连、手动输入 IP |
| 3 | 每机一把 ed25519 密钥，**第一次用到时在设备上生成，不进金镜像**（WEL-4、WEL-5）；`Curve25519.kt` 现在在 `cast/airplay/` 且是 internal，要移到公共位置；测验签速度 |
| 4 | 配对：A 发起，两边屏幕显示同一个 6 位短码（承诺加随机数的短认证字符串，`docs/44` §6），各自点确认，互存公钥；连续输错或拒绝若干次后锁定一段时间（提议 5 次）；可以解除配对 |
| 5 | 界面按 ADR-044：附近设备列表、短码、确认，不加说明文字 |

验收：

- [ ] 两台同网设备互相发现并配对；配对前对方不能发起通话。
- [ ] 主机测试：畸形 TXT、超长名字、重复、短码连续错误后锁定一段时间（提议 5 次）。
- [ ] 金镜像刷出的两台设备密钥不同（WEL-5 的检查项里加这一项）。

#### CALL-2 局域网通话（要 CALL-2a）

| # | 内容 |
|---|---|
| 1 | OS 里的 `CallHost`、`tos.call.*`（权限声明、新 API 号）、通话界面组件；协议不进 Lua |
| 2 | 信令：每台设备在同一个 `HttpServer`（现在在 `cast/dlna/`，要移到公共位置）上提供 `/call/v1/*`，互相 POST 签名的 JSON（`docs/44` §6）；WebRTC 不设 ICE 服务器，只用本机地址候选 |
| 3 | 隐私（CALL-5）和硬件开关一起做 |
| 4 | 来电：复用投屏的唤醒并接管前台（`CastHost.show` 的做法）；铃声；来电时按音频焦点暂停媒体 |

验收：

- [ ] 两台同网设备互拨，音视频双向，断开后摄像头和麦克风释放（`docs/10` §5：关闭相机要彻底）。
- [ ] 来电时媒体暂停，结束后恢复；时钟不受影响（ADR-029，先告知再跑 `clock_accuracy_device.py`）。
- [ ] 不装任何库以外的东西、不联网也能通。

#### CALL-3 NAT 自检和“无法直连”的提示——暂缓（跨局域网）

- [ ] 同一个套接字问至少 2 个 STUN 服务器，外部端口不一致判为对称型；结果随配对记录交换。
- [ ] 呼叫前就能判断成功概率；打不通时给出明确提示，不干等超时。界面按 ADR-044，不加说明文字。
- [ ] 主机测试覆盖：全部服务器无回复、只有一个回复、端口不一致、回复里有畸形属性。

#### CALL-4 跨网通话（要 OWN-19、OWN-24；服务器的部署和运维是所有者的事；人工要提供两个不同的网络，例如一台连手机热点）——暂缓（跨局域网）

| # | 内容 |
|---|---|
| 1 | 会合渠道做成可替换的接口，每个提供者自己是一份带健康检查的列表；内容端到端加密，topic 或键由配对密钥派生 |
| 2 | 会合与信令按 OWN-19（CALL-0b 之后）定：路 A 用现成软件（MQTT 或其它，见 `docs/43` §6.2），路 B′ 是一个 EasyTier 共享节点；DHT（jlibtorrent）和公共 MQTT 只作备选或兜底，首发不做 |
| 3 | 响铃：被叫方保持连接，收到加密的呼叫后启动 ICE |
| 4 | 记录成功率、耗时、失败原因，放 `artifacts/call/CALL-4/` |
| 5 | `tools/rendezvous/`：Mosquitto 和 coturn 的配置样例、部署说明、设备认证流程（Agent 写；**部署和运维是所有者的事**） |
| 6 | 信令消息、主题派生、端到端加密和防重放按 `docs/44` §4；MQTT 客户端用 Paho 的纯 Java 客户端（许可待核，见 CALL-0），不用它的 Android Service；信令放在 `Signaling` 接口后面 |

验收：

- [ ] 两个不同的网络之间，至少一种 NAT 组合通话成功；失败的组合记下原因并给出提示。
- [ ] TLS 固定我们自己的证书，核对不依赖设备的根证书库（`docs/41`）；公共设施只在做备选时才需要查使用条款。
- [ ] 会合服务不可用时，局域网通话照常，设备其余功能不受影响（ADR-015）。

#### CALL-5 权限、隐私、指示

- [ ] `CAMERA`、`RECORD_AUDIO` 的运行时授权流程（引导里处理，Device Owner 可授权）。
- [ ] 默认关；来电必须点接听；通话中屏幕有明显指示；摄像头开关和静音键（`camera_switch`、`mute` 输入事件）有效；摄像头被占用时存在检测让出。
- [ ] `THIRD_PARTY_NOTICES.md` 登记所选库及其内含的第三方部分。

#### CALL-6 稳定性与总验收

- [ ] 同网通话 2 小时不掉线；弱网（丢包、带宽受限）下降级到低分辨率或纯音频，而不是断开。
- [ ] 24 小时待机保持监听和通告，CPU 和内存不超过基线 +20%（性能测试，先告知）。
- [ ] 时钟不受影响（`clock_accuracy_device.py`，先告知）；冷启动 < 3 秒（.so 按需加载）。
- [ ] `AGENTS.md` 验收五条；`python3 -I -m unittest discover -s tools/tests`。
- [ ] 主机测试：信令消息的畸形输入、重放、篡改、超长全部拒绝。
- [ ] `THIRD_PARTY_NOTICES.md` 登记 WebRTC 库、JmDNS（若用）及各自内含的第三方部分。
- [ ] `docs/42-feature-rules.md` 加“通话”一节（协议不进 Lua、CallHost、默认关、来电需接听）。

## 11D. CAST：Miracast 的系统模块

历史检查：投屏页的 Miracast 一行显示“缺少系统模块”。**当时原因**（2026-11-04 只读核实）：设备上没有安装 `thinkos-cast`。`/data/adb/modules` 里只有 busybox-ndk、rv4a_engine、thinkos-certs、timesync、zygisk_vector；`@thinkos-wfd` 抽象套接字不存在；`plugins/cast/main.lua` 在 `status.miracastModule == false` 时显示 `state.module`。设备上还留着一份开发时的临时程序 `/data/local/tmp/wfdd-test`（没在运行，重启后也不会起来）。这记录的是当时没有运行守护进程的状态。当前提示还可能由STATUS不可读或SINK_ON失败产生，不能单凭这行文字判断磁盘安装状态；0.1.27本轮结果见下。

#### CAST-1 装模块并验收（装和重启要所有者做，或明确授权，OWN-28）

2026-11-05当前会话增量：0.1.27已采用`/data/local/tmp`临时root wfdd，生产等待页Miracast显示“开”；脚本化Source通过真实RTSP/RTP/MediaEngine接收1280×720灰帧与静音AAC、TEARDOWN释放，共11项通过。仅Miracast开、DLNA/AirPlay关时，同一cast.home随自有临时wfdd停止/原二进制重启自动显示缺少/开、无需重进，6项通过。接收专项原9文件字节、刷新专项三开关语义恢复/其他8文件字节及原UI/管理/同进程同boot/五流0保持。没有持久安装或重启，没有真实华为/Windows发现配对/共享屏幕，不替代下列27项或整套CAST-1。证据 `artifacts/cast/CAST-1/0.1.27/qualification.json`。

等人工：OWN-28持久安装和重启、真实手机/Windows发送端。已交实际脚本接口、静音前置、只读检查、残留处理和回滚，见 `artifacts/cast/CAST-1/human-steps.md`。未执行安装/重启；现27项脚本含性能采样及状态恢复缺口，不能直接作无人值守整体验收。

| # | 内容 |
|---|---|
| 1 | 先在主机跑 `system/test/run.sh`（18 项） |
| 2 | 人工（OWN-28 未授权）：`system/install.sh`（构建 v1.0 包、推到 `/sdcard/Download`、`magisk --install-module`），重启（冷启动 60–90 秒，adb 回来要 1–3 分钟）。建议和蓝牙叠加层模块同一次重启（BT-0）。Agent 只写好步骤，见 §0.7 |
| 3 | 验证：`/data/adb/modules/thinkos-cast` 存在；`grep thinkos /proc/net/unix` 看到 `@thinkos-wfd`；`logcat -s ThinkOS-wfdd` 有启动日志；设置里打开“用 Miracast 接收投屏”后，投屏页不再显示“缺少系统模块”；`python3 -I tools/tests/cast_miracast_device.py`（27 项） |
| 4 | 真实发送端验收（华为手机“无线投屏”、Windows `Win+K`）：`docs/28` 一直写“真机发送端还没测”，要所有者的手机和电脑 |
| 5 | 清理开发残留 `/data/local/tmp/wfdd-test*` |
| 6 | 金镜像（WEL-5）：参考机装好后再取镜像；更新器的模块白名单里已有 `thinkos-cast`（`docs/34`） |

验收：

- [ ] 装好、重启后投屏页的 Miracast 一行没有“缺少系统模块”。
- [ ] 27 项真机检查通过。
- [ ] 至少一台真实发送端连上并有画面；没做的写明。
- [ ] **没验证的：**SELinux enforcing 下的行为（设备是 permissive）；wpa_supplicant 控制套接字路径只在这个 ROM 上试过；真实手机和 Windows。

## 11E. MODE：模式（白天、红移、睡眠）

设计在 `docs/45-modes.md`，决定在 ADR-048。所有者 2026-11-04：白天；红移（原“夜间模式”，统一叫这个名字）；睡眠（免打扰：音量完全静音，屏幕完全不显示）；切换要用到用户设置、当地日出日落、光线传感器和用户的触摸使用。开发和测试由开发 Agent 做。状态：MODE-1/2已实现并装机，MODE-3静音/媒体部分通过；真实黑屏与触摸仍等MODE-0人工验证。

#### MODE-0 真机验证（先做）

结果：2026-11-05，已完成 `tools/tests/mode_backlight_device.py` 与9项主机测试（实际shell/假sysfs和uptime，不调用ADB）。设备端看门狗先回写原值自检、20秒内独立归零/恢复，host断连和迟到请求均有拒绝/恢复覆盖；真实黑屏/触摸/双击以及Activity窗口亮度0均未测。证据 `artifacts/mode/MODE-0/qualification.json`。

等人工：所有者在场后按 `artifacts/mode/MODE-0/human-steps.md` 观察黑屏、直接用手指测触摸；无人值守不运行背光归零，不用ADB触摸代替硬件。

| # | 内容 |
|---|---|
| 1 | 背光 0 是否真的全黑：窗口亮度 0、系统层写 `/sys/class/leds/lcd-backlight/brightness` 两种做法都试；**不用**整屏息屏（面板与触摸共用复位） |
| 2 | 背光 0 时触摸是否照常工作、双击能否检测；做不到就停下，睡眠不能上线 |
| 3 | 红移窗口里环境光的真实读数，记录一晚；传感器被遮挡时的表现；接近传感器的有效距离 |
| 4 | 睡眠时的功耗和 CPU |

验收：每一项有数据或明确的“做不到”，放 `artifacts/mode/MODE-0/`，结论写回 `docs/45` §12。

做法：脚本（建议 `tools/tests/mode_backlight_device.py`）先保存原值并起定时回写（§0.7），再写 0；第 1、2 项要所有者在场看屏幕、用手指碰屏幕，没人时写 `等人工`，不要用 `adb shell input tap` 代替（那不经过触摸硬件）。第 3 项的一晚记录可以无人值守，读数写文件。

#### MODE-1 ModeController 与改名

结果：2026-11-05，MODE-1已合入并接现有sensor tick，21项纯策略+12项运行时/天文JVM用例通过；schema配置、bundled tzdb/DST与Solar精确缓存、11语改名与文档sun选项齐全。0.1.16已装，MODE-2中文时间12/控制栏15项实机通过（含恢复），证据 `artifacts/mode/MODE-2/0.1.16/qualification.json`；未测黑屏/偷看或性能。

- [x] `core/ModeController.kt`：纯逻辑，输入时钟、lux、`Sun`、手动状态、触摸事件，输出 `Mode` 和下一次评估时刻；规则见 `docs/45` §4。
- [x] 主机单元测试（表驱动）：光线抖动与迟滞、日落跨越、跨午夜、夏令时日、时间不可信、极昼极夜、手动覆盖到期、ENGAGED 推迟、睡眠开始被推迟。
- [x] 改名“夜间”到“红移”：11 种语言（`shared/i18n/system/`、插件表）、`docs/04` §1.2、设置标签；`attention.to_asleep_s` 的标签（DEC-9）。内部名字不改。
- [x] 把 `theme.mode` 的 `sun` 选项补进 `docs/04` §9。

#### MODE-2 设置与控制面板

结果：2026-11-05，8可见+9隐藏设置键、按层消费旧theme.night迁移、公开API10 mode读/事件与受权限/真实近期触摸的setMode齐；中文横竖时间编辑与控制栏实际通过。11语词源一致，但其它语言排版仍后置；不以MODE-2通过冒充MODE-3黑屏通过。

- [x] 新控件 `time`（HH:mm，5 分钟一档）。SET-04 已完成，不重开，在这里做：schema 加类型并登记 `x-thinkos-api`（沿用当前 API 10），重新生成 Kotlin 和 Go，`FormView` 渲染与输入，11 种语言，schema 正反例，真机截图；外观沿用现有控件风格，按 §3.1、§3.2，不加说明文字。
- [x] 新一级分类“模式”（图标 `moon-star`，SET-07）和 `docs/45` §5 的设置项；`theme.night` 迁移进 `redshift.trigger`（旧值为关就迁成“关闭”，写迁移测试）。
- [x] 控制面板两个开关：红移、睡眠；点一下是手动覆盖。
- [x] `tos.device.mode()`（只读）和 `mode` 事件；按 `docs/06` §4.1 加词汇。

#### MODE-3 睡眠

结果：2026-11-05，0.1.16已接静音/恢复日志、媒体暂停/拒绝/迟到世代和投屏接线。实机零PCM旧播放暂停、唤醒不自动续播、明确播放后前进以及五流0/日志清理通过，见 `artifacts/mode/MODE-3/0.1.16/pcm-1/results.json`。非零恢复为主机fake证据；DLNA真会话补验见下一段，AirPlay/Miracast真会话、黑屏/偷看/整晚仍待，不把这项总体验收勾满。

DLNA补验（2026-11-05）：**0.1.23已装**，真实标准SOAP SetURI/Play、零PCM解码与前进、原生控制栏睡眠点按、旧队列结束/释放接管、新Play/SetURI拒绝、DAY空队列/无旧URI/不自动播、明确重连后再次前进，14项外部+2项native检查通过。60秒有界全零WAV；先前10秒素材自然结束的失败与恢复记录保留，没有据此报通过。原三项投屏开关始终true且未编辑；设置只经现有writer flush核对语义，radio/welcome/身份和完整历史只读核对；原home.main/digital、zh-CN、空媒体/无manual、DO/HOME/LockTask、五流0和boot_count28恢复，精确owned缓存/反向端口已清。仅证明DLNA这条真会话，不覆盖手机SSDP、其它协议、黑屏、非零硬件恢复和整晚。证据 `artifacts/mode/MODE-3/0.1.23/dlna-2/qualification.json`。

等人工：MODE-0黑屏/硬件触摸通过后才接入背光0与偷看；当前不把最低亮度NIGHT当作全黑。

- [ ] 黑屏、背光缓降；双击偷看 10 秒和“结束睡眠”；离开睡眠先到红移最低亮度。
- [ ] 所有 ThinkOS 音源统一查询 `ModeState.sleep`；媒体暂停、新播放被拒；系统音量流兜底静音并保存、恢复。
- [ ] 投屏新会话拒绝、已有的结束；与 `MediaEngine` 和 `CastHost` 接线。

#### MODE-4 联动

蓝牙音箱、通话实现后接入（见 `docs/45` §3）。

验收（总）：

- [ ] 设备：调试命令注入环境光和时间；三种模式的截图（红移符合 `docs/04` §1.2，睡眠全黑）；`dumpsys audio` 证明静音；睡眠中播放和投屏请求被拒；退出后音量恢复。
- [ ] 睡眠 8 小时：没有亮屏、没有声音；双击偷看可用；CPU 不高于基线。
- [ ] 时钟精度（先告知）和冷启动不回归；`test_ui_restraint.py` 通过。

## 11F. BT：蓝牙音箱

设计在 `docs/46-bluetooth-speaker.md`，决定在 ADR-049。所有者 2026-11-04：打开蓝牙后，设备可以当蓝牙音箱，手机连上它就能把它当作蓝牙音频设备来播放。开发和测试由开发 Agent 做，叠加层模块的安装和重启要人工。状态：只有调研和方案，蓝牙没有打开过。

#### BT-0 可行性实验（先做；不通过就停）

结果：2026-11-05，第1步模块/构建与第2步主机方案已完成：原Bluetooth.apk两bool只读确认false，vendor静态加载路径有AOSP8.1及本机已有层证据；新增7项主机检查、构建、常规7项BT+原18项wfdd通过，资源APK为API27且无代码/so。ZIP SHA-256 `ff77ddc5ede6915f8776853c182e38cf4b896c02d02e71109ba2a1cc1f129b98`（5108字节），产物/原始dump见`artifacts/bt/BT-0/builds.json`。本模块未安装、未重启、蓝牙未开启；第2步本模块实际加载和后续配对/听声未验，整项不勾完成。

- [x] 第1步：仅两项资源的模块、固定ROM/API27/arm门禁、构建与主机测试通过。
- [x] 第2步主机部分：确认静态vendor目录映射及重启方案，保留官方源和本机只读证据。
- [ ] 第2步真机部分：本模块实际扫描/idmap/目标资源加载，待人工安装重启。

等人工：按[BT-0人工安装与回滚](../artifacts/bt/BT-0/human-steps.md)核对固定ZIP SHA后安装并重启（OWN-28未授权Agent）；其后再核对static/idmap。开蓝牙、手机配对及听声须所有者在场，不能以主机通过代替。

| # | 内容 |
|---|---|
| 1 | 做叠加层模块 `thinkos-btsink`（目标 `com.android.bluetooth`，把 `profile_supported_a2dp_sink` 和 `profile_supported_avrcp_controller` 改成 true）；仓库里有 `system/build-immersive.sh` 这类不用 Gradle 的先例 |
| 2 | 先确认 8.1 上叠加层的加载办法：目录、静态还是动态、是否要重启；不行就用 Vector 钩子备选 |
| 3 | 人工装好并重启（和 CAST-1 同一次，OWN-28 未授权）；打开蓝牙，`dumpsys bluetooth_manager` 里应有 A2DP sink |
| 4 | 手机配对并播放（**会出声，所有者在场**）：声音从扬声器出来；断开、重连；**配对界面在锁定任务下的表现**；系统层代做 `setPairingConfirmation` 的办法（root 守护进程或 Vector 钩子） |
| 5 | 记录：接收端的编码（SBC？AAC？）、音量（AVRCP 绝对音量）、蓝牙类别（是否显示成扬声器） |

验收：每一项有数据或明确的“做不到”，放 `artifacts/bt/BT-0/`，结论写回 `docs/46` §6；**通不过就把原因写回并停下**。

#### BT-1 OS 服务、设置与配对界面

- [ ] `BluetoothSpeaker` 服务；设置新增一级分类“蓝牙”（开关、名称、已配对列表、“允许配对”开 120 秒）；控制面板的蓝牙开关；OS 自己的配对界面（手机名加 6 位码），ADR-044 不加说明文字。
- [ ] 默认关；窗口外不可被发现；最多 4 个已配对手机，可忘记（OWN-27）。

#### BT-2 音频优先级与模式联动

- [ ] 核对 `MediaEngine` 是否处理音频焦点丢失；统一“音频所有者”规则（媒体、投屏、通话、蓝牙），后开始的赢。
- [ ] 睡眠中不可被发现、不接受新连接，已连接的静音并通过 AVRCP 暂停；红移不受影响。

#### BT-3 真机验证

- [ ] 安卓手机、iPhone、Windows 电脑各连一次并播放；重启后自动重连。
- [ ] 连续 8 小时；Wi-Fi 大流量时的表现（设备连 5 GHz，蓝牙与 2.4 GHz 共存没测）。
- [ ] 睡眠模式下静音且不可发现。

## 11G. REL：首发交付与分发渠道

所有者 2026-11-05：快速交付一版固件，重点是 OTA，APK 和系统模块能自动验证、自动更新；分发渠道是 GitHub release 加国内源 CNB（`https://cnb.cool/kiwimoe/ThinkOS`）。§7 开头的架构审计列了缺口，本组把它们串成一条发布路径。设计在 `docs/34` §4.10。

**目标：**一台装了正式签名首发版的机器，不连开发机，能从 CNB 或 GitHub 取到下一版的签名目录，验证、下载、安装并通过健康检查；任何一个站点不可用、落后或被劫持，设备都不受影响。

**首发是装上去的，不是推上去的。**现有所有机器都是 debug 签名，Android 不允许正式签名的包覆盖它们；首发版要靠刷机镜像或逐台手工迁移装上（REL-5）。从首发起才靠 OTA，所以验证 OTA 的第一次真实推送是**第二个版本**。

**对 CNB 的核对（2026-11-05；Mac 经代理访问，不代表设备；样本是别人的公开仓库，我们的仓库还是空的）：**

| 项 | 结果 |
|---|---|
| 设备到 `cnb.cool`、`asset.cnb.cool` 的 TCP 443 | 各测 3 次全通；`ping` 约 23–33 ms |
| 仓库 `kiwimoe/ThinkOS` | 存在，公开，页面写“Repository not initialized”（没有分支、tag、release） |
| 匿名下载发布附件 `https://cnb.cool/<组织>/<仓库>/-/releases/download/<tag>/<文件>` | 样本（`haorwen/cnb-cli` 2.0.0，29.9 MB）：HEAD 直接返回长度；GET 先 302 到 `https://asset.cnb.cool/assets/t/<令牌>`，再返回 206，带 `Accept-Ranges: bytes` 和 `Content-Range` |
| 连续 25 次 1 MiB 分段请求 | 全部 206。接口说明里“302 跳转 12h 有效最多 10 次”是 `share` 参数的描述，没看到它限制普通下载 |
| 匿名读仓库原始文件 `https://cnb.cool/<组织>/<仓库>/-/git/raw/<分支>/<路径>` | 样本返回 200，带 `ETag` |
| 匿名调 `api.cnb.cool`（含 `releases/latest`） | **401**。设备只能走网页路径，不能走 API |
| 免费额度 | 仓库存储 100 GiB；对象存储（制品、LFS、图片、附件）100 GiB；没查到单文件上限（[定价](https://docs.cnb.cool/zh/pricing.html)） |
| 上传（所有者本机用令牌） | 三步：`POST /{repo}/-/releases/{id}/asset-upload-url`（`asset_name`、`size`、`overwrite`、`ttl`），把文件 `PUT` 到返回的预签名地址，再 `POST …/asset-upload-confirmation/{upload_token}/{asset_path}`，确认后附件才挂到版本上（[接口规范](https://api.cnb.cool/swagger.json)） |
| 客户端（读代码） | `UpdateHttpClient` 没有主机白名单，手动跟随最多 3 次重定向并带上 `Range`；`Fetcher` 每 1 MiB 一段、每段重新请求原地址、校验 `Content-Range`。所以 CNB 的 302 跳转预期可用；**2026-11-05 已在设备上用生产下载器跑通，见下** |

**设备实测（2026-11-05，REL-0 第一部分，`artifacts/update/REL-0/part1-20261105/`）：**用设备上的生产下载器（手动跟随跳转、1 MiB Range 分段、SHA-256 校验、系统加自带信任库），完整下载别人公开的发布附件并校验通过：

| 来源 | 文件 | 用时 | 速度 |
|---|---|---|---|
| CNB（`cnb.cool` 302 到 `asset.cnb.cool`，206） | 29.9 MB | 41 秒 | 724 kB/s |
| GitHub（`github.com` 302 到 `release-assets.githubusercontent.com`，206） | 9.7 MB | 793 秒 | 12 kB/s |

两条路径的证书链、跳转、Range 在这台设备上都通；**CNB 比 GitHub 快约 60 倍**，所以 CNB 必须排第一，GitHub 只是慢速兜底（一个 10 MB 的 APK 要 13 分钟）。只测了一个时段，上传一侧没测。

**没验证的：**我们自己的 CNB 仓库公开后能否匿名下载（仓库还是空的，样本是别人的）；`ttl` 不设时附件是否永久；预签名上传地址的有效期（第三方文档称只有 10 秒）；同名附件覆盖后是否立刻读到新字节；CNB 页面路径 `/-/tree/v<版本>` 是否可用（`release.py` 只检查后缀）；设备 IP 上更大体量下是否被限速。

**实现进度（2026-11-05，已由架构角色按所有者指令直接实现）：**

| 项 | 内容 | 验证 |
|---|---|---|
| 自动选源 | `UpdateCatalogClient`、`UpdateSourceHealth`、`Fetcher`（健康排序），默认源 CNB，`{channel}` 占位 | 更新包 JVM 测试；设备上更新组件回归 15 项加续传 1 项通过（`artifacts/update/REL-1/ota-device-final-20261105/`） |
| 目录里的签名镜像列表 | `catalog.json` 的 `mirrors`，`release.sh --catalog-url` | JVM、Python、Go 契约测试 |
| 发布工具 | `tools/publish.py` | `tools/tests/test_publish.py` 12 项（本机假 CNB 和假 `gh`） |
| 公开仓库导出 | `tools/export_public.py` | `tools/tests/test_export_public.py` 5 项；导出的目录自己构建（release 包 8.2 MB，只有 armv7，没有 FairPlay 库）并通过 1152 项 JVM 单元测试和 424 项 Python 测试 |
| 构建开关 | `release.py` 默认关 FairPlay 并核对 APK；`thinkos.sourceUrl` 默认 CNB | `test_release_tools.py` |
| 渠道实测 | 设备上用生产下载器完整下载 CNB 与 GitHub 的公开附件 | `artifacts/update/REL-0/part1-20261105/`：CNB 724 kB/s，GitHub 12 kB/s |
| 修了一个会让公开版 OTA 失效的隐患 | `.gitignore` 的 `*.pem` 把设备内置的公开 CA 根证书 `roots.pem` 也挡掉了：干净检出的仓库构建出的 APK 没有信任锚点（`UpdateTrust` 抛“Missing update CA roots”）。已加例外并加回归测试 `GitIgnoreTest` | 导出目录的构建与测试 |
| 测试偶发失败的原因 | 全套 Python 测试有两次出现“353 项、7 个错误”（`openssl pkey` 失败），单独重跑几十次都通过。原因：Homebrew OpenSSL 4.1.0 读加密私钥时，调用方 stdin 是 socket 就失败（管道、文件、`/dev/null` 正常）。`release_tools.run` 和三个测试的 openssl 夹具现在都把 stdin 接到 `/dev/null`。这也是“自动化环境里签名会失败”的隐患，已修 | 用旧写法对照复现 rc=1，修复后的 `release_tools.run` 成功 |


**仍未做、不要误以为做了：**真实钥匙和证书迁移、真实上传（预签名上传对请求头的要求只在假服务上测过）、我们自己的 CNB 仓库的匿名下载、GitHub 默认源、OTA-3 里 7 条“真实来源”验收、OTA-5 设备演练、模块更新器的备用机演练、FairPlay 关闭后的 AirPlay 回归。FFmpeg 对应源码存档约 469 MB，每次发布都会把它复制进发布目录并上传到每个站点，建议以后改成只在 FFmpeg 版本变化时上传一次、其余版本链接到它（需要改 `release.py` 的 GPL 附件处理，另开任务）。

#### REL-0 渠道预演（不需要正式钥匙）

- [x] **第一部分，不需要授权（2026-11-05 完成，结果见上表）：**在设备上用生产的 `Fetcher` 和 `UpdateHttpClient`（测试专用探针，不进主流程），对别人的公开 CNB 附件（`haorwen/cnb-cli` 2.0.0 的 `cnb-cli`）只取前 12 MiB 分段，不保存、不执行、不校验内容；对一个公开的 GitHub release 附件做同样的事。记录：`cnb.cool`、`asset.cnb.cool`、`github.com`、`release-assets.githubusercontent.com` 的证书链是否通过，302 次数，`Range` 是否透传，逐段耗时和 MB/s。早、中、晚各一次（目前只测了晚上一次，其余时段仍可补测）。证据放 `artifacts/update/REL-0/`。探针是 debug 版的广播命令 `fetch_probe`（`DebugReceiver`），只下载并校验后删除，release 包里没有。
- [ ] **第二部分，等所有者（OWN-33）：**所有者初始化 CNB 仓库并授权或代为上传一个**测试 release**。在最终布局下放一份测试目录（用 debug 构建的测试钥匙签名，只有 debug 包认）和一个测试 APK，从设备走真实更新流程。要测出：匿名下载是否可用；`.sig` 是否可取；覆盖同名附件后是否立刻读到新字节；`ttl` 不设时附件是否永久（上传后查接口里的过期字段，几天后再查一次）；CNB 落后 GitHub 一个 `seq` 时设备怎么表现（先到先用还是取最大 `seq`，结果写进 `docs/34` §4.10）；CNB 返回 404、超时、半截文件时是否换源。测完删除测试 release。
- [ ] 验收：第一部分有数据；第二部分每项有数据或明确“做不到”；没做的写明原因。

#### REL-1 默认源与 `{channel}` 占位

- [x] `UpdatePolicy.sources` 支持 `{channel}` 占位（`update.sources` 和 `update.mirror` 都可用），替换成 `stable` 或 `beta`。JVM 测试：完整 `.json` 地址带占位、根地址形式仍可用、非法字符、最多 8 个、占位出现在主机名或端口里不展开（2026-11-05 完成，`UpdatePolicyTest`）。
- [ ] `update.sources` 默认值（`shared/schema/settings/system.json`，隐藏项），国内源在前。**CNB 一条已写入（2026-11-05）；GitHub 一条等 OWN-31 给出仓库名**：
  - `https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/ota-{channel}/{channel}.json`
  - `https://github.com/<OWN-31 的 GitHub 仓库>/releases/download/ota-{channel}/{channel}.json`
- [x] 不放进默认源：`raw.githubusercontent.com`、jsDelivr（2026-11-05 重测，时通时不通）、GitHub Pages（不通）。
- [ ] `docs/34` §5.2 插件目录的地址顺序（现在写 jsDelivr、raw）同步改成同样的 release 布局，在 REG 任务里落实。
- [ ] 默认值写进 APK 之后，地址写错只能靠 `update.mirror` 手改或再发一版，所以定稿在 REL-0 第二部分之后；GitHub 地址没定之前留空，不填猜的。**这一条不再挡首发：**目录可以带已签名的 `mirrors`（见下一条），GitHub 仓库名定了以后写进下一版目录即可，设备会自己并入候选源。
- [x] **目录里的镜像列表（2026-11-05 完成）：**`catalog.json` 加可选 `mirrors`（≤ 8，互不相同，路径以 `/<通道>.json` 结尾），Kotlin 和 Go 模型已重新生成；设备从最新已验签目录（认证缓存）取它，排在配置的来源之后、总数 ≤ 8；`release.sh prepare --catalog-url`（可重复）写入。`UpdateCatalogTest`（校验与边界）、`UpdateCatalogClientTest`（先学到再并入、配置源失效时靠学到的镜像仍可更新、验签失败的镜像不能获胜、八个配置源时不并入）、`test_release_tools.py` 通过。
- [x] **自动选源（2026-11-05 完成）：**目录检查不再逐个等超时。按配置顺序启动，上一个失败或沉默 3 秒就启动下一个；拿到第一个通过验签的目录后，其余源再给 4 秒，**取序号最高的**（落后的镜像说“没有新版”不能盖住别处的新版；仍受本地序号下限约束，不会回退）。`UpdateSourceHealth` 记住哪些主机不通（按检查次数计时，没有 RTC：失败后接下来 1、2、4、8 次检查排在最后，之后恢复原位置），下载时先试能通的源，不通的源仍会在最后尝试，从不丢弃。8 项 `UpdateSourceHealthTest`、5 项新增 `UpdateCatalogClientTest`（沉默的第一个镜像、落后镜像、永不应答的镜像、全部失败、记住不通的镜像）和更新包共 250 项 JVM 全部通过。设备上 `update.sources` 默认值变化后，OTA5 UI 演练脚本改为接受“空或出厂默认”。

#### REL-2 发布工具（Agent 写，所有者运行）

`release.sh prepare` 只在本地准备，没有往站点上传的工具。新增 `tools/publish.py`：

- [x] （2026-11-05 完成，`tools/publish.py`，`tools/tests/test_publish.py` 12 项通过）输入是 `prepare` 的输出目录（只认其 `release-preparation.json` 且 `published=false`），目标站点 `--site cnb`、`--site github`。
- [x] 令牌只从环境变量读（CNB：`THINKOS_CNB_TOKEN`；GitHub：所有者已登录的 `gh`），不进参数、日志、仓库、CI；CI 环境拒绝（复用 `release_tools.require_owner_host`）。
- [x] 每个站点的顺序：创建或更新版本 `v<版本>` → 上传源码包、GPL 附件、APK、模块 → **匿名读回**，与 `SHA256SUMS` 核对 → 上传目录到滚动版本 `ota-<通道>`，先 `.json`、紧接着 `.sig`（中间几秒的“新目录配旧签名”会验签失败，设备退避或换源，无害）→ 再匿名读回目录，用公钥验签。
- [x] 默认 `--dry-run`（打印所有请求，不发出）；真正上传要显式 `--execute` 并再确认一次。
- [x] 一个站点失败不回滚另一个，输出明确列出哪边已发布；重复运行幂等（同名覆盖）。CNB 上传不设 `ttl`，或按 REL-0 的结果设置。
- [x] 不做：不建仓库、不改可见性、不推代码、不打 tag（这些是所有者的动作）。
- [x] 主机测试（本机 127.0.0.1 的假站点，不发外网请求）：成功、中途断流、确认步骤失败、同名已存在、令牌缺失、CI 环境拒绝、dry-run 不发请求、读回摘要不符时停止且不更新目录。
- [ ] 验收：主机测试通过（已通过）；真实上传在 REL-0 第二部分或 REL-5 里由所有者运行，结果留证。
- 实现要点：CNB 的 `PUT` 流式上传，不带令牌；确认地址和令牌只发给 API 主机，否则拒绝；上传 `overwrite` 和 `ttl=0`（永久）；大于 64 MiB 的文件（469 MB 的 FFmpeg 源码存档）读回只比较长度和首尾各 1 MiB；`--tag-suffix` 预演不要求目录里已经写着真实 tag，`--cleanup` 只删带后缀的预演发布。**没验证：**CNB 预签名上传对请求头的要求（`Content-Type` 等）和真实的确认流程，只在本机假服务上测过，所以第一次真实上传必须是预演。

#### REL-3 公开源码仓库与 GPL

- [x] （2026-11-05 完成，`tools/export_public.py`，不是 `.sh`）按 OWN-31：（Agent 写，所有者运行）从已提交的发布提交导出受跟踪文件，排除 `artifacts/`、个人路径和 PUB-2 扫描出的内容，写成干净历史的单个提交；之后每版在同一目录追加提交。`release.sh prepare` 要求干净工作区，并且用 `git archive` 生成对应源码包，所以**要在这个导出的仓库里运行**（`release.py` 以自己所在的仓库为根，这一点我没逐行核对，开发 Agent 先核对）。
- [ ] PUB-2 扫描：导出工具内置扫描，**拦截**真实的个人目录路径和私钥内容（已把 `tools/REGISTRY-FIXTURE.md` 里唯一一处 `/Users/…` 改掉），私有地址、邮箱、`ssid` 只提示（试导出：174 个私有地址、41 个邮箱、1 个 ssid，几乎都是测试夹具和第三方许可证）；所有者推送前仍要看一遍提示项。
- [ ] `--source-url`：`https://cnb.cool/kiwimoe/ThinkOS/-/tree/v<版本>`，推 tag 后打开确认可用；GitHub 的地址写进更新说明。
- [x] `SOURCE_CODE_URL` 改成构建属性 `thinkos.sourceUrl`，默认 `https://cnb.cool/kiwimoe/ThinkOS`（2026-11-05；GitHub 地址定了以后可用 `-Pthinkos.sourceUrl=` 覆盖），关于页显示它。
- [ ] GPL 附件（FFmpeg 对应源码存档 `thinkos-ffmpeg-source-v1.9.0+1`、`LICENSE-GPL-3.0.md`、ThinkOS 源码包）每个站点的版本页都要有，不能只放在一边。
- 试导出结果（2026-11-05，从工作树导出到 `/private/tmp`，仅试验）：2120 个文件；导出目录里 424 项 Python 测试全部通过；Gradle 完整构建见 REL-4。
- [ ] 验收：从公开地址匿名下载的源码包，能按其内容复现出与发布版相同的 APK 摘要（或写明哪些不能复现及原因）。

- [x] **源码包不带 `artifacts/`（2026-11-06）：**0.1.31 在开发仓库里准备，`git archive` 把 `main` 里已跟踪的 2319 个验收文件（截图展开后 240 MB）都打进了 207 MB 的源码包，发现后在上传开始前停掉，没有上传。`release.py` 现在用 `:(exclude)artifacts` 打包并同步放宽“不漏文件”检查（真源码约 18 MB），`test_release_tools.py` 加了回归；0.1.31 的 CNB release 作废，改发 0.1.32。FFmpeg 对应源码存档（447 MB）是 GPL 要求，不属于证据，仍要附。
- 开发机现在是正式证书。`android/keystore.properties`（被 `.gitignore` 忽略）要一直留在 `android/` 下，否则 `tools/deploy.sh` 装的 debug 包会因证书不符装不上。

#### REL-4 首发构建参数

- [x] 按 OWN-32（2026-11-05 完成）：`release.py` 默认带 `-Pthinkos.fairplay=false` 构建，`--fairplay` 才打开；`inspect_apk` 核对 APK 里是否有 `lib/armeabi-v7a/libthinkos_playfair.so` 与所选开关一致，不一致就停；`release-preparation.json` 记录 `build.fairplay`。`BuildConfig.DISTRIBUTION_GPL` 仍为 true（FFmpeg）。
- [ ] `docs/36` LIC 一节写着“设备 opt-out 回归待测”：在关闭 FairPlay 的包上跑这个回归（AirPlay 屏幕镜像在没有该库时的表现）。
- [ ] 版本号与首发 `--bootstrap-code`：首发的 `code` 要大于以后任何要被它替换的版本；记录选用的值和理由。
- [ ] 更新说明 `notes.json`：11 种语言，每种 ≤ 2048 字符（`release.py` 的限制）。
- [ ] **首发目录不带模块项**（`modules` 为空）。模块自动更新要先有 MOD-2 在备用机上的演练结果（更新器首装、守卫、坏模块恢复），通过后随下一版加入。
- [ ] 验收：构建产物只有 `armeabi-v7a`；`unzip -l` 无 `arm64`；`release-preparation.json` 的开关与说明一致。

#### REL-5 首发路径与迁移（多数是人工）

| # | 内容 | 谁 |
|---|---|---|
| 1 | 提交（OWN-1），导出公开仓库（REL-3） | 所有者 |
| 2 | 生成钥匙、放公钥、证书迁移（OWN-6，OTA-5 第 1 步） | 所有者 |
| 3 | 备用机上做 OTA-5（局域网镜像） | Agent，所有者在场 |
| 4 | 先发到 `beta` 通道：1–2 台机器设 `update.channel=beta`，至少过一个安静时段（凌晨 3–6 点）看自动安装结果，并做一次故意崩溃的回滚 | Agent 加所有者 |
| 5 | 发 `stable` | 所有者运行 `tools/publish.py` |
| 6 | 现有机器迁移，逐台：备份数据、放弃 Device Owner、卸载 debug 包、装正式包、重设 Device Owner（`tools/provision.py`，WEL-3）、验证。新机和重刷的机器走金镜像（WEL-5，参考机必须是正式签名） | 所有者 |
| 7 | 第二个版本（哪怕只改版本号）作为 OTA 的第一次真实推送：先只开 CNB 源，再只开 GitHub 源（用 `update.mirror`），各验收一次 | Agent 加所有者 |

#### REL-6 首发门槛

首发（对外发布）前必须全部满足：

- [ ] OTA-5 第 2、5、6、7、9 项在真机上有证据。
- [ ] OTA-3 的 7 条验收有“真实来源”的证据。
- [ ] OTA6-FIX-2（损坏的 `pending.json`）修复并有测试；OTA-6 的 P1 在源码里已修（F1、F3），复核者确认。
- [ ] REL-0 到 REL-4 完成。
- [ ] 在第二个版本上，CNB 和 GitHub 各自单独作为唯一来源推送成功一次；断开其中一个，另一个仍可用。
- [ ] PUB-2 扫描通过；`THIRD_PARTY_NOTICES.md` 与实际打包一致。
- [ ] 冷启动 < 3 秒、时钟精度不回归（性能测试，先告知所有者）。
- [ ] 汇报四件事：做了什么、怎么验证、哪些没验证、要所有者做什么。

## 12. PUB：公开发布（所有者决定时机）

| # | 内容 | 谁 |
|---|---|---|
| 1 | 新建干净历史的公开仓库的方案：不带 `artifacts/` 和个人邮箱；现在的仓库留作私有存档 | 你写方案和脚本，所有者执行 |
| 2 | 公开前扫描：11 个文档或脚本里的局域网地址和 `ssid` 字样逐个看；私钥和令牌；个人路径 `/Users/takuenn`、`~/ThinkSmartView` | 你 |
| 3 | ADR-043 清单核对：LIC-01 到 LIC-06 全部完成 | 你 |
| 4 | 公开仓库已经在，第一个公开 OTA 才能发（GPLv3 第 6 条） | 所有者 |
| 5 | `stable` 通道，先 `beta` soak | 所有者触发 |
| 6 | 作者文档、装机指南上线 | 你写，所有者发布 |

验收：

- [ ] 外部账号从零走通：装机、引导、OTA、提交插件。
- [ ] 通道文档的 `source` 指向这个版本的公开标签，能下载到完整源码和 FFmpeg 扩展源码。
- [ ] 所有者确认了 OWN-5、OWN-12、OWN-13。

## 13. OPEN：开放界面（等 OWN-2）

设计见 `docs/32-open-ui.md`。**所有者答复 §8 和附录 A 之前不要开始。** 在此之前按现有 `AGENTS.md` 工作。

| 任务 | 内容 | 验收 |
|---|---|---|
| OPEN-P0 | 用户确认方向和 §8；API 8 已拆提交（BASE-01）；把附录 A 改进 `AGENTS.md` | 所有者答复记入 ADR-039 |
| OPEN-P1 平等 | 强权限改为设备上确认并写授权表；去掉“摘要等于出厂”的判断（`PluginRuntime.kt` 的 `grantedPermissions`、`requireSystem`、`requireSettingsAdmin`）；角色由绑定表决定（`DeckRouter`）；`ui.bindings` 和解析顺序；安全模式等于出厂绑定，不写死 `clock`；`tos.ui.providers`、`bind`；设置加“界面”分区；原生确认框；救援菜单加“恢复出厂绑定” | 用目录推一个替换版 `panel`、`home`、时钟页，设备上选用，重启保留，换回出厂；让它崩溃或空白，自动回退；救援菜单能重置绑定 |
| OPEN-S1 可行性（和 P1 并行） | 场景内核最小切片，用它写时钟（数字和指针，含扫秒）和滑块（跟手）。跑设备测试前先告知 | `32` §6.1 的门槛全部达标：时钟判据不放宽；待机 CPU 不超过基线 +20%，janky < 5%；手指到像素不经过 Lua；松手事件 ≤ 100 ms；UI 库常驻 Lua 内存 < 3 MiB，PSS 增量 < 10 MB；冷启动 < 3 秒。不达标按 §6.1 止损，交还所有者决定 |
| OPEN-P2 契约 | 组件和版式可以由插件提供，先只允许声明式模板加现有原语；第一批出厂提供方 `tabs`、`toggle`；词汇冻结（§8 第 4 问） | 替换版 `tabs` 跑 `player`，截图和行为一致 |
| OPEN-P3 场景内核（API 按 §0.3） | 原语、信号表达式、动画、手势、虚拟化滚动、`slot`；`shared/schema/contracts/` 和生成代码；打包器、Go 和 Kotlin 校验；场景快照；`scene_dump` 调试命令 | 内核自己的 JVM 和原生 Lua 测试；§6.1 门槛 |
| OPEN-P4 移植 | 约 26 个提供方改写成出厂 Lua，按 `32` §6 的难度顺序；每过一个删一个原生实现。设置页是合适的第一个试点：屏幕上没有时钟，使用时间短，已经是 Lua 插件，前提是 SET-02 的 `link` 契约和 P1 的权限规则 | 每项：截图和原生版的像素差在容差内；ADR-029 度量；janky、CPU、PSS 不超基线 +20%；冷启动 < 3 秒 |
| OPEN-P5 外壳 | 翻页范式、手势映射、角色调度也可由插件提供 | 可选，P4 做完再问所有者 |

每个阶段都要守住的：§3.1 的界面规则由出厂提供方继续遵守；第三方提供方的外观是用户自己的选择，不受这条约束。

## 14. DEBT：其它欠账（所有者点名才做）

| 项 | 位置 | 备注 |
|---|---|---|
| M0 两项传感器验收：遮光切主题、接近唤醒 | `docs/09-roadmap.md` M0 | 要人在设备旁边 |
| M0.5 没勾的验收 | `09` M0.5 | 实体光照、旋转要人工 |
| M1 没做的：`notify`（`plugins/notify` 是空目录）、`tos.alert`、`tos.audio`、`tos.album`、`sources`、掉帧自动降级、歌词、频谱、Navidrome、嵌入封面、`mood` 背景 | `09` M1、`22-player-plan.md`、`30` §6 | |
| 投屏真实发送端：华为 Miracast、Windows、AirPlay 视频推送、各 App 的 DLNA；确认页和 `tos.cast` 事件 | `28-casting.md` | 要所有者的手机和电脑 |
| HTTP 通知 | `18-notification-http-proposal.md` | 用户暂缓 |
| M2 Server、M3 摄像头、M4 打磨 | `09` | `server/cmd/thinkos-server` 现在是空目录；M3 先做可行性验证 |

**不做**：刷 ROM、更新第三方系统组件（Magisk、Vector、busybox、ViPER）、安卓 App 商店、差分更新、遥测、评分和评论、付费、设置页搜索、样式语言和皮肤包、把引导做成账号注册。

## 15. 总验收

全部做完后，或所有者要求阶段性验收时，按这张表走一遍。

| # | 场景 | 通过标准 | 谁 |
|---|---|---|---|
| 1 | 自动检查 | §0.4 全部命令通过，`test_ui_restraint.py` 通过 | Agent |
| 2 | 界面走查 | UI-17 的前后对比图，§3.1 每行都有结论 | 所有者看 |
| 3 | 全新设备 | 断网、无 Server、无模块，走完引导约两分钟；时钟从地区那屏起就对 | Agent 测，所有者看 |
| 4 | 跳过 | 第一屏“跳过设置”直接进首页，设备可用 | Agent |
| 5 | 设置 | 从首页长按天气改城市；系统页三步内找到任何一项；返回一层一层退；横屏两栏 | Agent |
| 6 | 自动更新 | 开着“自动安装更新”，不碰设备，安静时段装完 APK 和模块，没有弹窗；Device Owner、Lock Task、默认桌面、设置、插件状态都还在 | Agent 测，部分人工 |
| 7 | 下载中断 | 拔网、杀进程、断电后续传；坏签名、旧版本、错哈希全部拒绝；视频和投屏中不下载、不安装；时钟度量不回归 | Agent，断电人工 |
| 8 | 坏版本 | 新版启动即崩自动回旧版；坏模块让系统起不来，下次开机恢复 | 所有者在场 |
| 9 | 被攻破 | 模拟被攻破的应用对更新器发任意内容，装不了未签名的东西 | Agent |
| 10 | 第三方插件 | 确认框显示来源和权限；断网、关 Server 后照常运行；声明 `home` 或 `settings` 被拒绝 | Agent |
| 11 | 安卓 App | 按ADR-046，所有应用的物理左缘返回、底缘回ThinkOS的home首页并清空栈、顶缘打开ThinkOS控制栏；全系统不出现安卓通知/快捷设置和导航栏，含IME/原生弹窗与横竖屏（`docs/38`） | Agent，模块安装人工 |
| 12 | 恢复 | 所有插件禁用时救援菜单可用：许可证、检查更新、恢复出厂插件、重置设置、移除设备管理权限 | Agent |
| 13 | 不含 GPL 的构建 | 两个开关都关，APK 没有 GPL 库，功能按 LIC-01 降级 | Agent |
| 14 | 性能（先告知） | 冷启动 < 3 秒；待机 10 分钟 janky < 5%；AWAKE、AMBIENT CPU 不超过基线 +20%；`clock_accuracy_device.py` 达标；峰值内存 < 350MB | Agent |
| 15 | 约束 | APK 只有 `armeabi-v7a`；应用里没有 `su`；没有 WebView、Compose、GMS；设备端没有 Git | Agent |
| 16 | 公开 | 外部账号从零走通：装机、引导、OTA、提交插件 | 所有者 |
