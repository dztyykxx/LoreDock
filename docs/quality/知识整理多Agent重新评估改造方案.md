# 知识整理多 Agent 重新评估改造方案

## 1. 文档目的

知识整理已经从单个 `knowledge-curator` Agent 切换为基于 Spring AI Alibaba Graph 的多 Agent 编排。本方案说明如何复用既有 Atlas 评估集重新评估，以及为此需要调整的代码、评估数据结构和报告指标。

本文是评估改造的影响面分析，不直接修改 Agent 实现，也不把建议中的评估结果当成已完成结果。后续若修改生产事件契约、公开 DTO 或持久化规则，需要单独建立或更新 OpenSpec change。

## 2. 当前基线与结论

现有评估入口位于 `backend/src/test/java/io/github/loredock/eval/`，数据位于：

- `docs/quality/atlas-agent-eval-tests/`：40 条 QA、8 条知识整理用例；
- `docs/quality/atlas-eval-documents/`：14 篇正式知识、8 篇候选草稿；
- `docs/quality/Agent全量评估报告.md`：旧单 Agent 的历史结果，不是新多 Agent 的结果。

当前旧评估的知识整理采集模型是：

```text
启动一个 selectedDraftId
        |
        v
等待 run 终态
        |
        +--> 最后一条 COORDINATOR_AGENT 消息 = finalResponse
        |
        +--> 工作区有效 DraftRevision = workspace
        |
        v
Judge 判断 issueType / action / unsafeWrite
```

当前图编排实际包含两层概念：

```text
会话级主 Agent（main_agent，唯一对话口径）
        |
        +--> 专家直调：retriever / drafter / reviewer
        |
        +--> FULL_CURATION
                |
                v
        完整整理子图：retriever -> coordinator -> drafter -> reviewer
```

代码中仍保留 `main_agent`、`coordinator`、`retriever`、`drafter`、`reviewer` 五个节点名；产品语义上通常称为四个 Agent，即“主 Agent（兼调度）、Retriever、Drafter、Reviewer”。评估报告必须同时保留代码节点名和逻辑角色名，避免把 `main_agent` 与完整整理子图中的 `coordinator` 混成一个节点，也避免对外宣称有五个业务 Agent。

结论：

1. QA 评估链路不是本次主要改造对象，可以作为回归组原样运行。
2. 8 条知识整理用例可以继续作为配对测试，比较旧单 Agent 与新多 Agent 的最终业务结果。
3. 评估器必须新增“图轨迹采集层”，至少记录主 Agent 路由、实际经过的 Agent 节点、关键决策、工具归属、审查结果和成本。
4. 旧 8 条用例不能完整衡量 Reviewer 的独立纠错召回率，需要后续增加带有可审查缺陷的补充用例或故障注入场景。

## 3. 评估范围

### 3.1 本次复测必须包含

- 相同数据集、相同项目范围、相同正式知识和候选草稿；
- 新多 Agent 的 8 条知识整理用例；
- 旧单 Agent 历史报告作为历史参考，若要声明“提升”，应在同一模型、同一数据种子、同一环境下重新跑单 Agent 基线；
- 最终问题识别、处置动作、工作区变更、不确定内容误写；
- 主 Agent 是否选择 `FULL_CURATION`，以及 `CHAT`/`TURN_DONE`/`FULL_CURATION` 是否与路径一致；
- Retriever、Drafter、Reviewer 是否按权限和职责执行；
- 模型调用数、Tool 调用数、输入/输出 Token、总耗时及各 Agent 阶段成本；
- 运行完成、等待人工、失败和恢复信息。

### 3.2 本次不应混入的内容

- 不把 QA 40 条结果与知识整理 8 条结果合成一个“Agent 准确率”；
- 不把阶段事件数量直接当成质量指标；
- 不把主 Agent 的公开回复、Agent summary 或 Tool 预览当成完整思维链；
- 不要求 8 条旧用例覆盖多文档输入、审查返工和所有直调组合；
- 不因评估需要新增通用 Runtime、评估数据库表或复制一套生产业务数据。

## 4. 既有数据的复用与必要调整

### 4.1 可以保持不变的部分

