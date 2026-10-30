# server/

ThinkOS 的 Server 端**下发与管理服务**（**Go**，可选）。设备是自主运行的 OS，没有 Server 也能完整使用（ADR-015）。

Server 负责：设备登记与配对、下发（插件文件、配置、照片集、APK 更新）、中转（手机通知、远程指令）、可选增强（语音识别、Server 上的音乐库）、观测（指标、日志、崩溃）。插件本身运行在设备上。

## 为什么是 Go

- 负载是 IO 密集型（WebSocket / HTTP / JSON / 读文件），Go 的 goroutine 模型最省心
- 单文件二进制部署，`go build` 产出可直接运行
- 本机已装 go1.27.3，无需额外工具链
- 插件是设备上的 Lua，与服务端语言无关

## 结构

```
server/
├── go.mod
├── config.example.toml
├── cmd/
│   └── thinkos-server/
│       └── main.go            # 入口
├── internal/
│   ├── config/                # TOML 配置
│   ├── protocol/              # 消息模型（由 shared/gen 生成）
│   ├── gateway/               # HTTP 路由（默认 :8765）
│   ├── discovery/             # mDNS
│   ├── devices/               # 设备登记、期望状态、实际状态
│   ├── registry/              # 插件来源：Git 取回、校验、锁定 commit、更新检查、topic 搜索
│   ├── content/               # 照片集裁切与下发
│   ├── live/                  # 实时通道（可选推送）
│   ├── relay/                 # 通知 / 远程指令入口
│   ├── services/              # Server 端服务（tos.server.call 的目标）
│   ├── voice/                 # [可选] 语音识别 sidecar
│   └── observe/               # 指标、日志、崩溃
└── testdata/
```

## 运行

```bash
cd server
cp config.example.toml config.toml   # 填 token、插件目录、照片目录、各设备配置
go run ./cmd/thinkos-server --config config.toml
```

构建：

```bash
go build -o thinkos-server ./cmd/thinkos-server
```

## 约束

- 只监听局域网；不对公网暴露
- 单设备优先，多设备作为 V1
- **不能成为设备的单点**：Server 停机时设备功能不受影响；所有接口幂等，设备可以随时重试
- 协议见 `../docs/05-protocol.md`，字段以 `../shared/protocol/schema/` 为准，生成到 `internal/protocol/`
- Server 端服务插件（可选）沿用子进程 + stdio JSON-RPC，只提供数据，不提供界面
- **插件通过 Git 安装**（ADR-018，`../docs/06-plugins.md` §12）：Server 所在主机需要有 `git`；`config.toml` 里用 `[[plugins]]` 声明来源（`url` + `tag` / `branch`，或本地 `dir`），取回结果锁定在 `data/plugins.lock.json`。取回只读 git 对象，不检出、不执行仓库里的任何内容，传输协议限定 `https` / `http` / `ssh`
- 私有仓库沿用本机 git 的凭据与代理，Server 不保存仓库凭据
