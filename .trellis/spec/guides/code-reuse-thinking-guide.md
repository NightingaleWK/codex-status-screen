# 现有代码复用入口

准备增加逻辑时，先从现有拥有者及其测试定位扩展点。

| 场景 | 当前入口 |
| --- | --- |
| 识别 Codex 事件或活动阶段 | `rollout.consume` 与 `snapshot`。[会话读取](../../../collector/source.go) |
| 在 Windows 读取 Codex 文件 | `openSharedRead`，共享读、写和删除。[Windows 句柄](../../../collector/platform_windows.go) |
| 扩展配对或状态错误 | `writeJSON`、`writeError` 与已授权状态请求。[服务端](../../../collector/server.go) |
| 修改设备地址或身份验证 | `CollectorClient.endpoint`、`status`，结合 `MainActivity.acceptStatus`。[HTTPS 客户端](../../../android/src/local/codex/statusscreen/CollectorClient.java) · [状态接收](../../../android/src/local/codex/statusscreen/MainActivity.java) |
| 修改完成判断或掉线时机 | `DeviceEvents.observeCompletions`、`takeOffline`。[事件状态机](../../../android/src/local/codex/statusscreen/DeviceEvents.java) |
| 新增状态屏图标、样式或双栏内容 | `Glyph.Icon`、`icon`、`palette`、`showColumns`。[图形组件](../../../android/src/local/codex/statusscreen/Glyph.java) · [现有界面](../../../android/src/local/codex/statusscreen/MainActivity.java) |
