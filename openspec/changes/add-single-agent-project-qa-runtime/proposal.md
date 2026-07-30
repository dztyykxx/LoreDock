## Why

T1～T5 已提供认证、项目/分支范围、正式知识混合检索和活动代码快照检索，但当前 Spring Boot 4.1.0、Spring AI 2.0.0 基线与计划采用的 Spring AI Alibaba 1.1.2.x Agent Framework 不兼容，也尚无能够安全调用这些检索能力的统一 Agent 运行时。依据 `docs/product/LoreDock_MVP功能开发计划.md` 的 T6A 和需求基线 FR-AGENT-01～07，需要先完成兼容性迁移并交付可追溯、受限的 `project_qa` 单 Agent，才能在 T7 接入可信 Web 问答。

## What Changes

- **BREAKING**：将后端依赖基线从 Spring Boot 4.1.0、Spring AI 2.0.0 迁移到经隔离 PoC 验证的 Spring Boot 3.5.x、Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.x 正式版本，替换 Boot 4 专用 Starter，并以依赖树门禁禁止两个 Spring 主版本线混用。
- 新增统一 Agent 运行应用契约和持久化运行记录，固定 Skill/模型/运行限制，记录工具调用摘要、输入来源、引用、Token、耗时、阶段事件、终态和脱敏失败语义。
- 加载并版本化内置 `project_qa` Skill；Skill Markdown 描述任务、工具、引用与拒答要求，服务端结构化配置独立强制工具白名单、范围和运行上限。
- 新增基于 Spring AI Alibaba `ReactAgent` 的单 Agent 适配器、MiniMax 2.7 `ChatModel` 适配器和可编程 Fake Model，复用既有知识搜索、代码搜索与代码片段读取应用端口。
- 在每次工具执行前由服务端固定并校验项目、分支、活动 commit、允许的知识范围和参数上限；不信任模型输出、Skill 内容或检索材料中的指令。
- 对步骤数、模型调用次数、超时、检索条数和上下文长度实施硬限制，超限或越权时安全终止并保留可核验记录。
- 输出 T7 可消费的运行快照与流式阶段事件，但本变更不提供问答页面、浏览器 SSE Controller、对话管理或知识缺口反馈接口。
- Agent 仅生成回答或拒答，不提供正式知识修改、发布、Shell、任意网络访问或数据库管理工具。
- 不包含 T6B 的多 Agent Graph、PostgreSQL 检查点恢复、人在回路、项目记忆和完整 Skill 管理，也不实现 T8～T10 的草稿/报告写入流程。

## Capabilities

### New Capabilities

- `agent-runtime`: 定义统一 Agent 运行、版本化 Skill、模型适配、受控工具执行、运行限制、追踪记录和阶段事件的服务端契约。
- `project-qa-agent`: 定义 `project_qa` 在固定项目与分支内检索知识和代码、形成引用、处理冲突并在证据不足时拒答的单 Agent 行为。

### Modified Capabilities

- `application-foundation`: 将可复现后端依赖基线收敛到兼容 Spring AI Alibaba Agent Framework 的 Spring Boot 3.5.x、Spring AI 1.1.2 正式版本线，并要求迁移后完整回归既有能力。

## Impact

- 后端构建：`backend/pom.xml` 的 Spring Boot Parent、Spring AI/Spring AI Alibaba BOM、MyBatis-Plus 与 Sa-Token Starter、测试依赖和依赖收敛规则。
- 后端代码：新增按能力分包的 `agent` 领域、应用与基础设施层；复用 `KnowledgeSearchUseCase`、`CodeSearchUseCase`、`CodeSnippetReadUseCase` 及项目/分支解析能力，不把 Spring AI 类型泄漏到应用契约。
- 数据库：通过新 Flyway 迁移增加 Skill 版本、Agent 运行、阶段事件、工具调用摘要、输入来源与引用等元数据表；MyBatis-Plus 只负责显式映射。
- 配置与运维：增加 MiniMax 兼容端点、模型名、密钥引用和运行限制配置；日志、API/事件和数据库均不得保存密钥、完整提示词、完整知识/代码正文或模型原始思维链。
- 验证：隔离 PoC、Maven 依赖收敛与不兼容版本检查、T1～T5 单元与真实 PostgreSQL 集成回归、应用启动检查，以及 Fake Model 驱动的范围、引用、拒答、越权和超限场景。依赖许可证、CVE 与完整供应链安全审计不属于本次内网答辩 MVP 范围。
