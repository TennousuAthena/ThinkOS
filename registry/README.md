# 本地插件仓库材料

本目录只准备将来独立 `thinkos-registry` 的文件结构，没有创建远程仓库、分支、PR、issue、Secret 或发布内容。`main/` 是需人工审阅的来源登记和工作流模板，`dist/`、`ota/` 只有用途说明。正式仓库和分发地址仍要所有者决定。

## 来源校验与目录构建

所有来源均按 Git 对象读取：裸仓库、`ls-tree`、`cat-file`；不会检出作者文件，不运行作者脚本、Git 钩子或构建命令。唯一解析 Lua 的程序是项目自己携带的可信 Lua 5.4 `luac -p`。缺少显式 `--luac` 时工具在临时目录编译项目内已有的 Lua 源码，不使用作者提供的编译器。Git 子进程仅允许 HTTPS、使用空钩子目录、关闭全局配置、懒取对象和自动维护，并限制时间、输出和单文件写入。

登记文件必须叫 `plugins/<id>.json`，只允许 `repo`、`path`、`owners`。作者不能声明 `official` 或绕过内置 ID 保护。先显式获取一个新的裸缓存：

```sh
python3 tools/registry.py fetch \
  --registration /absolute/path/registry/main/plugins/example.json \
  --cache /absolute/path/new-example.git
```

`repo` 使用 `owner/name`，只从固定构造的 `https://github.com/owner/name.git` 获取。既有缓存不会被覆盖；每天扫描使用新的隔离目录。大型或异常仓库会因子进程边界失败，不能通过协议降级、本地路径或作者的远程助手绕过。

构建计划只包含数据，不接受命令：

```json
[
  {
    "registration":"/absolute/path/registry/main/plugins/example.json",
    "git_dir":"/absolute/path/new-example.git",
    "tag":"v1.2.0"
  }
]
```

```sh
python3 tools/registry.py build \
  --sources /absolute/path/sources.json \
  --output /absolute/path/new-dist \
  --seq 1 \
  --blob-base https://example.org/thinkos-registry/immutable-commit/
```

输出是 **unsigned**，并未发布：

- `registry/index.json`：直接复用 `shared/schema/registry/catalog.json` 校验
- `registry/files/<digest>.json`：沿用设备已有的路径/NUL/hash/LF 摘要规则
- `blobs/ab/<sha256>`：插件、法律文本、商店图标和截图的原始字节
- `registry/tag-ledger.json`：记住见过的版本 tag，后续发现移动时拒绝替换
- `review-requests.json`：权限增加和 tag 移动的可审阅请求，`remote_action_performed=false`

后续构建增加 `--previous /absolute/path/old-dist --keys /absolute/path/public/keys.json`。旧目录及 tag ledger 的签名都要用仓库角色公钥验过，不能把损坏或无法访问的旧目录当成首次使用。seq 必须增加。每个插件保留最高三个语义版本，只保留它们和当前商店信息引用的文件清单及 blob；源文件原版权与许可字节不改写。

已发布 tag 被移动，或者同 tag/commit 的打包内容改变，都保留旧版本并生成 issue 请求。新版本增加权限时保留旧版，生成 pull_request 请求。在所有者批准启用真实仓库工作流后，`scan-tags.yml` 的独立 `review` job 使用 `tools/registry_review.py` 真正创建或更新权限审核 PR 和来源完整性 issue；`prepare` 生成请求本身不代表已开出 PR。当前模板尚未启用，未向真实远端发出这些写操作。批准的本地 `--approvals` 文件精确绑定 `id`、`version`、`commit`、`digest` 和排序后的完整 `permissions`，不接受仅按插件名称的宽泛批准。目录接纳后，设备安装新增权限仍需原生确认。

`--revocations /absolute/path/revoked.json` 使用现有 schema 的 `[{"id":"example","digest":"完整摘要","reason":"可见的停用原因"}]`；未提供时保留旧吊销记录。原因用纯文本，不使用尚不存在的 `@revoked.*` 翻译键。

## 作者的商店资料

插件源码或子目录里放 `store.json`：

```json
{
  "name":{"zh-Hans":"示例","en":"Example"},
  "summary":{"zh-Hans":"显示一条公开数据","en":"Shows public data"},
  "author":"alice",
  "license":"MIT",
  "tags":["utility"],
  "why":{"network":{"zh-Hans":"读取公开数据","en":"Read public data"}},
  "icon":"store/icon.png",
  "screenshots":["store/screen.png"]
}
```

