## 1. 框架对齐、契约与失败测试

- [x] 1.1 对照项目锁定的 Spring AI Alibaba `1.1.2.3` 本地源码/API 与官方文档，确认 Skill、Agent Spec、子 Agent、Tool、Hook、Human-in-the-loop 和 Checkpoint 的原生能力映射，删除重复实现运行时、Registry、Loader、调度器、Checkpoint 或人工介入框架的任务
- [x] 1.2 定义知识任务会话、消息、run 关联、框架状态投影、草稿修订、结构化更新、Diff 和发布修订锁定契约，并补充中文 Javadoc
- [x] 1.3 增加 Flyway 与 PostgreSQL 失败测试，验证触发幂等、会话隔离、修订递增、基础修订冲突、Tool 重试和发布冲突
- [x] 1.4 增加真实 Agent Framework/Checkpoint 集成失败测试，验证 Skill 与 Agent Spec 装配、未知 Tool 预检、系统首轮、先读后改、安全暂停、指导恢复、完成后新 run 和重启恢复
- [x] 1.5 增加前端失败测试，验证对话/事件、暂停状态、完成后输入、修订历史、Markdown Diff 和发布前冲突

## 2. T6B 框架接入与 LoreDock 业务 Tool

- [x] 2.1 实现知识任务会话与消息 Service，使手动和定时触发复用同一入口且每次执行保留独立 run
- [x] 2.2 配置 `FileSystemSkillRegistry`、`SkillsAgentHook`/`SkillsInterceptor`、`AgentSpecLoader`、`AgentSpecReactAgentFactory` 与 `TaskToolsBuilder`，直接使用 `TaskTool`/`AgentTool` 组织子 Agent，不创建自研加载器、Registry、运行循环或调度器
- [x] 2.3 通过 `ToolCallback`、`ToolCallbackProvider` 和 `ToolCallbackResolver` 注册知识读取、证据读取、冲突/缺口记录以及 `draft_create`、`draft_read`、`draft_update`、`draft_diff`；使用 `ToolContext` 传递固定项目范围，并在 run 启动前校验未知或越权 Tool
- [x] 2.4 将 `PostgresSaver`、稳定的 `RunnableConfig.threadId`、Graph interrupt 和 Human-in-the-loop 接入知识长任务，把 `PAUSE_REQUESTED → WAITING_FOR_USER → RUNNING` 作为框架执行状态的安全页面投影，不实现并行状态机
- [x] 2.5 实现草稿区块、乐观修订、Tool 幂等、来源关联、不可变修订与服务端 Diff
- [x] 2.6 将发布操作锁定到管理员已查看的明确草稿修订，保持 Agent 无正式发布权限
- [x] 2.7 从框架 Agent、Tool、Hook、interrupt 和 Checkpoint 事件投影允许展示的运行过程，禁止让模型调用 Tool 伪造状态事件

## 3. T8 知识整理接入

- [x] 3.1 编写本地 `knowledge_curator` Skill 和预定义子 Agent Spec，要求读取当前修订后按审核意义批次增量更新
- [x] 3.2 使用框架 `TaskTool`/`AgentTool` 接入系统/手动首轮、用户暂停指导、证据不足等待和完成后继续调整，不另写子 Agent 协调运行时
- [x] 3.3 将检索、冲突候选、来源、草稿修订和公开运行事件关联到同一任务会话

## 4. 前端与验证

- [x] 4.1 实现知识任务对话与产物双栏、过程折叠、暂停/恢复、完成后输入和修订列表
- [x] 4.2 实现正式/空基线到当前修订及任意修订间的 Markdown Diff 审批，处理截断和修订冲突
- [x] 4.3 同步 API、数据模型和 Pencil 增量设计，明确对话消息不等于草稿产物
- [x] 4.4 运行后端单元/契约/PostgreSQL 集成测试、前端测试与构建、OpenSpec 严格校验，并检查中文注释和敏感信息
