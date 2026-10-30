# plugins/

ThinkOS 的全部功能。**插件优先**：时钟、天气、相册、音乐、通知、首页都是插件，每个整屏页面都由插件声明（ADR-013）。插件**运行在设备上**：由声明式视图（JSON）和可选的 Lua 脚本组成（ADR-015/016）。这个目录放**自带插件**的源码：APK 构建时把它们复制进包内。其它插件各自是一个 Git 仓库（ADR-018），由 Server 取回并下发，设备热加载；页面的外观不开放额外接口，想要不一样的页面就写一个页面插件（ADR-017；ADR-025 仅开放 tile 底色）。

## 插件结构

```
plugins/<id>/
├── manifest.json
├── views/           # 页面 / 卡片的声明式视图
├── main.lua         # 可选
└── assets/
```

## 自带插件

| id | 提供 |
|---|---|
| `home` | 首页双栈小组件（纯声明，无进程） |
| `clock` | 时间卡片 + 全屏时钟页面（多种表盘） |
| `weather` | 天气卡片 + 详情页 |
| `gallery` | 默认 Bing 每日一图，可选本地/混合；API 3 平移轮播、预览和离线缓存，无需 Server |
| `tiles` | API 4 纯声明磁贴页面，引用时钟、天气与相册的 tile |
| `aero` | API 5 纯声明原生 Aero 桌面，引用 clock.desktop、weather.now、gallery.preview |
| `settings` | 原生设置页 |
| `music` | 待实现 |
| `notify` | 待实现 |

## 写插件

- manifest、声明式视图与绑定、`tos.*` API、完整 Lua 示例：见 `../docs/06-plugins.md`
- 只能使用设备内置的页面版式、卡片模板与组件（`../docs/06-plugins.md` §4.1）
- 自带插件和第三方插件走同一套 `tos.*` 接口
- 没有 Server 时可用 `tools/plugin-push.sh plugins/<id>` 直接推到设备调试
- 页面级设置在 manifest 的 `settings` 里声明，OS 自动生成设置页（长按页面进入），见 `../docs/06-plugins.md` §11

## 发布与安装（Git）

- 一个插件 = 仓库里的一个目录（默认根目录有 `manifest.json`；多插件仓库用子目录，如本仓库的 `plugins/clock`）
- 版本 = git tag（`v1.2.0`），`manifest.json` 的 `version` 必须一致
- 给仓库加上 topic **`thinkos-plugin`**，别人就能在 GitHub / GitLab / Gitea / Forgejo 上搜到
- 安装在 Server 上完成：`thinkos-server plugin add <仓库地址> [--tag vX.Y.Z] [--path 子目录]`，安装前会列出权限，确认后才安装；设备装好后离线也能运行
- 详见 `../docs/06-plugins.md` §12

相册 API、送图方式与验收记录见 `docs/06-plugins.md` §4.1.1 / §6.6 和 `docs/21-gallery-handoff.md`。
