# 所有者钥匙与本地发布准备

这些工具只在主机上运行。它们不推送、不创建 tag 或 Release、不更新远程通道、不安装系统模块。正式钥匙由所有者生成；Agent 不应替所有者执行生成命令。CI 环境一律拒绝钥匙操作和发布准备。

## 生成并备份钥匙

使用 JDK 17 的 `keytool`、OpenSSL 与 Python 3。三个私有目录必须位于仓库之外，尚不存在，父目录已存在；路径的任一级不能是符号链接。使用所有者控制的离线介质，两个备份目的地应在不同介质上。工具不能判断两个卷是否实际上属于同一块硬盘。

```sh
tools/gen_keys.sh \
  --private-dir /Volumes/ThinkOSKeys/thinkos-private \
  --backup-dir /Volumes/ThinkOSColdA/thinkos-private \
  --backup-dir /Volumes/ThinkOSColdB/thinkos-private \
  --public-dir /Volumes/ThinkOSKeys/thinkos-public
```

终端隐藏输入口令两次，至少 12 字符。APK 密钥存于加密的 `apk-signing.p12`；通道、系统、仓库各生成互相独立的 active 和 backup P-256，加密为 PKCS8。私有目录权限 0700、文件 0600。两个离线备份逐字节核对后才报告成功。生成中途失败会保留已创建的目录供人工检查，拒绝覆盖或合并，不能直接在原路径重试。

口令不作为命令参数、不写入输出文件或日志。子进程通过短暂环境变量读取。高级用法可通过 `--password-fd 3` 从所有者显式打开的私有文件描述符传入；工具拒绝 stdin/stdout/stderr、公开权限的口令文件和超过 4096 字节的输入。不要把口令、私有目录、keystore 或密钥复制到 Git、聊天、CI 或发布附件。

公开输出包含：

- `keys.json`：APK 的通道与仓库 active/backup SPKI 公钥
- `system-keys.json`：平铺的 `schema`、`active`、`backup`，两把系统 SPKI 公钥；不是 `system: {…}` 嵌套格式
- `updater_keys.h`：两把系统公钥的 64 字节 P-256 X||Y，宏 `UP_SYSTEM_KEY_COUNT=2` 与数组 `up_system_keys[][64]`
- `apk-certificate.der` 与 `key-inventory.json`：APK 公开证书、指纹、公开文件清单及备份数量

所有者审阅全部公开文件后，手动把 `keys.json` 和 `system-keys.json` 原字节分别放到 `android/app/src/main/assets/update/keys.json`、`android/app/src/main/assets/update/system-keys.json`，然后审阅并提交。只复制这两个**公钥** JSON，不把 private 目录、口令、keystore 或加密私钥放进 APK。发布工具不会替你创建或修复这两个资产，缺失时停止；不要用 JSON 重排/格式转换替代原文件复制。

`system/build-updater.sh --keys-header /Volumes/ThinkOSKeys/thinkos-public/updater_keys.h` 只把公钥编入更新器；缺少正式公钥的开发构建不能验证模块，不使用占位测试钥匙充作正式根。APK 的模块验证器仅从 `assets/update/system-keys.json` 读取系统根，不回退到通道、仓库、debug 资产或下载文件。

本地 `prepare` 在读取任何私钥、构建或签名前，核对这两份源码资产与所有者 public 文件**逐字节相同**，并检查系统 header 的 active/backup X||Y 顺序、数量和完整字节。通道、仓库、系统六把公钥及六个 ID 必须全部不同。系统 JSON 只接受平铺格式；缺失、重复字段、重复角色、错根、符号链接、超限输入都拒绝，不自动修复。`src/debug`、`src/release` 如有同名覆盖资产，也必须逐字节一致。构建后重新核对 debug/release 两个 APK 实际 ZIP 中的同两份资产，不接受缺失、重复条目或其他根代替。每次模块构建、签名前再次检查公开材料没变，通道签名前还会核对最终 APK 的根；`release-preparation.json` 保存三个公开文件摘要供审核。它仍是本地准备，绝不自动发布。

