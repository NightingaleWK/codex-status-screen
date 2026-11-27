# Android 布局与全屏

## 方向和交互

- `wide()` 在横屏且可用宽度至少 `520dp` 时启用双栏。左列展示运行任务和设备状态，右列展示今日完成。其他窗口尺寸沿用纵向布局。设备选择器在宽屏放到工具栏，窄屏另起一行。[wide、toolbar 与 renderStatus](../../../android/src/local/codex/statusscreen/MainActivity.java)
- 方向设定以 `system`、`portrait`、`landscape` 保存在 `direction`。工具栏图标一键切换当前方向，设置页可恢复跟随系统。配置变化使用 `onConfigurationChanged` 重新排版。[方向处理](../../../android/src/local/codex/statusscreen/MainActivity.java) · [Activity 配置](../../../android/AndroidManifest.xml)
- 工具栏用 `Glyph.Icon` 和 `icon` 布置主题、横竖屏和设置入口。复用图标按钮的无障碍描述、提示文字和 `48dp` 容器。颜色取自 `palette`，主题支持 `system`、`light`、`dark`。[图标组件](../../../android/src/local/codex/statusscreen/Glyph.java) · [工具栏与调色](../../../android/src/local/codex/statusscreen/MainActivity.java)
- 轮询导致的重绘走 `scheduleRender`，保留滚动位置并等手势结束后更新。常亮由 `applyAwake` 写入窗口的 `FLAG_KEEP_SCREEN_ON`，设置项 `awake` 默认开启。[手势、重绘与常亮](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 摄像头安全区与边缘提醒

窗口通过 `frame`、`contentFrame` 两层处理边到边显示。外层 `frame` 承载背景及 `startEdge` 创建的全尺寸提醒视图，交互内容放进 `contentFrame`，安全区 padding 只施加在这一层。保留两层结构可以让边缘提示照亮挖孔一侧可显示的屏幕区域，同时保持相关文字和按钮可读可点。[窗口初始化、startEdge 与 applyWindowMode](../../../android/src/local/codex/statusscreen/MainActivity.java)

状态屏隐藏系统栏，设置页显示系统栏。Android 11 及以上使用 `WindowInsets` 与 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，旧版本走兼容分支并扣除系统窗口已占的偏移。改窗口或根布局时，在横屏和竖屏分别核对背景、边框、工具栏、系统栏和摄像头位置。[全屏实现](../../../android/src/local/codex/statusscreen/MainActivity.java) · [提醒收起行为](./alerts.md)