| 数据或组件 | 处理意见 | 原因 |
|---|---|---|
| 14 篇正式知识、8 篇候选草稿 | 保持不变 | 配对实验必须固定输入，避免数据变化掩盖 Agent 变化 |
| `documentIdMappings` | 保持不变 | 运行器和种子器依赖固定 Long ID |
| QA 40 条用例 | 保持不变并做回归 | 新图只影响知识整理，不应改变 QA 的评估口径 |
| `selectedDraftId` | 继续支持 | 旧 8 条用例可直接启动新图；不应为了评估先改成多草稿格式 |
| `relatedDocumentIds`、`forbiddenDraftFacts` | 保持并继续使用 | 分别用于证据关联和不确定内容误写检查 |
| `expected.workspace` | 保持 | 仍是最终业务产物的确定性判定依据 |

### 4.2 建议调整的评估数据字段

旧数据中的 `expected.issueType=null` 应在加载后规范化为 `NONE`，最好在下一数据集版本中直接写成 `"issueType": "NONE"`。`null` 同时表示“无问题”和“字段未填写”，不适合作为图路由或 Judge 输入。

建议不覆盖 `atlas-agent-eval-v1`，新增一个评估协议版本，例如 `atlas-agent-eval-multiagent-v2`。它可以引用同一批基础文档和草稿，但记录新的评估口径：

```json
{
  "caseId": "CUR-003",
  "input": {
    "projectIdentifier": "atlas",
    "selectedDraftId": 720003
  },
  "expected": {
    "issueType": "CONFLICT",
    "relatedDocumentIds": [710006],
    "action": "ASK_USER",
    "finalResponse": "参考回复，仅用于 Judge 理解业务结论",
    "workspace": null,
    "forbiddenDraftFacts": ["自动重试次数为 5 次"],
    "pathExpectation": {
      "entryAction": "FULL_CURATION",
      "requiredLogicalAgents": ["main_agent", "retriever"],
      "forbiddenWrites": true,
      "reviewRequired": false
    },
    "responseCriteria": {
      "mustMention": ["5 次", "2 次", "冲突", "管理员确认"],
      "mustNotClaimPublished": true
    }
  }
}
```

字段边界：

- `pathExpectation` 只写业务上必须满足的路径和安全不变量，不把每个准备节点、修复节点或事件条数写死；
- `requiredLogicalAgents` 用于检查角色覆盖，报告同时保留实际代码节点名；
- `reviewRequired=false` 只表示该场景在 `ASK_USER` 前应停止，不应调用 Reviewer；能够进入写入流程的场景才要求 Reviewer；
- `responseCriteria` 用于 Judge 评估语义覆盖，不能改成要求最终回复逐字相同；
- `expected.finalResponse` 保留作 Judge 参考，不再作为准确率的直接匹配依据。

### 4.3 数据集本身的前置问题

当前 `manifest.json` 的 `reviewedByHuman` 仍为 `false`。正式复测前应完成人工复核，并让数据加载器把该字段纳入质量门禁；否则报告只能标记为开发验证，不能称为正式评估。

另外，8 条用例全是单个候选草稿：

- 可以衡量最终问题识别、动作选择和安全写入；
- 不能充分衡量多文档合并、多个 Retriever 证据合并、多个工作文档之间的审查；
- 不能直接计算 Reviewer 对“草稿 Agent 已经写错内容”的发现召回率，因为现有用例大多在写入前就应 `ASK_USER`。

## 5. 代码影响面

### 5.1 `AtlasAgentEvalFixture`

文件：`backend/src/test/java/io/github/loredock/eval/AtlasAgentEvalFixture.java`

需要调整：

1. 增加 `pathExpectation`、`responseCriteria` 对应的 record；
2. 校验 `issueType`、`entryAction`、Agent 名称和动作枚举值；
3. 将 `null issueType` 兼容归一为 `NONE`，并在 v2 数据中禁止继续使用 null；
4. 校验 `requiredLogicalAgents` 与 `forbiddenWrites` 不产生互相矛盾的组合；
5. 将 `reviewedByHuman=false` 纳入正式评估门禁；
6. 继续禁止用例携带特化 `goal`，防止把预期动作泄漏给 Agent。

不需要改变基础文档读取、固定 ID 映射和草稿状态检查。

### 5.2 `AtlasCurationEvalRunner`

文件：`backend/src/test/java/io/github/loredock/eval/AtlasCurationEvalRunner.java`

