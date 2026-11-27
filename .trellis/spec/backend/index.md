# Windows Go 采集器规范

适用于 `collector/` 及 Windows 打包脚本。采集器是独立的托盘进程，通过 Windows 共享只读句柄观察 Codex Desktop 的会话日志，并向已配对的 Android 设备提供状态。[采集入口](../../../collector/main_windows.go) · [日志读取](../../../collector/source.go)

| 规范 | 使用场景 |
| --- | --- |
| [目录、进程和配置](./directory-structure.md) | 调整入口、托盘、监听地址、端口或发现行为 |
| [Codex 会话读取](./source-reader.md) | 调整日志筛选、增量解析、运行阶段或完成记录 |
| [局域网接口](./lan-api.md) | 调整配对页、HTTP 路由、状态字段或发现协议 |
| [本机身份与配对记录](./identity-storage.md) | 调整证书、客户端授权或旧配对兼容 |
| [错误处理与诊断](./error-handling.md) | 调整启动失败、采集降级或错误响应 |
| [验证与构建](./quality-guidelines.md) | 验证 Windows 共享读取、配对兼容和托盘包 |

## 开发前检查

- 找到变更落在会话读取、局域网接口、身份存储还是 Windows 原生入口。触及 Android 消费方时读 [跨层检查指南](../guides/cross-layer-thinking-guide.md)。
- 检查会话源路径和采集器自有数据路径的边界。日志读取沿用 `openSharedRead`，身份写入沿用 `saveIdentity`。[Windows 文件句柄](../../../collector/platform_windows.go) · [本机身份](../../../collector/identity.go)

## 质量检查

- 在 Windows 运行 `go test -mod=readonly ./collector`。涉及启动、托盘或构建时，再检查生成的便携包。[验证与构建](./quality-guidelines.md)
- 涉及协议、配对或错误语义时，沿 Go 输出、Android 请求及设备状态检查一次完整链路。[局域网接口](./lan-api.md) · [Android 设备状态](../frontend/device-state.md)
