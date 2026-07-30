## 1. 隔离兼容性 PoC

- [x] 1.1 在 `backend/poc/agent-compatibility/` 建立不参与生产打包的最小 Maven 工程，第一候选锁定 Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.3、Java 21 及 Boot 3 版 MyBatis-Plus/Sa-Token/Flyway 测试依赖；所有 PoC 测试方法添加中文业务目的注释。
- [x] 1.2 先编写会失败的 PoC 测试，使用 Fake `ChatModel` 驱动真实 `ReactAgent` 完成单工具调用、流式消息、结构化结果、步骤/模型调用 Hook、超时与迟到响应，再补最小配置使测试通过并记录实际 API 差异。
- [x] 1.3 为 PoC 添加依赖树断言和类链接测试，覆盖实际使用的 Agent、Hook、ToolCallback、固定 Skill 注入与流式路径；保留已经验证不破坏核心路径的 fastjson 1.x 排除。
- [x] 1.4 运行依赖收敛和正式版本检查，确认不混入 Spring Boot 4.x、Spring AI 2.0.x、Boot 4 Starter、SNAPSHOT/Milestone 或非目标 Agent Starter；依赖许可证、CVE 与供应链安全审计不属于本次 MVP 范围。
- [x] 1.5 将 PoC 最终版本、排除项、依赖树摘要、Java 21/工具/流式测试结果和实际 API 差异写入 T6A 技术验证文档；兼容性门禁通过后开始修改主应用 POM。

## 2. 主应用迁移到兼容依赖基线

- [x] 2.1 修改 `backend/pom.xml`：切换 PoC 确认的 Spring Boot 3.5.x、Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.x 正式版本，替换 MyBatis-Plus/Sa-Token Boot 4 Starter、Boot 4 Flyway/WebMVC 测试用法，只加入 Agent Framework 与 OpenAI 模型所需最小依赖，并用 Maven Enforcer 禁止不兼容 Spring 版本和 fastjson 1.x。
- [x] 2.2 先运行编译与现有快速测试暴露 Boot 3 API/自动配置差异，只做保持既有契约所需的最小兼容修改；不得借迁移重写 T1～T5 业务或放宽现有断言。
- [x] 2.3 运行全部后端单元测试和 Web 契约测试，逐项修复认证、统一错误、项目/分支、文档生命周期、代码检索和知识搜索在 Boot 3 下的真实兼容问题，并保留测试中的中文业务目的与实际证据日志。
- [x] 2.4 使用真实 PostgreSQL/Testcontainers 运行 V1～V5 Flyway、持久化、索引、代码搜索、知识混合搜索和正式基准相关集成测试，确认迁移没有改动范围隔离、活动 generation、引用元数据或失败回退语义。
- [x] 2.5 在 Agent 默认关闭且无模型密钥的环境执行可执行 fat-jar 启动、liveness/readiness 和现有检索 smoke；保存主应用依赖树并确认最终运行图与 PoC 版本一致、fastjson 1.x 和两个 Spring 主版本线均不存在。

## 3. Agent 应用契约与领域规则

- [x] 3.1 接口优先定义 `StartProjectQaRunUseCase`、`AgentRunQueryUseCase`、`AgentEventQueryUseCase`、命令/快照/事件/引用 DTO，以及执行、Skill、运行、事件、工具、证据和内容存储端口；公共类型用中文 Javadoc 明确输入上限、默认 `main`、幂等、终态、错误和无恢复语义，此步不创建 Mapper 或框架适配器。
- [x] 3.2 为 `AgentRun` 状态机和固定快照编写带中文业务目的注释的失败单元测试，覆盖 `ACCEPTED → RUNNING → COMPLETED|FAILED|TERMINATED`、ANSWER/REFUSAL、终态不回退、Skill/模型/策略更新不影响当前运行、Token 缺失为未知和迟到结果被忽略。
- [x] 3.3 实现最小领域模型、值对象、稳定错误码和状态转换使 3.2 通过，并用中文注释解释终态 CAS、版本固定、迟到结果丢弃和为什么 T6A 不声明可恢复。
- [x] 3.4 为 `project_qa` 结构化结果与引用校验编写失败单元测试，覆盖 BUSINESS_RULE/CURRENT_IMPLEMENTATION/MIXED 的来源要求、空引用、伪造/跨运行/被裁剪证据、SOURCE_CONFLICT 双来源、无快照实现问题和模型伪造发布声明。
- [x] 3.5 实现最小结果校验器、模板化拒答与稳定原因码使 3.4 通过；校验失败只能降级为可信 REFUSAL 或终止，不得发布未校验回答，也不得修改任何正式知识状态。
- [x] 3.6 增加 `agent` 包依赖方向测试，证明 domain 不依赖 Spring/MyBatis/文件系统/HTTP、application 不依赖 Spring AI/Mapper/Controller，检查公共接口、实现策略和关键业务分支均有符合规范的中文注释。

