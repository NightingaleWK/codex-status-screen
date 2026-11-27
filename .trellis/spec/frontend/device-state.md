# Android 设备连接与状态

## 组件归属

| 入口 | 负责的行为 |
| --- | --- |
| [CollectorClient.java](../../../android/src/local/codex/statusscreen/CollectorClient.java) | HTTPS 地址标准化、证书指纹、状态请求与设备身份校验 |
| [DeviceStore.java](../../../android/src/local/codex/statusscreen/DeviceStore.java) | 已配对设备与快照存取、同 ID 设备更新 |
| [Discovery.java](../../../android/src/local/codex/statusscreen/Discovery.java) | DNS-SD 解析，提供候选地址 |
| [MainActivity.java](../../../android/src/local/codex/statusscreen/MainActivity.java) | 配对、轮询协调、地址验证与状态展示 |
| [ScanActivity.java](../../../android/src/local/codex/statusscreen/ScanActivity.java) | 摄像头扫码，返回原始 `pairing` 字符串 |

手动和扫码配对最后都调用 `CollectorClient.pair(address, code, fingerprint)`。手输地址可省略 HTTPS 前缀和端口，规范化为仅含主机与端口的 HTTPS 地址，默认端口为 `8767`。二维码只由 `MainActivity.onActivityResult` 解码协议字段，请求由客户端校验。[配对入口](../../../android/src/local/codex/statusscreen/MainActivity.java) · [连接校验](../../../android/src/local/codex/statusscreen/CollectorClient.java) · [配对协议](../backend/lan-api.md)

在私有 `settings` SharedPreferences 中，`DeviceStore` 用 `devices` JSON 数组保存 `id`、`name`、`endpoint`、`pin`、`token` 与最近快照。重新配对同一 ID 时用新的连接资料替换记录，并沿用用户设备名称及缓存快照。[存储与替换](../../../android/src/local/codex/statusscreen/DeviceStore.java) · [私有设置入口](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 轮询与地址迁移

- `onStart` 开始监看和发现，前台刷新约每 3 秒尝试轮询一轮。每台设备的请求由 `polling` 和 `checkingAddress` 限制并发。网络操作在执行器运行，设备状态和视图更新回到主线程。[主界面生命周期与 poll](../../../android/src/local/codex/statusscreen/MainActivity.java)
- DNS-SD 只给已配对 ID 提供候选地址。`poll` 先尝试候选地址，失败则回退保存的地址。手动地址修改也调用 `CollectorClient.status(endpoint, pin, token, id)` 验证，再由 `acceptStatus` 保存。[候选地址与手动修改](../../../android/src/local/codex/statusscreen/MainActivity.java) · [身份验收](../../../android/src/local/codex/statusscreen/CollectorClient.java)
- 接收异步结果时检查设备对象是否仍在列表和轮询记录的 `endpointRevision` 是否匹配。手动验证成功时递增 revision，防止较早的结果覆盖新地址。[回调门槛](../../../android/src/local/codex/statusscreen/MainActivity.java)
- 连接失败使用面向用户的操作提示。对同一设备，轮询错误分类变化时用 `CodexStatus` 标签记录异常类和根因类，成功响应后清空错误状态。[poll 与 networkFailure](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 汇总与就绪状态

`renderStatus` 只将在线且 `sourceReady=true` 的设备计入运行任务。设备连通、等待日志、部分日志可读和掉线有各自的状态展示。当天完成按设备快照的 `day` 与手机本地日期匹配，要求 `observedAt > 0`，因此离线设备的同日缓存仍可展示。[状态汇总与设备行](../../../android/src/local/codex/statusscreen/MainActivity.java)

「今日完成」总数相加 `completedCount`，列表按设备 ID 和会话 ID 合并，提醒则按每台设备的 `thread/turn` 判重。这三处保留各自的计数粒度。[状态与提醒输入](../../../android/src/local/codex/statusscreen/MainActivity.java) · [事件去重](../../../android/src/local/codex/statusscreen/DeviceEvents.java)
