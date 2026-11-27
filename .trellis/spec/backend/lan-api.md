# 局域网接口 v1

适用于更改采集器 API、配对二维码、状态字段和局域网发现。路由与 JSON 输出由 `server.go` 定义，Android 在 `CollectorClient` 验证连接和协议，`MainActivity` 消费状态字段。[服务端](../../../collector/server.go) · [客户端](../../../android/src/local/codex/statusscreen/CollectorClient.java) · [字段消费](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 监听器与入口

| 入口 | 当前契约 |
| --- | --- |
| `GET /health` | 局域网 HTTPS，返回 `version: int` 与 `ready: bool`，无须 token |
| `POST /v1/pair` | 局域网 HTTPS，输入 `{"code":"12345678"}`，返回 `version`、`device`、`token` |
| `GET /v1/status` | 局域网 HTTPS，要求 `Authorization: Bearer <token>`，返回 `version`、`device`、`sourceReady` 与展平的快照字段 |
| `GET /` | 本机 HTTP 配对页，绑定环回并校验 `Host`；二维码为 `{v, endpoint, pin, code}` |

局域网 HTTPS 默认 TCP `8767`，最低 TLS 1.2。本机配对页默认 TCP `8768`。JSON 的 `version` 和二维码的 `v` 均为数字 `1`；`device` 对象包含字符串 `id`、`name`。Android 扫码后将 `endpoint`、`code`、`pin` 交给与手动配对相同的连接入口。[监听与路由](../../../collector/server.go) · [扫码回调](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 状态字段

| 字段 | 类型与语义 |
| --- | --- |
| `sourceReady` | 布尔值，本轮读取是否产生快照。读取失败时仍可带上次成功快照 |
| `partial` | 布尔值，成功快照内仍有部分日志或索引读取失败 |
| `observedAt`、`day` | 快照时间为 Unix 毫秒，`day` 为采集器本地 `YYYY-MM-DD`。初始空快照的时间为 `0` |
| `tasks[]` | `thread`、`turn`、`title`、`stage` 为字符串，`start` 和 `update` 为 Unix 毫秒 |
| `completedCount`、`lastCompleted[]` | 当天完成总数与最近 12 条记录。记录含 `thread`、`turn`、`title`、Unix 毫秒 `at` |
| `live`、`tasks[].requests` | v1 兼容字段，当前采集分别给出 `false` 与空数组 |

字段以 `snapshot`、`task`、`completedTask` 的 JSON 标签为准。初始快照的两张列表也编码为空数组，便于客户端区分列表和缺失数据。[数据类型](../../../collector/source.go) · [初始快照与响应](../../../collector/server.go)

## 授权与失败

| 条件 | 响应 |
| --- | --- |
| 8 位配对码有效且授权落盘成功 | `200`，返回 43 字符 URL-safe token，当前配对码作废 |
| 配对码错误、过期、已用或失败次数已达上限 | `401`，返回 `{"error":"…"}`。配对码有效期 5 分钟，最多允许 12 次错误尝试 |
| 状态 token 缺失、长度不符或无对应授权 | `401`，有效授权请求才更新在线计时 |
| 持久化配对失败 | `500`，客户端收到通用错误，进程用上下文记录原因 |
| 路由或方法不匹配 | `404` |

配对请求读取上限为 2048 字节。Android 校验证书有效期与 DER 证书的 SHA-256 指纹，首次配对接受前 16 个或完整 64 个十六进制字符，成功后保存完整指纹。状态请求还比较返回的设备 ID，并复用采集器发放的 token。Android 禁用代理与跳转，继续使用身份校验处理地址变更。[配对与状态处理](../../../collector/server.go) · [客户端信任校验](../../../android/src/local/codex/statusscreen/CollectorClient.java) · [本机身份记录](./identity-storage.md)

DNS-SD 在局域网发布 `_codexstatus._tcp`，TXT 提供 `id` 和 `v=1`。Android 使用 `id` 为已配对设备提示候选地址，保存地址之前仍走原指纹、token 与设备 ID 校验。[服务发布](../../../collector/discovery.go) · [Android 服务发现](../../../android/src/local/codex/statusscreen/Discovery.java) · [地址验证](../../../android/src/local/codex/statusscreen/MainActivity.java)

## 协议验证

修改响应时同时检查 Go 序列化与 Android 字段读取，并按 `TestExistingPairingAndOnlineCount` 验证旧 token 可用、未授权请求返回 `401`、配对码成功后无法复用。调采集就绪或完成字段时同步检查 Android 的运行任务筛选和完成提醒门槛。[服务端测试](../../../collector/server_test.go) · [Android 设备状态](../frontend/device-state.md) · [提醒事件](../frontend/alerts.md)
