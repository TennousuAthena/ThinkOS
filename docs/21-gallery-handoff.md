# 21 · 相册插件：接口、实现与开发交接

> 2026-10-30 续作。用户要求开发相册、完整画面平移与轮播、优先公开 ThinkOS 接口，随后明确要求实现**微软每日一图**。已纠正此前把 Bing 一并排除的范围理解：当前默认设备直连 Bing，Server 下发/管理仍暂缓。当前实现以本节、ADR-024 与 `06-plugins.md` §4.1.1 / §6.6–6.7 为准。

## 照片页白边修正（2026-10-31）

- 根因：页面整体的防残影位移会露出底层浅色背景；进入 AMBIENT 时的整组下移、淡化也会让照片边缘露底。
- 修复：OS 的 `stage` 中单个 `image/carousel` 固定覆盖视口，防残影位移只作用于时钟、提示和照片字幕。照片背景不参加注意力淡化/位移动效，旋转与重新布局也保持铺满；不透明照片覆盖期间暂停底层呼吸动画，拖动或切页露出背景时按原有逻辑恢复。图片的等比例平移、完整画面和手势行为沿用原实现；透明背景页面仍使用原有整体位移，Aero/tiles 后续统一固定背景、只移动前景（见 `25-windows-design.md`）。本节替代下方「OS 页面整体漂移」的旧实现描述。
- 无线真机验证横竖屏 × LIGHT/DARK × AWAKE/AMBIENT × 四角位移，共 32 组；检查屏幕坐标、可见区域、照片不透明度，以及时钟/字幕仍然位移，并在唤醒/待机动画过程中和字幕重新布局后复查。截图与结果见 `artifacts/gallery/viewport/`。Bing 的 ambient 页面按既定路由在 NIGHT 退出，不计为照片页面测试。
- `assembleDebug`、227 项 JVM 测试、lint、安装和 8–30ms 短促滑动回归通过。最终 APK 断网、无 Server 冷启动 774ms；601.69 秒真实 Bing 轮播采集 14,133/14,133 帧，jank 0.70%，无掉帧告警，最大 UI 绘制 5.40ms，端到端帧最大 30.38ms，峰值 PSS 109.19MiB、Lua 0.305MiB；页面、进程、方向不变，无输入。完整结果见 `artifacts/tiles/gallery-viewport-performance/report.json`，原设置和联网规则已恢复。
- 首轮诊断在 397 秒内记录到一次 UI 绘制 12.34ms，随后中止以测试暂停被遮挡背景动画的最终版本；该轮不算验收通过，原始数据和说明保留在 `artifacts/tiles/gallery-viewport-baseline/`。最终轮保留全部有效帧、异常峰值与非零 Flags 的原有计数规则，没有改动 12ms、jank <5% 和内存门槛。
- 复现视口检查：构建 `-PgalleryGestureProbe=true assembleDebug assembleDebugAndroidTest`，安装两份 APK，执行 `adb -s 192.168.3.167:5555 shell am instrument -w -e page gallery.slideshow -e viewport true moe.kiwi.thinkos.test/moe.kiwi.thinkos.GallerySwipeInstrumentation`。连续运行使用 `python3 tools/tests/tile_perf.py --serial 192.168.3.167:5555 --scene gallery --seconds 600`，保留与磁贴相同的页面/进程/输入校验、采样覆盖率与性能阈值。两轮逐帧/内存原文保存在 `artifacts/gallery/viewport/performance-raw.tar.gz`，SHA-256 索引为同目录的 `performance-raw-index.json`；在仓库根目录解压可恢复原路径。

## 短促快滑抖动修正（2026-10-31）

- 复现：旧版对 8ms 的很小竖向位移会误切图片；缺少越过 slop 的 MOVE 时又会落入点击路径。归位中的下一次拖动调用 finish.run 强制到达终点，取消后立即清理预览也会破坏接管。
- 修复：按系统 touch/paging slop 识别有效动作，抬手补用最终坐标；快速反向甩动优先撤销切换。手动翻页/回弹改为保存实际位置的可中断阻尼运动；新手势接住原有两层继续移动，完成后才提交选中图片并清理旧层。等待解码保持进度，旧请求与旧动画回调按代际取消；边界阻力接管也保持原像素位置。
- 设备内 instrumentation 直接重放 8–30ms 触摸流（多次 adb input 启动延迟不足以复现）。同一夹具修复前发生误切、接管突跳 6 次，最大位置重置约 901px；修复后无误切、接管突跳 0 次，最大 33.8px 为实际跟手移动。取消后回到原图并只留一个归位图层。
- 记录见 `artifacts/gallery/short-swipe/`（before/after JSON 与录屏）。专用 runner 仅通过 `-PgalleryGestureProbe=true` 启用；普通测试 runner、生产权限与 Lua API 不变。在仓库根目录复现：`(cd android && ./gradlew --offline -PgalleryGestureProbe=true assembleDebug assembleDebugAndroidTest)`，随后 `python3 -I tools/tests/gallery_short_swipe_device.py --record`。
- 同样的短促触摸流在真实 Bing 页面验证通过；慢下载/迟到解码、上下历史与边界、反向取消、横向页面导航、长按设置、横竖屏回归通过。216 项 JVM、lint 与 APK 构建通过；断网无 Server 冷启动 753ms。当前 APK SHA256 与设备一致，仍仅含 armeabi-v7a、min/target 27；测试包与插件覆盖已移除，Wi-Fi 与动画倍率已恢复。汇总见 `artifacts/gallery/short-swipe/validation.json`。本轮未重跑独立的十分钟性能基准，下方历史采样不代表本次手势修正版。

## 照片页布局修正（2026-10-30）

- 用户反馈时钟位置不美观、竖屏说明应统一贴底。本次将横竖屏时间都固定在右上角，24dp 边距，数字真正靠右对齐；去掉时钟自己的正弦漂移。OS 页面整体的微小防残影漂移继续保留，描边不变。
- 标题和署名改为同一底部文字组，24dp 边距：标题 18sp Medium、最多两行，下接 8dp 间距与 12sp 完整换行的署名。竖屏用完整可用宽度，横屏最多 560dp；不再用固定 72/52dp 高度和 146/90dp 抬升。
- stage 的状态提示按实际测量的底部文字高度避让，与说明同侧对齐并使用照片上的白色阴影文字，避免离线提示覆盖署名。所有规则由 OS 固定实现，未新增布局设置、私有插件能力或 schema 字段。
- 已通过 assembleDebug、lint 和 211 项 JVM 检查，并核对横竖屏 LIGHT/DARK 与状态提示场景；最终 APK SHA256 与设备一致。摘要见 `artifacts/gallery/layout/validation.json`，测试覆盖已移除。
- 对应真机截图在 `artifacts/gallery/layout/`，包括横竖屏 LIGHT/DARK 与状态提示场景；下方旧版截图与数字仍属于各自的历史版本。

## 本次交互续作（0.3.0）

- 用户要求上下滑查看历史、随机方向流畅平移，并给照片时间加轻黑边。这些是当前交互口径，替代下方旧的 5fps/3dp/s 方案。
- API 3 `carousel` 统一支持上滑下一项/下滑上一项，手动到首尾回弹；Bing 按时间倒序即上滑更早、下滑更新。图片随指移动，角落时间不跟着翻页；短拖动取消，快速换向和离页撤销迟到请求。自动轮播仍循环。
- 原生属性动画按显示刷新连续插值，随机选择起点方向，正弦缓入缓出；`pan_seconds=12` 最短单程、`pan_speed=45dp/s` 速度上限、`pan_direction=random/forward/reverse` 均走共享 schema 与插件设置。扫完后停留，宽图延长行程，完整画面不永久裁切。其它 OS 层仍按原有节拍运行，ASLEEP/离页/重建即取消图片动画。
- 照片 `clock.style=float` 统一白色文字，1.25dp 的窄半透明黑边，主时间、AM/PM、未校时文字共用；横竖屏、LIGHT/DARK 都保持可读，不改变其它时钟样式。
- 可复现实机手势、录屏和采样脚本：`python3 -I tools/tests/gallery_motion_device.py --record --bench`。当前续作的记录在 `artifacts/gallery/motion/`；下方本地阶段和 Bing 0.2.0 的数字保留为对应版本的历史记录。

### 当前续作的验证口径

