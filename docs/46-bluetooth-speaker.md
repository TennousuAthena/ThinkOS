# 46 · 蓝牙音箱：手机连上设备，用它的扬声器播放

> 2026-11-04。所有者：“这个小机器打开蓝牙之后，可以当蓝牙音箱用。手机连上它的蓝牙，就能把它当作蓝牙音频设备来播放。”ADR-049，任务是 `36` 号文档的 BT-*。
> 2026-11-05状态：BT-0静态资源模块已构建，7项主机测试及常规套件（7项BT+原18项wfdd）通过，资源APK manifest/resources已核对为API27且无代码/so。ZIP SHA-256为`ff77ddc5ede6915f8776853c182e38cf4b896c02d02e71109ba2a1cc1f129b98`，真实产物/日志见`artifacts/bt/BT-0/builds.json`。原Bluetooth.apk两项默认资源已只读确认为false。**未安装、未重启，蓝牙全程没有打开**；本模块实际加载、配对、声音及BT-0整体均未通过。人工步骤见`artifacts/bt/BT-0/human-steps.md`。

## 0. 一页纸

- **能做，而且不用自己实现蓝牙协议。**这台设备的蓝牙栈里已经编进了接收端（A2DP sink）和 AVRCP 控制端的代码，只是配置默认关着。做法：用一个资源叠加层把两个开关打开，再在 ThinkOS 里做开关、配对、状态和音频优先级。
- **真正的风险不是“出不出声”，而是三件事：**
  1. **配对对话框在锁定任务模式下可能弹不出来**，配对会无声无息地超时（§2.2）。
  2. 叠加层能不能在安卓 8.1 上这样装（§2.1）。
  3. 手机的声音与 ThinkOS 自己的媒体、投屏、通话、睡眠模式的优先级（§2.3）。
- **所以先做一个有时限的可行性实验（BT-0），通过了再做 OS 部分。**

## 1. 设备事实（2026-11-04，只读）

| 项 | 内容 |
|---|---|
| 芯片与栈 | 蓝牙芯片 `qcom.bluetooth.soc=naples_uart`（QCA9379 的 UART 接口）；栈 `/system/lib/hw/bluetooth.default.so`（1.9 MB）；厂商库 `/vendor/lib/libbt-vendor.so`；应用 `/system/app/Bluetooth/Bluetooth.apk`；`/system/etc/bluetooth/bt_stack.conf` 里只有日志等级 |
| 当前状态 | **关闭**：`dumpsys bluetooth_manager`：`enabled: false`，“Bluetooth never enabled!”；名字 “Lenovo StarView” |
| 栈里有接收端 | `bluetooth.default.so` 的字符串里有 `BTIF_AV_SINK_CONFIG_REQ_EVT`、`btif_a2dp_sink_*`（包括 `btif_a2dp_sink_set_audio_track_gain`）、`bt_av_sink_callbacks`、`AVRC_Ctrl_*` |
| JNI 与应用里有 | `libbluetooth_jni.so`：`BluetoothA2dpSinkServiceJni`、`BluetoothAvrcpControllerJni`；`Bluetooth.apk` 的 dex：`A2dpSinkService`、`A2dpSinkStateMachine`、`A2dpSinkStreamHandler`、`AvrcpControllerService`；`resources.arsc` 里有 `profile_supported_a2dp_sink`、`profile_supported_avrcp_controller` 两个键 |
| 默认关 | AOSP `android-8.1.0_r81` 的 `res/values/config.xml`：`profile_supported_a2dp` = true，**`profile_supported_a2dp_sink` = false，`profile_supported_avrcp_controller` = false**；`Config.java` 用这些布尔值决定 `A2dpSinkService`、`AvrcpControllerService` 是否启用。2026-11-05已只读核对本机APK的两个默认bool也均为false；SHA为`7568679597d73871be7f5fb526938080ab0e67afcccffd02baf13c7459d36767`，不再仅按AOSP默认推断 |
| 音频路径 | `A2dpSinkStreamHandler`（8.1）：开始播放时请求音频焦点；临时失去焦点且允许压低时，按 `a2dp_sink_duck_percent`（默认 25）在原生层压低增益；永久失去焦点就放弃。声音由原生层的 AudioTrack 输出，所以会走系统的音乐流和现有扬声器通路（**推断自符号名，没验证**） |
| 叠加层管理 | `cmd overlay list` 可用，列出了 `android` 目标和 Lineage 的几个叠加层，说明系统有叠加层管理 |
| 配对界面 | 8.1 的 Settings：`BluetoothPairingRequest` 在屏幕亮着且允许前台显示时直接启动配对对话框的 Activity，否则发状态栏通知 |