当前只返回 `CurationActual(finalResponse, workspace, status, errorCode, elapsedMillis)`，需要扩展为“最终结果 + 图轨迹 + 成本”的结构。建议拆成以下测试专用 record：

```java
record CurationActual(
    String caseId,
    RunStatus status,
    String errorCode,
    String finalResponse,
    List<WorkspaceActual> workspace,
    GraphTrace trace,
    RunUsage usage,
    long elapsedMillis
) {}

record GraphTrace(
    MainAction entryAction,
    MainAction finalAction,
    List<AgentStageActual> stages,
    List<RouteActual> routes,
    List<SafeToolActual> tools,
    RetrievalObservation retrieval,
    DraftObservation draft,
    ReviewObservation review
) {}
```

采集规则：

- 最终回复仍取当前 run 的最终公开消息，但应按“当前 run + 最终消息”识别，不只依赖历史 `COORDINATOR_AGENT` 名称；
- 主 Agent 的尾部 JSON 中解析 `action` 和 `expertCalls`，正文只作为 `finalResponse`；
- 从 `AGENT_STAGE` 事件按 `runId + sequence` 收集阶段，不通过时间猜测 Tool 属于哪个 Agent；
- 从 `knowledge_tool_invocation.agent_node` 收集工具归属，只保留工具名、状态、purpose、结果数量/摘要、耗时和安全截断标志；
- 从 run 的 `modelCallCount`、`toolCallCount`、`inputTokens`、`outputTokens` 收集总成本；
- 阶段 Token 使用 `AgentEvent.Payload.promptTokens/completionTokens`，并区分“实际为 0”和“模型未返回 usage”；
- 不把完整 Prompt、Graph State、Checkpoint、完整 Tool JSON 或隐藏中间推理写入报告。

`awaitTerminal` 还应明确区分：

- 正常完成的 `COMPLETED`；
- 多 Agent 结构化修复耗尽或上下文预算耗尽后的 `WAITING_FOR_USER`；
- `FAILED`、`CANCELLED`、`TERMINATED`。

等待人工不是成功，也不是无条件失败；它应作为业务终态记录并由指标单独统计。

### 5.3 `AtlasEvalMetrics`

文件：`backend/src/test/java/io/github/loredock/eval/AtlasEvalMetrics.java`

保留旧指标，新增以下多 Agent 指标：

| 指标 | 计算对象 | 目的 |
|---|---|---|
| `completionRate` | 运行是否达到预期业务终态 | 区分质量错误和运行失败 |
| `entryRoutingAccuracy` | `expected.pathExpectation.entryAction` 与主 Agent 实际 action | 检查是否正确进入完整整理流程 |
| `pathInvariantRate` | 必须出现的 Agent、禁止出现的 Agent/写入是否满足 | 检查图编排和职责边界 |
| `retrievalEvidenceCoverage` | Retriever 输出或实际来源是否覆盖 `relatedDocumentIds` | 检查证据是否真正支撑决策 |
| `draftGroundingRate` | 工作区写入事实是否都有允许来源 | 检查 Drafter 是否只写受支持事实 |
| `reviewPassRate` | 需要审查的场景是否产生有效 Reviewer 结论 | 检查审查环节是否执行 |
| `reviewReworkRate` | Reviewer 返回 `REVISE` 后是否回到 Drafter，且不超过上限 | 检查返工闭环 |
| `unsafeWriteRate` | 现有定义保持不变 | 检查 CONFLICT/MISSING 的危险写入 |
| `agentTokenShare` | 按 Agent 汇总输入/输出 Token | 解释成本变化，不作为质量分 |

旧指标必须继续输出，以便与历史报告配对：问题类型 F1、处置正确率、误写率。新增轨迹指标不应替换旧指标，否则无法判断最终业务收益是否真实改善。

### 5.4 `AgentEvalReport`

文件：`backend/src/test/java/io/github/loredock/eval/AgentEvalReport.java`

报告结构需要从“逐题实际结果”扩展为：

