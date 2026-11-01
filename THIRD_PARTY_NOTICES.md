# 第三方说明

ThinkOS 的投屏接收（`docs/28-casting.md`）参考了下面的项目。**没有复制它们的源代码**；协议部分由我们按各协议的公开资料重写，并附自己的测试。

## weekdayjast/MiracastReceiver（固定提交 `d1aea7e`，MIT）

用作 DLNA、Miracast（Wi-Fi Display）的协议流程参考：SSDP / UPnP 的动作表、WFD 的 M1–M7 顺序与“Sink 主动连 Source 的 7236 端口”这一事实、`wfd_presentation_URL` 只取第一个词、RTP 载荷类型 33 是 MPEG-TS、`wpa_supplicant` 控制接口的命令（`SET wifi_display`、`WFD_SUBELEM_SET 0 …`）。AirPlay 部分用它读懂了 `/server-info`、`/reverse`、`/play`、`/playback-info` 的实际行为与配对握手的步骤。

> MIT License. Copyright (c) the MiracastReceiver authors. Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, subject to the inclusion of this copyright notice and permission notice in copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

它的 `cpp/playfair/`（FairPlay）没有许可证头，上游 shairplay 把 playfair 标为 GPLv3：**ThinkOS 没有使用这份拷贝**。

## 协议资料

- AirPlay：nto.github.io 的非官方规范（Apple TV 软件 5.0），UxPlay 与 RPiPlay 的文档（GPLv3，只读文档，未使用代码）。
- Wi-Fi Display：Wi-Fi Alliance WFD 技术规范的公开概述；RFC 2326（RTSP）、RFC 3550（RTP）。
- 曲线算法的测试向量：RFC 7748（X25519）、RFC 8032（Ed25519）。

## 运行时库

AndroidX Media3（ExoPlayer，Apache-2.0）。FFmpeg 音频扩展（GPL-3.0）按构建开关 `-Pthinkos.ffmpeg=false` 可去掉，见 `docs/27-streaming.md`。

## RPiPlay（固定提交 `64d0341ed3bef098c940c9ed0675948870a271f9`，GPLv3）

`android/app/src/main/cpp/playfair/` 里的 `playfair`（FairPlay 密钥解包）从 https://github.com/FD-/RPiPlay 的 `lib/playfair/` 原样复制，连同它的 `LICENSE.md`（GNU General Public License 第 3 版）；`android/app/src/main/cpp/fairplay_jni.c` 里 FairPlay 握手的固定回答表来自同一个提交的 `lib/fairplay_playfair.c`。ThinkOS 因此以 GPLv3 分发（ADR-034）。AirPlay 镜像的密钥派生与包格式参照了 RPiPlay 的 `lib/mirror_buffer.c`、`lib/raop_buffer.c` 与 `lib/raop_rtp_mirror.c`（只读，代码重写）。