- 真机手势已验证上下前后、最新边界、短拖动取消、快速换向、横向翻页、长按设置和横竖屏 LIGHT/DARK 时间描边。额外的本机 HTTP 夹具验证 A → 下载中的 B → C，B 的迟到响应不会覆盖 C；取消预览后仍可返回 B，再回 A。夹具只使用公开 API 3 和 network 权限，结束后移除插件、ADB 反向映射和自有缓存图片。
- 录屏见 `artifacts/gallery/motion/native-pan.mp4`。`portrait-active/` 是独立的 15.1 秒连续大幅平移样本：917 个渲染帧，约显示刷新率；20 帧 janky（2.18%），99th 18ms；单核平均 CPU 51.43%，结束 PSS 105,292KiB。以流畅性优先，这项开销高于前一版 5fps，未声称满足旧版 CPU<8% 目标。
- `motion/ambient/` 的 600.08 秒前台稳定性样本含额外的快速图片切换，未保存采样结束前的页面状态，**不作为隔离的相册纯待机 CPU 指标**。其记录为平均单核 CPU 12.943%、janky 1.24%、99th 17ms；进程未重启。请勿把它与上方独立连续动画样本或旧版低帧率样本混用。
- 新增公开契约字段、边界/方向/速度与手动邻项选择的 JVM 检查，共 211 项；Python 31 项、原生 Lua/JNI、Go、lint 与 assembleDebug 已通过。复现慢下载取消用 `python3 -I tools/tests/gallery_history_race_device.py`。

- 最终安装的 APK 与构建 SHA256 一致；本地 50 次切换、损坏图跳过、FD/减少动画/夜间退出，以及真实 Bing 缓存和常驻署名回归均通过。最终 Bing 断网冷启动 **467ms**；临时测试插件、ADB 反向映射、自有夹具缓存已清理，Wi-Fi 与动画倍率恢复。摘要见 `motion/validation.json`，最终界面见 `motion/final-clock.png`。

## 当前交付：先公开 OS 能力，再使用它

| 层 | 已实现接口 / 行为 |
|---|---|
| Lua 能力 | `tos.library.list{kind="image",dir=...,recursive=true}`，异步返回 `items/truncated/scanned/directory` 或 `nil,error`；通用枚举同时支持 audio/video 元数据，播放器仍未接线 |
| API 3 `image` | 本地文件、所属插件 `asset:assets/...`、HTTP(S)（network 权限）；`fit=pan/contain`、`pan`、`interval`、`pan_speed/pan_seconds/pan_direction`、`tap`、`weight` 可按 schema 使用 |
| API 3 `carousel` | `items` 状态路径，`interval/duration`，`fade/slide/zoom/random`，`shuffle`，`caption=auto/tap/off` 与 `caption_hold`；按稳定 id 保留当前图 |
| OS 图片管线 | LibraryAccess 最终打开授权；专用 Decode 线程；边界/文件大小检查、采样缩放、完整 EXIF 旋转与镜像、prepareToDraw；最多当前+转场两张；通用 MediaHttpClient 下载，2 个 ImageIO 线程，50MiB/512 项磁盘缓存、原子提交、相同 URL 合并、显示中 pin |
| gallery 插件 | 默认 Bing 每日一图，可选本地/混合；Bing 清单持久化、市场映射、重试与断网保留；≤300 项且清单预算≤180KiB，首页预览、轮播页、设置 schema、11 张语言表、refresh 命令、ASLEEP 暂停新请求；全程只使用 tos.* |
| 生命周期与路由 | 当前页才加载和计时，邻页预览不启动轮播；离页/睡眠/Activity 停止清空位图引用，取消后不接受迟到结果；NIGHT 退出 ambient 页且不自动切入照片 |

**不裁切的含义**：`pan` 等比例贴合一条边，沿溢出的轴扫过整张图，两端都会显示；OS 按速度上限自动延长这张图的停留时间。`pan=false`、`fit=contain` 或减少动画时整张缩放居中、纯色留边。没有 `cover/smart` 和 Ken Burns 永久裁边。

接口示例（普通第三方插件与自带相册完全相同）：

```lua
local result, err = tos.library.list{kind="image", recursive=true}
if result then
  local photos = tos.json.decode("[]")
  for i, item in ipairs(result.items) do
    if i > 300 then break end
    photos[#photos+1] = {id=item.src, src=item.src, title=item.title}
  end
  tos.state.set(tos.json.encode{photos=photos})
else
  tos.log.warn(err)
end
```

```json
{"layout":"stage","slots":{"stage":{
  "type":"carousel","items":"photos","interval":"{{config.interval}}",
  "duration":"{{config.duration}}","fit":"pan","pan":"{{config.pan}}",
  "pan_speed":"{{config.pan_speed}}","pan_seconds":"{{config.pan_seconds}}",
  "pan_direction":"{{config.pan_direction}}","transition":"{{config.transition}}",
  "shuffle":"{{config.shuffle}}","caption":"{{config.caption}}",
  "caption_hold":"{{config.caption_hold}}"
}}}
```

manifest 声明 `api:3`、`permissions:["library","network"]`，设置的实际定义见 `plugins/gallery/manifest.json`；不另建 SharedPreferences。所有可配置显示时间、速度和扫描节奏均来自该设置 schema。

### 微软每日一图：当前实现与真机验证

- 默认 `source=bing`，另可选 `local/all`；市场默认跟随 `tos.locale().language/region/script`，不支持本地化的语言退回英文。每 6 小时刷新，历史默认 30（1–90）；首轮 `idx=0/7`，后续增量，显式 wp=false 默认跳过。
- `plugins/gallery/sources/bing.lua` 规范化 OHR 图名、时间、标题和署名；`main.lua` 仅用 tos.* 获取与保存元数据。来源或市场变化先撤销旧代际；强制刷新失败仍会重试，第二批失败也保留并持久化第一批。
- 统一使用 `_1920x1080.jpg`，不切换为竖版裁图；OS 平移扫过完整画面。标题与版权署名分开；自动淡出/关闭字幕不隐藏 credit，预览卡片也显示完整署名。
- OS 通用图片接口先读缓存，再下载缺失文件；单文件≤8MiB、总计≤50MiB/512 文件，校验类型和图片边界后原子提交。当前显示和解码的缓存文件不被淘汰；离页取消下载，不阻塞本地图片。未预下载所有历史图；离线只能展示已下载且尚未被系统回收的图片。
- **真实设备已验证**：Bing HTTPS 元数据/图片成功，zh-CN 得到 13 张可用清单；实际显示 OHR.AutumnCranes、OHR.AveburyStones、OHR.KuneticeCastle，横竖屏共用原图；关闭字幕仍显示版权署名。关闭 Wi-Fi 后冷启动 **755ms**，从 tos.store 与 OS 图片缓存显示真实每日图，无 Server。
- **600.12 秒真实 Bing AMBIENT**（动画倍率 1，默认 3dp/s）：单核平均 CPU **5.577%**、10 秒采样最大 **19.841%**；2950 帧中 85 帧 janky（**2.88%**）、99th **18ms**、slow bitmap upload=1，结束 PSS **113,262KiB**（约 111MiB），PID 16109 未变。该性能样本的 APK 哈希另记在 `bing/performance-apk.json`；样本之后只修正 Lua 来源切换时先撤销代际的顺序，并同步 schema 描述，OS 图片管线相同。
- 截图与可复现脚本记录在 `artifacts/gallery/bing/` 和 `tools/tests/bing_device.py`；其中 `landscape-daily.png`、`portrait-daily.png`、`caption-off-credit.png`、`offline-daily.png` 分别验证首次显示、竖屏、常驻署名和断网。此处是设备实测，区别于下方实施前仅 Mac 请求的历史调研。

- **最终修正版已安装并复核**：210 项 JVM、31 项 Python、原生 Lua/JNI、Go、lint 与构建通过；Bing 缓存/横竖屏/常驻署名与本地相册 50 次切图回归通过。最终 APK 离线冷启动 **674ms**；设备 APK SHA256 与构建一致，Wi-Fi、动画倍率和临时覆盖均已恢复。摘要与两次 APK 的区分见 `artifacts/gallery/bing/validation.json`，最终界面见 `bing/final-apk/final-device.png`。

### 本轮边界与剩余工作

