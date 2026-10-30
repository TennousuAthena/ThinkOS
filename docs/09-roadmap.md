# 09 · 里程碑与验收标准

> 每个里程碑都必须**在真机上验证**，不接受「模拟器跑通」。
> 顺序原则：**先做纯设备端的框架与应用基底**（不依赖 Server），再做内容插件，最后接入 Server 管理（ADR-015）。

## M0 · 设备端基底（纯设备，无 Server）

实现与真机验证记录见 [12-m0-validation.md](12-m0-validation.md)。基础框架已安装；以下复选框仅标记已完成的验证。

**交付**
- Gradle 工程（含 NDK/CMake），单 Activity，包名 `moe.kiwi.thinkos`
- Kiosk 外壳：HOME 接管、全屏沉浸、开机自启、前台服务、看门狗、崩溃自恢复与安全模式
- 状态模型：Store + 四条轴（Attention / Deck / Overlay / Link）
- 舞台：四层 Stage、MotionClock、FrameGovernor、背景呼吸场、Motion tokens
- 主题：环境光驱动 LIGHT / DARK / NIGHT，颜色 token 插值，夜间红色滤镜
- 注意力：接近传感器 + 触摸驱动 ASLEEP / AMBIENT / AWAKE / ENGAGED，亮度随之变化（摄像头留到 M3）
- 横竖屏：不重建 Activity，`LayoutSpec` + FLIP 重排
- 插件运行时：Lua 5.4（NDK 编译）+ JNI 桥 + 沙箱与预算 + `tos.state/timer/store/log/time/page` + 视图编译与绑定
- 页面：DeckRouter、PageHost、左右翻页 / 上下切变体；版式 `stacks`、`focus`；组件 `text`、`icon`、`clock`
- 内置插件：`home`（纯声明）、`clock`（表盘变体），打包进 `assets/plugins/`
- 调试安装插件：debug 构建监听 `files/dev-plugins/` 并热加载（见 `07-build-deploy.md` §3.1）

**验收**
- [x] `./gradlew assembleDebug` 通过，APK 内只有 `lib/armeabi-v7a/`
- [x] 断开网络、没有 Server 的情况下，冷启动 < 3s 并显示首页（最终版本 502ms；首页完整绘制 825ms）
- [x] Home 键回到应用；重启设备后自动进入应用
- [x] 左右翻页、上下切表盘都有动画，横竖屏平滑重排（真机 debug 方向输入；实体旋转待人工确认）
- [ ] 遮住环境光传感器：浅色 → 深色 → 夜间红色依次切换，过渡无闪烁
- [ ] 手靠近接近传感器 / 触摸能唤醒，静置后按设定时间变暗
- [x] 修改 `clock` 插件并经调试通道安装，不重启应用即生效（PID 保持不变）
- [x] 故意写一个死循环插件：被预算终止，其它页面不受影响（失败版本保留旧版）
- [x] 待机 10 分钟 `dumpsys gfxinfo` janky < 5%；AMBIENT 待机 CPU < 8%（受控 AMBIENT：0.11% janky、单核平均 5.87%）

**产出物**：`app-debug.apk` + 一段录屏（翻页、切表盘、主题切换、唤醒/变暗）

### M1 前加固

实现与回归记录见 [13-pre-m1-hardening.md](13-pre-m1-hardening.md)。

- [x] 缓存 Lua 入口、类型化 JNI、C JSON 与分代 GC；大响应解析与内存限制回归
- [x] HTTP/sleep 跨挂起恢复，每次恢复独立 CPU/墙钟预算；替换/删除/停止取消迟到回调
- [x] 普通错误保留实例；频繁/资源错误退避，冷却后重试；原生退出按摘要归因隔离
- [x] store 延迟合并 IO；状态大小增量核算、发布快照与预编译绑定
- [x] 跟手翻页/翻栈、弹簧吸附、同页局部更新、唤醒波纹与卡片交错动作
- [x] AWAKE 与 ENGAGED 静态数字时钟各测 ≥2 分钟，janky <5%，记录单核 CPU、PSS、帧数、主题/页面/档位；后续相同场景 CPU 增长 >20% 必须调查（约 60fps，CPU 32.112% / 31.618%，janky 0% / 0.03%）
- [x] 加固版 AMBIENT 首页连续 10 分钟，janky <5%、单核 CPU <8%，离线冷启动 <3s（CPU 2.923%、janky 0.63%；最终包仅追加存储备份读取恢复，UI/调度一致，最终冷启动 479ms）

## M0.5 · 设置体系与插件目录化（设备端，无 Server）

设计见 ADR-017、ADR-018。排在 M1 之前：M1 的内容插件从第一天起就用 `settings` 声明配置，也不必先适配一遍 `.tpk` 再改。

