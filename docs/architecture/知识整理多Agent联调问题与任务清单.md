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

### 问题 1（高）：各 Agent 上下文实际只注入 goal，导致重复工具调用

**现象**（run 28 日志）：
- 检索 Agent 总共 49 次工具调用，且 `knowledge_grep` 反复命中同一段（doc 11 第 13 行被 grep 多次）、多条 grep 返回空；
- 草稿 Agent 重新执行 `workspace_document_list` + `selected_draft_read`(doc 10) + `knowledge_document_read`(doc 11)，把检索 Agent 已读过的材料又读一遍；
- 审查 Agent 同样重新 `workspace_document_list` + `draft_read` + `selected_draft_list/read` + `knowledge_document_read`(doc 11) + `knowledge_search`。

**根因**：`asNode(true, false)` 只把父 state 的 `messages`（恒为 `[goal]`）传给各 Agent；而 `templateRenderer` 是 passthrough（指令含 JSON 大括号/`|`，模板渲染会崩），所以 state 里的 `retrievalResult / draftResult / reviewResult / stage` **不会**被替换进任何 Agent 的指令。因此每个 Agent 只知道「本轮 goal + 自己的角色」，不知道前任 Agent 的结构化结果，只能重做全部读取。

**设计意图**（§7.1–7.4 要求）：
- 调度 DECIDE：读 `retrievalResult`；
- 草稿：读 `retrievalResult` + 调度 `draftInstruction`；
- 审查：读 `retrievalResult` + 调度决策 + `draftResult`；
- 调度 FINISH：读全部结果后汇总。

这些均未实现。

### 问题 2（高）：调度 Agent 在 FINISH 阶段重复 START 输出，最终回复错误

**现象**（run 28 日志，迭代=8）：
```
node=coordinator phase=START 公开摘要=已收到任务：将勾选草稿合并为稳定业务知识…现在开始检索相关草稿…
coordinatorRoute：stage=FINISH action=RETRIEVE -> FINISH
```
最终回复与 START 阶段完全一致（“现在开始检索…”），而不是面向管理员的收尾总结。

**根因**：调度 Agent 进入 FINISH 时，上下文仍只有 `goal`，不知道检索/草稿/审查结果，于是重复 START 的“开始检索”话术；`finalReply` 取 `coordinationResult.summary()` 得到该话术。同时调度 Agent 根本不知道自己处于 `START / DECIDE / FINISH` 哪个 stage（`stage` state 键未进 prompt），其阶段输出本质是模型猜测——本次恰好在 DECIDE 猜对、FINISH 猜错。

### 问题 3（高）：`draft_update` 持续报“引用了当前 run 或会话之外的来源”，3 次失败后以 sourceCount=0 落库

**现象**（run 28 日志 20:52:24 / 20:52:28 / 20:52:49 三次 `TOOL_ERROR`，最终 20:52:56 成功但 `sourceCount=0`）。

**根因**：`KnowledgeCurationTools.validateDraftSources` 只接受三类来源，且 ID 必须是：
- `EVIDENCE`：本 run 已落库的证据 ID（`agent_evidence`）；
- `USER_MESSAGE`：本会话的 USER 消息 ID；
- `SELECTED_DRAFT`：本会话选中草稿的文档 ID。

草稿 Agent 因看不到检索 Agent 的结构化结果，不知道本 run 的真实证据 ID，便用**文档 ID**（如 doc 11/20/16/6）作来源引用，被 `evidenceIds.contains(...)` 拒绝；反复失败后放弃引用，落库的草稿 `sourceCount=0`，缺少来源支撑。

**结论**：问题 3 是问题 1 的连带症状。修好消息传递（让草稿 Agent 拿到 `retrievalResult` 里的正确 `sourceRefs`）后，草稿 Agent 会引用真实证据 ID，不再重复读取也不再被拒。

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
