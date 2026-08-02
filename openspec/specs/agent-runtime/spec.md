# agent-runtime Specification

## Purpose

为 Web 问答及后续文档工作流提供统一、受控且可追溯的服务端 Agent 执行契约，使模型与 Skill 可以替换或版本化，同时不能绕过项目范围、工具权限、运行上限和人工发布边界。

## Requirements

### Requirement: 运行必须固定 Agent 定义、模型和业务范围事实
系统 SHALL 通过项目锁定版本的 Spring AI Alibaba Agent Framework 启动任务。启动输入 MUST 包含服务端生成或校验的幂等键、已认证操作者、任务类型和该任务要求的业务范围；系统 MUST 在首次接受请求时生成稳定运行 ID，并固定本次运行使用的 Skill 内容摘要、Agent Spec 摘要、模型名称、允许 Tool 名称、运行限制和解析后的项目、分支、代码快照及知识索引范围。内置 Skill SHALL 由框架 `ClasspathSkillRegistry` 或 `FileSystemSkillRegistry` 加载，文件化子 Agent SHALL 由框架 Agent Spec 组件加载；LoreDock MUST NOT 另建通用 Agent Runtime、Skill Provider、Front Matter Parser、Agent Spec Loader 或 Tool Registry。系统 MUST 确保实际执行定义与记录摘要一致，当前运行和暂停后的恢复不得因后续文件 reload 静默替换定义。

#### Scenario: 启动内置项目问答运行
- **WHEN** 已认证调用方以新幂等键提交合法的 `project_qa` 任务
- **THEN** 系统使用框架当前 classpath Skill 创建运行，固定模型、允许 Tool、限制和业务范围事实，并返回稳定运行 ID 与初始状态

#### Scenario: 相同幂等键重复提交相同请求
- **WHEN** 同一操作者使用相同幂等键重复提交业务输入完全相同的启动请求
- **THEN** 系统返回原运行及其当前状态，不重复调用模型、不重复执行工具也不创建第二份运行记录

#### Scenario: 相同幂等键提交不同请求
- **WHEN** 同一操作者使用已存在的幂等键提交不同任务、问题或业务范围
- **THEN** 系统拒绝请求并返回 `AGENT_RUN_IDEMPOTENCY_CONFLICT`，原运行保持不变

#### Scenario: 内置 Agent 定义不可用
- **WHEN** 请求的任务类型没有可加载且校验通过的 classpath Agent 定义
- **THEN** 系统不调用模型并以 `AGENT_SKILL_UNAVAILABLE` 或等价稳定错误拒绝启动

#### Scenario: 定义文件在运行期间改变
- **WHEN** 一个运行已经固定定义摘要后本地 Skill 或 Agent Spec 文件发生变化
- **THEN** 当前运行继续使用与摘要一致的定义，新文件只影响后续新运行；无法按原摘要安全重建的暂停运行不得用新定义恢复

### Requirement: 模型调用必须通过框架原生且受控的运行边界
系统 SHALL 直接通过 Spring AI Alibaba `ReactAgent` 与 Spring AI 标准 `ChatModel` 执行同步或流式模型调用，并 SHALL 使用框架 Hook/Interceptor 处理模型调用上限、Tool 调用上限、流式观察、用量观察和错误传播。生产 SHALL 支持 OpenAI 兼容模型，测试 SHALL 通过标准 `ChatModel` 替身脚本化模型回复、工具请求、Token 用量、延迟与失败；系统 MUST NOT 为单一框架实现保留通用 Agent Runtime 转发接口或包装 ChatModel 来复制框架 Interceptor 能力。模型请求 MUST 仅包含本次运行固定的系统规则、Skill、用户输入和经服务端裁剪的证据；模型密钥、内部端点和其他运行数据不得进入提示内容或公开事件。

#### Scenario: 生产模型流式返回
- **WHEN** DeepSeek `deepseek-v4-flash` 兼容模型按配置正常返回文本增量、工具请求和用量信息
- **THEN** 框架流式 API 与 Interceptor 把允许公开的增量、实际调用次数和可得用量交给业务投影，最终结果继续经过业务结构与引用校验

#### Scenario: 测试模型驱动确定性工具循环
- **WHEN** 标准 `ChatModel` 测试替身按脚本依次请求允许工具并返回最终结果
- **THEN** `ReactAgent` 执行可重复的相同步骤、工具摘要、引用和终态，不需要自定义 Runtime 替身或真实模型凭据

#### Scenario: 模型暂时不可用
- **WHEN** 模型连接失败、限流、鉴权失败或返回不可解析响应
- **THEN** 系统以脱敏的 `AGENT_MODEL_UNAVAILABLE` 或 `AGENT_MODEL_RESPONSE_INVALID` 结束运行并保留失败记录，既有非 Agent 能力继续可用

