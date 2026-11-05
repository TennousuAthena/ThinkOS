# 更新文档契约（schema 1）

`updater-status.json`是抽象root socket协议1的有界本地STATUS结果，不是Server或网络目录。客户端要求root peer、准确JSON、唯一模块id与4096字节上限；旧模块可省略`setup`，客户端据此禁用配置能力。`setup`的时区/语言能力不依赖正式分发keys，`device_owner`固定false，不能用来宣称已开启设备管理。仅契约生成，不在Server实现业务。

`catalog.json`是OTA通道文档，按`docs/34-ota-and-plugin-registry.md`§4.2；签名是原始UTF-8字节的SHA256withECDSA（P-256、ASN.1 DER），相邻路径追加`.sig`，保留原查询参数。先验签再读JSON。整数必须使用精确十进制整数token，不用小数或指数；重复键、非法UTF-8、未配对代理字符和JSON扩展语法均拒绝。APK/模块大小上限和数组/字符串边界在schema中。

`catalog.json`的可选`mirrors`（2026-11-05）是同一通道目录的更多地址（每个旁边有`.sig`，最多8个、互不相同、路径以`/<通道>.json`结尾，URL规则同下载地址）。设备只从**最新的已验签目录**取这份列表（读认证缓存，不另存），排在已配置的来源之后、总数不超过8个；因此发布时就能增加或更换镜像，不必先发新APK。每个来源的响应仍要通过通道钥匙验签和序号下限，列表本身不是信任依据。

`keys.json`仅描述APK内`assets/update/keys.json`：通道与仓库各有active和backup。SPKI是标准Base64的P-256 DER公钥，四个ID和公钥必须不同。`debug-keys.json`是额外测试公钥，只能放在debug源集；release加载路径不读取它。当前正式公钥尚未由所有者提供，仓库没有放占位或测试钥匙充作正式根。缺失时更新不能验证，应用其余功能仍应正常运行。

`release.api`表示新APK所提供的插件API，不是旧运行时阻止升级的上限；设备/SDK匹配、versionCode更大、APK签名者与已安装包一致分别检查。`version`按主×1000000＋次×1000＋修订计算`code`，次/修订0–999，结果限制在Android的正Int范围。发布与过期时间顺序要合法；实际过期只提示，时间不可信时不看过期。模块ID使用§4.5的七项白名单（含thinkos-shell、thinkos-certs、thinkos-power）；只有thinkos-immersive是vector，其余是magisk；模块系统签名仍由将来的root更新器独立验证。

`sequences.json`是设备私有落盘格式，不是Server接口。stable/beta分别保存最大已验证seq和原始文档SHA-256；同seq只接受相同原始内容，不要求随机ECDSA签名字节相同。实际文件至少包含一个通道记录；空记录/损坏文件不能解释成初始状态。只在文件完全不存在时视作初次使用。文件锁、同目录原子改名、数据及目录同步由设备实现。

`cache.json`是设备私有的认证目录缓存；原始目录和签名分别用规范Base64保留，在同一文件中原子提交。读缓存重新验证当前APK钥匙、目录schema与独立序号floor；source/ETag只是条件请求提示，不是信任依据。只有同一来源对应的已认证缓存才能使用If-None-Match/304；网络/签名失败可保留仍匹配floor的缓存，损坏缓存不能证明304有效。缓存文件上限2MiB、每个通道一个；没有把私有格式变成Server接口。

`state.json`是工作流检查点：IDLE、AVAILABLE、DOWNLOADING、READY、INSTALLING、FAILED以及有界进度、目录摘要/seq。安装事务权限属于独立的`pending.json`/`UpdateGuard`；磁盘READY只提示恢复器重验缓存签名和真实APK。部分下载恢复为AVAILABLE，INSTALLING只有与本机待处理事务匹配时才保留，否则报告中断，不自动恢复READY。就绪等待优先用相同boot的单调时间，跨boot只用可信墙钟，否则从0保守计时。损坏进度只能凭当前认证目录重建成AVAILABLE；不降低独立floor。

