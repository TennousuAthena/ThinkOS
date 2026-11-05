# 39 · 电台目录：radio-browser

> 2026-11-03。用户要用 radio-browser 作为 `radio` 插件的全球电台来源。本文记录评估、实测和风险；要做的事在 `36` 号文档的 APP-9，要所有者定的在 OWN-14、OWN-15。
> 2026-11-05 检查点：0.1.13已安装，939项JVM（1项旧skip）、194项Python、252项Lua/JNI（含46项radio）及debug构建/lint通过。真机本地目录18项通过并恢复；同APK原生DNS/HTTPS诊断得到200/100行，生产Lua新鲜落盘98台并显示原生台名。MP3、AAC与HLS已分别通过原生点选、真实解码和进度前进，三格式证据来自同APK的两轮用例，各已恢复；不把旧的三格式整轮失败改为通过。目录与点击上报默认关闭，OWN-14/15未决；数据许可范围见§5，最新证据和未验边界见§7、§9。
> 官方默认地址现在是DNS发现入口，经公开API10 `tos.net.resolve`得到并正向确认PTR后，Lua筛选官方名称、随机起点和有界切换，再请求节点HTTPS。TLS与默认连接/读超时3000ms均未放宽；0.1.10直接请求`all`的握手重置只保留为历史失败，不再代表当前公开目录状态。

## 1. 结论

把它作为“目录来源”，不把目录里的电台打包成内置内容。JSON适配、分类、缓存、健康检测与设置已实现；0.1.13已在真机走通官方DNS发现、HTTPS目录、生产Lua解析及MP3/AAC/HLS播放。三格式都有实际decoder、采样率和进度证据，不能扩张为全部电台、全部分类或健康检测矩阵通过。目录仍默认关闭，原`source`的M3U/PLS入口保留，其独立阶段0链路未重验。明文流、服务端检查与设备可达的差异、无可用性保证、点击上报隐私以及目录与媒体的权利范围仍须保留。

## 2. 实测

下表是2026-11-03的历史只读采样，不是当前完整真机验收；当前系统证书库状态也不再由当时的grep结果代表。0.1.10的历史握手失败与0.1.13的实际成功分别见§9。

| 项目 | 结果 | 怎么测的 |
|---|---|---|
| 规模 | 62,295 个电台，226 个国家和地区，642 种语言，11,971 个标签；661 个被标为坏（约 1%）；过去 24 小时 268,947 次点击；服务端版本 0.7.45 | Mac 上 `GET /json/stats`（Mac 经代理上网，只用来看数据形状） |
| 设备能否连上 API | `all`、`de1`、`de2` 的 443 通；`fi1`、`at1` 解析不到；到 `all.api` 的 ICMP 约 232 ms | HUA0BWUT 上 `nc -w 5 -z`、`ping` |
| TLS（11月3日历史采样） | Mac观察到服务器TLS1.3，链为`*.radio-browser.info` ← Let’s Encrypt YR2 ← Root YR ← ISRG Root X1交叉签名；叶子证书当时标示2026-12-21到期。当时设备库可查到X1，未查到X2/Y系根；之后已更换系统证书库，不能沿用该结论描述当前状态 | 当时用Mac的`openssl s_client`与设备`grep`，没有请求设备HTTP客户端。之后0.1.10直接`all`曾握手重置，0.1.13发现节点后生产HTTPS已成功；均见§9，不据此推测当前TLS根或版本 |
| 输出格式 | `/m3u/stations/search?…` 返回 `audio/mpegurl`，每台三行：`#RADIOBROWSERUUID:`、`#EXTINF:1,台名`、地址；3 个台 431 字节。`/pls/…` 同样可用 | Mac 上 `curl` |
| JSON 体积 | 平均每台约 1.2 KB；全球点击最多的 200 个 244,218 字节；Mac 上 1.25 秒 | Mac 上 `curl` |
| 点击最多的 1500 个（`hidebroken=true`） | https 969（65%）、**http 531（35%）**；HLS 184（12%）；MP3 1023、AAC 222、AAC+ 154、未知 88、OGG 10；`ssl_error=1` 有 9 个；国家：德 214、美 127、法 88、印 81、英 73、土 68、俄 60、意 57、西 56、中 52 | Mac 上 `curl` 后统计 |
| 样本里的异常地址 | 内网地址 0 个，非 http(s) 协议 0 个 | 同上。目录任何人都能提交，样本没有不等于没有 |
| 设备能否连上台的主机 | 70 个点击最多的非中国 https 主机，实际测了 67 个：63 个 443 通，4 个不通：`liveradio.swr.de`、`stream.radiofrance.fr`、`stream.zeno.fm`（一个托管平台，上面有大量台）、`icecast.ndr.de`。另有 3 个没测到：2 个国家码为空的行被脚本读错，最后一行没换行被跳过 | HUA0BWUT 上 `nc -w 4 -z 主机 443`。只测了 TCP，没分 DNS 和封锁；样本偏向热门 |