```text
Report
├── datasetVersion / projectIdentifier / executedAt / environment
├── experiment
│   ├── systemVariant: SINGLE_AGENT | MULTI_AGENT
│   ├── modelName / skillDigest / agentSpecDigest / graphDefVersion
│   └── seedFingerprint
├── qaResults[]                 # 旧 QA 回归
├── curationResults[]
│   ├── expected
│   ├── actual.finalResponse / workspace
│   ├── actual.trace
│   └── verdict
├── qaMetrics
├── curationMetrics             # 旧业务指标 + 新图指标
└── gates
```

其中：

- `systemVariant` 是配对实验的必要字段，不能只靠文件名区分；
- `skillDigest`、`agentSpecDigest`、`graphDefVersion` 用于解释同一数据集下的运行差异；
- `seedFingerprint` 用于证明正式知识和草稿输入没有变化；
- 报告必须区分“未执行”“运行失败”“等待人工”“业务判定错误”；不能把缺失结果按 0 分静默计入；
- stdout 只输出每题场景、路径摘要、节点结果数量、终态、关键指标和报告地址，正文继续遵守项目的安全裁剪规则。

### 5.5 `AtlasEvalJudge` 与 `AtlasEvalJudgeRunner`

文件：

- `backend/src/test/java/io/github/loredock/eval/AtlasEvalJudge.java`
- `backend/src/test/java/io/github/loredock/eval/AtlasEvalJudgeRunner.java`

Judge 输入需要增加安全图轨迹摘要：

- 主 Agent 实际入口 action；
- Retriever 的问题类型、证据状态、来源 ID；
- Coordinator 的实际决策 action；
- Drafter 的写入回执；
- Reviewer 的 verdict、finding code 和审查修订号；
- 最终回复、实际工作区、禁止写入事实。

Judge 仍然只使用候选草稿、相关正式文档和实际公开结果判断，不使用模型常识补充 Atlas 事实。建议把输出拆为：

```json
{
  "issueType": "CONFLICT",
  "action": "ASK_USER",
  "issueCorrect": true,
  "actionCorrect": true,
  "unsafeWrite": false,
  "routingCorrect": true,
  "evidenceSufficient": true,
  "reviewCorrect": null,
  "reason": "..."
}
```

`reviewCorrect` 对没有进入 Reviewer 的 `ASK_USER` 用例应为 `null`，不能按 Reviewer 未执行判错；只有数据标记 `reviewRequired=true` 时才参与 Reviewer 指标。

### 5.6 测试类与脚本模型

需要同步：

- `AtlasAgentEvalDeterministicIT`：脚本模型必须按主 Agent → 专家直调或完整子图返回不同响应，验证采集器不是只支持旧单 Agent；
- `AtlasAgentEvalRealModelIT`：报告记录多 Agent 版本、定义摘要、图版本和阶段成本；QA 与知识整理的限制参数保持兼容；
- `AtlasEvalResume`：续跑时按 `datasetVersion + systemVariant + caseId` 合并，防止把旧单 Agent 报告的结果误合入新多 Agent 报告；
- `AtlasEvalJudgeIT`：读取扩展后的 trace，并校验新增 Judge 字段的可空语义；
- `AtlasAgentEvalFixtureTest`、`AtlasEvalMetricsTest`、`AgentEvalReportTest`：增加 schema、路径不变量、缺失阶段、等待人工和版本隔离测试。

确定性测试至少要覆盖三条路径：

1. `FULL_CURATION` 正常路径：主 Agent 进入完整子图，Retriever → Coordinator → Drafter → Reviewer，最终产生受来源约束的工作区；
2. `FULL_CURATION` 阻断路径：Retriever/Coordinator 判断 `ASK_USER`，不调用 Drafter，不产生工作区写入；
3. `CHAT` 或 `TURN_DONE` 短路路径：不产生知识写入，也不伪造 Retriever、Drafter、Reviewer 阶段。

## 6. 生产代码与数据结构是否需要改变

### 6.1 最小方案：优先复用现有公开投影

如果目标只是复测最终业务结果，生产代码可以不新增表：

- `agent_run` 已有总模型/Tool 调用和 Token 字段；
- `agent_run_event` 已有 `AGENT_STAGE` 和安全 Payload；
- `knowledge_tool_invocation` 已有 `agent_node`；
- `KnowledgeTask` 已有 events、messages、toolInvocations、workspace、patchSets；
- Graph 结果契约已有 `MainTurnResult`、`RetrievalResult`、`DraftResult`、`ReviewResult`。

