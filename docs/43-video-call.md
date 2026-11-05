# 43 · 设备到设备的音视频通话

> 2026-11-04。所有者的决定，按时间顺序，后面的取代前面的：① 推翻 `01-vision.md` 的“明确不做：视频通话”，要做；② 要点对点、不要重新造轮子，一度要求不自建任何服务器；③ **可以提供一个服务器，但只用来帮大家打洞（会合），不转发音视频；也可以考虑 EasyTier 一类的现成方案。** ④ **再后来：问为什么是 MQTT；提出只借 EasyTier 的打洞和连接层、不走 VPN，必要时改 EasyTier（§6.3）。**跨网用 STUN，STUN 要做成列表，不能只依赖 Google。本文记录决定、调研（含设备实测）、方案、要所有者定的事和没验证的。ADR 是 ADR-047，任务是 `36` 号文档的 CALL-0 到 CALL-5。 **完整实施方案在 `44-call-plan.md`。** **⑤ 现阶段只做局域网内的互相发现、呼叫、通话、视频，跨局域网暂缓：本文 §4 到 §6 的跨网部分留作以后。**
> 状态：只做了调研，以及在设备上发了少量 STUN / TCP / UDP 探测包；代码没动，库没选，服务器没建。开发和测试由开发 Agent 做，本文只管决策和文档。

## 1. 决定与它的代价

| 所有者的决定 | 直接后果 |
|---|---|
| 做通话（推翻“不做视频通话”） | 要新 ADR（ADR-047），改 `01` 和 `03` 里的非目标；要处理摄像头、麦克风的隐私 |
| 媒体点对点，不经过我们的服务器 | 音视频走 WebRTC 的端到端加密连接 |
| **可以提供一个只帮打洞的服务器** | 它只做会合（交换地址、在线状态、响铃），可以顺带做 STUN；**不转发音视频**。所以仍然没有 TURN 兜底，除非所有者改主意（OWN-20）。这个服务是项目第一个必须放在公网上的服务（§6） |
| 跨网用 STUN；STUN 做成列表，不依赖 Google | 内置一份足够大的列表，设备端做健康检查，列表可经签名目录更新（§5） |

三件要先说清楚的事：

1. **STUN 只告诉设备“你在公网上看起来是哪个地址和端口”，它不能把这个地址交给对方。**交换地址、响铃、在线状态由“打洞辅助服务器”承担（§6）。没有它，就只能靠别人的公共设施或人工。
2. **没有 TURN，就没有兜底。**两端 NAT 都不友好时打不通（§4）。要不要让那台服务器在打洞失败时兜底转发，是 OWN-20。
3. **局域网内通话不需要 STUN，也不需要会合渠道**（用本机地址直连）。所以先做局域网通话（CALL-2），既符合 ADR-015，也能先把摄像头、编码、回声这些硬件问题解决掉。

## 2. 设备与库的实测（HUA0BWUT，2026-11-04，只读）

| 项 | 结果 |
|---|---|
| 摄像头 | 1 个前置。`dumpsys media.camera`：HAL 3.3 的 `android.info.supportedHardwareLevel` 是 **3**（LEVEL_3）；HAL 1.0 的 API1 也可用，朝向 270°。“legacy” 是提供者的名字，不是硬件级别。WebRTC 的 `Camera2Enumerator.isSupported` 只在有相机为 LEGACY 时返回 false（branch-heads/7871 源码），所以预计可走 Camera2。**还没在应用里用 `CameraCharacteristics` 复核，也没采过帧** |
| 硬件编码器 | `OMX.qcom.video.encoder.avc`、`.hevc`、`.vp8`；解码器有 avc、hevc、vp8、vp9。WebRTC 的 H.264 硬件编码判定（同一份源码）：型号不在黑名单（`SAMSUNG-SGH-I337`、`Nexus 7`、`Nexus 4`）且编码器名以 `OMX.qcom.` 开头，本机满足；VP8 也是只看 `OMX.qcom.`；`OMX.google.` 开头的算软件实现。API 27 低于 Q（Q 及以上的分支我没读）。**设备实际接受哪些颜色格式、会不会真被选中，要运行时才知道** |
| 回声消除 | `/vendor/etc/audio_effects.xml` 对 `voice_communication` 输入预置了 `aec` 和 `ns`，库是 `/vendor/lib/soundfx/libaudiopreprocessing.so`。平台有可用的回声消除和降噪，**效果没测**。麦克风只有 1 个，离功放很近 |
| 发热 | 用户态温控配置 `/vendor/etc/thermal-engine.conf` 是空的（“File empty by default”），内核 `msm_thermal` 的 `enabled=N`。**所以读不出降频门限**。`docs/10` §5 记录过“连续采集后发热、降频，控制 fps”。通话是持续采集加编码，必须实测 |
| 内存与 CPU | 总 1.9 GB，可用约 1.08 GB；8 核，最高 1.8 GHz |
| 硬件开关 | 摄像头开关和静音键以输入事件出现（`camera_switch`、`mute`），能否物理断开传感器没验证 |
| 权限 | 现在的 `AndroidManifest.xml` 没有 `CAMERA`、`RECORD_AUDIO`，代码里也没用到摄像头。API 27 要运行时授权 |
| 网络 | 公网 IPv4（不是运营商级 NAT 的 100.64 网段）；只有 `fd05:` 开头的内网 IPv6，没有公网 IPv6，所以现在走不了 IPv6 直连 |

