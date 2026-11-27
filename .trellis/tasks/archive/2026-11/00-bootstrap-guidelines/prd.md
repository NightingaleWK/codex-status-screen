# 存量项目接入 Trellis

## 目标

从 Codex 状态屏的现有实现提炼 Trellis 规范，让后续 Windows 或 Android 任务可以直接定位代码边界、跨层契约与验证入口。以项目当前的 `v0.2.3` 源码快照为基线，按官方存量项目流程审阅。[Trellis 存量项目接入](https://docs.trytrellis.app/zh/start/real-world-scenarios#2-%E6%8E%A5%E5%85%A5%E5%AD%98%E9%87%8F%E9%A1%B9%E7%9B%AE)

## 交付范围

- `.trellis/spec/backend/` 记录 Go 托盘采集器、Codex 日志只读、局域网协议、授权记录及验证命令。
- `.trellis/spec/frontend/` 记录原生 Java 状态屏、设备连接和汇总、边到边布局、提醒逻辑及构建验证。
- `.trellis/spec/guides/` 记录跨层核对和现有实现入口。任务上下文从层级索引进入具体规范。

## 验收标准

- [x] Trellis 在单仓库中识别 `backend` 与 `frontend`，引导任务上下文校验通过。
- [x] 规范索引与文件一致，代码引用可解析，规范可追溯到实际源码与测试。
- [x] Windows Go 回归和 Android 纯 Java 事件回归通过，文档差异可独立审阅。