按 `docs/34-ota-and-plugin-registry.md` §4.8 完成人工证书迁移。`android/keystore.properties` 继续使用现有四个属性，文件必须由所有者持有、权限 0600 并被 Git 忽略；`storeFile` 指向仓库外的 `apk-signing.p12`，`keyAlias=thinkos`，两个密码属性由所有者在本机配置。工具不会生成或输出含真实口令的 properties 文件。首次迁移、设备管理变更、模块持久安装与重启均按人工流程执行。

## 本地准备一版发布

先审阅并提交所有源码与公钥，再运行；工具拒绝任何已修改或未跟踪文件。`thinkos.version` 必须已在提交中递增。准备目录必须是仓库外新目录。正式密钥和已提交 APK 公钥必须相符；本地 debug 证书不能冒充正式发布证书。

准备一个更新说明 JSON，如 `{"zh-Hans":"修复新手引导"}`。可选模块选择 JSON 如：

```json
[
  {"id":"thinkos-updater","policy":"always"},
  {"id":"thinkos-certs","policy":"installed","mode":"replace"}
]
```

只支持文档白名单里的七个 ID，通过固定仓库脚本构建，不从输入执行命令。一次目录最多五个组件，沿用现有 schema。选择本版有变化的模块；已有模块若内容变化但 code 未增加，会拒绝。Vector APK 会重新用所有者 APK 证书签名；Magisk 模块以原样 ZIP 发布。模块清单固定七行、最后保留 LF，用系统钥匙签原始字节，`.sig` 是 ASN.1 DER SHA256withECDSA。目录 `kind` 仍为 `vector`；仅 `thinkos-immersive` 的七行清单将它映射成 `kind=vector-apk`，其余六个 ID 只允许 `magisk`。版本长度 1–48、首字符为 ASCII 字母或数字，后续只含字母/数字/`._+-`；code 为 1–INT32_MAX，包大小为 1–16MiB，与生产 C verifier 一致。新增临时 fixture 用真实 DER 签名和现有 C driver 交叉核对这些原始字节；不读取正式钥匙或连接设备。

```sh
tools/release.sh prepare \
  --private-dir /Volumes/ThinkOSKeys/thinkos-private \
  --public-dir /Volumes/ThinkOSKeys/thinkos-public \
  --output /Volumes/ThinkOSRelease/thinkos-0.2.0-beta \
  --bootstrap-code 1006 --channel beta --seq 1 \
  --url-base https://github.com/OWNER/ThinkOS/releases/download/v0.2.0 \
  --source-url https://github.com/OWNER/ThinkOS/tree/v0.2.0 \
  --notes /absolute/path/notes.json \
  --modules /absolute/path/modules.json
```

首次签名通道才使用显式 `--bootstrap-code`。后续改用 `--previous-catalog /absolute/path/beta.json`，要求相邻 `.sig` 用 APK 信任的通道钥匙验签通过，通道相同、新 code 和 seq 都增加。可重复 `--url-base` 提供最多八个 HTTPS 下载候选；默认用 active 钥匙，轮换时显式 `--slot backup`。

流程包含主机单测、Lua、契约 Go、系统层测试、插件和字符串检查、debug/release 构建、正式签名及纯 armv7/API27 核对。Go 仅现有契约测试，不启动或实现 Server。它核对 LIC-04 附件的内容、哈希和源码目录，再通过 `git archive` 生成对应提交的完整 ThinkOS 源码包。LIC-04 尚未准备好时，由所有者先按 `tools/archive_ffmpeg_source.sh --offline` 从本地缓存重建，不自动联网拉取源码。