### Requirement: 工具执行必须由服务端白名单和固定范围授权
每个 Skill SHALL 只能看到并调用其服务端允许的内部工具。支持渐进式 Tool 披露的 Skill MUST 先由模型通过框架 `read_skill` 激活，框架再通过服务端 `groupedTools` 或 `allowed_tools + ToolCallbackResolver` 挂载对应业务 Tool；协调 Agent MUST NOT 在 Skill 激活前静态获得全部业务 Tool。工具执行器 MUST 在每次调用前校验工具名、操作者权限、运行固定的项目与分支、允许的知识范围、参数类型和服务端上限；模型、Skill、用户问题或检索材料 MUST NOT 指定其他项目/分支、历史 generation、服务器路径或扩大工具权限。

#### Scenario: 激活 Skill 后调用白名单 Tool
- **WHEN** Agent 读取一个已授权 Skill 并请求该 Skill 服务端分组中的业务 Tool
- **THEN** 框架把该 Tool 加入当前运行，业务 Tool 复用既有应用服务并只返回固定范围内的有限结果

#### Scenario: Skill 激活前请求业务 Tool
- **WHEN** 协调 Agent 在未读取对应 Skill 时请求其业务 Tool
- **THEN** 该 Tool 不在本轮可调用集合中且不会执行

#### Scenario: 调用白名单内的只读工具
- **WHEN** `project_qa` 在固定项目与分支内以合法参数调用声明的 `knowledge_search`
- **THEN** 系统复用知识搜索应用服务执行查询，记录有限工具摘要，并只返回该运行范围内的有限结果

#### Scenario: 模型尝试跨范围调用
- **WHEN** 模型工具参数包含另一项目、另一分支、历史 generation、服务器路径或超出上限的读取范围
- **THEN** 工具在访问底层检索前拒绝调用，以 `AGENT_TOOL_SCOPE_VIOLATION` 终止运行，且响应和事件不暴露越权目标是否存在

#### Scenario: 模型请求白名单外工具
- **WHEN** 模型请求未在当前 Skill 白名单中的工具或尝试执行 Shell、任意网络访问、数据库管理、知识修改或发布
- **THEN** 系统不执行该调用，以 `AGENT_TOOL_NOT_ALLOWED` 终止运行并记录工具名摘要和失败语义

#### Scenario: 不可信材料包含操作指令
- **WHEN** 知识、用户问题或 Skill 示例中包含要求更改项目范围、泄露提示、调用外部网络或发布知识的指令
- **THEN** 系统只把材料作为有限证据文本处理，结构化权限与工具校验保持不变

### Requirement: 每次运行必须受到硬性资源限制
系统 SHALL 为每次运行固定最大 Agent 步骤数、最大模型调用次数、总超时、单次检索结果数、单次片段长度和总上下文长度。模型与 Tool 调用次数 MUST 由框架 Hook/Interceptor 的实际执行事实计算，客户端与模型 MAY 请求更小的值但 MUST NOT 提高服务端上限；系统 MUST NOT 写入固定占位统计。达到任何上限时系统 MUST 停止后续模型和工具调用，以对应稳定错误码安全结束，并保留截止前已完成的只读证据摘要。

#### Scenario: 在全部限制内完成
- **WHEN** 运行在步骤、模型调用、时间、检索和上下文限制内得到有效结果
- **THEN** 系统完成运行并记录实际步骤数、模型调用数、Tool 调用数、检索数量、上下文裁剪量、可得 Token 和耗时

#### Scenario: 达到步骤或模型调用上限
- **WHEN** 模型继续请求工具或推理步骤将超过最大步骤数或模型调用次数
- **THEN** 框架 Hook 不执行超限调用，系统以 `AGENT_STEP_LIMIT_EXCEEDED` 或 `AGENT_MODEL_CALL_LIMIT_EXCEEDED` 安全终止

#### Scenario: 业务 Tool 第一次失败
- **WHEN** 已授权业务 Tool 因范围变化、证据版本变化或基础设施异常失败
- **THEN** 框架立即传播原始业务失败，系统不把异常转换成可继续推理的 Tool 文本，也不继续消耗剩余模型调用预算

#### Scenario: 达到总超时
- **WHEN** 运行到达固定截止时间且模型或工具尚未完成
- **THEN** 系统取消可取消的后续工作，以 `AGENT_RUN_TIMEOUT` 结束，并忽略截止时间后到达的模型文本或工具结果

#### Scenario: 候选或上下文超过限制
- **WHEN** 工具或模型准备阶段产生超过检索条数、片段长度或总上下文长度的内容
- **THEN** 系统按稳定规则裁剪并记录裁剪数量；若裁剪后无法保留可引用证据，则不生成无依据回答

