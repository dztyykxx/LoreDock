## Context

参见 `proposal.md`。当前 `project_qa` 通过自定义 `AgentRuntime`、classpath Provider 和正则 Skill Validator 包装框架；知识整理直接构建 `ReactAgent`，但配置的 Resolver/Hook 未进入生产装配、全部业务 Tool 被静态加入、调用统计固定为占位值，且旧启动恢复器会终结可恢复知识任务。

锁定版本已经提供所需通用能力：Spring AI Alibaba 1.1.2.3 提供 `ReactAgent`、`ClasspathSkillRegistry`/`FileSystemSkillRegistry`、`SkillsAgentHook`/`SkillsInterceptor`、模型/Tool 调用限制 Hook、模型/Tool/流式 Interceptor、Agent Spec/Task Tool、Graph interrupt 和 `PostgresSaver`；Spring AI 1.1.2 提供方法/函数 `ToolCallback`、Provider、Resolver、`ToolContext` 和 Tool 异常处理。框架的明确缺口是 Agent Spec Loader 会跳过无效文件、Factory 会静默过滤未知 Tool，因此 LoreDock 保留启动前确定性预检。

## Goals / Non-Goals

**Goals:**

- 将两个 Agent 路径收敛到同一种直接 `ReactAgent` 装配方式，只保留业务执行与投影代码。
- 让 Tool 集合、定义摘要、调用限制、失败传播、Checkpoint 和统计都来自真实框架执行。
- 删除重复 Provider/Validator/Runtime 和未使用 Bean，同时保持现有 API、数据库与安全规则不变。

**Non-Goals:**

- 不新增 Agent、Tool、数据表、发布能力、自动重试、模型路由或通用运行平台。
- 不把正式知识发布注册为 Agent Tool，不允许 Shell、任意 HTTP、任意文件系统或数据库管理。
- 不重写框架 Loader、Checkpoint、模型/Tool 循环或子 Agent 调度。

## Decisions

### 1. 使用两个具体执行 Service，删除通用 AgentRuntime 接口

`project_qa` 由具体的 `ProjectQaAgentExecutor` 直接持有 `ChatModel`、Skill Registry、`ProjectQaToolService` 和结果校验所需组件，并在单次执行内创建 `ReactAgent`；`ProjectQaRunTaskExecutor` 只保留业务 run 状态转换、结果落库和事件投影。知识整理继续由 `KnowledgeCurationRunExecutor` 创建 `ReactAgent`。框架执行测试在标准 `ChatModel` 和业务 Tool 边界替换，业务编排测试只替换这个具体执行器，不再替换通用 Runtime。

选择原因：项目只锁定一个 Agent 框架，`AgentRuntime` 没有真实替换边界，并已扩张成第二层运行时。备选方案是保留一个薄接口，但这仍会让两个执行模型长期分叉，且违反当前禁止包装通用 Runtime 的约束。

结构化最终响应仍由 LoreDock 解析并做引用校验，因为锁定版本 `outputType` 只生成格式提示，`ReactAgent.call/streamMessages` 仍返回 `AssistantMessage`；这属于业务结果边界，不是重写运行时。

### 2. 每个 run 创建框架 Registry/Hook，业务 Tool 使用 groupedTools 渐进披露

配置只提供 Skill/Agent Spec 目录和标准 Tool Provider/Resolver，不提供会被多个 Agent 共享的单例 Hook。新 run 创建新的框架 Registry：`project_qa` 使用 `ClasspathSkillRegistry`，知识整理使用 `FileSystemSkillRegistry`；Hook 只绑定该 Agent。知识整理在接受 run 时加载并预检定义，运行使用同一 Registry 实例且 `autoReload(false)`，新 run 才重新扫描目录。

协调 Agent 初始 Tool 为 Skill Hook 自带的 `read_skill/search_skills/disable_skill` 与框架 `Task/TaskOutput`；知识整理业务 Tool 由 `groupedTools(skillName -> callbacks)` 加入。子 Agent 仍由 `AgentSpecReactAgentFactory` 按 Spec 声明过滤同一安全候选集。

选择 `groupedTools` 而不是只信任 `allowed_tools`，因为服务端映射更适合作为安全边界；Skill Front Matter 可以同时使用规范化 `allowed-tools` 作为可读声明，但服务端候选集决定最终权限。

### 3. 统一框架 Hook/Interceptor 与立即失败语义

