# Windows 设计参考与原生 Aero 主页

2026-10-31，按用户要求实查 GitHub 与微软原始文档。页面实现分别是 `TileView` / `AeroHomeView`；不依赖参考项目的运行时，不复制微软字体、壁纸、图标资源或开源实现代码。

## 参考来源

| 来源 | 核对内容 | ThinkOS 的采用方式 |
|---|---|---|
| [windowsphonelauncher](https://github.com/jovanovski/windowsphonelauncher)（GPL-3.0；本地只读快照 f5a14c9） | 阅读 TileView、TileGridLayout：居中图形、固定底边名称、动态内容与名称分开、方形基础单元 | 图形与数据有不同密度；名称在翻面时固定；不移植 Android 10+ 依赖或字体 |
| [GrooveLauncher](https://github.com/oscardiaz28/GrooveLauncher) | README/项目结构与展示：紧密、多尺寸的动态磁贴首页 | 参考密度与色块组织；其 Web 技术不进入 ThinkOS 主界面 |
| [MetroWinLauncher](https://github.com/developerkenshi-coder/MetroWinLauncher) | 项目展示：Start、动态内容、系统入口的整体秩序 | 对照视觉层级；ThinkOS 保留原生 View 与既定手势 |
| [7.css](https://github.com/khang-nd/7.css)（MIT；只读快照 76d29ab） | 阅读 gui/_window.scss 的玻璃叠层、浅色内边线、深色外边线、斜向高光 | 在 Canvas 中独立实现固定窗口外壳与渐变光泽，无 CSS 或模糊依赖 |
| [RetroBar](https://github.com/dremin/RetroBar) | 项目展示的 Vista 等经典任务栏、按钮和通知区 | 参考材质、按钮分组和托盘节奏；不把它误称为现成的 Windows 7 主页 |
| [windowslauncher](https://github.com/jovanovski/windowslauncher) | README 的原生 Android 经典桌面/开始菜单布局 | 参考桌面、任务栏、开始菜单三层关系，不导入较新 Android 依赖 |

微软原始依据：[Windows 8/8.1 磁贴模板目录](https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh761491(v=win.10)) 给出图形、品牌区、数字与文字的模板组合；[Windows Phone 设计建议](https://blogs.windows.com/windowsdeveloper/2013/01/18/8-tips-for-designing-windows-phone-apps/) 用于核对排版和内容优先原则。开源模仿项目用于交叉参考，不当作微软规范。

## 磁贴的具体修订

- 网格保持统一方形单元和 8dp 间距，色块直角 0dp，无投影、描边或卡片圆角。
- small 以居中图形为主；medium 有值时形成图形/大数字组合，无值时放大居中图形。数字使用系统细体，名称固定底边，角标在末端底边。
- 动态内容在固定名称上方纵向移出/移入；页面只允许一张翻面，延续统一间隔、停止条件和减少动画设置。
- 不整块降低 AMBIENT 透明度，否则自动黑白前景的对比度计算失效。低注意状态通过停止动态及位移表达。
- 不添加桌面编辑、拖动排列、应用列表等本次范围外功能；长按继续是设置。

## Aero 页面

`plugins/aero` 只声明 `layout: aero` 与 clock.desktop、weather.now、gallery.preview 三个引用。clock.desktop 是普通公开 glance 卡片，仍可被其它页面引用；没有 Aero 私有数据通道。

横屏为错落的三扇窗口：模拟钟、天气、完整照片；竖屏为上方宽钟窗、下方天气/照片双窗。底部玻璃任务栏包含 ThinkOS 原创球形入口、窗口快捷按钮与本地时间/日期。开始菜单打开这些功能及系统设置；横滑切页、长按所属插件设置保持一致。

所有窗口外壳、字号、颜色、圆角与布局是 OS 固定词汇。桌面背景是原创 Canvas 丝带和渐变，玻璃为静态合成光泽，不实时采样背后内容。Aero 的 5dp 窗口圆角不影响磁贴。

API 5 在共享 schema 先登记，Python/Kotlin/Go 沿用同一版本表；API 1–4 使用 aero 必须拒绝。不修改现有页面的声明，也不替换默认 ambient/sleep 角色。Aero 作为独立普通页面可横滑切换。

## 性能与验证

性能短实验、最终 600 秒磁贴场景以及 Aero 真机截图/交互结果记录在 `24-tiles-validation.md`。任何中途切页、输入、重启或覆盖缺口都不能偷偷删去再宣布通过。视觉最终版完成后重新测完整场景，短测不替代验收。


## 原生缓存与模拟钟

静态窗口外壳与文字由后台线程栅格化；UI 线程仅复制尺寸、颜色、字符串、TextPaint 与排版参数，不在后台访问 View。每窗最多一个待处理任务，旧代际结果不回写；显示中的 Bitmap 不改写、不 recycle。照片、图标、其它 OS 渲染组件保留真实 View，不被静态缓存冻结。

Aero 模拟钟采用独立的缓存表盘和三根窄硬件指针，由 RenderThread 驱动可见的平滑扫秒；UI 只需按秒校时。后台或 AMBIENT 停止平滑运动，减少动画时直接显示当前角度。普通 ClockView 与磁贴渲染不改变。真机截图像素测试确认两次 UI 秒更新之间仍有真实指针运动。

位图提前上传沿用 Android API 24 起提供的 [Bitmap.prepareToDraw](https://developer.android.com/reference/android/graphics/Bitmap#prepareToDraw())；GPU 上传与每实例硬件层仍有成本，是否达标以真机帧记录为准。


任务栏和菜单仍保留原生 TextView 的排版、点击和无障碍语义，字形位图在后台生成后替换；分钟更新不在 UI 绘制阶段重新塑形。每个标签只保留当前位图和一个待处理任务，不会积累历史时间字符串。
