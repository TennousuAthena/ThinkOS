# 38 · 全系统的系统栏与边缘手势

2026-11-03，所有者最新要求。取代ADR-045里“底边回应用页/离开前页”和“外部应用保留安卓原手势”的规则；也取代APP-4只隐藏状态栏、保留安卓导航栏作为主返回入口的目标。旧报告保留为历史功能证据，不能当作本目标已完成。

| 范围 | 行为 |
|---|---|
| 所有应用，包括安卓设置和第三方应用 | 屏蔽安卓通知栏/快捷设置与底部系统导航栏，边缘滑动不再闪出这些栏 |
| 物理最左缘右滑 | 返回：ThinkOS内沿用NavStack和原生弹层顺序；外部应用获得真正KEYCODE_BACK，先关键盘/弹层或退一层 |
| 物理底缘上滑 | 清空ThinkOS返回栈并回home角色的首页；不再回launcher角色的应用磁贴页或外部应用离开前页面 |
| 物理顶缘下拉 | 同一个ThinkOS PanelHost；外部应用不暂停，视频继续播 |

排版按所有者最新要求主要检查中文。媒体音量持续0；功能优先，性能后置。不实现Server。不安装持久模块、不改变Device Owner、不重启；这些仍由人工完成。

## 实现边界

应用不执行su、不读取触摸设备、不新增播放器，不给Lua增加私有手势或播放通道。ThinkOS内的手势仍由ThinkStage处理，目标通过home角色查找；launcher角色和公开tos.apps.open("launcher")继续用于“应用”按钮。

外部顶缘由不获取焦点的窄条叠层接收，复用APP-4的PanelHost。协议2在ThinkOS之外的窗口都提供控制栏，包括维护菜单打开的安卓设置；协议1保留只在主动外部会话显示的兼容行为。物理左缘的透明窄条保留触摸流，避免外部抽屉和系统返回同时触发；宽度使用nav.back_edge_dp，不获取焦点、不注入。关闭可选控制栏不会关闭必要的左缘返回。

物理左缘和底缘由Android 8.1已有的SystemGesturesPointerEventListener回调识别：onSwipeFromLeft执行固定Back，onSwipeFromBottom发私有的无内容Home信号。ThinkOS基础窗口的Left交给ThinkStage；原生弹窗、IME触摸区域使用系统Back。展开的外部PanelHost先收起，模块读取同包的真实窗口记录避免再返回底下应用；收起动作在UP提交。底缘Home也覆盖ThinkOS自己的弹窗/键盘窗口，接收者清理面板和本地弹窗、取消未结束的触摸、清空NavStack，以singleTask/CLEAR_TOP复用MainActivity并清掉上方Activity。

真正的外部Back仅在thinkos-immersive的system_server钩子内注入。没有接收应用“请求任意注入”的广播、socket或插件接口。模块只构造一个KEYCODE_BACK的DOWN/UP，投递前复核焦点窗口没有变化；注入失败就恢复安卓系统栏出口。AOSP android-8.1.0_r1已核对左右回调，不读取原始触摸设备。[Android 8.1系统手势源码](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/services/core/java/com/android/server/policy/SystemGesturesPointerEventListener.java)

thinkos-shell升级为全局隐藏状态栏和导航栏的固定事务，保存/还原原policy_control/appop/运行期标志。最后的属性写入完成校验后，向不导出的ShellChangedReceiver发固定刷新通知，让已在后台的窗口重新读取完整策略；通知不携带输入或导航请求。thinkos-immersive全局抑制requestTransientBars，并在updateSystemBarsLw计算前后保留隐藏系统栏的标志，覆盖Android 8.1在IME出现时清除导航栏隐藏标志的路径。只有新APK声明支持、悬浮窗权限、完整隐藏策略和全部必要钩子都就绪时才进入全局模式；固定Back失败恢复安卓出口。旧模块/缺模块是兼容降级，不能算全局目标完成。

全局模式下返回与首页是必要出口，旧的nav.back_enabled/nav.up_enabled只保留隐藏兼容值，不再在普通设置中列出；协议2启用时不会让旧的关闭值堵死全局返回。触发区和动画继续使用OS规则，不向插件开放注入。

## 验收

- [x] ThinkOS里中文横竖屏：应用、设置、视频、首页的底缘上滑到home，栈清空；控制栏打开时左缘先收起，底缘仍回首页。2026-11-03，home-v5/results.json 15项，APK ae97dc17…；旧的逐层返回另有APP/NAV证据，原生模块联动待下项。后续APK各自的回归范围见对应报告，不把此检查点冒充为全部后续版本已重测。
- [ ] 独立外部测试APK的左缘右滑触发真实Back；输入法/弹层的Back顺序正常，非边缘内容滑动不受影响。
- [ ] 外部应用和安卓设置的底缘上滑都回ThinkOS首页，恢复Lock Task，不多开Activity；冷进程也能回来。
- [ ] 外部视频上方的顶部下拉仍不暂停Activity或播放；窗口收起后触摸交还。
- [ ] 安卓通知/快捷设置和系统导航栏在全部上述场景中都不出现，包括临时栏；横屏左右位置和角落起手也覆盖。
- [ ] 非授权应用不能请求注入；固定桥异常、旧APK/旧模块、缺模块的路径有界且可退出。
- [ ] 主机、构建、ABI、真机功能与恢复记录齐全，媒体音量0；持久模块安装后的系统框架联动由人工完成。

当前设备仍安装thinkos-immersive 1.0，不能证明全局系统栏抑制。2026-11-05补修固定Back失败时ThinkOS原生弹层仍吞系统栏的问题：故障后实际transient-bar guard及visibility计算均保留安卓出口，健康状态的旧ThinkOS兼容抑栏不变。新版1.2已重新构建，69项immersive主机检查、18项system主机检查通过；Shell1.1固定事务此前11项通过并已重新构建。新产物摘要见`artifacts/app/APP-8/global/0.1.11/builds.json`，没有安装或重启。普通APK只含armeabi-v7a、min/target27。既有分层证据和保留失败见`artifacts/app/APP-8/global/report.md`与`qualification.json`，不把临时app_process或同UIDHome夹具算成真实系统回调加载。

持久模块由人工按system/README.md安装、启用系统框架作用域并重启。之后可运行`python3 tools/tests/global_back_device.py --native-gestures --output artifacts/app/APP-8/global/module-device`；此模式必须看到已安装1.2及协议2，只读检查就绪条件，不安装、不重启、不临时覆盖系统策略；真实物理Left/Bottom与顶缘控制栏、静音视频、两条系统栏状态会逐项记录。IME/权限窗口与角落起手仍需完成相应实机验收，不能只看脚本的complete字段。