- **Bing 已实现并默认启用**：通过公开 `tos.http` 请求元数据，通过通用 `image/carousel` 下载和显示。Server 下发/管理、自定义 JSON 源和其它公共图源继续暂缓。
- 默认照片位置是应用外部私有 `Pictures/`；manifest **未新增 READ_EXTERNAL_STORAGE**，没有系统权限弹窗，也不修改 Device Owner。共享目录授权留待后续。
- 上下切相簿/来源变体与提前下载后续图片未实现；当前按需下载、磁盘 LRU 已实现；`controls/video/tos.media.*` 继续暂缓。
- 开发送图与公开字段范围见 `06-plugins.md` §6.6；本地来源空目录显示提示。默认 Bing 首次无网且无缓存时显示稍后重试；已有清单和图片时脱网继续展示。
- 下文 §1–11 是**实施前的来源调研与候选设计记录**。当前 Bing 使用同张 `_1920x1080` 源在横竖屏平移；历史提议的横竖两套裁图、cover/smart、API 3 顺带启用 controls/video、READ_EXTERNAL_STORAGE 和 G0–G6 全量范围不代表当前接口。

### 验证方式

```bash
cd android && ./gradlew --offline testDebugUnitTest lintDebug assembleDebug
python3 -I -m unittest discover -s tools/tests
python3 -I tools/package_plugins.py --validate-only
python3 -I tools/gen_android_strings.py --check
android/app/src/testNative/run.sh
GO111MODULE=off go test ./server/internal/protocol
python3 -I tools/tests/gallery_device.py --stress-library --bench --output artifacts/gallery/verified/device
python3 -I tools/tests/bing_device.py --bench --output artifacts/gallery/bing
```

设备脚本创建合成图片与临时 gallery 调试覆盖，验证热替换、横竖屏/主题、点击、转场、连续切图、离线冷启动和 600 秒 AMBIENT，并恢复 Wi-Fi、动画缩放、调试覆盖和测试目录。本地测试原始记录在 `artifacts/gallery/verified/`，不下载素材；`bing_device.py` 会由设备直接请求 Bing 并保存屏幕记录，恢复 Wi-Fi、动画倍率和临时覆盖，保留真实 Bing 缓存。

### 大图库的传输方式（API 3）

512 项真实图库压力测试发现逐字段 typed JNI 在 armv7 上超过 50ms。现在 `tos.library.list` 内部传 JSON 文本，再由预算内的 C codec 解码，**对插件仍返回表**。`tos.state.set` 增加通用对象 JSON 文本重载，gallery 用 `tos.state.set(tos.json.encode{...})` 提交大清单；所有插件都能使用，config 隔离、256KiB 状态上限与 50ms/200ms 执行预算保持不变。原失败日志保存在 `artifacts/gallery/large-library-failure/`。

重复扫描还发现 API 27 EXIF 路径的文件描述符问题：设备上遗留了 271 个已删除测试图片的 FD，后续扫描逐渐读不到文件。现在使用 InputStream 构造并由现有 ParcelFileDescriptor 明确关闭，设备脚本对重复扫描前后的 FD 数量作断言。背景可核对 [AOSP Android 8.1 ExifInterface 构造器](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r1/media/java/android/media/ExifInterface.java)（FD 构造器内部 dup；stream 构造器不走该分支）；原始失败记录在 `artifacts/gallery/repeated-scan-failure/`。

### 前一版动画排障记录（历史，已由连续动画替代）

实际开启动画后，持续的原生 60Hz 平移在此设备上消耗约半个 CPU 核心；仅移动硬件缓存层也没有解决。该阶段曾将平移改为 OS 统一节拍：AMBIENT 5fps，AWAKE/ENGAGED 30fps（页面短转场期间可提速）。默认 `pan_speed=3dp/s`，范围 1–120；几何计算按速度上限延长停留，完整扫过两端。单图往返用相同的纯函数时序，不保留无限循环的原生 animator。短转场/字幕仍使用属性动画。其它页面的原有帧率策略不变。

图层只缓存移动图片；不缓存整个轮播视口。过大的全景投影不分配巨型硬件纹理。系统实际禁用动画时，统一退回整张 contain。

测试脚本曾把“缺省动画倍率”恢复成字符串 `null`，此前动画采样条件不可靠，**那些数值不作为验收结果**。脚本现明确以倍率 1 测试，原来没有该键时用 delete 恢复。本地阶段有效记录放在 `artifacts/gallery/verified/`，Bing 阶段另见 `artifacts/gallery/bing/`；早期记录仅用于问题定位。

### 本地阶段的静态与原生检查（2026-10-30）

- JVM / lint / assembleDebug、Python 31 项、Go 协议测试与原生 Lua/JNI 测试均通过；最终数量与 APK SHA256 记录于 `artifacts/gallery/validation.json`。
- 原生测试运行真实 bootstrap、JNI 与完整 gallery Lua：300 张照片恢复并发布状态约 0.35ms CPU（开发 Mac，**不是 armv7 的耗时**）；中文标题、长路径预算、初始注意力事件与异步取消都有回归。
- 真机已验证 512 项库结果、300 张插件清单、重复扫描、50 次不同文件切换、横竖屏/主题、NIGHT 退出、RTL、减少动画和离线启动。最终矩阵与持续性能记录以 `verified/` 为准。
- APK 只有 armeabi-v7a；无新增依赖，无新增 Android 权限。该本地阶段尚未验收网络图片、共享目录授权与真实用户相册；不把原规划 §7 的全部方框标成完成。

### 本地阶段真机验收（有效记录）

- 最终 APK 已安装且设备 SHA256 与构建产物一致；206 项 JVM 测试、31 项 Python 测试、原生 Lua/JNI、Go 协议测试、lint 与构建通过。
- 明确以动画倍率 **1** 验证；测试后恢复原先不存在的倍率键、Wi-Fi 和临时调试覆盖。
- 524 个合成夹具文件触发 OS 的 512 项枚举上限，插件得到并展示 300 张清单；多次重扫后 FD 从 66 到 70，没有先前的累积泄漏。50 个不同文件切换后 PSS 126,337KiB（约 123MiB），这是采样值，未声称全程峰值。
- 横竖屏 LIGHT/DARK、NIGHT 自动退出、RTL 的前后平移截图、四种转场、点击、减少动画、离页停止与目录热替换均通过。
- Wi-Fi 关闭、无 Server 冷启动 **756ms**；本地相册照常显示。
- 完整 **600.08s** 实际动画 AMBIENT：单核平均 CPU **5.024%**，10 秒采样最大 **31.349%**（包含重扫/转场峰值）；2626 帧中 76 帧 janky（**2.89%**），99th **18ms**，slow bitmap upload=0；结束 PSS **69,088KiB**（约 67MiB）。PID 14688 始终未变。满足平均 CPU<8%、janky<5% 的目标。
- 原始记录：`artifacts/gallery/verified/device/`、`verified/ambient/`；摘要、测试数、条件和 APK SHA 在 `artifacts/gallery/validation.json`。本地阶段其它早期采样是排障历史，不用于验收；Bing 的真实图片验收另在 `bing/`。

---

## 1. 目标与边界

做自带插件 `gallery`：

- **页面 `gallery.slideshow`**：全屏照片轮播（StandBy 的"照片"视图）。角色 `ambient`——DeckRouter 在 AMBIENT 持续 `attention.ambient_page_after_s`（默认 300s）后自动切入；左右翻页也能到。
- **卡片 `gallery.preview`**：首页右栈里的小预览，点击打开轮播页。
- **图片来源可插拔**：首个内置来源是 **Bing 每日一图**，另有设备本地照片和自定义 JSON 源；Server 下发相册（M2）和其它在线源以后再加。

沿用的原则：

- 插件只做**数据层**（有哪些图、说明文字、刷新节奏）；OS 做**显示层和图片管线**（下载、缓存、解码、适配屏幕、转场、平移）——`14-handoff.md` §1 最新指示。
- 设备自主（ADR-015）：断网用已缓存的照片照常轮播；不依赖 Server。
- 只走 `tos.*` 和固定词汇（ADR-016/017）：不给相册开私有通道，不为外观预留扩展点。
- armv7 / API 27，无 WebView、Compose、GMS。**本方案不引入任何新依赖**（OkHttp、BitmapFactory 已有）。

本期不做：视频、人脸/人物相册、云相册账号（Google / iCloud / OneDrive）、EXIF 信息展示、NAS / Immich 适配、Server 下发、图片编辑。

## 2. 现状核对（已核对，2026-10-30）