默认不操作设备。只有明确指定 `--smoke-serial HUA0BWUT` 才调用已有 release 冒烟驱动：需要设备当前已经安装同版本、同正式证书的 debug 包，替换成 release 验证后恢复该 debug 包；不会卸载、改变设备管理或安装系统模块。省略冒烟会明确记为未验证，不会报告完整发布验收成功。该驱动与此工具不包含冷启动性能、10 分钟性能或物理断电验收。

成功产出 `release-preparation.json`、APK、组件及原始清单/签名、通道 JSON/签名、GPLv3 全文、FFmpeg 完整对应源码及摘要、ThinkOS 对应源码和 `SHA256SUMS`。状态是本地 `prepared`，`published=false`。检查失败则没有成功清单，保留已有材料供审阅；修复后应使用新的准备目录。

## 对外发布仍须所有者执行

`OWNER-PUBLISH.md` 由 `tools/release.py` 的 `prepare` 在 `--output` 指定的仓库外准备目录中生成，与 `release-preparation.json`、`SHA256SUMS` 同目录；它不是仓库内的现成文档。此文件写在最终成功清单之前，因此它的存在也不代表整个准备流程成功；要核对最后生成的 `release-preparation.json`，状态仍只是 `prepared`、`published=false`。文件列出待办：审阅目录与所有摘要，完成实际设备验收和 soak，确认公钥、证书迁移和不可变发布配置，然后在对应提交建立版本 tag、推送 tag、创建带全部附件的 Release，并把通道原始 JSON 与 `.sig` 一起发布。工具没有 `publish` 子命令，不会替所有者实施这些外部动作；stable 推广必须使用新的 seq。

## 首发发布（CNB 加 GitHub）

渠道设计在 `docs/34` §4.10，任务在 `docs/36` §11G。下面每一步都是你做，Agent 只准备了工具，没有替你执行其中任何一步。设备到 CNB 实测比到 GitHub 快约 60 倍（`artifacts/update/REL-0/part1-20261105/`），所以默认源里 CNB 在前。

### 0. 一次性的准备

1. **钥匙：**上面的“生成并备份钥匙”。这台 Mac 的 PATH 上默认是 JDK 27，生成前先切到 JDK 17（`export JAVA_HOME=$(/usr/libexec/java_home -v 17); export PATH=$JAVA_HOME/bin:$PATH`），让 APK 证书的 keystore 和 Gradle 用的 JDK 一致；`keytool`、`openssl`、`gh`、`go`、`aapt`、`apksigner` 在这台 Mac 上都已装好（2026-11-05 查过）。然后把两份公钥放进 `android/app/src/main/assets/update/`，再配好 `android/keystore.properties`。
2. **CNB 令牌：**个人设置 → 访问令牌 → 添加访问令牌，选满足“读写发布附件”和“推送代码”的最小范围。接口说明里发布相关接口要求 `repo-release:rw`；表单里具体叫什么我没看到，以表单为准。CNB 只支持 HTTPS：推送时用户名固定写 `cnb`，密码填令牌。令牌只放在你自己的终端：`export THINKOS_CNB_TOKEN=…`。
3. **GitHub：**新建一个公开仓库，把 `owner/name` 告诉 Agent（要写进默认源，`docs/36` REL-1）；`gh auth login`。
4. **提交开发仓库**（`docs/36` OWN-1）。`release.sh prepare` 拒绝未提交的树。

关于 OpenSSL（2026-11-05 查出）：这台 Mac 上 Homebrew 的 OpenSSL 4.1.0 读加密私钥时，如果调用方的 stdin 是 socket（部分 IDE 或自动化环境），`openssl pkey -passin …` 会去找终端口令而失败；管道、文件、`/dev/null` 正常，系统自带的 `/usr/bin/openssl` 也不受影响。工具里的 openssl 调用现在统一把 stdin 接到 `/dev/null`（`release_tools.run`），所以从终端、IDE 都一样；我没法在这里用真实终端验证，如果你在终端里运行 `gen_keys.sh` 遇到 `unable to get passphrase`，把这一条告诉我。

### 1. 导出干净历史的公开仓库

