# Proposal: 第一阶段收口与模块契约

## 问题

后端已完成 `simplify-backend-mvc-runtime` 的 MVC 化改造，但模块间协作仍直接引用对方 `service` 实现（如 Agent 同时依赖 `ActiveCodeSnapshotQueryService`、`CodeSearchService`、`CodeSnippetService` 三个代码模块内部 Service；QA 直接依赖 Agent 的 `StartProjectQaRunService`、`AgentRunQueryService`、`AgentEventService`）。跨模块调用没有稳定的 `api` 契约包，任何内部实现调整都会传导到调用方，且模块边界只停留在文档中，缺少可验证约束。

同时仍存在与当前表结构不同步的 PostgreSQL 集成测试（引用已删除表 `knowledge_document_tag`、`knowledge_index_document`、`knowledge_search_generation`），以及使用固定 ID 夹具与自增 BIGINT 主键冲突的测试。

## 目标与第一阶段价值

- 固定“业务模块内简单 MVC + 按需 `api` 契约包”为唯一一致规则，并写入主规格；
- 为被跨模块调用的模块建立最小 `api` 契约包（`project`、`knowledge`、`code`、`agent`、`qa`、`job`、`storage`、`auth`），跨模块代码只引用对方 `api`；
- 按模块把存量跨模块调用迁移到契约，合并只做单次转发的小类，删除无调用方结构；
- 修正或删除仍依赖旧表的 PostgreSQL 集成测试，使集成测试与唯一 Flyway V1 基线一致；
- 保持已成型 HTTP 契约与前端行为不变，核心链路（项目 → 知识/代码 → 问答 → 引用/拒答）真实可运行。

## 保留项

- 所有已成型 HTTP 路径、请求/响应字段、错误语义和 SSE 契约（见 `当前行为基线与测试记录.md`）；
- `AgentRuntime`、`ObjectStorage`、`AgentDefinitionProvider` 等真实替换边界；
- 现有 17 张业务表 + 2 张 Graph checkpoint 表的单一 V1 基线；
- 前端现有页面与 API 层。

## 删除项

- 模块间直接引用对方 `service`、`mapper`、`model/entity`、内部 DTO 与过程模型的调用；
- 只为单次转发创建的 Command/Result/Snapshot/Converter 小类；
- 仍引用已删除表的集成测试与固定 ID 夹具缺陷；
- 无入口的半成品、死代码和过期测试。

## 非目标

- 多 Agent 知识挖掘流程与动态编排平台；
- MCP 扩展、身份校验和模型摘要；
- 知识、代码或 Agent 定义的历史版本系统；
- 生产级审计、多级恢复、完整可观测平台和穷举容错；
- 新的 HTTP 功能、前端页面或已成型 JSON 字段风格迁移；
- 按微服务、DDD、六边形或多 Maven 子模块重建项目；
- 为了覆盖率或测试数量给无业务逻辑小类补测试；
- 不引入个人 notes 目录作为仓库事实来源（相关规则已提炼进 AGENTS.md 与架构文档）。
