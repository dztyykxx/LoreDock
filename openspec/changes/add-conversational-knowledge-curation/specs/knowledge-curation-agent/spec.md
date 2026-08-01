## ADDED Requirements

### Requirement: 知识整理必须复用 Spring AI Alibaba 原生 Agent 运行时
系统 SHALL 在需求确定和规格设计阶段先核对项目锁定版本的 Spring AI Alibaba Agent Framework 能力，并 SHALL 直接使用框架原生组件承载通用 Agent 运行能力：使用 `FileSystemSkillRegistry`、`SkillsAgentHook` 或 `SkillsInterceptor` 加载 Skill，使用 `AgentSpecLoader` 和 `AgentSpecReactAgentFactory` 装配 Agent Spec，使用 `TaskToolsBuilder`、`TaskTool` 和 `AgentTool` 组织委派，使用 `ToolCallback`、`ToolCallbackProvider` 和 `ToolCallbackResolver` 暴露 LoreDock 业务 Tool，使用 Hook 提供调用限制与人工介入，并使用 `PostgresSaver`、`RunnableConfig.threadId`、Graph interrupt 和 Human-in-the-loop 保存与恢复长任务。LoreDock MUST 只实现业务 Tool、范围和权限校验、幂等、业务持久化以及面向页面的安全事件投影；除非规格记录经验证的框架能力缺口，否则 MUST NOT 自建 Agent Runtime、Skill Registry/Loader、Agent Spec Loader、子 Agent 调度器、通用 Tool Registry、Checkpoint 或人工介入框架。

#### Scenario: 需求确定时框架已覆盖运行能力
- **WHEN** T6B 需求涉及 Skill 加载、Agent Spec 装配、子 Agent 委派、Tool 调用、暂停恢复或 Checkpoint
- **THEN** 规格明确映射到项目锁定版本的框架组件，实施任务只保留框架配置、薄适配、LoreDock 业务 Tool 和安全约束，不产生对应的自研运行时任务

#### Scenario: 本地 Skill 或 Agent Spec 修改后开始新运行
- **WHEN** 管理员修改本地 Skill 或 Agent Spec，随后启动新的知识整理 run
- **THEN** 系统通过框架加载机制取得新定义并创建 run，正在执行的 run 不被中途替换定义

#### Scenario: Agent Spec 声明未知 Tool
- **WHEN** Agent Spec 引用了未注册或不在允许集中的 Tool 名称
- **THEN** LoreDock 在启动 run 前执行确定性预检并明确拒绝启动，不依赖框架默认忽略未知 Tool 的行为，也不另建一套 Tool Registry

### Requirement: 知识整理必须以系统或人工触发的长期会话运行
系统 SHALL 为每次手动或定时知识整理创建项目范围内的知识任务会话，并 SHALL 把触发类型、触发原因、目标 Skill 和范围作为首条系统消息。会话 SHALL 关联可见消息、一个或多个独立 Agent run、安全过程事件、来源和当前草稿；定时调度器 MUST 只负责幂等触发会话，不得复制 Skill 的检索、冲突分析或草稿修改逻辑。

#### Scenario: 定时任务触发知识整理
- **WHEN** 每周调度器为一个已启用项目触发 `knowledge_curator`
- **THEN** 系统创建以 `SYSTEM_TRIGGER` 开头的可查看会话和独立 run，用户能观察与手动任务相同的 Agent/Tool/来源过程

#### Scenario: 相同调度幂等重试
- **WHEN** 调度器因响应不确定以相同项目、计划窗口和幂等键重试
- **THEN** 系统返回原会话和 run，不重复启动 Agent 或创建第二份草稿

### Requirement: 用户必须能够在安全步骤边界暂停并指导长任务
管理员 SHALL 能对运行中的知识任务请求暂停。`PAUSE_REQUESTED` SHALL 是 LoreDock 对框架执行状态的页面投影，而不是另一套执行状态机；系统 MUST 等待当前模型或 Tool 步骤完成、结果安全提交，并通过 Graph interrupt、Human-in-the-loop、`PostgresSaver` 和稳定的 `RunnableConfig.threadId` 写入可读取 Checkpoint 后，再投影为 `WAITING_FOR_USER`。用户指导 MUST 在等待状态作为会话消息提交，并 SHALL 作为框架恢复输入加入后续 Agent 执行；系统 MUST NOT 把消息注入正在执行的调用、把未完成步骤伪装成已暂停，或自行实现与框架并行的 Checkpoint/恢复机制。

