## Context

现有实现已经用 `knowledge_task_conversation`、`knowledge_task_message`、`agent_run`、`knowledge_draft` 和不可变 `knowledge_draft_revision` 分离了会话、运行和产物。主要缺口不是重建运行时，而是解除 `current_draft_id` 单草稿投影、补充明确正式基线、把 Tool 过程投影为可核验 Invocation，并把单草稿发布扩展为任务级事务。

Spring AI Alibaba `1.1.2.3` 的 `ReactAgent` 原生实现模型与 Tool 的循环；原始 `stream` 同时提供增量输出和每轮 `AGENT_MODEL_FINISHED` 聚合结果，Tool Interceptor 能取得真实 `ToolCallRequest`、`ToolCallResponse` 和调用 ID。`streamMessages` 适合正文增量展示，但不能把其最后一个片段当成完整最终回复；锁定版本还存在官方后续修复的流式乱序问题。因此 LoreDock 复用原始完成事件与框架循环，不自建模型循环。框架 Human-in-the-loop 主要解决 Tool 审批，当前 MVP 不使用它承载 Agent 主动提问；Agent 有疑问时输出最终消息并结束 run，用户回复后创建新 run。

## Goals / Non-Goals

**Goals:**

- 用最少新增表和接口把单草稿会话扩展为最多 10 份工作文档。
- 让每一条公开决策、Tool Invocation、Patch Set 和任务状态都能通过 REST 快照与持久化 SSE 恢复。
- 让管理员审核的完整修订集合与事务实际发布集合严格一致。
- 保持正式文档稳定 ID，并以正式修订冲突阻止静默覆盖。
- 让 UI 明确区分用户对话、Agent 公开决策、Tool、本轮变化和累计变化；风险与待确认问题进入最终回复，不建设独立发现卡。

**Non-Goals:**

- 不实现通用 Workspace、Git 对象、Patch Set 存储、临时检索索引或合并器。
- 不实现同 run 主动等待、Token 流、Tool 审批和原始推理展示。
- 不实现超出 ADD/MODIFY 正文更新的文档生命周期操作。

## Decisions

### 1. Conversation 是工作区边界，Draft 是工作文档

删除业务对 `knowledge_task_conversation.current_draft_id` 的依赖。工作区通过 `knowledge_draft.conversation_id` 查询，最多 10 行。保留 `knowledge_draft_revision.created_by_run_id`，用每个 run 在某草稿上的最早前置修订与最后生成修订计算 Patch Set，不增加 Patch Set 表。

`knowledge_draft` 增加：

- `operation`：`ADD|MODIFY`；
- `baseline_revision`：MODIFY 必填且创建后不可变；
- `directory`：ADD 必填，MODIFY 固定为基线目录；
- 必要的发布状态与正式修订结果。

空 v0 草稿不进入有效工作区。查询与发布按 `current_revision > 0` 过滤。数量上限在创建 Tool 的事务内以会话锁或稳定行锁校验。

### 2. 工作区不建设临时搜索视图

现有 `knowledge_directory_list/knowledge_document_list/knowledge_search/knowledge_grep/knowledge_document_read` 继续只访问 PUBLISHED。新增 `workspace_document_list` 返回当前会话全部有效/空草稿摘要；`draft_read` 读取正文。Skill 每轮先列工作区，再按需读取，最多 10 份文档，不需要向量索引或 PUBLISHED 覆盖层。

### 3. 公开决策只使用框架完整模型轮次

Spring AI Alibaba `ReactAgent` 原生在每次 Tool 执行后再次调用模型，直到模型返回不含 Tool Call 的 AssistantMessage 才结束。后端消费原始 Agent Stream 的 `AGENT_MODEL_FINISHED`，从聚合 `ChatResponse` 读取每轮完整 AssistantMessage：带 Tool Call 的非空正文投影为过程对话，不带 Tool Call 的非空正文作为最终回复。禁止把 `streamMessages` 的最后一个增量 token 当作最终结果。

