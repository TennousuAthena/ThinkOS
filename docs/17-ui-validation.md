# 17 · UI 真机验证

2026-10-30，设备 `HUA0BWUT`（Android 8.1 / API 27、armv7、800×1280）。按用户最新指示，本轮仅验证 UI；HTTP 通知监听、轮询、媒体宿主接线与图片轮播完整功能暂缓。

## 原生组件

通过 `tools/tests/component_ui.py` 热加载使用模拟数据的 `component-probe`，七项检查全部通过，应用 PID 保持 `15053`。

| 检查 | 结果与截图 |
|---|---|
| split：月相、进度环、面积图、列表 | [横屏深色](../artifacts/ui-validation/components/split-landscape-dark.png) |
| 点击进度环与列表行 | 事件送达所属 Lua 插件 |
| 百行列表纵向滚动 | 不切页；复用后的行回传正确的一基索引，[截图](../artifacts/ui-validation/components/list-scrolled-and-tapped.png) |
| 从列表区域横滑 | 正常切到 stage，[横屏](../artifacts/ui-validation/components/stage-landscape-dark.png) |
| focus 组件组、日照、折线图与 footer 点击 | 正常；无需位置权限，[横屏](../artifacts/ui-validation/components/focus-landscape-dark.png) |
| 三种版式竖屏重排 | [split](../artifacts/ui-validation/components/split-portrait-dark.png)、[stage](../artifacts/ui-validation/components/stage-portrait-dark.png)、[focus](../artifacts/ui-validation/components/focus-portrait-dark.png) |
| 浅色、深色、夜间红色 | [浅色](../artifacts/ui-validation/components/split-portrait-light.png)、[夜间](../artifacts/ui-validation/components/split-portrait-night.png) |

图表的空值会断段，月相支持南半球方向；列表使用原生行复用。结果与日志见 [components/results.json](../artifacts/ui-validation/components/results.json)。组件测试后修改了通知预览、设置表单及其交互会话、暂缺卡片的引用解析，split/focus/stage 组件绘制和翻页/列表手势归属未改；此前候选包另有 [装载复验](../artifacts/ui-validation/components/final-apk-smoke.json)。

## 设置交互

`tools/tests/settings_ui.py` 使用两个无 Lua、无权限的声明式测试插件，以及临时首页卡片。七项检查全部通过：

| 检查 | 结果与截图 |
|---|---|
| 长按首页卡片 | 进入卡片所属插件的自动设置页；运行时确认该插件 Lua 内存为 0，[首页](../artifacts/ui-validation/settings/home-card-owners.png) |
| 开关与复位 | 值立即更新；复位删除本机覆盖；无障碍节点提供开关角色与选中/未选中状态，复位按钮热区为 78×78px（48×48dp） |
| 步进、单选、文本和密码 | 可编辑并保留；密码行始终掩码，控件树不回显测试密码 |
| 长标签和长地址 | 标签最多两行，地址省略；[横屏](../artifacts/ui-validation/settings/long-value-layout.png)、[竖屏](../artifacts/ui-validation/settings/long-value-portrait.png)均无挤压或遮挡 |
| 声明式卡片和页面 | 设置立即反映到卡片和页面；页面长按仍定位到所属插件 |
| 无设置项的卡片 | 长按回退到系统设置 |
| 应用重启 | 自动设置与声明式卡片保留修改后的值，[重启后首页](../artifacts/ui-validation/settings/settings-retained-after-restart.png) |

结果见 [settings/results.json](../artifacts/ui-validation/settings/results.json)，无障碍证据见 [switch-and-reset.xml](../artifacts/ui-validation/settings/switch-and-reset.xml)。测试结束后移除临时插件、恢复首页，并确认用户设置与测试前一致。

后续设置会话与滑块修复后，同一脚本的七项检查再次全部通过，见 [settings-after-session/results.json](../artifacts/ui-validation/settings-after-session/results.json)。输入框主题修正另做定向复验；其它组件的全量记录来自此前候选包。加入帮助文字与错误消退后，七项设置检查也全部复验通过，见 [settings-after-feedback/results.json](../artifacts/ui-validation/settings-after-feedback/results.json)。

