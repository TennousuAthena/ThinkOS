# 41 · 系统根证书更新

> 最新生效状态（2026-11-04）：所有者明确授权Agent执行本次CA持久安装及重启，无需人工点屏幕。已由Agent用Magisk安装add、重启（boot_count27）、验证205张及三个HTTPS预期根，再安装replace、重启（boot_count28）。当前系统文件和应用AndroidCAStore均为117张，排序指纹摘要`cef81225…0180f`，ISRG X1、ISRG X2、GTS R4三站点均返回200/TLS1.2并验证到预期根。CA专属开关已在Magisk实际关闭再开启证明可撤销，其余模块未更改；五路音量0且启动动画声音关闭。最终证据 `artifacts/certs/CERT-3/activation-20261104/`。public-data/radio/player的完整回归尚未补齐，不把三站点成功当作所有业务站点验收。下方“未安装”“须人工”是此前调查/执行边界，本次已获明确覆盖授权。

> 安装前真机基线（2026-11-04早期，已由上方生效状态替代）：三个CCADB登记的测试站点TCP443均可达；ISRG X1的原生TLS验证到预期根，ISRG X2与GTS R4均报Trust anchor for certification path not found。已在官方演示站点复现，尚未证明日常业务站点受影响。当时模块尚未安装。站点/根指纹固定在tools/tests/fixtures/certs-tls/sites.json，设备工具certs_tls_device.py。原始记录artifacts/certs/CERT-3/tls-baseline-20261104/。下面“没有复现”“站点未查”为早期调查状态。

> 2026-11-04实现检查点：生成工具、117张证书、可复现add/replace模块和主机16项检查已完成。真机测试APK已用原生CertificateFactory解析、自签名校验全部117张单段PEM；实际系统文件和AndroidCAStore仍是旧135张，指纹摘要核对一致。E0撤销演练、人工安装/重启、E1/E2挂载和公网握手未做。add已处理两处同主题不同DER的旧库文件名冲突，用连续.1保留旧.0，安装前核对基线；细节见system/README.md与artifacts/certs/。下面的调查结论与“未验证”以此检查点修订。

> 2026-11-03。所有者：设备上的 CA 证书是很老的一套，想更新成最新的，要一份计划。本文记录实测、选择、方案、验证步骤和没验证的；任务是 `36` 号文档的 CERT-1 到 CERT-5（CERT-6 已取消）。所有者 2026-11-03 已定：OWN-17 整个换成 Mozilla 的 117 张，OWN-18 ThinkOS 不自带信任根。开发和测试由另一位 Agent 做，本文只管决策和文档。
> 状态：只做了只读调查（把 `/system/etc/security/cacerts` 拉到 Mac 上分析），代码没动，设备没改。证据在 `artifacts/certs/CERT-0/`。同一天又上网核对了一轮（Magisk 官方文档、Conscrypt 8.1 源码、Mozilla 的 `certdata.txt`、AOSP 提交日期、curl 的说明页），结果在 §3、§4、§7；**其中一处更正了我上一版的判断：Mozilla 的列表里有一个带“某日期之后不信任”的根在我们钉住的包里（Izenpe.com）**。

## 1. 结论

做一个 Magisk 模块 `thinkos-certs`，用 Magisk 的无痕覆盖把 `/system/etc/security/cacerts` 换成 Mozilla 当前的根证书列表。来源用 OTA-2 已经钉住的 curl 2026-10-20 包（118 张；去掉带 distrust-after 日期的 Izenpe 后 117 张，见 §3）。不写 `/system` 分区，符合 `AGENTS.md`。分工：生成工具、模块、测试归 Agent；装模块和重启归人工（`system/install-certs.sh`）。

列表会过期，Mozilla 的更新比 ROM 勤得多。所以模块要能走 `34` 号文档的签名 OTA：第一次人工装，之后由更新器装（MOD-1 做完以后；白名单要提前加，见 CERT-4）。

## 2. 设备现状（HUA0BWUT，2026-11-03，只读）

