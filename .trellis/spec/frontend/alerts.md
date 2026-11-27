# Android 完成与掉线提醒

## 产生事件

- `DeviceEvents` 用单调时钟维护在线状态，收到一次成功响应后才开始掉线判定。距最近成功响应至少 `9800ms` 时，`takeOffline` 对每次掉线仅返回一次事件，下一次成功响应重新建立在线状态。[状态机](../../../android/src/local/codex/statusscreen/DeviceEvents.java) · [前台刷新](../../../android/src/local/codex/statusscreen/MainActivity.java)
- `noticeCompletions` 只用 `sourceReady=true` 且 `partial=false` 的快照更新提醒基线。首次快照建立基线，后续用最新完成时间以及该时间点已有的 `thread/turn` 区分新完成；同毫秒有多个不同任务时可分别被识别。[完成输入过滤](../../../android/src/local/codex/statusscreen/MainActivity.java) · [完成去重](../../../android/src/local/codex/statusscreen/DeviceEvents.java)
- `onStart` 调用 `startMonitoring` 重建各设备基线，`onStop` 停止前台刷新并释放音效、边框和发现资源。应用在前台监看时发出提醒，返回页面后先接收状态作为基线。[Activity 生命周期](../../../android/src/local/codex/statusscreen/MainActivity.java) · [基线回归](../../../android/test/local/codex/statusscreen/DeviceEventsTest.java)

## 提醒与试播

- `signal` 按 `Signal.DONE`、`Signal.OFFLINE` 应用两类提醒开关。`edge` 控制提醒边框，`sound` 控制声音及系统通知路径。默认使用各自的系统通知频道，启用 `mediaAlertSound` 后由 `MediaPlayer` 走媒体音量，获通知权限时另发静音通知。[提醒分发](../../../android/src/local/codex/statusscreen/MainActivity.java)
- `startEdge` 在根容器画绿色或橙色边框。前 8 秒为加强边框，系统允许动画时缓慢呼吸，随后变为细边并保留到点「知道了」、退出监看或下一次边缘提醒。全尺寸边框的父容器约定见 [界面与全屏](./dashboard-layout.md)。[边框生命周期](../../../android/src/local/codex/statusscreen/MainActivity.java)
- 设置页「试听」调用 `chooseTone / playTone`，只播放选中的媒体音效。完整提醒测试调用 `preview / signal`，用于检查权限、声音和边框实际表现。[试听与测试入口](../../../android/src/local/codex/statusscreen/MainActivity.java)
- 音源 `chime.wav` 与 `disconnect.wav` 由构建脚本分别生成并纳入 `R.raw`。音效变更从生成脚本入手。[音源生成](../../../tools/generate-chime.mjs) · [资源打包](../../../tools/Build-Android.ps1)

测试关注离线临界值、连续失败只提醒一次、恢复后的再次掉线、首次快照、同毫秒多条记录及恢复前台后的基线。现有无 Android 依赖的回归入口是 [DeviceEventsTest.java](../../../android/test/local/codex/statusscreen/DeviceEventsTest.java)。