实测修正了三处表单问题：复位按钮原来的 13×13px 点击区域扩大为 48×48dp，保留 8dp 的视觉圆点；长地址限制占宽，标签由三行收为最多两行；开关补齐无障碍选中状态。标签实测由 232×102px 改为横屏 395×68px、竖屏 308×68px。

重启测试另发现首页可能先于其引用的卡片插件装载，原先会把未解析的 `{widget: …}` 当作组件并在主线程抛出异常。现在暂缺引用显示空位，后续目录更新恢复卡片及其所属插件。新增五项 JVM 回归覆盖引用暂缺、恢复、移除、导航与内联组件；修复后真机重启通过，`safeMode=false`。失败日志保留在 [settings-restart-failure/logcat.txt](../artifacts/ui-validation/settings-restart-failure/logcat.txt)，测试引发的恢复标记已清理。

### 设置会话与滑块手势

`tools/tests/settings_session_ui.py` 补测八项交互，全部通过，见 [settings-session/results.json](../artifacts/ui-validation/settings-session/results.json)：

- 设置页停留超过普通交互超时后仍为 ENGAGED，[18 秒时的画面](../artifacts/ui-validation/settings-session/settings-after-18-seconds.png)。设置关闭后恢复原有注意力计时。
- Home 退出设置并返回此前页面；无操作时 30.57 秒自动退出。
- 滑块横向拖动选中预期步进值，竖向滚动经过滑块不会修改设置；取消手势恢复已提交值。
- 原生输入框连续输入 40.13 秒，设置页仍打开且保持 ENGAGED，[输入框](../artifacts/ui-validation/settings-session/active-text-editor.png)。取消或 Home 退出均不保存草稿。
- 检查过程中 PID 不变；结束时所有用户设置与测试前一致。

补测定位并修复了三处行为：设置页原本会按普通计时退回 AWAKE；原生对话框的输入没有续期设置会话，仍会被 30 秒超时关闭；滑块在按下时就拦截父容器，竖向滑动会误改数值。现在设置打开时保持 ENGAGED，输入与触摸活动续期会话，滑块确认横向意图后才阻止页面滚动。为保证设置页中手动关闭夜间模式仍立即反馈，同时修正了该切换在 ENGAGED 下被阻止的问题，三种主题模式均有 JVM 回归覆盖。

修复前的证据保留在 [settings-session-before/results.json](../artifacts/ui-validation/settings-session-before/results.json) 与 [settings-editor-before/results.json](../artifacts/ui-validation/settings-editor-before/results.json)。滚动测试误改的单个亮度阈值已通过原生复位按钮恢复，并核对原有设置；复验没有残留修改。

截图复核还发现深色对话框中的编辑框继承了 Activity 的浅色主题，出现黑字。编辑框现与对话框使用同一原生主题。主题修复候选包的四项输入框定向检查全部通过，见 [settings-editor-final/results.json](../artifacts/ui-validation/settings-editor-final/results.json) 与[修复后截图](../artifacts/ui-validation/settings-editor-final/active-text-editor.png)；模拟输入字符区域的主色对比度由 1.98:1 提升至 10.05:1，采样方法与颜色见 [contrast.json](../artifacts/ui-validation/settings-editor-final/contrast.json)。

### 帮助文字与拒绝反馈

`tools/tests/settings_feedback_ui.py` 使用无 Lua、无权限的 `feedback-ui-probe`，六项真机检查全部通过，见 [settings-feedback/results.json](../artifacts/ui-validation/settings-feedback/results.json)：

- 帮助文字在标签下方按宽度完整换行，横竖屏均不遮挡相邻内容；整行的无障碍描述包含说明。[横屏](../artifacts/ui-validation/settings-feedback/help-landscape.png)、[竖屏](../artifacts/ui-validation/settings-feedback/help-portrait.png)。
- 步进到声明上限后，再点加号保持上限。
- 将深色阈值拖到与浅色阈值冲突的值，存储拒绝修改、滑块回弹，并显示已翻译的原因。[出现提示](../artifacts/ui-validation/settings-feedback/error-visible.png)、[提示消失后](../artifacts/ui-validation/settings-feedback/error-after-wait.png)。
- PID 保持不变；测试插件与自己的覆盖值已移除，用户原有设置已核对恢复。

