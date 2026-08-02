# T6B Spring AI Alibaba 框架原生能力核验

本文记录 T6B 在实现前对项目锁定版本所做的源码与官方资料核验。可验收行为仍以 OpenSpec change `add-conversational-knowledge-curation` 为准；本文只用于固定框架能力边界，防止实现阶段重复建设通用 Agent 基础设施。

## 1. 锁定版本与核验来源

- 主应用通过 `backend/pom.xml` 固定 Spring AI `1.1.2` 与 Spring AI Alibaba `1.1.2.3`。
- 本地源码核验使用 Maven 缓存中的 `spring-ai-alibaba-agent-framework-1.1.2.3-sources.jar`、`spring-ai-alibaba-graph-core-1.1.2.3-sources.jar` 和 `spring-ai-model-1.1.2-sources.jar`，不根据类名推测行为。
- 官方资料交叉核对了 [Agent Skills](https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/)、[Agent Tool](https://java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool/)、[Human-in-the-Loop](https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop/)、[Graph 持久化](https://java2ai.com/docs/frameworks/graph-core/examples/persistence/) 和 [长任务持久化执行](https://java2ai.com/docs/frameworks/graph-core/examples/long-time-running-task/)。官方文档说明的是框架使用方式，具体 API 与默认行为以项目实际锁定的本地源码为准。

## 2. 原生能力映射

| T6B 能力 | 锁定版本原生组件 | 源码确认的行为 | LoreDock 只需补充 |
|---|---|---|---|
| 内置 Skill | `ClasspathSkillRegistry`、`SkillsAgentHook`、`SkillsInterceptor` | Registry 从应用 classpath 加载 Skill；Interceptor 注入轻量 Skill 列表，并在模型真实调用 `read_skill` 后按需加入该 Skill 的 Tool | 随应用发布允许的 Skill、记录摘要与允许 Tool；不暴露 Skill 示例中的 Shell/Python 能力 |
| Agent Spec | `AgentSpecLoader`、`AgentSpecReactAgentFactory` | Loader 解析带 YAML front matter 的 Markdown；Factory 用 Spec 创建 `ReactAgent` 并按名称过滤默认 Tool | 新 run 前校验文件、必填字段、重复名称、显式 Tool 和允许集；每个新 run 重新构建，运行中不替换定义 |
| 子 Agent 委派 | `TaskToolsBuilder`、`TaskTool`、`TaskOutputTool`、`AgentTool` | `TaskToolsBuilder` 可从目录/Resource 加载 Spec 并创建 Task Tool；`AgentTool` 把 `ReactAgent` 直接包装为 Tool，并从父配置派生子 Agent threadId | 选择符合当前长任务恢复边界的框架 Tool，注入受限业务 Tool 和公开事件；不自建调度器或模型/Tool 循环 |
| Tool 注册与解析 | Spring AI `ToolCallback`、`ToolCallbackProvider`、`ToolCallbackResolver` | `ReactAgent.Builder` 可直接接收 callback、provider、名称和 resolver；`toolContext(Map)` 最终形成不可变 `ToolContext` | 提供固定操作者、项目、会话、run 和截止时间；在业务 Service 再做权限、范围、来源、上限与幂等校验 |
| 调用限制与过程 Hook | `ModelCallLimitHook`、`ToolCallLimitHook`、Agent/Model/Tool Hook/Interceptor | 限额由框架执行；Hook 和 Interceptor 能观察 Agent、模型与 Tool 边界 | 投影允许公开的事件与聚合计数，不保存思维链、完整提示或证据正文 |
| Checkpoint 与恢复 | `PostgresSaver`、`RunnableConfig.threadId`、Graph Checkpoint | `ReactAgent.Builder.saver(...)` 直接接入 Saver；`PostgresSaver` 接受项目 `DataSource`，可用 `CREATE_NONE` 禁止运行时建表；相同 threadId 读取已提交 Checkpoint | Flyway 管理协议表，稳定映射会话/run threadId，并让业务写 Tool 自身保持幂等 |
| 暂停与人在回路 | `InterruptionHook`、`HumanInTheLoopHook`、Graph interrupt | `InterruptionHook` 支持在模型节点边界响应 `ReactAgent.interrupt(...)`；`HumanInTheLoopHook` 在需要审批的 Tool 调用前产生 interrupt，并通过 `RunnableConfig` 接收反馈 | 只有可读取 Checkpoint 后才投影 `WAITING_FOR_USER`；失败步骤保留失败，不伪造暂停；指导消息持久化后用同一 run/threadId 恢复 |

## 3. 必须保留的确定性预检

锁定版本有意采用宽松加载行为，不能直接作为 LoreDock 的安全失败语义：

1. `AgentSpecLoader.loadFromDirectory` 会记录告警并跳过解析失败的单个文件；LoreDock 必须在模型调用前把无效定义明确返回为启动失败。
2. `AgentSpecReactAgentFactory` 在 Spec 未声明 Tool 时会使用全部 `defaultTools`；LoreDock 必须要求 Agent Spec 显式声明非空 Tool，并且候选集合本身只包含当前任务允许的业务 Tool。
3. Spec 中的未知 Tool 会在过滤后消失而不是报错；`SkillsInterceptor` 对无法解析的 `allowed_tools` 也只记录调试信息并跳过。LoreDock 必须比较声明名称、服务端候选名称和当前 run 授权集，遇到未知或越权名称立即拒绝。
4. Agent Spec 的 `model` 字段在该版本工厂中只记录“不支持覆盖”的调试信息；T6B 继续使用服务端固定模型配置，不接受文件扩大模型选择范围。
5. `RunnableConfig.context` 明确不会持久化。需要重启后恢复的业务身份与范围必须由会话/run 持久化事实重新构造，不能只放在运行内 context。

这些校验是框架前的一层确定性业务门禁，不构成自研 Tool Registry、Agent Spec Loader、Checkpoint 或 Agent Runtime。

## 4. 实施边界结论

- 现有 OpenSpec 任务已经把通用 Runtime、Skill Registry/Loader、Agent Spec Loader、子 Agent 调度器、Tool Registry、Checkpoint 和 Human-in-the-Loop 排除在自研范围外，无需删除额外重复任务。
- T6B 生产代码只增加框架配置、业务契约与持久化、LoreDock `ToolCallback`、运行前预检、安全状态/事件投影和 Web 接口。
- 任意 Shell、HTTP、数据库管理、文件系统写入和正式知识发布 Tool 均不进入候选集合；官方示例展示这些 Tool 不代表 LoreDock 获得授权。
- 暂停、指导恢复和重启恢复的真实性必须由后续真实 Agent Framework + PostgreSQL 集成测试证明，不能以本核验记录代替任务 1.4 和 2.4 的验收。
