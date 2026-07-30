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

`StartProjectQaRunUseCase` 先校验操作者、问题和幂等键，再解析并固定项目、实际分支、活动代码 snapshot/commit、活动知识 generation、内置 Skill、模型和策略版本。运行及首事件在短事务内持久化，提交后才进入专用有界执行器；模型和工具等待不占用数据库事务。

每次运行创建独立 `ReactAgent` 和记忆，固定注入已校验的 `project_qa` Markdown 与结构化输出约定。Spring AI Alibaba 类型只存在于 `infrastructure.model`，应用层只依赖 `AgentExecutionPort`。生产适配使用 DeepSeek `deepseek-v4-flash` 的 OpenAI 兼容 Chat Completions；密钥只读取进程环境或部署 secret。T6A 不启用框架文件系统 Skill、Shell/Python、任意 HTTP、A2A、Studio、Nacos、动态 Agent 或写工具。

三个只读工具统一经过应用网关：

- `knowledge_search`：复用 T5 混合检索并固定当前运行的项目、分支和 generation；
- `code_search`：复用 T4 活动快照查询并固定项目、分支、snapshot 和 commit；
- `code_snippet_read`：只读取检索后仓库相对路径的有限行范围。

工具再次校验下层返回范围，并在进入模型上下文前执行相关性过滤、数量/片段/总上下文裁剪。每条结果形成当前运行内的证据 ID；证据台账只保存来源元数据，不保存完整知识或代码正文、对象键或服务器路径。

## 6. 可信结果与事件

模型结构化结果在应用层仍是不可信输入。服务端完成前确定性校验：`BUSINESS_RULE` 必须引用知识，`CURRENT_IMPLEMENTATION` 必须引用固定活动快照代码，`MIXED` 必须同时引用两类来源；引用必须属于本运行、仍被保留且元数据完整。伪造、跨运行、被裁剪或依据类型不匹配的引用统一转换为 `AGENT_CITATION_INVALID` 拒答，模型回答正文不会产生 `ANSWER_DELTA`。

没有证据、低相关、超出范围、实现问题无快照或知识与代码无法解释冲突时，返回“当前知识库没有足够依据”及稳定原因码。冲突拒答可以保留当前范围内的两类有限引用。Agent 不得用模型常识补写项目事实，也不能声称已经发布知识或修改项目配置。

运行状态只按 `ACCEPTED → RUNNING → COMPLETED|FAILED|TERMINATED` 前进。阶段、Skill 固定、模型、工具和来源事件可实时持久化；最终正文先聚合并通过校验，再按 500 个 Unicode 码点分块写入 `ANSWER_DELTA`，最后写终态事件。消费者使用 `afterSequence` 断线续读，不需要重新执行模型。

## 7. 配置与运行限制

`.env.example` 列出全部受控变量。默认 `LOREDOCK_AGENT_ENABLED=false`；启用时必须通过未提交的进程环境或部署 secret 提供 `LOREDOCK_AGENT_MODEL_API_KEY`。模型名、官方入口、超时、重试和运行上限由服务端配置，调用方与模型不能提高。

默认限制为 8 个 Agent 步骤、8 次模型调用、90 秒总截止时间、每工具 10 条结果、单片段 2000 字、总上下文 24000 字、最终回答 8000 字和 200 条公开事件。下一步或模型调用即将超限时立即终止；总截止时间会取消未完成流。只对连接失败和 429 等明确瞬时失败做有限重试，鉴权失败、无效 JSON 与已经执行工具后的整个运行不重放。

真实模型 smoke 只需验证鉴权、最小非流式 JSON 结构、Token 可用性和耗时。工具循环、流式分块、usage 缺失、429、鉴权、连接失败、无效 JSON、取消和超限均由 Fake Model 或本地协议服务覆盖，避免重复付费。

## 8. 故障、恢复与备份

- Agent 关闭、secret 缺失或 Skill 不可用时不会创建外部模型连接；现有文档浏览、知识搜索、代码搜索和活动索引继续工作。
- 有界执行器满时，已受理运行以 `AGENT_RUNTIME_BUSY` 失败并保留事件，不静默丢失。
- 运行中活动 generation/snapshot 变化时以 `AGENT_EVIDENCE_VERSION_CHANGED` 终止，防止混用两版证据。
- 超时、步骤/模型调用超限为 `TERMINATED`；模型不可用、越权工具和无效响应为 `FAILED`；所有终态记录实际可得的步骤、模型调用、Token 和耗时。
- 后端启动时把遗留 `ACCEPTED/RUNNING` 运行幂等终结为 `AGENT_RUN_INTERRUPTED`。T6A 不自动重放；可恢复 Graph 检查点属于 T6B。
- 日志记录 traceId/runId、固定范围、版本标识、状态、计数、Token 可用性、耗时和错误码，不记录问题、回答、证据正文、思维链、密钥、模型端点、对象键、绝对路径或连接串。

数据库备份覆盖 `agent_skill_version`、`agent_run`、`agent_run_event`、`agent_tool_call`、`agent_evidence` 和 `agent_citation`；对象存储备份覆盖内置 Skill 内容。数据库、对象存储、知识索引事实和代码快照应取自同一静默写入窗口。恢复后先验证 Flyway V1→V6、Skill 内容哈希和活动证据版本，再启用 Agent；不要手工修改运行终态或引用外键。

## 9. 完成门禁证据

2026-07-30 在 Java 21.0.12、Maven Wrapper 3.9.12 和 Testcontainers PostgreSQL 17/pgvector 0.8.1 环境完成：

- 隔离 PoC 全部通过，验证真实 `ReactAgent`、工具循环、流式、模型调用上限和超时取消；
- 主应用 Surefire 306 个测试通过，4 个条件测试按配置跳过；Failsafe 133 个真实 PostgreSQL 集成测试通过，1 个需要本地离线 Embedding 模型的正式基准按条件跳过；
- 空库、V1 和 V4 均只追加迁移到 V6，重复迁移为 0；已有 V5 本地数据库由可执行 fat-jar 启动时只执行 V6；
- Fake Model + 真实 PostgreSQL 验证 `BUSINESS_RULE`、`CURRENT_IMPLEMENTATION`、`MIXED`、非法引用、四类拒答、事件续读、失败矩阵与脱敏日志；
- `deepseek-v4-flash` 只执行一次最小非流式 JSON smoke，确认鉴权、结构解析、Token 可用性和耗时；没有输出或提交密钥与请求/响应正文，其余协议与失败场景均由本地模拟覆盖；
- 119 MiB 可执行 fat-jar 在 Agent 关闭时启动成功，readiness 与系统状态均返回 `UP`，随后正常优雅停止；
- Maven Enforcer、运行依赖树和敏感模式检查通过：Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.3，未发现 Boot 4、Spring AI 2、非目标 Agent Starter 或已提交模型密钥。

未执行项只有需要额外本地离线模型文件的 T5 正式检索基准；该基准与 T6A Agent 改动无直接关系，T5 既有单元、Web 和真实 PostgreSQL 搜索/重建回归均已通过。依赖许可证、CVE 与完整供应链安全审计按内网答辩 MVP 范围明确排除。
