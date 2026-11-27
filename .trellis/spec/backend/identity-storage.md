# 本机身份与配对记录

采集器自有目录内的 `identity.json` 保存设备身份、证书和授权。现有文件是扁平对象，字段为 `id`、`name`、`clients`、`cert`、`key`、`pin`。其中 `clients` 保存已授权 token 的 SHA-256 十六进制摘要，`pin` 是证书 DER 的 SHA-256 十六进制摘要。该结构兼容旧 Node 采集器生成的文件，Go 版测试覆盖原配对继续使用。[身份数据结构](../../../collector/identity.go) · [兼容性测试](../../../collector/server_test.go)

- `openIdentity(home)` 在文件缺失时创建身份。已有记录会校验 ID、名称、证书密钥对及指纹。读取或校验失败时，由启动入口显示错误。[加载与校验](../../../collector/identity.go) · [启动错误](../../../collector/main_windows.go)
- `pair` 先生成 token 和待写的授权摘要，由 `saveIdentity` 写同目录临时文件并重命名，再更新内存授权。接口只在成功落盘后返回 token 并消耗配对码。保存失败路径继续使用原内存授权。[授权落盘](../../../collector/identity.go) · [配对处理](../../../collector/server.go)
- 托盘「在线」表示采集器在最近 15 秒内收到过该客户端的已授权状态请求。`clients` 是授权列表，`seen` 是进程内最近访问时间，两者分别用于已配对与在线计数。[在线计数](../../../collector/server.go) · [托盘展示](../../../collector/main_windows.go)

身份文件及打包签名文件位于用户数据目录。测试使用 `t.TempDir()` 与测试 token，Git 忽略本机身份文件和签名凭据。[授权测试](../../../collector/server_test.go) · [打包签名](../../../tools/Build-Android.ps1) · [Git 忽略规则](../../../.gitignore)