不通的比例约 4/67，即 6%。第一次汇报时我说的是 6/69 约 9%，把脚本读错的 2 行也算成了不通，这里更正。

## 3. 官方对客户端的规则

来自 `api.radio-browser.info` 和 `de1.api.radio-browser.info` 的文档：

- 服务器发现：解析 `all.api.radio-browser.info` 的地址并反向查询名称，或查 SRV `_api._tcp.radio-browser.info`；随机起点、失败换下一个，使用发现的服务器名连接，优先HTTPS，不固定单台。`all`是发现入口，不能把示例中的单台服务器固化为默认兜底。[官方客户端规则](https://api.radio-browser.info/)
- 请求要带有描述性的 `User-Agent`，如 `appname/appversion`。
- 每次用户开始播放，调用 `/json/url/{stationuuid}` 一次；同一 IP 同一电台一天只算一次。
- 用 `stationuuid`，不要用 `id`；用 `countrycode`，不要用 `country`。
- 没有写出的速率限制；“This webservice can be used freely but without guarantee to work”；可能停机。
- 项目说可以自己部署、镜像全部数据、分叉。以后 Server 可以做局域网镜像（ADR-015），本轮不实现 Server 中的内容。
- 搜索参数：`countrycode`、`language`、`tag`、`is_https`、`hidebroken`、`order`（含 `clickcount`、`votes`、`random`）、`reverse`、`offset`、`limit`。

## 4. 风险和对策

| 风险 | 对策 |
|---|---|
| 35% 的热门台是明文 http，`media.public_http` 默认关，播不了 | 查询加 `is_https=true`；客户端再验一次；顺带挡掉指向内网的明文地址 |
| `hidebroken` 是对方德国服务器检查的结果，不代表这台设备连得上 | 所有者定：连不上无所谓，失效的不放，或自动换下一台。照 `iptv` 的线路健康做（§6），不预先测整张表 |
| 志愿运营，不保证可用 | 从`all.api…`发现服务器池，仅向筛过的官方名称发HTTPS；有界切换，整次刷新失败保留上一份列表；收藏存uuid和地址，目录连不上照常播 |
| 内容没有分级，任何人都能提交 | 入口只给“所选国家点击最多的”，不做全库关键词搜索；台名走 `clean` |
| 点击上报等于把设备 IP 和所听电台发给第三方，与 `35` 的“不收集任何数据”冲突 | 做成设置开关，默认关（OWN-15） |
| 响应大小贴着解析上限（`main.lua` 256 KiB）；`tos.http` 读超时 3 秒 | 每页 100 个；或用小得多的 M3U |
| 目录与电台内容权利不同 | 维护者拥有的汇集资料权利交付公共领域；流媒体、favicon与台方网页仍归电台，见§5 |
| 默认来源推翻“radio 默认无电台” | OWN-14 |

## 5. 许可

2026-11-04已核对官网前端原始模块中的数据声明：首页把**维护者自身拥有的汇集目录资料权利**交付公共领域，新增电台页也有对应的资料声明。这个声明不扩张到维护者不拥有的第三方权利；流媒体、favicon和电台主页内容仍归电台。它是官方的公共领域声明，**没有据此虚构CC0许可或声称所有电台内容可任意再分发**。

原始官方模块的URL、字节数、声明位置和SHA-256见[许可核对记录](../artifacts/app/APP-9/0.1.10/radio-browser-license.json)，在线数据声明已登记到`THIRD_PARTY_NOTICES.md`。没有下载或随APK打包整个数据库或电台媒体。服务端代码另为AGPL-3.0；本项目依据公开接口独立实现客户端，没有复制、运行或修改服务端实现，不能把服务端代码许可当作数据声明。

数据声明的查证已完成，默认来源及默认点击上报仍是OWN-14/15；其它公开前法律判断仍见OWN-12。官方要求播放点击上报，而当前开关默认关闭，这一隐私取舍没有因查到数据声明而自动解决。

## 6. 方案

- **阶段0原方案**：不改代码，`source`设为`https://all.api.radio-browser.info/m3u/stations/search?countrycode=DE&hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=200`。国家需手改地址；输出有`#RADIOBROWSERUUID`注释，但现有M3U解析器会忽略、不保留该UUID，因此该路径不能提供点击上报所需的UUID。主机回归已验证注释不会变成电台行；此独立M3U入口本轮未重验，不能用正式JSON目录的三格式播放替代。
- **阶段1已实现**：`plugins/radio/sources/radiobrowser.lua`提供JSON目录，设置含自动跟随已选引导地区、手动国家码与点击上报，以及下面两块检测设置。手动国家码为空表示全球；当前先用ISO文本输入，原生地区选择器接普通插件设置仍待通用契约。细节与验收边界见APP-9及§9。

### 官方HTTPS服务器池（0.1.13已安装）

- 仅`directory_url`去掉尾斜杠后精确等于`https://all.api.radio-browser.info`时启用。每次刷新调用一次公开API10 `tos.net.resolve("all.api.radio-browser.info")`；系统按默认DNS查地址、canonical PTR和正向确认，整体有界。它不授权TLS连接，Lua也不把数值地址拼成URL。
- 最多查看8条解析记录。只接收合法DNS名称、后缀严格为`.api.radio-browser.info`且不是`all.api.radio-browser.info`，拒绝userinfo、端口、路径、query、fragment、控制字符、字面IP、空或非法标签；去重后使用`https://名称`。没有合格候选就失败保缓存，不降到HTTP、不写死`de1`、不接客户端自选公共DNS、不关闭证书验证。
- 首次从随机位置轮转；上次成功的名称若仍在本次发现结果中则优先，只保存在当前插件会话。一次刷新全部标签共用这次发现和**最多8次HTTP尝试**，每个标签对同一候选最多一次，失败候选在本轮不再使用，成功节点优先服务后续标签。正常三标签共3次HTTP；网络失败、非200或畸形JSON可换节点。预算耗尽或任一标签失败时整次失败，不发布已取得的部分分类。
- 分页和缓存键仍使用用户配置的目录origin、国家和分类，切换节点不会产生新的缓存身份。自选HTTPS镜像及现已允许的本地HTTP镜像按原配置请求，绝不触发官方发现或改写地址。所有DNS/HTTP仍通过`tos.*`异步能力，JSON解析继续分片让出共享插件线程。
- 点击上报关闭时，不为上报启动DNS或HTTP；开启后仍仅在用户成功开始加载的独立托管回调中发现并使用合法HTTPS节点，失败可有界切换且不提示，不影响播放。关闭开关、停止插件或改来源后，迟到解析不继续发送请求。后台探测、重连、自动换台仍不上报。目录和点击默认值未变，OWN-14/15保留。

对应纯Lua回归已覆盖恶意PTR、无PTR、候选去重与随机起点、成功节点复用及失败切换、一次发现的三标签合并、全局8次尝试、完整缓存/收藏不变、取消后不发HTTP、自选镜像和关闭上报无额外通信。0.1.13的46项radio用例已统一通过；同APK真机发现了`de1.api.radio-browser.info`并以生产PluginHttp得到HTTP200/100行，生产Lua另有新鲜98台缓存和原生页面证据。该次只有一个合格候选，不把它计为真实多节点故障切换矩阵通过，详见§9。

### 失效电台

所有者 2026-11-03：连不上无所谓，失效的不放，或者自动换成下一台。同意，而且 `iptv` 已经有这套东西（`31` §5，`plugins/iptv/health.lua`），照搬：

- 不预先测整张表。`tos.media.probe` 一次最长 12 秒、同时最多两个，一张 200 个台的表全是慢失败时要 20 分钟，而且会对第三方服务器连续发请求。改成：目录标坏的不取；播放前先测没测过的那个台；后台每隔几秒测一个，当前列表前面的和收藏的先测。
- 失败的台暂停（10 分钟起，连续失败每次乘 3，最长一天，到期再测），暂停期间不出现在“全部”“分类”里。不删除：这只说明这台设备此刻连不上。
- 播放中失败，在“全部”“分类”里自动换到下一台，连续 5 台不行就停下提示；“收藏”里是用户点名的台，仍是断线重连。
- 连不上往往是整个主机的事（2 节里 4 个不通的主机，`stream.zeno.fm` 是托管平台，上面有大量台）。`dns`、`tls`、`network`、`timeout` 可以连带暂停同一主机的其余台，不用逐个测。这一点没验证。

### 分类

所有者 2026-11-03：要像电视那样，给它做一个大概的分类。`iptv` 的分组来自列表自带的 `group-title`；radio-browser 没有分类字段，只有任何人随便填的标签，不能直接拿来当分类。2026-11-03 点击过且未标坏的电台里，标签最多的是：

pop 6156、music 5028、news 3186、rock 3120、radio 2333、talk 1854、dance 1606、classical 1452、oldies 1433、jazz 1267、electronic 1035、christian 980、country 683、folk 657、metal 460、sports 411。

同一批数据里还有大量语种、地区、站名当标签：`entretenimiento` 2195、`estación` 2184、`méxico` 2051、`norteamérica` 1970、`moi merino` 1873、`música` 1803，同一个意思又有多种写法（`noticias` 607、`news talk` 478、`pop music` 1252、`música pop` 796）。所以：

- 分类由我们定：固定十来个，每个对应几个标签，含西语、法语等常见写法；名字 11 种语言。初稿：新闻谈话、流行、摇滚、古典、爵士蓝调、电子舞曲、乡村民谣、嘻哈、怀旧老歌、宗教、体育、本地社区。这份初稿凭上面的数字和常识定，没有对照数据验证。
- 请求方式：API 的 `tagList` 是“都包含”，没有“或”，所以一个分类要按标签各请求一次（`tag=…&tagExact=true`，加国家等条件），再按 `stationuuid` 合并。只在用户点开分类时请求，最多3个标签，正常3次HTTP；本轮服务器池故障切换使全刷新最多8次尝试，按分类完整缓存。
- 只在已取到的前 N 个里按标签归类不行：一个国家点击最多的 100 个里，爵士可能只有几个。
- 界面：标签“收藏”“全部”“分类”，和 `iptv` 的“分组”同一个做法；不显示数量，不加说明文字，不加图标（ADR-044）。
- 国家：自动模式订阅引导显式选定的地区；没有显式地区时为全球。手动模式的`country`为空也表示全球，不另加“国家”标签。0.1.14已通过公开choice/source=region接入同一离线地区选择器，支持本地化名称、搜索和“所有地区”空串；原字符串覆盖层不因显示而改写。

## 7. 没有验证的

- APP-9整项仍未完成：实际坏台暂停/到期再测、自动换台、收藏断线重连、后台探测误判率与同主机联动尚未完成真机矩阵。三格式播放用例关闭了探测，不把解码成功当作这些行为已验。
- 独立阶段0 M3U来源入口及其UUID注释处理未重新走真机链路；当前MP3、AAC、HLS证据均来自正式JSON目录。样本仍有限，长尾的HTTPS比例、编码、所有电台及全部HLS分支不由这三个成功样本代表。
- 本地分页、三标签OR/Back、国家/空提示、坏地址过滤、503保缓存和真实新进程离线恢复已过；仍未覆盖所有分类的设备行为或按最新公网页标签分布校调映射。0.1.14国家选择交互已真机通过，横屏IME/旋转后的系统栏遮挡仍待新版系统钩子验证，完整排版不算通过。
- 点击上报保持关闭。本地元数据矩阵的请求日志已证明无点击上报，但该轮没有播放；真实目录播放场景下关闭点击后的请求日志/TCP范围尚未完整验证，不能把前者扩大为后者的零请求证明。OWN-14/15默认值仍未决定。
- 官方节点HTTPS与公网页Lua解析已有成功证据，但本轮原生诊断只有一个合格节点，真实多节点故障切换仍未单独验。原生HTTP诊断未测实际连接peer、当前TLS根或版本，也没有服务端日志证明User-Agent已抵达。默认TLS与连接/读超时3000ms保持不变；性能未测试、继续后置。
- 0.1.14已把分类整次刷新（含失败尝试）限制为3次 `tos.http.get`，全部列表仍最多8次；耗尽预算不提交部分分类，保留完整旧缓存。生产Lua/JNI回归通过，真实公网故障切换矩阵未重跑。0.1.13使用8次的旧记录保留，不改写为已通过当前上限。

## 8. 来源

- [Radio Browser 总览](https://api.radio-browser.info/)
- [API 文档（de1）](https://de1.api.radio-browser.info/)
- [服务端 README（GitLab）](https://gitlab.com/radiobrowser/radiobrowser-api-rust/-/raw/master/README.md)
- [服务端 LICENSE（GitLab）](https://gitlab.com/radiobrowser/radiobrowser-api-rust/-/raw/master/LICENSE)
- [官网数据声明原始模块](https://www.radio-browser.info/chunk-XJBKC73J.js)
- [新增站点声明原始模块](https://www.radio-browser.info/chunk-FFUJJMXR.js)

## 9. 当前源码与待验

- `sources/radiobrowser.lua`：每次只解码 ≤256 KiB、≤100 行的 JSON 数组；解码前限制顶层对象数与嵌套深度，解析中分片让出共享线程。查询固定按点击量、`hidebroken`、HTTPS 条件排序，不提供全库关键词搜索。全部页显式“更多”，分类按最多三个独立标签请求、按 UUID 合并，总表 ≤200 台；本轮默认官方origin经一次DNS发现共用HTTPS池，全刷新最多8次HTTP，细节见§6，不读取favicon，不给媒体条目或image组件传封面地址。
- 三个标签为“收藏”“全部”“分类”。分类只在打开时请求；缓存按目录地址、地区和分类区分，最多四份、合计 ≤64 KiB，单页失败保留上次列表。缓存不足以完整存下过长地址时截短持久快照，不影响当次内存列表；收藏另存 UUID 与地址，≤50 台，目录离线仍可播放。收藏、目录缓存和健康账本还受宿主整个store外层JSON的256KiB总限制（包括内层字符串再次转义）；先缩减缓存和健康记录，保留已有合法收藏及未知旧键。宿主原子拒绝仍是最终字节判据；收藏写入失败回滚本次操作并显示“收藏保存失败”。
- `country_auto=true` 只订阅声明的 `welcome.setup/onboarding/1` 可选 `region`，不读 welcome 私有 store，不把系统 locale 推断成已选地区。没有显式地区就是全球；关闭自动地区后，ISO 国家码留空表示全球。普通插件设置从API10支持公开 `source=region`，0.1.14广播已接入，旧ISO字符串仍兼容，不另建设置存储。
- `test/test_gap/cooldown/recheck/failover_limit` 全部走 manifest 设置。前台先探测没测过或到期的目录台；后台单次一个，暂停到期优先，其次未测、过旧，休眠与当前正在播的地址不测。失败暂停为 10 分钟起、连续乘三、最长一天；`policy/unsupported` 仅记“暂时不能测”，不当坏台。健康记录按地址哈希，≤900 条/80 KiB。全部/分类的自动换台上限默认五个不同候选，可调低；收藏保留原台重连。同主机连带暂停是独立的 `host_cooldown` 开关，**默认关闭、仍待真机验证**，仅 DNS/TLS/network/timeout 可连带，HTTP/format 不连带。
- 目录来的探测与媒体条目都设置公开 API 10 的 `public_only=true`，收藏保留该限制；OS 通用媒体层负责 DNS、实际连接、重定向、HLS/DASH 及字幕子资源的公网 HTTPS 约束，Lua 的地址预筛不冒充 DNS 验证。旧 M3U/PLS 条目省略该字段，保留原媒体网络规则。
- 点击上报是独立开关，默认关闭。仅用户点选并成功开始加载后，在独立托管计时回调里发送一次；目录失败不提示，不影响播放。后台探测、自动换台与断线重连不发送点击上报。这与官方“每次点击上报”的要求存在未决取舍，OWN-15 未完成。

主agent已统一完成并安装0.1.13/1013：主包SHA-256为`3b86c9b2a1ecb5bcae8b314aaf3a1dfb1540bcb66579720bb672d09c57bbc80e`，测试包为`37b5684e1edcc9dd69cf60422df8660f4934ccb3adbb92bd54ee8c65ad72c3da`。939项JVM（1项旧skip，零失败/错误）、194项Python、252项Lua/JNI（radio46例）通过，debug构建/lint及设备测试包构建通过，仅armeabi-v7a。权威汇总见[0.1.13 qualification](../artifacts/app/APP-9/0.1.13/qualification.json)；整项`complete=false`，其未完成项不由三格式组合证据覆盖。

| 真机项目 | 已观察到的结果 | 边界与证据 |
|---|---|---|
| 本地目录矩阵 | 18项通过：100行去重99台、More至198台、三标签OR合并/Back、FR过滤与空提示、坏地址排除、503保缓存、真实新进程离线恢复 | [directory-local-1](../artifacts/app/APP-9/0.1.13/directory-local-1/results.json)`complete/restored=true`；此轮没有媒体播放，零点击日志限于元数据请求 |
| 原生DNS/HTTPS诊断 | 同APK的API27生产DNS得到官方节点`de1.api.radio-browser.info`，生产PluginHttp实测HTTP200、JSON100行，3项通过 | [radio-discovery-1](../artifacts/app/APP-9/0.1.13/radio-discovery-1/results.json)保持默认TLS及3000ms连接/读超时；未测实际peer或TLS根/版本，此诊断本身不代表Lua解析 |
| 生产Lua公网页 | 新鲜DE目录落盘98台，原生页面台名匹配，正式发现与JSON目录路径成功 | [public-playback-2](../artifacts/app/APP-9/0.1.13/public-playback-2/results.json)的元数据检查通过；该路径只接受HTTP200，因此页面/缓存可推断200，不能称其直接测得HTTP状态或TLS遥测 |
| 公网MP3与AAC | 原生点选台身份匹配；MP3由`OMX.google.mp3.decoder`以44.1kHz双声道解码、进度前进2310ms；AAC由`OMX.google.aac.decoder`以48kHz双声道解码、前进2263ms，均为progressive | [public-playback-2](../artifacts/app/APP-9/0.1.13/public-playback-2/results.json)两格式通过，但原整轮`complete=false`保留：HLS在播放前被过严的同名身份选择器拒绝 |
| 公网HLS | 后续仅HLS一轮按台名与来源host消除同名歧义；实际`source=hls`、AAC decoder、48kHz双声道、进度前进2402ms，原生台身份匹配 | [public-playback-hls-1](../artifacts/app/APP-9/0.1.13/public-playback-hls-1/results.json)`complete/restored=true`仅限该轮HLS；与上一行主包/测试包SHA一致，不改写原失败报告或声称单轮三格式全过 |

本地目录及两轮播放结束后，原设置/radio四文件及无关immutable文件按原字节恢复，原UI/page/variant/holds/方向、DO/admin/HOME/LockTask和五路音量0均核对；原生诊断也证明五个immutable文件、媒体与静音不变并完成恢复。三格式只组合各轮已验证的格式，`public-playback-2`的失败和更早[public-playback-1](../artifacts/app/APP-9/0.1.13/public-playback-1/results.json)目录失败仍保留。没有测试性能，没有启用点击上报，也没有把设置为false冒充播放时全TCP/请求日志证明。

历史记录保留：0.1.10[本地首屏](../artifacts/app/APP-9/0.1.10/directory-local-2/results.json)在找More时因输入监测停止，[恢复记录](../artifacts/app/APP-9/0.1.10/directory-local-2-recovery/results.json)没有抹掉未完成；[官方页面尝试](../artifacts/app/APP-9/0.1.10/directory-public-1/results.json)45秒未成功，[直接all的生产HTTP诊断](../artifacts/app/APP-9/0.1.10/radio-http-1/results.json)在约358ms得到TLS握手connection reset，不能归咎CA。这些历史失败不能覆盖0.1.13的新成功，也没有被改为通过。旧[公网策略本地验收](../artifacts/app/APP-9/0.1.10/media-public-policy/results.json)的7项、5次TCP正对照与私网拒绝零TCP仍限该规则矩阵，不扩大成所有公网重定向、HLS/DASH/字幕分支或播放时零点击证明。

`tools/tests/radio_test.lua`使用生产沙箱/JSON测试，`tools/tests/test_radio_directory.py`检查本地服务和静态契约。`tools/media/radio_browser.py`只在回环地址提供固定目录、分类、重复/坏地址、5xx、慢响应、超过1MiB与畸形响应；0.1.13本地请求日志覆盖分页、三标签、国家及503等本轮元数据用例，可核对关闭点击上报，不把主机的慢响应/超大体积等夹具能力全部计为真机已验。可显式提供本地静音MP3/AAC/TS样本，不下载或生成音频；没有样本就真实返回404。

**本地夹具与公网媒体证据各自独立。** 本轮正式JSON目录的公网MP3/AAC/HLS已分别真机播放，仍不替代独立M3U入口、实际健康/收藏重连/同host联动或全部分类验收。2026-11-04只读获取`/json/tags`现状响应未成功，不能声称分类映射已按最新标签分布调好；普通国家设置仍为ISO文本。剩余边界见§7，OWN-14/15未决，性能继续后置。

### 0.1.14增量检查点（2026-11-05）

已安装新包；公开地区选择器的DE显示、CN搜索/保存、所有地区空串、取消7项实机通过，横屏地区页有旧安卓导航栏遮挡，完整排版仍未通过。设置六分类图标与三种配色的中文横竖屏11项含恢复通过。947项JVM、199项Python、253项Lua/JNI与共享Go、lint/构建通过；分类3次限制是本轮生产Lua回归证据，0.1.13的本地18项与公网三格式不冒称已在0.1.14复验。原设置/UI/DO/HOME/LockTask/静音均恢复。权威范围及保留失败见[0.1.14 qualification](../artifacts/app/APP-9/0.1.14/qualification.json)。
