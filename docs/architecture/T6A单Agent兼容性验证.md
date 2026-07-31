# T6A 单 Agent 兼容性与运行架构

本文记录 T6A 在修改主应用之前完成的隔离 PoC。可验收行为以 OpenSpec change `add-single-agent-project-qa-runtime` 为准；依赖许可证、CVE 与供应链安全审计不属于本次内网答辩 MVP 范围。

## 1. 固定版本

PoC 位于 `backend/poc/agent-compatibility/`，不属于主应用 Maven 模块，也不会进入生产 fat-jar。最终候选为 Java 21、Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba Agent Framework 1.1.2.3、MyBatis-Plus Boot 3 Starter 3.5.17、Sa-Token Boot 3 Starter 1.45.0 和 Flyway 11.7.2。

Spring AI 与 Spring AI Alibaba 分别通过 BOM 固定。Agent Framework 的旧 MCP 0.14 传递依赖与 Spring AI 1.1.2 使用的 MCP JSON 0.17 冲突，T6A 本身不启用 MCP，因此排除旧 `io.modelcontextprotocol.sdk:mcp`。同时排除 T6A 不使用的 A2A Client 和已经证明核心路径不需要的 fastjson 1.x。JetBrains annotations 固定为 24.0.1 以消除框架与 flexmark 的双版本。

Agent Framework 的默认 Jackson 状态序列化器会直接链接 Spring AI DeepSeek 与 ZhiPuAI 消息类型，因此这两个模型 API jar 不能从框架依赖中裁剪；主应用仍只配置 OpenAI 兼容模型 Starter，不创建对应模型客户端或自动配置。

## 2. 真实 API 差异

- `ReactAgent.call` 返回 `AssistantMessage`，`streamMessages` 返回 `Flux<Message>`；同步调用和流式调用都由相同图执行。
- `ReactAgent` 默认走流式模型路径，Fake `ChatModel` 必须同时实现 `call(Prompt)` 与 `stream(Prompt)`。
- 固定 Skill 内容可通过每运行 Agent 的 `instruction` 注入，并能在模型收到的 Prompt 中观察到；T6A 不启用框架文件系统 Skill 扫描。
- `ModelCallLimitHook` 在下一次模型调用前直接抛出 `ModelCallLimitExceededException`，不是包装异常。
- 外层 Reactor `timeout` 会取消尚未交付的模型流；PoC 观察到取消计数为 1、迟到答案交付数为 0。
- `ReactAgent` 会把普通默认 ChatOptions 转换为禁用内部工具执行的 `ToolCallingChatOptions`，测试日志中的警告不影响受控工具循环。

## 3. 测试证据

