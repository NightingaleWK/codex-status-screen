# Windows 采集错误与诊断

| 情况 | 处理路径 |
| --- | --- |
| 启动配置、身份或端口错误 | `run` 返回错误，`main` 用原生对话框告知。端口打开失败会携带端口和底层错误 |
| 任一轮采集整体失败 | `sample` 标记 `ready=false`，保留最近成功快照。托盘显示等待日志 |
| 某些日志或索引暂不可读 | 快照有可用数据时用 `partial` 表示降级。被归档或删除的日志从扫描缓存移除 |
| DNS-SD 注册、HTTP 服务或配对页运行失败 | 用 `log.Printf` 加操作名记录，例如「局域网发现」「状态接口」。正常关停的 `http.ErrServerClosed` 已过滤 |
| LAN 请求或配对持久化错误 | API 返回 HTTP 状态和 `{"error":"…"}`。本地日志保留操作与内部错误 |

来源包括 [启动入口](../../../collector/main_windows.go)、[日志扫描](../../../collector/source.go)、[采样、错误响应与关停](../../../collector/server.go)、[局域网发现](../../../collector/discovery.go)。

诊断信息沿用操作名、端口、读源状态及技术错误。授权信息通过配对接口传输，日志使用上述上下文即可定位失败。[配对保存错误处理](../../../collector/server.go)