此前表单未绘制已传入的 `help`，错误也会一直保留。现已补齐说明列与无障碍描述；错误由设置插件通过公开计时 API 在三秒后清除，原生行用 120ms 的淡出与轻微位移收起，关闭动画时直接移除。重复失败重新计时，成功、重置、语言/作用域切换、离开页面与停止插件均取消旧计时。原生测试新增八项确定性检查，覆盖精确时长和过期回调，见 [native-tests.json](../artifacts/ui-validation/settings-feedback/native-tests.json)。设备节点的后次观察在提交后 6.92 秒，确认提示已消失；不把这个观察耗时当成精确消退时长。

修复前对照见 [settings-feedback-before/results.json](../artifacts/ui-validation/settings-feedback-before/results.json)。构建后的 Lua 生命周期检查使用仓库自带的 Lua 5.4，已并入 `android/app/src/testNative/run.sh`；系统安装的 Lua 版本不参与该验收。

### 时区选择器

`tools/tests/timezone_ui.py` 使用无 Lua、无权限的 `timezone-ui-probe`，通过自动设置页打开两个原生时区字段。十三项真机检查全部通过，见 [timezones/results.json](../artifacts/ui-validation/timezones/results.json)。浅深主题由原生系统设置页固定，并核对运行时主题后采样，横竖屏均通过。

| 检查 | 修复前 | 修复后 |
|---|---|---|
| 洲别按钮热区 | 88×58px，高约 36dp | 88×78px，达到 48dp |
| 深色选中文字对比度 | 2.76:1 | 6.10:1 |
| 浅色选中文字对比度 | 3.68:1 | 5.35:1 |
| 无障碍导航 | 选中洲别未标记，仍能访问被盖住的表单 | 洲别有选中状态，选择器打开时隐藏底层表单 |
| 搜索框持续输入 | 31.51 秒时已退出设置 | 输入 40.24 秒后仍保持设置页 |

按钮现使用 `surface` 底色、`accentText` 文字与选中描边；输入和按键接入现有设置会话的续期入口。对比度从截图按钮内部的背景与实心文字像素计算，颜色与热区原始数据在结果 JSON 中。截图已复核：[横屏深色](../artifacts/ui-validation/timezones/landscape-dark.png)、[竖屏深色](../artifacts/ui-validation/timezones/portrait-dark.png)、[横屏浅色](../artifacts/ui-validation/timezones/landscape-light.png)、[竖屏浅色](../artifacts/ui-validation/timezones/portrait-light.png)、[持续输入](../artifacts/ui-validation/timezones/active-search.png)。修复前对照见 [timezones-before/results.json](../artifacts/ui-validation/timezones-before/results.json)。

其它检查覆盖：必选字段不提供空值；洲别筛选只展示所属时区；搜索 IANA 名称 `Kathmandu` 显示 `UTC+5:45`；选取后保存并关闭键盘；返回不修改选中值；可空字段选“无”后重新打开仍有选中标记。交互期间 PID 保持 `11614`。结束后删除测试插件，逐字节恢复设备修改与 Server 层，确认成功后删除本机私有备份，开发覆盖目录为空；[最终设备核对](../artifacts/ui-validation/timezones/final-state.json)。

地点输入接线修复后，同十三项再次通过，见 [timezones-after-place/results.json](../artifacts/ui-validation/timezones-after-place/results.json)。控件树可能省略输入法窗口，因此“选择后关闭键盘”改用输入法服务的实际 `mInputShown` 状态及截图共同核对；本次确认关闭。首页长按入口也改为从可见内容定位中央间隙，避开临时露出的导航栏。

### 地点选择器与离线手填

`tools/tests/place_ui.py` 关闭 Wi-Fi，通过真实系统设置页完成二十一项检查，全部通过，见 [places/results.json](../artifacts/ui-validation/places/results.json)。没有启动在线搜索或 Server。搜索框输入持续 40.30 秒、手填对话框输入持续 40.28 秒，设置页均保持打开；未保存的草稿不修改位置。