名称、简介、每项 why 都是支持语言的表；每个声明权限恰有一个 why。SPDX 使用工具显式允许再分发的标识符；未知标识符和 LicenseRef 需另行审核，不能自动进入。保留原始 LICENSE/COPYING 与 NOTICE。支持的根法律文件名仅为 `LICENSE`、`LICENSE.txt`、`LICENSE.md`、`COPYING`、`NOTICE`、`NOTICE.txt`，受现有大小限制；插件子目录没有许可时，可按同一 Git commit 继承仓库根的原始许可文本。它们随插件下发，不能替换成项目的 MIT 许可。

`store/` 只允许图标/截图为 PNG、JPEG、WebP，各文件最多 1 MiB、截图最多八张。`store.json` 和整个 `store/` 都不进设备插件文件清单；商店图片以独立内容寻址 blob 发布。不得把商店字段塞进严格的 manifest。tag 必须与 manifest 的 version 对应，Lua 语法必须是设备实际使用的 5.4；符号链接、子模块、路径穿越、大小写冲突、超限文件、任何角色和 `system.*` 均拒绝。

## 官方首批来源

`main/plugins/` 和独立维护者 `official-allowlist.json` 准备了 `player`、`radio`、`iptv` 的原 ID 来源，固定 repo/path/owner；使用 `--official-allowlist /absolute/path/registry/main/official-allowlist.json` 才能接纳这些内置 ID。这是普通作者不得占用内置 ID 与官方同 ID 高版本升级之间的明确区分，不改名为 `official-*`。

官方目录也不能带任何角色或系统权限。`home`、`settings`、`panel`、`welcome`、`hub` 等系统插件不能通过该目录更新。设备在目录验签后才信任 `tier=official`，同版本仍用内置副本。

三个官方商店资料草稿位于 `main/store-drafts/`。正式 source tag 必须包含对应 `store.json`，由维护者审阅并在不干扰当前 APK 验收的源码提交中纳入；目前没有创建这些 tag，也没有声称已发布官方内容。为保持 monorepo 的应用 tag 与插件版本分开，官方来源只匹配 `player/v<插件版本>`、`radio/v<插件版本>`、`iptv/v<插件版本>`；普通作者仍用 `v<版本>`。

## 所有者主机签名与发布

```sh
python3 tools/registry.py sign \
  --directory /absolute/path/new-dist \
  --keys /absolute/path/public/keys.json \
  --private-key /Volumes/ThinkOSKeys/thinkos-private/registry-active.key.pem
```

首次以外增加 `--previous /absolute/path/old-dist`。签名会重新核对内容寻址、真实 manifest/API/权限/应用声明、原始角色限制及 Lua 5.4 语法，不相信 CI 的成功标记。私钥必须位于仓库外、由所有者持有且权限私密，采用加密 PKCS8；口令只走终端隐藏输入或 `--password-fd`，不进参数、仓库或日志。签名为原始文件字节的 SHA256withECDSA，ASN.1 DER，输出 `index.json.sig` 和 `tag-ledger.json.sig`，并用公开仓库钥匙验回。`registry.py sign` 仍只做本地签名，`published=false`；DEC-12 的签名并发布使用下面的 owner 命令。

DEC-12 的本机流程是 plan → 审阅输入 SHA-256 → 一条 `--execute` 命令签名并发布。先准备计划：

```sh
python3 tools/registry_owner.py publish \
  --directory /absolute/path/reviewed-unsigned \
  --repository ORG/thinkos-registry \
  --keys /absolute/path/public/keys.json \
  --private-key /Volumes/ThinkOSKeys/thinkos-private/registry-active.key.pem \
  --expected-dist FULL_40_CHARACTER_DIST_COMMIT
```

首次没有 dist 时，把 `--expected-dist` 及其值换成 `--initial`。默认计划不打开私钥、不签名、不写远端；它重新获取并认证当前 dist，输出 `input_sha256`、待签 catalog 摘要和 seq。所有者审阅材料与计划后，以相同参数附上计划中的 SHA-256 执行：

```sh
python3 tools/registry_owner.py publish \
  --directory /absolute/path/reviewed-unsigned \
  --repository ORG/thinkos-registry \
  --keys /absolute/path/public/keys.json \
  --private-key /Volumes/ThinkOSKeys/thinkos-private/registry-active.key.pem \
  --expected-dist FULL_40_CHARACTER_DIST_COMMIT \
  --reviewed-sha256 FULL_INPUT_SHA256_FROM_REVIEWED_PLAN \
  --execute
```

