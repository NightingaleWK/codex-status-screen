# 跨层检查

状态链路是 Codex 的桌面会话日志 → `collector/source.go` 的快照 → `collector/server.go` 的 HTTPS 响应 → Android 客户端验证 → 快照缓存、状态屏和提醒。[会话读取](../backend/source-reader.md) · [局域网接口](../backend/lan-api.md) · [Android 设备状态](../frontend/device-state.md)

按改动选取对应检查项。

- **新增或调整状态字段**。同时定位 Go 的 JSON 标签和 Android 字段消费点。检查 Unix 毫秒、采集端日期、空列表及上次成功快照在界面上的含义。[快照类型](../../../collector/source.go) · [状态、提醒数据入口](../../../android/src/local/codex/statusscreen/MainActivity.java)
- **调整配对、DNS-SD 或端口**。核对配对页二维码、手动输入和客户端默认配置。用已有授权检查服务端证书、token 与设备 ID 的连续性，再经过新地址验证及失败回退。[局域网接口](../backend/lan-api.md) · [本机身份](../backend/identity-storage.md) · [地址迁移](../frontend/device-state.md)
- **调整采样或在线判定**。分别验证采集成功、采集部分失败、整体失败与手机网络断开的表现。Windows 托盘根据手机来访计数，Android 根据各采集器最近响应判断掉线。[Windows 错误状态](../backend/error-handling.md) · [Android 提醒](../frontend/alerts.md)
- **调整完成事件**。从同一会话多次 turn、短时间批量完成和同毫秒记录检查列表汇总与提醒；将消费者验证连到已有 Go 和 Java 回归入口。[Windows 验证](../backend/quality-guidelines.md) · [Android 验证](../frontend/quality-guidelines.md)