此时只改测试评估器，从现有任务快照和安全事件重建 `GraphTrace`。但这种方案只能可靠评估阶段是否发生，不能可靠知道每个结构化决策字段，因为当前公开阶段 Payload 主要是 `phase/name/summary/status/token`。

### 6.2 推荐方案 2：扩展现有安全事件 Payload，不新增评估表

方案 2 的目标不是把内部 Graph State 暴露出来，而是在现有 `AGENT_STAGE` 安全事件上增加一组“服务端确认后的评估投影”。这样评估器可以从正常业务快照和公开事件得到图轨迹，不需要直接查询 Checkpoint，也不需要另建 `agent_evaluation` 表。

#### 6.2.1 Payload 结构

建议给现有 `AgentEvent.Payload` 在末尾增加一个可空的 `curation` 字段。它比继续向通用 Payload 平铺十几个字段更容易限定作用域，也不会把 QA 事件模型继续变成知识整理专用模型。

```json
{
  "phase": "DECIDE",
  "name": "coordinator",
  "status": "COMPLETED",
  "summary": "发现来源冲突，等待管理员确认",
  "promptTokens": 120,
  "completionTokens": 30,
  "curation": {
    "action": "ASK_USER",
    "issueType": "CONFLICT",
    "draftStatus": null,
    "reviewVerdict": null,
    "sourceRefs": [{"type": "EVIDENCE", "id": 710006}],
    "drafts": [],
    "findings": [],
    "expertCalls": []
  }
}
```

对应 Java 数据载体建议为一个生产 API 内部的嵌套 record：

```java
record CurationProjection(
    String action,
    String issueType,
    String draftStatus,
    String reviewVerdict,
    List<SourceRefProjection> sourceRefs,
    List<DraftProjection> drafts,
    List<FindingProjection> findings,
    List<String> expertCalls
) {}
```

字段只在适用的 Agent 阶段填写：

| Agent/阶段 | 允许填写的字段 | 服务端来源 |
|---|---|---|
| `main_agent` / `MAIN` | `action`、`expertCalls` | 尾部 JSON 解析出的 `MainTurnResult` |
| `coordinator` / `START`、`DECIDE`、`FINISH` | `action` | `CoordinatorResult` 的 stage/action，经过路由校验 |
| `retriever` / `RETRIEVE` | `issueType`、`sourceRefs` | `RetrievalResult` 的 issueType、Fact.sourceRefs |
| `drafter` / `DRAFT` | `draftStatus`、`drafts` | `DraftResult` 的 status/drafts |
| `reviewer` / `REVIEW` | `reviewVerdict`、`drafts`、`findings` | `ReviewResult` 的 verdict/reviewedDrafts/findings |

`SourceRefProjection` 只保留 `type` 和稳定 `id`；`DraftProjection` 只保留 `draftId`、`revision`、`operation`；`FindingProjection` 只保留 `code` 和 `draftId`。不把事实 statement、finding description、suggestion 或完整 Markdown 放进事件。

建议的上限为：`sourceRefs` 20 条、`drafts` 10 条、`findings` 20 条、`expertCalls` 10 条；超出时由服务端裁剪并将现有 `truncated=true` 置为 true。所有枚举字段必须经过白名单校验，未知值不能静默透传。

#### 6.2.2 生产代码改动清单

| 文件 | 改动 | 说明 |
|---|---|---|
| `backend/src/main/java/io/github/loredock/agent/api/AgentEvent.java` | 增加 `CurationProjection` 及其子 record，在 `Payload` 末尾增加可空字段 | 保持旧构造器兼容；旧事件缺字段时反序列化为 null |
| `backend/src/main/java/io/github/loredock/agent/service/KnowledgeCurationRunExecutor.java` | 在 `persistStageEvent` 前解析当前节点的结构化结果并构造投影 | 只投影已通过 Graph 路由/业务校验的结果，不直接信任模型 JSON |
| `backend/src/main/java/io/github/loredock/agent/service/AgentEventService.java` | 保持 JSONB 序列化，并增加投影长度、列表大小和枚举值校验 | 防止事件变成完整结果或大文本存储 |
| `frontend/src/api/knowledgeTasks.ts` | 增加可选 `curation` 类型 | 旧任务和 QA 事件均允许为空 |
| `frontend/src/components/KnowledgeTaskWorkspace.vue` | 评估所需字段可被读取，但默认只展示现有安全摘要 | 本次不新增独立评估页面，不展示事实正文或隐藏状态 |
| `backend/src/test/java/io/github/loredock/agent/service/KnowledgeCurationRunExecutorDriveIT.java` | 校验各节点投影和空字段语义 | 覆盖真实 Executor + 脚本模型 + PostgreSQL 事件落盘 |
| `backend/src/test/java/io/github/loredock/agent/mapper/AgentRunPersistenceIT.java` | 校验 JSONB 往返和旧 Payload 兼容 | 证明不新增迁移也不会破坏历史事件读取 |