| 项 | 值 |
|---|---|
| 位置、格式 | `/system/etc/security/cacerts`，135 个文件，名字是旧式主题哈希加 `.0`。**我自己算的旧式哈希（主题 DER 的 MD5，取前 4 字节按小端写成 8 位十六进制）和 135 个文件名全部一致**，没有 `.1` 后缀的 |
| 年纪 | 最新的一张根 notBefore 是 2015-10（SZAFIR ROOT CA2），2016 年以后签发的根一张也没有。ROM 是 Lineage 15.1 2024-05-31 构建，安全补丁 2021-10-05，库是冻结的 |
| 过期 | 21 张已过期，含 Baltimore CyberTrust Root（2025-05）、DST Root CA X3（2021-09）、GlobalSign Root CA - R2（2021-12） |
| 对照 Mozilla 2026-10-20（118 张） | 共有 48 张；**设备缺 70 张**；设备多出 87 张（21 张过期，66 张有效但 Mozilla 已经移除，如 VeriSign、thawte、GeoTrust 的老根） |
| 缺的 70 张里 | 主流 CA 现在在用的新根：ISRG Root X2，GTS Root R1/R3/R4，DigiCert TLS RSA4096 / ECC P384 Root G5，Microsoft RSA / ECC Root 2017，GlobalSign Root R46/E46，Sectigo Public Server Authentication Root R46/E46，SSL.com TLS 2022 两张，Certainly R1/E1，HARICA TLS 2021 两张，Telia v3 两张，Certum Trusted Root CA 等；还有中国大陆、香港、台湾的：TrustAsia（4 张）、BJCA（2）、UCA（2）、GDCA、vTrus（2）、Hongkong Post Root CA 3、TWCA CYBER、HiPKI。全表在 `artifacts/certs/CERT-0/diff-vs-mozilla-2026-10-20.txt` |
| 最近到期的有效根 | Entrust Root Certification Authority 2026-11-27，Certum CA 2027-06-11，Certigna 2027-06-29 |
| 用户证书 | 没有用：ThinkOS 的 `network_security_config.xml` 只信 `system`，应用目标 API 24+ 也默认不信用户证书 |
| Magisk | 27.0；魔法挂载在这台设备上确实在工作（`/system/xbin`、`/system/lib/libzygisk.so` 都是 tmpfs 覆盖，`mount` 里看到的） |

**为什么现在没有出问题**：已经看到的网站还连得到设备上有的老根。radio-browser 的链是 `*.radio-browser.info` ← Let’s Encrypt YR2 ← Root YR ← ISRG Root X1（服务器带上的交叉签名），Bing 走 DigiCert G2/G3。这些是推断，**没有在设备上复现过任何一次证书失败**。会出问题的是：某个 CA 换到新根、又不再提供交叉签名的时候，设备就连不上那个站点，且没有任何办法从应用里补救。这是预防，不是在救火。

## 3. 选择

| 办法 | 结论 |
|---|---|
| Mozilla 的列表，经 curl 的 `cacert.pem` | **采用**。OTA-2 已经钉住 2026-10-20 的这份：SHA-256 `796f1389…3cbe`，118 张，MPL-2.0，来源和每张的指纹在 `android/app/src/main/resources/moe/kiwi/thinkos/update/roots-source.json`；已有生成工具先例 `tools/vendor_update_roots.py` |
| AOSP 的 `system/ca-certificates` | 不用：最新一次更新是 2025-02-10（提交 `45c7f19`，用 googlesource 的 JSON 取到的绝对日期），比今天早约 21 个月，比 Mozilla 当前的旧。新版安卓改由 Google 在线更新根证书（Esper 的文章说从 Android 14 起），8.1 没有这条路 |
| 直接写 `/system` | 不行，`AGENTS.md` 禁止（remount、写系统分区） |
| 装用户证书 | 不行：对目标 API 24+ 的应用无效，ThinkOS 的配置也只信系统证书；而且是一张一张装 |
| 只在 ThinkOS 里自带（NSC 的 `@raw`） | 只救 ThinkOS，设备上别的应用不救。**所有者 2026-11-03 定为不需要（OWN-18）**，CERT-6 取消 |
| 社区里现成的同类 Magisk 模块 | 没有评估。自己做可以钉来源和哈希，能进更新器白名单，和 `thinkos-cast` 是同一套做法 |

