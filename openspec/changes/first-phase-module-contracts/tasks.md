# Tasks: 第一阶段收口与模块契约

> 每个模块单元独立可审查：确认保留行为与非目标 → 定义/确认最小 `api` 契约 → 编写失败测试（带中文业务目的注释）→ 使现有 Service 实现契约并迁移跨模块调用方 → 合并转发小类 → 删除无调用方代码 → 运行模块契约、受影响 HTTP、PostgreSQL 集成与架构测试 → 人工审查 diff 后进入下一模块。

## 1. project 模块契约

- [x] 1.1 定义 `project.api.ProjectService`：统一解析启用项目/分支与稳定项目范围（默认 `main`、停用/不存在返回稳定错误），返回最小不可变契约类型。
- [x] 1.2 为 `ProjectService` 编写失败测试（中文注释说明业务目的）：有效项目/分支解析、停用项目与未知分支错误、默认 main 分支。
- [x] 1.3 使 `ProjectApplicationService` 实现契约，迁移 agent/code/knowledge/qa/feedback 对项目内部 Service 的直接引用到 `project.api`。
- [x] 1.4 合并项目模块只做单次转发的小类，删除不再有调用方的内部入口；运行项目契约、受影响 HTTP 与架构测试。

## 2. knowledge 模块契约

- [x] 2.1 定义 `knowledge.api.KnowledgeSearchService`：只返回指定范围内已发布知识及来源，隐藏 generation、Mapper 与索引内部数据服务。
- [x] 2.2 为范围检索契约编写失败测试：项目/分支/发布状态强约束、无索引/无命中/嵌入不可用可区分、`CODE_SNAPSHOT_NOT_INDEXED` 警告保留。
- [x] 2.3 使现有检索 Service 实现契约，迁移 agent 对 `KnowledgeSearchService`/`KnowledgeSearchIndexDataService` 的直接引用。
- [x] 2.4 合并 knowledge 模块 Command/Result/DataService 转发小类，删除无调用方代码；运行契约、HTTP 与架构测试。

## 3. code 模块契约

- [x] 3.1 定义 `code.api.CodeQueryService`：统一活动快照解析、范围代码搜索与片段读取。
- [x] 3.2 为契约编写失败测试：活动快照唯一性、范围搜索过滤、片段路径/行边界、未索引语义。
- [x] 3.3 使 `ActiveCodeSnapshotQueryService`/`CodeSearchService`/`CodeSnippetService` 实现契约，迁移 agent 与 knowledge 的直接引用。
- [x] 3.4 合并 code 模块内部转发小类，删除无调用方代码；运行契约、HTTP 与架构测试。

## 4. agent 模块契约

- [x] 4.1 定义 `agent.api.AgentService`：向 QA 提供运行启动、终态与公开事件；内部继续使用 `AgentRuntime`。
- [x] 4.2 为契约编写失败测试：只通过已确认模块 `api` 取得证据、回答/拒答/运行失败可区分、终态事件可查询。
- [x] 4.3 使 `StartProjectQaRunService`/`AgentRunQueryService`/`AgentEventService` 实现契约，迁移 qa 的直接引用。
- [x] 4.4 删除 agent 模块多余调度、定义、快照与过程模型，保留 `AgentRuntime` 与 `AgentDefinitionProvider` 真实边界；运行契约、HTTP 与架构测试。

## 5. qa 模块契约

- [x] 5.1 定义 `qa.api.QaService`：问答创建、历史、详情与必要关联事实（供 Feedback 使用），保持已成型 HTTP 契约。
- [x] 5.2 为契约编写失败测试：创建/历史/详情/终态对外行为不变，`failureMessage` 与引用语义保留。
- [x] 5.3 使 `CreateWebQaQuestionService`/`QueryWebQaQuestionService` 实现契约，迁移 feedback 的直接引用。
- [x] 5.4 使用 `AgentService` 收口问答编排，保持 SSE 与详情 HTTP 契约兼容；运行契约、SSE 持久化与架构测试。

## 6. job / storage / auth 契约

- [x] 6.1 定义 `job.api.JobService`（提交/查询/取消）并迁移 code/knowledge 对 `PersistentBackgroundJobService` 的直接引用；`JobHandler`/`JobExecutionContext` 收敛为模块内部类型或任务注册方式。
- [x] 6.2 将 `ObjectStorage` 收口为 `storage.api` 契约（真实替换边界），迁移 code/knowledge 直接引用。
- [x] 6.3 定义 `auth.api` 最小会话/操作者契约，迁移 qa/feedback 对 `SessionService` 的直接引用；不创建空 api。
- [x] 6.4 运行受影响 HTTP、PostgreSQL 与架构测试，确认无循环依赖。

## 7. 数据库与测试精简

- [x] 7.1 修正 `KnowledgeDocumentLifecycleServiceIT`、`KnowledgeDocumentDataServiceIT`、`KnowledgeSearchWebE2EIT`、`KnowledgeSearchReadRepositoryIT` 对已删除表（`knowledge_document_tag`、`knowledge_index_document`、`knowledge_search_generation`）的引用，使清库与断言与唯一 Flyway V1 一致。
- [x] 7.2 修正 `PersistentBackgroundJobServiceIT`、`CodeSnapshotPersistenceIT`、`CodeSnapshotUploadServiceIT`、`BranchScopedBackgroundJobServiceIT` 的固定 ID/对象键夹具冲突，保证进程内/跨运行可重复。
- [x] 7.3 建立确定性核心 E2E：真实 PostgreSQL + 可控 Fake `ChatModel`，走通项目、知识、索引、问答、引用与拒答。
- [x] 7.4 更新 `BackendMvcArchitectureTest`：跨模块只允许引用对方 `api`，禁止引用对方 `service`/`mapper`/`model/entity`/内部过程模型；存量违规输出明确迁移路径。
- [ ] 7.5 建立可手工触发的真实模型 Smoke（承接 `return-qa-terminal-outcomes` 4.4）：验证 `COMPLETED/REFUSAL`、`INSUFFICIENT_EVIDENCE`、步骤数、页面文案，以及模型故障显示失败而非拒答。

## 8. 验收与归档

- [ ] 8.1 全量后端单元测试、代表性 PostgreSQL 集成测试与前端测试通过；唯一 Flyway 基线可空库启动。
- [ ] 8.2 人工审查所有模块 diff：无规格外新功能、无未来扩展点、无新增低价值类与表。
- [ ] 8.3 同步 `backend-module-architecture` 增量到主规格，更新必要文档，严格校验并归档本 Change。