## 2. 方案

### 2.1 打开接收端：资源叠加层（不自己写蓝牙）

- 做一个目标为 `com.android.bluetooth` 的叠加层，把 `profile_supported_a2dp_sink` 和 `profile_supported_avrcp_controller` 改成 true，打包成 Magisk 模块 `thinkos-btsink`（系统层，ADR-033）。持久安装和重启由人工。仓库里已有同类构建的先例（`system/build-immersive.sh` 用 aapt2、签名、不用 Gradle）。
- 2026-11-05已有只读事实与候选源码：本机`/system/vendor -> /vendor`，OMS已有从`/vendor/overlay`加载且启用的static层；AOSP8.1按该目录可信扫描，并为static层默认启用。模块用Magisk的`system/vendor/overlay/ThinkOSBluetoothSink.apk`映射，`android:isStatic=true`，只改两项资源；API27/arm与原APK SHA均失败关闭。构建/主机测试入口及官方依据见`system/thinkos-btsink/README.md`。
- **仍没验证：**新层在该ROM的实际扫描/idmap/目标运行期资源、重启后的A2DP sink及配对/声音。必须人工安装并重启后验证，不能以已有另一个static层代表本模块成功。动态`cmd overlay enable`和Vector钩子仅作未实施备选；本实现没有用它们绕过静态加载失败。

### 2.2 配对（最大风险）

- 8.1 的配对请求由 Settings 处理：启动对话框 Activity，或发状态栏通知。**在锁定任务模式下，别的应用的 Activity 可能起不来；通知栏又被 ADR-046 屏蔽。结果是配对可能无声无息地超时，没验证。**
- **方案：**ThinkOS 自己接管配对界面：在“配对窗口”内用 OS 自己的界面显示手机名（以及 6 位码，如果是数字比较），让用户点确认。
  - `BluetoothDevice.setPairingConfirmation` 在 8.1 需要 `BLUETOOTH_PRIVILEGED`（签名或特权），应用没有，所以确认动作要由系统层代做：模块里的 root 守护进程，或者 Vector 钩子。两条都要在 BT-0 里验证。
- **策略（提议）：**
  - 默认关闭。
  - 用户打开“蓝牙音箱”页，才进入 120 秒的“可被发现、可配对”窗口；窗口外不可被发现。
  - 最多保存 4 个已配对的手机，可以忘记。
  - 已配对的手机可以自己重连，不需要窗口。

### 2.3 OS 部分：`BluetoothSpeaker`

- **开关与名字：**`bluetooth.enabled`（默认关）；`bluetooth.name`（默认带蓝牙地址后四位，避免一排同名）。
- **状态：**关、开、可配对、已连接（手机名）。
- **音量：**手机的音量键经 AVRCP 绝对音量调我们的音乐流？和 ThinkOS 音量滑块的关系？**没验证。**
- **元数据：**AVRCP 控制端能不能给出曲名、歌手，在 8.1 里怎么对应用暴露，**没验证**；首版不做“正在播放”。
- **音频优先级：**手机开始播放就请求音频焦点，ThinkOS 自己的媒体应当暂停。**`MediaEngine` 设置了音频属性（`USAGE_MEDIA`），但是否处理音频焦点丢失，我没在代码里读到，要核对。**投屏、通话、蓝牙同时存在时的规则，建议统一成一个“音频所有者”：后开始的赢，`media.owner`（`tos.state.get("media.owner")`）已有这个概念，具体在 CALL 和 CAST 的设计里一起定。
- **与模式联动（`45`）：**睡眠中不可被发现、不接受新连接，已连接的静音并通过 AVRCP 暂停；红移不受影响。