## 4. V6 迁移与运行事实持久化

- [x] 4.1 先编写带中文业务目的注释的真实 PostgreSQL 失败迁移测试，覆盖 V1→V6、V5→V6、重复迁移、Skill 唯一启用、操作者+幂等键唯一、状态/结果约束、事件序号、证据/引用外键、JSON/正文大小限制和旧应用面对 future migration 的回滚兼容。
- [x] 4.2 追加 `V6__create_agent_runtime_tables.sql`，建立 `agent_skill_version`、`agent_run`、`agent_run_event`、`agent_tool_call`、`agent_evidence`、`agent_citation` 及必要索引/注释，使 4.1 通过；不得修改 V1～V5、自动建表或引入 Graph Saver DDL。
- [x] 4.3 为六张表的独立 Lombok 实体、显式 MyBatis-Plus 映射和领域/DTO 分离编写失败映射测试，覆盖每个表名、主键、列、枚举、可空 Token、UTC 时间、JSONB 和不透明 object key。
- [x] 4.4 实现 Mapper 与仓储适配器使 4.3 通过，优先使用 BaseMapper/Wrapper，只有批量或比较更新无法清楚表达时才用参数化注解 SQL，禁止 XML Mapper。
- [x] 4.5 为同一幂等键并发插入、状态比较更新、事件单调序号、证据批量写入、引用外键、终态后迟到写入和 `afterSequence` 有界分页编写真实 PostgreSQL 失败测试并实现相应短事务服务。
- [x] 4.6 捕获事务与 SQL 证据确认模型、工具和等待期间没有长事务，事件遵循“先持久化提交、后供下游读取”，失败不会留下已公开但数据库不存在的事件。

## 5. project_qa Skill 与受控配置

- [x] 5.1 创建版本化内置 `project_qa/SKILL.md` 与输出 schema，先编写结构校验失败测试覆盖名称/版本/场景、必要输入、三个只读工具、推荐步骤、最大步骤、答案依据、引用、拒答、冲突、禁止发布和公开模拟验收示例。
- [x] 5.2 为 Skill 引导发布编写失败集成测试，覆盖按内容哈希幂等写入 ObjectStorage、数据库元数据为事实来源、同版本内容冲突、每个名称单一 ENABLED、读取固定旧版本和引导失败时 Agent 不可用但现有应用仍可启动。
- [x] 5.3 实现 Skill 内容端口、ObjectStorage 适配、元数据仓储和内置引导发布器使 5.2 通过；不扫描用户目录/工作目录、不暴露真实路径、不启用文件系统自动重载或在线 Skill 编辑。
- [x] 5.4 定义并校验强类型 `loredock.agent` 配置，固定模型描述、工具策略、阈值、步骤/调用/超时/检索/片段/上下文/回答/事件/执行器上限；测试非法或试图放宽安全边界的配置启动失败，`enabled=false` 与缺少模型密钥时现有 readiness/搜索仍可用。

## 6. 运行启动、幂等、查询与中断协调

