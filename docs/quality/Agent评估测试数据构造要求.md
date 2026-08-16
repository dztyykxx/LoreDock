# LoreDock Agent 评估测试数据构造要求

## 1. 目标

本文指导数据生成模型基于 Atlas 基础文档构造轻量测试数据，主要用于 PPT 展示：

- QA Agent：Top-5 检索准确率、Top-5 检索召回率、忠实度、相关性；
- 知识整理 Agent：重复识别 F1、矛盾识别 F1、缺失识别 F1、处置正确率；
- 补充指标：不确定内容误写率。

测试结构遵循两个原则：

1. 每条用例在同一个 JSON 中保存输入和预期输出；
2. 只保留运行和评分真正需要的字段，其余字段由评估程序生成或从实际接口结果读取。

## 2. 数据来源

测试数据基于《Agent 评估基础文档数据集生成说明》：

- 正式知识：`DOC-01`～`DOC-14`；
- 候选草稿：`DRAFT-01`～`DRAFT-08`；
- 8 篇候选草稿对应 8 条知识整理用例，每条用例只选择一篇完整草稿。

基础文档使用业务键，加载测试数据库时映射成固定 Long ID：

```json
{
  "DOC-01": 710001,
  "DOC-12": 710012,
  "DRAFT-01": 720001,
  "DRAFT-08": 720008
}
```

## 3. 输出文件

```text
atlas-agent-eval-tests/
├── manifest.json
├── qa-cases.json
└── curation-cases.json
```

`manifest.json` 只保留：

```json
{
  "datasetVersion": "atlas-agent-eval-v1",
  "projectIdentifier": "atlas",
  "qaCaseCount": 40,
  "curationCaseCount": 8,
  "reviewedByHuman": false,
  "documentIdMappings": {}
}
```

`reviewedByHuman` 在人工复核前必须为 `false`。

## 4. QA 测试用例

### 4.1 用例结构

```json
{
  "caseId": "QA-001",
  "caseType": "SINGLE_DOCUMENT",
  "input": {
    "projectIdentifier": "atlas",
    "branch": "main",
    "question": "上传的材料为什么不能马上被大家搜到？"
  },
  "expected": {
    "resultType": "ANSWER",
    "refusalReason": null,
    "resultText": "上传材料属于候选内容，必须经过 ADMIN 人工审核并正式发布后，才能进入普通用户检索。这样可以避免未经确认的内容直接成为正式知识。",
    "documentIds": [710007]
  }
}
```

拒答用例：

```json
{
  "caseId": "QA-035",
  "caseType": "INSUFFICIENT_EVIDENCE",
  "input": {
    "projectIdentifier": "atlas",
    "branch": "main",
    "question": "Atlas 是否会自动把场景包翻译成西班牙语？"
  },
  "expected": {
    "resultType": "REFUSAL",
    "refusalReason": "INSUFFICIENT_EVIDENCE",
    "resultText": "当前知识库没有足够依据确认 Atlas 支持场景包自动翻译。",
    "documentIds": []
  }
}
```

### 4.2 与当前代码的对应

| 测试字段 | 当前代码 |
|---|---|
| `projectIdentifier` | Web QA Controller 路径参数 |
| `branch` | `CreateWebQaQuestionRequest.branch` |
| `question` | `CreateWebQaQuestionRequest.question` |
| `expected.resultType` | `QaQuestion.ResultType` |
| `expected.refusalReason` | `QaQuestion.RefusalReason` |
| `expected.resultText` | `WebQaQuestionResponse.resultText` 的参考答案 |
| `expected.documentIds` | 正确知识来源的 `documentId` |

以下字段不放进数据集，由评估程序生成：

- `idempotencyKey`：使用 `caseId` 生成；
- `conversationId`：固定为 `null`；
- 运行状态、步骤数、模型调用数和事件序号：从实际响应读取。

### 4.3 用例分布

共 40 条：

| 类型 | 数量 |
|---|---:|
| `SINGLE_DOCUMENT` | 14 |
| `MULTI_DOCUMENT` | 10 |
| `PARAPHRASE` | 6 |
| `INSUFFICIENT_EVIDENCE` | 4 |
| `SOURCE_CONFLICT` | 3 |
| `SCOPE_OR_LIFECYCLE` | 3 |

