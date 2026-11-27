# Android 状态屏规范

适用于 `android/` 原生 Java App。`MainActivity` 负责状态屏与设置，`ScanActivity` 通过 ZXing 扫码。应用使用项目内 PowerShell 脚本编译和签名，包名为 `local.codex.statusscreen`。[应用入口](../../../android/AndroidManifest.xml) · [构建脚本](../../../tools/Build-Android.ps1)

| 规范 | 使用场景 |
| --- | --- |
| [设备连接与状态](./device-state.md) | 配对、轮询、地址变更、持久化、状态汇总 |
| [界面与全屏](./dashboard-layout.md) | 横竖屏、深色模式、常亮、安全区和图标交互 |
| [完成与掉线提醒](./alerts.md) | 提醒判定、通知音、试听和边缘光效 |
| [验证与构建](./quality-guidelines.md) | JVM 回归、APK 构建及真机检查 |

## 开发前检查

- 网络请求复用 `CollectorClient`，本机设备配置通过 `DeviceStore` 维护。改动配对或快照字段时，查阅 [采集器接口](../backend/lan-api.md)。[HTTPS 客户端](../../../android/src/local/codex/statusscreen/CollectorClient.java) · [设备持久化](../../../android/src/local/codex/statusscreen/DeviceStore.java)
- 先定位改动属于状态解析、事件判定还是渲染。`DeviceEvents` 是纯 Java 事件判断入口，窗口与提醒渲染由 `MainActivity` 负责。[事件逻辑](../../../android/src/local/codex/statusscreen/DeviceEvents.java) · [主界面](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 质量检查

- 事件逻辑调整后运行 `DeviceEventsTest`，涉及应用代码或资源时验证构建。[验证与构建](./quality-guidelines.md)
- 布局或提醒变化在横屏与竖屏检查。带挖孔的设备要同时确认控件安全区与背景、提醒边框的覆盖范围。[界面与全屏](./dashboard-layout.md) · [提醒渲染](./alerts.md)
