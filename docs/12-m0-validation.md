# 12 · M0 实现与真机验证

验证日期：2026-10-29（Asia/Hong_Kong）。目标设备：`HUA0BWUT`，Lenovo StarView，Android 8.1 / API 27 / 32 位 ARM。

## 已实现

- 可独立构建的 Kotlin / Gradle / NDK 工程；单 Activity，HOME Launcher、沉浸式全屏、常亮、开机启动、前台服务与 30 秒看门狗。
- Java 未捕获异常记录、恢复闹钟、同一开机周期一小时内五次崩溃进入安全模式；安全模式只加载内置时钟。恢复闹钟与周期看门狗分开，磁盘日志失败不阻断恢复调度。
- Store 的 Attention / Deck / Overlay / Link 四轴；本地页面路由、上下变体、角色切换、变体记忆与焦点仲裁。
- 四层原生 Stage、统一 MotionClock、60 / 30 / 5 fps 调度上限、背景呼吸、轻微漂移防残影、横竖屏 FLIP 重排。稳态 AMBIENT 的慢背景按需约 3Hz 更新、防残影每秒以 1/8px 精度更新；交互/主题动画仍使用高帧率。
- 环境光 LIGHT / DARK / NIGHT 滞回，颜色插值与整屏红色合成；接近/触摸注意力策略、亮度渐变。
- Lua 5.4.9 源码编译、JNI、独立插件线程、受限标准库与包内 require；`tos.state/timer/store/log/time/page/json`、生命周期事件。
- 50ms **线程 CPU 时间**及指令预算、每插件 4MiB / 总计 24MiB Lua 内存上限、每插件 256KiB 状态/存储上限。磁盘等待不计 CPU 时间。
- Schema 驱动的 manifest / view 校验与 Kotlin / Go 生成；受限 ZIP 解包、开发插件原子热加载、启动失败保留旧版、删除覆盖恢复内置版本。
- 纯声明 `home` 双栈与 `clock` 插件：数字、指针、日光弧、世界时钟；通用组件另支持 `float`。
- 左上区域双指长按 5 秒的维护入口：诊断、恢复出厂插件、系统设置、退出安全模式。

M0 不申请 INTERNET 权限；没有 Server、GMS、WebView、设备端模型或摄像头依赖。内容插件和 Server 管理仍属于后续里程碑。

## 构建与自动化检查

固定工具链：Gradle 8.13、AGP 8.10.1、Kotlin 2.2.0、JDK 17、compileSdk 35、build-tools 35.0.0、NDK 26.3.11579264、CMake 4.4.5。`minSdk=targetSdk=27`。

| 检查 | 结果 |
|---|---|
| `assembleDebug` | 通过 |
| `testDebugUnitTest` | 12 项通过：注意力/主题 4，Schema 5，绑定 3 |
| `lintDebug` | 通过；按 ADR-010 排除 Play 的 targetSdk 发布要求，保留其它检查 |
| Python 插件打包测试 | 14 项通过 |
| 原生 Lua / JNI 测试 | 21 项通过 |
| Shell 语法检查 | 通过 |
| 生成 Go 结构编译 | 通过 |
| APK ABI | 只有 armv7 `libtos.so`，ELF32；无 arm64 原生依赖 |

原生测试包含死循环、pcall/xpcall、嵌套协程、不可挂起的 table.sort 回调、分配器耗尽、总内存上限、禁止二进制 load、只读配置、禁用终结器、UTF-8/emoji、定时器回调、host I/O 等待、病态模式拒绝、字面搜索索引边界和长搜索可中断性。

## 真机结果