**WebRTC 库**（只列出 AAR 内容，没有运行）：

| 库 | 版本与日期 | 许可 | AAR | armv7 的 .so |
|---|---|---|---|---|
| LiveKit `io.github.webrtc-sdk:android` | 150.7871.04，2026-10-23 | 仓库 `LICENSE` 是 MIT（版权 2023 WebRTC SDKs，只管打包脚本）；libwebrtc 本体是 BSD-3（`Licenses/WEBRTC.md`），POM 写的也是 BSD-3。**两者都适用，不矛盾**；libwebrtc 内含的第三方库许可没逐个查 | 49 MB | 6.8 MB |
| Stream `io.getstream:stream-webrtc-android` | 1.3.10，2025-09-11 | Apache-2.0；README 另写 WebRTC 部分是 BSD | 20.6 MB | 6.6 MB |

历史只读检查确认上述标准版与Stream均含armv7库，不代表API27运行已通过。2026-11-05补查LiveKit150.7871.04三个变体：AAR分别49,068,339 / 23,113,908 / 14,265,935字节，armv7 so为6,791,820 / 6,795,276 / 4,471,732字节，均ELF32/EM_ARM、minSdk21；prefixed的Java实现位于`libs/shadow-all.jar`，三变体各433个Java17类。JmDNS3.6.5为220,480字节Java8 JAR，运行依赖SLF4J-api2.0.7，许可分别Apache-2.0/MIT。四组离线D8（min-api27、android-35库桩）均exit0且原日志为空；不是API27链接/JNI或发现验证。证据见`artifacts/call/CALL-0/libraries-20261105/`。

固定构建源码的三个Android目标均设`rtc_use_h264=false`，不包含内置OpenH264/FFmpeg软件H.264；不能从“带software codecs”推断软件H.264回退。stripped仅称裁剪部分codec，不称所有软件视频codec都无。平台MediaCodec的软件解码是另一条路径，真机支持未知。包装仓库MIT、native BSD-3及Apache-2.0修改需分层保留；已查AAR/嵌套JAR未发现LICENSE/NOTICE，完整第三方清单与实际Maven成品→构建run的绑定仍未闭环，尚未选变体/加入依赖。Stream对应的libwebrtc版本本轮未复核。

## 3. 通话怎么“自动打通”（ICE）

1. 两端各自从**同一个 UDP 套接字**向几个 STUN 服务器发绑定请求，得到自己的公网地址和端口（srflx 候选）。再加上本机的局域网地址（host 候选）。
2. 把候选地址（加密后）通过会合渠道交给对方。
3. 两端**同时**向对方所有候选发探测包，各自的 NAT 因此打开通向对方的通路，谁先通就用谁。
4. DTLS 握手，用 SRTP 传音视频。WebRTC 默认端到端加密（通用知识，没在本项目验证）。

第 1、3、4 步 WebRTC 库自己做。**第 2 步是我们要解决的，见 §6。**

## 4. NAT：这个网络和一般情况

在这台设备所在的网络上（用设备发 STUN 请求，没有用 Mac，Mac 经代理上网）：

- 同一个本地端口分别发给 11 个、7 个不同的服务器（不同 IP），外部端口始终是同一个（40021、40022）。这是**端点无关映射**，对打洞最友好。
- 批量测试里 107 个回复，87 个的外部端口等于我们选的本地端口，**20 个不等**，原因没查。
- 这只是**这一侧**。成不成还取决于对端的 NAT，我对它没有任何数据。

一般情况（都是别处的数据，不是本项目的）：

- libp2p 的大规模测量：4.4 百万次打洞、85,000+ 网络、167 个国家，打洞成功率 **70% ± 7.1%**，TCP 和 QUIC 相近，成功的 97.6% 在第一次尝试就成功（arXiv:2510.27500）。对象是 IPFS 节点，不是家庭视频通话用户。
- 业界常说约 15–25% 的 WebRTC 会话需要 TURN：来自二手博客，原始的 callstats 分析没取到。
- 国内家庭宽带 CGNAT、对称型 NAT 的比例：**没有找到权威数据**，只有没有来源的博客说法。

