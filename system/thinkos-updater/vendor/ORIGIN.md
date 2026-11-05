# 更新器的密码学实现

这里仅供 `thinkos-updater` 的 C 守护进程使用。源码、原许可证和 README 按固定提交原样保留；每个文件的官方地址、大小和 SHA-256 在 `sources.lock.json`。2026-11-04 直接核对原作者仓库及其许可证后纳入，无设备下载或运行时依赖。

| 目录 | 官方来源 / 固定提交 | 许可证 | 用途 |
|---|---|---|---|
| `micro-ecc/` | [kmackay/micro-ecc](https://github.com/kmackay/micro-ecc/tree/541b3a78026420a3e369c4c9281c396b5e531113) `541b3a78026420a3e369c4c9281c396b5e531113` | [BSD-2-Clause 原文](micro-ecc/LICENSE.txt)，Kenneth MacKay | secp256r1 公钥验证、ECDSA 验签 |
| `sha-2/` | [amosnier/sha-2](https://github.com/amosnier/sha-2/tree/565f65009bdd98267361b17d50cddd7c9beb3e6c) `565f65009bdd98267361b17d50cddd7c9beb3e6c` | 原仓库允许 Unlicense 或 0BSD；本项目选择 [0BSD](sha-2/LICENSE.md)，Alain Mosnier | 原始清单和文件流的 SHA-256 |

`src/crypto_config.h` 固定 micro-ecc 为纯 C、32 位字、仅 P-256；通过 `src/micro_ecc.c` 包含上游 `uECC.c`，不要另行编译后者，以免配置不一致。没有修改、改写或自行实现散列或椭圆曲线算法。未纳入未使用的 ARM/AVR 汇编文件，平台固定为 `uECC_arch_other`，不依赖 arm64。

`src/module_verify.c` 是本项目 MIT 的有界格式适配器：把规范 ASN.1 DER 的签名转换为 micro-ecc 所需的 `r || s`，先验证 SHA256withECDSA 签名，再解析七行清单；它不实现新的密码算法，也不解析压缩包。正式系统公钥仅在模块构建时嵌入，一把在用、一把冷备；缺失时不接受任何清单。测试生成的临时钥匙不得作为正式构建默认值。
