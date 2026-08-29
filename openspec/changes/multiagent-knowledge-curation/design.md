## Design Notes

- 四份 Agent 定义（`backend/src/main/resources/agent-specs/knowledge-curation/*.md`）用 YAML front-matter 声明 `name`/`description`/`tools`，正文作为系统提示；`AgentSpecLoader` 在启动时加载并校验。
- 唯一结果契约 `KnowledgeCurationGraphResult`（嵌套 record，camelCase）：调度/检索/草稿/审查四种结构化输出，供 `outputType` 约束与路由节点 Jackson 解析。
- `KnowledgeCurationGraphFactory`：`validate()` 做角色/白名单 fail-fast；`build()` 用 `asNode(false,false)` 接入四 Agent，装配 `StateGraph`（状态键 messages=APPEND、其余=REPLACE）、条件边、`set_decide/set_finish/set_draft_round` 与 `CompileConfig.interruptAfter` + `PostgresSaver`。
- `KnowledgeCurationRunExecutor`：共享模型与 Tool Interceptor/hook；`drive()` 循环调用 `graph.stream(input, config)`，每次到 `interruptAfter` 边界检查 run 状态，续跑用 `graph.getState(config).config()`（`StateSnapshot.config()`），直到 `next()` 为 `null`/`__END__`；最终回复取 `coordinationResult.summary`。
- 关键框架行为：`interruptAfter` 会暂停图；续跑必须用 `snapshot.config()` 才能继续下一节点（否则重跑入口节点）；须设 `templateRenderer` 透传避免指令里的 `{}`/`|` 被 StringTemplate 解析；子 Agent 输出用 `StreamingOutput.agent()`（去 `subgraph_` 前缀）而非 `_AGENT_MODEL_` 节点名。
- 安全规则：DECIDE DRAFT 需 draftInstruction + 至少一个 SUPPORTED 事实；ASK_USER 需具体问题；CHAT 需可见回复；WRITTEN/PASS 需修订条目；REVISE 需至少一条 finding 且 draftRound<2 才返工；任意失败/越权不触发发布。