实测修复了三类问题：搜索与手填输入、按键和触摸未续期设置会话；深色弹窗的说明文字及确认/取消文字对比度偏低；非法值提示被底部操作栏遮住。现已接入已有会话入口，编辑控件继承对应的原生对话框主题，自绘内容使用颜色 token，验证失败后将提示滚入可视区。数据来源署名字号由 12sp 调整到规范要求的 14sp。

| 像素采样 | 修复前 | 修复后 |
|---|---:|---:|
| 深色坐标标签 | 3.21:1 | 5.39:1 |
| 深色确认/取消文字 | 2.02:1 | 9.05:1 |
| 浅色坐标标签 | — | 4.83:1 |
| 浅色确认/取消文字 | — | 16.88:1 |

对照证据：[输入会话基线](../artifacts/ui-validation/places-before/results.json)、[原标签采样](../artifacts/ui-validation/places-before/contrast.json)、[原按钮采样](../artifacts/ui-validation/places-initial/initial-button-contrast.json)。基线搜索在输入后 31.40 秒退出；手填在输入后 19.35 秒退出，因为此前打开弹窗和准备编辑的时间也被计入了闲置时长。

其余检查覆盖：纬度 91 与未知 IANA 时区均被拒绝，已有位置完全不变；负数坐标、海拔、空时区与手动模式一起保存并能重开；取消和 Home 丢弃草稿；浅深主题横竖屏的返回/手填热区至少 48dp，搜索入口和离线说明可见；竖屏内容可滚动访问。输入法显隐读取实际服务状态，避免控件树漏掉键盘。横屏右缘的搜索按钮在工具树中可能被临时导航栏裁剪，本记录不据该可见矩形声称其完整点击尺寸。

截图已复核：[横屏深色手填](../artifacts/ui-validation/places/manual-landscape-dark.png)、[竖屏浅色手填](../artifacts/ui-validation/places/manual-portrait-light.png)、[错误完整可见](../artifacts/ui-validation/places/invalid-latitude.png)、[保存后](../artifacts/ui-validation/places/saved-location.png)。交互期间 PID 为 `17958`；测试后逐字节恢复设备修改与 Server 层，恢复 Wi-Fi，核对成功后删除本机私有备份；Android 系统时区未改变。

截图复核另发现“使用设备时区”的空值在重开时被解析成 `Asia/Hong_Kong`，直接保存会意外固定当前时区。表单描述现保留手动位置配置的空/显式时区；时区代表城市来源的初始值使用空值。公开位置 API 仍提供用于计算的解析后时区。新增两项 JVM 读写回归，修复前 [2 项中 1 项失败](../artifacts/ui-validation/places-timezone-retention-before/unit-tests.xml)，修复后全部通过。

`place_ui.py --timezone-retention` 的七项定向真机检查在最终包上通过，见 [places-timezone-retention/results.json](../artifacts/ui-validation/places-timezone-retention/results.json)：自动来源留空、手动空值重开仍为空、原样保存不固定当前时区、显式时区保留、切回自动后不使用未生效的手动时区，以及 PID 与 Android 时区不变。截图：[重开后的空时区](../artifacts/ui-validation/places-timezone-retention/empty-timezone-reopened.png)、[显式时区](../artifacts/ui-validation/places-timezone-retention/explicit-timezone-reopened.png)。原始设置与 Wi-Fi 已恢复，私有备份已删除；[最终设备核对](../artifacts/ui-validation/places-timezone-retention/final-state.json)。

### 系统设置实际生效

`tools/tests/system_settings_ui.py` 新增十项真机检查，全部通过，见 [system-settings/results.json](../artifacts/ui-validation/system-settings/results.json)。该次验证未改生产代码与 APK；测试通过原生设置页修改六个键，结束后逐键恢复，用户原有设置保持不变。

