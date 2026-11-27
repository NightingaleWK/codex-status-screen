# 开发检查指南

| 改动范围 | 先读 |
| --- | --- |
| Windows 托盘、Codex 日志、HTTPS 或配对身份 | [Windows Go 采集器规范](../backend/index.md) |
| Android 状态屏、连接、设置或提醒 | [Android 状态屏规范](../frontend/index.md) |
| 采集结果、配对数据或状态跨越应用边界 | [跨层检查](./cross-layer-thinking-guide.md) |
| 准备新增解析、身份校验、事件或渲染逻辑 | [现有代码复用入口](./code-reuse-thinking-guide.md) |

Trellis 可用 `python ./.trellis/scripts/get_context.py --mode packages` 列出当前单仓库的 `backend`、`frontend` 两个规范层。变更涉及两层时，从两个索引进入相关规范。
