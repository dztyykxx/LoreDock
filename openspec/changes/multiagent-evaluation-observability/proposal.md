# 多 Agent 评估轨迹与安全事件投影

## Why

知识整理已经由主 Agent 和完整整理子图共同编排，旧评估器只能读取最终回复和工作区，无法区分入口路由、职责覆盖、证据来源、审查闭环与运行成本。需要复用现有 `agent_run`、`agent_run_event` 和任务快照补齐可复算的评估观察值。

## What Changes

- 在已有 `AGENT_STAGE` 公开事件 Payload 中增加可空的 `curation` 安全投影。
- 由 Executor 只投影已解析的主 Agent、Coordinator、Retriever、Drafter、Reviewer 结构化结果；不保存 Prompt、Graph State、完整 Tool JSON、事实正文或隐藏推理。
- Atlas 知识整理评估器从任务快照重建 GraphTrace、阶段成本和实验版本信息，并计算路由、路径不变量、证据覆盖、审查与完成指标。
- 保持旧事件、旧评估报告和旧构造器的读取兼容，不新增评估表或数据库迁移。

## Non-Goals

- 不改变 Graph 路由、Agent 职责或发布权限。
- 不把阶段事件数量当作质量指标。
- 不读取 Checkpoint 或 Graph State 作为评估数据源。
- 不在本变更中执行 8 条知识整理和 40 条 QA 全量复测。