- 在设置页保持 ENGAGED 时关闭夜间模式，主题立即退出 NIGHT。
- 关闭夜间模式并固定深色，在首页分别输入 0.1 lux 与 10000 lux，各持续超过 34 秒，始终保持 DARK；注意力保持 AWAKE，避免用交互状态抑制自动主题切换而误判。[极暗](../artifacts/ui-validation/system-settings/dark-low-lux.png)、[强光](../artifacts/ui-validation/system-settings/dark-high-lux.png)。
- 通过设置页选择竖屏与横屏，原生界面立即重排为 800×1280 与 1280×800，PID 不变。[竖屏](../artifacts/ui-validation/system-settings/orientation-portrait.png)、[横屏](../artifacts/ui-validation/system-settings/orientation-landscape.png)。
- 将交互结束、变暗、休眠分别设为 5/30/60 秒，退出设置并触摸一次，解除注意力保持，不再模拟注意力状态；下一轮按新值依次转换。

| 状态 | 首次观察（秒） | 窗口亮度 | 实际背光（0–255） |
|---|---:|---:|---:|
| ENGAGED / AWAKE | 0.17 / 6.40 | 0.85 | 216 |
| AMBIENT | 31.60 | 0.3995 | 101 |
| ASLEEP | 61.56 | 0.03 | 10 |

首次观察时间包含一秒采样周期及 ADB/诊断开销；亮度在转换稳定后读取。截图不能反映 LCD 背光，所以同时保留窗口与显示诊断：[活跃](../artifacts/ui-validation/system-settings/attention-engaged-window.txt)、[变暗](../artifacts/ui-validation/system-settings/attention-ambient-window.txt)、[休眠](../artifacts/ui-validation/system-settings/attention-asleep-display.txt)。

亮度与无人状态使用已有 debug 输入；实体遮光、旋转和接近传感器仍待人工实测。本记录不替代相关硬件验收。测试准备过程中修正了恢复普通闲置计时后的唤醒步骤，以及旋转后向上查找控件的脚本逻辑。

### 原生救援菜单

`tools/tests/rescue_ui.py` 对 APK 内真实的 `settings` 插件摘要连续写入三次孤立运行标记，触发其隔离状态；没有制造实际崩溃，也没有启用全局安全模式。诊断确认 `disabled=true`、Lua 内存为 0。九项真机检查全部通过，见 [rescue/results.json](../artifacts/ui-validation/rescue/results.json)：

- 先通过原生设置页保存有效修改，再隔离设置插件；长按首页进入原生维护菜单。
- 横竖屏的五项操作与返回按钮均完整显示、互不遮挡，热区至少 48dp。[横屏](../artifacts/ui-validation/rescue/menu-landscape.png)、[竖屏](../artifacts/ui-validation/rescue/menu-portrait.png)。维护菜单与诊断框按当前主题选择深浅色原生样式。
- 诊断显示设置插件已停用及本地运行状态；返回按钮正常关闭弹窗。[诊断](../artifacts/ui-validation/rescue/diagnostics.png)。
- 设置 Lua 停用时，“重置设置”仍清除设备修改，并保留 Server 下发层。
- “恢复出厂插件”使设置 Lua 恢复，随后长按重新进入自动设置页；上述交互 PID 始终为 `7424`。[恢复后的设置页](../artifacts/ui-validation/rescue/settings-restored.png)。

首轮复现了空白表单：插件隔离后，其 `settings` 角色仍留在页面目录中，长按被导向无法处理事件的设置页。[修复前结果](../artifacts/ui-validation/rescue-before/results.json)、[空白截图](../artifacts/ui-validation/rescue-before/failed-screen.png)。现在仅在相应运行时可用时发布交互设置页；普通内容仍保留最后一帧，无 Lua 的健康声明式插件不受影响。新增五项 JVM 检查覆盖正常、退避、隔离与声明式页面。首轮修复后的浅色弹窗对照保留在 [rescue-light-dialog](../artifacts/ui-validation/rescue-light-dialog/results.json)，最终截图已复核为深色。

