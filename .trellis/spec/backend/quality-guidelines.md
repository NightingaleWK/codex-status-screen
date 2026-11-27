# Windows 验证与构建

在 Windows 仓库根目录使用 Go 1.26 或更新版本，版本以 `go.mod` 为准。原生文件共享用例需要 Windows 文件系统语义。[Go 模块版本](../../../go.mod) · [共享读取测试](../../../collector/source_test.go)

```powershell
go test -mod=readonly ./collector
.\tools\Build-Windows.ps1
```

本机有便携 Go 工具链时，可用 `-Go` 给打包脚本传入 `go.exe` 的路径。构建使用 `windows/amd64`、关闭 CGO 并使用 GUI 子系统，归档可执行文件、说明和第三方许可证到 `dist/CodexStatus-Windows-x64.zip`。[Windows 构建脚本](../../../tools/Build-Windows.ps1) · [使用说明](../../../README.md)

| 改动范围 | 已有回归与检查点 |
| --- | --- |
| 日志筛选、状态或增量读取 | `TestReadOnlySnapshot` 用临时 JSONL 验证 Desktop 过滤、标题、长行、半行和完成去重 |
| Windows 文件打开方式 | `TestFileSharingAllowsCodexToAppendAndArchive` 在读句柄仍持有时追加并重命名 |
| 配对、身份或在线计数 | `TestExistingPairingAndOnlineCount` 使用旧格式身份、已有 token 和一次性配对码验证接口与持久化 |
| 入口或托盘 | 在 Windows 验证双击启动、重复实例提示、配对页和退出顺序 |

测试实现位于 [source_test.go](../../../collector/source_test.go) 和 [server_test.go](../../../collector/server_test.go)。