### 4.4 构造要求

1. 问题使用自然中文，不暴露文档 ID 或预期结果。
2. 可回答问题只能使用当前范围内的 `PUBLISHED` 文档。
3. 多文档问题至少需要两个不可相互替代的来源。
4. 口语问题不能直接重复文档标题。
5. 证据不足问题应与 Atlas 业务相关，但正式文档中确实没有答案。
6. `expected.resultText` 必须是一份自然、完整的参考回答。
7. `expected.documentIds` 必须覆盖参考回答需要的全部来源。
8. 实际评分使用语义匹配，不要求模型逐字复现参考回答。

## 5. QA 实际结果与检索原文

忠实度评估必须使用 Agent 本轮实际看到的检索原文。当前公开问答响应没有文档原文字段，因此评估程序需要额外记录检索结果。

建议实际结果格式：

```json
{
  "caseId": "QA-001",
  "actual": {
    "resultType": "ANSWER",
    "refusalReason": null,
    "resultText": "模型实际最终回答",
    "citationDocumentIds": [710007],
    "retrievals": [
      {
        "query": "候选材料检索和审核规则",
        "documents": [
          {
            "documentId": 710007,
            "title": "人工审核与正式发布规则",
            "relevance": 0.91,
            "content": "本次实际返回给 Agent 的文档原文或片段",
            "truncated": false
          }
        ]
      }
    ]
  },
  "judge": {
    "faithfulness": 96,
    "relevance": 94,
    "reason": "回答中的审核和检索隔离结论均有本轮检索原文支持，并且直接回答了用户问题。"
  }
}
```

`retrievals.documents.content` 必须是本轮实际提供给 Agent 的文本：

- Agent 只看到片段时，只保存该片段；
- 发生裁剪时设置 `truncated=true`；
- 不得在运行结束后用另一修订的完整文档替换；
- 多次检索按调用顺序保存。

`content` 是评估专用返回字段。本阶段只在构造文档中明确要求，不修改公开 Web API。

## 6. 知识整理测试用例

### 6.1 用例结构

```json
{
  "caseId": "CUR-001",
  "input": {
    "projectIdentifier": "atlas",
    "selectedDraftId": 720001
  },
  "expected": {
    "issueType": "DUPLICATE",
    "relatedDocumentIds": [710007],
    "action": "NO_CHANGE",
    "finalResponse": "本次候选材料与已有的《人工审核与正式发布规则》内容重复，没有形成需要单独发布的新知识，因此本轮不创建重复工作文档。",
    "workspace": null,
    "forbiddenDraftFacts": []
  }
}
```

`goal` 不放进测试数据：知识整理目标是**系统统一配置的通用提示词**，对全部用例一致，
不随用例特化。用例数据携带特化 goal（如"已有同主题正式知识时优先合并"）会把预期处置
泄漏给被评估 Agent，属于数据污染；评估框架加载数据时会拒绝携带非空 goal 的用例。