| 项 | 现状 | 依据 |
|---|---|---|
| `image` / `carousel` 词汇 | schema 里有定义但标注 Deferred，**不在 `component.oneOf`、不在 `x-thinkos-api` 版本表**；安装器拒绝这些声明 | `shared/schema/plugin/layouts.json`；`06-plugins.md` §4.1 |
| 渲染器 | `MediaComponents.kt` 只有 `controls`、`video`；**没有 image / carousel 渲染器**。`PageEvents.component()` 是预留的工厂入口，默认返回 null | `ui/MediaComponents.kt`、`ui/PageEvents.kt` |
| `stage` 版式 | 已实现（API 2）：`stage` 槽位全出血，`over` / `under` 是叠在上面的 56dp 条带（距边 20dp） | `ui/RenderGeometry.kt` |
| `float` 时钟 | 已实现，带缓慢漂移（缓解 LCD 残影） | `ui/ClockView.kt` |
| 本地图片枚举 | `MediaLibrary.list{kind="image"}` 已实现：可递归、≤512 项，返回 `url / src / title / mime / width / height / rotation`（含 EXIF 旋转）。授权根目录 = 应用外部私有目录 + 共享存储（后者需要 READ_EXTERNAL_STORAGE） | `media/MediaLibrary.kt` |
| `tos.library` / `tos.media` | **没有接到 Lua**。接线草稿在仓库外 `/tmp/thinkos-media-integration/`（`MediaCapability.kt`、`StoragePermission.kt`、`CapabilityAwait.kt` 等），易失，接手先拷进仓库 | `14-handoff.md` 开头；该目录 |
| Manifest 权限 | 只声明 INTERNET、RECEIVE_BOOT_COMPLETED、FOREGROUND_SERVICE；**没有 READ_EXTERNAL_STORAGE** | `AndroidManifest.xml` |
| `tos.http` | 响应体按 **UTF-8 字符串**返回，上限 **1MiB** → Lua 不能下载图片字节 | `net/PluginHttp.kt` |
| OkHttp 媒体栈 | `MediaHttpClient` 与 `PluginHttp` 同一套私网 / 重定向 / 降级策略，连接 3s、读 15s；目前只有 `MediaEngine` 在用 | `net/MediaHttpClient.kt` |
| 页面角色 | DeckRouter 取第一个声明 `ambient` 角色的页面，AMBIENT 持续 `attention.ambient_page_after_s`（默认 300，范围 60–1800）后切入。**没有"夜间自动切回时钟页"**——04 §1.2 写了但代码里没有主题监听 | `core/DeckRouter.kt`、`shared/schema/settings/system.json` |
| 帧率档 | `FrameGovernor`：AMBIENT 无自绘动画 3fps，有则 30fps；View 属性动画由系统合成，不受它限制 | `anim/Motion.kt` |
| 状态上限 | `tos.state`、`tos.store` 各 ≤256KiB | `PluginRuntime.MAX_STATE`；`03-architecture.md` §3.13 |
| 设置类型 | 只有 `bool / choice / number / text`（text ≤1024 字符）；没有多选、没有列表；有 `hidden` | `settings/SettingField.kt` |
| `tos.locale()` | 返回 `{tag, language, region, script, rtl, hour24}`，可用来映射 Bing 市场 | `plugin/PluginRuntime.kt` |
| 设备 | Android 8.1.0 / API 27；`/data` 可用约 3.3GB；`/sdcard/Pictures` 为空，应用外部目录 `…/Android/data/moe.kiwi.thinkos/files` 尚不存在；系统根证书含 DigiCert Global Root CA / G2 / G3 与 ISRG Root X1；时钟已校准（HKT） | 只读 `adb shell`（本次） |

## 3. 图片来源

### 3.1 总览（提议）

| 优先级 | 来源 | 权限 | 说明 |
|---|---|---|---|
| P0 | **Bing 每日一图** | `network` | 无 Key；默认来源。见 §3.2 |
| P0 | **设备本地照片** | `library` | 默认应用私有 `Pictures/`（免运行时权限）；可改共享目录。M2 的 Server 下发相册也落在这里 |
| P1 | **自定义 JSON 源** | `network` | 用户自己托管：公网 HTTPS，或局域网 HTTP（私网规则）。NAS / Mac / Immich 代理都可以包成它 |
| P2 暂缓 | NASA APOD、Wikimedia 每日图 | `network` | 见 §3.3，实测发现的问题比较多 |
| 不用 | Picsum / Unsplash / Pexels / Pixabay | — | 随机图没有策展；后三者要 Key 与归属规则。Picsum 只能当压测素材，不进产品 |

### 3.2 Bing 每日一图（已实测）

> 以下数据来自 2026-10-30 **开发 Mac** 上的真实请求，只代表接口格式，**不代表设备实测**（设备取图见验收 D1）。

接口（无文档的内部接口，可能随时改变）：

```
GET https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=<market>
```

- HTTP 200，约 5.5KB，耗时 1.8–2.1s；`cn.bing.com` 同样可用。顶层 `images`（≤8 项）和 `tooltips`。
- 每项字段：`startdate enddate fullstartdate title copyright copyrightlink url urlbase hsh wp bot top drk quiz hs`。
- **`startdate` 是该市场"当日开始时刻"的 UTC 日期**：同一时刻 zh-CN 的 idx0 是 `20261029`，en-US 是 `20261030`；`fullstartdate` 带时分（如 `202610291600`）。不要当本地日期用；**去重用 `urlbase` 里的图名**，排序用 `fullstartdate` 做字符串比较即可。
- **深度**：`n` 最大 8；`idx` 超过 7 会被截断到 7（`idx=7/8/15/16` 返回同一批）。所以一次拿 8 天，两次（`idx=0` 与 `idx=7`）最多约 15 天；更早的历史只能靠设备自己累积。
- **图片地址** = `https://www.bing.com` + `urlbase` + 后缀。去掉 `url` 里的 `&rf=…&pid=hp` 也能取到。

同一张图各后缀的实测大小（HEAD，没有下载图片）：

| 后缀 | 比例 | 大小 | 结论 |
|---|---|---|---|
| `_1920x1080.jpg` | 16:9 | 333,190 B | **横屏默认**。铺满 1280×800 面板只裁 10% |
| `_1080x1920.jpg` | 9:16 | 338,324 B | **竖屏默认**。铺满 800×1280 只裁 10% |
| `_1920x1200.jpg` | 16:10 | 1,001,084 B | 与横屏比例完全一致，但体积 3 倍；已贴近 `tos.http` 的 1MiB 上限 |
| `_UHD.jpg` | 16:9 | 3,773,562 B | 不用：面板只有 1280×800，解码 8.3MP 只浪费内存 |
| `_1366x768` / `_1280x720` / `_800x480` | 16:9 | 170,712 / 104,178 / 48,521 B | 缩略图候选 |
| `_800x1280.jpg` | — | **404** | 不存在，别用 |

响应头 `cache-control: public, max-age=691200`（8 天）。**横竖各一个 URL、由 OS 按方向选**，Bing 来源就不需要平移，只需要轻微的 Ken Burns。

**市场（`mkt`）实测**：

| 结果 | 市场 |
|---|---|
| 正常本地化 | `zh-CN ja-JP de-DE fr-FR es-ES pt-BR en-GB en-US` |
| 回落英文（`EN-IN`） | `bn-IN`、`hi-IN` |
| 回落 `_ROW`：图片相同，但 **`title` 固定为 `"Info"`**，说明为英文 | `zh-HK zh-TW ru-RU ar-XA ur-PK`（`zh-HK` 首次请求连接被重置，重试后同为 `_ROW`） |

`_ROW` 不是垃圾数据，只是标题无用。插件必须：接受 `_ROW` 后缀；`title` 为空或等于 `"Info"` 时，取 `copyright` 里 `(©` 之前的部分当标题。

`auto` 市场映射（提议，用 `tos.locale()`）：`zh`+`Hans`→`zh-CN`，`es`→`es-ES`，`fr`→`fr-FR`，`pt`→`pt-BR`，`en`+地区 GB→`en-GB`，其余（`zh-Hant`、`ru`、`ar`、`bn`、`hi`、`ur` 等）→`en-US`。

**`wp` 字段**：每批 8 张里有 1–2 张是 `wp=False`（例：en-US 第 5、7 张，zh-CN 第 7 张）。含义没有官方说明，我推测是"不提供作壁纸"。**提议默认跳过 `wp=false`**（保守），做成隐藏设置 `wallpaper_only`。

**证书链**：`www.bing.com`（ECC）← Microsoft TLS ECC Root G2 ← **DigiCert Global Root G3**；`cn.bing.com`（RSA）← Microsoft TLS RSA Root G2 ← **DigiCert Global Root G2**。设备根证书库**两者都有**，静态上能通过；**设备真机 HTTPS 取图仍未验证**。设备没有 RTC，未校时 HTTPS 会失败（`10-known-issues.md` §2）。