测试前将设备修改、Server 层与失败记录的原始字节保存到本机私有临时目录，测试结束逐项恢复并核对；确认成功后删除备份。最终核对见 [final-state.json](../artifacts/ui-validation/rescue/final-state.json)。预检另发现旧测试把“文件不存在”的 `cat` 错误文本误存为失败记录；仅清理了完全匹配的无效记录，证据见 [invalid-history-error.txt](../artifacts/ui-validation/rescue-preflight/invalid-history-error.txt)。设备文件读取现使用带远端退出码的 ADB shell，区分文件缺失、权限失败与设备离线，新增四项 Python 回归，避免错误文本再次被当成待恢复数据。

### 原生弹窗按钮

`tools/tests/native_dialog_ui.py` 覆盖普通文字、密码、手填坐标、维护菜单与诊断框，在横屏深色及竖屏浅色下采样十个真实弹窗。二十一项检查全部通过，见 [native-dialogs/results.json](../artifacts/ui-validation/native-dialogs/results.json)。所有确认、取消与返回按钮的实际热区均至少 48dp。

| 按钮文字 | 修复前 | 修复后 |
|---|---:|---:|
| 普通文字 / 密码，深色 | 2.02:1 | 9.05:1 |
| 普通文字 / 密码，浅色 | 4.97:1 | 16.88:1 |
| 维护 / 诊断，深色 | 2.02:1（此前截图） | 9.05:1 |
| 维护 / 诊断，浅色 | — | 16.88:1 |

原生创建点现统一调用 `NativeDialogs.readableButtons`，按弹窗实际采用的原生主题选择稳定的主文字色。手填弹窗也使用该入口，其已有对比度保持不变。修复前的文字/密码检查仅两项深色对比度失败，其余保存、掩码和取消正常：[基线](../artifacts/ui-validation/native-dialogs-before/results.json)。维护/诊断旧图的采样单独记录了原 APK 摘要：[历史采样](../artifacts/ui-validation/native-dialogs-before/previous-rescue-contrast.json)。

验证还确认：普通文字能保存；模拟密码草稿保持掩码、取消后未写入；取消手填不改位置；维护与诊断返回后正常关闭，交互期间 PID 保持 `27439`。维护菜单通过仅在 debug APK 中可调用、受 `android.permission.DUMP` 保护的入口展示，使用 `MainActivity` 的真实创建函数；本轮只操作查看诊断与返回。真实设置插件隔离后的回退证据仍为上一节的九项检查。

截图已复核：[深色文字](../artifacts/ui-validation/native-dialogs/text-dark.png)、[浅色文字](../artifacts/ui-validation/native-dialogs/text-light.png)、[深色维护](../artifacts/ui-validation/native-dialogs/maintenance-dark.png)、[浅色维护](../artifacts/ui-validation/native-dialogs/maintenance-light.png)、[诊断](../artifacts/ui-validation/native-dialogs/diagnostics-dark.png)。测试后移除声明式样例插件，逐字节恢复设备修改与 Server 层，核对后删除私有备份；[最终设备核对](../artifacts/ui-validation/native-dialogs/final-state.json)。

## 模拟通知

`NotificationPreviewActivity` 仅存在于 debug 源集，启动权限限制为 USB shell 的 `DUMP`。用模拟标题与正文检查展示；没有 HTTP 监听、接收、通知服务、实际唤醒或插件 `tos.alert` 接线。

`tools/tests/notification_ui.py` 的七组场景全部通过。原生控件树确认标题、正文和关闭按钮在屏幕内且互不覆盖，关闭按钮能退出预览，进程保持不变；截图已人工逐张检查。

| 场景 | 截图 |
|---|---|
| 横屏深色横幅 | [banner-landscape-dark.png](../artifacts/ui-validation/notifications/banner-landscape-dark.png) |
| 竖屏浅色横幅、英文长文案 | [banner-portrait-light-long.png](../artifacts/ui-validation/notifications/banner-portrait-light-long.png) |
| 横屏深色全屏、中文长文案 | [takeover-landscape-dark-long.png](../artifacts/ui-validation/notifications/takeover-landscape-dark-long.png) |
| 竖屏浅色全屏 | [takeover-portrait-light.png](../artifacts/ui-validation/notifications/takeover-portrait-light.png) |
| 竖屏夜间红色全屏 | [takeover-portrait-night.png](../artifacts/ui-validation/notifications/takeover-portrait-night.png) |
| 夜间 urgent 视觉样式 | [takeover-landscape-night-urgent.png](../artifacts/ui-validation/notifications/takeover-landscape-night-urgent.png) |
| 阿拉伯语 RTL 横幅 | [banner-portrait-arabic.png](../artifacts/ui-validation/notifications/banner-portrait-arabic.png) |