| 项目 | 结果 / 证据 |
|---|---|
| 安装 | `adb install -r` 成功，包名 `moe.kiwi.thinkos` |
| 离线冷启动 | 最终 APK `am start -W` 的 TotalTime **502ms**，首页 Fully drawn **825ms**；首次基础版本 571–616ms。Wi-Fi 关闭，无 Server，显示本地首页 |
| HOME 与开机 | 默认 HOME 已为 ThinkOS；实际重启后自动显示首页；一次重启 USB 约 15s 返回、约 31s 已进入 ThinkOS |
| 看门狗 | 从系统设置自动拉回 ThinkOS；无需 root / Device Owner |
| 横竖屏 | debug 方向控制触发 `onConfigurationChanged`，未重建 Activity；已检查两种方向截图 |
| 翻页与变体 | 左右 home/clock，时钟上下数字/指针/日光/世界；动画已录屏 |
| 主题 | 通过 debug 注入 0 / 200 lux，走同一 ThemePolicy 的真实滞回计时，验证 NIGHT → DARK → LIGHT 和红色滤镜 |
| 插件热更新 | clock 0.1.0 → 0.1.3，应用 PID 保持 **6033**；中文/emoji、包内 require、tos.store 均通过 |
| 死循环隔离 | 0.1.2 测试版本的 start 死循环被预算终止，0.1.3 继续显示；删除覆盖恢复 0.1.0 |
| 崩溃恢复 | 连续五次受控 Java 崩溃，分别 **1.49 / 4.75 / 5.49 / 1.45 / 4.77s** 恢复，第五次安全模式仅运行内置 clock |
| 病态模式隔离 | 最终 APK 的 clock 0.1.4 测试版本立即拒绝模式匹配，0.1.3 继续运行；PID 4187 不变，随后恢复内置版本 |
| 跨重启计数 | 注入上一开机周期的四条记录，再崩溃一次，只保留本次一条，未误入安全模式 |
| 优化前长时运行 | 同一 PID 1782 运行超过 30 分钟，106467 帧中 janky 0.67%，PSS 59921KiB；期间存在真实交互和注意力变化，不视为纯 AMBIENT 验收 |
| 优化后 10 分钟 AMBIENT | **600.08s**，单核平均 CPU **5.87%**、最大 **7.14%**；1815 帧中 janky **0.11%**，PSS **56203KiB（约 55MiB）**，PID 3823 前后不变 |

10 分钟采样针对最终 UI 优化版本；随后只增加 Lua 模式匹配隔离，UI 与稳态调度未变化，最终 APK 已再次通过冷启动、合法插件和病态模式真机回归。冷启动测量时暂时恢复原桌面以避免 HOME 自启动产生 0ms 的假结果，测量后已将 ThinkOS 设回默认 HOME。

实体环境光传感器已读取到有效 lux；但“人工遮挡传感器”和“手靠近传感器”的实物动作尚未由人工验收。主题/接近策略的注入测试不能替代传感器有效距离和实物触发验收。性能测试用 debug 固定 AMBIENT 档位，防止现场交互/接近变化混入其它帧率档位；测试后解除。录屏中的方向切换使用 debug 控制，物理旋转仍需人工确认。

## 测试中修复的问题

- Android 8.1 的 ICU 正则要求绑定表达式结尾大括号显式转义，JDK 单测不能覆盖此差异，已在真机修复。
- 初始插件目录必须批量发布，避免按文件名字先加载 clock 时抢占 home 首屏。
- Lua 预算使用线程 CPU 时间，避免把 `tos.store` 的 fsync 等待当作脚本运算超限。
- JNI 显式转换 UTF-16 / 标准 UTF-8；保护解析/回调分配失败；阻止在不可挂起的回调中捕获预算异常继续死循环。
- Lua C 模式匹配不触发指令钩子，200 字符病态模式可阻塞数秒；M0 公开字符串库因此禁用任意模式匹配，find 仅允许 plain=true。内部 JSON 使用私有的固定模式，插件不能取得原始 matcher。
- 恢复闹钟不再被前台服务的周期看门狗覆盖；持久化的单调时间记录同时带开机世代。
- 稳态的极小漂移和 HH:mm 数字不再每帧重绘，背景呼吸按变化速度更新，减少 RenderThread CPU。CPU 验收按“一核满载=100%”计，不用除以核心数美化数据。

## 验证产物

本地生成物放在 `artifacts/m0/`（已 gitignore）：

- `demo.mp4`：翻页、表盘、旋转与夜间模式录屏。
- `home-landscape.png`、`home-portrait.png`、`night-portrait.png`、`light-landscape.png`、`hot-update.png`、`safe-mode.png`。
- `cold-start-offline.txt`、`cold-start-system-log.txt`、`boot-test.txt`、`hot-load.txt`、`crash-recovery.txt`、`recovery-boot-generation.txt`。
- `native-tests.txt`、`package-tests.txt`、`build-check.txt`、`apk-check.txt`、`idle-10min/`、`long-run-before-optimization/`。

APK：`android/app/build/outputs/apk/debug/app-debug.apk`。开发测试包覆盖已移除，设备恢复内置插件与正常模式；Wi-Fi / 自动旋转恢复原设置。

## 范围与后续

- `solar` 当前是 24 小时进度弧，不计算经纬度相关日出日落。
- 原生 SIGSEGV 的持久化计数不由 Java UncaughtExceptionHandler 覆盖；当前安全模式验证针对 Java 未捕获异常。进程退出仍有独立系统闹钟/前台服务恢复路径。
- 环境光/接近的实物操作、物理旋转、72 小时稳定性仍需人工或后续长测。
- M1 内容插件、M2 Server/同步/签名认证、M3 摄像头、M4 完整设置与性能降级未纳入本次基础框架。
