# 知识整理多 Agent 联调问题与任务清单

| 属性 | 内容 |
|---|---|
| 文档日期 | 2026-08-29 |
| 关联设计 | [`知识整理多Agent架构设计.md`](./知识整理多Agent架构设计.md)（v0.6，已按现状修订 §6） |
| 本次基线 | 四个 Agent StateGraph 已跑通，`asNode(true, false)` 修复 + 内部状态日志已合入，43 项相关后端测试通过 |
| 状态 | 联调中发现若干问题，作为后续修复任务清单；本轮先存档代码现状 |

---

## 一、当前已落地并验证

1. 四个 Agent 显式 `StateGraph`（coordinator/retriever/drafter/reviewer）+ `PostgresSaver` Checkpoint。
2. **修复根因**：`KnowledgeCurationGraphFactory.build()` 全部改用 `asNode(true, false)`，使每个 Agent 子图收到父 state 的 `messages`（本轮 `goal`）作为其唯一用户输入；之前 `asNode(false, false)` 会移除 `messages`，导致调度 Agent 只有系统指令、没有任何用户消息，从而把知识整理请求误判为 CHAT 短路。
3. 内部状态流转日志：`drive()` 运行开始/每个 `interruptAfter` 边界（下一节点、阶段、草稿轮数、已产出结果键）、每个 Agent 节点完成（node、phase、公开摘要）、Graph 完成（最终回复、模型/工具调用数）、条件边路由决策（coordinatorRoute/draftRoute/reviewRoute）、状态推进节点（set_decide/set_finish/set_draft_round）。
4. 新增回归测试 `KnowledgeCurationGraphRunIT#coordinatorReceivesGoalAsUserMessage`：断言调度 Agent 收到的模型 prompt 包含本轮目标（该断言在 `asNode(true,false)` 之前必失败）。
5. 相关测试全绿：`KnowledgeCurationGraphRunIT`(4)、`GraphAssemblyTest`(4)、`GraphRoutingTest`(4)、`RunExecutorDriveIT`(1)、`KnowledgeCurationPersistenceIT`(17)、`AgentRunPersistenceIT`(13)。

---

## 二、联调暴露的问题（按优先级）

### 问题 1（高）：各 Agent 上下文能看到前序结果，但为无标签、含自引用原始 JSON，导致重复工具调用与阶段误判

**现象**（run 28 日志）：
- 检索 Agent 总共 49 次工具调用，且 `knowledge_grep` 反复命中同一段（doc 11 第 13 行被 grep 多次）、多条 grep 返回空；
- 草稿 Agent 重新执行 `workspace_document_list` + `selected_draft_read`(doc 10) + `knowledge_document_read`(doc 11)，把检索 Agent 已读过的材料又读一遍；
- 审查 Agent 同样重新 `workspace_document_list` + `draft_read` + `selected_draft_list/read` + `knowledge_document_read`(doc 11) + `knowledge_search`；
- 调度 Agent 在 FINISH 阶段重复 START 输出（“现在开始检索…”），最终回复错误。

**先修正认知（实测验证**）：**各 Agent 并不是“只能看到 goal”**。框架在 `asNode(true, false)` 下会把每个 Agent 的最后一条结构化输出（原始 JSON）自动追加到父 `messages`（`ReactAgent` 的 `AgentToSubCompiledGraphNodeAdapter` + `processLastResponse` 只保留最后一条消息并 `mergeIntoCurrentState`）。因此下一环 Agent 的模型 prompt 实际已包含前序结果，只是以**无标签、且混入调度 Agent 自身早期 `stage=START/DECIDE` 输出**的原始 JSON 形式出现。

**真正根因**：
1. 前序结果是原始 JSON，没有阶段标签；调度 Agent 无法可靠识别自己处于 `START/DECIDE/FINISH`，看到上下文里自己上一轮输出的 `stage=START` 就照抄，→ FINISH 重复开场白；
2. 提示词只强调“读取草稿/来源”，没有告诉各 Agent“已提供的事实直接用、不要重复检索”，→ 草稿/审查重复读同名文档；
3. 检索提示词未约束去重，→ 大量重复 grep。

**修复（已落地）**：在 Graph 状态推进节点合成**带标签、可识别阶段**的上下文消息（`set_decide` 注入【检索结果 · 供调度决策】、`set_draft_context` 注入【调度决策 · 草稿写入要求】、`set_review_context` 注入【草稿结果 · 本次修订】、`set_finish`/`set_draft_round` 注入【审查结果】），并重写四份 `.md` 提示词：调度 Agent 只据【 】标签判断阶段、忽略自身早期输出；草稿/审查/检索直接使用已给事实、不再重复检索。