**授权与署名（需要用户知情）**：图片版权归摄影师/图库（`copyright` 形如"…（© 摄影师/Getty Images）"），接口没有文档也没有 SLA。本项目只作**个人桌面展示**：不打包进 APK、不转发、不商用；界面始终显示 `title` 与 `copyright`；M2 的 Server 也不得代为分发这些图片。若将来要分发或商用，必须换成有明确授权的来源。**我没有核对 Microsoft 使用条款的原文**，这是需要用户自己判断的合规点。

复现探测（响应是不可信数据：存进全新的临时目录，用 `python3 -I` 解析，URL 先按正则校验再交给 curl）：

```bash
D=$(mktemp -d); curl -sS -A "ThinkOS-planning/1.0" -o "$D/hp.json" \
  "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US"
curl -sIL "https://www.bing.com/th?id=<urlbase 去掉 /th?id= 前缀>_1920x1080.jpg"   # 只发 HEAD，看 content-length
```

### 3.3 其它来源的实测

- **NASA APOD**：`api.nasa.gov/planetary/apod?api_key=DEMO_KEY` 返回 200，但 `x-ratelimit-limit: 10`（DEMO_KEY 很紧，日常使用要自己申请 Key，并存成 `secret` 设置项）。**当天的响应竟然是 NASA 徽标 PNG（title "NASA Science"）而不是天文照片**；另外部分日期是视频（`media_type=video`）。要校验 `media_type=="image"`、域名和尺寸，所以不作默认来源。
- **Wikipedia 精选图**：`en.wikipedia.org/api/rest_v1/feed/featured/YYYY/MM/DD` 返回 **293KB**（绝大部分与图无关），在 A53 上解码有预算风险；原图 7360×4600，必须用缩略图；许可是 CC BY 类，须署名；大陆可达性不保证。若要做，应换更轻的 Commons 接口并单独评估。
- **Picsum**：HEAD 返回 405，不用于产品。

### 3.4 本地与自定义源

**本地**

- 默认目录 = 应用外部私有目录下的 `Pictures/`（`getExternalFilesDirs()[0]/Pictures`），**免运行时权限**；设置里可改成共享存储路径（如 `/sdcard/Pictures`），此时 `LibraryAccess` 要求 READ_EXTERNAL_STORAGE。
- 申请读权限有两条路：草稿里的 `StoragePermission` 系统授权弹窗；或开发期 `adb shell pm grant moe.kiwi.thinkos android.permission.READ_EXTERNAL_STORAGE`（前提是 manifest 已声明）。应用已使用 `DevicePolicyManager`（`shell/KioskPolicy.kt`），若它是 Device Owner，还可用 `setPermissionGrantState` 静默授予，避免 Kiosk 下弹系统对话框——**未核对**。
- 格式 `jpg / jpeg / png / webp / gif（只显示首帧）/ bmp`。**HEIC / HEIF 在 API 27 没有解码器**，枚举里本来就没有，这是对的。
- 开发期送图：`adb shell mkdir -p /sdcard/Android/data/moe.kiwi.thinkos/files/Pictures` 后 `adb push`。

**自定义 JSON 源**（提议）：

```json
{ "items": [ { "id": "a1", "src": "https://host/a.jpg", "portrait": "https://host/a-p.jpg",
               "title": "…", "credit": "…" } ] }
```

≤1MiB、≤300 项；字段与 §4.3 的 item 一致；`http://` 仅限私网。

## 4. 架构

### 4.1 分层

```
 gallery 插件（Lua，数据层）                    OS（显示层 + 图片管线）
 ┌─────────────────────────────────┐       ┌────────────────────────────────────────────┐
 │ sources/bing.lua    tos.http    │       │ carousel：序列、转场、预取、平移、字幕             │
 │ sources/local.lua   tos.library │ state │      │                                      │
 │ sources/feed.lua    tos.http    │ photos│      ▼                                      │
 │ main.lua：合并、去重、缓存、调度、 │ ────▶ │ ImageLoader：URL 策略 → 磁盘缓存 → 解码线程 → 位图 │
 │          退避、空状态提示         │       │   ├ https / 私网 http → MediaHttpClient     │
 └─────────────────────────────────┘       │   └ 本地路径 → LibraryAccess.openAuthorized │
                                           └────────────────────────────────────────────┘
```

### 4.2 为什么图片字节必须由 OS 来取

- `tos.http` 把响应体当 UTF-8 字符串，且 ≤1MiB：二进制会被破坏，而 `_1920x1200` 实测已有 1.0MB。
- 解码、缓存、内存预算和转场必须在原生层统一管理；Lua 永远不在主线程，也不该持有大块数据。
- 离线要有磁盘缓存；放在 OS 层，所有插件共用。

### 4.3 契约变更（提议）

**`photos` 数组元素（item）**——插件把它放进状态，`carousel.items` 绑定它；OS 绑定时丢弃无效项：

| 字段 | 必填 | 说明 |
|---|---|---|
| `src` | 是 | `https://…`、私网 `http://…`、`file:///…` 或绝对路径、`asset:…`；≤2048 字符 |
| `id` | 否 | 稳定标识，用于去重、记住失败、保持当前图；缺省取 `src`。Bing 用图名（如 `OHR.AutumnCranes`），这样换市场后仍认得是同一张 |
| `portrait` | 否 | 竖屏时优先使用的地址；缺省回退到 `src` |
| `title` / `credit` | 否 | 字幕文字，≤120 / ≤200 字符 |
| `width` / `height` / `rotation` | 否 | 已知宽高与 EXIF 旋转（本地库已提供）；缺省时解码边界后得到 |
| `color` | 否 | 主色 `#RRGGBB`；缺省由 OS 解码时顺带算出，供 `mood` 使用 |

**`carousel` 属性**：现有 `items / interval / duration / transition(fade|slide|zoom|random) / fit(cover|contain|smart) / pan / shuffle / tap / weight` 保持不变，除 `tap / weight` 外都允许 `{{路径}}` 绑定（`items` 本身就是状态路径），所以可以直接绑设置值。**新增 `caption`**：`auto | tap | off`，可绑定。

**权限规则**：http(s) `src` 要求所属插件有 `network`；本地路径要求 `library`；`asset:` 不需要权限。不满足则该项不显示并记日志。

**API 版本**：`image`、`carousel`（以及同样处于 Deferred 的 `controls`、`video`）一起归 **API 3**——`06-plugins.md` §11.3 规定"之后新增词汇必须提升 `api`"，且 API 2 已被天气使用。改 `x-thinkos-api`，由生成脚本同步到 Kotlin / Go；与 music 计划协调同一次提升。

**URL 与响应策略**（OS 强制，插件不可配置）：只接受 https 与私网 http；拒绝 URL 里的 userinfo；重定向 ≤3 跳且不得 https→http；不接受插件自定义请求头或 Cookie；`Content-Type` 必须是 `image/*`；单文件 ≤8MiB；解码前先读边界，长边 >8192 或像素 >32MP 一律拒绝。超时沿用 `MediaHttpClient`。

### 4.4 ImageLoader（提议，预算见 `03-architecture.md` §3.14–3.15）

- **取**：远程走 `MediaHttpClient`（私网 / 降级策略已在里面）；本地走 `LibraryAccess.openAuthorized`（O_NOFOLLOW，已有）。同一 URL 的并发请求合并（预览卡和轮播会同时要同一张）。
- **磁盘缓存**：`cacheDir/images/`，键 = `sha256(url)`，**缓存优先、不重新验证**（Bing 的 URL 含内容 ID，同 URL 即同图）；按最近访问 LRU，总量常量 50MB（03 已预留）；临时文件 + rename 原子写；正在显示或预取的不被淘汰；本地文件不进缓存。横竖各缓存一张时每个 Bing 条目约 0.7MB，30 天约 20MB。
  注意：03 写的是"OkHttp 磁盘缓存"。OkHttp `Cache` 走 HTTP 语义，离线要额外加 `only-if-cached`；自管文件缓存更直接。若采用后者，请同步改 03。
- **解码**：专用单线程（03 的 Decode 线程）；先 `inJustDecodeBounds`，再粗降采样加精确缩放，位图不超过屏幕像素的 1.5 倍（≈6MB/张）；本地照片套 EXIF 旋转；`ARGB_8888`（`RGB_565` 在天空渐变上有色带）；解码后调用 `Bitmap.prepareToDraw()` 预热纹理，避免转场第一帧卡顿——**效果要用 `gfxinfo` 验证**。同时最多持有"当前 + 下一张"两块位图，不用 32MB 通用 LruCache（它留给预览卡等小图）。
- **主色**：解码时对降采样图求平均，无需再解码一次。
- **失败**：按 `id` 记失败并退避（建议 10 分钟），跳到下一张；全部失败则停在舞台背景。

