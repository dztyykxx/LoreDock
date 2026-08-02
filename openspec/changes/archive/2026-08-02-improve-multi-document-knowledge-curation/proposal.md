## Why

当前知识任务已经具备长期会话、多轮 run、单草稿修订和管理员发布，但产品仍以固定双栏展示单份草稿，Agent 公开回复大多只有终态占位文本，Tool 开始与完成被拆成多条过程事件，任务状态又被最新 run 状态覆盖。用户无法在运行中理解 Agent 的关键判断，也无法让一次任务安全修改并审核多篇正式知识。

本变更依据《对话式多文档知识整理流程 MVP 需求》，把现有能力收敛为一条可真实演示的核心链路：公开决策过程、多文档会话工作区、每轮 Patch Set、累计 Diff 和管理员原子发布。项目锁定的 Spring AI Alibaba `1.1.2.3` 已提供 `ReactAgent.streamMessages`、Tool Interceptor、运行限制 Hook 和 Checkpoint；LoreDock 复用消息流和 Interceptor，只新增业务持久化、事件投影和工作区 Tool，不自建 Agent Runtime。

## What Changes

- 知识任务增加独立任务状态 `PROCESSING/PUBLISHED/CLOSED_NO_CHANGE/ABANDONED`，不再用最新 run 的完成状态表示整个任务结束。
- 会话从单一 `current_draft_id` 改为按 `conversation_id` 管理最多 10 份工作文档；只支持 `ADD` 和 `MODIFY`。
- 修改工作文档固定正式 `baseline_document_id` 与 `baseline_revision`；发布时更新同一正式文档 ID，不再把普通修改实现为替代文档。
- 增加 `workspace_document_list` Tool；现有 `knowledge_*` Tool 继续只读正式知识，`draft_read/update` 继续操作工作修订，不建设临时索引。
- Skill 要求模型在关键节点输出少量公开决策说明；后端持久化完整公开消息并通过 SSE 推送，不展示原始思维链或逐 Token 分片。
- Tool 调用按模型 `toolCallId` 聚合成稳定 Invocation，保存脱敏且限长的真实业务输入输出、状态、耗时和截断标记。
- 每个 run 从修订事实计算多文档 Patch Set；页面同时展示本轮净变化和当前累计待发布变化。
- 管理员提交完整的工作文档修订集合，以任务级幂等键原子执行所有 ADD/MODIFY、任务和候选输入状态更新，并创建一个异步索引任务。
- 页面改为全宽对话、顶部累计审核条和按需 Diff 抽屉，移除固定右侧草稿栏、修订页签、暂停恢复入口和复杂 Diff 能力。
- 增加“确认无需变更”和“放弃任务”终止路径。

## Capabilities

### Modified Capabilities

- `knowledge-curation-agent`：任务生命周期、公开消息、Tool Invocation、多文档工作区、每轮 Patch Set、SSE 和对话页面。
- `knowledge-document-lifecycle`：固定正式基线、同 ID 修改、多文档审核集合与原子发布。

## Impact

- 后端 `agent`：任务状态与消息投影、Tool Invocation、SSE、工作区列表、Patch Set 查询和停止语义。
- 后端 `knowledge`：草稿基线修订、操作类型与目录、按任务列出草稿、批量发布和正式文档同 ID 修订。
- 数据库：新增任务状态、候选整理状态、草稿基线修订/操作/目录、Tool Invocation 与发布幂等记录；允许开发环境直接重建。
- Agent Skill：删除唯一合并草稿约束，增加工作区恢复、主题边界和公开决策要求。
- 前端：知识任务 API、SSE 状态合并、连续时间线、文件变更卡、累计审核条和 Diff 抽屉。
- Pencil：重做 `07A · 知识任务会话` 画布。
- 兼容性：前后端同步切换新契约，不保留旧单草稿页面响应；历史开发数据只做必要读取迁移，不建设双写兼容层。

## Non-Goals

- 原始思维链、Tool 审批、同 run 等待恢复、人工编辑、撤销或历史恢复。
- 删除、归档、替代、重命名、移动和创建知识目录。
- 会话级向量索引、部分发布、逐 hunk/三方合并。
- 复杂 Diff 组件、正文预览、大 Diff 优化和移动端审核。
