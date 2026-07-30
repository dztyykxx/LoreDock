## Context

参见 `proposal.md` 的动机。当前后端是单模块 Java 21 应用，使用 Spring Boot 4.1.0、Spring AI 2.0.0、MyBatis-Plus Boot 4 Starter、Sa-Token Boot 4 Starter、Flyway、PostgreSQL、Lucene 和项目自有 ONNX Embedding 适配器。T4、T5 已通过不泄漏框架类型的应用端口提供活动代码搜索、有限片段读取和知识混合搜索；每次调用固定单一 snapshot/generation，但多个调用之间可能发生活动版本切换。

T6A 是跨构建、运行时、持久化、安全和检索范围的改动。Spring AI Alibaba 的正式 1.1.2.x 版本线要求 Spring AI 1.1.2 与 Spring Boot 3.5.x；截至 2026-07-30，Maven Central 已提供 1.1.2.3，Spring Boot 3.5.x 最新正式补丁为 3.5.16。实际版本仍须由隔离 PoC 而不是文档推断锁定。

依赖调研还发现 `spring-ai-alibaba-agent-framework:1.1.2.3` 直接传递 `com.alibaba:fastjson:1.2.83`。2026-07-23 的 [CVE-2026-16723](https://nvd.nist.gov/vuln/detail/CVE-2026-16723) 与 [Alibaba 安全公告](https://github.com/alibaba/fastjson2/wiki/Security-Advisory%3A-Remote-Code-Execution-in-fastjson-1.2.68%E2%80%931.2.83) 将该版本列为 Spring Boot 可执行 fat-jar 场景下的严重 RCE 影响版本，因此默认依赖树不可直接进入 LoreDock。

T6A 需要持久化可公开的运行事实和事件，但不提供 Graph 检查点恢复。模型调用和工具循环属于长耗时外部工作，不能持有数据库事务；进程退出后也不能伪装成可恢复运行。T7 才增加 Web/SSE 与对话接口，T6B 才增加多 Agent、检查点恢复、人在回路、项目记忆和 Skill 管理。

## Goals / Non-Goals

**Goals:**

- 先用可复现的隔离 PoC 选择安全、正式且兼容的 Spring Boot 3.5.x、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.x 组合，再迁移主应用并证明 T1～T5 行为未回归。
- 建立不向应用层泄漏 Spring AI/Spring AI Alibaba 类型的单 Agent 应用契约、状态机、执行端口和查询端口。
- 让 `project_qa` 复用 T4/T5 端口，同时在 Agent 层再次固定项目、分支、活动版本、工具白名单和资源上限。
- 先持久化、后公开运行状态与事件；最终回答只在结构、引用和范围校验通过后成为可信结果。
- 模型不可用或 Agent 被禁用时，应用仍能启动且现有浏览和检索保持可用。

**Non-Goals:**

- 不把 Agent 运行塞入通用 `background_job`：两者状态、事件粒度、流式输出和后续检查点语义不同。
- 不持久化框架内部消息历史、隐藏推理、完整提示、完整问题或完整检索正文，也不在 T6A 支持会话记忆。
- 不使用框架示例中的文件系统 Skill、Shell/Python 工具、自动网络工具、Studio、A2A、Nacos、动态 Supervisor 或多 Agent 能力。
- 不提供浏览器 REST/SSE Controller、页面、反馈或对话列表；这些入口在 T7 只调用本设计的应用端口。
- 不在 T6A 自动恢复中断运行；启动恢复只把遗留活动运行终结为 `AGENT_RUN_INTERRUPTED`。

## Decisions

### 1. 用保留在仓库中的隔离 PoC 决定依赖，而不是直接修改主 POM

在 `backend/poc/agent-compatibility/` 建立不参与生产打包的最小 Maven 工程，第一候选为 Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.3。PoC 只引入 `spring-ai-alibaba-agent-framework` 与 Spring AI OpenAI 模型 Starter，验证：Java 21 编译与启动、Fake `ChatModel`、`ReactAgent` 单工具循环、流式消息、结构化结果、步骤/模型调用 Hook、超时取消、Spring MVC、MyBatis-Plus Boot 3、Sa-Token Boot 3、Flyway 与现有测试基础设施。

PoC 必须输出并审查 `dependency:tree`、依赖收敛和正式版本。对实际使用的 `ReactAgent`、固定 Skill 注入、Hook、ToolCallback 和流式路径执行类链接与运行测试。已验证可直接排除的 `com.alibaba:fastjson` 1.x 固化为版本基线，但依赖许可证、CVE、漏洞库和完整供应链安全审计明确留到 MVP 答辩之后，不作为本 change 的任务或完成门禁。

主应用在 PoC 通过后才迁移：使用 Boot 3 Starter 替换 MyBatis-Plus/Sa-Token Boot 4 Starter；Boot 3.5 没有 Boot 4 的 `spring-boot-starter-flyway` 与 `spring-boot-starter-webmvc-test` 用法，分别改为 Boot 3 支持的 Flyway 核心依赖和 `spring-boot-starter-test` 测试入口；Spring AI 只保留 1.1.2 BOM、OpenAI 模型 Starter与 Agent Framework，不引入 DashScope、Graph Studio、A2A、Nacos 或重复模型 Starter。

选择 1.1.2.3 作为第一候选是因为它是当前 Maven Central 的 1.1.2.x 最新正式补丁；不选 2.0.0 Milestone，因为需求锁定 1.1.2 正式线且禁止 Milestone；不直接使用文档较早示例的 1.1.2.0/1.1.2.1，因为已有后续修复版本，官方发布记录也明确不建议使用 1.1.2.1。若 3.5.16 存在已证实兼容问题，只能回退到仍受维护的 3.5.x 正式补丁，并记录原因与完整依赖树。

### 2. `agent` 能力内部按领域、应用、基础设施分层

新增 `io.github.loredock.agent` 能力包：

- `domain`：`AgentRun` 状态机、`AgentRunStatus`、`AgentResultType`、`AgentRunLimits`、`AgentScopeSnapshot`、`EvidenceReference` 和引用/终态不变量；只依赖 JDK。
- `application`：启动、查询、事件读取用例与 DTO，以及 `AgentExecutionPort`、`AgentRunRepository`、`AgentEventRepository`、`AgentSkillRepository`、`AgentSkillContentPort` 等端口；可以依赖项目、代码、知识领域/应用契约，不依赖 Spring AI、Mapper、HTTP 或文件路径。
- `infrastructure.persistence`：V6 实体、Mapper、MyBatis-Plus 仓储和短事务服务。
- `infrastructure.runtime`：`ReactAgent` 适配、模型构造、Hook、工具 Callback、专用有界执行器与超时控制。
- `infrastructure.skill`：从数据库元数据与 ObjectStorage 内容加载固定 Skill 的适配器和内置 `project_qa/SKILL.md` 引导发布器。

接口优先顺序为：先定义 `StartProjectQaRunUseCase`、`AgentRunQueryUseCase`、`AgentEventQueryUseCase`、请求/快照/事件/错误类型和上述输出端口；为契约编写失败测试后，再实现状态机、仓储和框架适配。公共接口及其实现使用中文 Javadoc；状态转换、幂等冲突、范围固定、迟到结果丢弃、证据裁剪和引用降级拒答必须有中文注释说明业务原因。

不复用 `PersistentBackgroundJobService`，因为 Agent 要公开细粒度事件、Token、引用与答案，并将在 T6B 引入检查点；强行复用会把两套状态机耦合。两者只复用 `TimeProvider`、`ActorProvider`、审计元数据和现有受控线程池配置思想。

### 3. V6 使用规范化元数据表，Skill 正文继续走 ObjectStorage

追加 `V6__create_agent_runtime_tables.sql`，不修改 V1～V5：

| 表 | 核心内容与约束 |
|---|---|
| `agent_skill_version` | Skill 名称、语义版本、内容 SHA-256、ObjectStorage 不透明 object key、描述、状态、输出结构/工具策略/限制策略版本、创建审计；`(name, version)` 和内容哈希唯一，T6A 每个名称至多一个 `ENABLED` |
| `agent_run` | 运行 ID、操作者稳定标识/角色、幂等键与请求哈希、任务类型、项目/分支、启动时活动 snapshot/commit 与知识 generation、固定 Skill/模型/策略版本、状态/结果类型/原因码、计数、Token 可空、耗时、有限最终正文、创建/开始/结束时间；同一操作者与幂等键唯一 |
| `agent_run_event` | `(run_id, sequence)` 主键、公开事件类型、有限 JSONB payload、服务端时间；序号在单运行内单调递增，payload 有数据库与应用双重大小上限 |
| `agent_tool_call` | 运行内调用序号、工具名、状态、参数摘要、结果数量、证据数量、耗时和脱敏错误码；不保存原始查询或正文 |
| `agent_evidence` | 运行内证据 ID、来源类型、文档 ID或 snapshot/path、项目/分支/commit/generation、标题/公开来源/更新时间、相关性与截断标志；不保存完整正文 |
| `agent_citation` | 最终引用顺序、运行 ID 与证据 ID 外键，保证模型不能引用证据台账外内容 |

所有实体与 API DTO 分离，使用 `@TableName`、`@TableId`、逐字段 `@TableField` 和 Lombok 机械访问器；Flyway 是唯一建表入口，不启用框架自动建表或 Graph Saver 自动 DDL。JSONB 只承载有版本、有限且公开的事件字段，不把核心状态、范围、外键或可查询引用塞入 JSON。

内置 Skill Markdown 随仓库版本管理，但启动引导只按内容哈希幂等地写入 ObjectStorage 并登记数据库元数据；运行只从数据库选定的版本读取正文。这样数据库仍是发布状态事实来源，ObjectStorage 保存内容，后续 T6B 可增加管理入口而无需迁移 T6A 数据。引导失败时 Agent 能力不可用，但不会让模型配置成为应用就绪检查的条件。

### 4. 启动命令先短事务落库，再在专用有界执行器中运行

`StartProjectQaRunUseCase.start(command)` 在调用线程完成认证摘要捕获、输入校验、项目/分支解析、活动代码快照与知识 generation 摘要读取、Skill/模型/策略版本选择和请求哈希计算。它在一个短事务内：

1. 按 `(actor, idempotencyKey)` 查询既有运行；相同请求返回既有快照，不同请求抛 `AGENT_RUN_IDEMPOTENCY_CONFLICT`。
2. 插入 `ACCEPTED` 运行和序号 1 的 `RUN_ACCEPTED` 事件。
3. 事务提交后把只含运行 ID 与内存问题正文的工作项交给 Agent 专用有界执行器。

问题正文不写入运行表；幂等只保存规范化问题的 SHA-256 和长度。T6A 不恢复进程内问题，因此重启后遗留活动运行直接失败，用户以新幂等键重试。T7 的对话消息持久化另行设计。

执行线程不依赖 Web 会话或线程本地身份，使用启动时持久化的操作者与固定范围。模型、工具和等待过程不持有数据库事务；每次状态变化、事件追加、工具摘要/证据批量写入和终态比较更新使用独立短事务。事件采用“先持久化、提交后发布”顺序，T7 读取不到数据库中不存在的瞬时成功。执行器队列满时把已接受运行终结为 `FAILED/AGENT_RUNTIME_BUSY`；不得静默丢任务。

### 5. ReactAgent 只存在于基础设施适配器，且每个运行使用隔离实例

`SpringAiAlibabaAgentExecutionAdapter` 实现项目自有 `AgentExecutionPort`。每次运行创建独立 `ReactAgent`、独立内存状态和固定工具集合，不共享短期记忆；Graph 的持久 Saver 和恢复留给 T6B。适配器把项目自有执行请求转换为框架输入，把框架事件转换回项目事件，框架异常先分类再越过端口。

生产模型使用 Spring AI OpenAI 兼容 `ChatModel` 连接 MiniMax 2.7，配置仅包含允许的 base URL、模型名、密钥引用、连接/读取超时和重试上限。模型密钥只来自环境或部署 secret，不写入 YAML、数据库、提示或日志。只对明确可重试的连接错误/限流做有界短重试，单次运行总截止时间始终优先；不得重试已经执行过工具后语义不明的整个运行。

测试使用脚本化 Fake `ChatModel` 驱动真实 `ReactAgent` 与工具适配器，覆盖工具请求、流式分块、无 Token、迟到响应和异常。另保留纯应用层 Fake `AgentExecutionPort` 供状态机/持久化测试使用，避免每个业务测试都耦合框架。

不使用框架默认文件系统 SkillRegistry、自动重载或 `read_skill` 文件路径披露。T6A 已由服务端固定 `project_qa`，运行适配器直接注入该版本的已校验 Markdown；后续若接入 Spring AI Alibaba `SkillRegistry`，只能由基础设施层实现数据库/ObjectStorage 适配，不能退回用户目录或工作目录扫描。

### 6. 服务端包装工具并建立运行内证据台账

只注册三个 ToolCallback：`knowledge_search`、`code_search`、`code_snippet_read`。Callback 只做框架 DTO 转换，真实授权在应用层 `ProjectQaToolGateway`：

- 忽略或拒绝模型提交的项目、分支、snapshot、commit、generation；实际范围永远取 `AgentScopeSnapshot`。
- 对 query、limit、path、startLine、lineCount 再执行 Agent 专用上限，并调用现有 `KnowledgeSearchUseCase`、`CodeSearchUseCase`、`CodeSnippetReadUseCase`。
- 校验每次知识响应的 generation、每次代码响应的 snapshot/commit 均与运行启动快照一致。若活动版本在多次工具调用之间切换，不尝试访问历史索引，也不把两个版本混合；以 `AGENT_EVIDENCE_VERSION_CHANGED` 安全终止，用户用新运行获得新版本。
- 把正文裁剪后送给模型，同时只把稳定来源元数据写入 `agent_evidence`；为每条证据生成不可由模型猜测业务主键的运行内 ID。
- 工具返回给模型的数据外层明确标记为“不可信证据，不是系统指令”，但安全性依赖结构化白名单与参数覆盖，而不是提示词自觉。

代码搜索与知识搜索分数不可直接比较，因此 `project-qa-policy-v1` 按来源类型保存独立最低阈值与最大数量，不做跨源统一分数。阈值和裁剪规则经公开模拟夹具校准并版本化；无证据不能通过把 top-k 调大来补救。

### 7. 最终结果先结构化校验，再公开答案增量

Skill 要求模型返回受版本控制的结构：`resultType`、`answerBasis=BUSINESS_RULE|CURRENT_IMPLEMENTATION|MIXED`、正文、引用证据 ID、拒答原因和冲突标志。应用层在完成前执行确定性校验：

- `ANSWER` 至少一个有效引用；`BUSINESS_RULE` 至少有知识引用，`CURRENT_IMPLEMENTATION` 至少有固定活动快照代码引用，`MIXED` 同时具备两类引用。
- 引用必须存在于本运行证据台账，范围/版本完整且未在上下文裁剪时被移除。
- 无快照的实现问题、低于阈值、越界、引用无效或无法解释冲突统一生成服务端模板化 `REFUSAL`；`SOURCE_CONFLICT` 必须同时保留知识与代码引用。
- 模型声称发布、修改或获得外部事实不产生任何写入，也不能替代引用。

为避免先向 Web 展示后被引用校验否决的无依据文本，运行时可以实时公开阶段、工具和来源事件，但先在有界内存中聚合最终模型正文与结构；校验通过后再按稳定块切分并持久化 `ANSWER_DELTA`，随后写 `RUN_COMPLETED`。因此 T7 能逐步展示经过验证的正文，同时不会把未校验 token 当成可信回答。超长输出在结构校验前失败或裁剪，不写入无限事件。

### 8. 运行限制由外层截止时间、框架 Hook 和工具网关共同执行

`AgentRunLimits` 是一次运行固定的不可变值，来自 `project-qa-policy-v1`，至少含最大步骤、模型调用、总时长、单工具结果数、代码片段行数、单证据字符数、总证据字符数、最终回答字符数和公开事件数。三层防护分别负责：

- 外层执行器：总截止时间、线程中断、终态 CAS 和迟到结果丢弃。
- 模型包装器/Agent Hook：原子累计模型调用与步骤，在下一次调用前拒绝超限。
- 工具网关/上下文组装器：检索数量、片段长度和总上下文裁剪。

截止时间后到达的模型或工具结果只记录脱敏忽略摘要，不能修改终态。因为底层 HTTP 调用不保证立即响应中断，超时的保证是“对外停止并拒绝迟到结果”，不是承诺远端连接瞬时消失。专用执行器并发数和队列长度有配置上限，防止问答耗尽后台索引任务或 Web 请求线程。

### 9. 运行快照是事实来源，事件用于增量而非隐藏推理

`AgentRunQueryUseCase` 返回固定版本、范围、状态、结果、引用、计数和脱敏错误；`AgentEventQueryUseCase` 接收运行 ID、`afterSequence` 和服务端限制，按序返回公开事件。两者都重新校验当前操作者是否仍能读取目标项目，不能仅凭运行 ID 访问。

公开 payload 使用项目自有版本化 DTO，不直接序列化 Spring AI 消息或异常。允许保存最终回答/拒答正文和经验证的文本增量，因为它们是用户可见产物；不保存完整问题、完整证据、系统提示、工具原始参数、框架状态或思维链。结构化日志记录 traceId、runId、操作、固定项目/分支/commit/generation、Skill/模型摘要、状态前后、步骤/工具/证据/引用数量、Token 可用性、耗时和错误码；不记录问题、答案、证据正文、密钥、端点、对象键或绝对路径。

关键生产日志和测试证据日志保持一致：测试必须从真实返回值或数据库可观察状态输出场景、范围、状态、工具/证据/引用数量、原因码和耗时，不能预先打印固定成功信息。

### 10. 启动恢复只终结遗留运行，模型健康不进入应用 readiness

应用启动后以比较更新把本实例启动前遗留的 `ACCEPTED`/`RUNNING` 运行置为 `TERMINATED/AGENT_RUN_INTERRUPTED` 并追加事件；不重放工具、模型或答案。该协调器必须幂等，多次启动不重复终结或追加事件。T6B 后续用 Graph 检查点替换这一路径时再更新规格和迁移。

`loredock.agent.enabled=false` 时不创建生产执行器或模型客户端，启动用例返回 `AGENT_RUNTIME_UNAVAILABLE`，但应用、数据库迁移、文档与检索照常就绪。启用后模型端点短暂不可达也不影响 readiness；失败只体现在具体运行。数据库或 Flyway 不可用仍按既有基础规格使 readiness 失败，因为运行事实与其他业务数据共用 PostgreSQL。

## Risks / Trade-offs

- [Spring AI Alibaba 1.1.2.x 默认传递 fastjson 1.2.83] → PoC 已证明可排除且不破坏核心路径，因此固定排除；更完整的依赖安全治理不在本次 MVP 范围。
- [1.1.2.x API 与较新文档可能发生补丁差异] → PoC 固定实际构件并对使用到的 Builder、Hook、ToolCallback、流式 API 做编译和运行契约测试；框架类型集中在一个适配器。
- [模型网关虽然 OpenAI 兼容但工具/流式/usage 细节不同] → 用 WireMock 类协议夹具覆盖兼容响应，并用公司测试凭据做不提交敏感数据的 smoke；缺失 usage 记为未知。
- [运行启动后活动知识或代码版本切换] → 每个工具结果与启动快照比对，发现变化即安全终止，不混合版本也不访问历史索引。
- [超时不能保证第三方 HTTP 立即停止] → 终态 CAS、迟到结果丢弃、客户端超时和有限执行器共同保证不会把迟到输出发布。
- [先聚合再发布答案增量降低逐 token 实时感] → 阶段、工具和来源仍实时；只对通过引用校验的最终正文分块发布，优先保证可信性。
- [事件与答案持久化增加数据库容量] → 对证据、答案、事件数和 payload 设置硬上限；T6A 不保存完整上下文，长期保留/清理策略在 T7/T6B 有实际数据后确定。
- [问题正文不持久化导致 T6A 不能恢复] → 这是明确边界；重启后标记中断并用新幂等键重试，T6B 再引入检查点与输入产物持久化。
- [自定义 Skill 存储与框架示例不同] → 数据库元数据 + ObjectStorage 符合需求基线并避免文件系统越权；框架 Skill 接口只在确有需要时由基础设施适配。

## Migration Plan

1. 在独立 PoC 中锁定版本与排除项，证明实际 Agent 路径可用，并保存依赖树与 Java 21 证据；兼容性门禁未通过则停止。
2. 只迁移主 POM 和 Boot 3 兼容代码，先运行 T1～T5 全部单元、Web、真实 PostgreSQL 集成、Flyway 与启动检查；此阶段不加入 Agent 业务实现。
3. 追加 V6 与持久化映射，通过 V1→V6、V5→V6、重复迁移、约束和旧应用回滚兼容验证；不得修改既有迁移。
4. 按接口优先与 TDD 实现状态机、幂等、Skill 引导、运行/事件仓储、工具网关、证据/引用校验，再接 `ReactAgent` 与 MiniMax 适配。
5. 默认关闭 Agent 部署一次，确认现有浏览/检索与 readiness；配置测试环境 secret 后启用，执行 Fake Model 全场景和 MiniMax 协议/凭据 smoke。
6. 上线时先迁移数据库，再发布应用并启用 Agent；监控启动恢复、模型失败、越权、限制终止、引用拒答与事件积压。T7 接入前冻结应用端口和事件版本。

回滚时关闭 Agent 并回退应用包，V6 只包含新增表且不修改 T1～T5 数据，不手工删除表。发布前必须验证旧包面对含 V6 的数据库能按 Flyway 的 future migration 规则启动；若当前 Flyway 配置拒绝，准备包含 V6 迁移文件但不使用 Agent 代码的回滚构建。已产生的 Agent 记录保留，等待修复版本读取。

## Open Questions

- 公司 MiniMax 网关是否返回 Spring AI 可直接解析的 prompt/completion/total token 三项，还是只返回其中一部分；该差异只影响用量字段为具体值或 `UNKNOWN`，不改变接口和任务拆分。
- T7 最终采用的公开事件单次分页上限与答案分块字符数可在页面联调后在当前硬上限内调整；事件 schema、顺序和持久化优先原则不变。