```sh
python3 tools/export_public.py --output ~/ThinkOS-public \
  --author-name 'ThinkOS' --author-email 'noreply@users.noreply.cnb.cool'
```

只导出已提交的版本，丢掉 `artifacts/`（只留构建和测试要读的几个文件），作者信息用你给的，不用全局 git 配置。出现 `BLOCKING` 行（真实的个人目录路径、私钥内容）就先处理再跑；私有地址、邮箱、`ssid` 是提示，多半是测试夹具。之后的版本在同一目录上再跑一次，会追加一个提交。

### 2. 在导出的仓库里准备版本

```sh
cp <开发仓库>/android/local.properties <开发仓库>/android/keystore.properties ~/ThinkOS-public/android/   # 这两个被 .gitignore 忽略
cd ~/ThinkOS-public
tools/release.sh prepare --private-dir … --public-dir … --output …/release-0.1.31 \
  --channel beta --seq 1 --bootstrap-code 1000 \
  --url-base https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/v0.1.31 \
  --url-base https://github.com/<owner>/<name>/releases/download/v0.1.31 \
  --catalog-url https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/ota-beta/beta.json \
  --catalog-url https://github.com/<owner>/<name>/releases/download/ota-beta/beta.json \
  --source-url https://cnb.cool/kiwimoe/ThinkOS/-/tree/v0.1.31 \
  --attachments <开发仓库>/artifacts/lic/LIC-04/release-attachments.json \
  --notes notes.json
```

`--url-base` 的顺序就是设备下载的顺序，CNB 在前。`--catalog-url` 是各来源上这个通道目录的地址，签进目录后设备会把它们并入候选源，所以以后增加镜像只要在下一版目录里加一行，不用先发新 APK；GitHub 那一行等仓库建好再加。不带 `--fairplay` 时构建关闭 FairPlay，并核对包里没有 `libthinkos_playfair.so`。首发目录不带模块（不传 `--modules`）。FFmpeg 对应源码存档有约 469 MB，上传和备份都要留出时间。

### 3. 推送代码和 tag

```sh
cd ~/ThinkOS-public
git remote add cnb https://cnb.cool/kiwimoe/ThinkOS && git remote add github https://github.com/<owner>/<name>.git
git tag v0.1.31 && git push cnb main v0.1.31 && git push github main v0.1.31
```

打开 `https://cnb.cool/kiwimoe/ThinkOS/-/tree/v0.1.31` 确认能访问，它就是 `--source-url`。

### 4. 先做一次预演发布，再正式发布

```sh
python3 tools/publish.py …/release-0.1.31 --site cnb --site github --github-repo <owner>/<name>                    # 只打印计划
python3 tools/publish.py …/release-0.1.31 --site cnb --tag-suffix -rehearsal1 --execute                          # 预演：只在 CNB，发布到带后缀的测试版本
python3 tools/publish.py …/release-0.1.31 --site cnb --tag-suffix -rehearsal1 --cleanup                          # 删除预演发布
python3 tools/publish.py …/release-0.1.31 --site cnb --site github --github-repo <owner>/<name> --execute        # 正式
```

工具上传后会像设备那样匿名读回每个文件核对摘要，**目录最后才替换**；大文件只比较长度和首尾各 1 MiB。上传的 `PUT` 请求不带令牌，令牌只会发到 CNB 的 API 主机。预演要在 CNB 上做：GitHub 的版本发布要求你已经推送了对应的 tag。`ttl` 一律传 0（永久）；预演后请在 CNB 页面确认附件没有过期时间。

### 5. 设备

首发版是装上去的，不是推上去的：现有机器是 debug 签名，需要先卸载 debug 包、装正式包、重设 Device Owner（金镜像或 `tools/provision.py`）。装好后设备会自己去 CNB、再去 GitHub 取目录。OTA 的第一次真实推送要等第二个版本，用 `--previous-catalog` 和更大的 `--seq` 准备并发布。
