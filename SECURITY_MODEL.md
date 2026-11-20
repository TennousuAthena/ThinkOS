# ThinkOS 威胁模型

> 依据仓库代码与 `AGENTS.md`、`docs/06`、`docs/28`、`docs/34`、`system/README.md` 整理，供安全扫描使用。
> 标注「用户确认」的条目来自所有者回答；其余是从代码推断，供所有者修订。

## 1. 系统概述与信任边界

ThinkOS 是运行在已 root 的 Lenovo ThinkSmart View（Android 8.1 / API 27 / armv7，SELinux permissive）上的 Kiosk 主应用（HOME launcher，可为 Device Owner），外加 `system/` 下的 Magisk / Vector 模块。设备必须脱离 Server 独立运行；`server/` 目前只有生成的协议包，没有入口程序，未部署。

信任边界：
- **局域网 ↔ 设备**：投屏接收（DLNA / AirPlay / Miracast）在同网段无认证开放（`CastHost.kt`：只服务私网对端，并校验推送地址）。
- **公网 ↔ 设备**：插件 `tos.http`、图片加载、OTA / 插件仓库目录下载（jsDelivr、raw、国内镜像），传输不可信，只信签名（`docs/34` §3）。
- **插件代码 ↔ OS**：插件是任意 Lua 代码，运行在沙箱里（无 `io/os/package/debug`，不在主线程），只能用 manifest 声明且用户确认过的 `tos.*` 权限；`system.*` 权限只授予 APK 内摘要匹配的出厂副本。
- **应用（普通 uid）↔ root 守护进程**：应用不 `su`；`thinkos-updater`（`updaterd.c`）和 `thinkos-cast`（`wfdd.c`）经 abstract unix socket 接受窄协议请求，设备上的任何应用都能连到 abstract socket，守护进程用 `SO_PEERCRED` + `packages.list` 校验 uid。
- **开发机（Mac）↔ 发布物**：签名私钥只在所有者 Mac 上（`tools/OWNER-RELEASE.md`），不进仓库 / CI / 日志；`tools/registry.py` 在主机上处理社区插件的不可信 Git 仓库。

## 2. 组件

| 组件 | 代码 | 说明 |
|---|---|---|
| Cast | `android/app/src/main/java/moe/kiwi/thinkos/cast/**`、`android/app/src/main/cpp/playfair/` | DLNA HTTP/SOAP/SSDP/事件回调、AirPlay RTSP/plist/配对/镜像解密、Miracast RTSP/RTP |
| Plugin Runtime | `android/.../plugin/**`、`android/.../net/**`、`android/app/src/main/cpp/{lua,json,tos_bridge.c}`、`plugins/**` | Lua 沙箱、`tos.*` 权限、插件文件、公开数据、插件 HTTP 与 DNS 策略 |
| Update | `android/.../update/**`、`android/.../plugin/Registry*.kt`、`shared/schema/**` | OTA 与插件仓库目录的验签、下载、防回退、安装 |
| System Daemons | `system/**`（`thinkos-updater`、`thinkos-cast`、`thinkos-shell`、`thinkos-power`、`thinkos-immersive`、`thinkos-certs`、`timesync`） | root 守护进程与开机脚本 |
| Kiosk Shell | `android/.../shell/**`、`admin/**`、`service/**`、`MainActivity.kt`、`AndroidManifest.xml`、`ui/**`、`media/**` | 导出组件、Device Owner、崩溃恢复、媒体播放 |
| Host Tools | `tools/**`、`registry/**`、`server/**` | 发布、签名、插件仓库构建（处理不可信 Git 仓库）、Server 协议包 |

## 3. 入口与可达者

| 入口 | 可达者 |
|---|---|
| DLNA HTTP（`HttpServer.kt`）、SSDP（UDP 1900 组播）、AirPlay RTSP（`AirPlayServer.kt`）及镜像动态端口、Miracast RTSP/RTP | 同一局域网内任何主机，无认证 |
| 插件 Lua 代码与 manifest、视图声明 | 插件作者（自带插件和自写插件都出自所有者；用户确认不装第三方插件） |
| `tos.http` 响应、图片、媒体流、IPTV 列表、天气等公网数据 | 公网服务器 / 网络中间人 |
| OTA / 插件仓库目录与包 | 下载源与网络中间人（需能伪造签名才有影响） |
| abstract socket `thinkos-updater`、wfdd 控制 socket | 设备上任何本地应用 |
| 导出的 `MainActivity`、`BootReceiver`、`ThinkOSAdminReceiver` | 设备上任何本地应用（可装应用很少：Kiosk、无 GMS） |
| `tools/registry.py fetch/build` | 社区插件仓库作者（在所有者 Mac 上运行） |

