## MODIFIED Requirements

### Requirement: 知识整理必须复用 Spring AI Alibaba 原生 Agent 运行时
系统 SHALL 直接使用项目锁定版本的 Spring AI Alibaba Agent Framework 承载通用 Agent 运行能力：使用 `FileSystemSkillRegistry` 和每个 Agent 独立的 `SkillsAgentHook` 加载运行内稳定 Skill，使用 `AgentSpecLoader`、`AgentSpecReactAgentFactory` 和 `TaskToolsBuilder` 装配文件化子 Agent，使用 `ToolCallbackProvider`/`ToolCallbackResolver` 暴露 LoreDock 业务 Tool，使用框架 Hook/Interceptor 提供真实调用限制、统计和人工介入，并使用 `PostgresSaver`、稳定 `RunnableConfig.threadId` 与 Graph interrupt 保存和恢复长任务。协调 Agent MUST 先通过框架 `read_skill` 激活目标 Skill，框架再按服务端 `groupedTools` 或已预检的 `allowed_tools` 挂载对应业务 Tool；系统 MUST NOT 在激活前静态挂载全部业务 Tool。LoreDock MUST 只实现业务 Tool、范围和权限、幂等、业务持久化、Agent Spec 安全预检以及页面状态投影，不得自建或包装通用 Runtime、Registry/Loader、子 Agent 调度、Tool Registry、Checkpoint 或人工介入框架。

#### Scenario: 新运行加载当前定义并保持运行内稳定
- **WHEN** 管理员修改本地 Skill 或 Agent Spec 后启动新的知识整理 run
- **THEN** 系统通过框架加载当前定义，记录与实际执行一致的摘要；后续文件变化不会替换该 run 或其暂停恢复使用的定义

#### Scenario: 读取 Skill 后获得业务 Tool
- **WHEN** 协调 Agent 调用框架 `read_skill` 激活知识整理 Skill
- **THEN** 框架只把服务端分组到该 Skill 的知识搜索、来源读取、草稿和发现项 Tool 加入当前 run，且不加入 Shell、任意 HTTP、文件系统、数据库管理或正式发布 Tool

#### Scenario: 未读取 Skill 时请求业务 Tool
- **WHEN** 协调 Agent 尚未激活知识整理 Skill
- **THEN** 业务 Tool 不在模型可调用集合中，模型不能绕过 Skill 直接修改草稿

#### Scenario: Agent Spec 声明未知 Tool
- **WHEN** Agent Spec 引用了未注册或不在允许集中的 Tool 名称
- **THEN** LoreDock 在模型调用前明确拒绝启动，不依赖框架静默忽略未知 Tool，也不另建 Tool Registry

#### Scenario: 运行达到框架限制
- **WHEN** 知识整理运行将超过固定模型调用、Tool 调用或总时间限制
- **THEN** 框架停止后续调用，业务运行记录保存对应稳定错误和已经发生的真实调用数量，不写入占位统计

### Requirement: 用户必须能够在安全步骤边界暂停并指导长任务
管理员 SHALL 能对运行中的知识任务请求暂停。`PAUSE_REQUESTED` SHALL 是 LoreDock 对框架执行状态的页面投影，而不是另一套执行状态机；系统 MUST 等待当前模型或 Tool 步骤完成并通过 `InterruptionHook`、`PostgresSaver` 和稳定 `RunnableConfig.threadId` 写入可读取 Checkpoint 后，再投影为 `WAITING_FOR_USER`。只有对具体 Tool 配置了 `HumanInTheLoopHook.approvalOn` 时系统才 SHALL 产生逐 Tool 审批；空配置 HITL 不得作为暂停已实现的依据。用户指导 MUST 在等待状态作为会话消息提交，并作为框架恢复输入加入同一 run；系统 MUST NOT 把消息注入正在执行的调用、自行保存 Graph 状态或让短运行重启恢复器终结可恢复知识任务。

#### Scenario: Tool 执行期间请求暂停
- **WHEN** 管理员在知识读取 Tool 执行期间点击暂停
- **THEN** 页面显示将在当前步骤完成后暂停，系统提交该 Tool 结果和真实 Checkpoint 后进入等待，再允许提交指导

#### Scenario: 暂停后加入整理方向
- **WHEN** 运行处于 `WAITING_FOR_USER` 且管理员提交新的整理方向
- **THEN** 指导作为用户消息持久化，run 使用相同 threadId 和与摘要一致的定义从 Checkpoint 恢复

#### Scenario: 后端在暂停期间重启
- **WHEN** 已保存 Checkpoint 的知识整理 run 在后端重启后仍处于等待或可恢复状态
- **THEN** 系统保留该 run 和 threadId，不把它按不可恢复短运行终结，并可在收到指导后从 Checkpoint 恢复

#### Scenario: 当前步骤失败
- **WHEN** 暂停请求后当前模型或 Tool 步骤超时或失败且没有形成可靠 Checkpoint
- **THEN** run 保留真实失败状态，不显示为等待人工，也不执行未经确认的恢复
