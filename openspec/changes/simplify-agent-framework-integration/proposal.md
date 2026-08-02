## Why

当前 Agent 模块同时存在 T6A 自定义 Skill 加载/运行时包装与 T6B 框架原生装配，两条路径重复且行为不一致；知识整理还没有真正接通 Skill 渐进式 Tool 披露、调用上限和 Checkpoint 重启恢复。现在需要在继续 T8 之前收敛到项目锁定的 Spring AI Alibaba 1.1.2.3，避免后续能力建立在失效装配、伪造统计和重复运行时之上。

## What Changes

- 直接使用框架 `FileSystemSkillRegistry`/`ClasspathSkillRegistry`、`SkillsAgentHook`、`ToolCallbackProvider`/`ToolCallbackResolver` 和 `TaskToolsBuilder` 装配 Agent，删除 T6A 自定义 Skill Provider、正则 Front Matter 校验和通用 `AgentRuntime` 包装层。
- 让知识整理业务 Tool 通过框架 `groupedTools` 或 `allowed_tools + ToolCallbackResolver` 在 `read_skill` 后按需挂载，不再把全部业务 Tool 静态暴露给协调 Agent。
- 为项目问答和知识整理统一接入框架 `ModelCallLimitHook`、`ToolCallLimitHook`、模型/Tool/流式 Interceptor 与立即失败的 Tool 异常处理，记录真实调用次数并停止失败后的无效循环。
- 每个新 run 使用框架 Registry/Spec 形成运行内稳定定义；暂停恢复沿用稳定 `threadId` 和 `PostgresSaver`，旧短运行恢复器不得终结可恢复知识任务。
- 删除未使用的 Hook/Resolver Bean 和空配置 Human-in-the-loop；外部“安全步骤后暂停”继续使用框架 `InterruptionHook`，只有规格要求某个 Tool 审批时才配置 `HumanInTheLoopHook.approvalOn`。
- 将知识整理 Tool 改为 Spring AI 方法 Tool 注册或保留等价的最小 `FunctionToolCallback`，统一由标准 Provider/Resolver 暴露；LoreDock 仍保留 ToolContext 范围、权限、幂等、草稿和引用业务规则。
- 用生产装配测试验证动态 Tool 披露、第一次 Tool 失败即终止、真实资源统计、Checkpoint 暂停与重启恢复，并删除只证明测试代码手工装配成功的弱测试。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `agent-runtime`: 运行必须直接依赖框架原生 Agent、Hook、Interceptor、Tool 解析和 Checkpoint；短运行与可恢复长运行按任务类型执行不同重启语义。
- `project-qa-agent`: `project_qa` 改用框架 Skill/Tool 装配，Tool 故障必须在第一次失败后停止，不再消耗剩余模型调用预算。
- `knowledge-curation-agent`: Skill 激活后才挂载对应业务 Tool，运行定义与摘要一致，调用限制和统计来自真实框架执行，暂停恢复经过真实 Checkpoint。

## Impact

- 影响 `backend` 的 Agent 配置、项目问答执行、知识整理执行、Skill/Agent Spec 加载、启动恢复和相关测试。
- 删除 `AgentRuntime`、`AgentDefinitionProvider`、`ClasspathAgentDefinitionProvider`、`ProjectQaSkillValidator` 等重复结构，并相应更新架构测试与文档。
- 不改变现有 HTTP/API DTO、数据库表结构、正式知识发布边界、业务 Tool 名称或草稿契约；不新增第三方依赖和基础设施。
- 框架原生能力：ReAct/model-tool 循环、Skill Registry/Loader、渐进 Tool 披露、Agent Spec/子 Agent、Hook/Interceptor、Checkpoint、interrupt；LoreDock 扩展：固定项目范围、ToolContext 权限、幂等、证据/引用、草稿修订与页面状态投影。确需保留的自定义缺口只有框架静默跳过未知 Agent Spec Tool 时的确定性预检，以及业务结果/引用校验。
