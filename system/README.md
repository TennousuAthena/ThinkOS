# system/ — ThinkOS 的系统层

ThinkOS 不只是一个安卓应用，也是一套对安卓的整体改动（ADR-033）。需要系统权限、应用自己拿不到的能力，放在这里，做成 Magisk 模块。应用一侧只通过一个很窄的接口和它说话，**没有模块时应用照常工作**（ADR-015）。

| 模块 | 作用 | 谁用 |
|---|---|---|
| `thinkos-cast` | root 守护进程：代应用向 wpa_supplicant 设置 Wi-Fi Display（Miracast）Sink 信息，让手机和电脑能把设备当作无线显示器发现 | `android/.../cast/miracast/` |

## thinkos-cast

```
thinkos-cast/
  module.prop       Magisk 模块说明（id thinkos-cast）
  customize.sh      安装检查：只接受 32 位 ARM、API 27+，设权限
  service.sh        开机后拉起守护进程，退出后 3 秒重启
  sepolicy.rule     一条规则：wpa_supplicant 回复守护进程（permissive 的设备上只记日志，enforcing 下会被拒）
  src/wfdd.c        守护进程
  META-INF/         Magisk 模块包的标准安装器
build.sh            构建（armv7 模块包；--host 出本机测试版）
install.sh          构建并装到设备（人工执行）
test/               对着假的 wpa_supplicant 的主机测试
```

### 为什么需要它

手机和 Windows 靠 Wi-Fi Direct 探测响应里的 **WFD 信息元素**发现无线显示器。这个元素只能由 wpa_supplicant 带出去；应用层的 `WifiP2pManager.setWFDInfo()` 需要 signature 级的 `CONFIGURE_WIFI_DISPLAY`，ThinkOS 的 APK 没有平台签名，这台设备上的 `su` 也只放行 adb shell（uid 2000）。所以由一个 root 守护进程代发命令。

### 守护进程说什么、听谁的

在抽象 socket `@thinkos-wfd` 上收一行一条的文本命令（最长 256 字节，整行 2 秒内必须到齐，一次一个连接）：

| 请求 | 做什么 | 回答 |
|---|---|---|
| `HELLO` | 探活 | `OK thinkos-wfdd 1` |
| `SINK_ON <控制端口> <设备名>` | `SET wifi_display 1`、`WFD_SUBELEM_SET 0 <主 Sink，会话可用，端口，50 Mbps>`、`SET device_name`、`P2P_EXT_LISTEN 500 2500` | `OK`，或 `ERR bad-port` / `bad-name` / `wpa-down` / `wpa-silent` / `wpa-refused` |
| `SINK_OFF` | 撤销以上，并移除 P2P 组 | `OK` |
| `STATUS` | 当前状态 | `OK sink=on port=7236 wpa=up wfd=000600111c440032 name=…` |

只接受 root 与 ThinkOS 应用（`SO_PEERCRED` 查 uid，包名从 `/data/system/packages.list` 查，每次连接重新查）；别人得到 `ERR denied`，什么也不会发给 wpa_supplicant。名字只收 1–32 字节的可见字符，不含 `"`、`\`、`` ` ``、`$`。不是通用的 wpa 代理：没有任何办法让它发别的命令。

### 构建、测试、安装

```bash
system/build.sh                       # system/build/thinkos-cast-v1.0.zip
system/test/run.sh                    # 18 项，对着假的 wpa_supplicant
system/install.sh                     # 装到设备（人工）；装完重启
```

开发时不想装，可以不持久地跑同一份程序（重启后消失）：

```bash
adb push system/build/wfdd-armv7 /data/local/tmp/wfdd-test
adb shell 'su -c "nohup /data/local/tmp/wfdd-test --listen @thinkos-wfd >/dev/null 2>&1 &"'
adb shell 'su -c "kill $(pidof wfdd-test)"'     # 停（会先撤销 Sink）
```

日志在 logcat，标签 `ThinkOS-wfdd`。

### 已知限制

- SELinux enforcing 下没有验证（设备是 permissive）。
- 只在 Lineage 15.1（API 27）、Magisk 27、`wpa_supplicant` 的控制 socket 在 `/data/misc/wifi/sockets/p2p0` 的设备上试过。路径不同时用 `--wpa-socket`。
- 守护进程不处理 P2P 连接请求：确认框由 Android 的 Wi-Fi Direct 栈弹出。