所以：只靠 STUN 时，**不能承诺一定通**。缓解办法：

- **NAT 自检：**同一个套接字问至少 2 个 STUN 服务器，外部端口不一致就是对称型。结果随配对记录交换，呼叫前就知道这一对是否可能成功，打不通时直接提示，不让用户干等。
- **IPv6：**如果两端都有公网 IPv6，不经 NAT，可以直连。这台设备所在的网络现在没有。
- 端口预测之类的对称型 NAT 技巧：不做，不可靠，以后再评估。

## 5. STUN 列表

**候选来源：**[pradt2/always-online-stun](https://github.com/pradt2/always-online-stun)，MIT，每小时重新检测一次，文件 `valid_hosts.txt`（211 个）、`valid_ipv4s.txt`、`valid_hosts_tcp.txt` 等。README 要求使用者写死文件的原始地址，作者保证不改文件名。服务器都是第三方运营的，项目不保证可用。

**在这台设备上的实测：**216 个候选（上面的 211 个加我补的 12 个，去重）各发一个绑定请求，**107 个有正确回复，109 个没有**。有回复的包括小米（`stun.miwifi.com`）、哔哩哔哩（`stun.chat.bilibili.com`）、`stun.hitv.com`、Google 的三个、Cloudflare；没回复的包括 `stun.qq.com`、`stun.stunprotocol.org`、`stun.syncthing.net`。

两条教训：

- 一半的候选在这个网络上用不了，所以**列表必须足够大**（至少上百个），并且**必须在设备上做健康检查**，不能信列表自己说的“在线”。
- **单次探测不可靠：**Cloudflare 第一次探测没回复，这一次有回复。判断一个服务器好坏要多次，要退避，不能一次失败就永久剔除。

**设计：**

- **内置一份**，由工具从已下载的候选列表生成，记录来源、日期、SHA-256（照 `tools/vendor_update_roots.py` 的做法，不联网）。
- **更新走 `docs/34` 的签名目录**，不新增我们的服务器（目录托管在 GitHub 等静态托管上，这是 OTA 本来的设计）。
- **设备端健康评分：**照 `plugins/iptv/health.lua` 的做法，失败暂停，连续失败退避，到期再测；通话前并行问 3–4 个，谁先回用谁。一次传给 WebRTC 几个、库对个数有没有上限：没查。
- **多样性：**不同运营商、不同地区；Google 不是默认；国内能用的（miwifi、bilibili、hitv 等）排前面。
- 列表是配置，走设置 schema（ADR-017），不写死。

## 6. 会合渠道：打洞辅助服务器、EasyTier 一类、公共设施

配对本身可以在两台设备同在一个局域网时做（交换公钥和一个会合密钥）。难的是**分开以后**：对方的公网地址会变，还得能“响铃”和知道对方在不在线。

### 6.1 三条路

| 路 | 是什么 | 我的判断 |
|---|---|---|
| **A. 自己的“只帮打洞”服务器加 WebRTC** | WebRTC 的 ICE 负责打洞；我们的服务器只做信令（交换地址、响铃、在线状态）和 STUN。媒体点对点 | 标准、最简单。打洞只有 ICE 的能力（没有针对对称型 NAT 的特殊手段）。信令用什么（MQTT、自写 WebSocket、XMPP/Jingle、SIP）还没定，见 §6.2 |
| **B′. EasyTier 去 VPN 化** | 只借 EasyTier 的节点发现、打洞和加密连接，不建 TUN、不改系统路由；我们的服务器是一个 EasyTier 共享节点；一个家庭的设备是一个网络 | 打洞手段更多，会合不用另写，但工程量不是“稍微改”，见 §6.3。**走不走这条，取决于 CALL-0b 的对比实验** |
| **C. 公共设施（公共 MQTT、BitTorrent DHT、tracker 等）** | 不运营任何东西，借别人的 | 备选或兜底，见 §6.4；不适合当首发的唯一渠道 |

### 6.2 路 A：现成软件，服务端零自研代码

- **信令用什么还没定。**我之前写 MQTT，只是因为它最省事：推送式、有遗嘱消息可以当在线状态、客户端很小，现成软件多。但它**不是通话的标准信令**。标准的是 SIP 和 XMPP 的 Jingle。SIP 的现成客户端 Linphone SDK 开源版是 **AGPLv3**（页面写的是 AGPLv3，不是 GPL-3.0；用了要先改 ADR-043），Maven 分发、体积、minSdk 页面都没写；Jingle 的客户端库没查。另一条是自写一个很小的 WebSocket 信令，但那是自己造轮子。选哪个等 CALL-0 评估。
- **MQTT broker（候选之一）：**Mosquitto。README：实现 MQTT 3.1.1 和 5.0；有 `mosquitto_passwd` 做用户名密码；仓库里有 `aclfile.example`，说明支持文件形式的访问控制，**但页面没有明说**；根目录有 `epl-v20`、`edl-v10` 两个许可证文件，**许可条款没读**。MQTT 协议本身有“遗嘱消息”（设备掉线时 broker 代发一条），可以当在线状态；Mosquitto 页面没单列这一点，我也没试过。
- **STUN：**coturn 的 `-S, --stun-only`：“Run as STUN server only, all TURN requests will be ignored”（`README.turnserver`）；另有 `--no-udp-relay`、`--no-tcp-relay`。去掉这个开关，同一个软件就能提供 TURN 中继，这是以后要不要兜底转发的退路（OWN-20）。coturn 的许可：`LICENSE` 文件头是 Citrix 的版权声明，具体条款没读完。STUN 其实可以不自建（§5 的公共列表在这个网络上有一半可用），自建一个只是为了列表里至少有一个我们自己能保证的。
- **TLS：**我们自己的服务，证书由我们定；设备**固定我们自己的证书或根**，不依赖设备那套旧的根证书库（`41-ca-trust.md`）。要不要域名、服务器放国内还是国外、是否要备案：没核实，见 OWN-24。
- **安全：**这是一个放在公网上的服务。`docs/05` §11 的“Server 不应暴露公网”针对的是 ThinkOS Server（还没做），不适用于它，但它需要自己的安全设计：每台设备用自己的密钥认证，内容端到端加密并签名，限流，尽量少记元数据。Mosquitto 的访问控制能不能做到“设备只能给配对过的对端发消息”，**没验证**（它的“动态安全插件”在页面里只提了名字，没读）。
- **代价：**要有人运维一台机器；它挂了，跨网通话就响不了铃（局域网通话和设备其余功能不受影响）；元数据（谁、何时、从哪个 IP）运营方能看到，也就是我们自己。
- **没有 TURN：**两端都是对称型 NAT 时仍然打不通（§4）。

### 6.3 路 B′：EasyTier 去 VPN 化（评估）

**查到的事实**（README、配置文档、PR 和 issue、main 分支源码；我没有在设备上运行过）：

| 项 | 内容 |
|---|---|
| 是什么 | “A simple, decentralized mesh VPN with WireGuard support”，Rust 加 Tokio，LGPL-3.0。节点通过连接任一已有节点加入同一个虚拟网络 |
| 穿透 | README：“Supports UDP and IPv6 traversal, works with NAT4-NAT4 networks”。配置文档：`--disable-sym-hole-punching` 关闭“针对对称型 NAT（NAT4）的生日攻击式 UDP 打洞，运营商可能会拦截”；TCP 打洞有单独开关。可以用 `--stun-servers` 换掉默认的 STUN 列表。**成功率没有数据**，也没有限制打洞速率的选项 |
| 共享节点 | 社区提供免费共享节点；任何 EasyTier 网络都可以当共享节点。README：“When P2P fails, data will be relayed through shared nodes.” |
| 关闭转发 | 有 `--relay-network-whitelist`、`--private-mode`、`--foreign-relay-bps-limit` 等。issue #1387 里，“只帮打洞、不转发”被说成“目前尚未有这种功能”。PR #2188（2026-04-30 合并）加了 `disable_relay_data`：“keep the relay/admin control plane online while dropping relay data-plane forwarding”。**PR 页面没写版本；v2.6.4 标为 Latest、发布于 5 月 12 日（年份按资源时间戳推断），晚于合并，所以多半包含，但这是推断。页面也没写关闭转发后打洞协调是否照常** |
| **能不能不建 TUN** | **能。**配置层面：`--no-tun`（“不创建TUN设备，可以使用子网代理访问节点”）；`-i/--ipv4` 留空则只转发数据包、不创建 TUN；`--socks5 <端口>` 内置 SOCKS5 服务，让客户端访问虚拟网络（UDP ASSOCIATE 页面没写）；`--port-forward`“将本地端口转发到虚拟网络中的远程端口”，文档例子是 `udp://0.0.0.0:12345/10.126.126.1:23456`。编译层面：main 分支 `easytier/Cargo.toml` 里 `tun` 是可选特性（默认开），`socks5`、`kcp`、`quic`、`websocket`、`wireguard` 等也都是特性，所以可以编出根本没有 TUN 代码的版本 |
| 能不能嵌入 | main 分支已把核心拆成独立的 `easytier-core` 库；源码里的测试用 `InstanceManager`、`NativeInstanceFactory` 直接创建实例，设 `flags.no_tun = true`、`set_listeners(Vec::new())` 就能跑。**这是测试代码，不是公开承诺的接口**。crates.io 上的 `easytier` 只到 2.0.3（2024-10），docs.rs 构建失败，没有 API 文档 |
| 安卓现成构建 | 第三方 `710850609/easytier-ffi`（MIT）在 CI 里编出安卓四个 ABI 的 `libeasytier_ffi.so`，v2.7.0（2026-10-08）的 armv7 压缩包 6.3 MB，README 没写接口。官方 v2.6.4 的 arm APK 22.5 MB，里面唯一的原生库 `libapp_lib.so` 19.8 MB（未压缩），是 Tauri 界面加核心合在一起的，不是核心本身。第三方 EasyTierCompose 把核心做成 JNI 库，有 VpnService 和 Root TUN 两种模式 |
| 家庭=网络 | 网络靠名称和密钥隔离，共享节点可以同时服务多个网络。所以“一个家庭的设备是一个网络”，配对就是互相交换网络名和密钥。`--secure-mode`（Noise 握手）和 `--credential`（临时节点不拿 network_secret）可以避免把一个全局密钥打进 APK；具体怎么用没验证 |
| 已知风险 | 2024 年的 issue #289：打洞产生数千个 UDP 连接，在某些路由器（iStoreOS 固件）上导致全家断网；PR #298 修复，页面没写在哪个版本。**当前版本在便宜路由器上的表现没有数据**。资源占用也没有数据 |

**“只交换地址”不保证打得通，你说得对。**会合只负责告诉对方地址，能不能打通完全看 NAT 和防火墙的行为，也就是打洞手段。EasyTier 比纯 ICE 的手段多（对称型 NAT 的生日攻击式打洞、TCP 打洞、IPv6），但两者都没有成功率数据，也都打不穿“UDP 全封”或“双方对称型 NAT 且运营商拦截”的网络。EasyTier 在这种情况下靠共享节点中继兜底，而我们关了中继（OWN-20）。

**“稍微改一下 EasyTier，让它只走我们自己的连接”可以吗？**

- **不走 VPN 可以，而且大半不用改源码：**去掉 `tun` 特性，或者用 `--no-tun` 加 `--port-forward` / `--socks5`。
- **但“稍微”要打折：**
  1. 没有 TUN 时，对端虚拟 IP 上的端口由谁接收，文档没写，要实测；可能要我们补一小段，把虚拟端口接到本地回环。
  2. **WebRTC 吃不了 EasyTier 的套接字。**打洞用的 UDP 套接字归 EasyTier，而 WebRTC 的 ICE 要自己的套接字。办法是两端各开一个本机回环的 UDP 转发器：WebRTC 以为对方在 127.0.0.1，转发器把数据报塞进 EasyTier。代价是多一层加密、多一次拷贝、MTU 变小，都没测。另一条路是放弃 WebRTC 的传输，把媒体帧装进 EasyTier 的连接，那就要自己写抖动缓冲和拥塞控制，是真正的造轮子，不推荐。
  3. 要加 Rust 工具链（`AGENTS.md` 现在写的是两条构建链路），或者信任第三方预编译的库。
  4. LGPL-3.0：改了就要公开改动。我们的 APK 本来按 GPLv3 公开，所以可以，但要长期跟上游，main 分支最近有大的重构。
  5. 资源占用、路由器影响没数据。
- **它真正的优势只有一个：**打洞手段更多，而且会合不用另写（服务器就是一个共享节点）。如果它的成功率明显更高，值得；没有数据之前不能凭感觉定。
- **如果走这条，MQTT 就不需要了。**

其它同类产品（ZeroTier、Nebula、Tailscale 和它的自建控制端、NetBird）我没有逐个查。它们同属“覆盖网络”这一类，上面关于 VPN 通路、嵌入方式、资源的顾虑大体通用，但**这一句是推断，不是查证**。

### 6.4 路 C：公共设施（备选或兜底）

下面是我在设备上测到的（**只测了 TCP 连接或一个 UDP 包能不能通，没跑真实协议，也没测 TLS 证书能否通过设备旧的根证书库**）：

| 渠道 | 设备上测到 | 官方定位与限制 | 工作量 |
|---|---|---|---|
| **公共 MQTT broker** | `broker.emqx.io` 1883、8883 通；`broker-cn.emqx.io` 1883 通；`broker.hivemq.com` 1883 通；`test.mosquitto.org` 8883 通、1883 不通；`mqtt.eclipseprojects.io` 不通 | EMQX 写明“Prototype, learn, and test”、“All messages are visible to other users. Do not send sensitive data.”；HiveMQ 写明“quick, temporary MQTT testing”、“not intended for private or production data”。都**没有写 SLA** | 小。推送式，能立刻响铃 |
| **BitTorrent 主线 DHT（BEP 44）** | 4 个引导节点里只有 `dht.transmissionbt.com` 回复（各只试一次） | 没有运营商，是公共网络。BEP 44：值 ≤ 1000 字节、salt ≤ 64 字节、ed25519 签名；不重发布约 2 小时过期，应每小时重发；规范是草案 | 中。用现成库 jlibtorrent；没有推送，响铃靠轮询，延迟秒级到十几秒 |
| **WebTorrent 的 WebSocket tracker** | `tracker.openwebtorrent.com`、`tracker.webtorrent.dev` 的 443 通 | 本来用来交换 WebRTC 的连接信息，但运营方只为种子下载提供，没有查他们的使用条款 | 中 |
| ntfy.sh | 443 通 | 托管的发布订阅服务，条款和限额没查 | 小 |
| Nostr 中继（`relay.damus.io`、`nos.lol`、`relay.nostr.band`） | 都不通 | — | — |
| 手动码（二维码或文字，两台设备之间自己转交） | 不依赖任何第三方 | 只适合配对时或两台设备在一起时，分开后基本不能用 | 小 |

- **公共 MQTT 官方明说仅供测试、消息公开**，所以不适合当首发的唯一渠道；内容一律端到端加密。
- **DHT（jlibtorrent）：**MIT；最新版 2.0.15.3（GitHub 页面标 2026-10-31）。读 master 分支源码确认：`SessionManager.dhtPutItem(公钥, 私钥, 条目, salt)` 和 `dhtGetItem(公钥, salt, 超时)` 就是 BEP 44 的可变条目读写，默认带引导节点。AAR 52.5 MB，下载后核对：含 armv7，其 .so 11.06 MB（未压缩），minSdk 26（我们是 27）；**不在 Maven Central**，在 GitHub release 和 FrostWire 自己的 Maven 仓库，所以构建要把它固定下来（SHA-256 `9f14e44e…382e`），不能在线拉（`docs/07`：构建不在线拉取）。优势是**没有服务器也能工作**，所以适合做“我们的服务器不可用时的兜底”；首发不做（省约 11 MB，少一个要测的东西）。只开 DHT、不下种子时的内存和 CPU 没测，**真实的 put / get 没试过**。
- **别人的做法：**网页世界里同样的办法很常见。Trystero 用 BitTorrent tracker、Nostr、MQTT 等公共设施只交换连接信息，数据不经过它们；它也提醒，运营方能用房间名反推出默认密钥，所以要用配对密钥当密码。
- 以后如果需要中继，也可以让有公网地址的 ThinkOS 设备互相帮忙转发，仍然是点对点。现在不做。

### 6.5 建议

- **现在不要二选一。**打洞成功率是整个功能最大的风险，而我手上没有 EasyTier 和 WebRTC 的对比数据。先做一个有时限的对比实验（`36` 的 CALL-0b）：同样的两个网络之间（家里的网络对手机热点），分别试 EasyTier 去 VPN 化（`--no-tun`）和 WebRTC 的 ICE，各试若干次，记录成功率、耗时、路由器连接数。
- **实验之前的倾向：**通话先走路 A（标准、最简单），把“连接层”做成可替换的接口。如果数据显示 EasyTier 明显更能打通，再换到路 B′，并顺势让它成为所有跨地点功能（OTA 缓存、配置同步、远程运维）的底座（OWN-23）。
- 走路 B′ 时，服务器是一个 EasyTier 共享节点（开 `disable_relay_data`），不需要 MQTT；走路 A 时，信令方案在 §6.2 里选。
- DHT 只是兜底。
- 前提：所有者确认 OWN-19、OWN-20、OWN-24。
- 无论哪条路：内容端到端加密；连接层和会合渠道做成可替换的接口，每个提供者自己是一份带健康检查的列表（和 STUN 一样）。

## 7. 方案概要

- **位置：**OS 里的 `CallHost`，和 `CastHost` 同级；协议不进 Lua；插件只声明联系人页和拨号页，通过 `tos.call.*`（要权限声明，需要新 ADR 里的 API 号）调用；通话界面是 OS 组件。
- **服务端（OWN-19、OWN-24）：**一台有公网 IP 的机器，跑现成的 Mosquitto，可选一个 `--stun-only` 的 coturn；不转发音视频；设备用自己的密钥认证，内容端到端加密并签名，TLS 固定我们自己的证书，不依赖设备的旧根证书库。它独立于 ThinkOS Server（`docs/05`，还没做），不放进 Server。部署和运维是所有者的事。
- **配对：**依赖“对等发现与配对”（上一轮讨论的设想，还没有文档和任务）。定了再单独写文档。
- **隐私：**默认关；来电必须点接听，不做免接听；通话中屏幕要有明显指示；尊重摄像头开关和静音键；摄像头被占用时，ADR-004 的存在检测让出。
- **媒体：**来电时暂停媒体（音频焦点）。WebRTC 的音视频管线不走 `MediaEngine`，这是 ADR-047 里待所有者确认的澄清：通话不算“播放”，不算第二个播放器。
- **冷启动：**库的 .so 按需加载，启动时不加载。
- **许可：**BSD-3 / Apache-2.0 可以进 GPLv3 的 APK，要登记 `THIRD_PARTY_NOTICES.md`；库里的第三方部分没逐个查。

## 8. 要所有者定的事（`36` 号文档的 OWN-19 到 OWN-24）

1. **连接层与会合（OWN-19）**（§6）：先做 CALL-0b 的对比实验再定。实验前倾向路 A，连接层做成可替换的接口；EasyTier 去 VPN 化是否更好，看数据。
2. **打洞失败时，要不要让这台服务器兜底转发（OWN-20）：**“只帮打洞”意味着不转发，部分配对（两端都是对称型 NAT）永远打不通。推荐首发不兜底，界面明说“无法直连”，用 CALL-4 的真实成功率再决定；去掉 coturn 的 `--stun-only` 就能开启中继，带宽成本没估。
3. **对端是谁（OWN-21）：**只有 ThinkOS 设备，还是也要手机或浏览器？有了自己的服务器，网页端可行，但要另做网页和部署。推荐首发只做设备。
4. **WebRTC 库（OWN-22）：**先定在 CALL-0 之后（许可核对、软件 H.264、体积、实测）。
5. **EasyTier 是否成为所有跨地点功能的底座（OWN-23）：**取决于 CALL-0b。如果它明显更能打通，推荐把它作为 OTA 缓存、配置同步、远程运维、通话的共同连接层，而不只是通话；否则不排期。
6. **服务器放哪、谁运维、要不要域名和备案（OWN-24）：**这是所有者的事，我没有服务器和账号，不能代办；放国内还是国外、用 IP 加固定证书还是用域名，我没核实备案和云厂商的规则。

## 9. 没验证的（每项都标了要怎么验）

2026-11-05局部实机证据：固定standard ARMv7库已在独立F030/API27 UID实际装载并完整枚举四工厂；随后SDK实际选HardwareVideoEncoder及OMX.qcom.video.encoder.avc，byte-buffer颜色21输入的一帧1280×720灰色I420成功输出3639字节关键帧并release OK。没有相机、麦克风、EGL、PCFactory或网络；30是maxFramerate配置，未测720p30采集、持续编码、解码渲染、码率控制或通话。原数据/权限/五流0保持，见`../artifacts/call/CALL-0/h264-one-frame-030-20261105/qualification.json`。

| 项 | 怎么验 |
|---|---|
| 应用里 `CameraCharacteristics` 报的硬件级别；采 720p30 的 CPU | CALL-0 |
| 实际单帧硬编与颜色21已通过；持续采集/编码、其他输入颜色、码率控制和解码渲染仍待 | CALL-0，见上述单帧实机证据 |
| 回声消除效果；WebRTC 软件回声消除在 A53 上的 CPU | CALL-0，喇叭和麦克风同机 |
| 持续采集加编码的发热和降频（配置里读不出门限） | CALL-0，十分钟以上，属于性能测试，先告知所有者 |
| 109 / 216 个 STUN 无回复的原因；服务器随时间的稳定性（只测了一次） | CALL-1，多次、间隔着测 |
| 20 / 107 个外部端口不等于本地端口的原因 | CALL-0 |
| 对端 NAT 类型的分布，国内家庭宽带的比例 | 没有权威数据；CALL-4 在两个真实网络间实测 |
| 会合服务的真实握手（呼叫、应答、候选交换）、断线重连、延迟；TLS 固定自己的证书，核对不依赖设备根证书库 | CALL-0 的会合服务原型 |
| 各渠道的使用条款和限额（WebTorrent tracker、ntfy.sh 没查） | CALL-4 前 |
| jlibtorrent 只开 DHT 时的内存、CPU、网络；真实 put / get 在国内网络上的成功率和延迟；libtorrent 内含的第三方许可 | 只在要做 DHT 兜底时 |
| Mosquitto：访问控制只能按用户名或客户端 ID 限制主题，不能表达“配对关系”，所以安全靠“主题猜不到加端到端加密”（`44` §4）；配置页没有发布速率限制的选项；遗嘱消息是否受 ACL 约束页面没写；许可条款 | CALL-0 的会合服务原型 |
| coturn 的许可条款 | CALL-0 |
| 服务器放哪、域名和备案、云厂商对 UDP 和 MQTT 端口的限制 | OWN-24 |
| EasyTier：`disable_relay_data` 在哪个版本；关闭转发后打洞协调是否照常；资源占用；便宜路由器上的连接数 | CALL-0b |
| 无 TUN 时对端虚拟 IP 的端口由谁接收；`--port-forward` 是否支持 TCP；`--socks5` 是否支持 UDP | CALL-0b |
| 去掉 `tun` 特性后能否为 armv7 安卓编译；核心的实际体积；是否需要补丁；`easytier-core` 的内部接口是否稳定（测试代码里用，没有公开承诺；crates.io 的 `easytier` 只到 2.0.3） | CALL-0b |
| WebRTC 经本机回环转发器跑在 EasyTier 上的 MTU、延迟、CPU | CALL-0b（只有路 B′ 有希望时才做） |
| EasyTier 对称型 NAT 打洞和 WebRTC ICE 的真实成功率对比 | CALL-0b |
| Linphone SDK：Maven 分发、体积、minSdk、AGPLv3 与 ADR-043 的关系；XMPP Jingle 的客户端库 | 只在考虑 SIP 或 XMPP 路线时 |
| LiveKit三个变体静态体积/固定配方软件H.264已补事实；Maven成品与构建run绑定、精确第三方清单、最终变体及实际通话仍待；API27装载/四工厂/单帧硬编已补，STUN限制本轮未查 | CALL-0；静态证据见§2及libraries-20261105 |
| 运营商对 UDP 的限速或封锁 | CALL-4 |
| 来电唤醒、接管页、Lock Task、注意力状态机怎么配合 | 单独设计，CALL-2 前 |

## 10. 来源

- 设备：`adb shell` 的只读命令（`dumpsys media.camera`、`/vendor/etc/audio_effects.xml`、`/vendor/etc/thermal-engine.conf`、`/vendor/etc/media_codecs*.xml`、`/sys/module/msm_thermal`）；STUN、TCP、UDP 探测见 §4 到 §6
- WebRTC 源码（branch-heads/7871）：`sdk/android/api/org/webrtc/Camera2Enumerator.java`、`HardwareVideoEncoderFactory.java`、`sdk/android/src/java/org/webrtc/MediaCodecUtils.java`
- Maven Central：[`io.github.webrtc-sdk:android`](https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/)、[`io.getstream:stream-webrtc-android`](https://repo1.maven.org/maven2/io/getstream/stream-webrtc-android/)；[webrtc-sdk/android](https://github.com/webrtc-sdk/android)
- [always-online-stun](https://github.com/pradt2/always-online-stun)
- [BEP 44](https://www.bittorrent.org/beps/bep_0044.html)
- EasyTier：[README](https://github.com/EasyTier/EasyTier)、[releases](https://github.com/EasyTier/EasyTier/releases)、[配置文档](https://www.easytier.cn/guide/network/configurations.html)、[PR #2188](https://github.com/EasyTier/EasyTier/pull/2188)、[issue #1387](https://github.com/EasyTier/EasyTier/issues/1387)、[issue #289](https://github.com/EasyTier/EasyTier/issues/289)；第三方 [EasyTierCompose](https://github.com/originalFactor/EasyTierCompose)
- [coturn `README.turnserver`](https://github.com/coturn/coturn/blob/master/README.turnserver)、[Mosquitto](https://github.com/eclipse-mosquitto/mosquitto)
- [jlibtorrent](https://github.com/frostwire/frostwire-jlibtorrent)（`SessionManager.java`、releases）、[Trystero](https://github.com/dmotz/trystero)
- 设备：`/dev/tun`、`/proc/config.gz` 里的 `CONFIG_TUN`
- EasyTier main 分支源码：[`easytier/Cargo.toml`](https://github.com/EasyTier/EasyTier/blob/main/easytier/Cargo.toml)、[`easytier/src/instance/`](https://github.com/EasyTier/EasyTier/tree/main/easytier/src/instance)；[docs.rs/easytier](https://docs.rs/easytier/latest/easytier/)；第三方构建 [easytier-ffi](https://github.com/710850609/easytier-ffi)
- [Linphone SDK](https://github.com/BelledonneCommunications/linphone-sdk)
- NAT 打洞测量：[arXiv:2510.27500](https://arxiv.org/abs/2510.27500)
- 公共 MQTT：[EMQX](https://www.emqx.com/en/mqtt/public-mqtt5-broker)、[HiveMQ](https://www.hivemq.com/mqtt/public-mqtt-broker/)
