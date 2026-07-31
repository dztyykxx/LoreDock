## 1. 建立行为基线与重构清单

- [x] 1.1 固定登录、项目/分支、知识导入与发布、知识检索、代码快照与检索、项目问答、引用/拒答和知识缺口反馈的现有 HTTP 契约，并补充最小冒烟脚本输出真实结果。
- [x] 1.2 复核 `return-qa-terminal-outcomes` 剩余验证，把已确认的问答终态和 SSE 行为作为本 change 基线，并在其工件中标明后续结构重构由本 change 接管，避免继续固化旧分层。
- [x] 1.3 生成现有接口处置清单，为每个非 Mapper 接口标记“保留的真实替换边界”或“待删除的单实现转发”，至少明确 Agent Runtime、Agent 定义、ChatModel、EmbeddingModel 和对象存储边界。
- [x] 1.4 生成现有数据库表处置清单，为每张表记录独立生命周期、完整性或查询价值，并明确保留、合并、字段化或删除决定。
- [x] 1.5 记录重构前后端编译、核心测试、空库迁移和 Agent/QA 代表性测试结果，所有新增或调整的测试添加中文业务目的和回归说明。

## 2. 建立 MVC 架构约束

- [x] 2.1 编写会因现有职责错放而失败的架构测试，检查 Controller 只放 HTTP 入口、Service 只放业务服务、Mapper 只放 MyBatis Mapper、数据载体进入对应 Model 子包，并继续检查 Controller 不访问 Mapper、Service 不访问其他模块 Mapper和模块依赖无环。
- [x] 2.2 定义最小公共代码边界，将统一配置、Web 错误、时间和 MyBatis 支持归入明确公共包，并为“业务类不得进入 common/platform/util”增加失败检查。
- [x] 2.3 建立接口边界检查或审查清单，禁止新增只做转发的 UseCase、Port、Gateway、Repository Adapter，并允许标准模型接口、Agent Runtime、Agent 定义、对象存储和 MyBatis Mapper。
- [x] 2.4 为严格 MVC 架构测试和公共契约补充中文注释，运行定向测试确认失败原因来自职责错放而不是测试实现错误。

## 3. 按功能模块收敛基础能力

- [x] 3.1 将认证与会话代码按严格 MVC 职责归位，合并单实现 UseCase 和转发类，运行登录、会话恢复、管理员授权测试并检查中文注释。
- [x] 3.2 将项目与分支代码按严格 MVC 职责归位，由 Service 直接使用 Mapper，运行创建、启停、默认分支和范围错误测试并检查中文注释。
- [x] 3.3 将对象存储代码按严格 MVC 职责归位，保留真实存储替换接口，运行原子写入、读取不存在对象和路径穿越测试并检查中文注释。
- [x] 3.4 将后台任务代码按严格 MVC 职责归位，删除调度转发接口，运行提交、容量拒绝、状态转换和陈旧任务恢复测试并检查中文注释。
- [x] 3.5 将知识缺口反馈按严格 MVC 职责归位，运行幂等创建、范围不匹配、权限和状态转换测试并检查中文注释。

## 4. 收敛知识与代码模块

- [x] 4.1 将知识文档生命周期迁移到 `knowledge/controller|service|mapper|model`，合并 Repository Adapter、Converter 和细粒度异常，运行范围、发布、归档、替代和权限测试并检查中文注释。
- [x] 4.2 将知识导入迁移到同一 Knowledge MVC 模块，保持 ZIP 与 UTF-8 安全边界，运行单文件、部分成功、路径穿越、资源上限和原子草稿测试并检查中文注释。
- [x] 4.3 将知识索引和混合检索迁移到 Knowledge Service/Mapper，保持项目/分支/发布状态强约束和活动 generation 原子切换，运行关键词、语义、混合、无命中和跨范围测试并检查中文注释。
- [x] 4.4 消除知识重建中的重复文档投影和标签 N+1，改为分批读取、分批生成向量和分批写入，并用真实 PostgreSQL 集成测试记录处理数量、generation 和最终命中范围。
- [x] 4.5 将代码快照导入、Lucene 索引、搜索和片段读取迁移到 `code/controller|service|mapper|model`，保留上传安全与活动快照切换，运行安全导入、失败保留旧索引、范围搜索和片段读取测试并检查中文注释。