`pending.json`是设备私有APK事务：固定的from/to身份（版本、code、摘要、大小、单一签名者），目录摘要/seq、安装方式、阶段、唯一安装/回滚会话ID、启动计数、每进程试用代号和单调开始时间。旧APK完全复制、校验并同步为`previous.apk`后才能写事务；同步好的平台会话ID落盘后才提交。阶段为PREPARED、INSTALLING、TRIAL、ROLLING_BACK，备份路径固定，不从JSON接收路径。启动计数在主进程初始化的第一行同步提交，回执辅助进程不计数。健康需实际首帧、目录发布以及之后完整的连续试用期；旧进程的证明不能给新进程认证。所有时长/次数从设置schema读取，开始事务时固定该次策略；默认10分钟、允许3次启动。

`install-history.json`保存最近64个版本的失败统计与一次更新/回滚结果；`install-failure.json`定义每个版本独立的`failure-<code>.json`，旧黑名单不会随最近列表淘汰。每个事务只计一次失败，连续普通安装失败达到默认3次只阻止自动重试，显式手动重试仍可用；崩溃回滚或回滚失败是硬黑名单，也阻止下载。终态历史先同步再删除pending和备份，重启会补完中断的清理。健康结果的恢复确认与现有recovery偏好中的事务代号配合，避免一次确认写失败后，旧健康结果误清除后来无关的安全模式。三个私有JSON记录不含路径、信任钥匙或可执行命令，不是Server接口。

运行标准生成入口：

```sh
python3 shared/gen/gen_kotlin.py
python3 shared/gen/gen_go.py
```

分别生成`update/UpdateModels.kt`和`server/internal/protocol/update_generated.go`；后者只有契约结构，没有Server业务实现。Kotlin模型的读写与校验词汇均从这些schema生成/驱动；不手写DTO。这是独立的文件协议，版本由文档`schema`字段管理，没有改实时通道`protocol/VERSION`。文件协议的`schema`仍为1；本地`tos.update`接口另按插件API10管理。

`status.json` 是 API10 的只读本地契约，原生恢复界面也使用它，不是 Server HTTP 接口。新候选版本与说明只能来自与工作流摘要匹配的已认证 Offer，语言沿 LocaleChain 回退；只有已认证的版本不更新条件才显示 up_to_date，空状态不能冒充“最新”。不输出路径、下载地址、信任材料或设备标识。components只列签名目录要求的候选组件，当前状态requires_updater，不声称它们已安装。`can_install`需要已认证且原生重验的READY、已接入的平台安装器、无待处理事务、无媒体/投屏/外部前台；有模块候选时仍需后续模块更新器。正式钥匙缺失时分发操作仍不可用。

可选`result`独立于目录：只从本机TRIAL事务或终态安装历史投影，含kind、目标版本/code、有界本地化message、纯文本notes和reason。下载/READY不能冒充安装完成；旧的健康结果也不能替另一已安装版本报更新成功。试用期可立即显示已安装的新版本，健康后由终态记录接续；默认保留一天，同boot使用单调时钟、跨boot只使用已知可信的墙钟，否则不虚构年龄。结果观察器不依赖分发钥匙或插件，所以安全模式和未配置来源时仍可显示本机结果。可选`silent_install`只表示当前静默安装能力，不等于目录已就绪或系统组件已安装。

自动安装在设备运行期接入同一纯Kotlin策略，设置与触摸由实际Main事件提供，媒体/投屏由MediaEngine提供，原生窗口和外部前台也纳入门禁。只有等待安装的READY/INSTALLING才定期读可用空间与事务；初次分发未配置时不创建更新目录。自动下载开关不控制缓存包的自动安装，后者也不依赖网络。进程重启不会凭空继承十分钟无触摸或一小时连续休眠。提交前重新读空间、失败计数、窗口、媒体、触摸和静默能力；提交后不因断网取消或重复提交。不具备静默能力时自动提交关闭，手动仍可进入系统确认。