矛盾用例：

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
    "finalResponse": "候选材料称自动重试 5 次，但现有正式规则规定最多重试 2 次，两者冲突。当前无法判断哪一方有效，本轮不写入该结论，请管理员确认正确次数。",
    "workspace": null,
    "forbiddenDraftFacts": [
      "自动重试次数为 5 次"
    ]
  }
}
```

需要修改正式文档时，`workspace` 使用：

```json
{
  "operation": "MODIFY",
  "baselineDocumentId": 710004
}
```

需要新增文档时，`workspace` 使用：

```json
{
  "operation": "ADD",
  "baselineDocumentId": null
}
```

### 6.2 与当前代码的对应

| 测试字段 | 当前代码 |
|---|---|
| `projectIdentifier` | Knowledge Task Controller 路径参数 |
| `selectedDraftId` | 转成 `StartBody.selectedDraftIds=[selectedDraftId]` |
| `expected.finalResponse` | 最后一条非空、无 Tool Call 的 `COORDINATOR_AGENT` 消息 |
| `workspace.operation` | `WorkspaceDocument.operation`，值为 `ADD/MODIFY` |
| `workspace.baselineDocumentId` | `WorkspaceDocument.baselineDocumentId` |

以下字段由评估程序生成：

- `idempotencyKey`：使用 `caseId` 生成；
- `triggerReason`：固定为“Agent 评估”；
- `goal`：系统统一配置的通用知识整理提示词（评估框架 `AtlasCurationEvalRunner.DEFAULT_GOAL`，可用 `loredock.agent-eval.curation-goal` 覆盖），对全部用例一致，不随用例特化；
- `selectedDraftIds`：由单个 `selectedDraftId` 转为单元素数组；
- 任务和运行状态：从实际 `KnowledgeTask` 读取。

`issueType` 和 `action` 是评估标签，不是生产 API 枚举。

### 6.3 8 条用例分布

| 草稿 | 问题类型 | 预期动作 |
|---|---|---|
| `DRAFT-01` | `DUPLICATE` | `NO_CHANGE` |
| `DRAFT-02` | `DUPLICATE` | `MERGE` |
| `DRAFT-03` | `CONFLICT` | `ASK_USER` |
| `DRAFT-04` | `CONFLICT` | `ASK_USER` |
| `DRAFT-05` | `MISSING` | `ASK_USER` |
| `DRAFT-06` | `MISSING` | `ASK_USER` |
| `DRAFT-07` | 无问题，值为 `null` | `ADD_OR_UPDATE` |
| `DRAFT-08` | 无问题，值为 `null` | `ADD_OR_UPDATE` |

### 6.4 构造要求

1. 每条用例只选择一篇完整草稿。
2. 不基于同一草稿生成多个变体。
3. 重复必须是相同对象、范围和条件下的同一事实。
4. 矛盾必须是相同条件下不能同时成立的结论。
5. 缺失必须影响规则执行或发布安全。
6. 无问题草稿必须内容完整、事实明确。
7. `expected.finalResponse` 必须是一份自然、完整的参考最终回复。
8. 冲突和缺失用例必须列出不能写入工作草稿的 `forbiddenDraftFacts`。

## 7. 知识整理实际结果

问题识别率主要评估知识整理模型的最终回复；处置和误写情况再检查实际工作区。

建议实际结果格式：

```json
{
  "caseId": "CUR-003",
  "actual": {
    "finalResponse": "模型实际最终回复",
    "workspace": [
      {
        "operation": "MODIFY",
        "baselineDocumentId": 710006,
        "markdown": "实际工作草稿全文"
      }
    ]
  },
  "judge": {
    "issueType": "CONFLICT",
    "action": "ASK_USER",
    "issueCorrect": true,
    "actionCorrect": true,
    "unsafeWrite": false,
    "reason": "最终回复识别了 2 次与 5 次的冲突并请求人工确认，工作草稿中没有写入 5 次重试结论。"
  }
}
```

- `finalResponse` 来自最后一条非空、无 Tool Call 的 `COORDINATOR_AGENT` 消息；
- `workspace.markdown` 来自实际 `DraftRevision.markdown`；
- 不保存完整任务快照、Tool 列表、事件、PatchSet 或全部修订历史。

## 8. LLM Judge

### 8.1 QA Judge

输入：

- 用户问题；
- 预期 `resultText`；
- 实际 `resultText`；
- 实际检索文档原文；
- 实际引用文档。

输出两个百分制分数：

```json
{
  "faithfulness": 96,
  "relevance": 94,
  "reason": "回答中的业务事实均可由实际检索原文支持，并且直接回应了问题。"
}
```

- 忠实度只判断实际回答是否由本轮检索原文支持；
- 相关性判断实际回答是否直接解决用户问题；
- `reason` 简要说明事实支持情况和相关性评分依据；
- Judge 不得使用自身常识补充 Atlas 事实。

评分采用锚定分档（先按可观察条件定档，再在档内取整数分），口径如下：

| 分档 | faithfulness 忠实度 | relevance 相关性 |
| --- | --- | --- |
| 96-100 | 全部业务事实均可由原文直接支持（含同义转述），无任何无依据事实；拒答时拒答依据充分 | 直接回答核心并覆盖全部子问，无答非所问或回避 |
| 90-95 | 关键事实均有原文支持，仅存在不影响结论的措辞概括，无新增业务事实 | 直接回答核心问题，遗漏次要子问，或含少量无关铺垫 |
| 80-89 | 存在一处无原文支持的具体业务事实（数字、状态、权限、流程），或关键结论依据薄弱 | 只回答了部分子问，或内容相关但未切中核心 |
| 60-79 | 存在多处无依据事实或关键事实与原文矛盾；检索本有答案却无依据拒答 | 仅涉及问题主题，未解决核心问题（如只给背景或流程而不给结论） |
| 40-59 | 大部分内容无原文支持，或内容与检索原文无关 | 明显答非所问或回避问题，仅提及相关主题词 |
| 0-39 | 几乎全部内容无原文支持，或与原文明显矛盾 | 与问题无关 |

分数是唯一判定口径；布尔口径（如"忠实/不忠实"）可由阈值派生，例如忠实度 ≥ 90 视为忠实，报告本身保留百分制平均分。

### 8.2 知识整理 Judge

输入：

- 候选草稿全文；
- 相关正式文档全文；
- 预期 `issueType`、`action` 和 `finalResponse`；
- 实际 `finalResponse`；
- 实际工作草稿正文；
- `forbiddenDraftFacts`。

输出：

```json
{
  "issueType": "CONFLICT",
  "action": "ASK_USER",
  "issueCorrect": true,
  "actionCorrect": true,
  "unsafeWrite": false,
  "reason": "最终回复正确指出冲突并请求人工确认，实际工作草稿没有写入未确认结论。"
}
```

`reason` 必须说明问题识别、动作判断和误写判断的直接依据。

问题识别必须看最终回复，不能根据 Tool 调用或模型中间过程判定。

## 9. 指标计算

### 9.1 QA Agent

参与 Top-5 统计的用例：`ANSWER` 用例，以及携带期望文档的 `SOURCE_CONFLICT` 拒答用例
（正确拒答的前提是同时检索到冲突文档，检索质量可测）；无期望文档的证据不足拒答不参与。

```text
Top-5 检索准确率
= Top-5 找回的 expected.documentId 数量
  ÷ 5（Top-5 窗口大小，按用例平均）
  含义：目标引用文档在最终 Top-5 中的出现率