该命令只在所有者主机运行，生产目标固定为 GitHub HTTPS，拒绝 CI。私钥沿用上述加密及权限限制；口令只走隐藏终端或 `--password-fd N`，GitHub token 只走隐藏终端、`--github-token-fd N` 或 `THINKOS_REGISTRY_GITHUB_TOKEN` 环境，不把真实值填入参数、仓库或日志。真实提交与 push 仍须本次所有者明确授权。

执行前重新获取并验当前目录/tag ledger 的签名、原始字节及递增 seq；输入 SHA 不符或远端已变就停止。临时 bare 先造只含引用文件清单/blob 的内容 commit A，将 catalog 的 jsDelivr/raw blob 根固定到 A 后签名，再造包含目录、ledger 及两份签名的 commit B（parent=A）。这样无需把 catalog 指向包含自身的 commit。只用精确预期旧 ref 的 CAS 更新 `dist` 到 B，随后读回 ref；报告、钥匙和来源计划不入发布树。不自动重试；读回无法确认时返回 `status=unknown,published=null`，应先核对远端 ref 与报告中的 B，不能直接重跑。计划与目录级审阅不会给 community 新增逐项目人工审核。

## CI 模板与保留的门槛

模板只放在本地 `main/.github/workflows/`，不会在 ThinkOS monorepo 自动运行。所有者批准创建真实仓库后，先配置受保护 main、CODEOWNERS 和 `registry-review` 环境，再设置公开变量：

- `THINKOS_TOOLS_COMMIT`：审阅后的 ThinkOS 工具完整 commit，固定可信校验代码
- `REGISTRY_BLOB_BASE`：正式 HTTPS 内容根地址
- `REGISTRY_INITIAL=true`：只允许第一次、尚无 dist 的材料生成，明确不宣称做过历史比较；之后关闭

