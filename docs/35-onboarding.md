# 35 · 新用户启用引导：策划

> 最新实际包（2026-11-04）：0.1.7/1007、`2230ed9c…`已安装，同一APK断网引导45项通过，包含旧三次提示额度用尽后的手动重跑和Home后新进程续跑。UpdaterClient、root peer认证、异步系统语言/时区桥与ROM时区别名源码已实现；可选更新器尚未安装，特权设置仍待真机验证，Device Owner没有变更。插件中心另11项真实入口通过，全部音量0、设置和Wi-Fi恢复。见`artifacts/welcome/WEL-2/0.1.7/qualification.json`；下方0.1.6为前一检查点。

> 2026-11-04实现更新：所有者要求优先做出可完整点击的引导，不再停在两屏视觉原型。最终0.1.6（c77fe6c2…）已实际安装，内置welcome、独立导航层、原生表单/地区选择、进度与补遗漏、设置重跑入口以及六种真实手势练习已接通；主agent统一完成真机断网全流程44项检查，包括Home后新进程自动续跑。开发由三个subagent分工，验收由一个主agent执行。系统更新器尚不存在，设备管理步骤跳过，系统时区仍走安卓日期页；未授予语言权限、未更改Device Owner、未安装持久模块。真实新机、全部语言排版、30分钟超时与性能仍待验，不能据此勾完全部WEL。证据 `artifacts/welcome/WEL-1/report.md`；下方WEL-0及“尚不存在”等是历史现状记录。

> 全局手势以ADR-046及38-global-gestures.md为准：左缘返回、底缘首页、顶缘控制栏；旧的上滑到应用页说明已作废。排版按所有者最新要求主要检查中文。

> 2026-11-02，策划稿第二版，ADR-042（草案）。**第二版的变化（用户决定）：顺序仿照苹果的 Setup。** 正式引导流程尚未接入。2026-11-03的WEL-0已补debug原生两屏效果图（11语言×竖横屏44画面）、真实系统语言/RTL27项和跨UTC偏移时区24项。1.3倍字号48画面验证发现磁贴裁切，按DEC-4暂不加入文字大小设置。见artifacts/welcome/WEL-0/report.md；OWN-11与新机默认值仍待所有者，旧§3同偏移时区实测不能替代新的跨日期结果。
> 标记：**已核对** = 读过代码或 AOSP 8.1 源码；**实测** = 在设备 shell 上查过；**提议** = 我的设计，可以推翻；**未验证** = 开工前要上真机。
> 先读：`AGENTS.md` → ADR-015 / 017 / 019 / 020 / 039 / 040 / 041 → `19-provisioning-for-agent.md` → `33-apps-and-back.md` → `34-ota-and-plugin-registry.md` → 本文。

## 1. 要做什么

新用户第一次开机时，用两分钟左右把设备调到能用，并学会那几个看不见的手势。

- **顺序仿照苹果**：先问你好和语言，最后才是手势和欢迎；一屏只问一件事，用不上的屏自动跳过（§4.3、§4.4）。
- **不强制**：每一屏都能跳过或直接继续，跳过全部也能正常用。
- **不需要**账号、网络、Server（ADR-015）、系统模块。
- **不困住人**：引导坏了、半途走开，设备都照常工作。
- **也是插件**（ADR-039）：步骤和文案在 Lua 里，OS 只管什么时候进入、怎么保证退得出来。

**不做**：账号注册；遥测和问卷；从旧设备迁移；把首页样式、插件推荐做成必经步骤。

## 2. 新用户的完整路径

| 段 | 在哪 | 现状 | 谁解决 |
|---|---|---|---|
| 从新机器到安卓 + Magisk | 电脑：EDL、Lineage、Magisk | `19-provisioning-for-agent.md`，给 Agent 的手册，材料都在所有者的 Mac 上 | W3 |
| 装 ThinkOS 和更新器 | 电脑 | `tools/deploy.sh`（debug 包）、`system/install.sh` | W3 |
| **第一次开机** | 设备 | **什么都没有，直接进首页** | **W1、W2** |

第一段是公开后最大的门槛，但它和设备上的引导是两件事，放在 W3（§5）。

## 3. 现状（已核对 / 实测）

