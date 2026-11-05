# 34 · OTA 更新与插件仓库

> 状态更新于2026-11-05：已实现并分段真机验证，未完成正式来源的端到端验收。各证据仅对应其版本；旧文件头迁入 `artifacts/update/OTA-4/header-history-20261105.md`。执行边界、当前任务和人工步骤以 `AGENTS.md`、`36-task-list.md` §0.7为准。

| 任务 | 已实现 | 真机验证到哪 | 缺什么 |
|---|---|---|---|
| OTA | 签名目录、续传/缓存、运行期门禁、原生入口、应用UID安装/回滚与健康守卫、本地prepare工具 | 0.1.4安装/崩溃回滚/600秒健康分段通过：`artifacts/update/OTA-3/install/qualification.json`；准备工具仅主机检查：`artifacts/update/OTA-1/tools/host.log` | 正式公钥/来源、证书迁移、真实来源自动安装/完整回滚与所有者发布；独立审计F2仍开放（`artifacts/update/OTA-6/`） |
| MOD | 固定七id、系统根独立验签、模块事务/启动守卫、原生库存 | armv7构建与host检查；0.1.9临时keys0只读库存及无更新器界面：`artifacts/update/MOD-2/0.1.9/qualification.json` | 更新器/外置守卫未持久安装；完整升级/重启/健康/回滚仅在备用机验收 |
| REG | 签名目录/文件下载、权限确认、安装/更新/卸载、试用回退及Hub；所有者主机签名与无钥匙CI模板 | 0.1.8基础签名安装16项：`artifacts/registry/REG-2/0.1.8/qualification.json`；仓库工具12项：`artifacts/registry/REG-1/qualification.json` | REG-2b真实600秒生命周期、恶意样本/权限/吊销与16数量上限；未创建正式仓库、tag或Release |

## 1. 要做什么

| | 目标 |
|---|---|
| **OTA** | 设备自己发现、下载、校验并安装新版 ThinkOS（APK）、系统模块和官方插件；打开“自动安装更新”时全程不弹提示 |
| **插件仓库** | 一个放在 GitHub 上的公开目录：作者提交，CI 校验，用户在设备上浏览、安装、更新、卸载 |
| **共同前提** | 不需要 Server（ADR-015）；检查失败不影响设备运行；设备不碰 Git（`AGENTS.md`）；**应用里仍然没有 `su`**（ADR-033） |

**不做**：刷 ROM；更新第三方系统组件（Magisk、Vector、busybox、ViPER）；安卓 App 商店；差分更新；遥测；评分和评论；付费。

## 2. 现状（已核对 / 实测）

### 2.1 发布

