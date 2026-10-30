# tools/

开发与运维脚本（bash）。所有脚本都应能在 Mac 上直接跑。

| 脚本 | 作用 |
|---|---|
| `deploy.sh` | 构建并安装 APK（不需要 Server） |
| `plugin-push.sh` | 把一个插件目录经 `run-as` 推到设备的开发目录热加载（仅 debug 构建，见 `docs/07-build-deploy.md` §3.1）。按文件清单写入隐藏暂存目录，完成后发布整个目录 |
| `dev.sh`（M2 计划） | 起服务端 + 装 APK + 跟随日志 |
| `bench.sh` | 采样帧率与内存（`dumpsys gfxinfo` / `meminfo`） |
| `screenshot.sh` | 截屏到 `/tmp/thinkos-<时间>.png` |

## 约定

- 统一使用 `set -euo pipefail`
- 设备序列号默认 `HUA0BWUT`，可用 `$ANDROID_SERIAL` 覆盖
- 脚本不修改设备系统设置（那是人工操作）
- 需要 root 的命令一律不放进脚本

## 示例：screenshot.sh

```bash
#!/usr/bin/env bash
set -euo pipefail
out="/tmp/thinkos-$(date +%H%M%S).png"
adb exec-out screencap -p > "$out"
echo "$out"
```


## 已实现（M0）

- `deploy.sh`：构建（macOS 自动选择本机 JDK 17/21）、安装、设置 HOME、启动。`--no-build` 使用已有 APK。
- `package_plugins.py`：按共享 schema 校验，生成可复现的插件目录与 `index.json`（ADR-018）；文件清单摘要的规范见 `docs/06-plugins.md` §2。`--help` 查看输出与单插件选项。
- `plugin-push.sh plugins/clock`：通过 `run-as` 原子安装开发插件；`--remove clock` 恢复内置版本。
- `screenshot.sh [输出文件]`：截图。
- `bench.sh [秒数=600] [输出目录=/tmp/thinkos-bench]`：记录 gfxinfo、进程 CPU、内存、PID 和日志。CPU 使用 `/proc/<pid>/stat` 累计时间与设备 `AT_CLKTCK` 采样，输出 `cpu.json`（100% 为单核满载）。

设备默认 `HUA0BWUT`，可用 `ANDROID_SERIAL` 覆盖。真机验收记录见 `../docs/12-m0-validation.md`。脚本不使用 root、不改系统分区。

受控 AMBIENT 性能测试可在 debug 构建中发送 `hold_attention`：

```bash
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command hold_attention --es value AMBIENT
tools/bench.sh 600 /tmp/thinkos-bench
# 测试后清除固定档位，恢复真实传感器/触摸策略：
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command hold_attention
```
