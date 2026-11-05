# OTA-5 受限 LAN 本地材料

`tools/ota_lan.py` 只把已经由 `release.sh prepare` 完成的正式本地材料重签到一个显式数字私网 HTTP 根，不构建、不联网、不接设备、不发布、不生成钥匙。正式 `release.prepare` 的 HTTPS 分发规则保持不变。本工具是 OTA-5 的材料准备环节；完整正式根/来源安装、真实600秒健康、自动安装、来源故障/回滚 runner 与实机证据仍未完成。

输入须为原 `release.prepare` 的平铺输出目录：成功的 `release-preparation.json`、覆盖全部顶层普通文件的 `SHA256SUMS`、原始 channel JSON/.sig、实际正式 APK、所列模块及其原始7行清单/.sig。暂不接受有 `device-smoke/` 等子目录的输出；请使用未指定 `--smoke-serial` 的新准备目录。所有私有/public/export/source/output 路径父链均不能有 symlink，output 必须仓库外且全新。public-dir 使用所有者公开导出，且必须与本次 APK 及当前源码 public assets 的原字节相同。

APK 必须 owner 单一证书、非 debuggable、min/target27，version/code/hash/size/signer 与签名 catalog 一致；逐 so 验 ELF32/little-endian/ARM，ZIP 有数量/大小/展开界限、拒重复/穿越/链接。system 清单用独立 system 根验签，并与 module tuple、payload 哈希/大小及真实 module.prop / Vector APK身份比对。所有者私钥只在 execute 最后签名阶段读取，必须仓库外、0600、加密 PKCS8、对应所选 channel slot；口令只走隐藏终端或 `--password-fd N`。不接受 debug/crash APK 的隐式豁免；正式来源坏版本演练另需明确实现和审阅，不能拿现 `ota_package_fixtures.py` 的 NEVER INSTALL 样本补上。

先准备 output 的父目录，示例 LAN 地址与 seq 必须换成所有者实际确认的值。仅 RFC1918 IPv4 或 IPv6 ULA 数字 HTTP 根可用，不接受 DNS、loopback、link-local、CGNAT、公网、凭据、query/fragment、URL编码或路径穿越。

```sh
python3 tools/ota_lan.py \
  --release-dir /Volumes/ThinkOSRelease/thinkos-0.2.0-beta \
  --public-dir /Volumes/ThinkOSKeys/thinkos-public \
  --output /Volumes/ThinkOSRelease/lan-root/N \
  --lan-root http://192.168.1.20:8765/N \
  --seq 100 --initial \
  --private-key /Volumes/ThinkOSKeys/thinkos-private/channel-active.key.pem
```

默认只验证并打印 plan，不创建 output、不读取私钥。所有输入先经有界、O_NOFOLLOW 的文件描述符复制到0700私有临时快照；SDK验签/身份、摘要与最终复制始终使用同一快照字节，避免源APK在验证与复制间被替换。审阅 `input_sha256`、原/新目录摘要、镜像、通道、seq、slot与白名单后，以**完全相同参数**加 `--reviewed-sha256 <计划input_sha256> --execute`，才在本机生成 LAN 根。该摘要同时绑定完整输入文件/public roots、原始前序签名、LAN/seq/slot/output，不只是 APK 哈希。后续改用 `--previous-catalog /Volumes/ThinkOSRelease/lan-root/N/beta.json`，不能再传 `--initial`；新 seq 必须大于原正式目录与前序已验 channel 目录，APK/module code不可回退或同版本换字节。前序未再列出的模块沿用正式发布“未变组件省略”语义，不意味着卸载、未安装或清除其floor。设备已接受的更高 floor 仍会正常拒绝旧目录，工具不会清它。

工具仅修改 APK/module/manifest URLs 和 seq，原 published/expires、身份、notes/source、payload、system原始清单与签名保持。output 只放 channel JSON/.sig、实际 APK/module、原始 module manifest/.sig，不放钥匙、报告、日志、源码或 SHA256SUMS；校验结果与各输出 SHA-256 只打印 stdout。失败保留部分新目录供检查，catalog最后写入，不能把失败目录交给设备；重试需新output和新plan。

所有者另行决定启动只读静态服务时，目录映射必须和签名 URL 的路径一致，例如上述 N 根应服务其父目录：

```sh
python3 -m http.server 8765 --bind 192.168.1.20 --directory /Volumes/ThinkOSRelease/lan-root
```

设备镜像指向完整 `http://192.168.1.20:8765/N/beta.json`；通过现设置 schema/原生入口设置，不写 SharedPreferences。该命令不是工具自动行为，也不授权安装/重启。OWN-6 的正式钥匙/证书迁移仍须所有者；模块/guard/坏模块仍只准 OWN-4 备用机，HUA0BWUT禁装updater/guard及坏模块演练。

测试源码 `tools/tests/test_ota_lan.py` 使用本机 SDK、真实一次性 PKCS12/OpenSSL/APK签名和最小 hasCode=false APK，fixture只含不会执行的ARM ELF头；不联网、不安装、不运行模块，不读取正式钥匙。测试覆盖完整CLI plan→execute、角色隔离、模块7行不变、APK/Vector实签语义、ELF/ZIP/路径界限、旧seq、旧版本换字节、备用slot、CI、输入变化与真实aapt期间源文件交换；交换测试仅包裹并委托真实SDK命令，未伪造验签/身份结果。SDK版本字段只接受精确独立的旧 `sdkVersion` 或新 `minSdkVersion` 行及唯一 target 行，均须27；不按标签子串猜测。root已运行20项专项及完整288项Python回归，均通过；证据见 `artifacts/update/OTA-5/lan-preparation-20261105/qualification.json`。以上不等于正式钥匙、迁移或设备端OTA-5验收。