### 4.5 `carousel` 行为（提议，平移算法承接 `14-handoff.md` §E）

- **适配**：裁切比 `c = 1 − min(图比/屏比, 屏比/图比)`。`c ≤ 0.2` → 铺满裁切 + Ken Burns（缩放 1.0→1.06，线性，贯穿停留时间，`04-ui-spec.md` §4.2）；`c > 0.2` 且 `pan` 开 → 沿被裁掉的那条轴匀速扫过整张（方向随阅读方向，RTL 反向；速度上限约 45dp/s，超过就延长这一张的停留，该值要肉眼调）；`pan` 关 → `contain`（纯色底，不做模糊——04 §1.3 禁止毛玻璃）。`fit=cover / contain` 强制覆盖。
- **转场**：`fade` 交叉淡入（`duration` 默认 800ms）、`slide` 推入（RTL 反向）、`zoom` 淡入加微缩放、`random` 每次随机。
- **实现原则**：转场和平移用 **View 属性动画**（API 27 上由 RenderThread 合成，不设监听）。这与 M1 前加固对呼吸动画的做法一致（`anim/Motion.kt` 的 `PropertyBreath`）；主线程只在换图的时刻被唤醒一次。`MotionClock` 保持 AMBIENT 的 3fps 档，只有必须自绘时才把 `animating` 设为真。系统"减少动画"开启时：只做 300ms 淡入，不平移。
- **序列**：`shuffle` 关 = 按数组顺序（Bing 为新→旧，所以每次进入先看到今日图）；开 = 带种子洗牌。`items` 更新按 `id` 做差量，当前图保持，不重置。
- **预取**：停留期间先把下一张解好，转场才开始；没准备好就延后，绝不显示半成品。
- **方向变化**：当前图自动换成对应方向的地址，解码完成后交叉淡入（300ms）。
- **不可见 / ASLEEP / 页面离开**：停止计时和动画，释放位图。邻页预加载（拖动翻页时）不得启动轮播。
- **空状态**：没有可用图片时只显示舞台背景（OS 呼吸场），文字提示由插件在 `under` 槽给出（"等待网络""没有找到照片"）。
- **字幕**：OS 绘制，插件只给文字。`auto` = 每次转场后出现约 6 秒再淡出；`tap` = 点一下才出现；样式按 04 §4.2 的"20% 黑色描边文字"，不用渐变底。
- **手势**：点击 = 下一张并显示字幕；长按 = 设置（OS 保留）；左右滑 = 翻页（OS 保留）；上下滑 = 切变体。
- **性能顺手项**：全出血的 `stage` 页面把 L0 呼吸背景完全盖住，应暂停 L0 呼吸，省掉被遮挡的合成。

### 4.6 夜间规则（需要在 DeckRouter 里实现）

04 §1.2：红色照片没有观赏价值，"照片页在夜间模式下自动切回时钟页"。目前 DeckRouter 没有主题监听。提议：主题进入 NIGHT 时，若当前页角色含 `ambient`，切到 `sleep` 角色页；退出 NIGHT 后按注意力策略恢复。

## 5. gallery 插件设计

### 5.1 文件

```
plugins/gallery/
├── manifest.json            "api": 3；permissions: network, library
├── main.lua                 调度、合并、缓存、状态
├── sources/bing.lua         URL 构造、字段校验、市场映射
├── sources/local.lua        tos.library 封装
├── sources/feed.lua         自定义 JSON 源校验
├── i18n/{11 种语言}.json
└── views/{slideshow.json, preview.json}
```

### 5.2 manifest 与设置

- `pages`：`slideshow`（`roles: ["ambient"]`，变体见下）。`widgets`：`preview`（`opens: "slideshow"`）。`commands`：`refresh`。
- 设置全部走 settings schema，插件只读（ADR-017）。ⓗ = `hidden`（保留、校验、可由 Server 下发，但不在设备设置页列出）：

| key | 类型 | 默认 | 范围 / 选项 | 组 |
|---|---|---|---|---|
| `bing` | bool | true | 启用 Bing 每日一图 | source |
| `market` | choice | `auto` | auto、en-US、en-GB、zh-CN、ja-JP、de-DE、fr-FR、es-ES、pt-BR | source |
| `local` | bool | true | 启用本地照片（目录为空则无影响） | source |
| `local_dir` | text | 空 | ≤512；空 = 应用私有 `Pictures/`；共享路径需要读权限 | source |
| `feed` | bool | false | 启用自定义 JSON 源 | source |
| `feed_url` | text | 空 | ≤512；https 或私网 http | source |
| `interval` | number | 30 | 5–3600 s，每张停留 | display |
| `transition` | choice | `fade` | fade、slide、zoom、random | display |
| `fit` | choice | `smart` | smart、cover、contain | display |
| `pan` | bool | true | Ken Burns 与长图平移 | display |
| `shuffle` | bool | false | 关 = 新→旧 | display |
| `caption` | choice | `auto` | auto、tap、off | display |
| `refresh_hours` | choice | `6` | 3、6、12、24 | data |
| `history` | number | 30 | 1–90 天，Bing 条目保留天数 | data |
| `wallpaper_only` ⓗ | bool | true | 跳过 `wp=false` | data |
| `endpoint` ⓗ | text | `https://www.bing.com/HPImageArchive.aspx` | ≤512、不含 query；测试和镜像用，图片 origin 取自它 | data |
| `retry_initial` / `retry_max` / `clock_retry` ⓗ | number | 60 / 900 / 30 s | 与天气同义 | data |

### 5.3 视图（草案）

`views/slideshow.json`：

```json
{
  "layout": "stage",
  "slots": {
    "stage": { "type": "carousel", "items": "photos", "interval": "{{config.interval}}", "duration": 800,
               "transition": "{{config.transition}}", "fit": "{{config.fit}}", "pan": "{{config.pan}}",
               "shuffle": "{{config.shuffle}}", "caption": "{{config.caption}}" },
    "over":  { "type": "clock", "style": "float", "format": "@time" },
    "under": { "type": "text", "value": "{{notice}}", "style": "caption" }
  }
}
```

`views/preview.json`（首页右栈卡片）：

```json
{ "template": "glance", "relevance": 0.2,
  "children": [ { "type": "image", "src": "{{cover}}", "fit": "cover", "pan": false, "weight": 1 },
                { "type": "text", "value": "{{coverTitle}}", "style": "caption" } ] }
```

待核对的 UI 细节：

- 04 §4.2 要求角落时间"横屏右上、竖屏左下"，而 `stage` 的 `over` 是满宽 56dp 条带，`float` 时钟目前居中——需要定对齐规则。
- 卡片内的 `image` 要裁成卡片圆角（`ComponentGroup` 的 card 模式加 `clipToOutline`）。
- **变体（提议，G5）**：上下滑切来源——`all / daily / local`。需要先确认 `variants[].title` 是否支持 `@key` 翻译（`clock` 的变体标题目前是写死的英文）。

### 5.4 状态模型

`photos`（item 数组，≤300 项、总计远低于 256KiB）、`cover`、`coverTitle`、`notice`（离线 / 无照片 / 没权限等提示）。Bing 条目每项约 350B，300 项约 105KB；本地条目只复制必要字段，每项约 150B。

### 5.5 行为（承接天气的做法，见 `20-weather-handoff.md` §4）

- **`start` 只读缓存**：`tos.store.get("bing")`（**JSON 文本**，用 `tos.json.decode`）、异步扫描本地，然后渲染；**1 秒后**才联网，不让热替换等待 HTTP。天气的教训：几百个标量反复过 typed JNI 会超预算，所以缓存存 JSON 文本，`tos.state.set{photos=…}` 只在列表真的变化时写；150–300 项的 CPU 要在 armv7 上实测。
- **Bing 刷新**：首次拉 `idx=0` 与 `idx=7` 两批（约 15 天），之后只拉 `idx=0`；默认每 6 小时一次，唤醒时若超期补拉；ASLEEP 不刷新；`inflight` 防重入；失败保留旧列表，退避 1→2→5→15 分钟；`tos.time.now()` 为 nil（未校时、TLS 会因证书时间失败）→ 30s 短重试。
  可选优化：用 `fullstartdate + 24h` 精确安排下一次（新图在各市场午夜出现，HKT 下 zh-CN 是 00:00、en-US 是 15:00）。需要在 Lua 里手写 UTC 日期换算，先不做。