#### Scenario: Tool 执行期间请求暂停
- **WHEN** 管理员在知识读取 Tool 执行期间点击暂停
- **THEN** 页面显示“将在当前步骤完成后暂停”，系统提交该 Tool 的真实结果和 Checkpoint 后进入等待，再允许提交指导

#### Scenario: 暂停后加入整理方向
- **WHEN** 运行处于 `WAITING_FOR_USER` 且管理员要求“优先核对适用版本，不要直接合并”
- **THEN** 指导作为用户消息持久化，run 使用相同 threadId 从 Checkpoint 恢复，后续 Tool 和草稿修订可追溯到该指导

#### Scenario: 当前步骤失败
- **WHEN** 暂停请求后当前模型或 Tool 步骤超时或失败且没有形成可靠 Checkpoint
- **THEN** run 保留真实失败状态，不显示为等待人工，也不执行未经确认的恢复

### Requirement: Agent 必须通过版本化 Tool 增量修改草稿
系统 SHALL 把待审核草稿作为独立版本化产物，并 SHALL 通过 Spring AI `ToolCallback` 机制暴露草稿业务能力。Agent MUST 先通过 `draft_read` 获取当前修订和服务端区块 ID，再通过 `draft_update` 提交基础修订号、调用幂等键、有界的 `insert_after|replace_block|delete_block` 操作及 `sourceRefs`。来源可引用本轮 evidenceId 或当前会话用户消息；新增项目事实 MUST 至少引用有效 evidenceId，纯结构、措辞或用户明确要求的修改 MAY 引用用户消息。服务端 MUST 原子校验和应用操作，并为每次成功更新生成不可变新修订、来源关联和变更摘要。Agent 最终消息 MUST NOT 作为草稿正文，也不得使用未授权的全量覆盖或任意文件写能力。

#### Scenario: 分区块生成初始草稿
- **WHEN** Agent 为一个空草稿生成背景、冲突点和建议三个部分
- **THEN** Agent 读取空基线后通过结构化操作写入区块，服务端形成新修订；最终消息只总结修改并引用该修订

#### Scenario: 使用过期基础修订更新
- **WHEN** Agent 基于修订 3 准备更新，但管理员已提交修订 4
- **THEN** `draft_update` 返回 `DRAFT_REVISION_CONFLICT` 且不写入任何操作，Agent 必须重新读取后再决定如何修改

#### Scenario: Checkpoint 后重复 Tool 调用
- **WHEN** 恢复执行以相同幂等键重放已经成功的 `draft_update`
- **THEN** Tool 返回原修订，不创建重复修订或重复来源关系

### Requirement: 草稿审批必须基于服务端修订 Diff
系统 SHALL 为正式文档或空基线到指定草稿修订、以及任意两个草稿修订生成服务端 Markdown Diff、变更统计和截断状态。管理员发布时 MUST 指定已经审核的草稿修订；若当前修订已变化，系统 MUST 拒绝发布并要求重新查看 Diff。MVP SHALL 以整份指定修订为审批单位，不要求逐 hunk 接受或三方合并。

#### Scenario: 审核知识更新
- **WHEN** 管理员打开基线正式文档到草稿修订 5 的审批页
- **THEN** 页面展示服务端生成的增删 Diff、来源和变更摘要，确认后只发布修订 5

#### Scenario: 审核期间草稿变化
- **WHEN** 管理员正在审核修订 5，但 Agent 随后产生修订 6
- **THEN** 发布修订 5 的请求被明确拒绝或要求重新确认，系统不得静默发布未查看的修订 6

### Requirement: Agent 完成后任务会话必须允许继续调整
一次 Agent run 完成后，知识任务会话 SHALL 保持可继续，当前草稿修订和历史消息/事件 SHALL 保持不变。管理员追加调整意见时，系统 MUST 创建新的 Agent run，以当前草稿修订和受限会话消息作为输入，并通过新的 `draft_update` 生成后续修订；不得回退或覆盖既有 run、消息、来源和修订。

#### Scenario: 完成后要求缩小结论
- **WHEN** 首轮整理完成后管理员追加“删除没有双来源支持的建议”
- **THEN** 系统创建新 run，Agent 读取当前修订并通过 Tool 形成下一修订，页面保留首轮过程并展示新的 Diff

#### Scenario: 完成后无需修改
- **WHEN** 新 run 复核后认为当前草稿已满足指导且没有必要更新
- **THEN** Agent 可以只提交公开结论而不调用 `draft_update`，系统不制造空修订或伪造文档变化