| 事实 | 位置 | 影响 |
|---|---|---|
| 代码和插件里没有任何首次运行的逻辑 | 全仓搜索 | 从零做 |
| 19 号文档用 `settings put` **跳过了** Lineage 自带的开机向导；`org.lineageos.setupwizard` 还在 | §6.1、实测 | 不依赖它，语言和 Wi-Fi 要自己引 |
| **语言**：`CHANGE_CONFIGURATION` 是 `signature\|privileged\|development`，可以 `pm grant`；ROM 里有 `LocalePicker.updateLocales` 和 `IActivityManager.updatePersistentConfiguration`；ThinkOS 的 11 种语言 ROM 全有。manifest 还没声明这个权限 | WEL-0用已持有权限的shell UID2000实际切en-US/ar-EG/ur-PK，27项通过，原完整语言列表、`userSetLocale`和`persist.sys.locale`均还原；没有给应用授权 | ROM方法和界面刷新已验证；应用UID调用及授权仍属于WEL-2 |
| **地区**：`zones.tsv` 覆盖 248 个地区，**217 个只有一个时区**，31 个有多个（美国 29、俄罗斯 26、巴西 16、中国 2、德国 2 等）；`ZoneNames.country` 能出本地化的地区名 | `shared/tzdata/`、`tz/ZoneNames.kt` | 选地区就能定时区、定单位制（§4.6） |
| ROM 的 `tzlookup.xml` 有 247 个国家的时区表，但每个国家的第一项**不是**默认：印尼是 `Asia/Jayapura`，俄罗斯是 `Asia/Kamchatka`，巴西是 `America/Noronha` | 设备实测 | 多时区的地区不能猜，要问 |
| **时区应用改不了，root 可以**：Device Owner 在 8.1 没有 `setTimeZone`；`SET_TIME_ZONE` 是 `signature\|privileged`，`pm grant` 给不了；设备没有基站（`ro.radio.noril=true`），“自动时区”不起作用。但 `AlarmManager.setTimeZone` 对 root 放行 | AOSP `DevicePolicyManager.java`、`AlarmManagerService.java:1406`、`ActivityManager.checkComponentPermission`；实测 | 新机时区不对，这个以时钟为主角的产品第一眼就是错的（新机默认值**未验证**）。用系统模块解决 |
| **实测通过**：root下固定 `service call alarm 3 s16 <时区>`，真实广播刷新可见时钟、日期和太阳时刻，进程保持。香港、伦敦、Kiritimati（UTC+14）、Pago Pago（UTC−11）来回切，+14/−11实际跨本地日期 | 2026-11-03 WEL-0，24项通过；`artifacts/welcome/WEL-0/timezone-date-crossing/` | 跨偏移/日期已验证，原香港时区及八文件均还原。不是应用内设置时区能力的验收 |
| **陷阱：ROM 不认识的时区 ID 会被静默设成 GMT**（`TimeZone.getTimeZone` 返回 GMT，`setTimeZoneImpl` 照写）。ThinkOS 的 419 个时区里有 3 个 ROM 没有：`Europe/Kyiv`、`America/Ciudad_Juarez`、`America/Coyhaique` | `AlarmManagerService.java:1063`、设备实测 | 要先换成 ROM 认识的 ID（§4.6） |
| ThinkOS 的时间显示都经过 `Zones.home()`：读系统时区的 ID，再用 APK 自带的 IANA 规则计算 | `tz/Zones.kt`、ADR-019 | ROM 的时区规则旧也不影响 ThinkOS 的显示 |
| 安卓的语言页、日期页、辅助功能页都在（`LocalePickerActivity`、`DateTimeSettingsActivity`、`AccessibilitySettingsActivity`）；设备上没有 TalkBack | 设备实测 | 兜底和入口够用，读屏做不到 |
| Wi-Fi：已有 `tos.device.openAndroid("wifi")`，只收 `wifi`、`settings` 两个目标；离开和回来的 Lock Task 都处理过 | `PluginRuntime`、`MainActivity.launchOutside`、`KioskPolicy.relock` | 复用，加 `locale`、`date`、`accessibility` |
| 手势都是看不见的：左右翻页、上下切变体、顶部下拉、最左缘返回、长按设置、双指长按救援 | ADR-014 / 017 / 035 / 040 | 这是引导最该讲的 |
| **文字大小**：约70处文字按sp、约11处画布文字固定尺寸；磁贴和Aero固定网格。WEL-0真实font_scale=1.3的中英阿语/竖横屏48画面已采集，磁贴标签/相册长文裁切；测试后已恢复1.0 | `artifacts/welcome/WEL-0/font-verified/qualification.json` | 可行性未通过，按DEC-4不把文字大小加入引导；不能声称全界面字号适配 |
| `form` 的“完成”写死调用 `closeSettings`；schema 里有 `tap` 但没接上；`form` 版式只有 `body` 一个槽位 | `layouts.json`、`PageView.kt`（`formHost`） | 要小改才能复用（§4.9） |
| `FormView`原生返回已由NAV-01/02修复：`ComponentGroup.back()`会调用`FormView.back()`，先收选择器/展开行；设置的scope/分类再逐层返回 | `PageView.kt`、`FormView.kt`、`DeckRouter.back` | WEL-1需复用现有返回处理并补引导层拦截，不重复实现旧缺陷 |
| 角色枚举：`home`、`ambient`、`sleep`、`settings`、`panel`、`launcher`；`onboarding`仍不存在 | `shared/schema/plugin/manifest.json`，API9 | WEL-1待OWN-11；尚未增加`onboarding` |
| Device Owner 只能由 shell 或 root 设置（`dpm set-device-owner`），要求没有账号；19 号文档写明“由人工决定” | `07-build-deploy.md` §4.3、实测 `Accounts: 0` | 见 §4.7 |

