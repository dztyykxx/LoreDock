# Spring AI Alibaba Agent 框架能力核验

## 1. 核验范围与结论

核验日期：2026-08-02。

LoreDock 当前锁定 Spring AI `1.1.2`、Spring AI Alibaba `1.1.2.3`。本次同时检查了 `backend/pom.xml`、本机 Maven 源码包和官方资料，结论是：项目问答与知识整理需要的通用 Agent 运行能力均应直接使用框架，LoreDock 不应再维护通用 `AgentRuntime`、Skill Provider、Front Matter Parser、Agent Spec Loader、Tool Registry、模型/Tool 循环或 Checkpoint 实现。

本地核验源码包：

- `~/.m2/repository/com/alibaba/cloud/ai/spring-ai-alibaba-agent-framework/1.1.2.3/spring-ai-alibaba-agent-framework-1.1.2.3-sources.jar`
- `~/.m2/repository/com/alibaba/cloud/ai/spring-ai-alibaba-graph-core/1.1.2.3/spring-ai-alibaba-graph-core-1.1.2.3-sources.jar`
- `~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.2/spring-ai-model-1.1.2-sources.jar`

官方资料：

- [Spring AI Alibaba Skills](https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/)
- [Spring AI Alibaba Hooks 与 Interceptors](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/)
- [Spring AI Alibaba Human-in-the-Loop](https://java2ai.com/en/docs/frameworks/agent-framework/advanced/human-in-the-loop/)
- [Spring AI Alibaba Graph 持久化](https://java2ai.com/en/docs/frameworks/graph-core/core/persistence/)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Alibaba 发行说明](https://github.com/alibaba/spring-ai-alibaba/releases)

## 2. 能力对照

| 能力 | 锁定版本原生组件 | LoreDock 处理 |
|---|---|---|
| ReAct 模型/Tool 循环 | `ReactAgent`、`streamMessages` | 直接使用；不包装第二层 Runtime |
| classpath Skill | `ClasspathSkillRegistry`、`SkillsAgentHook` | `project-qa` 直接加载 |
| 文件系统 Skill | `FileSystemSkillRegistry`、`SkillsAgentHook` | 每个知识整理 run 创建独立 Registry/Hook |
| Skill 渐进披露 | `read_skill`、`SkillsInterceptor`、`groupedTools` | 业务 Tool 按服务端 Skill 分组，激活前不暴露 |
| Tool 注册与解析 | `ToolCallback`、`ToolCallbackProvider`、`ToolCallbackResolver` | 只提供安全业务 Callback，不建注册框架 |
| 子 Agent | `AgentSpecLoader`、`AgentSpecReactAgentFactory`、`TaskToolsBuilder`、`TaskTool`、`AgentTool` | 文件化 Spec 直接装配，只补安全预检 |
| 模型/Tool 限制 | `ModelCallLimitHook`、`ToolCallLimitHook` | 直接配置服务端上限 |
| 真实调用观察 | `ModelInterceptor`、`ToolInterceptor`、`StreamingModelInterceptor` | 只累计业务运行统计并投影公开增量 |
| Tool 失败传播 | `DefaultToolExecutionExceptionProcessor.alwaysThrow(true)` | 第一次业务 Tool 失败即终止并映射稳定业务错误 |
| 安全暂停 | `InterruptionHook` | 在安全节点请求中断，不自行中断半次调用 |
| 逐 Tool 人工审批 | `HumanInTheLoopHook.approvalOn` | 仅在规格明确某个 Tool 需要审批时配置；空 Hook 没有业务效果 |
| 长任务状态 | `PostgresSaver`、`RunnableConfig.threadId` | 使用稳定 threadId；业务表只保存页面状态与结果投影 |

`SkillsAgentHook` 内部会为具体 Agent 安装 `SkillsInterceptor`，其 `groupedTools` 在 `read_skill` 后加入对应 Tool。Hook 基类保存其绑定的 `ReactAgent`，因此 Hook 必须按 Agent 创建，不能作为会被多个运行重绑的共享单例。

## 3. 已确认的框架缺口

以下内容仍由 LoreDock 实现，但它们是明确的业务或锁定版本缺口，不是另一套 Agent 框架：

1. Agent Spec 安全预检。`AgentSpecLoader` 遇到无效文件会记录告警并跳过，`AgentSpecReactAgentFactory` 对未知 Tool 只会从候选集合中过滤；LoreDock 必须在模型调用前确定性拒绝未知或越权 Tool。
2. 整个 run 的统一截止时间。锁定版本有模型调用和 Tool 调用限制，但没有覆盖模型、Tool、业务投影全过程的统一总超时 Hook；应用对框架流施加 Reactor 截止时间。
3. 业务安全边界。操作者、项目、分支、知识状态、参数上限、来源、幂等键、草稿修订和正式发布门禁属于 LoreDock 业务规则，继续放在业务 Service/Tool 内。
4. 可信结果边界。结构化输出解析、证据台账、引用校验、拒答规则、公开事件和页面状态是产品行为，不能交给模型或通用框架猜测。
5. 启动恢复分流。框架负责 Checkpoint；LoreDock 只决定不可恢复的 `project_qa` 如何终结，以及可恢复知识任务如何映射页面状态，不读取或复制 Graph 内部状态。

## 4. 本次收敛后的约束

- `project_qa` 是业务任务类型，实际 classpath Skill 名称为 `project-qa`；知识整理 Skill 名称为 `knowledge-curator`。
- 项目问答直接使用 `ProjectQaAgentExecutor`，知识整理直接使用 `KnowledgeCurationRunExecutor`；不再保留通用 `AgentRuntime` 转发接口。
- 项目问答当前只注册 `knowledge_search`；代码能力未接入时不得在 Skill 或现行规格中宣称可调用代码 Tool。
- 知识整理接受和执行复用同一个定义快照，运行内 `autoReload(false)`；新 run 才扫描新定义，防止摘要与实际执行不一致。
- `BoundedAgentRunScheduler` 只承担 Web 请求后的有界线程分发；子 Agent 调度由框架 Task Tool 负责，两者不得混为一层。