PR 校验使用 `pull_request`、只读且无 Secret。`scan-tags.yml` 的 `prepare` job 自动发现并校验 community、扫描 tag，只上传 unsigned 与请求材料；成功后，独立 `review` job 仅在可信 main 的定时或手动事件运行，以当前仓库/同一 run 的 artifact ID、请求/context SHA-256、扫描 commit 和固定工具 commit 绑定生产者材料，再用最小 `contents` / `pull-requests` / `issues` 写权限创建真实审核 PR/issue。它不签名、不合并、不发布 dist；fork PR 校验不运行 writer。main/受保护环境的 `prepare-reviewed.yml` 仍只准备未签名材料。作者仓库始终裸读，只有维护者固定的工具源代码被物化并执行。文件上传使用固定的 [actions/upload-artifact v4.6.2 提交](https://github.com/actions/upload-artifact/commit/ea165f8d65b6e75b540449e92b4886f43607fa02)，按其 [MIT 许可](https://github.com/actions/upload-artifact/blob/v4.6.2/LICENSE)；这是未来 CI 的主机依赖，不进入 APK。

按 DEC-12，**CI 不持有签名私钥，正式签名和 dist 发布只由所有者主机执行**；review job 的短期 GitHub 写 token 不属于签名钥匙。以上 CLI 与 writer 模板已具备本地实现，模板尚未启用、真实远端未运行。正式钥匙仍待 OWN-6，组织/仓库仍待 OWN-9；不能据此把整项 REG-1 或线上发布验收勾为完成。


## 自动发现 community（REG-3）

定时 `scan-tags.yml` 运行 `registry_ci.py --mode community`：固定搜索 GitHub
公开 topic `thinkos-plugin`，逐个裸读、自动校验，通过者直接进入**未签名**目录，
档位固定为 `community`，不增加逐项目人工审核或注册门槛。公开fork及归档项目同样进入校验；disabled项目若平台拒绝读取则记录实际验证失败，不按旗标静默排除。topic 不授予 `official`
或 `reviewed`。设备沿用 `plugins.community=false` 默认隐藏，档位展示和安装权限
确认仍走现有公开入口。最终整个批次由所有者本机签名、发布；`prepare` 只生成 unsigned 与
review-requests，独立可信 `review` job 为权限增加创建审核 PR、为移动 tag/
内容改变创建 issue，不签名或发布 dist。community 本身仍不经逐项目人工审核。

作者约定：仓库根有 `manifest.json`、`store.json`、原始许可文本和普通文件；tag
是 `v<manifest.version>`，插件 id 跨版本稳定。不扫描默认分支或随意猜子目录。
monorepo 使用已有 `main/plugins/<id>.json` 登记的 path/tag_prefix 路径作为例外，
按原登记流程校验。已登记仓库不会被 topic 改档；占用内置/其它登记/历史身份、
跨仓库同 id、有歧义的根身份、非法源均拒绝并在 community-validation 报告中列出。
同一 id 两个候选都不按遍历先后抢占。搜索未出现的历史项目不会被删除或吊销。

```sh
# 仅查看完整的公开发现；token 可选，只传环境变量名，不把值放在参数里。
python3 tools/registry_discovery.py \
  --cache /absolute/private/topic-cache --output /absolute/new-discovery.json

# 真正自动校验并生成 unsigned：使用固定登记仓库 commit 与正式 HTTPS blob 地址。
python3 tools/registry_ci.py --mode community \
  --repository ORG/thinkos-registry --commit FULL_40_CHARACTER_COMMIT \
  --workspace /absolute/new-registry-ci --blob-base https://example.org/immutable/ \
  --discovery-cache /absolute/private/topic-cache
```

首次没有旧分发目录时显式加 `--initial`；之后仍须旧目录与 tag-ledger 的真实公钥
验证，不能因 discovery 成功省略历史认证。可选 `--github-token-env GITHUB_TOKEN`
只从该环境变量读取 Bearer，token 既不是通道/系统/仓库签名钥匙，也不进 argv、
子进程环境、缓存或报告。CLI 不接受任意 API origin；本地 HTTP 只在测试中注入。
CI 使用只读、短期 `${{ github.token }}`；curl 不读取用户 curlrc、不跟随重定向，
保留默认 TLS 验证，没有 HTTP 或跨主机回退。

API 与缓存的明确边界：

- 固定 `GET /search/repositories`、`topic:thinkos-plugin is:public fork:true`，
  使用 `Accept: application/vnd.github+json`、应用 User-Agent、API `2022-11-28`。
- 每响应最多 2 MiB，单 curl 总时限15秒并有父进程超时兜底；整个发现60秒。
  串行通过受同源/同 query 校验的 `Link rel=next`，最多2页（100项/页）。
  默认最多32个搜索结果，`--community-max-repos` / `--max-repositories` 可显式
  调到128；越界失败，不静默截断。GitHub每次搜索本身最多1000条。
- `incomplete_results`、缺页/数量变化/重复、超限、网络/格式异常均清空本轮可导入
  结果并返回 complete=false；CI 在生成新目录前停止，旧分发与历史不变。
- 只缓存投影后的公共 repo/owner/id、页总数、next、ETag，不存完整响应或错误体。
  相同 URL 发送 If-None-Match，304必须有合法既有缓存；失败不把旧缓存当新成功。
  完整新query参与URL摘要缓存key，旧archived过滤查询的页面不复用、也不擅自删除。
  缓存目录须0700且由当前主机用户持有，缓存写入有进程锁。主机可持续复用；
  GitHub-hosted runner默认冷缓存，上传的是无凭据缓存材料，不冒称跨run自动恢复。
- 403/429不重试，记录有界 retry-after 秒数或 x-ratelimit-reset 时刻（否则60秒），
  缓存保存非秘密not-before时刻，下次使用同缓存时到期前不发请求；外部调度到期后
  重跑。鉴权/非鉴权相邻页分别至少隔2.1/6.1秒，避免并发突发。
- 作者校验最多10分钟后停止启动新操作；每个Git子进程仍有既有CPU/文件/输出/
  60秒边界。每仓库最多256个tag，只处理最高3版和移动过的历史tag；总计划仍受
  原256项/64KiB边界。输出报告不把“待审核请求”写成已经开出远端PR。

官方依据（2026-11-05只读核对）：[Search repositories](https://docs.github.com/en/rest/search/search#search-repositories)、
[topic语法](https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories#search-by-topic)、
[fork:true包含fork及非fork](https://docs.github.com/en/search-github/searching-on-github/searching-in-forks#repository-search)、
[Link分页](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)、
[条件请求和限流处理](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)、
[API版本](https://docs.github.com/en/rest/about-the-rest-api/api-versions)。2022-11-28仍受支持，
不会因为当前文档示例使用新版而悄悄改变响应契约。

本地测试源码 `tools/tests/test_registry_discovery.py` 使用自有loopback HTTP与真实
bare对象，覆盖分页/304/限流/不完整/恶意数据、无凭据落盘、自动community入unsigned、
重名/占用/内置身份、symlink/不执行脚本以及既有移动tag/增权规则；不依赖公网数据。