数据库方面只使用现有 `agent_run_event.payload JSONB`，不新增表、不新增列、不新增索引、不改变 `agent_run` 和 `knowledge_tool_invocation` 的业务结构。是否需要 Flyway 迁移的判断标准是：只增加 JSONB 可选字段时不需要；如果后来决定把投影拆成列或表，才另行建立 OpenSpec 和迁移。

`AgentEvent.Payload` 当前已经存在 15 参数兼容构造和带 Token 的 17 参数调用点。新增字段后必须保留这两类调用入口，并统一把 `curation` 设为 null；不能一次性修改所有 QA 事件和历史测试数据。Jackson 读取旧 JSON 时缺失的 `curation` 也必须得到 null，而不是反序列化失败。

#### 6.2.3 Executor 投影时序

`KnowledgeCurationRunExecutor` 的 `persistAgentResult` 已经拿到当前节点名和 `AssistantMessage`，建议按以下顺序处理：

```text
模型响应
   |
   v
按节点解析结构化结果
   |
   +--> Graph 路由/业务校验通过？ -- 否 --> 不生成业务投影，进入现有 Repair/Recovery
   |
   v
构造 CurationProjection（只保留白名单字段）
   |
   v
写入 AGENT_STAGE 的 AgentEvent.Payload
   |
   +--> 评估器读取事件
   +--> 前端继续读取原有安全事件
```

具体规则：

1. `main_agent` 使用 `splitTailJson`/`tolerantStructured` 得到 `action` 和 `expertCalls`；最终回复正文仍单独保存，不把正文放入投影。
2. `retriever` 的 `sourceRefs` 只能来自已经解析的 `Fact.sourceRefs`，只保留类型和 ID；没有来源的 Fact 不得被投影成有效证据。
3. `drafter` 的 `drafts` 只能来自实际 `DraftResult`，但最终是否真的产生修订仍以工作区和 `DraftRevision` 为准，不能仅凭模型回执判定写入成功。
4. `reviewer` 的 `reviewVerdict` 和 `findings` 只表示实际审查结果；审查结果绑定的 draftId/revision 必须与实际工作区一致。
5. `coordinator` 的 `action` 只能用于解释当前 stage；最终路径是否正确由 Graph 实际后继事件和工作区结果共同判断。
6. Tool 事件继续使用已有 `agent_node` 字段归属，不把 Tool 原始参数和结果复制到 `curation`。

当前最终消息在业务表中仍使用 `COORDINATOR_AGENT` 这一公开角色值；本方案不要求立即修改 `MessageRole`，评估器应结合 `runId`、消息插入顺序和主 Agent 阶段事件识别最终回复，避免误把角色名当成真实节点名。

#### 6.2.4 评估器如何消费投影

`AtlasCurationEvalRunner` 读取 `KnowledgeTask` 后构建 `GraphTrace`：

```text
task.events（AGENT_STAGE + curation）
task.toolInvocations（agentNode + toolName + status）
task.runs（状态、调用数、Token、耗时）
task.workspaceDocuments / patchSets（真实写入）
                         |
                         v
                 GraphTrace + CurationActual
```

评估器不再从自然语言 `summary` 猜测 issueType/action/verdict：

- `entryAction` 取 `main_agent.curation.action`；
- `decisionAction` 取 `coordinator` 在 `DECIDE` 阶段的 projection；
- `issueType` 取 `retriever` projection；
- `draftResult` 取 `drafter` projection，但以 workspace/patchSets 做最终写入事实校验；
- `reviewVerdict` 取 `reviewer` projection，并检查是否绑定当前 revision；
- route 由“当前 action + 后继 AGENT_STAGE + Tool/工作区事实”推导，不另存一套 route 表。

