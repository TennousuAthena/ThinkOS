# REG-2b 独立应用构建

本构建只用于同源码、完整 Runtime 的功能验收，不是发行包。它运行普通 `ThinkApplication`、`MainActivity`、KioskService、RecoveryReceiver、真实 Repository 与 Lua VM；Android 为独立应用 ID `moe.kiwi.thinkos.regfixture` 分配 UID 和私有数据目录。没有 storage wrapper、路径租约或运行时替换根。

## 公钥材料与构建

主机 origin 夹具在仓库外的新 0700 目录持有本次临时私钥，另输出 `public/keys.json`。构建工具只读取公钥，不生成/读取私钥，不联网。公钥格式沿用 `UpdateKeyDocument`，没有额外顶层字段：

- `schema: 1`，`channel` 与 `registry` 各有 `active`、`backup`。
- 每项恰好 `id`、`algorithm: SHA256withECDSA`、`spki`，四把唯一的规范 P-256 SPKI。
- ID 精确为 `reg2b-<32位小写hex run_id>-channel-active`、`channel-backup`、`registry-active`、`registry-backup`；四项同 run_id 且各自匹配角色/槽位。
- 同次 origin 与 APK 必须使用同一份公钥原字节。私钥只留在主机私有临时目录，不能放入 APK、源码、CI 或日志。

输入是仓库外的绝对 `.json` 普通文件；最多 64 KiB，拒绝路径/父目录符号链接、重复 JSON 键、额外字段、非整数 schema、私钥 PEM、错角色/run_id、重复 key、非规范 DER/Base64 或不在 P-256 曲线上的点。原字节只复制到 `android/app/build/generated/regFixture-assets/update/keys.json`，不重排 JSON。

```bash
cd <repository>/android
./gradlew -PregistryFixtureProbe=true \
  -PregistryFixturePublic=/private/tmp/THIS-RUN/public/keys.json \
  assembleRegFixture assembleRegFixtureAndroidTest
```

替换真实公钥路径。`registryFixtureProbe=true` 选择 `regFixture` 为 testBuildType，并复用现有 `UpdateInstrumentation`；不新增 runner。debug/release 的默认任务与公钥资产路径不变。新 buildType 显式继承 debug 的 Java、res、Java resources 和 assets；manifest 镜像 debug 诊断组件声明，并只为 Main 提供 LAUNCHER，不提供 HOME。BootReceiver 被禁用；普通 Kiosk/Alarm 冷 PID 恢复保留，但据此不验真实硬件开机。

检查构建输出的 metadata，不凭文件名安装：主包应用 ID 必须是 `moe.kiwi.thinkos.regfixture`，测试包目标及 runner 必须匹配；minSdk/targetSdk 27、仅 armv7、DEBUGGABLE；解出 APK 的 `assets/update/keys.json` 与输入逐字节相同且四 ID 同本次 run_id；Main 不得注册 HOME，普通 Main/Kiosk/Recovery 类名仍为原 namespace。不要覆盖安装原 `moe.kiwi.thinkos`。此页没有执行安装命令。

普通构建也检查 `src/main`、`src/debug`、`src/release` 的三个固定 trust JSON 文件，拒绝保留命名空间 `reg2b-` 混入。不得把 fixture 公钥手工复制到这些资产；只有新 buildType 引用 dedicated generated directory。

## 运行与验收界线

- origin 在主机独立存活，不能依赖目标 PID 内的签名服务器；APK 固定公钥保证普通新 PID 可重验同次缓存/ledger。冷启动不依赖 RAM override，私钥不跟随设备。
- 夹具自己的初始 done、目录来源/community/auto 策略与关闭的 cast 开关通过正常 SettingsStore/公开设置准备；不改变生产文件位置。保持真实 600 秒，不缩短 schema 值，不用窄组件或假 VM 替代完整 Runtime。
- 原 ThinkOS 可以经真实设置临时将 `apps.return_after_s` 设为 0，再从公开应用网格打开“ThinkOS REG 验收”；结束经公开设置语义还原。原包继续是 HOME/Device Owner。冷 PID 期间若原桌面恢复并重新锁定，记录实际行为，只经公开路径重进，不抢未知前台。
- 旧设备工具中 `APP`、组件及 `run-as` 存在正式包硬编码。此构建不修改那些工具，也不授权把旧 `RegistryProbe`/rawrestore 不审查地直接用于新包；root 先整合专用 fixture driver/phase。所有修改和清理必须限新应用 UID/包名，不清原 registry/floor/native 历史。
- 固定 root peer 仍只认正式包，新包没有其 Device Owner/root/全局钩子资格。不改系统白名单、不设置 fixture 为 HOME、不启用其设备管理。投屏监听默认关闭，避免两份应用争用服务；原 Runtime 正常缓存变化与 fixture 跨域写入分开记录。
- 可验同源码独立包的 Hub/同意/权限/恶意输入/吊销/安全模式/出厂恢复、真实 VM 与普通冷 PID。不能据此宣称正式包系统集成、硬件启动、root 模块或性能通过；双 Runtime 下的性能数据不能充作产品指标。
- 完成或失败后只处理已核实的 fixture 包和本次主机私有材料，不把原包设置/ledger 用原始文件覆盖回去。真实用户输入、声音或未知前台出现时停止。

本补丁只提供构建隔离层和输入校验。没有执行构建、测试、ADB、安装或设备状态更改；真实 REG-2b 验收须另有证据。