- [ ] 6.1 为启动用例编写带中文业务目的注释的失败应用测试，覆盖 ADMIN/MEMBER、匿名、问题 1～2000 Unicode 字符、项目不存在/停用、指定分支、默认 `main`、活动 snapshot/commit 与知识 generation 固定、Skill 不可用和 Agent 关闭。
- [ ] 6.2 扩展失败测试覆盖新幂等键创建、相同键相同请求返回原运行、相同键不同问题/范围冲突、问题只持久化哈希/长度、配置更新只影响新运行，并实现最小 `StartProjectQaRunService` 使其通过。
- [ ] 6.3 为事务提交后调度、专用有界执行器、队列满、执行线程不读取 Web ThreadLocal 身份和调度失败留痕编写失败测试；实现先落库后调度，使队列满运行终结为 `AGENT_RUNTIME_BUSY` 而不是丢失。
- [ ] 6.4 为运行快照和事件查询编写失败应用/持久化测试，覆盖项目访问复核、匿名/越权隐藏、afterSequence 不重复、服务端分页上限、最终引用聚合和数据库快照优先于内存事件，并实现查询用例。
- [ ] 6.5 为启动中断协调器编写失败集成测试，覆盖遗留 ACCEPTED/RUNNING 终结为 `AGENT_RUN_INTERRUPTED`、既有事件保留、重复启动幂等、不重放模型/工具和既有终态不变；实现最小协调器使其通过。

## 7. 服务端工具白名单、固定范围与证据台账

- [ ] 7.1 先定义三个项目自有工具请求/响应契约和 `ProjectQaToolGateway`，明确模型不得控制项目、分支、snapshot、commit、generation、服务器路径或上限；工具 Callback 的 Spring AI 类型只能出现在基础设施层。
- [ ] 7.2 为工具注册表编写失败测试，证明 `project_qa` 只注册 `knowledge_search`、`code_search`、`code_snippet_read`，未知工具、Shell、Python、任意 HTTP、数据库管理、知识写入/发布在执行前返回 `AGENT_TOOL_NOT_ALLOWED`。
- [ ] 7.3 为知识工具编写带中文业务目的注释的失败应用测试，覆盖复用 HYBRID 项目搜索、GLOBAL/PROJECT/BRANCH 已发布范围、默认/指定分支、Agent 结果与上下文上限、低相关阈值、generation 固定、无结果不扩大和提示注入文本只作证据。
- [ ] 7.4 为代码搜索/片段工具编写失败应用测试，覆盖活动 snapshot/commit、路径与行数上限、同名跨项目/分支零泄漏、敏感/未索引文件、无快照、模型提交历史版本/绝对路径和提示注入不扩大权限。
- [ ] 7.5 实现三个工具网关适配器使 7.3～7.4 通过，为每条有限结果分配运行内证据 ID，只持久化来源元数据与摘要，正文只在本次有界模型上下文中存在。
- [ ] 7.6 为同一运行多次工具调用期间知识 generation 或代码 snapshot 切换编写失败测试；实现版本比对，在变化时以 `AGENT_EVIDENCE_VERSION_CHANGED` 终止，不访问历史索引、不混合两版证据。
- [ ] 7.7 增加架构与回归测试，证明 Agent 正常/恶意调用只改变 V6 运行事实表，不改变知识文档、知识索引、代码快照、项目、分支、账号或系统配置。

## 8. ReactAgent、Fake Model 与 MiniMax 适配

- [ ] 8.1 在主应用先编写失败框架契约测试，用脚本化 Fake `ChatModel` 驱动真实 `ReactAgent` 依次调用三个受控工具并返回结构化结果，覆盖同步/流式、工具参数、Token 有值/未知、独立运行不共享记忆和 Spring 类型不越过 `AgentExecutionPort`。
- [ ] 8.2 实现每运行独立的 `SpringAiAlibabaAgentExecutionAdapter`、固定 Skill 注入、ToolCallback 转换和 Fake Model 支持使 8.1 通过；不注册框架文件系统 Skill、Shell/Python、A2A、Studio、Nacos 或动态 Agent 能力。
- [ ] 8.3 为步骤数、模型调用次数、检索/片段/上下文/输出/事件限制编写失败测试，覆盖客户端或模型试图提高上限、到界前成功、下一调用被拒绝和实际计数证据；实现外层计数器、Hook 与裁剪器使其通过。
- [ ] 8.4 为总超时、可取消工作、不可立即取消的迟到模型/工具响应和终态 CAS 编写失败并发测试；实现截止时间控制，确保超时后没有新工具、答案或终态回退，错误为 `AGENT_RUN_TIMEOUT`。
- [ ] 8.5 使用本地协议模拟服务先编写 MiniMax OpenAI 兼容测试，覆盖普通/流式响应、工具调用、多分块工具参数、usage 完整/缺失、429、鉴权失败、连接失败、无效 JSON、有限重试和错误脱敏，再实现生产 `ChatModel` 配置适配。
- [ ] 8.6 在具备公司测试凭据时执行不提交请求正文、密钥、端点或响应正文的 MiniMax 2.7 smoke，记录模型名摘要、工具/流式能力、Token 可用性和耗时；若环境未提供凭据，明确记录未执行原因且不得伪造通过。