## 4. 资产

- 设备 root 与系统完整性：系统签名钥匙「等于 root」（`docs/34` §9）；`thinkos-updater` 的验签、白名单与防回退。
- 签名私钥与口令（系统钥匙、通道钥匙、APK 证书）：只在 Mac，不进仓库。
- **用户隐私（用户确认：最重要的资产）**：位置、摄像头 / 存在感应、传感器数据；另有本地媒体库、投屏内容。
- 可用性：时钟优先（ADR-029），桌面不冻屏、不崩溃循环；设备没有电源键。
- 插件权限模型：`system.settings`、`system.device`、`system.plugins`、`system.update`、`system.apps` 只给出厂副本。

## 5. 攻击者画像

1. **同局域网的未认证主机**（用户确认：在范围内）：所有者不知道用户会把设备放在什么网络里（家庭、办公室、酒店、公共 Wi-Fi 都可能），所以同网段任何主机都按不可信对待，可直接打投屏接收端口。
2. **网络中间人 / 恶意下载源**：篡改 OTA、插件目录、公网数据（`tos.http` 响应、图片、媒体流）。
3. **设备上的其它本地应用**：连 root 守护进程的 abstract socket 或导出组件。
4. **恶意第三方插件作者**（用户确认：基本不在范围）：所有者只使用自带插件和自己写的插件，不装社区 / 第三方插件。只有当攻击者自己写并安装恶意插件时才成立的问题，按此降级；但插件处理的外部数据（网络响应、投屏内容）仍不可信，经由自带插件被利用的问题不降级。
5. **向所有者提交插件的社区作者**（推断）：针对主机侧 `tools/registry.py`；所有者目前不装第三方插件，此路径影响有限，但代码仍在扫描范围。

## 6. 现有控制

- 投屏只服务私网对端，推送地址校验（`CastHost.kt`）；网络数据有界、解析代码有畸形输入测试（项目不变量）。
- Lua 沙箱裁剪标准库；`require` 只加载包内文件；预算限制（`docs/06` §6.4）。
- 插件 HTTP：公网只允许 HTTPS、明文 HTTP 仅私网、每次重定向重新校验、禁止 HTTPS 降级、图片 ≤ 8MiB（`docs/06` §4.1.1；`net/*Policy.kt`、`PluginDnsResolver.kt`）。
- `system.*` 权限按 APK 摘要授予出厂副本；敏感操作要 5 秒内的真实点按信用；安装与权限增加由 OS 原生确认。
- OTA：「不信任传输，只信签名」；钥匙编入 APK / 更新器；防回退序号；试用期与回滚（`docs/34` §3–4）。
- `updaterd`：`SO_PEERCRED` + `packages.list` 校验调用者 uid，只接受只读 FD、固定七个模块 id，拒绝任意命令、路径和下载来的公钥。
- 第三方插件默认不自动更新（`plugins.auto_update = official`）。
- `tools/registry.py`：只按 Git 对象读取，不检出、不运行作者脚本 / 钩子，仅 HTTPS，空钩子目录，限时限量。

## 7. 范围外 / 接受的风险

- `artifacts/`、二进制产物不扫描（用户确认：重点扫代码）；`docs/` 是记录，不是代码。
- 公开的测试钥匙、夹具不是发现。
- `docs/34` §9 已接受：持有 `network` + `location` 且经用户确认的插件确实能把位置发出去（确认框要说清楚）。
- 物理接触设备（EDL、adb）不在范围（推断，待确认）。

## 8. 开放问题

1. 物理接触设备（EDL、adb）是否在范围？（默认：不在。）
2. 基础设施：用户确认没有单独的基础设施仓库，Server 未部署；部署后需补充 Server 侧威胁。