这样既能判断 `ASK_USER` 是否在 Drafter 之前短路，也能判断成功写入路径是否经过 Reviewer，同时不需要把内部 StateGraph 节点状态直接变成对外 API。

#### 6.2.5 安全与兼容门禁

- 新字段全部可空；旧 `agent_run_event` JSON 和旧前端 fixture 缺少 `curation` 时必须正常读取。
- `CurationProjection` 只能出现在知识整理的 `AGENT_STAGE` 事件中；QA 事件必须为空。
- 不允许保存 Prompt、模型原始消息、完整 Tool JSON、完整事实正文、Graph State、Checkpoint ID 或 hidden reasoning。
- `sourceRefs`、`drafts`、`findings` 只存稳定 ID、枚举、版本号和有限数量，不替代现有业务表中的完整事实。
- 事件投影失败不能改变已经完成的业务写入结果；应记录脱敏错误并让评估报告标记“轨迹缺失”，不能伪造默认值。
- 事件投影契约属于公开行为变化，实施前必须更新 `knowledge-curation-agent` OpenSpec、后端 API 契约、前端类型和回归测试。
- 不新增 `agent_evaluation` 表，评估报告继续作为测试输出文件。

## 7. 建议的执行顺序

1. 固定评估协议：决定是只做最终结果复测，还是同时验收图轨迹；本方案建议后者。
2. 复制 v1 数据为 v2 评估协议，补齐 `NONE`、路径不变量和响应判定条件；人工复核后再把 `reviewedByHuman` 置为 true。
3. 先扩展测试 record 和确定性采集，不接真实模型，验证三条最小路径。
4. 扩展报告与断点续跑，确保单 Agent 与多 Agent 结果不会混合。
5. 按方案 2 更新安全事件 Payload：先建立 OpenSpec，定义 `CurationProjection` 的白名单和兼容语义，再实现 Executor 投影；不应在评估测试里直接读取 Graph State 或数据库内部结构绕过契约。
6. 运行多 Agent 冒烟：至少覆盖一个正常写入、一个 `ASK_USER`、一个短路/元对话场景。
7. 在同一环境运行 8 条知识整理全量复测；QA 40 条同时做回归。
8. 先生成原始报告，再用独立 Judge 生成评判报告；最后按 `systemVariant` 做配对比较。

## 8. 结果报告最低要求

最终报告至少应同时给出：

| 维度 | 最低输出 |
|---|---|
| 最终业务效果 | 问题类型 F1、处置正确率、误写率、工作区匹配率 |
| 图编排正确性 | 主 Agent 入口动作、路径不变量通过率、非法 Agent/Tool 调用数 |
| 证据链 | 相关文档覆盖、写入事实来源覆盖、引用/来源缺失数 |
| 审查闭环 | Reviewer 执行率、返工次数、审查发现、审查召回率（仅在有适用样本时） |
| 运行稳定性 | 完成率、等待人工数、失败数、恢复/重试次数 |
| 成本 | 总耗时、模型调用数、Tool 调用数、总 Token、按 Agent Token |
| 实验可比性 | 数据集版本、模型、Skill/Agent 定义摘要、Graph 版本、种子指纹 |

结论必须分开写：

- “最终业务结果是否改善”；
- “职责隔离和安全边界是否按设计执行”；
- “成本和延迟增加多少”；
- “哪些能力仍没有被旧 8 条用例覆盖”。

只有在输入、模型、版本和执行环境可比时，才可以把新结果与旧单 Agent 结果做因果上的提升比较。旧报告中的 `100% / 66.7% / 25%` 只能作为历史基线，不能直接写成新多 Agent 的结果。

## 9. 关联代码与资料

- [评估数据构造要求](./Agent评估测试数据构造要求.md)
- [评估框架使用说明](./Agent评估测试框架使用说明.md)
- [旧全量评估报告](./Agent全量评估报告.md)
- [多 Agent 图编排设计](../architecture/知识整理多Agent架构设计.md)
- [主 Agent 最终回复契约](../../openspec/changes/main-agent-reply-channel/proposal.md)
- [会话级多路编排 OpenSpec](../../openspec/changes/multiagent-conversation-orchestration/proposal.md)