结果见 [notifications/results.json](../artifacts/ui-validation/notifications/results.json)。长文案按固定行数省略；RTL 的图标与关闭按钮顺序会镜像。语言配置只作用于预览 View 的局部 Context，不修改设备语言。

验证中修复了两处预览问题：API 27 已初始化 Activity 资源后不能再调用 `applyOverrideConfiguration`，改为局部配置上下文；时钟自身会重设 alpha，背景改为由父容器调暗，避免干扰前景文案。首个异常预览已停止，相关故障日志保留，测试引发的恢复标记已清理，最终主界面 `safeMode=false`。

## 构建与边界

- `./gradlew --offline testDebugUnitTest lintDebug assembleDebug` 通过：174 个 JVM 测试，0 失败，包含卡片引用解析、设置注意力与夜间开关、设置页可用性及地点时区读写回归。
- Python 30 项通过，包含设备文件读取四项回归；此前的 11 张语言表 ×83键一致与生成 Go 文件编译检查通过，相关源文件未变。
- `android/app/src/testNative/run.sh` 通过，包含新增的八项设置提示生命周期检查及原有预算/JNI 回归。
- APK 安装成功，原生库只有 `lib/armeabi-v7a/libtos.so`；内置插件为目录和索引，无 `.tpk`。构建摘要见 [build.json](../artifacts/ui-validation/build.json)。
- 最终包断网、无 Server：Activity 冷启动803ms，完整首页卡片2166ms，见 [cold-start-native-dialogs/result.json](../artifacts/ui-validation/cold-start-native-dialogs/result.json)。设备安装包与本地 APK 摘要一致，原生弹窗二十一项在该包上通过。本次修改统一了按钮文字颜色，并增加受保护的 debug 维护菜单展示入口；地点、时区、救援、组件、通知及其它设置全量记录的版本范围见各节与 [build.json](../artifacts/ui-validation/build.json)。
- 前置基线曾连续采样600.1秒：单核平均 CPU 2.33%、janky 2.43%、PSS约58.4MiB，PID未变，见 `artifacts/location/ambient/`。该记录属于加入最终 debug 预览前的候选包，不代替完整 M1 的媒体与相册性能验收。

`controls/video/image/carousel` 尚未完成宿主或渲染器接线，因此未开放为可安装词汇；其候选 schema 定义保留。当前安装器拒绝这些声明，避免缺渲染器的页面在主线程崩溃。六个内容插件的完整功能与 HTTP 接收方案均未标记完成。

## 复现

```bash
python3 -I tools/tests/component_ui.py
python3 -I tools/tests/settings_ui.py
python3 -I tools/tests/settings_session_ui.py
python3 -I tools/tests/settings_feedback_ui.py
python3 -I tools/tests/timezone_ui.py
python3 -I tools/tests/place_ui.py
python3 -I tools/tests/place_ui.py --timezone-retention --output artifacts/ui-validation/places-timezone-retention
python3 -I tools/tests/system_settings_ui.py
python3 -I tools/tests/rescue_ui.py
python3 -I tools/tests/native_dialog_ui.py
python3 -I tools/tests/notification_ui.py
python3 -I tools/tests/pre_m1_cold_start.py --output artifacts/ui-validation/cold-start-native-dialogs
android/app/src/testNative/run.sh
```

组件与设置脚本结束时移除自己的测试插件，恢复调试注意力、lux、方向；自动设置样例清理测试插件的设置作用域，时区脚本恢复原有设置，救援脚本还原失败记录；通知脚本关闭预览。最终设备返回 ThinkOS 首页，开发覆盖目录为空，用户设置保持原值。