## 5. 删除无必要版本管理并统一模型接口

- [x] 5.1 先编写 classpath Agent 定义加载测试，覆盖定义存在、结构无效和任务类型不存在的稳定失败行为，并输出实际 Agent 名称和加载结果。
- [x] 5.2 实现最小 `AgentDefinitionProvider` 与 classpath 实现，迁移内置 `project_qa` 定义，删除数据库 Skill 目录、对象存储 bundle、发布器、启用状态和配套单实现接口，并运行加载测试。
- [x] 5.3 删除运行记录中的 Skill 版本 ID、内容哈希、工具策略版本和限制策略版本，只保留 Agent 名称、模型名称、必要配置摘要及业务范围事实，并更新幂等启动测试。
- [x] 5.4 先为 Spring AI `ChatModel` 装配编写无外部网络的模型替身测试，覆盖正常、流式、临时不可用和无配置启动，再将 Agent 模型边界收敛到标准 `ChatModel` 并删除重复供应商工厂抽象。
- [x] 5.5 先为 Spring AI `EmbeddingModel` 编写文档批量、查询指令、维度错误和资源不可用测试，再使 ONNX 实现接入标准接口，将知识文本组合与索引模型摘要留在 `KnowledgeEmbeddingService` 并删除平行模型端口。
- [x] 5.6 运行项目问答与知识混合检索代表性测试，证明更换模型实现不需要修改 Controller 和核心业务 Service，并检查模型配置、失败分支和公共接口的中文注释。

## 6. 重建数据库基线和自增 Long ID

- [x] 6.1 先编写真实 PostgreSQL 失败测试，要求所有 Flyway 管理表使用 identity `BIGINT` 主键、关联列使用 `BIGINT`、Entity 使用 `Long`/`IdType.AUTO`，并验证运行时组件不能自动建表。
- [x] 6.2 根据表处置清单建立新的单一 Flyway 基线，明确删除旧可丢弃开发数据库的要求，不提供 UUID 双写、兼容视图或在线转换。
- [x] 6.3 将项目、分支、对象存储、后台任务、知识文档、导入、索引、代码快照和反馈 Entity/Mapper/Service/DTO 主外键一次性改为 `Long`，运行对应持久化与 API 契约测试。
- [x] 6.4 将文档标签合并为文档字段、将仅供批次展示的导入项合并为结果 JSON，并运行编辑、筛选、导入历史和部分成功集成测试证明行为不丢失。
- [x] 6.5 删除可由正式文档直接重建的知识索引文档投影，并在确认两个 generation 表表达同一原子切换后合并，运行重建失败保留旧 generation 和并发切换测试。
- [x] 6.6 将 Agent 持久化收敛为自增主键的 `agent_run`、`agent_run_event` 和 `agent_evidence`，在 evidence 中保存引用标志与顺序，删除 `agent_tool_call`、`agent_citation` 和 `agent_skill_version`。
- [x] 6.7 同步后端 HTTP DTO、前端 TypeScript 类型、路由参数、API 客户端、演示数据和测试夹具中的 ID 类型，运行前后端类型检查和主要页面交互测试。
- [x] 6.8 在全新 PostgreSQL 中执行完整 Flyway 初始化和重复启动，记录迁移版本、实际表主键类型、外键完整性和应用就绪结果，并检查所有 Entity 显式字段映射。

## 7. 精简并保留 Agent Runtime