| 事实 | 位置 | 影响 |
|---|---|---|
| GitHub 仓库 `TennousuAthena/ThinkOS` 是**私有**的，没有任何 Release | `gh repo view` | 设备不带令牌就下载不到。不能把令牌放进设备 |
| `LICENSE`（MIT）和 `LICENSE-GPL-3.0.md` 已放（2026-11-03）；Go 没有依赖，`assets/` 只有 Lucide 图标；关于页已实现（完整版25项设备检查通过）、FFmpeg源码存档与离线重建已通过、FairPlay构建开关和双构建已通过，设备opt-out回归待测；`playfair` 的法律风险待所有者评估 | ADR-043 | 公开分发完整版 APK 就要同时公开完整源码（GPLv3），这份清单是上线门槛 |
| 仓库跟踪着 2319 个验收产物（截图、日志），提交历史里有个人邮箱，11 个文档或脚本里出现局域网地址或 `ssid` 字样（没有逐个看） | `git ls-files`、`git grep` | 直接把仓库改成公开，这些都会公开 |
| 设备上是 debug 包：证书 `CN=Android Debug`（`f5:28:26:51…`，只有 v2 签名）、`DEBUGGABLE` | `apksigner`、`dumpsys package` | 与正式版证书不同就不能覆盖安装 |
| **API 27 不能换证书**：密钥轮换从 Android 9 才有（[来源](https://source.android.com/docs/security/features/apksigning/v3)） | | 证书一旦定下，就是所有设备的永久信任根 |
| 版本名来自`android/gradle.properties`的`thinkos.version`，版本码按主×1000000+次×1000+修订生成；当前正常版本0.1.3/1003 | `build.gradle.kts`，OTA-1 | 先前固定2的状态已纠正；静默自更新实验中的递增版本已真实安装 |
| debug/release共用未跟踪的`android/keystore.properties`；缺失时明确警告并共用现有debug证书，配置存在但不完整则失败。0.1.3非debuggable release已在真机完成16项独立冒烟，再覆盖回debug，均未卸载 | `artifacts/update/OTA-0/release/`、`tools/tests/release_smoke.py` | 冒烟本身不用应用私有文件、调试接收器或root。外围恢复日志只在debug前后使用。正式证书仍未生成/迁移，不能把这个本地release构建当作正式签名发布 |
| APK 14.2 MB（debug，未开 R8） | 设备上已安装的 `base.apk` | 见 §2.4 的下载时间 |

### 2.2 APK 安装能力

| 事实 | 位置 | 影响 |
|---|---|---|
| **Device Owner 静默自更新已实测**（2026-11-04）：应用UID10074提交PackageInstaller会话218872014、507508005，0.1.1→0.1.2→0.1.3，实际候选APK、进程替换、MY_PACKAGE_REPLACED自动前台、Lock Task、HOME及设置/收藏/非空插件KV保留均通过；无确认输入、无root/shell候选安装 | `artifacts/update/OTA-0/self-update/`、`self-update-callback/` | 第一次自动观察器因没有回调而等待，第二次看见回调进程刚启动即被后续pkg removed杀死。保留这两个原始失败，另在还原前核对原平台会话终态和新进程/包/数据。生产安装器必须按持久pending与实际包核对，不能将回调缺失当作重试授权 |
| 降级能力已实测（2026-11-04）：应用UID10074用隐藏`setAllowDowngrade`将debug 1003降到1002；shell UID2000的`pm install -r -d`对debug及非debuggable release都成功 | `artifacts/update/OTA-0/downgrade/`；会话799111198、374852227及`device-shell/results.json` | 全部使用同一工作树、同一本地debug签名的版本变体；非debuggable release间降级也实际核对了APK摘要/UID/新进程/自动前台。完成后恢复0.1.3和原文件，未重启。正式证书迁移、生产回滚守卫与崩溃回滚仍未做；不能仅靠`Build.TYPE`推断能力（实测应用里为user，shell属性为userdebug） |
| `BootReceiver` 已经监听 `MY_PACKAGE_REPLACED`，会重启服务和 Activity | `shell/BootReceiver.kt` | 安装后自动回来这一步已有 |
| `KioskPolicy`在既有Device Owner下设置持久HOME：清理且仅清理ThinkOS自己的旧持久规则，再注册MainActivity；多次release/debug覆盖和进程启动后仍只有一条规则 | `artifacts/update/OTA-0/release/persistent-home-read-only.json` | 常规resolve-activity与实际Home键均通过。API27的dumpsys不显示持久表，另以只读平台文件核对，未直接写平台文件 |
| `CrashRecovery` 的安全模式是粘性的，只有人工能清 | `shell/CrashRecovery.kt` | 修复版装上后要自动退出，否则修好了也还在安全模式 |
| 放弃 Device Owner 只有 debug 的 `kiosk_surrender`，release 没有入口 | `07-build-deploy.md` §4.3 | 用户装了正式版就卸载不掉，公开前要在救援菜单补上 |

### 2.3 系统模块（实测）

| 事实 | 影响 |
|---|---|
| `su` 只放行 adb shell：Magisk 的策略表里只有 uid 2000 | 应用拿不到 root，也不该拿（ADR-033：一个解析漏洞不能变成 root） |
| 设备上的模块：`busybox-ndk`、`rv4a_engine`、`timesync`、`zygisk_vector`。**`thinkos-cast` 没装**；`thinkos-immersive` 是单独的 APK，在 Vector 里已启用 | 模块 OTA 要能处理“这台设备没装” |
| `timesync` 是项目自己的（`author=takuenn`，`service.sh` 74 行），但源码不在仓库，只有 `~/ThinkSmartView/timesync-v1.0.zip` | 要走 OTA 先收进 `system/` |
| 模块装完要**重启才生效** | `system/install.sh`、`19-provisioning-for-agent.md` §5.2：模块更新就意味着一次重启，重启冷启动 60–90 秒（`02-device.md` §7） |
| `thinkos-immersive` 重装后，必须同步 Vector 数据库里的 `apk_path`，否则下次开机钩子找不到文件；它现在用调试密钥签名 | `system/install-immersive.sh`、`10-known-issues.md` §13：只有 root 能做，证书迁移要把它算上 |
| 守护进程需要的工具都在：`/system/bin/{sqlite3,pm,dpm,settings,service,reboot}`、`/sbin/resetprop`、`/data/adb/post-fs-data.d` | 不用新增依赖 |
| `magisk --install-module` 是官方入口；模块脚本 `post-fs-data` 阻塞启动、上限约 40 秒，早于 Zygote（[Magisk 文档](https://topjohnwu.github.io/Magisk/guides.html)） | 启动守卫放这里 |
| **Magisk 的防循环不能指望**：[FAQ](https://topjohnwu.github.io/Magisk/faq.html) 说连续两次没启动完就进 Safe Mode，但[更新日志](https://topjohnwu.github.io/Magisk/changes.html)查不到是哪一版加的，设备上是 27.0（2024-02）。日志能确认的是 v21 起“系统进 Safe Mode 时停用所有模块” | 要有自己的守卫 |

### 2.4 设备到各下载源的网络（实测）

2026-11-02 约 21:50（HKT），设备 shell 里的 `curl`，用设备自己的证书库校验（成功的连接 `ssl_verify_result=0`）。**只有一个晚上、一个网络的少量样本**。

| 地址 | 结果 |
|---|---|
| `github.com`、`api.github.com` | 通，约 0.6–0.7 秒 |
| `raw.githubusercontent.com` | 通，TLS 握手 0.4–6.5 秒波动，约 12–23 KB/s，支持 Range |
| `release-assets.githubusercontent.com`（Release 文件的实际地址） | **15–18 KB/s**，支持 Range；从 `github.com` 跳转过去的五次里有一次连接超时 |
| `codeload.github.com`（源码包） | 超时 |
| `*.github.io`（GitHub Pages） | **不通** |
| `cdn.jsdelivr.net` | 通，24–160 KB/s，支持 Range，带版本或提交号的地址 `immutable` |
| Cloudflare（`www`、根域名、`speed`、`1.1.1.1`）、`unpkg.com` | **不通** |
| `gitee.com` | 通，TLS 0.3 秒；小文件样本太小，判断不了速度 |
| `registry.npmmirror.com` | 通，约 130 KB/s |

2026-11-05 重测（设备上 TCP 443，各 3 次）：`github.com`、`gitee.com`、`gitcode.com`、`cnb.cool`、`asset.cnb.cool` 稳定通；`raw.githubusercontent.com`、`cdn.jsdelivr.net` 时通时不通；`gcore.jsdelivr.net`、`fastly.jsdelivr.net` 不通。只测了连通，没测 TLS 和速度。所以 `raw` 和 jsDelivr 不再作为默认源（§4.10）。

由此定下四件事：

1. **不依赖 GitHub API**：匿名只有每 IP 每小时 60 次（[文档](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)，响应头实测一致），家庭网络的出口 IP 可能和别人共用。
2. **不用 GitHub Pages 托管**：这个网络上连不通。
3. **下载必须能续传、能换源**：14.2 MB 按 15–25 KB/s 要 10–16 分钟，中途断线是常态。
4. **传输通道不可信也不可靠，所以安全不能靠它**（§3）。

`raw.githubusercontent.com` 还有未公开数值的匿名限流（[GitHub 2025-05 公告](https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/)），社区里有人报告 `Accept-Language: zh-CN` 会触发 429（[讨论](https://github.com/orgs/community/discussions/157887)，GitHub 没有确认）。所以更新客户端**不发 `Accept-Language`**。

### 2.5 插件安装

| 事实 | 位置 | 影响 |
|---|---|---|
| 正式版**只有 APK 内置一个来源**；开发目录 `scanDev` 只在 debug 下运行 | `PluginRuntime.kt` | 要新增第三个来源 |
| 文件清单、摘要、白名单、限制、暂存目录、原子发布都已具备 | `plugin/PluginFiles.kt`、`install()` | 直接复用，这是最大的利好 |
| 权限等于 manifest 声明，只有 `system.*` 限内置副本 | `Instance.permissions`、`grantedPermissions()` | 要加安装前确认 |
| **角色可以被抢**：`pages.firstOrNull { role in it.roles }`，同档按页面键字母序。代码只校验 `panel` | `DeckRouter.kt`、`publishCatalog()` | 第三方插件声明 `home` 或 `settings`，再取个靠前的 id，就能接管首页或设置页。`06-plugins.md` §3 写的“`settings` 仅限 `system.settings`”代码里没有实现 |
| 启动失败才回滚，运行中反复崩只会退避和冷却 | `PluginHealth.kt` | 自动更新要加试用期 |
| 普通页面自动进翻页序列；首页卡片写死在 `plugins/home/views/main.json`；开始屏 48 格已满；应用列表插件还没做 | `DeckRouter.navigablePages`、`06-plugins.md` §8、`33-apps-and-back.md` | 新装插件要有入口；它的卡片放不上首页 |
| 所有插件开机启动、共用一个线程 | `PluginRuntime.start()` | 冷启动 < 3 秒的预算不能被第三方插件吃掉 |
| `install()` 的复制和哈希跑在插件线程 | `scanDev`、`PluginFiles.copy` | 下载和校验要搬到 IO 线程 |
| `tos.http` 和 `MediaHttpClient` 连接超时 3 秒 | `06-plugins.md` §6.4、`net/MediaHttpClient.kt` | 比实测 TLS 握手还短，更新要用单独的客户端，目录也不能由 Lua 去取 |

## 3. 核心设计：不信任传输，只信签名（提议）

OTA 和插件仓库是同一件事的两个用户，共用一个内核：

> **设备只相信内置的公钥。** 公钥验证一份小的“目录文档”，文档里写着每个文件的 sha256。文件从哪儿下载都无所谓：GitHub、jsDelivr、局域网里的电脑、将来的 Server。

这是 [TUF](https://theupdateframework.github.io/specification/latest/) 的简化版，防的是同样的几类攻击：装上任意文件、回退到旧版本、新旧目录混用。好处有三个：换下载源不用重新考虑安全；将来的 Server 自然成为一个局域网镜像；没有任何地方需要 GitHub 账号或令牌。

### 3.1 钥匙

**系统模块进 OTA 之后，钥匙的分量变了：有一把钥匙等于所有自动更新设备的 root。** 所以四把钥匙分开放，互相不能替代。

| 钥匙 | 签什么 | 放在哪 | 泄露的后果 | 丢失的后果 |
|---|---|---|---|---|
| **Android 证书** | APK | 只在 Mac，加两份离线备份 | 配合通道钥匙才能推送伪造更新 | **无法挽回**：每台设备都要卸载重装、重设 Device Owner |
| **OTA 通道钥匙** | `ota/*.json` | 只在 Mac，加离线备份 | 只能指向某个 APK，APK 仍要过 Android 证书校验；模块仍要过系统钥匙校验 | 启用备用钥匙 |
| **系统钥匙** | 系统模块清单 | 只在 Mac，加离线备份；推荐硬件钥匙（§10） | **等于 root**：能往开着自动更新的设备推 root 代码 | 启用备用钥匙（更新器里预置） |
| **仓库钥匙** | `registry/index.json` | GitHub Actions 受保护环境的 Secret，发布要所有者批准 | 能发恶意插件，但出不了 Lua 沙箱，新增权限要在设备上确认；碰不到 APK 和模块 | 下一版 APK 换掉 |

- 算法用 **ECDSA P-256**：应用侧用平台自带的 `Signature("SHA256withECDSA")`，Mac 和 CI 上用 `openssl` 就能签，不新增依赖。项目里有 Ed25519（`cast/airplay/Curve25519.kt`），但那是为 AirPlay 自写的，**信任根不用自写的密码学代码**。
- 通道与仓库公钥资产为 `android/app/src/main/assets/update/keys.json`；APK模块验签单独读取 `android/app/src/main/assets/update/system-keys.json`。系统根另由所有者公开输出 `updater_keys.h` 编入更新器模块，应用不能替换守护进程内置根。每类均为active/backup，原字节部署与构建检查见 `tools/OWNER-RELEASE.md`；debug额外测试根不能替代缺失的正式资产。
- Android 证书、通道钥匙、系统钥匙**永不进 CI**。

### 3.2 目录文档

签名对象是**文件的原始字节**，签名放在同名 `.sig` 里。先验签再解析，不需要规范化 JSON。

所有目录文档都有：

- `seq`：单调递增。设备记住见过的最大值，更小的一律拒绝（防止回退到旧目录）。
- `expires`：过期只提示“目录可能过旧”，**不阻断**。设备没有 RTC，时间不可信时不看这个字段；否则 CI 停一个月，所有设备都装不了东西。

### 3.3 下载器 `update/Fetcher`

- 新的 OkHttp 客户端：连接 15 秒、读取 30 秒；不发 `Accept-Language`、不带 Cookie，`User-Agent: ThinkOS/<版本>`；不带任何设备标识。
- 公网只走 HTTPS，重定向不得降级；明文只允许私网（沿用 `net/LocalNetworkPolicy`），这样局域网镜像不用配证书。
- 每个文档都有上限：目录 ≤ 1 MiB、签名 ≤ 1 KiB、文件清单沿用 64 KiB、APK ≤ 64 MiB、模块 ≤ 16 MiB，都不得超过声明大小。按 `AGENTS.md`，远程数据一律有界、不信任，要有畸形输入测试。
- 候选地址按顺序试，单个地址失败或超时就换下一个；大文件按 1 MiB 分段，断点写入 `.part`，重启后续传；全部下完再比对 sha256。
- **自带一小份根证书**（GitHub、Fastly、jsDelivr 用的根和它们的下一代）。ROM 的证书库是冻结的，如果 CDN 换了根，OTA 就再也修不了自己。

2026-11-03实现进度：Fetcher/UpdateHttpClient已提供有界文档读取、私网DNS/跳转约束、请求头过滤、1MiB分段/.part续传与哈希后发布，31项主机检查通过，含真实子进程强杀后续传。UpdateTrust已接入16张固定Mozilla/curl来源的CA根证书（MPL-2.0），合并当前平台接受的信任根，继续使用平台原生信任管理器与OkHttp主机名验证；8项JVM/TLS检查及5站点仅用子集的主机TLS1.2通过。可信OTA公钥、清单到下载器的运行时调用链和API27设备验收仍缺，不能据此认为OTA已可用。证据见`artifacts/update/OTA-2/report.md`、`artifacts/update/OTA-2/roots/report.md`。

2026-11-03目录链路进度：`shared/schema/update/`已定义并生成通道目录、APK公钥和私有序号记录的Kotlin/Go契约，Go仅数据结构。UpdateKeys、UpdateCatalog、UpdateSequenceStore、UpdateCatalogClient与传输层已接通并过本地主机HTTP测试；序号按通道独立保存，同序号必须是相同原始字节，且损坏记录不重置。正式公钥仍由所有者提供，当前APK无可信OTA钥匙资产；更新状态机、目录缓存、定时和安装未接入。详见`shared/schema/update/README.md`、`artifacts/update/OTA-2/catalog/report.md`。

### 3.4 什么时候做

| 动作 | 时机 |
|---|---|
| 检查 | 时间可信（年份 ≥ 2024）且已联网后，随机延迟 10–60 分钟；之后每 24 小时 ± 2 小时；手动立即。带 `If-None-Match` |
| 下载 | 注意力 ≤ AMBIENT（沿用 `05-protocol.md` §3.4）或用户手动；视频或投屏时暂停，慢网络上不抢带宽 |
| 校验 | IO 线程、`Thread.MIN_PRIORITY`；绝不在主线程（ADR-029） |
| 安装 | §4.4 的安静时段，或用户点了安装 |
| 失败 | 静默退避 1 分钟 → 1 小时，不弹任何提示（`03-architecture.md` §3.9） |

## 4. OTA（提议）

### 4.1 更新什么

| 层 | 走 OTA？ | 说明 |
|---|---|---|
| **APK** | 是 | §4.3、§4.4 |
| **`thinkos-updater`** | 是 | 新模块，负责其它模块（§4.5）。**第一次要人工装** |
| **`thinkos-cast`、`thinkos-certs`、`thinkos-power`、`timesync`** | 是 | Magisk 模块，经更新器安装。`timesync` 先收进 `system/` |
| **`thinkos-immersive`** | 是 | Vector 模块 APK，经更新器安装并同步 Vector 数据库 |
| **官方插件**（不含 `system.*`） | 是，走插件仓库 | 改插件不用发 APK，不重启（ADR-013 的初衷） |
| **`settings`、`panel`** | 只随 APK | 它们持有 `system.*`，只给内置副本；ADR-039 P1 之后再放开 |
| **tzdata** | 只随 APK | 不单独做 |
| **Magisk、Vector、busybox、ViPER** | **否** | 第三方组件，没法替它们测；Vector 在 8.1 上本来就有已知问题（`10-known-issues.md` §13） |
| **ROM** | 否 | |

### 4.2 发布物与通道

- APK 和模块发在 `ThinkOS` 仓库的 Release，开启[不可变发布](https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/)，tag 和文件发布后不能再改。
- 通道文档放在分发仓库的 `ota` 分支（§5.1）。不能放进不可变 Release，因为它要不断更新。
- `versionCode = 主版本 × 1000000 + 次版本 × 1000 + 修订号`（0.2.0 → 2000）。
- 通道只有 `stable` 和 `beta`，不做灰度比例，设备太少，没有意义。

```jsonc
// ota/stable.json，旁边是 ota/stable.json.sig
{
  "schema": 1, "channel": "stable", "seq": 17,
  "published": 1793620000000, "expires": 1801396000000,
  "release": {
    "version": "0.2.0", "code": 2000, "api": 9,
    "device": ["starfire"], "sdk": 27,
    "sha256": "…", "size": 14195351, "signer": "<证书 SHA-256>",
    "urls": ["https://github.com/<owner>/ThinkOS/releases/download/v0.2.0/thinkos-0.2.0.apk",
             "https://cdn.jsdelivr.net/gh/<owner>/thinkos-registry@<提交号>/ota/thinkos-0.2.0.apk"],
    "modules": [{
      "id": "thinkos-cast", "kind": "magisk", "version": "v1.1", "code": 2,
      "policy": "feature",            // always：每台都装；installed：只升级已装的；feature：相关功能打开时才装
      "sha256": "…", "size": 88123,
      "urls": ["…"],
      "manifest_urls": ["…"]          // 系统钥匙签的清单和签名。应用只转交，更新器自己验
    }],
    "notes": { "zh-Hans": "…", "en": "…" },
    "source": "https://github.com/<owner>/ThinkOS/tree/v0.2.0"   // GPLv3 的对应源码
  }
}
```

jsDelivr 的 GitHub 路径单文件上限 20 MB，带提交号的地址永久缓存，分支地址缓存 12 小时（[README](https://github.com/jsdelivr/jsdelivr)）。2026-11-03已读官方[使用条款](https://www.jsdelivr.com/terms/terms-of-use)及[维护者仓库原文](https://github.com/jsdelivr/jsdelivr/blob/master/Terms%20of%20Use.md)，当前标示2026-10-05生效：§1允许符合规则的个人/商业使用，要求同时符合GitHub等来源平台条款，并说明已提供的不可变文件可在上游删除后继续保留；§4保留大小、文件数与扩展名等硬限制；§6禁止将服务作为“general-purpose file or media hosting service”、大量低复用抓取或绕过限制；§10–11允许限制服务且不保证持续可用。这些是条款原文事实，APK分发用途是否适合仍由所有者在OWN-12评估。

### 4.3 设备流程

```
检查：拉 stable.json + .sig → 验签 → seq 不倒退 → code 更大、device 和 sdk 匹配 → AVAILABLE
下载：APK 和需要的模块（可续传、可换源）→ sha256 + size → getPackageArchiveInfo 查包名、versionCode、证书 → READY
等待：自动安装开着就等安静时段；关着就等用户点（§4.4）
安装：有更新器且有模块变化 → BEGIN，逐个 STAGE（还没生效）
      → 装 APK（进程被替换，MY_PACKAGE_REPLACED 拉起新版）
      → 模块有变化就 REBOOT，一次重启让两边同时生效
试用：新版健康满 10 分钟 → HEALTHY，事务结束（§4.6）
```

只有 APK 变化时不重启。只有模块变化时不动 APK。

**同一版本的 APK 和模块必须能交叉工作**：新 APK 配旧模块、旧 APK 配新模块都不能坏，因为模块要等重启才生效，回滚也可能只退一边。应用和守护进程之间靠 `HELLO` 协商协议版本（`thinkos-cast` 已经这样做）。

### 4.4 免提示安装与“自动安装更新”（用户决定）

设置项 `update.auto_install`，文案“自动安装更新”，**默认开**。引导的“软件更新”屏会明确问一次：“继续”即自动安装，“只自动下载”即关闭（`35-onboarding.md` §4.4）。

| 开关 | 行为 |
|---|---|
| **开** | 下载好后，在安静时段自动安装 ThinkOS 和系统组件，不弹任何提示；模块有变化时重启一次 |
| **关** | 下载好后等用户点“立即安装”。点了之后同样没有系统确认框 |

**安静时段**（全部满足才装）：

- 时间可信时，本地3–6点（隐藏项`update.install_hour`）；连续3天等不到，才按`update.fallback_wait_s`/`update.fallback_asleep_s`放宽到ASLEEP≥1小时。时间不可信时不检查本地时段，也不附加这一小时条件，仍须满足下列全部共同门禁；
- ASLEEP，最近 10 分钟没有触摸；
- 没有媒体会话和投屏，设置页和引导没开，没有打开外部 App；
- 可用空间 ≥ 3 倍 APK。

时钟分支按`36-task-list.md` OTA-3第4项、OTA-5第7项；只有本地时段判断因时间不可信而豁免，ASLEEP、10分钟无触摸、无媒体/投屏、设置/引导/外部应用关闭和空间门槛全部保留。实际运行期还核对已认证READY候选、静默安装资格、失败记录和未决事务，并在IO返回、提交前复核；接口或策略源码存在不代表正式来源的完整自动安装已验收。

**能不能真的免提示，取决于设备状态**：

| 设备状态 | APK | 系统模块 |
|---|---|---|
| Device Owner + 更新器 | 静默 | 静默，重启一次 |
| 只有 Device Owner | 静默 | 更新不了，提示用电脑安装更新器 |
| 只有更新器 | 静默（root 的 `pm install`） | 静默，重启一次 |
| 都没有 | 要点系统确认框，开关旁写明原因 | 更新不了 |

所以“都没有”时 APK 也要加 `REQUEST_INSTALL_PACKAGES`。

**事后要告诉用户**：免提示不等于不声不响。装完后控制面板和设置页显示“已更新到 0.3.0”和更新说明，保留一天。同一版本连续失败 3 次就不再自动试，显示原因。

**APK 的安装步骤**：

1. 写 `update/pending.json`（`from`、`to`、`attempts=0`），把当前 `base.apk` 复制成 `update/previous.apk`（有更新器时由 `BEGIN` 复制到 root 目录）。
2. `PackageInstaller` 写入会话并提交（Device Owner 不弹框），或交给更新器。
3. 进程被系统结束；`BootReceiver` 拉起新版；`KioskPolicy.apply` 恢复 Lock Task。

**要先补的**：

- Device Owner 下调用 `addPersistentPreferredActivity`，把 ThinkOS 固定为 Home。
- 救援菜单加“退出设备管理”（`clearDeviceOwnerApp`，今天只在 debug 里），否则用户卸载不掉。
- 安装时屏幕会有一段时间没有 ThinkOS，原厂桌面可能闪现；重启要 60–90 秒。**这是时钟优先（ADR-029）唯一接受的例外**，所以自动安装只在没人看的时段。中断时长（含 dexopt）**未验证**，S0 要量。

原厂桌面不隐藏：ThinkOS 真起不来时，它是一条回到安卓设置的路。

### 4.5 系统模块：`thinkos-updater`

**核心做法：应用只是信使，验证由 root 守护进程自己做。** 这样 ADR-033 不用破例：应用里没有 `su`，一个解析漏洞（投屏协议就在解析局域网数据）拿下应用，也只能请求安装“本来就签过名”的包。

新模块 `system/thinkos-updater`，结构照 `thinkos-cast`：C 写的守护进程、`service.sh` 拉起、主机测试。

**接口**：抽象 socket `@thinkos-updater`，规则和 `wfdd` 相同（`SO_PEERCRED` + `packages.list`，只认 root 和 ThinkOS；一行 ≤ 256 字节，2 秒读完）。**文件用文件描述符传（`SCM_RIGHTS`），不传路径**，避免符号链接和“验完被换”的问题。

| 请求 | 做什么 | 回答 |
|---|---|---|
| `HELLO` | 探活，协议版本 | `OK thinkos-updater <协议>` |
| `STATUS` | 已装模块和版本、事务状态、上次结果 | `OK …` |
| `BEGIN` | 开事务，把当前 `base.apk` 和模块备份到 `/data/adb/thinkos/` | `OK` |
| `STAGE`（附清单、签名、包三个描述符） | 复制到 root 目录 → 验系统钥匙签名 → 查包的 sha256 → 查 id 白名单、`code` 不倒退 → 安装（还没生效） | `OK` / `ERR bad-signature` / `old` / `unknown-id` / `too-big` / `install-failed` |
| `INSTALL_APK`（附 APK） | 不是 Device Owner 时代装：`pm install -r`，**不带 `-d`**；证书和版本由 Android 查 | `OK` / `ERR …` |
| `REBOOT` | 重启。必须有进行中的事务，每小时最多一次 | `OK` |
| `HEALTHY` | 新版健康，结束事务，推进防回退记录 | `OK` |
| `ROLLBACK` | 回到事务前（救援菜单用） | `OK` |

没有“执行命令”这种东西：固定程序、绝对路径、固定参数（`execve`），不经过 shell。引导还会给它加三条设备配置命令（`SET_TIMEZONE`、`DEVICE_OWNER_ON`、`GRANT_LANGUAGE`），规则相同，见 `35-onboarding.md` §4.6、§4.7、§4.11。

WEL-2/MOD-2 客户端与设备配置源码（2026-11-04）：`UpdaterClient` 只连接抽象 `@thinkos-updater`，检查服务端 UID 为 root，固定请求一行不超过256字节。HELLO、STATUS、HEALTHY、REBOOT和配置请求的整次交换限2秒；BEGIN、STAGE、INSTALL_APK、ROLLBACK限190秒，覆盖守护进程180秒的安装执行上限，超时仍视为结果未明。文件只通过 `SCM_RIGHTS` 传递，响应最多4096字节。`STATUS` 的本地闭合契约为 `shared/schema/update/updater-status.json`，不是 Server 接口；可选 `setup` 能力表与正式分发公钥是否存在无关，缺字段时保守认为不能配置。安装类请求超时不自动重试，必须按新STATUS、持久意图与当前APK/boot身份核对。源码和主机检查不能替代真实模块升级、重启及完整健康期验收。

`SET_TIMEZONE <id>`、`GRANT_LANGUAGE` 仍先验证 peer，并且不受 `keys-missing` 限制。前者仅API27，ID最多64个ASCII字符，限定`[A-Za-z0-9_+/-]`；应用先查ROM实际ID及三对替代，root再独立有界读取固定ROM `tzdata` 索引（优先 `/data/misc/zoneinfo/current/tzdata`，再 `/system/usr/share/zoneinfo/tzdata`），只接受已知ID。调用固定`/system/bin/service call alarm 3 s16 <id>`后必须读回`persist.sys.timezone`；不符时恢复原值并核对，恢复失败明确报告。`GRANT_LANGUAGE`只运行固定`pm grant moe.kiwi.thinkos android.permission.CHANGE_CONFIGURATION`。应用调用这两项必须使用OS记录的真实点按5秒信用，不接受Lua自报时间。`DEVICE_OWNER_ON`因OWN-10未决定而始终返回`ERR unavailable`，不修改设备管理权限。以上代码尚不代表已经安装更新器或验证过真机能力；失败时继续使用安卓设置页。

**清单和验签**：

```
thinkos-module 1
id=thinkos-cast
kind=magisk
code=2
version=v1.1
sha256=…
size=88123
```

- 先验签再解析，解析的只有这几行已验证的文本。**守护进程不解析 zip**，只比对 sha256，解包交给 Magisk。
- P-256 验签用 [`micro-ecc`](https://github.com/kmackay/micro-ecc)（BSD-2-Clause，支持 secp256r1）加一份 SHA-256，源码按项目惯例固定提交、放进仓库（设备上没有 `openssl`）。主机测试里和 `openssl` 交叉验证，并覆盖畸形输入（`AGENTS.md` 对解析代码的要求）。
- id 白名单编译在守护进程里：`thinkos-updater`、`thinkos-cast`、`thinkos-immersive`、`thinkos-shell`、`thinkos-certs`、`thinkos-power`、`timesync`。APP-4/CERT-4已扩展契约，MOD-1实现更新器时必须包括这七项；更新器目前仍未实现，尚无可执行白名单。以后增加其它模块要先发一版更新器。第三方模块永远装不了。
- 防回退记录放在 `/data/adb/thinkos/`，应用碰不到。

**两种模块**：

| `kind` | 安装 | 生效 |
|---|---|---|
| `magisk` | `magisk --install-module` | 重启后 |
| `vector-apk` | `pm install -r`，再把 `install-immersive.sh` 的做法搬进来：备份 Vector 数据库，改 `apk_path` | 重启后（钩子在系统框架里） |

**什么时候装**：`policy` 决定。`thinkos-updater`、`timesync` 是 `always`；`thinkos-immersive` 是 `always`（下拉控制面板时系统栏不闪，人人都要）；`thinkos-cast` 是 `feature`，用户打开“用 Miracast 接收投屏”时触发原生缺组件流程。已运行的cast模块由固定root端HELLO检查；已有组件正常启用，不显示缺组件提示。缺失或库存未知时，公共设置写入在提交前被拦截，保持原开关和整批设置不变。

原生流程分两次真实触屏：先确认检查并下载，复用渠道验签、序号、APK实际身份、独立模块清单验签与白名单，准备过程只缓存认证数据和按哈希的文件，计划留在RAM，不生成普通更新的READY或安装资格；准备好后显示实际APK与全部选中的组件，再用新的触屏确认“安装并重启”。计划绑定nonce、目录、APK、组件和原库存/floor，最终重验一致才消费。关闭未提交窗口撤销计划，不能把旧UP、普通点击或辅助功能点击当批准；Lua原有三种更新动作的触摸时限不变。

最终确认才在现有`module-pending.json`事务中记录可选`feature=miracast`意图，普通事务省略；它只能是manual且selected含cast，不可在change中改写。首次BEGIN前重验认证目录并经设置schema开启、flush和读回；失败保留未完成记录而不发BEGIN。提交后的“返回”只关闭窗口，已有手工事务继续受实时媒体等门禁约束，由既有恢复链持有。无更新器、有效钥匙或来源时只显示不可自动更新/未知状态，不下载或推测未安装版本，也不把APK-only fallback当Miracast安装成功。

2026-11-05：该原生流程已随0.1.26完成本地构建与主机检查，尚未装机；不代表真实模块安装、重启和投屏验收。HUA0BWUT不安装更新器/守卫，真实持久步骤与备用机仍遵循36 §0.7与OWN-4/OWN-6/OWN-28。

**第一次怎么来**：更新器自己不能靠 OTA 装上来，这是唯一要人工的一步。装机脚本里顺手装（`system/install.sh` 或 `35-onboarding.md` 的 W3）。已有的设备运行一次安装脚本。**没有更新器时一切照常**（ADR-015）：APK 的 OTA 不受影响，设置页写明“系统组件要用电脑更新”。

**更新器更新自己**：风险最高，只走最慢的路：先 `beta`，所有者的设备上跑够时间。启动守卫（§4.6）放在模块之外的 `/data/adb/post-fs-data.d/`，文件极小、很少改，不随模块更新一起冒险。

**测试的边界**：源码、构建、主机测试归开发 Agent；真机上持久安装仍要人工或明确授权（`AGENTS.md`，ADR-037 的先例）。

### 4.6 试用期与回滚

没有 A/B 分区，安装不是原子切换，所以靠两层守卫。

**应用层 `UpdateGuard`**（没有更新器也有）：

- 放在 `ThinkApplication.onCreate` 的**第一行**，比 `CrashRecovery.install` 和加载原生库都早。每次进程启动，如果版本等于 `to`，就把 `attempts` 加一并落盘。这样 Java 崩溃和原生崩溃都能数到。
- **健康**：首帧画出、插件目录发布、连续 10 分钟没有崩溃。之后删掉 `pending.json` 和 `previous.apk`，清掉 `safe_mode`，有更新器就发 `HEALTHY`。
- **不健康**（`attempts > 3`）：用 `previous.apk` + `setAllowDowngrade` 回滚（仅 userdebug ROM，§2.2）；失败就进安全模式，安全模式里仍然检查更新；把这个 `code` 记入黑名单，不再下载。

**系统层守卫**（有更新器时）：

2026-11-04 守卫源码检查点（尚未统一验收或持久安装）：稳定恢复程序的两个固定入口为 `--guard-early`、`--guard-watch`，程序与两个启动脚本放在模块之外，具体产物路径与人工安装边界见 `system/thinkos-updater/guard/README.md`。默认15分钟健康窗口和5秒观察间隔从系统设置 schema 生成 root 私有 policy；缺失或损坏时停止自动动作。独立 guard 记录上一 boot_id、真实 boot_completed、冻结的健康窗口、早期恢复声明及一次自动恢复/重启预算。daemon 与稳定守卫共享 root 文件锁，操作前重载 journal；内部 `GUARD_ROLLBACK <32hex事务id>` 只接受内核 peer UID0，并重新验证当前事务、deadline和预算，不能由应用借普通 socket 权限触发。稳定副本可以在新版模块 daemon 不可用时执行同一受限恢复流程。此检查点不代表应用已经把系统模块事务的 HEALTHY 与自己的首帧/健康守卫接通，也没有进行真实坏模块/断电/重启演练。

| 情况 | 谁发现 | 怎么办 |
|---|---|---|
| 事务开着，上一次开机没走到 `boot_completed` | `/data/adb/post-fs-data.d/` 的脚本，每次开机最早运行 | 恢复备份的模块；`vector-apk` 先在 Vector 数据库里停用。用 `resetprop`，不用 `setprop`（文档说会死锁） |
| 开机完成，15 分钟内没收到 `HEALTHY` | 守护进程 | `pm install -r -d` 装回旧 APK，恢复模块，重启一次，记录失败 |
| 自动回滚之后还是坏的 | | 停手，不再自动操作，等人。每个事务只自动回滚一次 |

最后的路：Magisk 的音量键 Safe Mode、`adb wait-for-device shell magisk --remove-modules`（[FAQ](https://topjohnwu.github.io/Magisk/faq.html)）、原厂桌面、EDL。

回滚之后，旧版会读到新版写过的数据：同一个次版本内的数据迁移不能破坏旧格式。设置存储本来就保留未知键。

这些风险靠发布流程压低：先走 `beta`，在自己的设备上跑够时间再推 `stable`。

### 4.7 永远不能改的东西

下面任何一项改了，已部署设备就升不上去或会失去 Device Owner：

- `applicationId` `moe.kiwi.thinkos`
- 签名证书
- 组件名 `.admin.ThinkOSAdminReceiver`
- `minSdk` ≤ 27
- 不得加 `sharedUserId`

### 4.8 证书迁移（一次性，人工）

**现在只有一台设备，是最便宜的时候。**

1. 生成正式 keystore 和三把 P-256 钥匙，离线备份。
2. debug 和 release **都用正式证书签**（只在这台 Mac 上，配置放未跟踪的 `keystore.properties`；缺失时回退 debug 证书并警告）。这样 `adb install -r` 可以在两种构建之间切换，开发测试不受影响。`thinkos-immersive` 同样换证书。
3. 设备上人工操作：备份应用数据（`files/` 和 `shared_prefs/`）→ `kiosk_surrender` → 卸载 → 装新证书的 debug 包 → 还原数据 → `dpm set-device-owner` → 确认 `deviceidle` 白名单和桌面 → 跑 `install-immersive.sh`。

卸载后 uid 可能变化：`thinkos-cast` 每次连接都重新查 `packages.list`（`wfdd.c`，已核对），`thinkos-immersive` 只认包名，都不受影响。

**也可以不换**，继续用现在的 debug 证书当正式证书，省掉这一次操作。不推荐：它的口令是公开的 `android`，这台 Mac 上所有 debug 构建都用它，它的安全性全靠一个没有特别保护的文件。

### 4.9 发布流程：`tools/release.sh`（在 Mac 上跑）

```
检查工作区干净 → 版本号递增 → 单元、Lua、Go、system/test 测试 → assembleRelease → apksigner 签名并核对
→ 只含 armeabi-v7a → 构建有变化的模块并用系统钥匙签名 → 装到设备冒烟 → 生成并签名通道文档 → 这个版本的源码标签推到公开仓库 → 发 Release 和 beta 通道（附 GPLv3 全文和 FFmpeg 扩展的源码，ADR-043）
→ 设备 soak → 改 stable 通道（seq + 1）
```

CI 只跑测试，不接触钥匙。所有向外发布的步骤由所有者触发。

GPL 附件准备已实现为 `tools/archive_ffmpeg_source.sh`（LIC-04）：固定标签和子模块提交，不追踪分支，支持缓存后的离线重建；输出的 `artifacts/lic/LIC-04/release-attachments.json` 列出源码存档、SHA-256 和 GPLv3 原文，发布脚本必须逐项核对并附到每个完整版 Release。这里只准备本地材料，`release.sh`、钥匙和对外发布仍按 OTA-1 / OWN-3 / OWN-6 推进。

### 4.10 分发渠道（2026-11-05，所有者给定国内源）

渠道：GitHub release 加国内源 CNB（`https://cnb.cool/kiwimoe/ThinkOS`）。任务在 `36` 号文档 §11G REL，决定是 DEC-13，暂定。

- **布局（两个站点相同）：**版本 `v<版本>` 的附件放 APK、模块、源码包、GPL 附件；滚动版本 `ota-<通道>` 的附件放 `<通道>.json` 和 `<通道>.json.sig`，每次发布覆盖。设备取 `https://<站点>/<仓库>/…/releases/download/ota-<通道>/<通道>.json`。这个地址没有 `/ota/` 子路径，所以 `update.sources` 要支持 `{channel}` 占位（REL-1）。
- **顺序：**`update.sources` 里国内源在前，GitHub 在后。目录里 `urls` 的顺序也一样（`release.sh --url-base` 的顺序）。这台设备所在网络上，CNB 比 GitHub 快约 60 倍（见下），所以顺序是必须的，不只是习惯。
- **设备怎么自动选源（2026-11-05 已实现，`UpdateCatalogClient`、`UpdateSourceHealth`）：**
  - 检查目录：按顺序启动，上一个失败或沉默 3 秒就启动下一个；拿到第一个通过验签的目录后，其余源再有 4 秒，**取序号最高的**。落后的镜像说“没有新版”盖不住别处的新版；仍受本地序号下限约束，更旧的目录进不来。
  - 记住不通的主机：失败后按检查次数（设备没有 RTC）降到最后，接下来 1、2、4、8 次检查后恢复原位。下载同样先试能通的源，中途失败会在同一偏移处续传换源。降级的源仍会在最后尝试，从不丢弃。
  - **目录里带镜像列表（`mirrors`，已签名）：**发布时用 `release.sh prepare --catalog-url …` 写进目录。设备从最新的已验签目录（认证缓存）取这份列表，排在配置的来源之后、总共不超过 8 个。以后增加 GitHub 或别的镜像，只要在下一版目录里加一行，不用先发新 APK；列表本身不是信任依据，每个来源的响应仍要验签和过序号下限。
  - 状态只是顺序提示，放在 `files/update/source-health.json`（≤ 8 KiB，最多 16 个主机），损坏或写不了都当作没有历史。
- **为什么安全不依赖站点：**目录和模块清单有签名，APK 有证书校验，下载后核对 `sha256`（§3）。站点被劫持或落后，最坏是设备拒绝更新或晚一点更新，装不上伪造的包。
- **CNB 的下载路径：**`/-/releases/download/<tag>/<文件>` 匿名可用（样本验证），先 302 到 `asset.cnb.cool` 上有时效的地址，再返回 206；`HEAD` 直接返回长度。`Fetcher` 每段重新请求原地址，所以每段都会重新跳转。`api.cnb.cool` 匿名返回 401，设备不使用它。
- **目录更新的非原子窗口：**先传 `.json` 再传 `.sig`，中间几秒内设备可能读到新目录配旧签名，验签失败后退避或换源，无害。
- **首发目录不带模块**，模块随 MOD-2 演练通过后的版本加入。
- **设备实测（2026-11-05，`artifacts/update/REL-0/part1-20261105/`）：**生产下载器完整下载并校验别人公开的发布附件：CNB 29.9 MB，41 秒，724 kB/s；GitHub 9.7 MB，793 秒，12 kB/s。两边的证书链、302 跳转、Range 分段都通。GitHub 下载一个 10 MB 的 APK 约 13 分钟，只能当兜底。
- **没验证的**见 `36` §11G 开头。其中最重要的是我们自己的仓库发布后能否匿名下载；上传时已显式传 `ttl=0`（永久，接口说明里 0 表示永久、最大 180 天），上传后仍要在页面确认附件没有过期时间。
- **不使用：**`raw.githubusercontent.com`、jsDelivr、GitHub Pages 作为默认源。`raw` 只作为手工配置的备用。

## 5. 插件仓库（提议）

### 5.1 形态

两个仓库：

- `ThinkOS`：源码，APK 和模块的 Release。
- `thinkos-registry`：提交记录、CI，以及**设备读取的所有东西**。

```
thinkos-registry
├── main 分支（人审）
│   ├── plugins/<id>.json          作者提交：{"repo":"alice/x","path":"","owners":["alice"]}
│   └── .github/workflows/         校验 PR；定时生成目录
├── dist 分支（CI 准备 unsigned；所有者主机签名并发布）
│   ├── registry/index.json(.sig)  目录
│   ├── registry/files/<digest>.json  文件清单，和设备已有的 index.json 同一格式
│   └── blobs/ab/<sha256>          插件文件、图标、截图，内容寻址
└── ota 分支（Mac 上的 release.sh 推送）
    └── ota/stable.json(.sig)、beta.json(.sig)、thinkos-*.apk、模块包和清单
```

**为什么把作者的文件收进 `dist`，而不是让设备去作者仓库下载**：

- 作者删库或强推后，目录里的版本还在。
- 只有一个来源，设备永远不访问作者的地址（`05-protocol.md` §11 的精神不变）。
- `blobs/<sha256>` 和协议里将来的 `GET /api/v1/blobs/{sha256}` 是同一个模型，设备代码不用分两套。
- 大仓库里的插件也能走 jsDelivr（它不支持超过 150 MB 的包，而 `ThinkOS` 仓库本身就超过了）。

代价是要重新分发作者的文件，所以**许可证必须允许再分发**，每个插件只留最近 3 个版本。

**对比过的其它做法**：

- 设备直接调 GitHub API 按 topic 搜索：撞 60 次/小时的限制。把 topic 扫描放进 CI（§5.4）。
- 类似 [Obsidian](https://github.com/obsidianmd/obsidian-releases)：列表放 GitHub，设备从作者的 Release 下载文件。Release 下载在这个网络上最慢，而且设备要访问作者的地址。
- 只靠 Server 取 Git（ADR-018 原方案）：Server 还不存在，而且违背“没有 Server 也能完整使用”。这条路径在 M2 仍然保留，给私有仓库用。

### 5.2 目录

```jsonc
// registry/index.json
{
  "schema": 1, "seq": 1287, "generated": 1793620000000, "expires": 1796212000000,
  "blob_bases": ["https://cdn.jsdelivr.net/gh/<org>/thinkos-registry@<提交号>/",
                 "https://raw.githubusercontent.com/<org>/thinkos-registry/<提交号>/"],
  "revoked": [{ "id": "stocks", "digest": "c7d1…", "reason": "@revoked.malware" }],
  "plugins": [{
    "id": "stocks", "tier": "reviewed",            // official / reviewed / community
    "name": { "zh-Hans": "股票", "en": "Stocks" }, "summary": { "zh-Hans": "…" },
    "author": "alice", "repo": "alice/thinkos-stocks", "license": "MIT", "tags": ["finance"],
    "icon": "<sha256>", "screenshots": ["<sha256>"],
    "releases": [{                                 // 新的在前，设备取第一个 api ≤ 自身的
      "version": "1.2.0", "commit": "9f2c1e7…", "digest": "5be0…", "api": 8, "size": 183402,
      "permissions": ["network"], "why": { "network": { "zh-Hans": "取行情数据" } },
      "apps": ["board"]
    }]
  }]
}
```

- 文件清单里的 `digest` 由设备重新计算（`PluginFiles.parse` 已经这样做），和目录里的比对，所以清单本身不用单独签名。
- 目录地址顺序：用户配置的镜像 → jsDelivr `@dist` → raw。手动刷新时多试几个，取 `seq` 最大的。
- 目录校验后缓存在设备上，断网也能看已知内容。

按 DEC-12，CI 只校验并准备未签名目录及审核 PR/issue，不接触签名私钥。所有者用 `tools/registry_owner.py publish` 默认 plan，审阅材料和输出的 `input_sha256` 后，以相同参数加 `--reviewed-sha256 <该摘要> --execute` 一条命令签名并发布；目标使用 `--repository <org>/thinkos-registry`，公钥/私钥使用 `--keys` / `--private-key`，旧分发必须用 `--expected-dist <完整40位commit>` 精确绑定，首次才显式用互斥的 `--initial`。完整命令与凭据入口见 `registry/README.md`。

owner 命令签名前重取并验当前目录和 tag ledger 的签名及原始字节、强制 seq 递增。先造引用清单/blob 的内容 commit A，把 `blob_bases` 固定到 A，再签最终目录字节并造签名目录 commit B（parent=A），避免提交号自引用；仅以预期旧 ref 的 CAS 更新 dist 到 B，随后读回确认。报告和钥匙不入发布树；远端变更或未知结果不得盲目重试。CLI 和 CI 模板目前只在本地准备，真实远端未运行，正式钥匙/仓库仍待 OWN-6/OWN-9。

### 5.3 作者流程

1. 一个 Git 仓库或子目录，结构照 `06-plugins.md` §2；打 tag `v1.2.0`，必须等于 manifest 的 `version`（ADR-018）。
2. 商店信息放 `store.json` 和 `store/` 目录。它们不在设备白名单里，不会下发（`06-plugins.md` §2）。**这样不用改 manifest**：manifest 的 schema 是 `additionalProperties: false`，多一个字段，旧 APK 就会拒绝安装。
3. 本地跑 `tools/package_plugins.py --validate-only`，或者用提供的 GitHub Action。
4. 向 `thinkos-registry` 提 PR，新增 `plugins/<id>.json`。
5. CI 校验 + 人工审核，合并。
6. 之后作者只管打 tag，CI 每天扫描：

| 情况 | 处理 |
|---|---|
| 新 tag，权限没变 | 自动进未签名目录；最终批次按 DEC-12 由所有者主机签名发布 |
| 新 tag，**权限增加** | 开 PR 等人工审核；进目录后设备还要再确认 |
| 已发布的 tag **指向了别的提交** | 不更新，开 issue（`06-plugins.md` §12.2） |

### 5.4 CI 检查

- 复用 `06-plugins.md` §12.4 的规则：bare 克隆不检出，`ls-tree` + `cat-file` 读对象；`GIT_ALLOW_PROTOCOL=https`；钩子目录为空；拒绝符号链接、子模块、`..`、大小写冲突路径、超限文件。**不执行仓库里的任何东西。**
- 复用 `tools/package_plugins.py` 的 `validate_plugin`（同一份 `shared/schema/plugin/`）和 `luac -p`。
- 仓库专属规则：
  - id 未被占用，且不是内置插件的 id；
  - 不得声明 `system.*`；不得声明任何角色（§5.6）；
  - 有 SPDX 许可证且允许再分发；
  - 每个权限都写了 `why`。
- **CI 自身的安全（DEC-12）**：校验 PR 用只读 `pull_request`（无 Secret），不用 `pull_request_target`。`scan-tags.yml` 的 `prepare` job 自动校验 community/tag 并上传 unsigned；独立 `review` job 仅接受可信 main 的定时/手动成功生产者，以同仓库/同 run 的 artifact ID、请求/context 摘要、扫描 commit 和固定工具 commit 绑定材料，再用最小写权限运行 `tools/registry_review.py`，为精确权限增加创建/更新 PR、为 tag 移动或已发布内容改变创建/更新 issue。writer 不合并、不签名、不发布 dist，fork PR 不运行 writer；签名私钥始终在所有者主机。模板尚未启用，不能把具备模板写成已经对真实远端开 PR/发布。
- `community` 档：CI 扫描 GitHub topic `thinkos-plugin`（ADR-018），自动校验后列入**未签名目录**，**不经逐项目人工审核**，设备上默认不显示。默认根 `manifest.json` / `store.json` 配 `v<version>` tag；已有登记的 monorepo 路径走原登记流程。公开fork和归档仓库同样自动校验，不额外按这些旗标筛选；disabled仓库依实际取回/校验结果报告。topic 不赋予 official / reviewed；同 id 冲突、占用、非法来源拒绝并报告。工具 `tools/registry_discovery.py` 和 `registry_ci.py --mode community` 的分页、ETag、限流、资源边界见 `registry/README.md`。按 DEC-12，所有者仍在本机对最终批次签名、发布，CI 不持有签名私钥。

### 5.5 设备安装流程

```
hub 插件请求安装 → OS 弹原生确认框（名称、来源 repo@commit、档位、逐项权限和 why、大小）
→ 只接受屏幕上的触摸 → IO 线程取文件清单 → 校验 digest
→ 逐个下载 blob（本地已有相同 sha256 的直接复用）到暂存目录 → PluginFiles.copy 校验
→ 回到插件线程 install() 的语义校验 → activate → 写入 registry-state.json
```

- 安装位置不变：`filesDir/plugins/<id>/<version>-<digest>/`。新增 `filesDir/plugins/registry-state.json`，记录来源、提交、已确认的权限、是否自动更新、上一版摘要。
- 同 id 同时存在内置版和仓库版（官方插件）：版本高的生效，一样高用内置。
- **更新**：
  - 权限没增加：按设置自动或手动；权限增加：必须再确认。
  - 不打断正在显示的页面，等它离开屏幕再切换；媒体会话的所有者也等。
- **试用期**：更新后 10 分钟内重启、冷却或原生不洁退出（`PluginHealth`、`NativeCrashTracker`）→ 回到上一版，这个摘要不再自动安装。
- **吊销**：目录的 `revoked` 命中已安装的摘要 → 停用并显示原因。这是一个后门式的权力，只针对具体摘要，写进公开的提交记录。
- **卸载**：设置值保留（ADR-017）。

### 5.6 开放前必须先补的

| 缺口 | 要做 |
|---|---|
| 角色可被抢（§2.5） | 仓库来源的插件不得持有任何角色，CI 和设备都查；顺带补上 `settings` 角色的校验。ADR-039 P1 的绑定表之后，由用户选择再放开 |
| 权限等于声明 | 安装前原生确认，新增权限再确认。全有或全无，不做逐项开关 |
| 页面自动进翻页序列 | 仓库来源的页面一律按应用页（ADR-040）处理，不进翻页序列，从插件中心的“已安装”打开 |
| 启动成本 | 仓库来源的插件在**首帧之后**分批启动；上限 16 个（提议）；冷启动 < 3 秒的验收不变 |
| 安装跑在插件线程 | 下载和哈希放 IO 线程，只有 `activate` 回插件线程 |
| 恢复 | `restoreFactory` 目前只清开发目录，要同时停用仓库插件；安全模式忽略仓库插件。救援菜单依旧不依赖任何插件 |

**第一版的已知限制**：第三方卡片放不上首页和磁贴页。`home` 的卡片是写死在视图里的，开始屏 48 格已满，“添加到首页”是另一件事（ADR-039 绑定表、M4 的入口），第一版卡片只能出现在插件自己的页面里。

### 5.7 设备上的界面与接口

- **应用 `hub`（插件中心）**：内置 Lua 插件，应用页，分三个标签：发现、已安装、更新。只用现有词汇（`tabs`、`list`、`text`、`image`），**不加新组件**（ADR-039）。系统更新放在同一个应用里，设置页和控制面板链接过来。
- **恢复不依赖它**：原生救援菜单加“检查并安装系统更新”。插件全坏了也能升级。

| 接口 | 权限 | 说明 |
|---|---|---|
| `tos.update.status()`、事件 `update` | `system.update` | 状态、当前版本、新版本、说明、进度、错误、系统组件状态 |
| `tos.update.check()`、`download()`、`install()` | `system.update` | 只能在用户点按后 5 秒内调用（同 `tos.settings.open`） |
| `tos.plugins.catalog{query, tag, offset, limit}` | `system.plugins` | 挂起；每次 ≤ 50 条，不把整个目录塞进 256 KiB 的插件状态 |
| `tos.plugins.installed()`、`open(id)`、事件 `plugins` | `system.plugins` | |
| `tos.plugins.install(id)`、`update(id)`、`uninstall(id)`、`setAuto(id, bool)` | `system.plugins` | 安装和新增权限时由 OS 弹确认框 |

2026-11-04实现范围：API10已提供`tos.update.status/open/check/download`及受权限过滤的`update`事件；`open()`是原生恢复页入口。设置页“软件更新”通过这个公开接口打开同一页，救援菜单“检查更新”直接打开，不依赖插件。状态schema见shared/schema/update/status.json，不输出路径/URL/信任材料。检查/下载返回受理结果，实际完成通过状态/事件观察；UI与Lua都用同一只读投影和控制器。`install()`、安装/健康/回滚仍缺，所以当前`can_install=false`且没有安装按钮，不能把恢复界面可打开当作修复版可安装。

两个权限和其它 `system.*` 一样，先只给内置副本。ADR-039 P1 之后改为设备上确认，插件中心本身也就可以替换。

目录和文件的获取都在 OS 里，因为 `tos.http` 有 3 秒超时和 1 MiB 上限，而且验签不能交给插件。

### 5.8 系统设置（走 `system.json`，ADR-017）

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `update.enabled` | bool | 开 | 自动检查和下载；关掉后只能手动 |
| `update.auto_install` | bool | **开** | **自动安装更新**（§4.4） |
| `update.channel` | choice | `stable` | `stable` / `beta` |
| `update.mirror` | text | 空 | 自定义镜像地址，优先于内置地址（高级） |
| `plugins.auto_update` | choice | `official` | `off` / `official` / `all`（权限没变时才自动） |
| `plugins.community` | bool | 关 | 显示未审核的插件 |
| `update.check_hours` | number，隐藏 | 24 | |
| `update.install_hour` | number，隐藏 | 3 | 安静时段的开始，持续 3 小时 |

运行期适配（2026-11-04）：`UpdateRuntime` 由 `RuntimeHost` 启动，异步读取 APK 公钥；缺少钥匙时状态为未配置，不创建更新目录、不发自动请求，不影响本地功能。当前未部署正式钥匙，也未确定公开仓库地址，所以 `update.sources` 默认为空；它是隐藏的分发候选根地址列表（逗号/空白分隔，连同自定义镜像最多8个），不是 Server 接口。`update.mirror` 优先；地址以 `.json` 结尾时直接读目录，否则在根地址后加 `ota/<通道>.json`；签名仍从相同地址加 `.sig` 读取。坏镜像允许回退，验签与防回退规则不变。

以下隐藏项也在同一 `system.json`，没有新建偏好文件：`update.initial_min_s=600`、`initial_max_s=3600`、`jitter_hours=2`、`retry_min_s=60`、`retry_max_s=3600`、`manual_grace_s=5`、`trusted_year=2024`、`connect_s=15`、`read_s=30`。首次与周期检查用单调时钟、随机延迟和有界退避；自动检查等待可信时间与连接，已认证的缓存下载不依赖墙钟。只监听系统默认网络，不要求 Google 的联网验证；局域网镜像可独立使用。控制器在入队和真正执行前都核对入口提供的点按时间，最多5秒；实际UI/tos.update点按与system.update权限适配尚未接入；设置关闭只取消自动工作，通道/来源变化先取消并等待旧传输结束，再重建会话。

下载环境由真实 MediaEngine 主线程回调、注意力状态和 Activity 前台状态提供：播放中或暂停的视频、投屏所有者/镜像会话都会暂停下载；外部应用前台也保守暂停。打开投屏接收开关本身不算投屏会话。没有 IO 线程读取播放器快照，也没有轮询播放器。手动可以绕过注意力，不能绕过这些媒体/前台限制。`update.auto_install` 默认值已入设置声明，但安装器还未实现，所以暂时隐藏；不把保存一个开关当成已具备静默安装。插件中心hub仍待接入；API10与原生恢复页已实现上述检查/下载范围，安装尚缺。

### 5.9 Server 来了之后（M2）

- Server 自然变成局域网镜像：`GET /api/v1/blobs/{sha256}` 和这里同一个模型；通过 `update.mirror` 或服务发现接入。
- 期望状态的 `apk` 字段走同一条安装流程。
- **要改一条**：`05-protocol.md` §3.2 说 `plugins` 是“应当安装的全集，不在列表中的就卸载”，这会删掉用户从仓库装的插件。改为 Server 只管理自己下发的那些。

## 6. 失败模式

| 故障 | 表现 | 恢复 |
|---|---|---|
| GitHub 或 jsDelivr 不通 | 无变化，已装的一切照常 | 换源；退避重试 |
| 目录被篡改、被回退、签名不对 | 拒绝，继续用缓存的目录 | 换源 |
| 文件哈希不对 | 丢弃这个文件，换源 | 连续失败就放弃这一轮 |
| 下载到一半断电 | 无影响 | 从 `.part` 续传 |
| 安装失败（空间不足、证书不符） | 继续运行旧版，状态显示原因 | 下次重试；证书不符不再重试 |
| 新版 APK 反复崩溃 | 3 次后回滚 | §4.6 |
| 新模块让系统起不来 | 下次开机守卫恢复备份 | §4.6 |
| 没有更新器 | APK 照常更新，模块不更新；相关功能不出现（ADR-015），设置页写明 | 人工装更新器 |
| 应用被攻破，乱发命令 | 装不了未签名内容；重启受限速；能做的最坏事情是触发回滚 | 下次更新恢复 |
| 时间不对（刚开机） | HTTPS 失败，不检查 | 时间可信后再试 |
| CDN 换了根证书 | 系统证书库不认 | 自带的根证书；否则自定义镜像或 adb |
| 第三方插件更新后崩溃 | 回到上一版 | §5.5 |
| 恶意插件 | 被沙箱和权限限制 | 吊销；救援菜单恢复出厂 |
| 仓库钥匙泄露 | 能发恶意插件，碰不到 APK 和模块 | 发新 APK 换钥匙 |
| **系统钥匙泄露** | 攻击者还要能改通道文档或骗过下载，才能把包送上设备；一旦送上就是 root | 发新更新器换钥匙；公开说明；受影响设备重新装机 |
| GitHub 账号被盗 | OTA 和模块过不了验签（钥匙都不在 GitHub）；插件目录可以被改，后果同“仓库钥匙泄露” | 收回账号，发新 APK 换仓库钥匙。这正是这些钥匙不进 CI 的原因 |

## 7. 要修订的现有结论

下表记录修订前的问题与目标口径，不是当前设备状态快照。逐行文档/代码/证据核对见`artifacts/update/OTA-4/table-review-20261105.md`；文档改正不等于正式OTA、Server对齐或模块部署已验收。

| 位置 | 修订前的说法 | 修订后的口径 |
|---|---|---|
| ADR-015 | APK 和插件由 Server 下发 | 设备也可以直接从公开镜像取；Server 是可选的局域网镜像和管理者 |
| ADR-018 第 2 条、`05-protocol.md` §11 | 设备不访问任何插件地址 | 设备仍不碰 Git，也不碰作者仓库，但读取签名目录和内容寻址文件。Server 取 Git 的路径保留 |
| ADR-033、`system/README.md` | 持久安装模块由人工做 | **第一次**安装更新器由人工做；之后由签名的 OTA 经更新器完成。应用没有 `su` 不变 |
| `03-architecture.md` §4.3 | 不做静默安装 | 默认静默安装（`update.auto_install`），只在安静时段；可关 |
| `05-protocol.md` §3.2 | `plugins` 是全集 | §5.9 |
| ADR-035、`29-panel-and-apps.md` §1 | 不做应用商店 | 不做**安卓 App** 商店；插件中心要做 |
| `06-plugins.md` §6.3 | 权限表 | 加 `system.update`、`system.plugins` |
| `07-build-deploy.md` §8、`19-provisioning-for-agent.md` | 发布和新机 | 正式签名、`release.sh`、新机装 release 或正式证书的 debug 包，并装更新器 |
| `AGENTS.md` | 没有这方面规则 | 等方案确认后加：钥匙不进 CI 和仓库；不用 `tos.http` 取目录；模块只经更新器、只收白名单 id |

`AGENTS.md` 的“设备端不要引入 Git / JGit”“应用里不要加 `su`”“持久安装由人工完成（对开发 Agent）”**都不变**。

## 8. 分阶段（含引导）

大小是相对的，不是日历。每阶段另加 `AGENTS.md` 的五条验收。测性能前先告诉用户。

| 阶段 | 内容 | 大小 | 验收 |
|---|---|---|---|
| **S0 验证** | 不入库的原型，和正在用设备的人错开：① Device Owner 静默自更新、重启回来、Lock Task、默认桌面；② 降级（`setAllowDowngrade` 和 `pm install -d`）；③ 中断时长（含 dexopt、重启）；④ release 包能不能正常跑；⑤ 一周内不同时段的下载速度；⑥ 守护进程里调 `magisk --install-module`、改 Vector 数据库；⑦ 故意做坏模块看守卫（**有变砖风险，要所有者同意，最好用第二台设备**） | S | 结论写回本文；①不成立就要重新设计安装方式 |
| **O1 发布基础** | 正式证书和钥匙、版本号规则、`release.sh`、持久默认桌面、数据版本号、退出设备管理、`release` 冒烟脚本；一次性迁移（人工） | S–M | 正式包在设备上跑满 `AGENTS.md` 五条；debug 和 release 能互相覆盖安装 |
| **O2 下载与验签** | `update/` 包：签名文档、`Fetcher`、调度、状态机；`tools/tests/ota_device.py` 配本地测试台（`adb reverse`） | M | 见下 |
| **O3 免提示安装** | `PackageInstaller`、`UpdateGuard`、回滚、安全模式联动；安静时段；“自动安装更新”和其它设置项；救援菜单入口；`tos.update.*` | M | 见下 |
| **M1 更新器** | `system/thinkos-updater`：守护进程、验签、主机测试；`timesync` 收进 `system/`；模块构建和签名进 `release.sh`；人工装到设备 | M | 见下 |
| **M2 模块 OTA** | 事务、启动守卫、一次重启、Vector 路径同步、`policy`、APK 与模块的交叉兼容 | M | 见下 |
| **R1 仓库与 CI** | `thinkos-registry`、校验、签名、`dist`；官方插件作为第一批内容 | M | 恶意样本全部被拒（§5.4）；tag 被强推不更新；权限增加开 PR |
| **R2 设备安装** | 第三来源、确认框、§5.6 全部缺口、`tos.plugins.*`、`hub` 应用 | L | 见下 |
| **W1、W2 引导** | `welcome` 插件、触发规则、手势练习（`35-onboarding.md`） | M | 见 35 号文档 |
| **P 公开** | ADR-043 清单、公开仓库、作者文档、`stable` 通道；W3 面向普通人的装机指南和脚本 | M，主要是所有者的决定 | 外部账号从零走通：装机、引导、OTA、提交插件 |
| **R4 社区** | topic 扫描、`community` 档、吊销演练 | S | |

**顺序**：

1. S0 → O1 → O2 → O3：先把 APK 的 OTA 做通。有了它，之后任何问题都能推修复。
2. M1 → M2：系统模块接上。回滚能力也在这里变强。
3. R1 → R2 和 W1 → W2 可以并行，互不依赖。R2 依赖 O2 的内核；W1 要写 `update.auto_install`，系统组件那一步要读更新器状态。
4. 最后 P：引导和插件仓库都是给新用户的，公开之前才真正有人用。

**O2 + O3 的设备验收**：

1. 断网、无 Server：设备行为和没有这个功能时一样。
2. 下载中拔网、杀进程、断电：恢复后续传，不重下；全程 `clock_accuracy_device.py` 不回归（ADR-029）。
3. 坏签名、旧 `seq`、错哈希、超大响应、错设备、低版本：全部拒绝，设备不受影响。
4. 视频和投屏中不下载、不安装。
5. **开着“自动安装更新”：不碰设备，在安静时段自动装完**，没有任何弹窗；关着：有提示，一点就装，也没有系统确认框。
6. 静默安装后自动回来：Device Owner、Lock Task、默认桌面（按 Home 验证）、设置、插件状态、收藏都还在；控制面板显示“已更新”。
7. 新版启动即崩：3 次后回到旧版，这个版本不再下载。
8. 在安全模式下能检查并安装修复版，之后自动退出安全模式。
9. 冷启动 < 3 秒（断网、无 Server）不回归。

**M1 + M2 的验收**：

1. 主机测试：坏签名、别的钥匙、旧 `code`、不在白名单的 id、超大文件、畸形清单，全部拒绝；验签结果和 `openssl` 一致。
2. 模拟被攻破的应用，对 socket 发任意内容：装不了未签名的东西；别的 uid 得到 `ERR denied`；重启被限速。
3. `thinkos-cast` 升级：自动、一次重启、Miracast 正常（`cast_miracast_device.py`）。
4. `thinkos-immersive` 升级：钩子仍加载（`immersive_device.py` 6/6）。
5. 打开 Miracast 而模块没装：提示后自动装好，重启后可用。
6. 坏模块让系统起不来：下一次开机恢复备份。新 APK 一直不健康：15 分钟后回滚。
7. 新 APK + 旧模块、旧 APK + 新模块都能工作。
8. 没有更新器：APK 的 OTA 照常，设置页写明情况。
9. 更新器更新自己成功；新版本的更新器能继续更新别的模块。

**R1 + R2 的设备验收**（改自 `09-roadmap.md` M2）：

1. 目录里装一个插件：确认框显示来源和权限；不重启应用；断网、关 Server 后照常运行。
2. 新版增加权限：确认前不生效。
3. 新版启动即报错：保留旧版。运行中反复崩溃：10 分钟内回到上一版。
4. 第三方插件声明 `home` 或 `settings`：被拒绝。
5. 装满上限后冷启动 < 3 秒、时钟度量不回归。
6. 吊销：已安装的被停用并显示原因。救援菜单“恢复出厂插件”能清除全部仓库插件。

## 9. 风险

| 风险 | 对策 |
|---|---|
| **系统钥匙等于 root** | 只在 Mac、永不进 CI、推荐硬件钥匙；和通道钥匙分开；用户可以关自动安装 |
| **证书或钥匙丢失** | 两份离线备份。证书丢了没有补救 |
| 默认自动安装，一个坏版本同时打到所有设备 | `beta` 先行；试用期和两层回滚；同一版本失败 3 次停手 |
| 新版把设备变砖（`Application` 起不来、系统起不来） | 守卫在最早位置；Safe Mode、adb、原厂桌面、EDL 是最后的路 |
| 只有一台设备，测坏了就停工 | 第二台设备（§10）；EDL 恢复材料都在本地 |
| 无 RTC 与安静时段 | 时间不可信时仅免本地时段判断，仍需ASLEEP及§4.4全部共同门禁；可信时间连续3天未装的兜底才使用`update.fallback_asleep_s`（默认1小时），按36号文档优先级统一 |
| 网络（jsDelivr、raw 都可能变差） | 多源、续传、自定义镜像；S0 先量一周 |
| 第三方插件窃取数据 | 沙箱、确认框、审核、吊销。**不能完全防**：有 `network` 和 `location` 的插件确实能把位置发出去，确认框要说清楚 |
| 作者账号被盗后自动更新 | 第三方默认不自动更新（`plugins.auto_update = official`） |
| 个人维护的审核负担 | 校验尽量自动化；生态小的时候全靠人工也够 |
| 分发责任 | 仓库里有侵权内容时要能下架：`revoked` 加公开的处理流程；对外负责的是所有者本人 |
| GPLv3 的“安装信息”要求（第 6 条） | 锁定证书不妨碍用户卸载后装自己编译的版本，但文档要写清怎么做；是否够用，发布前由所有者确认 |

## 10. 要所有者定的事

已定：系统模块走 OTA；APK 免提示安装；“自动安装更新”默认开（可关，引导里会问）。

1. **系统钥匙怎么保管？** 它等于 root。*推荐：硬件钥匙（比如 YubiKey，支持 P-256）加一把离线备份；不想买就用强口令加密的文件，放在不联网的介质上。*
2. **要不要第二台设备做破坏性测试？** 故意做坏模块、测重启循环，可能要进 EDL 恢复。*推荐：要，二手机器不贵；只有一台时这项测试要你在场。*
3. **什么时候公开，怎么公开？** 公开发布完整版 APK 就要同时公开全部源码（GPLv3，ADR-043），不能只发 APK。*推荐：先用局域网镜像跑通 OTA，不公开；上线时**新建一个干净历史的公开仓库**，不带 `artifacts/` 和个人邮箱，现在这个仓库留作私有存档。*
4. **现在换正式证书吗？** *推荐：现在换（§4.8），这是唯一便宜的时候；操作要你亲手做，设备会短暂失去 Device Owner。*
5. **插件审核到什么程度？** *推荐：第一批全部人工审核；未审核的 `community` 档等生态起来再开，默认隐藏。*
6. **第三方插件的许可证政策？** *推荐：不强制 GPL，只要求允许再分发。`tos.*` 和运行时是项目自己的 MIT 代码，作者写插件用不到任何 GPL 代码（ADR-043）；但插件跑在 GPLv3 的完整版 APK 里，“这不构成衍生作品”这句话由你决定是否声明，最好问律师。*
7. **放在哪个账号下？** *推荐：建一个 GitHub 组织放 `thinkos-registry`，以后可以交给别人一起维护。*

## 11. 参考

- GitHub：[REST API 限流](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)、[匿名请求限流公告](https://github.blog/changelog/2025-05-08-updated-rate-limits-for-unauthenticated-requests/)、[不可变发布](https://github.blog/changelog/2025-10-28-immutable-releases-are-now-generally-available/)
- Android：[APK 签名方案 v3](https://source.android.com/docs/security/features/apksigning/v3)；AOSP `oreo-mr1-release` 的 `PackageInstallerSession.java`、`PackageManagerService.java`、`DevicePolicyManager.java`
- Magisk：[开发者指南](https://topjohnwu.github.io/Magisk/guides.html)、[命令行工具](https://topjohnwu.github.io/Magisk/tools.html)、[FAQ](https://topjohnwu.github.io/Magisk/faq.html)、[更新日志](https://topjohnwu.github.io/Magisk/changes.html)
- 更新安全：[The Update Framework 规范](https://theupdateframework.github.io/specification/latest/)
- 验签库：[micro-ecc](https://github.com/kmackay/micro-ecc)
- jsDelivr：[README](https://github.com/jsdelivr/jsdelivr)（限制和缓存）、[使用条款](https://www.jsdelivr.com/terms/terms-of-use)与[维护者原文](https://github.com/jsdelivr/jsdelivr/blob/master/Terms%20of%20Use.md)（2026-11-03已读，见§4.2；用途适用性留OWN-12）
- 同类做法：[Obsidian 的插件列表](https://github.com/obsidianmd/obsidian-releases)