- **校验与合并**：`urlbase` 必须匹配 `^/th%?id=OHR%.[%w_.-]+$`（含 `_ROW`，故意放宽——探测过的 28 条（含 `_ROW`）都符合，过严会悄悄丢掉合法项），不合格丢弃；`id` = 去掉末尾 `_<市场><数字>` 后的图名；`title` 为空或 `"Info"` 时回退到 `copyright` 前半段；`wallpaper_only` 开则丢 `wp=false`；按 `id` 去重，只保留最近 `history` 条（按 `fullstartdate` 排序）；图片 origin 取自 `endpoint`。换 `market` 时同 `id` 的条目整体替换。
- **本地**：`tos.library.list{kind="image", dir=…, recursive=true}`；结果超过 300 项只取前 300 并记日志（更大的相册请分目录）；读权限被拒 → `notice`。
- **多来源（`all`）**：轮流取，避免 300 张本地图淹没 15 张 Bing。
- **事件**：`config` → 重渲染，来源开关变了再重新合并；`locale` → 重新映射 `auto` 市场；`variant` → 切换来源过滤；`attention` → 同天气；命令 `refresh`。
- **可选兜底**：在 `assets/` 放 2–3 张自制图（每张 ≤1MiB），让首次离线开机也有可看内容；不使用第三方版权图。

### 5.6 国际化

约 45 个键 × 11 种语言（天气是 56 键）：名称与页面标题、分组与设置标签 / 帮助、choice 选项、状态提示（等待网络、离线、没有找到照片、没有读取权限、未校时）、变体名。校验沿用 `python3 -I tools/gen_android_strings.py --check` 与 `python3 -I tools/package_plugins.py --validate-only`。

## 6. 开发分解

相对工作量 S / M / L 是估计。依赖：G0 → G1 → G2 → (G3 ∥ G4) → G5 → G6。**G1、G2 不依赖网络，也不依赖 Lua 接线**——用一个调试推送的测试插件（`tools/plugin-push.sh`，不进 APK）加本地合成图片即可做"纯 UI 测试"，与天气"先 UI 后数据"的节奏一致，UI 自动化可参考 `tools/tests/component_ui.py`。

| 阶段 | 内容 | 主要文件 | 验证 |
|---|---|---|---|
| **G0 契约与决策（S）** | 新 ADR（编号待分配：08"待复议"把 ADR-023 留给 HTTP 通知提案，建议相册用 ADR-024）：图片由 OS 取并缓存、来源白名单、Bing 的合规说明。`layouts.json`：`image` / `carousel` 进 `component.oneOf`，`carousel.caption`，`x-thinkos-api` 提到 3。跑 `python3 shared/gen/gen_kotlin.py && python3 shared/gen/gen_go.py`，**不手改**生成文件 | `shared/schema/plugin/layouts.json`、`docs/08-decisions.md` | `PluginSchemaTest`（已有两条 carousel 用例）、`tools/tests/test_plugin_packages.py` |
| **G1 图片管线（M）** | 新包 `moe.kiwi.thinkos.image`（提议）：URL 策略、磁盘缓存、解码、门面 `ImageLoader`；合并并发请求；失败退避 | `android/app/src/main/java/moe/kiwi/thinkos/image/` | JVM：URL 策略表（userinfo、公网 http、https→http 重定向、非 `image/*`、超大）、缓存键 / LRU / 原子写 / 损坏文件、采样与目标尺寸、主色。设备：1920×1080 JPEG 在 A53 上的解码耗时、`prepareToDraw` 前后首帧耗时、位图内存 |
| **G2 渲染器（L）** | 纯函数 `FitMath`（适配、裁切比、平移区间、速度上限）；`ImageComponent`、`CarouselComponent`（经 `PageEvents.component()`）；转场、平移、字幕、点击、减少动画、空状态、差量更新、生命周期；卡片圆角裁剪 | `ui/`（新文件）、`ui/ThinkStage.kt` | JVM：极端比例、RTL、0 尺寸、差量更新状态机。设备：纯 UI 测试，截图矩阵（§7 U） |
| **G3 Lua 接线（M）** | 只接 `tos.library.list` 与读权限流程：从草稿 `MediaCapability.kt` 里**拆出 library 部分**（`media.*` 属于 music 计划，别一起带进来）；`src` 的权限校验；Manifest 增加 READ_EXTERNAL_STORAGE（需用户确认，§9） | `plugin/`、`media/`、`AndroidManifest.xml`、`RuntimeHost.kt` | 原生 Lua 测试、权限拒绝、取消后迟到结果被丢弃（`CapabilityAwait`） |
| **G4 插件（M）** | §5 的全部文件；`tools/package_plugins.py` 默认列表加入 `gallery`；`plugins/home/views/main.json` 右栈加 `gallery.preview`；11 种语言 | `plugins/gallery/`、`tools/package_plugins.py` | Lua 单测 `tools/tests/gallery_test.lua`（夹具见下）、armv7 原生预算测试、`--validate-only` |
| **G5 夜间规则与变体（S）** | DeckRouter 的 NIGHT 规则（§4.6）；变体与 `variant` 事件；确认 `ambient` 切入联调 | `core/DeckRouter.kt` | JVM + 设备 |
| **G6 真机验收与文档（M）** | 跑 §7；原始记录放 `artifacts/gallery/`；本文改写成"交接与验收"（仿 `20-`）；同步 §10 的文档 | `artifacts/gallery/`、`docs/` | — |

**测试夹具**（全部合成，不下载素材，AGENTS.md）：

- Bing 响应：en-US 正常批次、`_ROW` + `title:"Info"`、`wp=false`、重复项、缺字段、非法 `urlbase`、畸形 JSON。
- 图片：标准库生成 PNG，用 macOS `sips` 转 JPEG；覆盖 16:9、16:10、9:16、3:2、4:3、21:9、3:1、64×64、8000×6000，再加截断 JPEG、0 字节、HTML 冒充图片、慢速响应、重定向到公网 http。
- 本地 HTTP 夹具：扩展 `tools/tests/http_fixture.py`，提供 `/HPImageArchive.aspx` 与 `/th?id=…`；用 `adb reverse` 映射到设备；插件 `endpoint` 设为 `http://127.0.0.1:<端口>/HPImageArchive.aspx`（回环 http 属于私网，允许）。

## 7. 验收

**U · 界面**
- [ ] 横屏 / 竖屏 × LIGHT / DARK / NIGHT 各截图一张；NIGHT 时自动离开照片页
- [ ] `fade / slide / zoom / random` 四种转场流畅；`pan` 对 16:9、4:3、21:9、3:1 样图方向和范围正确，RTL 反向；`contain` 纯色底、不模糊
- [ ] 字幕三种模式；长标题不溢出；阿拉伯语 RTL；竖屏 / 横屏位置合规
- [ ] 点击下一张、长按设置、左右翻页、上下变体都正常；页面离开再进入不重置位置
- [ ] 系统"减少动画"开启时没有平移
- [ ] 预览卡：圆角裁切、换图时交叉淡入

**D · 数据与网络**
- [ ] **D1 设备真机取 Bing**：`www.bing.com` 与 `cn.bing.com`、en-US 与 zh-CN，JSON 与图片 HTTPS 均成功；未校时失败后，校时自动恢复
- [ ] 断网冷启动：立即轮播已缓存的照片；无缓存且断网：空状态、不崩溃
- [ ] 中途断网再恢复：当前轮播不中断，缓存外的项被跳过；恢复后下一次刷新成功
- [ ] `_ROW`、`title:"Info"`、`wp=false`、重复项、畸形 JSON 的处理正确（Lua 单测 + 设备夹具）
- [ ] 本地：私有 `Pictures/`（免权限）；共享目录授权 / 拒绝两条路径；HEIC 被忽略；>300 项只取前 300 并有日志
- [ ] 自定义源：局域网 http 夹具 + 公网 https

**P · 性能与内存**
- [ ] 连续切换 50 张不同尺寸的照片不 OOM，`dumpsys meminfo` 峰值 <350MB（M1 验收）；切换后内存回落，位图不泄漏
- [ ] AMBIENT 10 分钟（真实 Bing 图、`interval=30`、`pan` 开）：janky <5%，单核 CPU <8%；记录与 `13-pre-m1-hardening.md` AMBIENT 首页基线（2.923%，janky 0.63%）的差值
- [ ] AWAKE / ENGAGED 各 ≥2 分钟记录 CPU、janky、PSS，作为后续基线
- [ ] 转场期间 `gfxinfo` 帧耗时无 >32ms 的首帧卡顿
- [ ] Lua：一次刷新（解 JSON + 合并 + 写状态）<50ms CPU、内存 <1MiB；冷启动读缓存并渲染 <50ms；armv7 原生预算测试
- [ ] 断网、无 Server 冷启动 <3s，相册不拖慢启动
- [ ] 磁盘缓存 ≤50MB，LRU 生效，淘汰不影响正在显示的图