## 4. 设计（提议）

### 4.1 分工

| 归 OS | 归插件 `welcome`（Lua + 视图） |
|---|---|
| 什么时候进入（§4.2） | 有哪些屏、什么顺序、说什么 |
| 进入后保持屏幕、挡住接管和角色页切换 | 把设置写进去（`tos.settings.*`） |
| 手势识别和练习画面（§4.8） | 练哪几个手势、配什么文字 |
| 退出路径：崩溃、半小时没人碰、Home 键 | 记住走到第几屏（`tos.store`） |

和设置页同一个思路：`settings` 是插件，控件是 OS 的。

公开进度契约 `welcome.setup`（`shape="onboarding/1"`）保持 `pending:boolean` 与 `skipped:string[]`，并兼容增加可选 `region:string`。只有引导里实际保存的显式 ISO 两字母大写地区才发布 `region`；没有显式地区记录或值格式不合法时省略，不把 locale 的兜底值伪装成选择。消费者必须在 manifest 的 `uses` 中声明，随后经 `tos.data.get(alias)` 读取，例如 radio 的自动国家选择；不得读取 welcome 私有 store，也不得据此取得位置权限。旧消费者可继续只读取 pending/skipped。本轮补了纯 Lua 契约断言，尚待主 agent 统一运行。

### 4.2 什么时候进入

- 新增隐藏设置 `onboarding.done`（数字，默认 0）。隐藏项由 Server 下发也合法，批量部署可以直接跳过。
- `DeckRouter.setPages` 第一次拿到目录时：`done` 小于当前版本、不在安全模式、有持有 `onboarding` 角色的页面 → 进入引导，否则照常进首页。**不增加冷启动时间**，它就是一个普通页面。
- **老设备不打扰**：第一次运行带这个功能的 APK 时，如果已有 `settings/local.json`，直接把 `done` 设成当前版本。
- 设置页里有“重新运行新手引导”。
- 结束时插件写 `onboarding.done` 并 `tos.page.release`。

导航上是 `NavStack` 新增一种层 `ONBOARDING`：

| | 行为 |
|---|---|
| 返回 | 可以拦截，`tos.back.intercept` 回上一屏；第一屏再返回就是“稍后设置” |
| Home 键 | 算“稍后设置”：退出，已改的设置保留 |
| 接管和角色页 | `tos.page.focus` 不生效，`ambient`、`sleep` 不切换。投屏开关默认关（`cast.*`），第一次开机不会有投屏进来 |
| 注意力 | 保持 ENGAGED，同设置页；**半小时没人碰就退出引导**，回到正常的时钟和变暗 |

半途退出不算完成：下次开机再问一次，最多问 3 次。跳过的屏记下来，设置页顶部留一行“完成设置”（§4.5）。

### 4.3 苹果的顺序

**来源要分清**：苹果官方只公布主线，后半段没有完整顺序。

