## Why

当前知识整理链路由单个 `knowledge-curator` Agent 同时承担“找事实、做决定、执行写入、证明写入正确”四类职责，前序判断容易影响后续写入和自审；固定评估中检索之后的动作选择和写入审查仍不稳定（过度保守转询问、来源不完整仍写入、写入 Agent 自审延续原判断）。本次改造的目标不是替换检索与草稿能力，而是拆开职责，用显式 `StateGraph` 固定合法顺序并持久化执行状态，正式发布仍由管理员完成。

## What Changes

- 知识整理由显式 `StateGraph` 编排四个职责不同的 `ReactAgent`：调度 Agent 判定意图并决定 `ASK_USER/DRAFT/NO_CHANGE` 与汇总，检索 Agent 只提交证据事实，草稿 Agent 只执行写入，审查 Agent 独立核对来源与最新修订。
- 以 `PostgresSaver` 按 run `threadId` 持久化父 Graph，节点边界设置框架 `interruptAfter`；Executor 在边界检查 run 状态（RUNNING 续跑 / PAUSE_REQUESTED 投影等待 / CANCELLED 结束），续跑必须使用 `StateSnapshot.config()`（含 `checkPointId`/`nextNode`）。
- 每个 Agent 只获得设计白名单 Tool（调度与审查无写 Tool，任何 Agent 无发布 Tool）；运行级模型与 Tool Interceptor 挂到四个 Agent，共享原子计数与配额。
- 运行定义由四份随应用发布的 Agent Markdown（`agent-specs/knowledge-curation/*.md`）在启动时加载并校验（角色齐全、白名单一致、未知 Tool 启动失败），不再使用单 Agent Skill。
- 公开过程以 `AGENT_STAGE` 事件 + `SUB_AGENT` 消息 + `COORDINATOR_AGENT` 最终回复投影；前端把 `AGENT_STAGE` 投影为阶段卡片。

## Capabilities

### Modified Capabilities

- `knowledge-curation-agent`: 知识整理从单 Agent 升级为四 Agent StateGraph 编排，Graph 固定顺序与最多两轮返工；Tool 权限满足最小权限（无发布能力）；Graph 使用真实 PostgreSQL Checkpoint 可在新实例恢复；路由安全规则（无来源不写入、无问题不结束、无修订不进入审查、返工上限）由代码保证；页面与公开事件只含白名单字段。

## Impact

- 影响 `backend` 的 `KnowledgeCurationGraphFactory`、`KnowledgeAgentDefinitionService`、`KnowledgeCurationRunExecutor`、`KnowledgeCurationGraphResult`、Agent Spec 资源和 `frontend` 的 `KnowledgeTaskWorkspace` 过程展示。
- 删除单 Agent 的 `agent-skills/knowledge-curator` Skill 与 `LoadedDefinition.createSkillHook`/Skill Registry 依赖；保留运行级稳定 `skillName` 标识以维持前端最终消息识别契约。
- 不改变 HTTP/API DTO、数据库表结构、正式知识发布边界、业务 Tool 名称或草稿契约；不新增第三方依赖和基础设施；不实现自动触发、自动发布、消息队列、A2A、通用 Workflow Runtime 或再次评估。
- 框架原生能力：`StateGraph`、ReactAgent/`asNode`、`outputType`/`outputKey`、`KeyStrategy`（REPLACE/APPEND）、`CompileConfig.interruptAfter`、`PostgresSaver`、`AgentSpecLoader`、`ModelCallLimitHook`/`ToolCallLimitHook`；LoreDock 补充：四份 Agent 定义、角色/白名单校验、结构化结果契约、Graph 工厂与 Executor 接线、公开事件/消息投影。
