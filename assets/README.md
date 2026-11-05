# assets/

设计与动效资源。

```
assets/
├── lottie/        # Lottie 动画源文件（.json）
└── icons/         # 取自 Iconfont 的图标（SVG）与登记表 glyphs.json
```

## 约定

- 只放**源文件**，不放导出产物（apk 里的资源由构建复制）
- 命名：`<场景>-<变体>.json`，例如 `idle-breath@v2.json`
- 每个动画必须有预览（mp4/gif 放同目录，体积小于 2MB）
- 约束：单个动画图层 ≤ 40，文件 ≤ 300KB（见 `../docs/04-ui-spec.md`）
- 需要授权的第三方资源必须在同目录写 `LICENSE.txt`

## icons/

图标**不自己画**，从 Iconfont 取，符合设计规范（`../docs/04-ui-spec.md`：扁平、单色、2dp 描边、圆头端点、不用渐变；同一套里风格统一）就可以用。现在这批取自合集“Lucide”（来源与许可证见 `../THIRD_PARTY_NOTICES.md`）。

- `glyphs.json`：OS 里的图标名 → SVG 文件，加上它在 Iconfont 上的编号；`lucide/` 是 SVG 本身（只含一条 `<path d="…">`，viewBox 为 0 0 1024 1024）。
- 加一个图标：把 SVG 放进 `lucide/`（或同级的新目录），在 `glyphs.json` 里加一行，再 `python3 tools/gen_glyphs.py`。它会生成 `res/drawable/glyph_<名字>.xml` 和名字到资源的对照表 `ui/GlyphResources.kt`；`--check` 验证没有过期。
- `GlyphResources.kt` 是生成的名字表，`IconView.names` 自动包含它；没有额外的 `staticNames`。用于表单分类的矢量名还要同步 `shared/schema/plugin/layouts.json` 的 `formIcon`，`GlyphsTest` 检查两者一致。
- 渲染：这些图标是矢量，按显示的实际大小直接画，不从位图缩小，所以边缘不会发毛。

SET-07 的 `monitor`、`map-pin`、`house`、`sliders-horizontal`、`puzzle`、`moon-star`、`sunset`、`bluetooth` 于2026-11-05从同一合集的公开目录 `https://www.iconfont.cn/api/collection/detail.json?id=54062` 取得，保留原始 `show_svg`；真实编号登记在 `glyphs.json`，合集返回许可 `isc`、上游 `https://lucide.dev/`。这批未使用来源替代或自行绘制。