## 9. project_qa 端到端可信行为

- [ ] 9.1 使用公开模拟项目、两个同名分支、三层知识和 Fake Model 编写真实 PostgreSQL 端到端失败测试，验证业务原因回答只引用允许的已发布知识，当前实现回答只引用固定分支活动代码，MIXED 同时引用两类来源。
- [ ] 9.2 扩展端到端测试覆盖默认 `main`、指定功能分支、其他分支/项目更相关内容仍零泄漏、知识/代码结果裁剪和最终知识/代码引用元数据完整，并输出与断言一致的项目、分支、commit/generation、证据/引用数量和耗时。
- [ ] 9.3 扩展端到端测试覆盖空引用、伪造证据 ID、另一运行证据、被裁剪证据和答案依据类型不匹配，确认未校验模型文本不产生 `ANSWER_DELTA`，而是带 `AGENT_CITATION_INVALID` 的 REFUSAL。
- [ ] 9.4 扩展端到端测试覆盖没有证据、低相关、超出范围、询问实现但分支无快照、知识与代码冲突，确认返回“当前知识库没有足够依据”、稳定原因码和有限当前范围来源，不使用模型常识补写。
- [ ] 9.5 为持久流式事件编写端到端测试，覆盖阶段/工具/来源实时事件、通过校验后才分块写 ANSWER_DELTA、序号拼接等于最终正文、断线按序续读和失败/终止后部分文本不被标记为可信答案。
- [ ] 9.6 覆盖模型不可用、Agent 关闭、执行器繁忙、超时、越权工具、证据版本切换和后端重启，确认运行终态/错误可追溯且现有文档浏览、知识搜索、代码搜索和活动索引均继续可用。
- [ ] 9.7 为关键运行日志和事件脱敏编写捕获测试，确认开始、范围固定、状态转换、模型/工具/证据/引用计数、Token 可用性、完成与失败均有 traceId/runId 和实际结果，同时不出现问题/答案/正文、思维链、密钥、模型端点、对象键、绝对路径或连接信息。

## 10. 文档、回归与完成门禁

- [ ] 10.1 更新环境变量示例、后端运行说明和 T6A 架构/故障排查文档，说明 Agent 开关、MiniMax secret、固定版本、运行限制、Skill 引导、事件/拒答、模型不可用、重启中断和备份；示例不得包含真实凭据、内部地址、绝对路径或业务正文。
- [ ] 10.2 运行 PoC、主应用编译、全部单元/Web 测试、真实 PostgreSQL 集成测试、V1→V6/重复迁移、应用启动/readiness、Fake Model 端到端和可执行 fat-jar smoke，保存与断言一致的实际执行证据并列出任何未执行验证。
- [ ] 10.3 运行最终 Maven 依赖树、Enforcer 收敛/不兼容版本和敏感信息检查，确认无 Spring Boot 4.x、Spring AI 2.0.x、Boot 4 Starter、SNAPSHOT/Milestone、非目标 Agent Starter 或凭据；依赖安全审计不属于本次 MVP 范围。
- [ ] 10.4 对照三份 delta spec 逐条复核正常、边界和失败场景，检查每个测试的中文业务注释、公共接口/实现/关键分支中文注释及生产/测试证据日志，运行 `openspec validate add-single-agent-project-qa-runtime --strict`。
- [ ] 10.5 只有全部实现与验证门禁满足、主规格同步且 change 准备归档时，才勾选实际完成任务并把 `docs/product/LoreDock_MVP功能开发计划.md` 的 T6A 更新为 `[x]`；不得提前标记 T6B 或 T7。
