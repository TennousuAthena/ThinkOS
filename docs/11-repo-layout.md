# 11 · 仓库架构（Monorepo）

> 本仓库同时承载 **设备端 OS（Android）**、**Server 端管理服务（Go）**、**插件**，以及它们共享的**契约**。
> 目标：一个仓库、两条构建链路、一份契约。设备可以脱离 Server 独立构建、独立运行（ADR-015）。

## 1. 顶层目录

```
ThinkOS/
├── README.md                 # 项目入口（给人看）
├── AGENTS.md                 # 给开发 Agent 的规则（给 AI 看）
├── docs/                     # 设计文档（本目录）
│   └── archive/              # 历史企划版本
│
├── android/                  # 【产物 1】设备端 OS（APK）
│   ├── settings.gradle.kts  build.gradle.kts  gradle.properties
│   ├── gradle/wrapper/       # wrapper（由首个开发者生成）
│   ├── gradlew / gradlew.bat
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── cpp/                      # Lua 5.4 源码 + JNI 桥（NDK，armeabi-v7a）
│           ├── assets/plugins/           # 构建时生成：自带插件目录 + index.json（不提交）
│           ├── java/moe/kiwi/thinkos/…   # 包名 moe.kiwi.thinkos
│           └── res/…
│
├── server/                   # 【产物 2】Server 端下发与管理服务（Go，可选）
│   ├── go.mod
│   ├── config.example.toml
│   ├── cmd/thinkos-server/main.go
│   ├── internal/
│   │   ├── config/           # 读 config.toml（含各设备期望配置）
│   │   ├── protocol/         # 消息模型（由 shared/ 生成）
│   │   ├── gateway/          # HTTP 路由
│   │   ├── discovery/        # mDNS
│   │   ├── devices/          # 设备登记、期望状态、实际状态
│   │   ├── registry/         # 插件来源：Git 取回、校验、锁定 commit、更新检查、topic 搜索
│   │   ├── content/          # 照片集裁切与下发
│   │   ├── live/             # 实时通道
│   │   ├── relay/            # 通知 / 远程指令入口
│   │   ├── services/         # Server 端服务（tos.server.call 的目标）
│   │   ├── voice/            # [可选] 语音识别 sidecar
│   │   ├── observe/          # 指标、日志、崩溃
│   │   └── version/
│   └── testdata/
│
├── plugins/                  # 所有功能都在这里：设备插件源码（声明式视图 + Lua）
│   ├── home/                 # 纯声明：首页双栈
│   ├── clock/
│   ├── weather/
│   ├── gallery/
│   ├── music/
│   └── notify/
│
├── shared/                   # 【契约】
│   ├── protocol/             # 设备 ↔ Server 通讯
│   │   ├── VERSION           # 协议版本（整数）
│   │   └── schema/*.json     # 期望状态、回报、实时消息
│   ├── schema/plugin/        # 插件：manifest、视图、版式槽位（设备解析 + Server 校验）
│   ├── schema/settings/      # 设置：字段格式、系统级设置清单（设备 + Server 共用）
│   └── gen/                  # 代码生成脚本（Python 写的构建工具）
│       ├── gen_kotlin.py
│       └── gen_go.py
│
├── assets/
│   └── lottie/               # 动效源文件（.json + 预览 mp4）
│
└── tools/                    # 开发脚本
    ├── deploy.sh             # 构建并安装 APK
    ├── plugin-push.sh        # 把插件目录经 run-as 推到设备热加载（无需 Server）
    ├── dev.sh                # 起 Server + 装 APK + 看日志
    ├── bench.sh              # 帧率/内存采样
    └── screenshot.sh         # 截屏到 /tmp
```

## 2. 为什么这样切

| 决定 | 理由 |
|---|---|
| Monorepo | 协议、插件格式、设备端、服务端必须同步演进；分仓会导致契约漂移 |
| `shared/` 作为唯一事实源 | 通讯协议和插件格式都由 schema 定义，两端数据类由脚本生成 |
| `android/` 与 `server/` 平级 | 两条独立构建链路；**设备端不依赖 server 目录就能构建和运行** |
| `plugins/` 独立 | 自带插件的源码：APK 构建时复制进包内；同时它们也是普通插件，本仓库的 `plugins/<id>` 可以作为 Git 来源（带 `path`）安装。第三方插件在各自的 Git 仓库里（ADR-018） |
| `tools/` 独立 | 部署/调试脚本同时服务两端 |

## 3. 两条构建链路

### 设备端