两个执行器都使用 `ModelCallLimitHook` 和 `ToolCallLimitHook`。总超时仍由应用对框架调用施加 Reactor/任务截止时间，因为锁定框架没有统一总运行超时 Hook。模型和 Tool 实际次数通过框架 `ModelInterceptor`、`ToolInterceptor`/业务 Tool 观察器记录；流式正文通过 `StreamingModelInterceptor` 与 `streamMessages` 观察，不再包装 `ChatModel`。

业务 Tool 异常使用 `DefaultToolExecutionExceptionProcessor.alwaysThrow(true)` 立即传播。错误映射沿 cause chain 保留 `AgentToolException` 业务码；删除“先把错误交还模型、耗尽预算后恢复第一次错误”的 Ledger 分支。证据、命中数量和裁剪量仍由业务 Tool 调用结果累计。

### 4. 区分应用调度与子 Agent 调度

保留 `BoundedAgentRunScheduler`，它只负责把已提交业务 run 从 Web 线程分发到有界应用线程池；它不选择子 Agent、不执行模型循环。`TaskToolsBuilder`/`TaskTool` 继续负责子 Agent 委派。为避免旧 `AgentRuntime` 参数耦合，调度器只保留 `schedule(runId, Runnable)`。

### 5. Checkpoint 是运行事实，agent_run 是业务投影

`PostgresSaver` 继续由 Flyway 预建表，`RunnableConfig.threadId` 是恢复键。`InterruptionHook` 负责安全节点中断；只有 Saver 可读取 Checkpoint 后才允许 `PAUSE_REQUESTED → WAITING_FOR_USER`。空 `HumanInTheLoopHook` 删除；未来若某个 Tool 需要逐次审批，必须在对应规格中增加 `approvalOn`。

启动恢复器只查询并终结 `project_qa` 的 `ACCEPTED/RUNNING`。知识整理的 `WAITING_FOR_USER` 保持不动；异常重启留下的 `RUNNING/PAUSE_REQUESTED` 根据 Checkpoint 投影为等待，若无 Checkpoint则记录真实中断失败。恢复器不读取或写入 Graph 内部状态。

### 6. Tool 注册优先使用方法 Tool，但不牺牲稳定 Schema

知识整理 Tool 方法改为 `@Tool`/`@ToolParam` 并由 `MethodToolCallbackProvider` 生成 Callback；`ToolContext` 保持服务端注入且不进入模型 Schema。若锁定版本对单 record 参数生成额外包装，方法使用显式参数或保留最小 `FunctionToolCallback.inputType(record)`，不为追求注解形式改变外部 Tool Schema。

`knowledge_search` 可以继续使用函数 Callback，因为它需要把本轮证据和统计写入执行上下文；但所有 Callback 统一从标准 Provider/Resolver 取得，不再在 Runtime 内建立第二套注册表。

## Risks / Trade-offs

- [Hook 会绑定具体 Agent，错误共享单例会产生并发串线] → 每个 Agent 构建独立 Hook，只共享无 Agent 状态的 Registry 配置与业务 Service。
- [定义文件在接受和后台执行之间变化] → 接受阶段构建并传递同一 `LoadedDefinition`/Registry；恢复时重新加载后必须与持久化摘要一致。
- [删除 AgentRuntime 会影响大量 Fake 测试] → 将 Fake 下沉为标准 `ChatModel`，先补生产装配测试，再机械迁移原有行为测试。
- [立即抛出 Tool 异常改变旧模型可见错误文本] → 这是规格要求的安全终态；保留稳定业务错误码，不让模型二次解释基础设施失败。
- [框架调用计数与旧 step 定义不同] → 明确 step 为实际模型调用数与 Tool 调用数之和，迁移后只保存真实值，不兼容旧占位统计。

## Migration Plan

1. 先增加生产装配级失败测试，覆盖 Tool 渐进披露、立即失败、真实计数和重启恢复分流。
2. 改造知识整理配置与执行器，确认真实 Skill/Task Tool/Checkpoint 路径通过。
3. 把项目问答迁移到直接 `ReactAgent`，删除 `AgentRuntime`、自定义 Skill Provider/Validator 及相关架构白名单。
4. 跑 Java 21 单元测试、真实 PostgreSQL 集成测试和受影响问答端到端测试；同步主规格和开发计划。
5. 本变更不改数据库结构；回滚可整体回退提交，不需要数据迁移。
