## Why

知识整理会话已支持跨轮会话与多路编排，但父 Graph 的 `messages` 使用追加策略，逐轮混入 Agent 原始结构化输出、阶段上下文副本和旧轮历史，后续模型调用携带与当前任务无关的旧工作过程；同时上下文无预算约束，超限行为散落在失败分类里，无法事先把"输入过大"与"模型解析失败"区分。同一会话多轮使用后模型输入持续膨胀、工具回执重复进入专家上下文，成本与延迟不可控。

## What Changes

- **节点入口最小上下文组装**：每个 Agent 节点入口由 `ContextAssemblyService` 按 `agentNode × purpose` 组装最小语义消息，父 Graph `messages` 改为一次性输入缓冲区（REPLACE）；本轮用户输入写入独立 `currentInstruction` REPLACE state 键，`TURN_FINISH` 历史重建不再扫描 `messages`；
- **子 Agent 不继承历史消息**：专家（子图节点或 AgentTool 直调）只见当前准备节点的最小消息视图；直调路径由父侧组装 `AgentTool actualInput` 文本承担；阶段推进节点的显式【当前阶段】标记语义由准备节点生成的视图保留；
- **预算配置与守卫**：统一部署配置（窗口/输入硬上限/输出预留/安全余量/压缩阈值/run 累计上限），节点入口压缩（72k 触发、64k 目标），`ContextBudgetGuardHook` 于每次 ReAct 模型调用前（`MessagesModelHook.BEFORE_MODEL`）做预算守卫、闭合 Tool 组保守裁剪与日志；run 累计预算耗尽抛错终结；
- **确定性裁剪**：按可复现顺序移除旧 Tool 参数与原文、已完成节点原始 JSON、正文替换为稳定引用、整轮丢弃旧会话历史（不截断半轮）；保留原始目标、当前指令、pending 管理员指导、人工决定、未解决问题与当前引用；
- **LLM 压缩兜底与滚动摘要**：确定性裁剪后仍超限时由 `ContextAssemblyService` 内部的受限 ChatModel 结构化输出调用压缩旧轮（无 Agent Spec、无 Tool、无 Saver、不入父图），结果写入父图 Checkpoint 的 `conversationSummary` 等 REPLACE 字段并随滚动增量维护；摘要可重建、非新建表；
- **BLOCKED 语义**：压缩后仍超限（或压缩请求超预算）返回 `BLOCKED` 并映射 `WAITING_FOR_USER`，保留当前语义稳定 Checkpoint，不进同输入重试回路，不进入 `FAILED` 终态。

## Capabilities

### New Capabilities
- `knowledge-context-assembly`: 知识整理会话的上下文组装与压缩预算能力——节点入口最小上下文、子 Agent 历史隔离、确定性裁剪、预算守卫与日志、LLM 压缩兜底与滚动摘要缓存。

### Modified Capabilities

无。既有 `knowledge-curation-agent` 规格中的需求未发生删除或冲突，全部新增行为收敛在新能力中；若实现中发现既有需求表述需修订，再通过 change 增量同步。

## Impact

- 影响 `backend` 的 `KnowledgeCurationGraphFactory`（顶层图节点结构、state 键与消息策略）、`KnowledgeCurationRunExecutor`（运行输入、失败分类衔接 BLOCKED）、新增 `ContextAssemblyService`、`ContextBudgetGuardHook`、Token 估算器与压缩调用组件（`agent` 模块内部，不新增跨模块 API/Port）；
- 无数据库表结构变更、无迁移；摘要与阈值均为配置项与 state REPLACE 键；
- 无前端改动（事件按 runId 区分，轮次间展示不变）；不新增第三方依赖与基础设施；
- 不改变 HTTP/API DTO、正式知识发布边界、业务 Tool 名称或草稿契约；不改动 Agent Spec 文件本身（阶段标记格式与现有 spec 指令保持兼容，仅按"标记以输入中最后出现为准"继续生效）。
