# Codex 会话读取

## 只读边界

`newCodexSource` 从源目录读取 `sessions/**/rollout-*.jsonl` 和 `session_index.jsonl`。文件内容通过 `openSharedRead` 打开，底层使用 `GENERIC_READ`、`OPEN_EXISTING` 及读、写、删除共享权限，允许 Codex 在采集时继续追加、重命名或归档。新增会话或标题文件读取时沿用这条路径。[会话源](../../../collector/source.go) · [共享句柄](../../../collector/platform_windows.go)

## 增量解析与状态

- `rollout.read` 按已消费的字节偏移继续读取，只在读到换行后推进偏移。文件截断或替换时清空解析缓存并重放。半行留给下一轮，大行由 `ReadBytes` 读取。[rollout.read](../../../collector/source.go) · [长行与半行示例](../../../collector/source_test.go)
- `rollout.consume` 先通过 `session_meta` 识别 Codex Desktop 会话并过滤子代理。`task_started` 创建运行状态，`task_complete` 记录完成，`turn_aborted` 清除匹配的运行状态。阶段来自已写入的事件，例如 `exec_command` 映射到「运行命令」。[事件映射](../../../collector/source.go)
- `snapshot(now)` 按采集器本地日期统计完成。`completedCount` 是当天总数，`lastCompleted` 只返回最近 12 条，运行任务按最新更新时间降序。标题经 `compactTitle` 规整，从索引按会话 ID 获取，缺省为「Codex 任务」。[快照汇总](../../../collector/source.go)
- 标题、阶段和耗时取自日志。协议 v1 保留恒为 `false` 的 `live` 和空的 `tasks[].requests`，界面使用实际可采集的指标。[快照类型](../../../collector/source.go) · [状态屏渲染](../../../android/src/local/codex/statusscreen/MainActivity.java)

新增日志事件时在 `consume` 收敛映射，并用 `source_test.go` 的临时 JSONL 样例覆盖完整行、重复完成及过滤行为。[采集回归测试](../../../collector/source_test.go)
