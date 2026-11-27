# Windows 采集器目录与配置

## 代码归属

| 现有入口 | 负责的行为 |
| --- | --- |
| [main_windows.go](../../../collector/main_windows.go) | 启动顺序、重复实例提示、托盘操作和退出清理 |
| [platform_windows.go](../../../collector/platform_windows.go) | 共享文件句柄、进程互斥锁、原生对话框和打开本机浏览器 |
| [source.go](../../../collector/source.go) | 会话发现、日志解析、标题索引和状态快照 |
| [server.go](../../../collector/server.go) | 环境变量、采样循环、HTTPS API 和本机配对页 |
| [identity.go](../../../collector/identity.go) | 设备身份、TLS 证书和客户端授权记录 |
| [discovery.go](../../../collector/discovery.go) | DNS-SD 服务发布及地址更新 |
| [assets](../../../collector/assets) | 通过 `go:embed` 随程序打包的托盘图标与配对页 |
| [Build-Windows.ps1](../../../tools/Build-Windows.ps1) | Windows x64 GUI 可执行文件、便携 ZIP 和第三方声明 |

`run` 按配置 → 单实例互斥锁 → 本机身份 → 监听器 → DNS-SD → 托盘的顺序启动。互斥锁按状态端口和配对页端口组合命名，退出托盘时清理发现服务、HTTP 监听器和采样循环。[启动与退出](../../../collector/main_windows.go) · [互斥锁](../../../collector/platform_windows.go) · [监听器清理](../../../collector/server.go)

## 配置入口

环境变量统一在 `readConfig` 读取。将端口和源目录的检查保持在这个入口，托盘和监听器复用同一份 `config`。[配置解析](../../../collector/server.go)

| 环境变量 | 类型与默认值 |
| --- | --- |
| `CODEX_STATUS_SOURCE` | Codex 数据目录，优先于 `CODEX_HOME`，最终默认 `%USERPROFILE%\.codex` |
| `CODEX_STATUS_HOME` | 采集器自有目录，Windows 默认 `%LOCALAPPDATA%\CodexStatusScreen` |
| `CODEX_STATUS_BIND` | 状态 API 监听地址字符串，默认 `0.0.0.0` |
| `CODEX_STATUS_PORT` | HTTPS 端口，默认 `8767` |
| `CODEX_STATUS_UI_PORT` | `127.0.0.1` 本机配对页端口，默认 `8768` |
| `CODEX_STATUS_DISABLE_DISCOVERY` | 字符串 `1` 关闭 DNS-SD，默认尝试发现 |

端口接受 `1..65535` 的整数，两个监听器使用不同端口。状态接口和配对页的隔离由 `serve` 与 `managementHost` 落实。[端口校验和监听配置](../../../collector/server.go)