```bash
cd android
./gradlew assembleDebug          # 同时编译 Lua/JNI 并把 plugins/ 下的自带插件复制进 assets
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（仅 armeabi-v7a）。环境要求见 `07-build-deploy.md`。

### 服务端（可选）

```bash
cd server
go run ./cmd/thinkos-server --config config.toml
go build -o thinkos-server ./cmd/thinkos-server   # 单文件二进制
```

## 4. 契约同步流程

```
改 shared/protocol/schema/*.json、shared/schema/plugin/*.json 或 shared/schema/settings/*.json
        │
        ├─ python3 shared/gen/gen_kotlin.py  → android/.../net/Protocol.kt、plugin/PluginSchema.kt
        └─ python3 shared/gen/gen_go.py      → server/internal/protocol/*.go
```

规则：
- **手写的数据类视为 bug**，一律从 schema 生成
- 生成文件头部带 `GENERATED - DO NOT EDIT`
- CI（未来）校验生成结果与仓库一致

## 5. 运行时拓扑

```
ThinkOS（设备，自主运行）
   ├─ 插件（Lua）──直连──> 天气 API、Navidrome 等外部服务
   │
   │  同步 HTTP：GET 期望状态 / 制品，POST 回报        （可选）
   │  实时 WS：nudge、通知、远程指令、服务调用、语音    （可选）
   ▼
thinkos-server（Server）
   ├─ devices / registry / content  下发与管理（registry：从 Git 取插件）
   ├─ relay / live                  手机通知与远程指令中转
   └─ services / voice              可选增强
```

## 6. 配置分层

| 层 | 位置 | 内容 | 谁改 |
|---|---|---|---|
| 1 默认值 | APK 内置（系统级来自 `shared/schema/settings/system.json`，页面级来自各插件 manifest 的 `settings`） | 系统设置默认值、deck、角色 | 开发者 / 插件作者 |
| 2 下发配置 | `server/config.toml` → 期望状态 → 设备 `filesDir/settings/server.json` | 覆盖默认值（设置文档 + 部署配置） | 用户（在 Server 上） |
| 3 设备上的修改 | 设置页（长按进入）/ 救援菜单 → 设备 `filesDir/settings/local.json` | 任何系统级、页面级设置项 | 用户（在设备上），回报给 Server |
| 插件来源 | `server/config.toml` 的 `[[plugins]]` + `server/data/plugins.lock.json` | 插件的 Git 地址、tag / 分支、锁定的 commit、已确认的权限 | 用户（在 Server 上） |
| 插件元信息 | `plugins/<id>/manifest.json` | 页面、卡片、权限、配置项 | 插件作者 |

## 7. 目录命名约定

- Go：文件/包名 `snake_case`；Kotlin：`PascalCase` 类名
- 文档：`kebab-case`
- Kotlin 包名：`moe.kiwi.thinkos.<layer>`，层为 `shell / service / core / settings / plugin / ui / anim / media / vision / sync / net / voice`
- 插件 id：小写 + `-`，例如 `weather`、`photo-frame`；Lua 文件 `snake_case.lua`

## 8. 版本与发布

| 产物 | 版本来源 | 命名 |
|---|---|---|
| APK | `android/app/build.gradle.kts` 的 `versionCode/versionName` | `ThinkOS-0.1.3.apk` |
| 运行时 API | APK 内常量 `PLUGIN_API`（整数） | 插件 manifest `api` 不能高于它 |
| 插件 | manifest 的 `version`（Git 来源必须等于 tag 去掉前缀 `v`） | git tag `v<version>`，安装时锁定为 commit SHA |
| 服务端 | `server/internal/version/version.go` 常量 | 标签 `server-v0.1.3` |
| 协议 | `shared/protocol/VERSION` | 整数，回报与握手互验 |

**兼容规则**：协议版本不一致时，设备停止同步但继续本地运行，维护页提示“需要更新”。

## 9. 建议的 .gitignore 补充

```gitignore
# Android
android/.gradle/
android/app/build/
android/app/.cxx/
android/app/src/main/assets/plugins/
android/local.properties
android/.idea/

# Server (Go)
server/thinkos-server
server/config.toml
# git 缓存、plugins.lock.json、blob 库
server/data/
server/logs/

assets/lottie/*.mp4
```

## 10. 首个开发者的初始化步骤

1. 补齐 Android SDK（platforms、build-tools、cmake）与 JDK 17/21（见 `07-build-deploy.md` §1）
2. `cd android && gradle wrapper`（生成 wrapper，版本与 AGP 匹配）
3. `tools/deploy.sh` 构建并安装 APK —— 此时设备已可独立运行
4. （可选）`cd server && go mod tidy`；复制 `config.example.toml` → `config.toml`；`tools/dev.sh`