**交付**
- 设置契约：`shared/schema/settings/`（字段格式 + `system.json` 系统级清单，`04-ui-spec.md` §9）；manifest 的 `config` 改名为 `settings`（仍属 api 1，内置插件没有使用 `config`，无迁移）；生成脚本产出 Kotlin / Go 的校验器与默认值
- `SettingsStore`：三层叠加（默认 ← Server 下发 ← 设备修改）、校验（含跨字段）、合并写盘、变更通知、导出回报用的 overrides；`tos.config` 改读生效值并触发 `config` 事件
- 系统设置接入：ThemePolicy / AttentionPolicy / DeckRouter 里的硬编码阈值与计时改读设置；方向锁定；亮度上限
- `tos.settings.*` 与权限 `system.settings`（只授予 APK 内置插件）；`page.enter` 增加 `params`
- `form` 版式（开关、单选、滑块 / 步进、文本行；`04-ui-spec.md` §10）
- 内置 `settings` 插件（`settings` 角色，不在翻页序列里）；长按入口（含按住反馈）、退出（完成 / 30 秒 / Home 键）
- 原生救援菜单增加“重置设置”；`settings` 插件不可用或安全模式时长按回退到救援菜单
- `clock` 声明第一批页面级设置（如 24 小时制）作为样板
- 插件目录化：设备端安装器改为目录 + 文件清单（摘要 = 清单摘要）；APK 内置插件改为目录 + `index.json`；`plugin-push.sh` 推送目录；删除 zip 解包代码；`package_plugins.py` 改为校验并生成索引

**验收**
- [x] 长按任一页面进入该页面所属插件的设置；改一项后立即生效，重启后保留（声明式测试页面真机通过，见 `17-ui-validation.md`）
- [x] 长按首页某张卡片进入该卡片所属插件的设置；该插件没有设置项时进入系统设置
- [x] 一个只声明了 `settings`、没有 Lua 的测试页面也自动获得设置页
- [ ] 系统设置：主题固定为深色后，遮光 / 强光不再切换 LIGHT / DARK；方向锁定立即生效；改注意力计时后下一次计时按新值（原生设置页、调试亮度/无人输入与实际背光已验证，见 `17-ui-validation.md`；实体光照/旋转待人工确认）
- [ ] 非法值被拒绝并给出原因（超范围、跨字段冲突）；插件不能直接写设置（步进边界与跨字段回弹 UI 已验证，见 `17-ui-validation.md`；完整权限验收仍待补齐）
- [x] 禁用 `settings` 插件后长按进入原生救援菜单，“重置设置”清除设备上的修改（真实摘要隔离、横竖屏及恢复插件九项真机检查通过，见 `17-ui-validation.md`）
- [ ] 构建产物和设备上都没有 `.tpk`；目录形态的热更新不重启应用（PID 不变），坏版本保留旧版，死循环插件仍被预算终止
- [ ] 现有 JVM / 原生 / 插件测试通过；相同场景的 AWAKE / AMBIENT CPU 相对 `13-pre-m1-hardening.md` 的基线增长 ≤ 20%（超过必须调查）

## M1 · 内容插件

**交付**
- 运行时补齐：`tos.http` / `tos.json` 已在前置加固完成；继续 `tos.alert`、`tos.audio`、`tos.album`、`sources`
- 版式 `slideshow`、`nowplaying`、`list`；卡片模板 `glance`、`media`、`list`、`photo`；智能轮换（相关度）
- `weather`：已实现 `weather.now` / `weather.detail`，直连 Open-Meteo、原始数据缓存与恢复；位置沿用系统设置，插件提供温度单位、刷新/重试与关注阈值，验收见 `20-weather-handoff.md`
- `gallery`：默认微软 Bing 每日一图，可选本地/混合；API 3 `image/carousel`、通用 HTTP(S) 图片缓存与 `tos.library.list`；完整画面平移、常驻署名、设置与夜间退出。Server 下发/管理暂缓；验收见 `21-gallery-handoff.md`
- `tiles`：API 4 固定磁贴网格与 tile 模板，插件自定义底色、自动黑白前景、动态翻面、长按所属插件设置，clock/weather/gallery 的磁贴和纯声明示例页；T0–T6 / A1–A9 已通过，两轮各 600 秒；验收见 `24-tiles-validation.md`
- `aero`：API 5 原生 Aero 桌面，固定玻璃窗口、任务栏与开始菜单，公开引用时钟/天气/照片；不含 WebView 或实时模糊，设计与来源见 `25-windows-design.md`；独立 600 秒稳定性检查通过
- `music`：Navidrome 直连播放；ExoPlayer + `TeeAudioProcessor` 频谱；实时活动全屏接管
- `notify`：本地通知展示（两种形态），M2 接入手机通知中转
- 掉帧自动降级