**curl 的包没有处理“某日期之后不信任”，要自己处理（上一版我写“看来已经筛过”，是错的）**：

- 读了 curl 的生成脚本 `mk-ca-bundle.pl`（1.35）：默认只收服务器信任是 `TRUSTED_DELEGATOR` 的根；脚本里没有任何一处处理 `CKA_NSS_SERVER_DISTRUST_AFTER`。
- 取了 Mozilla 的 `certdata.txt`（firefox release 分支，2026-11-03；SHA-256 `beb7e6df…63eb`，**和 2026-10-20 的包头里写的 `SHA256:` 完全一致**，所以就是这份包转换自的那个文件）。172 个证书对象里 4 个带服务器 distrust-after 日期：Entrust.net Premium 2048 Secure Server CA、Entrust Root Certification Authority - G2、- EC1（都是 2024-11-30，信任级别已经是 `MUST_VERIFY_TRUST`，所以 curl 的包里没有它们，包里找不到 Entrust 是这个原因）；**Izenpe.com（2026-04-15，信任级别仍是 `TRUSTED_DELEGATOR`，在我们钉住的 118 张里）**。
- 影响：Mozilla 不再信任 Izenpe 在 2026-04-15 之后签发的服务器证书，Android 的信任库没法表达“某日期之后”。办法是生成工具同时读 `certdata.txt`，带日期的根一律不收（CERT-1）。排除以后是 117 张。代价：Izenpe 在 2026-04-15 以前签发、现在还有效的证书会验不过，最晚约到 2027-05 它们都到期（证书有效期上限约 398 天，这是我的推算）。设备现在有这张根，所以对设备是一个变化；Izenpe 是西班牙巴斯克政府的 CA，这台设备上用到的可能性很小。
- 另外两点没解决：curl 的说明页写明 Firefox 对一些 CA 的域名限制**不会**带进 cacert.pem（具体哪些根没查）；certdata 里有 3 张 emSign 根（C1、C3、G3）服务器信任是 `TRUSTED_DELEGATOR`，却不在 curl 的包里，原因没查到。
- 许可：curl 的页面写明“The PEM file is only a converted version of the original one and thus it is licensed under the same license as the Mozilla source file: MPL 2.0”，和 `roots-source.json` 登记的一致。包每天检查一次更新，页面上最新的 Mozilla 更新时间是 2026-10-20，所以我们钉的就是当前最新的。

## 4. 方案

### 4.1 生成（CERT-1）

`tools/vendor_system_cacerts.py <cacert.pem> --sha256 <钉住的值> --certdata <certdata.txt> --retrieved-on <取得日期>`：不联网、不碰私钥、不碰设备，照 `tools/vendor_update_roots.py`。

- 校验输入的 SHA-256，解析每一张，按旧式主题哈希命名，同哈希的依次 `.0`、`.1`（当前没有冲突）。**编号必须连续**：Conscrypt 的 `findCert` 从 0 往上找，遇到第一个不存在的文件就停；长度为 0 的文件被当成“已删除”跳过。哈希就是 Conscrypt 里的 `X509_NAME_hash_old`。
- 同时读 Mozilla 的 `certdata.txt`（它的 SHA-256 要和包头里写的 `SHA256:` 对上）：证书对象上带服务器 `CKA_NSS_SERVER_DISTRUST_AFTER` 日期的，或服务器信任不是 `TRUSTED_DELEGATOR` 的，一律不收，并在输出里列出。curl 的脚本不处理 distrust-after，所以这一步不能省。`certs-source.json` 里记下 `certdata.txt` 的来源和 SHA-256。
- 输出 `system/thinkos-certs/system/etc/security/cacerts/<哈希>.<n>` 和 `system/thinkos-certs/certs-source.json`（来源地址、SHA-256、取得日期、每张的 DER SHA-256 和文件名）。
- 文件里只放一段 PEM。Conscrypt 8.1 的源码（`TrustedCertificateStore.readCertificate`）用 `CertificateFactory.getInstance("X509").generateCertificate(BufferedInputStream)` 读，PEM 和 DER 都行（用户证书就是以 DER 写入的），设备原有文件在 PEM 后面的文字说明是被忽略的。所以只放 PEM 应该能读；**设备上没跑过**，单机验证的第一步验它。
- 同样的输入出同样的字节（排序、固定权限）。提交的是 `.0` 文件和 JSON，不是 `*.pem`，不受 `.gitignore` 里 `*.pem` 的影响。
- 刷新：下载新的 `cacert-<日期>.pem` 和对应的 `certdata.txt`（curl 每天检查一次，Mozilla 改了才出新包），喂给工具；工具打印和上一版的差（加了谁、删了谁）、被 distrust-after 排除的根、一年内到期的根，所有者看过再发。

