# APP-9 最小本地目录验收

驱动为 `tools/tests/radio_directory_device.py`。本轮只写源码，尚未运行；主 agent 单独执行。正式数据许可已核实并由主 agent 登记，本计划不重新推断许可。

## 运行前

- 已安装待验主 APK 与测试 APK，不由此脚本构建或安装。两个完整 SHA-256 **都必须显式传入**；可另传本地 APK 路径作只读交叉核验，脚本不内置候选哈希。
- 仅 HUA0BWUT，ThinkOS MainActivity 已前台，原语言为中文，五音频流均为0；保持已有 DO、自身 admin、HOME、LockTask。没有媒体/投屏队列、返回栈、控制栏、引导、更新事务或法律窗口。
- 最小夹具要求原 radio 目录关闭、M3U/PLS 与直连源均为空，避免分步改设置时请求旧公网源。允许现有收藏、健康记录、缓存与未知 radio 键；不清空它们。
- 现有 source-addressed ThinkHierarchy jar 已缓存，也可用 `--hierarchy-jar` 指定。脚本只运行既有 jar，不编译，不启动 app instrumentation，所以没有 instrumentation runner 前置。

```sh
python3 -B tools/tests/radio_directory_device.py \
  --apk-sha256 '<主APK完整SHA256>' \
  --test-apk-sha256 '<测试APK完整SHA256>' \
  --apk /owner/path/app-debug.apk \
  --test-apk /owner/path/app-debug-androidTest.apk \
  --output /tmp/thinkos-radio-directory-result
```

`--device-port` 默认8894；如果已有 reverse 占用便拒绝，不覆盖。源站仅监听主机127.0.0.1随机端口，设备仅增加本轮的一条 reverse。复用 `tools/media/radio_browser.py` 的固定 `RadioBrowserFixture`，不代理或请求任何公网服务，不提供音频，不改既有夹具源码。

## 实际闭环

1. 经现有 `DebugReceiver.setting` 的真实 SettingsStore handler 设置临时 `test=false/click=false/country_auto=false/country=CN/directory_url=loopback/directory=true`。不注入 Lua state、store 或目录记录。
2. 实际横滑到应用磁贴页，触屏点“广播”。100行含一个重复 UUID，真实 Lua 去重后落盘99台；实际滚动并点“更多”，观察 offset100 和合并198台。只点明确的标签、分类、刷新及更多，不点任何电台行。
3. 实际点“分类/新闻谈话”：三个独立 `tagExact` 请求（news、noticias、actualités），相同 UUID 合并100台。真实 Back 后必须看到流行、摇滚等分类行，仍在广播，再点全部。
4. 改国家FR，夹具故意仍回CN，真实 Lua 应过滤为空；改回CN使用坏地址场景，HTTP、私网、本地主机、凭据、fragment都不出现，只余固定合法HTTPS样本。
5. 恢复正常目录100台并等待真实合并写盘。源站改503，实际点刷新，列表与已落盘缓存保留。请求日志核对 User-Agent、安全查询参数和零 `/json/url/` 请求。
6. 关闭本轮主机源站，保留同一reverse，实际 force-stop/启动应用并确认新PID；重新触屏打开广播，看到真实Lua从原落盘缓存恢复。再实际点刷新，连接失败提示出现而缓存不变。这里是**应用进程重启**，没有重启设备。
7. 严格校验并恢复原设置/radio四文件，移除本轮reverse及本token的精确helper/oat目录，恢复原page/variant/holds/方向；再次核对真实DO/HOME/LockTask和五静音。成功后结果仍 `full_APP9_complete=false`。

## 恢复和停止边界

- 原设置/radio `.json` 与 `.bak` 仅四个固定路径可写回；原始字节及日志保存在0700私有目录，文件0600，不复制到公开 artifacts。原本有 AtomicFile backup 则不开始。
- server设置、welcome、cast身份、管理退出状态严格比较字节，绝不写回；deck/recovery XML只做私有审计和键值语义校验，**不回滚历史或自然序列化状态**。无更新/设备重启，本轮不允许恢复崩溃历史变化；deck仅允许原页面记忆恢复为原variant，其余原键保持。
- SettingsStore新文档必须等于本轮声明的前/后模型，其余scope和radio字段不能变。radio只允许已知夹具地址/国家/分类的缓存变化；收藏、健康、未知键必须保留。发现未知内容不会为了通过而覆盖它。
- TouchWatch监控真实硬件输入；Guard检查前台和五静音。人触、前台丢失、活动媒体出现时停止，不发Home、不自动重获屏幕，不强行恢复。私有 journal 保留。普通失败也只有在字节/语义仍可证明属于本轮时才恢复。
- 恢复目录校验所有者、0700/0600、快照摘要、固定路径与双APK哈希。中断后先人工查看私有记录，设备空闲并回到ThinkOS再显式恢复：

```sh
python3 -B tools/tests/radio_directory_device.py \
  --restore /tmp/thinkos-radio-directory-recovery-实际目录 \
  --apk-sha256 '<同一候选主APK完整SHA256>' \
  --test-apk-sha256 '<同一候选测试APK完整SHA256>' \
  --output /tmp/thinkos-radio-directory-recovery-result
```

脚本保留恢复journal作为审计，不删除其它插件目录、数据或历史。helper只接受 hierarchy.jar 和Android8.1的 `oat/arm/{hierarchy.odex,hierarchy.vdex}` 普通文件布局，无symlink，jar摘要须匹配才删除该token目录；未知内容保留。

## 明确未验

- 本地假目录的公开域名是占位，**没有发起媒体load**，不能算 `public_only` 公网播放通过。零点击上报只覆盖本轮目录操作，不替代关闭上报时真实播放的验证。
- 严格公网 MP3/AAC/HLS、官方 API TLS/读超时、健康探测/主机联动/收藏重连矩阵、全部分类和长期运行留给后续单独验收。
- 不安装 APK/模块，不设置或清除DO，不设备重启，不调健康阈值，不做性能测试，不实现Server。
