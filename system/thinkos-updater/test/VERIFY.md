# 模块验证器的主机验收

`verify_driver.c` 只属于主机测试，不进入模块。正式 daemon 不从请求或文件读取信任钥匙。编译输入为 driver、`src/module_verify.c`、`src/micro_ecc.c` 与 `vendor/sha-2/sha-256.c`；无需 OpenSSL 库。OpenSSL CLI 只在测试中作为独立签名/验签与散列判据。

命令：

```
verify_driver verify manifest.txt manifest.txt.sig public-xy.bin package.zip
verify_driver hash package.zip 12345
```

测试公钥文件是 64 或 128 字节的 P-256 `X || Y`；可从 OpenSSL 生成的 SPKI DER 取其标准 65 字节未压缩点，去掉 `04`。这是主机 driver 的输入，不是产品入口；空文件代表没有信任根，应拒绝。

统一验收至少覆盖：

- OpenSSL active/backup 分别签名的规范七行文本成功；与 OpenSSL 的 `dgst -sha256 -verify` 结果一致。
- 错钥匙、改字节、全零/非法曲线点、缺钥匙、重复钥匙、零或大于阶的 r/s、负数/冗余前导零/长形式长度/缺字段/尾随内容的 DER 均拒绝。
- 以有效测试钥匙给每种畸形清单签名：重复、漏行、多行、调序、NUL/CR/TAB/非ASCII、未知 id、错误 kind、空版本、路径/空格、数字符号/前导零/指数/溢出、坏摘要、0或超大size，均拒绝。用错误钥匙签未知id文本应先返回 bad-signature，不能泄露解析结果。
- 所有七个 id；只有 `thinkos-immersive` 接受 `vector-apk`，其余仅 `magisk`。代码为 1..2147483647，文件为 1..16777216 字节。
- 包摘要与 `sha256sum`/OpenSSL 一致；错内容、错大小、非普通文件/不可读 FD 拒绝。`hash` driver 验证 FD 原偏移不变。
- 不同读取分段长度及 SHA-256 55/56/63/64/65 字节边界，对照官方 NIST/OpenSSL；主机 sanitizers 与 NDK armv7 交叉编译。

清单必须以 LF 结尾且恰好七行；签名是这些原始字节的 ASN.1 DER SHA256withECDSA，不是 JSON、Base64 或 JOSE 原始 r/s。数值防回退比较在 daemon 的 root 事务层，验证器本身不读取设备状态。验签只解析格式，不解释 zip/APK。