**S · 失败与安全**
- [ ] 404、HTML 冒充图片、截断 JPEG、0 字节、超大像素、Content-Type 错、慢速 stall、https→http 重定向、公网 http：全部拒绝或跳过，不崩溃、不卡主线程
- [ ] 没有 `network` / `library` 权限的插件声明 `src` 被拒
- [ ] 热更新 `gallery` 不重启应用（PID 不变）；禁用 `gallery` 后 DeckRouter 没有 `ambient` 页也不崩溃

通用：`./gradlew assembleDebug` 与 JVM / lint、`adb install -r`、APK 内只有 `lib/armeabi-v7a/`、`python3 -I -m unittest discover -s tools/tests`、`python3 -I tools/package_plugins.py --validate-only`、`python3 -I tools/gen_android_strings.py --check`。命令见 `20-weather-handoff.md` §7。

## 8. 风险

1. **Bing 接口无文档、无 SLA，且有版权**：格式可能变、可能被限流；`wp` 含义是推测；条款原文我没读。→ 个人展示用途；字段校验失败时保留旧列表；来源设计成可替换。
2. **设备真机 HTTPS 取图未验证**：根证书静态上够，实际还要看 TLS 握手、设备时间和网络。→ D1 最先做。
3. **图片管线是新的大模块**：A53 解码、纹理上传、位图生命周期都可能出卡顿或泄漏，是主要工作量。→ G1 先做设备小测再写渲染器。
4. **属性动画驱动转场和平移**：呼吸动画已验证过这条路径，但带位图的平移和交叉淡入没有。→ 如果 RenderThread 路径行不通，退回 `MotionClock` 自绘，并重新评估 AMBIENT 的 CPU。
5. **状态体积和 JNI 成本**：150–300 项过 typed JNI 可能再次逼近 50ms 预算（天气已踩过）。→ 缓存存 JSON 文本；`history` 默认 30；实测后再调字段。
6. **文档与代码不一致**（落地时要一并修）：
   - 03 §3.13 的 `filesDir/albums/`（Server 下发相册）不在 `LibraryAccess` 的授权根目录里——`tos.library` 读不到。M2 前统一：建议改放外部私有目录 `Pictures/<album>`，或给 `LibraryAccess` 增加内部根。
   - 03 的"OkHttp 磁盘缓存"与本文的自管缓存。
   - 04 §4.2"设备不缩放"已不成立：设备自主后由 OS 适配屏幕，Server 预裁只是可选优化。
   - 06 §4.1 / §5 仍写着 `album:` 地址和 `sources.album` / `photos` 权限（权限已改名为 `library`），06 §8 的 `deck.roles` 配置与现在由 manifest `roles` 决定的实现也不一致。
7. **夜间规则缺失**（§4.6）。
8. **LCD 残影**（`10-known-issues.md` §4）：长时间静态高对比内容会留影。Ken Burns、漂移的角落时钟和定期转场就是缓解手段，不要因为省 CPU 而全部关掉。
9. **Kiosk 下的权限弹窗**：系统读权限对话框在 Kiosk / lock task 下是否可用，没有实测。→ 默认目录不需要权限；共享目录再用 `pm grant` 或 Device Owner 授权。
10. **eMMC 写入**：缓存每天只新增约 1MB，可以忽略。

## 9. 实施前的确认清单（历史记录）

| # | 问题 | 我的建议 |
|---|---|---|
| 1 | 是否解除相册（图片轮播）的暂缓，并按 G0→G6 推进？ | 是；先 G0–G2 做纯 UI，和天气一样"先 UI 后数据" |
| 2 | Bing 合规姿态：接受仅个人展示，默认启用，默认跳过 `wp=false`，字幕始终显示 `copyright`？ | 是；条款原文请你自己判断 |
| 3 | Bing 没有本地化的市场（繁体中文、俄语、阿拉伯语、孟加拉语、印地语、乌尔都语）：字幕用英文还是简体中文？ | `en-US`；偏好简体的用户可在设置里选 `zh-CN` |
| 4 | Manifest 新增 `READ_EXTERNAL_STORAGE`（为了支持共享目录）？ | 可以加，但只在用户把 `local_dir` 设为共享路径时才会真正请求；默认私有目录不需要 |
| 5 | 实现 NIGHT 自动离开照片页？ | 是（04 §1.2 已有设计） |
| 6 | `image` / `carousel` 与 music 的 `controls` / `video` 一并提升到 API 3？ | 是 |
| 7 | 默认值：`interval=30s`、`transition=fade`、`pan` 开、`shuffle` 关（新→旧，进入时先看到今日图）、`caption=auto` | 按此，后续可调 |
| 8 | 上下滑 = 切来源（全部 / 每日 / 本地）？ | 是，放 G5 |
| 9 | 出厂是否带 2–3 张自制兜底图？ | 先不带，空状态足够；要带的话必须是自制或明确授权的图 |

## 10. 原规划的文档同步清单（历史记录）

- `06-plugins.md`：§4.1 词汇表（`image` / `carousel` / 字幕 / API 3）、§4.2 的 `album:` 地址、§5 的 `sources.album`、§6.2 的 `tos.library` 状态、§8 的 `deck.roles`、§10 的 `gallery` 行。
- `03-architecture.md`：§3.13 的 `albums` 路径与图片缓存、§3.14 / 3.15 的 Decode 线程和内存预算、新增 ImageLoader 模块说明。
- `04-ui-spec.md`：§4.2 `slideshow`（设备适配、字幕、角落时钟位置、夜间规则）。
- `08-decisions.md`：新 ADR；`09-roadmap.md` M1 的 `gallery` 条目；`14-handoff.md` §E / §G 的指向；`shared/schema/plugin/README.md`；`plugins/README.md`；`tools/package_plugins.py` 与 `plugins/home/views/main.json`。

## 11. 代码与文档索引

| 要看什么 | 位置 |
|---|---|
| 组件 / 版式词汇与 API 版本表 | `shared/schema/plugin/layouts.json`（`x-thinkos-api`） |
| manifest 与设置 schema | `shared/schema/plugin/manifest.json`、`shared/schema/settings/` |
| 媒体组件与工厂入口 | `android/.../ui/MediaComponents.kt`、`ui/PageEvents.kt`、`ui/RenderComponent.kt` |
| `stage` 槽位几何、`float` 时钟 | `ui/RenderGeometry.kt`、`ui/ClockView.kt` |
| 本地图片枚举与授权 | `media/MediaLibrary.kt`（`LibraryAccess`、`LibraryScanner`） |
| OkHttp 媒体栈与私网策略 | `net/MediaHttpClient.kt`、`net/LocalNetworkPolicy.kt`、`net/PluginHttp.kt` |
| 页面角色与 ambient 切入 | `core/DeckRouter.kt`；设置 `attention.ambient_page_after_s` |
| 帧率档与属性动画样例 | `anim/Motion.kt`（`FrameGovernor`、`PropertyBreath`） |
| 数据插件样板 | `plugins/weather/`（`main.lua` 的调度 / 缓存 / 退避）、`plugins/clock/` |
| Lua 接线草稿（仓库外） | `/tmp/thinkos-media-integration/`——接手先拷进仓库 |
| 测试样板 | `tools/tests/weather_test.lua`、`weather_native_device.sh`、`weather_device.py`、`http_fixture.py`、`component_ui.py` |
| 性能基线 | `13-pre-m1-hardening.md`、`20-weather-handoff.md` §6 |

**当前接手顺序**：先读本文开头的实际接口与验证记录 → `06-plugins.md` §4.1.1 / §6.6 → `plugins/gallery/` 与 `image/`、`ImageComponents.kt` → `artifacts/gallery/verified/`。本轮本地能力已实现；不要再次按历史 G0 从头接线。接上游服务、共享目录或媒体播放器需要单独任务。

### 相册目录（UI-03）

`local_dir` 留空时使用应用的外部私有目录 `Pictures/`（设备上为 `/sdcard/Android/data/moe.kiwi.thinkos/files/Pictures/`，完整路径由 `MediaLibrary` 解析），不需要共享存储权限；改成共享存储目录时沿用 OS 的存储授权流程。界面输入框只显示“默认目录”，不在设置行重复说明。