| 来源 | 给出的顺序 |
|---|---|
| **官方**（[Set up your iPhone or iPad](https://support.apple.com/en-us/105132)，当前版本） | 多语言的 Hello → 文字和图标大小 → Quick Start 或手动（选语言）→ 激活（Wi-Fi）→ 为自己或孩子设置 → Face ID 和密码 → 恢复或迁移 → Apple Account → 自动更新和其它功能（Siri、明暗外观、屏幕使用时间、数据共享、Liquid Glass、Apple 智能、安全功能） |
| **官方的屏幕清单**（[Manage Setup Assistant](https://support.apple.com/guide/deployment/manage-setup-assistant-for-apple-devices-depdeff4a547/web)，不含顺序） | 确认这些屏都存在：`Language`、`Country or region`、`Data and privacy`、`Remote Management`、`Terms and conditions`、`Software update`、`Location Services`、`Appearance`、`True Tone Display`、`Device feature tour`、`Welcome` |
| **第三方**（[iGeeksBlog](https://www.igeeksblog.com/how-to-set-up-iphone-ipad/)，没写 iOS 版本，内容偏旧） | Hello → 语言 → 地区 → Quick Start → Wi-Fi → 数据与隐私 → Face ID → 密码 → 迁移 → Apple ID → 条款 → 保持更新 → Siri → 屏幕使用时间 → 分析 → True Tone → 外观 → 操作演示（回主屏、切换 App、控制中心）→ 欢迎 |

官方和第三方有两处不同：官方把文字大小放在语言之前；官方把明暗外观排在屏幕使用时间之前，第三方排在之后。对我们保留的这些屏，只有第一处有影响。另外，“软件更新”和“定位服务”谁先谁后没有来源能确定（[FoneLab](https://www.fonelab.com/resource/ios-setup-assistant.html) 只写了定位在 Siri 之前，iGeeksBlog 只写了更新在 Siri 之前），我按更新在前定的。**我没有在真机上走过当前 iOS 的流程**，你手上有 iPhone，抹掉重走一遍最准。

**逐项取舍**：

| 苹果的屏 | ThinkOS | 取舍 |
|---|---|---|
| Hello | 你好 | **照搬** |
| 文字和图标大小 | — | 暂不做（§4.5） |
| 语言 | 语言 | **照搬** |
| 国家或地区 | 地区 | **照搬**，还顺带定时区和单位 |
| Quick Start | — | 没有对应的旧设备 |
| 激活 / Wi-Fi | Wi-Fi | **照搬位置**，但可以跳过（§4.5） |
| 数据与隐私 | 数据与隐私 | **照搬**，一屏说清 |
| 条款 | 合并进数据与隐私 | **不要“同意”按钮**（§4.5） |
| 远程管理 | 设备管理 | **对应**，只在需要时出现 |
| 为孩子设置、Face ID、密码、迁移、Apple Account、Apple Pay、iCloud | — | 没有账号，也没有锁屏 |
| 软件更新 | 软件更新 | **照搬**，就是“自动安装更新” |
| 定位服务 | 位置 | **照搬位置**。设备没有 GPS，改成选城市 |
| Siri | — | 语音默认关（ADR-003） |
| 屏幕使用时间、分析、数据共享 | — | 没有这些功能，也不收集数据 |
| True Tone、外观 | 外观 | **照搬**。环境光自动切换就是我们的 True Tone |
| 显示缩放 | 外观里放屏幕方向 | 我们更需要方向 |
| 操作演示 | 手势 | **照搬位置**，放在最后，并且改成真实练习 |
| 欢迎 | 欢迎 | **照搬** |

### 4.4 ThinkOS 的顺序

最多 12 屏，标 ※ 的两屏只在需要时出现。**一屏只问一件事，一路点继续约一分钟，加上手势练习约两分钟（估算，W1 上真机计时）。不显示进度条和“第几步”。**

| # | 屏 | 内容 | 主操作 / 次操作 | 写入 | 自动跳过 |
|---|---|---|---|---|---|
| 1 | **你好** | 11 种语言的问候轮流出现（ROM 的字体都有） | 轻点任意处 / “跳过设置”、“辅助功能” | — | — |
| 2 | **语言** | 11 种语言，当前的排最前。点一行就切换并进入下一屏 | 点一行 | 系统语言 | — |
| 3 | **地区** | 按语言推荐几个，下面是全部 248 个，可搜索。点一行进入下一屏 | 点一行 | 语言的地区部分、单位制、时区（只有一个时区时） | — |
| 3a | **时区** ※ | 这个地区的时区，每行写城市和当前时间 | 点一行 | 系统时区 | 地区只有一个时区 |
| 4 | **Wi-Fi** | 当前状态；“连接 Wi-Fi”打开安卓页面，回来继续。写明“没有网络也能用” | 继续 / 暂不联网 | — | 已经联网 |
| 5 | **数据与隐私** | 三句话：不收集任何数据；检查更新和插件目录不带设备标识；插件要联网时会先问你。另加一句许可证说明（这个 APK 按 GPLv3 发布，项目自己的源码是 MIT，ADR-043）和全文入口 | 继续 | — | — |
| 6 | **设备管理** ※ | 开启后 Home、最近任务、状态栏都归 ThinkOS（§4.7） | 开启 / 以后再说 | — | 已经开启，或没有更新器 |
| 7 | **软件更新** | 夜里没人用时自动安装 ThinkOS 和系统组件，可能重启一次 | 继续（自动安装）/ 只自动下载 | `update.auto_install` | OTA 还没做时 |
| 8 | **位置** | 选城市（复用 `PlacePicker`，离线时搜内置的时区城市），用于天气和日出日落。大字显示现在的时间，让人确认对不对；不对就改时区 | 继续 / 以后再说 | `location.*`、系统时区 | — |
| 9 | **外观** | 自动 / 日出日落 / 浅色 / 深色，立即生效；红色夜间模式；屏幕方向 | 继续 | `theme.mode`、`theme.night`、`display.orientation` | — |
| 10 | **手势** | 练习 6 个手势（§4.8） | 做对自动下一个 / 跳过 | — | — |
| 11 | **欢迎** | “欢迎使用 ThinkOS”；有网时给插件中心入口 | 开始使用 | `onboarding.done` | — |

**这个顺序的好处**：

- **时间最早就对**：地区一选完，时钟就对了（有更新器时）。之前的方案要到第三步。
- **语言先于一切**：后面所有文字都是用户选的语言。
- **单位不用问**：地区是美国、利比里亚、缅甸就用英制，其余公制；想改去设置。
- **软件更新在设备管理之后**：开了专用模式，更新才一定能免提示，文案可以说得准。
- **手势排最后**：前面的设置都做完了，这时学的是真正要用的东西。

### 4.5 和苹果不一样的地方

| 地方 | 苹果 | 我们 | 为什么 |
|---|---|---|---|
| **网络** | 必须联网才能激活 | 可以跳过 | 设备要能脱离网络使用（ADR-015） |
| **条款** | 要点“同意” | 不问，只说明 | GPLv3 第 9 条：接收和运行不需要接受许可证（“You are not required to accept this License in order to receive or run a copy”），MIT 也没有要用户同意的内容。加个“同意”反而说错了 |
| **跳过全部** | 没有 | 第一屏有“跳过设置” | ADR-042 的原则：不困住人 |
| **文字大小** | 第一个问 | 暂不做 | WEL-0已测1.3倍，固定磁贴存在裁切，可行性未通过，保持DEC-4的暂不加入 |
| **Wi-Fi** | 内嵌列表 | 打开安卓页面 | 按 `29-panel-and-apps.md` §3.6 的结论，第一版不自己写列表和密码框。这一屏会离开 ThinkOS 一下，是和苹果观感差距最大的地方（§8） |
| **读屏** | VoiceOver | 只给安卓辅助功能页入口 | 设备上没有 TalkBack |
| **手势** | 只看演示 | 真实练习 | 我们的操作全靠手势，而且设备没有实体 Home 键 |

**跳过的屏不丢**：记在 `welcome` 的公开数据里（ADR-027），`settings` 声明读取，设置页顶部显示一行“完成设置”，点进去只走跳过的那几屏。不需要新机制。

### 4.6 语言、地区和时区

**语言**：

- 有 `CHANGE_CONFIGURATION` 就在 ThinkOS 里直接切，调用隐藏的 `LocalePicker.updateLocales`。WEL-0已用shell现有权限实际验证英文、阿拉伯语、乌尔都语及RTL即时刷新，设置页/返回栈/PID保留；修复了页面无障碍标题残留旧语言。应用UID的权限和`tos.device.setLocale`仍未接入。`welcome`收到`locale`事件后重新渲染。
- 测试恢复必须核对完整语言列表和`persist.sys.locale`：只用`updatePersistentConfiguration`并传`userSetLocale=false`，会出现当前语言已恢复、持久化属性还留在测试语言的情况。夹具现先由`LocalePicker`恢复，再恢复原标志，并核对两者；原始失败及修复证据见WEL-0报告。
- 权限从哪来：装机脚本 `pm grant`；或者更新器的 `GRANT_LANGUAGE`，在用户点了语言那一行时才发。manifest 要声明这个权限。
- 两者都没有：打开安卓语言页，回来继续。这是第二屏就离开 ThinkOS，体验最差，所以装机脚本一定要带上授权。

**地区**：

- 写入语言的地区部分（选了英语和香港就是 `en-HK`），和语言一起切换。安卓能正确回退到 `en` 的资源；ThinkOS 的 `tos.locale()`、相册的 Bing 市场都按它走，不用额外处理。
- 单位制：美国、利比里亚、缅甸设 `units.system=imperial`，其余不动。
- 列表用原生选择器，结构照 `TimezonePicker`：`form` 新增 `source: "region"`。推荐项来自当前语言。

**时区**：用系统模块改**系统时区**，ThinkOS 不另存一份。

- **地区只有一个时区**（248 个里的 217 个）：选完地区当场设置。
- **有多个时区**（31 个）：多一屏让人选（3a）。不猜：ROM 的时区表第一项靠不住（§3），猜错了时钟就错。
- **有更新器**：插件调 `tos.device.setTimeZone(id)`，OS 发 `SET_TIMEZONE <id>`，守护进程执行 `/system/bin/service call alarm 3 s16 <id>`。系统自己改属性、改内核时区、发广播；`RuntimeHost.timeReceiver` 已经会接这个广播并刷新界面。
- **没有更新器**：第 3 屏和 3a 不设时区，第 8 屏的“改时区”打开安卓日期页，写明“选好后返回”。
- **防 GMT 陷阱，两层**：
  - OS 先把 id 换成 ROM 认识的：在 `TimeZone.getAvailableIDs()` 里就直接用；不在就查替代表：`Europe/Kyiv` → `Europe/Kiev`，`America/Ciudad_Juarez` → `America/Denver`，`America/Coyhaique` → `America/Punta_Arenas`。这三对用 APK 自带的 tzdata 2026e 逐小时比过 2026–2037 年，UTC 偏移完全相同，要写成单元测试。表里也没有的，不改系统时区，走安卓页。
  - 守护进程只收 `[A-Za-z0-9_+/-]`、≤ 64 字节的 id；调用后读回 `persist.sys.timezone`，不一致就改回原值并回 `ERR zone-unknown`。
- **编号 3 只对 API 27 成立**：别的 Android 版本回 `ERR unsupported`，走安卓页。

### 4.7 设备管理这一屏

对应苹果的“远程管理”：决定这台设备归谁管，所以放在设置项之前。只在**没开启且有更新器**时出现。

用人话说明：开启后 Home、最近任务、状态栏都归 ThinkOS，更新不用确认；想退出，救援菜单里有“退出设备管理”。

**一键开启**（提议）：更新器增加一条固定命令 `DEVICE_OWNER_ON`，运行 `dpm set-device-owner moe.kiwi.thinkos/.admin.ThinkOSAdminReceiver`。用户点了“开启”才发。19 号文档的“由人工决定”指的就是这种人的决定；开发 Agent 仍然不碰。退路（`34` 号文档 §4.4）必须同时上。

其它系统组件不单独占一屏，免得新用户面对一堆术语：

| 组件 | 没有时 | 怎么补 |
|---|---|---|
| 系统更新器（`thinkos-updater`） | 系统组件不能自动更新；时区要去安卓页改 | 装机脚本；否则设置页写明“用电脑运行 …” |
| 沉浸式（`thinkos-immersive`） | 下拉控制面板时系统栏闪一下 | 有更新器时随 OTA 自动装上（`policy: always`） |

### 4.8 手势练习

当前组件增量（2026-11-05，0.1.20已装）：六种自动方向箭头/长按圈、跟手让位、静态动画关闭与生命周期取消已在原生隔离窗口验收；八段Surface方向证据、中文横竖屏与原状态恢复见 `artifacts/welcome/WEL-2/0.1.20/qualification.json`。没有重开已完成引导；真实整套流程与成功打勾尚未收口，不能据此勾完W2。全局系统栏/外部手势仍待新版系统钩子。

手势看不见，光看图记不住。在真实手势上练一遍。

**练习模式**由 OS 提供：`ThinkStage` 照常识别手势。换页、换样式、控制面板、返回和长按只报告“做对了”，不执行对应导航；底缘Home遵循ADR-046，先保存练习进度再退出到首页，不能吞掉全局出口。返回练习无需先搭建返回栈。

| 练习 | 判定点（`ThinkStage`） |
|---|---|
| `swipe` 左右滑换页 | 横向拖动提交 |
| `variant` 上下滑换样式 | 纵向拖动提交 |
| `panel` 从顶边下拉控制面板 | 下拉提交 |
| `back` 从最左缘向右滑返回 | 返回手势提交 |
| `home` 从最底下向上滑回到首页（ADR-046） | 先报告练习完成，再清栈回首页 |
| `long-press` 长按进设置 | 长按触发 |

- 画面归 OS：一个手型或箭头沿手势方向动，手指按下后跟手，做对打勾。系统关闭动画时显示静态箭头。文字由插件给（ADR-017：没有外观参数）。
- 只在持有 `onboarding` 角色的页面上有效，离开页面自动结束。
- 每个手势都能跳过。“双指长按左上角 5 秒打开救援菜单”只用文字讲，不练：它是最后的救生通道，让人知道就够了。
- 引导期间底边上滑退出并保存进度；从设置顶部“完成设置”继续下一课（ADR-046）。

第一版可以先做静态说明（W1），再加练习（W2）。两者都要在公开之前做完。

### 4.9 版式与观感

照苹果的样子：**一屏一列，居中**。上面是图标和标题，中间是内容，主按钮固定在底部，次操作是按钮下面的一行字，左上角是返回。

- **返回要画出来**：左缘返回手势到第 10 屏才教，前面不能指望用户知道。
- **不加新组件，也不加新版式**，改 `form`：
  - `form` 版式多一个可选槽位 `hero`（图标或时钟，第 8 屏用它显示大字时间）；
  - `form` 组件多几个字段：`subtitle`、`primary`、`secondary`、`back`。有 `primary` 时标题居中，底部画按钮，点击发 `tap(name, "primary" | "secondary" | "back")`；
  - `FormView` 接上返回：先关选择器，再交给插件。
- 设置页不带这些字段，**长相和行为都不变**。
- 第 1 屏和第 10 屏不用表单：第 1 屏是 `focus` 版式加一段文字，Lua 用定时器换问候语；第 10 屏是练习模式。
- 横屏只有 492dp 高，图标、标题、底部按钮约占 200dp，内容区要能滚动。
- **观感要单独过一关**：这是新用户对这个产品的第一印象，现在的表单偏工程风。W1 开工前先在真机上出两张效果图（第 1 屏和第 7 屏），给所有者定。

上一版的“左右分栏”（`split` 加 `form`）不要了：苹果的单列更简单，也不用改 `split` 的规则。设置页的两栏是另一件事，互不影响。

### 4.10 不能困住人

| 情况 | 结果 |
|---|---|
| `welcome` 插件崩溃、被隔离、被禁用 | `NavStack.prune` 已经会清掉失效的层，回到首页；下次开机再试，最多 3 次 |
| 插件拦截了返回却不响应 | `BackGate` 的 2 秒规则，下一次返回直接退出（ADR-040） |
| 半途走开 | 半小时后退出 |
| 安全模式 | 不进入 |
| 引导期间任何时候 | 救援手势（双指长按）照常可用；它在舞台层，先于任何插件 |
| 没有输入法 | 只有 Wi-Fi 密码、地区和地点搜索要打字；设备有 LatinIME（`02-device.md`）；地区和地点不搜索也能选 |

### 4.11 新增接口

| 接口 | 权限 | 说明 |
|---|---|---|
| 角色 `onboarding` | `system.settings` | 只取内置版本；ADR-039 P1 的绑定表之后可替换 |
| `form` 版式的 `hero` 槽位；`form` 组件的 `subtitle`、`primary`、`secondary`、`back`；`source: "region"` | `system.settings` | §4.9、§4.6 |
| `tos.onboarding.practice(name, text)`、`stop()`、事件 `practice` | 持有 `onboarding` 角色的页面 | §4.8 |
| `tos.device.openAndroid("locale" \| "date" \| "accessibility")` | `system.device` | 增加三个目标 |
| `tos.device.setLocale(tag)`、`canSetLocale()` | `system.device` | §4.6 |
| `tos.device.setTimeZone(id)`、`canSetTimeZone()` | `system.device` | §4.6；只能在用户点按后 5 秒内调用（同 `tos.settings.open`） |
| `tos.tz.zones(region)` | 无 | 一个地区的时区和代表城市，只读 |
| 系统设置 `onboarding.done`（隐藏） | — | 走 `system.json`（ADR-017） |
| `SET_TIMEZONE <id>`、`DEVICE_OWNER_ON`、`GRANT_LANGUAGE` | 更新器的固定命令 | 都只在用户点过之后发；没有更新器时不出现 |

`welcome` 持有 `system.settings`、`system.device`、`system.update`，只给内置副本。

## 5. 分阶段

大小是相对的，不是日历。每阶段另加 `AGENTS.md` 的五条验收。

| 阶段 | 内容 | 大小 | 依赖 |
|---|---|---|---|
| **W0 验证** | 真实切换语言（含从右到左）；换到不同 UTC 偏移的时区后，ThinkOS 的时钟、日期、日出日落是否立刻跟上；放大系统字号后的界面；新机的默认时区和语言；**两张效果图** | S | — |
| **W1 骨架** | `onboarding` 角色、`NavStack` 新层、触发规则、老设备跳过；`form` 的新字段和 `hero`、`FormView` 收返回、地区选择器；`welcome` 插件的全部屏（手势先用静态说明，时区先用安卓日期页）；11 种语言 | M | ADR-040（已实现）；`update.auto_install` 没做时第 7 屏自动跳过 |
| **W2 练习与能力** | 手势练习；设备管理；地区和城市直接改时区；语言授权（更新器的三条命令） | M | `34` 号文档 M1 |
| **W3 装机** | 面向普通人的安装指南和 `tools/provision.py`：包住 19 号文档的步骤，校验 SHA-256，最后装 release APK 和更新器，授权 `CHANGE_CONFIGURATION`，询问是否开设备管理 | M | `34` 号文档 O1、M1；公开前完成 |

**W3 的注意点**：Lenovo 官方固件不是我们能分发的，脚本只给来源说明和 SHA-256，由用户自己准备；Magisk、Lineage、Vector 同样不打包。`magisk_patched.img` 只对应一版 Lineage（19 号文档 §4.3），指南要写死版本。

**W3 批量部署**（2026-11-03，所有者手上有很多台实体机，要快速刷成初始状态，开机自动进引导；任务是 `36` 号文档的 WEL-4、WEL-5）：

- 19 号文档每台要进两次 EDL、一次 recovery sideload，还要点屏幕授权 Magisk 和连 Wi-Fi，台数一多就是瓶颈。改成先把一台参考机做成成品，读出 boot、recovery、system、userdata 当“金镜像”，其余机器一次 EDL 写入。
- 为什么引导会自动出现：镜像里没有 ThinkOS 的数据（取镜像前 `pm clear moe.kiwi.thinkos`）。§4.2 规定已有 `settings/local.json` 的设备被当成老设备、直接把 `onboarding.done` 设成当前版本，所以镜像里不能带它，也不要预先设 `onboarding.done`。
- 为什么不重写 `persist`、`modem` 和引导链：`persist` 里是每台独有的校准数据，官方整包 qfil 会把它写成通用的 `persist_1.img`，是否丢数据没验证。新机已是出厂固件，不用跑整包。
- 为什么克隆 `/data` 可行：设备 `/data` 没有加密（`ro.crypto.state=unsupported`），已用约 0.5 GB。克隆带走 Magisk 的 su 授权、Zygisk 和模块，省掉点屏幕那几步。代价是 Wi-Fi 凭据、adb 公钥、`ANDROID_ID` 也会被带走，所以取镜像前要清理，投屏身份改用 ThinkOS 自己的随机种子（WEL-4）。
- 引导要做的事因此少了：Wi-Fi 屏照旧（除非所有者决定把凭据烤进镜像，OWN-16）；语言、地区、时区仍由用户选；`onboarding.done` 只在确实要跳过引导的批量场景由部署流程写入。
- 刷机仍归人工；各台开机后的校验和 APK 部署可以按序列号并行。

## 6. 验收

设备脚本 `tools/tests/onboarding_device.py`，加一条调试命令重置 `onboarding.done`。

1. 全新状态进入引导，**按 §4.4 的顺序**；断网、无 Server、无模块全程走完。
2. 每一屏都能继续或跳过；屏幕上的返回和 Back 键都回上一屏；Home 键退出，已改的设置保留；下次开机从原来那屏继续，最多问 3 次。
3. 跳过的屏出现在设置页的“完成设置”里，点进去只走这几屏。
4. 插件崩溃或被隔离：直接进首页，不循环；救援手势全程可用。
5. 老设备升级不出现；设置页能手动重跑；再次开机不出现。
6. 在语言屏点一行：界面立刻换语言，包括切到阿拉伯语、乌尔都语时从右到左。没有权限时打开安卓语言页，回来继续。
7. 选只有一个时区的地区：时钟当场按新时区走，不出现 3a。选多时区的地区：出现 3a。选美国：单位变成英制。
8. 时钟、日期、日出日落跟着时区走；重启保留；`clock_accuracy_device.py` 不回归（ADR-029，跑之前告诉用户）。选 `Europe/Kyiv` 等 3 个 ROM 没有的时区，系统时区不会变成 GMT。没有更新器时打开安卓日期页，回来后继续。
9. 已联网时跳过Wi-Fi屏。打开安卓 Wi-Fi 页再回来，继续在原屏，Lock Task 恢复（沿用 `panel_device.py` 的检查）。
10. 软件更新屏：“继续”写入 `update.auto_install=true`，“只自动下载”写入 `false`，OTA 行为和开关一致。
11. 选择器开着时按返回，只关选择器。
12. 手势练习：6 个手势用 `input swipe` 走通；做错不前进；练习期间不真的翻页或打开面板。
13. 横屏、竖屏、从右到左语言下布局正常，横屏内容放不下时能滚动，底部按钮不被盖住；11 种语言的键和占位符一致（`gen_android_strings.py --check`）。
14. 冷启动 < 3 秒不回归。

## 7. 风险

| 风险 | 对策 |
|---|---|
| 屏数比之前多，新用户嫌烦 | 一屏一问、点一行就走、用不上的自动跳过；W1 上真机计时，一路继续超过一分钟就砍 |
| 隐藏 API 换语言不成立 | 回到安卓语言页；语言仍然排第二屏，只是要离开 ThinkOS 一下 |
| 地区换了语言，部分界面没刷新 | W0 验证；`locale` 事件已经覆盖插件和原生界面 |
| ROM 的时区库停在旧版，以后 IANA 新增的时区都不在 ROM 里 | 替代表加单元测试；表里没有的不改系统时区，走安卓页 |
| 引导变成每次发版都要改的负担 | 屏数固定，新功能不往里加；`done` 的版本号很少升 |
| 一键开 Device Owner 后用户不知道怎么退 | 说明里写清退路；救援菜单入口同时上 |
| 手势练习的原生代码和 ADR-039 的方向冲突 | 手势识别本来就是内核外壳；只加判定点和一个提示层，不加插件可见的样式 |

## 8. 要所有者定的事

已定：顺序仿照苹果（§4.4）；条款不要“同意”按钮（§4.5）。

1. **引导里能不能一键开设备管理？** 改变了“Device Owner 只能用电脑设置”的现状。*推荐：能，因为这是新用户最容易卡住的一步；退路同时上。*
2. **手势练习要不要进第一个公开版？** *推荐：要。这个产品的操作全靠看不见的手势，静态图教不会。*
3. **Wi-Fi 要不要做成内嵌列表？** 这是和苹果观感差距最大的地方，第 4 屏会跳到安卓页面。API 27 的应用可以自己扫描和连接，不需要 root，控制面板也能用上。*推荐：要做，但放在 W2 之后，第一版先用安卓页面。*
4. **文字大小要不要像苹果一样放在最前面？** *推荐：先不放。W0 放大系统字号看一下，界面撑得住再加。*
5. **视觉方向**：W1 前看两张真机效果图再定。

## 9. 参考

- 苹果：[Set up your iPhone or iPad](https://support.apple.com/en-us/105132)、[Manage Setup Assistant for Apple devices](https://support.apple.com/guide/deployment/manage-setup-assistant-for-apple-devices-depdeff4a547/web)
- 第三方（只用来补官方没写的先后）：[iGeeksBlog](https://www.igeeksblog.com/how-to-set-up-iphone-ipad/)、[FoneLab](https://www.fonelab.com/resource/ios-setup-assistant.html)
- 许可证：[GPLv3 第 9 条](https://www.gnu.org/licenses/gpl-3.0.en.html)