- [x] 7.1 先基于锁定依赖编写 Spring AI Alibaba 兼容性测试，验证 ReactAgent 结构化结果、ToolCallback、流式输出、模型/工具调用限制 Hook、Flow Agent 组合和 PostgresSaver `CREATE_NONE` 行为。
- [x] 7.2 在 `agent/service` 定义稳定 `AgentRuntime` 请求、结果和流式事件契约，测试替身不依赖具体 Agent 框架类型，并为公共契约添加中文 Javadoc。
- [x] 7.3 实现 `SpringAiAlibabaAgentRuntime`，直接委托 ReactAgent/Flow Agent、Hook、Interceptor 和标准 ChatModel，删除手工 ReAct、重复计数、重复重试和可由框架提供的流式消息拼装。
- [x] 7.4 将知识搜索、代码搜索和代码片段读取注册为明确的 ToolCallback，删除单实现 Tool Registry/Gateway，同时保留每次工具调用的项目、分支、快照、generation、参数和结果上限校验测试。
- [x] 7.5 将证据裁剪、证据来源、模型结构化结果、引用白名单和证据不足拒答集中到 Agent Service，运行伪造引用、低相关来源、跨范围工具和无快照拒答测试并检查关键规则中文注释。
- [x] 7.6 将 Agent 接受、运行和终态事务收敛到 `AgentRunService`，模型/工具等待不持有事务；运行幂等、状态单调、迟到结果和失败保持测试并输出实际状态证据。
- [x] 7.7 将持久化事件缩减为接受、开始、必要进度、来源发现、完成、失败和终止，删除逐工具和逐分片数据库事件依赖，运行事件序号、续读和敏感内容不落库测试。
- [x] 7.8 由 Flyway 创建带 identity 主键和唯一 UUID 协议列的 Graph Checkpoint 表，使用项目 DataSource 与 `CREATE_NONE` 接入 PostgresSaver，并运行可恢复长任务不重复副作用、不可恢复短任务单调终结的集成测试。
- [x] 7.9 以 Agent 为严格 MVC 样板：Service 直连 Mapper，Mapper 只保留 MyBatis 接口，数据载体归入对应 Model 子包，配置/异常/调度/Skill 各归其位，并删除残留 Repository 类和无价值接口后运行架构检查、编译和代表性测试。

## 8. 收敛 QA 并恢复完整核心链路

- [x] 8.1 将 Web 问答按严格 MVC 职责归位，保持问题、用户可见回答和 `agent_run` 的清晰关联，不把完整问题或答案复制到运行追踪字段。
- [x] 8.2 用 Agent Runtime 流式事件和粗粒度持久化事件实现 SSE 首次连接、断线续读、刷新终态和会话失效，删除固定 500ms 轮询旧内部事件所需的中间服务，并运行对应契约测试。
- [x] 8.3 运行真实项目问答核心路径，验证知识工具、代码工具、引用、拒答、模型不可用和失败终态；stdout 只输出原始问题、工具结果数量/有限预览和模型最终原始响应。
- [x] 8.4 运行知识缺口从拒答创建、关联范围校验和管理状态流转测试，证明 QA/Agent ID 与表结构变化没有破坏反馈链路。

## 9. 删除残余结构并完成验收

- [x] 9.1 删除所有残留 `application/domain/infrastructure/usecase/port/gateway/repository/adapter` 业务包、孤立类、无入口配置和被新结构替代的测试，确认没有未使用 Spring Bean 或循环依赖。
- [x] 9.2 使 MVC 架构测试全部通过，并输出最终模块、违规包、跨模块 Mapper 访问、循环依赖和保留接口清单的实际检查结果。
- [x] 9.3 执行后端单元测试、真实 PostgreSQL 集成测试、前端测试与构建，记录失败、跳过项和实际验证证据，不通过放宽断言获得通过。
- [x] 9.4 在全新环境执行登录、项目/分支、知识导入/发布/索引/检索、代码快照/检索、项目问答/引用/拒答、知识缺口反馈的完整冒烟，并确认无模型配置时非 Agent 核心能力可启动。
- [x] 9.5 更新 MVP 功能开发计划、后端架构说明、数据库表说明、Agent Runtime 边界、本地数据库备份/重建步骤和模型替换说明，勾选本 change 已完成任务并检查文档与实现一致。
