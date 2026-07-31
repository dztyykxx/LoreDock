# Design: 第一阶段收口与模块契约

## 1. 模块卡片与数据归属

以 `docs/architecture/模块卡片与数据归属.md` 为人工确认基线，摘要如下：

| 模块 | 一句话职责 | 拥有的表 | 是否创建 api |
| --- | --- | --- | --- |
| project | 项目/分支主数据与稳定范围解析 | `project_space`、`project_branch` | 是 `ProjectService` |
| knowledge | 知识文档生命周期、索引与有范围混合检索 | `knowledge_document`、`knowledge_import_batch`、`knowledge_index_generation`、`knowledge_search_chunk` | 是 `KnowledgeSearchService` |
| code | 代码快照管理、活动快照、范围搜索与片段读取 | `code_snapshot`、`code_index_generation` | 是 `CodeQueryService` |
| agent | 运行 Spring AI Alibaba Agent，向 QA 提供启动/终态/事件 | `agent_run`、`agent_run_event`、`agent_evidence`（`graphthread`/`graphcheckpoint` 为框架协议表） | 是 `AgentService` |
| qa | 编排 Web 项目问答，保持 HTTP 契约 | `web_qa_question`、`web_qa_message` | 是 `QaService` |
| job | 通用后台任务提交/查询/取消/启动恢复 | `background_job` | 是 `JobService` |
| feedback | 知识缺口反馈记录与单向状态流转 | `knowledge_gap_feedback`、`knowledge_gap_feedback_citation` | 否（当前无调用方） |
| auth | 固定账户认证、会话与操作者身份 | 无表（配置账户） | 是（`SessionService`/操作者身份，被 qa/feedback 调用） |
| storage | 本地对象存储真实替换边界 | `stored_object` | 是 `ObjectStorage` |

## 2. 目标依赖方向

```text
QaService → AgentService / ProjectService / AuthSession
AgentService → ProjectService / KnowledgeSearchService / CodeQueryService
KnowledgeSearchService → ProjectService / CodeQueryService(快照警告) / JobService / ObjectStorage
CodeQueryService → ProjectService / JobService / ObjectStorage
FeedbackService → QaService / ProjectService / AuthSession
```

依赖图单向无环（2026-08-01 实际 import 已核对）。

## 3. api 契约包规则

- `api` 只保存 Service 接口、最小不可变输入/输出类型（优先 `record`）与少量稳定枚举；
- 接口命名 `XxxService`，实现 `XxxServiceImpl` 位于模块内 `service`（模块内部单一调用且无边界价值的 Service 直接用具体类）；
- HTTP DTO 只有语义完全相同时才复用 `api` 类型，含展示/分页/JSON 兼容字段的仍放 `model/dto`；
- 不为单次转换创建 Command/Result/Snapshot；内部临时数据使用私有 `record`；
- 每个 `api` 类型必须有稳定性价值说明，删除仅为迁移创建的临时契约类。

## 4. 存量跨模块调用迁移顺序（按模块渐进）

| 顺序 | 模块 | 建立契约 | 迁移的存量调用 |
| --- | --- | --- | --- |
| 1 | project | `ProjectService`（范围解析） | agent/code/knowledge/qa/feedback 对 `ProjectApplicationService` 的直接引用 |
| 2 | knowledge | `KnowledgeSearchService` | agent 对 `KnowledgeSearchService`/`KnowledgeSearchIndexDataService` 的直接引用 |
| 3 | code | `CodeQueryService` | agent 对 `ActiveCodeSnapshotQueryService`/`CodeSearchService`/`CodeSnippetService` 的直接引用；knowledge 对 `ActiveCodeSnapshotQueryService` 的引用 |
| 4 | agent | `AgentService` | qa 对 `StartProjectQaRunService`/`AgentRunQueryService`/`AgentEventService` 的直接引用 |
| 5 | qa | `QaService` | feedback 对 `QueryWebQaQuestionService` 的直接引用 |
| 6 | job/storage/auth | `JobService`/`ObjectStorage`/`SessionService` | code/knowledge 对 `PersistentBackgroundJobService`/`ObjectStorage` 的引用；qa/feedback 对 `SessionService` 的引用 |
| 7 | 数据库与测试精简 | — | 修正引用已删除表的 IT 与固定 ID 夹具 |

每个模块完成固定循环：确认保留行为与非目标 → 定义最小 api 契约 → 写失败测试 → 实现契约并迁移调用方 → 合并转发小类 → 删除无调用方代码 → 运行契约/HTTP/PostgreSQL/架构测试。

## 5. 事务边界

- 事务保持在模块 Service 公开方法上；跨模块契约调用不要求被调方代理事务；
- Agent 运行受理/完成/失败在短事务内提交，模型与工具执行不占用数据库事务；
- 涉及对象存储与数据库的一致性继续使用“发布顺序 + 补偿”策略，不引入分布式事务框架；
- 后台任务提交先持久化再执行，容量拒绝终结为 `FAILED/CAPACITY_EXCEEDED`。

## 6. 架构测试门禁

- 模块内允许 `Controller → Service → Mapper`；
- 跨模块只允许引用对方 `api`；
- 禁止跨模块引用对方 `service`、`mapper`、`model/entity`、内部 DTO 和过程模型；
- 禁止模块循环依赖；
- 禁止新增 DDD、Repository、Port、Adapter、Facade、Coordinator 等未批准结构；
- 存量违规给出明确迁移顺序，不通过扩大白名单合法化旧结构。

## 7. 测试策略

- 为必须保留的 HTTP 行为建立最小特征测试（已有 8 套契约测试继续复用）；
- 模块契约测试：`ProjectService` 范围解析、`KnowledgeSearchService` 范围检索、`CodeQueryService` 活动快照/搜索/片段、`AgentService` 回答/拒答/失败区分、`QaService` 创建/历史/详情/终态；
- 确定性核心 E2E：真实 PostgreSQL + 可控 Fake `ChatModel`；
- 可手工触发的真实模型 Smoke（含任务 4.4 转移的验收）；
- 修正/删除仍依赖旧表的 PostgreSQL 集成测试，保证与唯一 Flyway V1 一致。