### 4.2 模块（CERT-2）

```
system/thinkos-certs/
  module.prop        id thinkos-certs；version = 取包日期，如 v2026.10.20；versionCode 20261020
  customize.sh       安装检查：API 27+；数量、文件名、PEM 头都对才装，否则 abort
  certs-source.json
  LICENSE-MPL-2.0.txt
  META-INF/          Magisk 模块包的标准安装器（同 thinkos-cast）
  system/etc/security/cacerts/*
system/build-certs.sh [--mode add|replace]
system/install-certs.sh [序列号] [--mode ...]    人工执行
system/test/run-certs.sh                         主机测试
```

没有可执行文件，没有 sepolicy 规则，不需要 armv7 检查。replace 模式在 `cacerts` 目录里放一个空文件 `.replace`（Magisk 的整目录替换标记）；add 模式不放，Magisk 把新文件合并进原目录。
**Magisk 官方文档核对过的**（[Module guide](https://topjohnwu.github.io/Magisk/guides.html)）：模块的 `system` 目录会“递归合并进真实 `/system`”，新文件会被加进去、同名文件被替换；目录里放 `.replace` 则整个目录替换，不合并；模块文件默认上下文是 `u:object_r:system_file:s0`，和设备上原有证书文件的标签完全一样（`ls -Z` 看到的）；`REMOVE` 是在模块里放 `mknod c 0 0` 的哑设备，不用它，因为 replace 模式整个目录都换了。社区里已有同样做法的先例：`UpdateAndroidTruststore` 用 Magisk 覆盖 `/system/etc/security/cacerts`，在 Android 7.0 上试过，README 注明“只在信任库位于 `/system/etc/security/cacerts` 时有效”（它的来源是 AOSP，我们用 Mozilla）。这些是文档和别人的结果，**在这台设备上还没试过**。

**魔法挂载失败时会怎样**（推理，没验证）：模块的覆盖没挂上，设备就还是原来的库，等于没装。真正的风险是挂上了、内容却是坏的，所以检查放在主机测试和 `customize.sh` 里。

### 4.3 加还是换（OWN-17，所有者 2026-11-03 定：换）

**已定：终态是整个换成 117 张。**换掉的包括 66 张 Mozilla 已移除的有效根、21 张过期根和 Izenpe；接受“只靠这些根的老站点会连不上”的风险（浏览器里也一样）。下面的表是当时的比较。验证分两步仍是推荐做法（先只加，确认机制，再换），不是必须：只想一步到位也可以，代价是“换”出问题时分不清是机制还是内容。

| | 只加 | 整个换成 Mozilla |
|---|---|---|
| 结果 | 135 + 70 = 205 张 | 117 张（Mozilla 的 118 张去掉 Izenpe） |
| 过期的 21 张 | 留着，而且**仍然被完全信任** | 去掉。过期不会让它们失效：Conscrypt 8.1 的 `TrustManagerImpl` 只对服务器发来的中间证书调用 `checkValidity()`，信任锚直接交给 PKIX，不查有效期（Scott Helme 的文章也写：Android 不检查根证书的有效期）。所以这 21 张不是无害的，是一直被信任着（上一版我写“无害”，不对） |
| Mozilla 已经移除的 66 张有效根 | 留着，继续信任浏览器已经不信的 CA | 去掉 |
| 风险 | 小：现有的一张不少 | 只靠这些根的老站点会连不上，浏览器里也一样 |

推荐**换**，但分两步：先装“只加”的包（单机验证 E1），确认模块机制、Conscrypt 读新文件、握手这三件事都没问题；再装“换”的包（E2）。两步各一次重启（冷启动 60–90 秒，adb 回来 1–3 分钟），成本低；这样“换”这一步出问题时，已知机制是好的。

### 4.4 许可（CERT-5）

证书数据是 MPL-2.0（curl 的 CA Extract 页和包头都写了），许可证全文已经在仓库（`LICENSE-MPL-2.0.txt`）。目前 `THIRD_PARTY_NOTICES.md` 和 `07` 号文档只写到“OTA 用的 16 张子集”，改成包含系统模块的整套 117 张；模块包里带许可证全文和来源。法律上的判断归 `36` 号文档的 OWN-12，不在这里下结论。

## 5. 验证

### 主机（CERT-1、CERT-2，不要设备）

- 重复生成，字节相同。
- 117 张全部能被 `openssl x509` 解析，无重复、无过期、都是 CA、都是自签名、没有 distrust-after 日期；Izenpe 不在里面；同哈希的证书编号连续（用一对人造的同主题证书测）。
- **48张共有根的旧式文件名算法全部匹配；最终输出保留其中47张（Izenpe排除）**（用 `artifacts/certs/CERT-0/device-store-2026-11-03.tsv` 当夹具），这直接证明命名算法对。
- 必须有（ISRG X1/X2、GTS R1/R3/R4、DigiCert G2/G3/G5、Microsoft 2017、Amazon 1–4、GlobalSign R3/R46/E46、Sectigo R46/E46、USERTrust、TrustAsia、CFCA EV ROOT 等）和必须没有（TrustCor、Chunghwa、Camerfirma、E-Tugra、VeriSign/GeoTrust/thawte 老根等）的名单。
- `customize.sh` 的检查对坏包（空目录、少于 100 张、名字不对、不是 PEM）都 abort。
- 刷新演练：喂一个假的“新版”包，工具打印的差是对的。

### 设备（CERT-3，装和重启归人工）

| 步骤 | 做什么 | 看什么 |
|---|---|---|
| E0 | 装之前：拉一份当前的库存证据；**演练撤销办法**：能用 `adb shell am start -n com.topjohnwu.magisk/.ui.MainActivity` 打开 Magisk，模块页里能关掉模块 | 撤销办法确认可用，才能装 |
| E1 | 人工 `system/install-certs.sh --mode add`，重启 | `tools/tests/certs_device.py --expect add`：目录里的文件名和内容 SHA-256 是旧 135 + 新 70；权限和 SELinux 上下文与旧文件一致；**应用视角**：debug 包的新命令 `trust_roots` 读 `KeyStore.getInstance("AndroidCAStore")`，数量和排序后的指纹摘要与期望一致（证明平台真的认，而不只是文件在）；抽样握手 |
| E2 | 人工装 `--mode replace`，重启 | 同上，期望 117 张；原来的 66 张（Mozilla 已移除）、21 张过期的和 Izenpe 已经没有了 |
| 不回归 | 两步之后各跑一次 | `public_data_device.py`、radio、player、更新器客户端；`logcat` 里没有新增的 `SSLHandshakeException`、`CertPathValidatorException` |

抽样握手的站点：从缺的 70 张里挑几张，用各 CA 在 CCADB 登记的测试站点，再加一个老根的站点作对照；先用 `adb shell 'nc -w 5 -z 主机 443'` 确认设备连得上（Mac 经代理上网，它能连不说明设备能连）。站点清单查好以后写死在脚本里并记来源。前两项（文件、AndroidCAStore）不依赖公网。

## 6. 风险与撤销

| 风险 | 说明 / 对策 |
|---|---|
| 内容坏了但挂上了，HTTPS 全断 | 主机测试和 `customize.sh` 挡；分两步装；E0 先确认能撤销 |
| 撤销 | Magisk 应用里移除模块再重启（和 `thinkos-cast` 一样）。Magisk 官方文档还写了：模块目录里有 `remove` 文件，下次重启就移除该模块；但往 `/data/adb` 里写，`02` 号文档写明在这台设备上会被拦，所以不能指望从 adb 写。最后的办法是 `19` 号文档的 EDL 流程，人工 |
| 换掉 66 张有效根 | 见 §4.3，OWN-17 |
| 设备没有 RTC，时间不对时所有证书都验不过 | 现状（`timesync` 模块管），和本模块无关；新根的 notBefore 是 2021 年以后，时间被拨回 2008 年时它们同样验不过，和旧根没有区别 |
| 设备上别的东西依赖被移除的根 | 不知道。已知的使用者只有 ThinkOS 自己和 `timesync`（读 HTTP 的 Date 头，不经 Android 的信任库） |
| 对启动时间、内存的影响 | 少了 17 张，只会更小；没测 |

## 7. 上网核对过的和仍然没验证的

**已核对（2026-11-03）**

- **Magisk**：合并、`.replace`、默认上下文，见 §4.2。
- **Conscrypt 8.1**（`android-8.1.0_r81` 的 `TrustedCertificateStore.java`、`TrustManagerImpl.java`）：
  - 证书是按需读的，不是启动时一次读完；目录是 `$ANDROID_ROOT/etc/security/cacerts`。
  - 文件名是 `X509_NAME_hash_old` 加 `.<编号>`（和我验证过的 135/135 一致），编号必须连续，长度为 0 的文件当作已删除。
  - 读文件用 `CertificateFactory`，PEM、DER 都行。
  - 信任锚的有效期不检查（§4.3）。
- **curl / Mozilla**：见 §3。包头的 `SHA256:` 和 `certdata.txt` 的 SHA-256 一致；4 个带 distrust-after 的根里 1 个在包里。
- **AOSP**：最新一次更新 2025-02-10。
- **设备**：`getenforce` 是 Permissive；原有证书文件的上下文是 `u:object_r:system_file:s0`。

**仍然没验证**

- 没有在设备上复现过证书失败，§2 里“会出问题”是推断。
- 这台设备上 `.replace` 和合并两种用法的实际效果：魔法挂载本身看到了，文档说的也对，但没试。
- 只有一段PEM的文件已在真机测试APK中逐张由原生CertificateFactory解析并验自签名；经Magisk挂载后由AndroidCAStore读取仍待E1/E2。
- SELinux enforcing 下的行为：标签和原文件一致，预期没问题，没测。
- 撤销办法没有演练过。
- 换成 117 张之后，哪些网站会因此连不上：没测。
- Firefox 对某些 CA 的域名限制不在 curl 的包里（哪些根受影响没查）；3 张 emSign 根为什么不在包里没查到。
- 抽样握手用的各 CA 测试站点清单还没查（CERT-3 要从 CCADB 查）。

## 8. 来源

- 设备：`/system/etc/security/cacerts` 的只读拉取，分析结果在 `artifacts/certs/CERT-0/`
- curl / Mozilla：[CA Extract](https://curl.se/docs/caextract.html)；固定的 2026-10-20 包见 `roots-source.json`
- AOSP：[platform/system/ca-certificates 提交记录](https://android.googlesource.com/platform/system/ca-certificates/+log/refs/heads/main)
- Magisk：[Module guide](https://topjohnwu.github.io/Magisk/guides.html)
- Conscrypt 8.1：[`TrustedCertificateStore.java`](https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-8.1.0_r81/platform/src/main/java/org/conscrypt/TrustedCertificateStore.java)、[`TrustManagerImpl.java`](https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-8.1.0_r81/platform/src/main/java/org/conscrypt/TrustManagerImpl.java)
- curl：[`mk-ca-bundle.pl`](https://github.com/curl/curl/blob/master/scripts/mk-ca-bundle.pl)；Mozilla 的 [`certdata.txt`](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/heads/release/security/nss/lib/ckfw/builtins/certdata.txt)
- 过期根不检查有效期：[Scott Helme](https://scotthelme.co.uk/should-clients-care-about-the-expiration-of-a-root-certificate/)；Android 14 的在线根证书更新：[Esper](https://www.esper.io/blog/android-14-updatable-certificates)
- 先例：[UpdateAndroidTruststore](https://github.com/bmmrq84/UpdateAndroidTruststore)
- 仓库内：`docs/34-ota-and-plugin-registry.md`（自带根证书、模块白名单）、`system/README.md`、`docs/19-provisioning-for-agent.md`