部分 OpenAI 兼容供应商在 Tool Call 轮次不会稳定返回可见文本；此时页面只展示真实 Tool Invocation，不生成占位理由，也不增加额外消息 Tool。公开正文必须来自模型原生 AssistantMessage，服务端不根据 Tool 事件生成理由。

新 run 使用独立 thread，但启动 prompt 注入有界的历史用户消息和各轮最终 AssistantMessage，用于理解“按上一轮建议继续”等指代。公开过程消息、Tool Call、Tool 参数和 Tool 结果不进入新 run；最新工作文档与执行事实仍必须通过 `workspace_document_list` 和 `draft_read` 读取。

不读取 `reasoning_content`、`reasoning_text` 或 `<think>` 作为产品数据。

### 4. Tool Invocation 是业务投影

新增 `knowledge_tool_invocation` 表，以 `run_id + tool_call_id` 唯一。字段保存 conversation、run、顺序、Tool 名称、用途、模型业务参数 JSON/文本、模型业务结果/错误、状态、开始/结束时间、耗时和参数/结果截断标记。

Tool Interceptor 在调用前 upsert STARTED，在返回或异常时更新同一行。只保存 `ToolCallRequest` 中模型实际提交的 arguments 与返回给模型的 result；ToolContext 不进入表。输入输出分别限制约 12000 Unicode 码点。公开事件仅携带 Invocation ID 和状态，REST 详情从 Invocation 表读取。

### 5. 持久化 SSE 复用事件游标模式

知识任务页面先读取一个聚合快照：任务、候选输入、runs、messages、tool invocations、patch sets、workspace summary。随后打开 `/events?after=` SSE。事件使用数据库单调序号，至少区分 message.created、tool.updated、run.updated、patchset.ready、task.updated 和 publication.updated。SSE 只通知事实变化，不复制大正文。

断线携带最后序号续接；刷新重新拉快照。前端以实体 ID 合并，不能仅追加导致 Tool 卡重复。浏览器离开页面造成的异步连接断开或心跳 `Broken pipe` 属于正常客户端断连，服务端不得尝试写错误响应，也不得记录为 `unexpected_failure`。

### 6. Agent 提问结束当前 run

不新增 `NEEDS_INPUT` 或 WAITING 状态。Agent 有疑问时以普通最终回复提问并让 run 进入 COMPLETED；任务仍为 PROCESSING，输入框恢复。用户回复创建新 run并重新读取工作区。新 UI 不提供旧“请求暂停/恢复”入口。

停止本轮使用现有安全中断边界，但最终投影为 CANCELLED 且不再恢复；若停止实现无法稳定取消当前框架调用，本 change 可以保留服务端请求语义并在当前步骤返回后终结，不承诺中止正在进行的网络请求。

### 7. 正式修改保持 document_id

MODIFY 创建时从正式文档锁定 `baseline_document_id + baseline_revision` 并复制固定标题、目录、标签元数据。批量发布时重新锁定正式文档并要求修订等于 baseline_revision，然后用知识模块内部更新能力写入审核正文并增加正式修订，保留 document_id。

ADD 在发布时验证项目内目录和标题唯一性并创建新正式文档。工作草稿不再调用现有“创建候选文档并以 replacesDocumentId 发布”的普通替代路径。

### 8. 任务级原子发布

知识模块 `api` 增加任务级批量发布契约，agent 模块通过该公开接口传入固定访问上下文、发布幂等键与完整 `draftId/reviewedRevision` 集合。实现事务：

1. 锁定 PROCESSING 任务并校验操作者；
2. 锁定全部有效工作草稿，比较请求完整集合和当前修订；
3. 按 ID 稳定锁定所有 MODIFY 基线并验证正式修订；
4. 校验 ADD 目录/标题；
5. 执行全部正式新增和同 ID 修改；
6. 标记各草稿发布结果；
7. 更新任务与候选输入状态；
8. 写发布记录和一个索引 Job；
9. 提交。

同一任务的发布幂等键与请求哈希唯一；同键同参返回原结果，同键异参冲突。任何业务冲突回滚整个事务。

