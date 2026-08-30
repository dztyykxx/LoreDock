## Why

多 Agent 知识整理 Graph（`multiagent-knowledge-curation`）已经解决了单 Agent 职责混同的问题，但多轮使用仍有三个结构性问题：每个新 run 生成新 `threadId`，上一轮 Graph State 不被继承；续聊把最近用户消息与调度 Agent 回复压成一段字符串重启，不是角色化消息与状态继承；任何非闲聊输入都会进入完整检索图，查询、改标题、抽查过程等简单意图也要付出完整链路的延迟与模型调用。同时模型网络异常、结构化结果解析失败、进程重启与人工暂停还没有统一的“可恢复运行”语义，部分错误会直接终止 run，用户只能靠文本历史重建上下文。

## What Changes

- 会话级 `threadId`：全部轮次共享 `knowledge-task-conversation-{conversationId}`；旧会话未完成的 run 继续使用其已保存的旧 `threadId`，旧会话新开轮次且无会话级 Checkpoint 时从业务消息与工作区重建一次初始会话状态。
- 顶层会话 Graph：父图增加 `TURN_FINISH / WAIT_INPUT` 边界，正常轮次完成后停在 `WAIT_INPUT`，下一轮创建新 run、复用同 `threadId`，用 `updateState()` 注入本轮消息后从 Coordinator 继续；暂停/可恢复错误恢复原 run 且首次 stream 直接使用最新 `StateSnapshot.config()`。
- 主 Agent 多路编排：主 Agent 持有 `retrieve_expert`、`draft_expert`、`review_expert` 三个专家 `AgentTool`，本身输出 `MainTurnResult`（`CHAT` / `FULL_CURATION` / `TURN_DONE`），父图条件边只按该枚举路由；允许先直调专家再输出 `FULL_CURATION` 进入完整子图；完整子图收缩为四个专家的现有 StateGraph，不重写其机制。
- 上下文所有权：子 Agent（专家直调与子图节点）不再配置 Saver，必不继承旧 Checkpoint 链；顶层 `messages` 改为 REPLACE，由 `TURN_FINISH` 重建为角色化会话历史（用户指令 + 主 Agent 最终回复），隔离框架自动追加原始结构化 JSON 的副作用；专家只获得主 Agent/父 Graph 组装的最小输入。
- 分层失败与恢复：完整子图增加“原始候选 → Validate → Repair/Recovery Gate”回路，直调路径的校验对象收敛为主 Agent 的 `MainTurnResult`；只读 Tool 重试 2 次，写 Tool 不盲目重试，写入结果未知按 `idempotencyKey + draftId + baseRevision` 对账；非终态 run 进程重启后按定义摘要重建调度，`agent_spec_digest`/`config_summary`（Graph 定义版本）不匹配一律停 `RECOVERY_REQUIRED`，不解析不兼容 Checkpoint。
- 会话级串行：同一 conversationId 已有非终态 run 或活跃轮次时，Runtime Gate 拒绝新轮次并提示，不做并发执行。
- `KnowledgeCurationRunExecutor` 的任意异常终结 run 改为按失败类别处理：只有权限越界、明确取消、不可迁移的定义不兼容、重试耗尽且无语义稳定恢复点进入终态 `FAILED`；其余进入 `WAITING_FOR_USER`（`waitReason` 区分 `ASK_USER`/`PAUSED`/`RECOVERY_REQUIRED`）。

## Capabilities

### Modified Capabilities

- `knowledge-curation-agent`: 知识整理从“单轮四 Agent Graph”扩展为“会话级多路编排”。同一会话所有轮次共享稳定 `threadId` 与父 Graph State；主 Agent 统一识别意图，可直调专家、组合专家结果或进入完整整理子图；完整整理子图保持现有顺序、返工上限、无来源不写入与无发布能力边界；结构化失败、写副作用与进程重启具备分层可观察的恢复语义；子 Agent 无独立 Checkpoint 与长期历史；同一会话禁止并发轮次。

## Impact

- 影响 `backend` 的 `KnowledgeCurationRunExecutor`、`KnowledgeCurationGraphFactory`、`KnowledgeCurationGraphResult`、`KnowledgeTaskServiceImpl`、`KnowledgeAgentDefinitionService`（定义摘要复用）、启动恢复器（AgentRunRecovery 或新增知识整理恢复路径）、新增主 Agent 与 `MainTurnResult` 契约；前端 `KnowledgeTaskWorkspace` 无需改动（事件按 runId 区分，轮次间展示不变）。
- 无数据库表结构变更：`agent_spec_digest` 已由 run 创建时写入，会话级 `threadId` 复用现有 `thread_id` 列，Graph 定义版本约定写入现有 `config_summary`。
- 不改变 HTTP/API DTO、正式知识发布边界、业务 Tool 名称或草稿契约；不新增第三方依赖和基础设施；不实现消息队列、A2A、分布式调度、自动化触发或多用户协作。
- 框架原生能力（锁定版已核验）：`ReactAgent`（主/专家）、`AgentTool.create(ReactAgent)`（专家直调，子线程 `{parentThreadId}_{name}` 且清空 checkpoint/nextNode）、`StateGraph`/子图/条件边、`CompileConfig.interruptAfter`、`PostgresSaver`、`StateSnapshot.config()`/`CompiledGraph.updateState()`、`outputType`/`outputKey`、`ModelCallLimitHook`/`ToolCallLimitHook`、`ModelRetryInterceptor`/`ToolRetryInterceptor`；LoreDock 补充：`MainTurnResult` 契约、顶层会话图与 `TURN_FINISH`/Runtime Gate、定义摘要比对、Validate/Repair/对账回路、事件与消息投影规则。子 Agent Saver 移除是与会话级 Checkpoint 共存的前提（避免确定性子线程复用旧 Checkpoint 链）。
