## Why

LoreDock 当前只有产品与技术文档，尚无可运行工程，后续认证、知识导入、检索、Agent 与 MCP 功能缺少共同的运行、持久化和异步任务基础。依据 `docs/product/LoreDock_MVP功能开发计划.md` 的 T1 以及需求基线第 10、13 节，需要先建立可重复启动、可迁移、可测试且具备明确失败语义的工程骨架。

## What Changes

- 建立 Java 21、Spring Boot 后端与 Vue 3、TypeScript、Vite 前端工程，并提供宿主机本地运行的统一开发启动和健康检查路径。
- 建立 PostgreSQL、pgvector、Flyway 与仅承载数据库/中间件依赖的 Docker Compose 基础设施；迁移可在空库和已迁移数据库上重复执行。
- 建立 MyBatis-Plus 持久化基线和显式字段映射约束，并使用 Lombok 减少持久化实体中的机械访问器代码；Flyway 仍是唯一数据库结构变更入口。
- 冻结 Spring Boot、Spring AI、pgvector、Lucene 及主要构建工具的实际版本，禁止使用动态版本或预发布版本。
- 定义并实现本地持久化对象存储端口，保存对象键和校验元数据，为后续 S3 适配保留稳定边界。
- 定义统一 API 错误响应、参数校验、UTC 持久化/ISO 8601 传输、本地可读且生产结构化的日志及通用审计字段约定。
- 建立 PostgreSQL 后台任务记录、受控线程池和明确的任务状态机，使任务成功或失败均可追踪。
- 配置后端单元测试、PostgreSQL 集成测试和前端测试环境，并提供最小基础设施验证。
- 本变更不实现认证、项目/分支、知识文档、代码快照、检索、Agent、MCP 或完整业务页面。

## Capabilities

### New Capabilities

- `application-foundation`: 可统一启动、健康检查和重复迁移的后端、前端、数据库工程基础，以及明确冻结的技术版本和测试运行入口。
- `platform-api-conventions`: 所有后续 HTTP API 共用的错误、校验、时间、日志和审计约定。
- `object-storage`: 面向业务的持久化对象存储契约及安全的本地文件实现。
- `background-jobs`: 可持久化、受控执行并保留成功或失败结果的后台任务生命周期。

### Modified Capabilities

无。仓库当前没有主规格，本变更只新增能力。

## Impact

- 新增后端、前端、数据库迁移、依赖服务容器编排、测试和本地开发配置；T1 不要求将本地开发的前后端进程容器化。
- 新增平台级 API 模型、对象存储端口、后台任务契约及其基础实现。
- 引入 Java 21、Node.js、PostgreSQL/pgvector、Flyway、Spring Boot、Spring AI、Lucene、MyBatis-Plus 与 Lombok 的版本约束；AI、向量检索和 Lucene 仅冻结依赖版本，不在本变更接入业务流程。
- 后续 T2～T13 将依赖本变更形成的启动方式、数据库迁移、错误语义、存储端口、审计约定和后台任务能力。