Top-5 检索召回率
= Top-5 找回的 expected.documentId 数量
  ÷ 全部应找回文档数量（按用例平均）
  含义：出现的目标引用文档占目标总数的比例

Top-5 命中率（附加）
= Top-5 至少命中一个 expected.documentId 的参与统计用例数
  ÷ 参与统计用例总数

忠实度 = QA Judge 的平均 faithfulness

相关性 = QA Judge 的平均 relevance
```

Top-5 取多次检索合并去重（同一文档取最高相关度）后的最终候选。准确率与召回率互补：
单期望文档用例只要目标进入 Top-5，召回率即为 1.0（目标被找回）；准确率固定以 5 为分母，
反映返回列表中的期望文档占比（例如 5 个位置只命中 1 个时准确率为 0.2）。
窗口收窄（如 Top-3）时准确率上升但召回率可能下降，两个窗口口径均可按同一公式复算。

### 9.2 知识整理 Agent

分别计算 `DUPLICATE`、`CONFLICT`、`MISSING` 的 Precision、Recall 和 F1。

```text
处置正确率
= actionCorrect=true 的问题用例数
  ÷ DUPLICATE、CONFLICT、MISSING 用例总数

不确定内容误写率
= unsafeWrite=true 的 CONFLICT 或 MISSING 用例数
  ÷ CONFLICT 与 MISSING 用例总数
```

## 10. 最小质量检查

- [ ] 每条用例只有 `caseId`、`caseType`（QA）、`input` 和 `expected`；
- [ ] QA 和知识整理输入、预期输出保存在同一 JSON 对象；
- [ ] 8 篇草稿与 8 条知识整理用例一一对应；
- [ ] 每条知识整理用例只有一个 `selectedDraftId`；
- [ ] 知识整理用例不携带 `goal`（goal 为系统统一配置的通用提示词）；
- [ ] 每条用例都有完整参考最终回答；
- [ ] 所有文档 Long ID 都能从 manifest 反查业务键；
- [ ] QA 实际结果包含 Agent 本轮看到的检索 `content`；
- [ ] 知识整理问题识别根据最终回复评判；
- [ ] 实际工作草稿正文用于检查动作和误写；
- [ ] 测试输入不包含 `expected`；
- [ ] 数据不含真实内部材料或凭据；
- [ ] 人工复核前 `reviewedByHuman=false`。
