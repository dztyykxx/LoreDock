# Design: knowledge-context-assembly

## Context

见 proposal.md - Why。当前知识整理 top 层 Graph 的 `messages` 为 APPEND，跨节点累积 Agent 原始 JSON 与 Tool 链；TURN_FINISH 已把 `conversationHistory` 裁剪为角色化最近轮次（4 轮/8000 码点，`KnowledgeCurationGraphFactory`），但模型输入仍透传父图消息。完整设计依据 docs/architecture/知识整理上下文组装与压缩设计.md（下称设计文档），本文件只记录实现层面的决策与约束。

## Goals / Non-Goals

Goals：
- 节点入口级最小语义上下文 + 子 Agent 历史隔离 + 预算守卫与日志 + 确定性压缩 + 内部受限 LLM 压缩 + 滚动摘要缓存；
- 全部阈值统一配置、启动校验；
- 全部行为可测试可观测，测试证据输出符合 AGENTS.md §9。

Non-Goals：
- 不改 Checkpoint 保存语义（仍是"从断点继续"），不新建摘要表，不做版本回放；
- 不新增跨模块 API/Port/抽象，上下文组装是 `agent` 模块内具体 Service；
- 不改 Agent Spec 文件内容（阶段标记格式与现有 spec 指令兼容）；
- 不做前端与 API DTO 变更，不引入消息队列/通用 Runtime。

## Decisions

1. **组装粒度 = 节点入口，而非每次模型调用**。框架依据（锁定版 1.1.2.3 源码核验）：`ReactAgent.asNode(true,false)` 返回的 `AgentSubGraphNode` 会把父图完整 data 复制进子线程（`ReactAgent.java:1012-1024`），子图内部 `messages` 恒 APPEND（`buildMessagesKeyStrategyFactory:740-746`），子图返回时 `processLastResponse` 移除父消息只留子图增量（:1062-1072）。因此：父图新增确定性"准备节点"（写 `messages` REPLACE > 组装视图），专家节点入口收到的即最小视图；准备节点同时写入 `currentInstruction` REPLACE 键。备选（StreamingModelInterceptor 改写每次请求）被否：破坏 Tool 链连续性、无法区分窗口责任。
2. **每次调用前守卫走 `MessagesModelHook.BEFORE_MODEL`**（框架源码 `ReactAgent.java:348-397`：hook 节点链在模型节点之前，每次 ReAct 迭代都会执行；`MessagesModelHook.beforeModel(List<Message>, config)` 可返回修改后消息、可抛异常中止）。守卫只做：估算/日志、闭合 Tool 组保守裁剪、上限检查；不重新组装、不调 LLM（避免递归与破坏连续性）。`ContextLimitExceeded`/`ContextRunBudgetExceeded` 两种受控异常由 Executor 现有失败分类识别为 WAITING_FOR_USER（复用 `markKnowledgeRecovery`），与"模型解析失败"区分。
3. **直调路径的组装在父侧完成**。框架 `AgentTool` 固定以 `[instruction, UserMessage(actualInput)]` 新建子线程 state（已核验），专家侧无注入点；`DIRECT_*` 的 purpose 只作用于主 Agent 准备节点，约束其生成 `actualInput` 文本。
4. **LLM 压缩不是 Agent**。由 `ContextAssemblyService` 内部直接调用现有 ChatModel 结构化输出（无 Spec/Tool/Saver/父图节点），这是上下文管理模块内部实现；日志标识 `context_compressor` 仅为调用点统计。
5. **摘要字段随父图 Checkpoint 持久化**（`conversationSummary`/`summaryThroughMessageId`/`summarySourceDigest`/`summarySchemaVersion`/`summaryGeneration`，REPLACE 键）。框架 PostgresSaver 在已有 checkpointId 时同事务"删除旧记录+插入新记录"（`PostgresSaver.java updatedCheckpoint`），`parent_checkpoint_id` 恒 NULL——同一 thread 只保留最新快照，摘要天然随最新 Checkpoint 持久化。业务库全量消息（`knowledge_task_message`）保持可重建来源。
6. **确定性压缩优先级高于** 阈值 72k 触发 → 64k 目标 → 96k 硬上限；压缩源：会话 state（结构化键）+ `knowledge_task_message`（跨轮历史，USER + 最终回复按 subject 过滤，与现有 `previousDialogue` 过滤逻辑一致）。
7. **子 Agent 不继承历史是硬约束**（用户已确认）：子图路径用准备节点 REPLACE 保证；直调路径由框架保证；修复回路（REPAIR）同样经准备节点按 `purpose=REPAIR` 重新组装（有界错误摘要 + `lastValidatedNode`）。
8. **阶段标记语义不回归**：准备节点生成的视图显式携带「【当前阶段：DECIDE】/【当前阶段：FINISH】/完整整理完成」标记文本，与 coordinator.md 现有约定（"以输入中最后出现的标记为准"）保持一致；这是已修复的重复开场白问题的防回归要求。

## Risks / Trade-offs

- [守卫 Hook 裁剪消息会写入子图 state，最终随返回合并进父图缓冲区] → 缓冲区即弃（准备节点总是 REPLACE），可接受；最终回复投影一律走结构化键（`mainTurnResult`/`recoveryInfo`），设计文档已明确。
- [节点条目组装依赖权威状态键：若某结构化键缺失（历史 run 恢复）] → 组装失败回退为"上次已提交的最小消息视图 + 新指令"，并记录 WARN；不吞异常。
- [Token 估算依赖模型 Tokenizer 不可得时用 UTF-8 字节上界] → 日志明确 `estimateMode`；预算判定只使用配置阈值，不混用单位。
- [压缩是一次真实模型调用，成本/时延] → 受 `max-llm-compression-calls`（默认 1）与有界批次限制，且只发生在节点入口确定性压缩仍超限时。
- [滚动摘要的语义漂移] → `max-rolling-summary-generations`（默认 3）后从原始消息低频重建并重置代数；摘要只存指代所需内容，不存正文。

## Migration Plan

1. 一次性部署；无 DB 迁移；配置项经 `application.yml` 提供默认值，env 可覆盖；
2. 行为变化（模型输入变少）不影响 API 契约；既有 run 恢复路径按新组装语义执行——旧 Checkpoint 中的 `conversationSummary` 键缺失视为"无摘要"，从 `knowledge_task_message` 重建；
3. 回滚：配置阈值调大 + 关闭 LLM 压缩（`max-llm-compression-calls=0`）即退化为"确定性裁剪+守卫"，不影响主链路。

## Open Questions

无（本轮冻结的需求均可在现有框架与模块内实现）。