在 Homebrew OpenJDK 21.0.12 下执行：

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./mvnw -f poc/agent-compatibility/pom.xml test
```

实际通过 6 个测试，覆盖 fastjson 类路径缺失、Agent/Hook/ToolCallback 类链接、固定 Skill Prompt 注入、单次白名单工具循环、结构化 ANSWER 与引用、流式消息、第二次模型调用前的 Hook 限制，以及超时取消和迟到结果丢弃。关键证据为：受控工具循环调用模型 2 次、工具查询恰为“场景包 刷新拓扑”；调用上限场景只调用模型 1 次；超时场景交付 0 个答案并收到 1 次取消信号。

Maven Enforcer 的 Java 版本、正式依赖、依赖收敛和禁用依赖规则全部通过。运行依赖树只包含 Spring Boot 3.5.16、Spring Framework 6.2.19、Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.3；没有 Spring Boot 4、Spring AI 2、Boot 4 Starter、SNAPSHOT/Milestone、A2A Client、DashScope/Studio/Nacos Starter 或 fastjson 1.x。

## 4. 迁移结论

候选版本可以在 Java 21 上驱动真实 `ReactAgent` 完成 T6A 所需核心路径，依赖冲突已有明确排除或版本固定方案。主应用已经按相同版本与排除项迁移到 Boot 3，并在 Agent 默认关闭、无模型密钥时完成 T1～T5 回归。

## 5. 主应用运行结构

`StartProjectQaRunService` 校验操作者、问题和幂等键，解析并固定项目、实际分支、活动代码 snapshot/commit、活动知识 generation、Agent 名称、模型名称和必要配置摘要。`AgentRunService` 在短事务内统一完成受理、开始、完成或失败状态及对应事件；事务提交后才进入专用有界执行器，模型和工具等待不占用数据库事务。

`ClasspathAgentDefinitionProvider` 从部署制品加载固定的 `project_qa` 定义。当前阶段不提供数据库 Skill 目录、对象存储 bundle、发布/启停或版本管理。每次运行创建独立 `ReactAgent` 和记忆，注入 Agent 定义和结构化输出约定；应用只保留稳定的 `AgentRuntime` 契约，具体实现 `SpringAiAlibabaAgentRuntime` 直接使用 Spring AI Alibaba 的 ReactAgent、Flow、Hook 和 ToolCallback。

模型边界使用 Spring AI 标准 `ChatModel`。当前生产配置可使用 DeepSeek OpenAI 兼容模型，但 Controller、QA Service 和 Agent 核心 Service 不依赖供应商类型。替换聊天模型时只需提供新的 `ChatModel` Bean 并调整服务端配置与 secret；无需修改 HTTP 契约或业务流程。密钥只从进程环境或部署 secret 读取。

三个只读工具统一经过应用网关：

- `knowledge_search`：复用 T5 混合检索并固定当前运行的项目、分支和 generation；
- `code_search`：复用 T4 活动快照查询并固定项目、分支、snapshot 和 commit；
- `code_snippet_read`：只读取检索后仓库相对路径的有限行范围。

工具再次校验下层返回范围，并在进入模型上下文前执行相关性过滤、数量/片段/总上下文裁剪。每条结果形成当前运行内的自增 `Long` 证据 ID，另有 `E1`、`E2`……形式的运行内展示键；模型引用证据 ID。证据台账只保存来源元数据，不保存完整知识或代码正文、对象键或服务器路径。

知识向量模型同样使用 Spring AI 标准 `EmbeddingModel`。当前 ONNX 实现只负责实现标准接口，文本组合、查询指令、维度校验和模型摘要留在 `KnowledgeEmbeddingService`。替换嵌入模型时提供新的 `EmbeddingModel` Bean，并确保维度及模型摘要与重建 generation 一致；Controller 和知识检索核心 Service 无需修改。

## 6. 可信结果与事件

模型结构化结果在 Service 中仍是不可信输入。服务端完成前确定性校验：`BUSINESS_RULE` 必须引用知识，`CURRENT_IMPLEMENTATION` 必须引用固定活动快照代码，`MIXED` 必须同时引用两类来源；引用必须属于本运行、仍被保留且元数据完整。伪造、跨运行、被裁剪或依据类型不匹配的引用统一转换为 `AGENT_CITATION_INVALID` 拒答。

没有证据、低相关、超出范围、实现问题无快照或知识与代码无法解释冲突时，返回“当前知识库没有足够依据”及稳定原因码。冲突拒答可以保留当前范围内的两类有限引用。Agent 不得用模型常识补写项目事实，也不能声称已经发布知识或修改项目配置。

运行状态只按 `ACCEPTED → RUNNING → COMPLETED|FAILED|TERMINATED` 前进。持久化事件只保留 `RUN_ACCEPTED`、`RUN_STARTED`、`MODEL_STARTED`、`SOURCE_FOUND`、`RUN_COMPLETED`、`RUN_FAILED` 和 `RUN_TERMINATED`。最终正文只写入运行终态和问答终态消息，不写逐 token、逐分片或拒答正文事件。

SSE 建连时先订阅当前进程的事务提交后通知，再按 `afterSequence` 从数据库补读已提交事件，因而不会遗漏订阅与补读之间的事件；在线等待期间不以固定间隔轮询数据库。断线重连继续从数据库按序补读，心跳只用于保持连接和复核会话。收到终态事件后，前端重新读取详情，以 `agent_run`、终态消息和 `agent_evidence.cited/citation_order` 为最终展示事实。

## 7. 配置与运行限制

`.env.example` 列出全部受控变量。默认 `LOREDOCK_AGENT_ENABLED=false`；启用时必须通过未提交的进程环境或部署 secret 提供 `LOREDOCK_AGENT_MODEL_API_KEY`。模型名、官方入口、超时、重试和运行上限由服务端配置，调用方与模型不能提高。

默认限制为 8 个 Agent 步骤、8 次模型调用、90 秒总截止时间、每工具 10 条结果、单片段 2000 字、总上下文 24000 字和最终回答 8000 字。模型调用上限由 Spring AI Alibaba Hook 执行，总截止时间会取消未完成流；项目代码不重复实现框架已有的 ReAct 循环、计数和流式拼装。

真实模型 smoke 只需验证鉴权、最小非流式 JSON 结构、Token 可用性和耗时。工具循环、流式分块、usage 缺失、429、鉴权、连接失败、无效 JSON、取消和超限均由 Fake Model 或本地协议服务覆盖，避免重复付费。

## 8. 故障、恢复与备份

- Agent 关闭、secret 缺失或 classpath Agent 定义不可用时不会创建外部模型连接；现有文档浏览、知识搜索、代码搜索和活动索引继续工作。
- 有界执行器满时，已受理运行以 `AGENT_RUNTIME_BUSY` 失败并保留事件，不静默丢失。
- 运行中活动 generation/snapshot 变化时以 `AGENT_EVIDENCE_VERSION_CHANGED` 终止，防止混用两版证据。
- 超时、步骤/模型调用超限为 `TERMINATED`；模型不可用、越权工具和无效响应为 `FAILED`；所有终态记录实际可得的步骤、模型调用、Token 和耗时。
- 后端启动时把不可恢复的遗留 `ACCEPTED/RUNNING` 短运行幂等终结为 `AGENT_RUN_INTERRUPTED`。未来多 Agent 长流程使用 Spring AI Alibaba Graph `PostgresSaver` 从最后检查点恢复。
- 日志记录 traceId/runId、固定范围、版本标识、状态、计数、Token 可用性、耗时和错误码，不记录问题、回答、证据正文、思维链、密钥、模型端点、对象键、绝对路径或连接串。

Agent 业务持久化只包含 `agent_run`、`agent_run_event` 和 `agent_evidence`；引用状态和顺序直接保存在 evidence 中。Graph 恢复状态位于 `graphthread` 和 `graphcheckpoint`，由 Flyway 建表，`PostgresSaver` 使用项目 DataSource 和 `CREATE_NONE`，运行时不得自动改表。classpath Agent 定义随代码仓库和应用制品备份，不在对象存储或数据库中另存版本。

数据库、对象存储、知识索引事实和代码快照应取自同一静默写入窗口。恢复后先验证单一 Flyway V1、Agent 三张业务表和 Graph 两张表，再按需重建派生索引；不要手工修改运行终态、证据引用标记或 checkpoint 协议键。旧 UUID/多迁移开发库不能整体恢复到当前基线，应先导出材料、删除旧开发卷并从 V1 重建。

## 9. 完成门禁证据

2026-07-31 在 Java 21、Maven Wrapper、Node 24 和 Testcontainers PostgreSQL 17/pgvector 0.8.1 环境完成最终重构验证：

- 隔离 PoC 全部通过，验证真实 `ReactAgent`、工具循环、流式、模型调用上限和超时取消；
- 后端全量单元/契约测试通过，26 个真实 PostgreSQL `*IT` 集成测试文件通过；
- 前端 18 个测试文件、83 个测试通过，`vue-tsc -b && vite build` 通过；
- 空库只执行单一 Flyway V1，重复启动不新增迁移，创建 17 张业务表和 2 张 Graph 表；所有数据库主键为 identity `BIGINT`；
- Fake Model + 真实 PostgreSQL 验证三类回答依据、非法引用、证据不足拒答、模型不可用、粗粒度事件续读、事务回滚不发布事件和终态 CAS；
- `PostgresSaver` 验证 `CREATE_NONE` 不自动建表，连接 Flyway 表后能够恢复状态、下一节点和已提交副作用标记；
- `./scripts/smoke-test.sh` 通过登录、项目/分支、知识导入/发布/索引/检索、代码快照/检索、项目问答/引用/拒答、知识缺口、重启持久性和数据库失联健康检查；无模型配置时非 Agent 核心能力正常启动；
- Maven 依赖保持 Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.3，未混入 Boot 4 或 Spring AI 2。

需要额外本地离线模型文件的正式检索基准以及真实外部模型付费 smoke 不属于本次默认全量验证；标准模型接口、Fake Model、本地协议和真实数据库已经覆盖模型替换边界与失败行为。