### Requirement: 运行记录和公开事件必须可追溯且不暴露隐藏推理
系统 SHALL 持久化运行状态、任务类型、解析范围、模型和定义摘要、阶段、可引用证据、真实模型/Tool 调用数量、实际可得的模型用量、耗时、结果类型和脱敏错误。状态 MUST 只按 `ACCEPTED → RUNNING → COMPLETED|FAILED|TERMINATED` 前进，`COMPLETED` 的结果类型 MUST 明确区分 `ANSWER` 与 `REFUSAL`，终态不得回退。公开事件 SHALL 带运行 ID、单调递增序号、事件类型和服务端时间，支持调用方从已知序号读取后续事件；持久化事件只表达接受、开始、必要进度、发现来源、完成、失败和终止等对外有意义的阶段，不要求复制每个模型分片、工具内部步骤、Hook 或 Graph 节点。

Agent 运行记录、公开事件与结构化日志 MUST NOT 保存或公开模型原始思维链、完整系统提示、完整知识/代码正文、模型密钥、内部端点、对象键、服务器绝对路径或连接信息。工具参数和错误只保存稳定标识、长度、数量、范围及脱敏摘要；模型没有返回 Token 用量时 MUST 记录为未知，不得伪造为零。被标记为可恢复的长运行 MUST 保留框架 `PostgresSaver` Checkpoint 和稳定 `threadId`，后端重启后只能从最后已提交 Checkpoint 继续或保持等待指导，不得被短运行恢复逻辑终结；未标记为可恢复的短运行在重启后 MUST 以稳定错误单调终结。重新调度和页面状态映射属于 LoreDock 业务投影，不得复制 Graph Checkpoint。

面向已认证用户的 Web 问答能力 MAY 在独立于 Agent 运行追踪的受授权消息存储中保存用户主动提交的完整问题，以及通过引用校验后允许公开的最终回答或拒答，以支持问答历史、刷新恢复和知识缺口追溯。该存储 MUST 执行项目与操作者访问控制、正文长度限制和审计约束，不得保存隐藏提示、模型思维链或完整证据正文。

#### Scenario: 完成带证据检索的运行
- **WHEN** Agent 调用允许的只读工具并形成回答或拒答
- **THEN** 运行快照、粗粒度事件和证据能够证明固定范围、实际阶段、来源标识、引用、Token 可用性、耗时和最终结果，且不包含隐藏推理、完整证据正文或逐分片事件副本

#### Scenario: 从已知序号继续读取事件
- **WHEN** 下游调用方携带某运行已消费的最后事件序号重新读取
- **THEN** 系统按序返回更大的持久化公开事件，不重复更早事件，并以当前运行快照作为终态事实来源

#### Scenario: 运行失败后收到迟到结果
- **WHEN** 运行已经进入失败或终止状态后模型或工具仍返回迟到内容
- **THEN** 系统不得改变终态或把迟到内容作为回答发布，只记录脱敏的忽略结果摘要

#### Scenario: 可恢复长运行期间后端重启
- **WHEN** 后端重启时发现一个声明为可恢复且已经写入有效 Checkpoint 的 Agent 运行
- **THEN** 系统从最后已提交 Checkpoint 继续运行，不重复已经提交的工具副作用或业务记录

#### Scenario: 不可恢复短运行期间后端重启
- **WHEN** 后端重启时发现一个未声明为可恢复的 `ACCEPTED` 或 `RUNNING` 运行
- **THEN** 系统以 `AGENT_RUN_INTERRUPTED` 或等价稳定错误将其单调终结并保留既有运行事实

#### Scenario: 敏感内容进入运行输入或错误
- **WHEN** 输入、证据、模型响应或异常包含密钥样式、内部地址、完整正文或服务器路径
- **THEN** Agent 运行记录、结构化日志和公开事件只保留允许字段或脱敏摘要，敏感原文不被写入这些追踪边界

#### Scenario: Web 问答保存用户可见消息
- **WHEN** 已认证用户通过 T7 Web 问答提交问题并得到经引用校验的回答或拒答
- **THEN** 独立消息存储可以保存该用户可见问题与最终产物供授权恢复，但运行记录和日志仍只保存必要摘要，且任何边界都不保存隐藏提示、思维链或完整证据正文

### Requirement: Agent 不得越过正式知识发布边界
T6A Agent SHALL 只执行授权的只读证据查询并产生临时回答或拒答。除运行追踪元数据外，它 MUST NOT 创建、编辑、归档、替代、索引或发布知识文档，也 MUST NOT 修改代码快照、项目、分支、账号或系统配置；后续新增写工具时也必须由独立规格和人工审核边界授权。

#### Scenario: 正常问答完成
- **WHEN** `project_qa` 生成带引用回答或拒答
- **THEN** 系统只持久化运行、事件、工具摘要和引用元数据，不改变任何正式知识、索引、代码快照或项目配置

#### Scenario: 模型输出伪造发布指令
- **WHEN** 模型在文本或工具请求中声称已批准、发布或修改正式知识
- **THEN** 系统不执行任何业务写入，且该文本不能改变正式知识状态或检索资格