### 2.4 界面（ADR-044，不加说明文字）

- 设置新增一级分类“蓝牙”（图标 `bluetooth`）：开关、名称、已配对列表（可忘记）、“允许配对”（开 120 秒）。
- 控制面板：一个蓝牙开关（图标 `bluetooth`）。
- 首版不做磁贴，也不做“正在播放”。

## 3. 阶段与任务

| 任务 | 内容 | 谁 |
|---|---|---|
| **BT-0 可行性实验** | 做叠加层模块；人工装好并重启；打开蓝牙，`dumpsys bluetooth_manager` 里有 A2DP sink；手机配对并播放，声音从扬声器出来；断开、重连；验证配对界面在锁定任务下的表现和系统层代做确认的办法 | Agent 做模块和验证；**装和重启人工** |
| **BT-1** | `BluetoothSpeaker` 服务、设置、控制面板开关、配对窗口和 OS 自己的配对界面 | Agent |
| **BT-2** | 音频优先级（媒体、投屏、通话）和睡眠模式联动 | Agent |
| **BT-3 真机验证** | 安卓手机、iPhone、Windows 电脑；连续 8 小时；Wi-Fi 大流量时的表现（设备现在连 5 GHz，蓝牙与 2.4 GHz 共存没测）；断开重连；重启后重连 | Agent，手机由所有者提供 |

**门槛：**BT-0 不通过（配对做不了，或叠加层装不上），整个方案停下来，把原因写回本文，不往下做。

## 4. 风险

| 风险 | 缓解 |
|---|---|
| 配对对话框在锁定任务下弹不出来 | OS 自己的配对界面，确认由系统层代做；BT-0 先验证 |
| 叠加层在 8.1 上装不上 | Vector 钩子备选；仍不行就只能改 ROM（人工，超出本方案） |
| 接收端只解码 SBC，iPhone 偏好 AAC，音质或兼容性受影响 | **AOSP 8.1 的接收端是否只支持 SBC，我没核实**，BT-3 实测 |
| 同时多个音源抢音频 | 统一的“音频所有者”规则 |
| 蓝牙和 Wi-Fi 共用 QCA9379，同时大流量可能互相干扰 | BT-3 实测 |
| 默认可被发现带来的安全问题 | 默认关；只有配对窗口内可被发现；限制已配对数量 |
| 手机来电走 HFP | 不在范围内；只做音频播放（A2DP） |

## 5. 要所有者定的事（`36` 号文档 OWN-27）

1. **配对策略：**窗口 120 秒、最多 4 个手机、窗口内要不要在屏幕上显示数字比较（提议：显示，手机名加 6 位码）。
2. **多个手机同时连：**提议同时只保持一个，空闲时后连的替换；正在播放时拒绝新连接。
3. **装模块和重启的授权**（和 Miracast 的模块同一次重启，见 `36` 的 CAST-1 和 OWN-28）。

## 6. 没验证的

| 项 | 怎么验 |
|---|---|
| 设备上两个 `profile_supported_*` 的实际取值 | 原APK默认值已只读确认均false；应用overlay后的有效值/支持档案仍须BT-0人工安装重启后验证，当前不开蓝牙 |
| 叠加层的加载目录、静态还是动态、是否要重启 | API27官方源及本机已知层确认vendor静态方案；模块构建、7项BT主机及常规7+18项通过，产物资源only/API27；本模块真实加载/idmap仍待人工安装重启，不算BT-0通过 |
| 配对界面在锁定任务下的表现；`setPairingConfirmation` 由系统层代做的办法 | BT-0 |
| 声音是否走现有扬声器通路；音量与 AVRCP 绝对音量的关系 | BT-0 |
| 接收端的编码（只有 SBC？AAC？） | BT-3 |
| `MediaEngine` 是否处理音频焦点丢失 | BT-2 前读代码 |
| AVRCP 控制端的元数据在 8.1 里怎么暴露 | 首版不做，以后评估 |
| 蓝牙与 Wi-Fi 共存；连续 8 小时稳定性 | BT-3 |
| 蓝牙类别（Class of Device）是否显示成扬声器，要不要设 | BT-0 |
