# Proposal: 主 Agent 最终回复双通道分离

## Why

主 Agent 当前把「面向管理员的最终回复」放入结构化输出 JSON 的 `summary` 字段（`main_agent.md` 输出契约），一段面向用户的自然语言必须同时满足 JSON 语法（转义、引号、换行、截断），而它并非路由所需的结构信息。生产已实证两类事故：runId=60 因 JSON 重复键在 record 构造时崩溃、runId=63 因结构合法但省略可空 `summary` 在公开投影阶段 NPE——后者虽已在 213a4ab 降级修复，但「长文本进 JSON」的风险面（转义错误、输出截断成不完整 JSON、字段缺失）并未消除；且长文本在公开投影还会被 `MAX_PUBLIC_PROGRESS_CODE_POINTS=1000` 二次截断，说明它本就不是为长文设计的载体。

## What Changes

- **主 Agent 双通道输出契约（BREAKING，对旧定义 run 的恢复行为有影响）**：消息正文承载面向管理员的完整回复（可直接展示，无 JSON 转义责任）；JSON 只承载路由与调用元数据——`action`（CHAT/TURN_DONE/FULL_CURATION，路由输入）、`expertCalls`（专家调用参数，AgentTool 入参）、`memo`（可空极短说明，供公开投影回退）。
- **JSON 提取位置约定**：结构化 JSON 作为消息**尾部尾缀**（正文在前、JSON 在后）；任何公开/路由解析失败时 `memo` 是回退来源，最终回复通道以正文为准。
- **消费端优先级**：`finalReply`（最终消息正文）与 `projectSummary`（AGENT_STAGE 公开投影）改为「正文 → 回退 memo」；`tolerantStructured` 提取规则从「首尾括号截取」调整为「优先消息尾部 JSON 块」（正文中出现的 JSON 片段不得误提取）。
- **契约更名**：`MainTurnResult.summary` 语义变更为极短说明并更名 `memo`（消除「长文本可放 summary」的旧口子）。
- **定义版本推进**：指令定义 Digest 变化，`GRAPH_DEF_VERSION` 前缀推进；部署后旧定义 run 恢复被判定不兼容属于既有预期行为（规范见 `specs/knowledge-curation-agent`）。
- **范围外（不变）**：检索/起草/审查/协调四个专家节点的 `summary` 保持「公开行动摘要」短文本语义（它们不承担面向用户的对话回复）；路由条件边与 AgentTool 调用参数仍走结构化 JSON；不引入 run 级自动重跑；公开投影截断策略（1000 码点）不变。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `knowledge-curation-agent`：主 Agent 输出契约由「最终回复在 JSON.summary」改为「正文承载回复、JSON 承载路由与调用元数据 + 可空 memo」，并为消费端（最终回复提取、公开投影、JSON 提取规则）规定优先级与回退行为。

## Impact

- 指令定义：`backend/src/main/resources/agent-specs/knowledge-curation/main_agent.md`（双通道输出契约、各 action 语义重述、【当前阶段：FULL CURATION 完成】轮汇报方式）。
- 契约与解析：`KnowledgeCurationGraphResult.MainTurnResult`（`summary`→`memo`）、`KnowledgeCurationGraphFactory.tolerantStructured`（消息尾部 JSON 提取）、`KnowledgeCurationRunExecutor.finalReply/projectSummary`（正文优先、回退 memo）。
- 测试（现有契约驱动型用例全部需要同步输出格式）：`KnowledgeCurationRunExecutorDriveIT`、`KnowledgeCurationGraphRunIT`、`KnowledgeCurationGraphRoutingTest`、`AtlasAgentEvalDeterministicIT`（`EvalScriptedChatModel` 主 Agent 分支）及新增双通道回归用例（正文含花括号/JSON 片段、正文缺失回退 memo）。
- 前端：通过 AGENT_STAGE 事件/最终消息消费（投影后字段名不变），无需改动——需在 MVP 验证中回归确认。
- 部署行为：`GRAPH_DEF_VERSION` 推进后，部署前已在 WAIT_INPUT/恢复路径上的旧定义 run 会按既有守卫判定为不兼容（预期行为，非本次新增）。