索引 Job 只在事务中入队，实际重建在提交后异步执行。索引失败不逆转正式生命周期。

### 9. 关闭与放弃

`CLOSED_NO_CHANGE` 只允许在没有有效工作文档时由管理员提交有限结论，事务内把候选输入标记 CURATED。ABANDONED 把候选输入恢复 PENDING。两者均写系统消息并让任务只读。

### 10. 前端结构

`KnowledgeTaskWorkspace` 拆为页面容器与小组件：ConversationTimeline、ToolInvocationCard、RunChangeCard、WorkspaceReviewBar、DiffDrawer、TaskComposer。默认只有居中对话主列；DiffDrawer 打开时覆盖右侧。Unified Diff 仅按行前缀加类，不引入第三方 Diff 库。

Pencil `07A` 是页面层级与视觉事实来源；Vue 使用已有颜色、字体和基础组件。

## API Shape

核心接口建议：

- `GET /admin/projects/{project}/knowledge-tasks/{taskId}`：聚合快照；
- `GET /.../{taskId}/events?after={sequence}`：持久化 SSE；
- `POST /.../{taskId}/messages`：创建下一 run；
- `POST /.../{taskId}/runs/{runId}/stop`：请求停止；
- `GET /.../{taskId}/workspace`：累计工作区；
- `GET /.../{taskId}/runs/{runId}/changes`：本轮 Patch Set；
- `POST /.../{taskId}/diff`：指定草稿起止修订或基线 Diff；
- `POST /.../{taskId}/publish`：完整修订集合原子发布；
- `POST /.../{taskId}/close-no-change`：确认无需变更；
- `POST /.../{taskId}/abandon`：放弃任务。

对外 JSON 使用 snake_case；前端 TypeScript 在 API 边界映射或遵循项目现有全局策略。

## Data and Transaction Boundaries

- `draft_update` 继续以单文档事务保证基础修订、区块操作、来源和新修订原子。
- 批量发布以任务为事务边界，禁止 Controller 循环调用单草稿 publish。
- SSE 事件与产生它的业务事实同事务写入，避免提交后页面永远收不到通知。
- Tool Invocation 的 STARTED 与终态允许分事务更新；重启遗留 STARTED 由 run 终态投影为失败/取消，不伪造成功。
- 公开消息、Tool 业务参数与结果遵守长度和敏感字段边界。

## Risks / Trade-offs

- [从修订动态计算 Patch Set 查询复杂] → 工作区上限 10，按 run/draft 有界查询；有真实性能问题再物化。
- [流式 AssistantMessage 可能重复累计文本] → 以 run、Tool Call ID/消息哈希去重，只保存完整公开消息。
- [Tool 返回正文较大] → 两侧限长并显式 truncated，页面按需展开。
- [多个 MODIFY 锁顺序导致死锁] → 按正式 document_id 和 draft_id 升序锁定。
- [索引任务入队与发布不一致] → 在同一数据库事务创建 Job，后台只消费已提交记录。
- [历史单草稿数据缺少 baseline_revision] → 开发环境允许重建；迁移保留数据时从当前正式修订回填并把现有任务保守标为只读或需要重新审核。

## Test Strategy

- PostgreSQL 集成测试：工作区数量与范围、基线不可变、Patch Set 净变化、完整审核集合、同 ID MODIFY、ADD/MODIFY 混合事务回滚、发布幂等和候选状态。
- Fake ChatModel/框架测试：公开决策消息持久化与去重、多草稿 Tool 循环、问题作为最终回复结束 run、停止后不恢复。
- Tool Interceptor 测试：同 toolCallId 单卡更新、真实业务输入输出、限长与 ToolContext 不泄露。
- SSE 集成测试：REST 后按序续接、断线游标、Tool 原位更新和终态事件。
- 前端测试：连续时间线层级、运行中禁用输入、每轮/累计变更区分、Diff 抽屉、发布确认和任务终态只读。
- 真实模型烟测：两份候选输入产生一份 MODIFY 与一份 ADD，第二轮继续修改后原子发布并创建一个索引任务。
