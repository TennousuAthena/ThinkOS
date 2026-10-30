# assets/

设计与动效资源。

```
assets/
└── lottie/        # Lottie 动画源文件（.json）
```

## 约定

- 只放**源文件**，不放导出产物（apk 里的资源由构建复制）
- 命名：`<场景>-<变体>.json`，例如 `idle-breath@v2.json`
- 每个动画必须有预览（mp4/gif 放同目录，体积小于 2MB）
- 约束：单个动画图层 ≤ 40，文件 ≤ 300KB（见 `../docs/04-ui-spec.md`）
- 需要授权的第三方资源必须在同目录写 `LICENSE.txt`