**验收**
- [ ] 连续切换 50 张照片不 OOM（`dumpsys meminfo` 峰值 < 350MB）
- [ ] 播放 30 分钟不断流，暂停/继续正常；播放 + 频谱时 CPU < 25%、帧率 ≥ 50fps
- [x] 断网后天气卡片保留最后数据，恢复网络后自动更新（W0–W2 真机验收见 `20-weather-handoff.md`）
- [ ] 人为制造负载，掉帧降级逻辑生效
- [ ] 所有插件的 Lua 内存合计 < 24MB

## M2 · Server：下发与管理

**交付**
- Go 服务：mDNS 广播、共享 token 配对、设备登记
- 期望状态：`config.toml` → 期望状态文档（设置文档 + 部署配置）；插件来源（Git 仓库 / 本地目录）→ 校验 → 文件清单；照片集裁切（横竖各一份）与下发
- 插件安装（ADR-018）：`registry` 模块与 `thinkos-server plugin search / add / list / info / update / remove`；锁定 commit；权限确认；更新检查与跟踪分支；按 topic `thinkos-plugin` 搜索（GitHub / GitLab / Gitea·Forgejo）
- 设置同步：下发层、回报 overrides、冲突规则（`05-protocol.md` §3.5）
- 设备 SyncAgent：拉取、对齐、原子安装、回滚、回报；outbox 补传指标、日志、崩溃
- 实时通道：nudge、`POST /notify` 中转给 `notify` 插件、远程指令
- `tos.server.call / subscribe`；Server 上的音乐库流服务（`media`）

**验收**
- [ ] 在 Server 的 `plugins/` 里改一个插件，设备 10 秒内（实时通道在线时）自动更新，不重启应用
- [ ] 下发一个启动即报错的新版本插件，设备自动回滚并在 Server 看到错误
- [ ] 关掉 Server 30 分钟：设备功能无变化；重开后自动对齐并补传日志
- [ ] 手机快捷指令发通知，设备 2 秒内显示
- [ ] Server 换 IP 后设备自动重新发现
- [ ] `plugin add <https 仓库> --tag vX.Y.Z` 后设备 10 秒内加载；关掉 Server 和外网后该插件仍运行
- [ ] tag 被强制推送到另一个 commit：不自动更新，只提示
- [ ] 新版本增加了权限：确认之前不下发
- [ ] 含符号链接、子模块、超限文件、`..` 路径或大小写冲突路径的仓库被拒绝；取回过程没有执行仓库里的任何内容
- [ ] 手动导入并跟踪分支的插件：push 后几分钟内设备更新；从搜索结果安装的插件不能选择跟踪分支
- [ ] `plugin search` 在 GitHub 上能按 topic 返回结果
- [ ] Server 对某个 key 下发新值会覆盖设备上该 key 的旧修改；对其它 key 的无关更新不影响设备上的修改

## M3 · 视觉感知

**交付**
- Camera2 采集；L0：运动检测 + 1fps 人脸检测，接近传感器预触发
- L1：检测到人后 10fps 手势（挥手 / 左右 / 握拳），10s 无交互回落 L0
- 手势映射到翻页与插件指令

**验收**
- [ ] 40–70cm、正常室内光下，人脸检测准确率 > 90%；“走近 → 醒来”延迟 < 500ms
- [ ] 手势识别率 > 85%（3 个手势各测 20 次）
- [ ] L0 常驻时 CPU < 8%
- [ ] 低光环境下不崩溃（允许检测失败，退化为接近传感器 + 触摸）

## M4 · 打磨与稳定

**交付**
- 设备上调整首页栈内卡片顺序的入口（长按已用于设置，入口待定；Server 配置仍是主路径）
- 白噪音 / 环境音插件
- 可选语音模块（Server 端关键词识别）
- 设置页打磨：`text` 项在设备上的输入方案（取决于输入法实测结果）

**验收**
- [ ] 连续运行 72 小时不重启、不 OOM
- [ ] 人为 kill 进程后 30s 内自动恢复
- [ ] 断网 10 分钟后恢复网络，同步与实时通道自动恢复

## 里程碑之外（V1+）

- 配对码配对、每设备 token、git 签名 tag 校验（`git verify-tag`）
- 定时拍照 / 延时摄影
- 相册智能轮播（按人物 / 时间）
- Server 端管理界面（网页或原生，不涉及设备主界面）：插件浏览 / 安装 / 更新、查看和写回设备上的设置修改、用同一份 schema 生成设置表单