**已知局限**：框架仍会把原始 JSON 一并追加，上下文存在“原始 + 带标签”双份冗余；提示词已要求优先读标签。后续若需彻底消除冗余，需在框架层关闭子图 `messages` 回传或在适配器层过滤。

### 问题 2（高，已随问题 1 修复）：调度 Agent 在 FINISH 阶段重复 START 输出，最终回复错误

**现象**（run 28 日志，迭代=8）：`node=coordinator phase=START 公开摘要=已收到任务…现在开始检索…`，最终回复与 START 阶段完全一致。

**根因（与问题 1 同源）**：调度 Agent 收到的是无标签、含自身早期 `stage=START` 输出的原始 JSON，无法识别自己处于 FINISH；提示词也没有“据【 】标签判断阶段、忽略自身早期输出”的措辞。

**修复**：见问题 1；`coordinator.md` 已新增“据【 】标签判断阶段，忽略自身早期 `stage=START/DECIDE`”的明确指令，FINISH 阶段必须输出 `action=END` 与最终总结。

### 问题 3（高，已随问题 1 修复）：`draft_update` 持续报“引用了当前 run 或会话之外的来源”，3 次失败后以 sourceCount=0 落库

**现象**（run 28 日志 20:52:24 / 20:52:28 / 20:52:49 三次 `TOOL_ERROR`，最终 20:52:56 成功但 `sourceCount=0`）。

**根因**：`KnowledgeCurationTools.validateDraftSources` 只接受三类来源且 ID 必须是本 run 的 `agent_evidence` 证据 ID、本会话的 USER 消息 ID、或本会话选中草稿的文档 ID。草稿 Agent 看到的是无标签原始检索 JSON，无法稳定认出真正的证据 ID，便用**文档 ID**（doc 11/20/16/6）作来源引用，被 `evidenceIds.contains(...)` 拒绝；反复失败后放弃引用，落库 `sourceCount=0`。

**修复**：见问题 1；`drafter.md` 已要求直接使用【检索结果 · 供调度决策】里带 `sourceRefs` 的 SUPPORTED 事实，引用其中真实的证据 ID，不再用文档 ID 猜测。

**结论**：问题 3 是问题 1 的连带症状。修好消息传递后，草稿 Agent 会引用真实证据 ID，不再重复读取也不再被拒。

### 问题 4（中）：前端所有 Agent 输出挤进首张卡片，未按时间/Agent/工具调用分组

**需求的展示方式**：
- 按时间先后顺序排列；
- 每个 Agent 一个可折叠区域；
- 区域内部展示该 Agent 的工具调用过程；
- 工具调用若有输出，也一并展示。

### 问题 5（低）：`AsyncRequestTimeoutException` 反复出现

`TimeoutDeferredResultProcessingInterceptor.handleTimeout` 触发，属于异步 SSE / DeferredResult 请求空闲超时，与上述根因不同，单独排查。

---

## 三、修复方向（供后续任务拆分，未开始）

1. **消息传递**（问题 1/2/3 的根）：按阶段构图 `messages`，让每一环 Agent 的会话带上前一环的相关结构化结果，而非只有 `goal`。需在 `KnowledgeCurationGraphFactory` 增加状态合成逻辑，并同步四份 `.md` 指令，改动较大，应作为独立任务。
2. **前端时序卡片**（问题 4）：`KnowledgeTaskWorkspace.vue` 按 run 时间序渲染，每个 Agent 节点为可折叠卡片，内部展示工具调用/输出。
3. **调度 FINISH 语义**（问题 2 子项）：调度 Agent 应据已读取的结果生成 FINISH 总结，而非重复 START。
4. **人工验收清单回归**（`知识整理多Agent人工验收清单.md`）：上述修复后按场景 1、2、6 重新验收。
5. **异步超时**（问题 5）：单独定位 SSE/DeferredResult 空闲超时的来源与配置。

---

## 四、下一步

本轮先把上述现状与问题整理入库并存档；随后按优先级从问题 1（消息传递）开始排查与设计，再落到前端问题 4。修复时遵循「契约/接口 → 失败测试 → 最小实现 → 验证」，并同步设计文档 §6/§7 与 OpenSpec change。
