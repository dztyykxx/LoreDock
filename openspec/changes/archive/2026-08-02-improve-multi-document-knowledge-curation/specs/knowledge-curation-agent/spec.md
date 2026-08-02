## MODIFIED Requirements

### Requirement: 知识整理必须复用 Spring AI Alibaba 原生 Agent 运行时
系统 SHALL 直接使用项目锁定版本的 Spring AI Alibaba Agent Framework 承载通用单 Agent 模型/Tool 循环：使用 `ClasspathSkillRegistry` 和每个 run 独立的 `SkillsAgentHook` 加载随应用发布的稳定 Skill，使用 `ToolCallbackProvider`/`ToolCallbackResolver` 暴露 LoreDock 业务 Tool，并使用框架 Hook/Interceptor 提供真实调用限制和统计。LoreDock MUST 只实现业务 Tool、范围和权限、幂等、业务持久化、事件投影与必要的安全预检，不得自建通用 Runtime、Registry/Loader、Tool Registry 或模型/Tool 循环。当前知识整理 MUST NOT 加载 Agent Spec、Task/Agent Tool、子 Agent 或 Human-in-the-Loop；模型需要人工信息时 SHALL 返回最终 AssistantMessage 并结束当前 run。

#### Scenario: 需求确定时框架已覆盖运行能力
- **WHEN** 知识整理需求涉及 Skill 加载、模型/Tool 循环、调用限制或 Tool 解析
- **THEN** 规格明确映射到项目锁定版本的框架组件，实施任务只保留框架配置、薄适配、LoreDock 业务 Tool 和安全约束，不产生对应的自研运行时任务

#### Scenario: 本地 Skill 或 Agent Spec 修改后开始新运行
- **WHEN** 部署者修改随应用发布的知识整理 Skill，随后启动新的知识整理 run
- **THEN** 系统通过框架加载机制取得新 Skill 并创建 run，正在执行的 run 不被中途替换；当前产品不加载 Agent Spec

#### Scenario: 读取 Skill 后获得业务 Tool
- **WHEN** 知识整理 Agent 激活 `knowledge-curator` Skill
- **THEN** 框架只提供服务端批准的知识搜索、来源读取、工作区草稿和 Diff Tool，且不加入 Shell、任意 HTTP、文件系统、数据库管理、警告记录或正式发布 Tool

#### Scenario: 未读取 Skill 时请求业务 Tool
- **WHEN** Agent 尚未激活知识整理 Skill
- **THEN** 业务 Tool 不在模型可调用集合中，模型不能绕过 Skill 直接修改工作草稿

#### Scenario: Agent Spec 声明未知 Tool
- **WHEN** 外部输入尝试通过 Agent Spec、未知名称或未批准分组扩大 Tool 集合
- **THEN** LoreDock 在启动 run 前明确拒绝且不创建运行；当前知识整理不加载 Agent Spec，也不另建一套 Tool Registry

#### Scenario: 运行达到框架限制
- **WHEN** 知识整理运行将超过固定模型调用、Tool 调用或总时间限制
- **THEN** 框架停止后续调用，业务运行记录保存对应稳定错误和已经发生的真实调用数量，不写入占位统计

### Requirement: 知识整理必须以系统或人工触发的长期会话运行
系统 SHALL 为每次手动或定时知识整理创建项目范围内的知识任务，并 SHALL 把触发类型、目标 Skill、固定候选输入和整理目标作为首轮上下文。知识任务与单次 Agent run MUST 分离：任务 SHALL 使用 `PROCESSING/PUBLISHED/CLOSED_NO_CHANGE/ABANDONED` 表达业务生命周期，一个任务 SHALL 关联一个或多个顺序运行的 run、可见消息、Tool Invocation 和最多 10 份有效工作文档。单个 run 完成、失败或取消 MUST NOT 自动关闭任务。

#### Scenario: 定时任务触发知识整理
- **WHEN** 每周调度器为一个已启用项目触发 `knowledge-curator`
- **THEN** 系统创建以 `SYSTEM_TRIGGER` 开头的可查看任务和独立 run，用户能观察与手动任务相同的 Agent、Tool 和来源过程

#### Scenario: 相同调度幂等重试
- **WHEN** 调度器因响应不确定以相同项目、计划窗口和幂等键重试
- **THEN** 系统返回原任务和 run，不重复启动 Agent 或创建第二套工作区

#### Scenario: 首轮任务成功后继续指导
- **WHEN** 管理员选择合法候选输入创建任务且首轮 run 完成
- **THEN** 任务仍为 `PROCESSING`，输入框恢复可用，管理员的新消息创建独立 run；新 run 接收有界的历史用户消息与各轮最终回复，并继续读取当前工作区，但不接收历史过程消息或 Tool 调用内容

#### Scenario: Agent 需要补充信息
- **WHEN** Agent 无法在当前证据下安全决定内容
- **THEN** Agent 在普通最终回复中提出明确问题并正常结束当前 run，任务保持 `PROCESSING`，用户回复后创建新 run，不恢复旧 Checkpoint

#### Scenario: 终态任务收到继续消息
- **WHEN** 管理员尝试向 `PUBLISHED`、`CLOSED_NO_CHANGE` 或 `ABANDONED` 任务追加消息
- **THEN** 服务端拒绝请求且不创建 run，后续修改必须创建新任务

### Requirement: Agent 必须通过版本化 Tool 增量修改草稿
每个 `PROCESSING` 知识任务 SHALL 以 `conversation_id` 下的工作草稿集合构成逻辑工作区，MUST NOT 依赖单一 `current_draft_id`。工作文档 SHALL 只支持 `ADD` 与 `MODIFY`，每次有效更新 SHALL 基于当前修订并生成不可变新修订。MODIFY 创建时 MUST 固定正式 `baseline_document_id` 与 `baseline_revision`，且只允许修改正文；ADD MUST 提供标题和已有目录。Agent 每轮 SHALL 先调用 `workspace_document_list` 恢复工作区，再按需调用 `draft_read` 与 `draft_update`；系统 MUST NOT 建设会话级临时索引作为工作区事实来源。

#### Scenario: 分区块生成初始草稿
- **WHEN** Agent 为一份空的 ADD 工作草稿生成多个长期知识区块
- **THEN** Agent 读取空基线后通过结构化操作写入区块，服务端形成新修订；最终消息只总结已提交修改而不作为草稿正文

#### Scenario: 使用过期基础修订更新
- **WHEN** Agent 基于修订 3 准备更新，但当前工作修订已经变为 4
- **THEN** `draft_update` 返回 `DRAFT_REVISION_CONFLICT` 且不写入任何操作，Agent 必须重新读取后再决定如何修改

#### Scenario: Checkpoint 后重复 Tool 调用
- **WHEN** 模型或客户端重试以相同幂等键提交已经成功的 `draft_update`
- **THEN** Tool 返回原修订，不创建重复修订或重复来源关系；当前产品不依赖 Checkpoint 恢复触发该重试

#### Scenario: 第二轮恢复多文档工作区
- **WHEN** 第一轮已经新增文档 A 并修改文档 B，管理员提交第二轮指导
- **THEN** Agent 先取得 A、B 的当前草稿 ID、操作、基线和修订，并基于服务端最新修订继续修改，而不是从历史对话重建正文

#### Scenario: 修改正式文档时固定基线
- **WHEN** Agent 首次为正式文档创建 MODIFY 工作副本
- **THEN** 系统在同一事务固定该正式文档 ID、当前正式修订、标题、目录和标签，后续 Tool 不能更换基线或元数据

#### Scenario: 工作文档超过上限
- **WHEN** Agent 尝试为同一任务创建第 11 份有效工作文档
- **THEN** Tool 返回可修正的数量上限错误，不创建额外草稿，模型可以合并主题或停止新增

#### Scenario: 空草稿未形成变更
- **WHEN** Agent 创建 ADD 草稿但从未提交有效正文修订
- **THEN** 该草稿不进入本轮或累计变更，不允许进入发布集合

#### Scenario: 模型首次写入空草稿
- **WHEN** `draft_read` 返回 `blocks=[]`，模型使用 `INSERT_AFTER` 且把可选 `targetBlockId` 输出为 JSON `null` 或空白字符串
- **THEN** 服务端统一按无目标区块处理并追加首个区块；Skill 与 Tool Schema 示例仍要求优先使用 JSON `null`

### Requirement: 草稿审批必须基于服务端修订 Diff
系统 SHALL 从不可变修订事实计算每个 run 对每份工作文档的起止修订与净变化，并 SHALL 计算当前全部有效工作文档相对正式/空基线的累计变化。前端 SHALL 在每轮后展示本轮多文档变更卡，在顶部展示累计待发布审核条；点击历史卡 MUST 查看该轮起止修订 Diff，点击累计入口 MUST 查看正式/空基线到当前修订 Diff。Diff MUST 由服务端生成。

#### Scenario: 审核知识更新
- **WHEN** 管理员打开一份工作文档从正式/空基线到当前修订的累计审核视图
- **THEN** 页面展示服务端生成的增删 Diff、来源和变更摘要，发布请求只能提交管理员已经审核的当前修订

#### Scenario: 审核期间草稿变化
- **WHEN** 管理员正在审核修订 5，但 Agent 随后产生修订 6
- **THEN** 发布修订 5 的请求被明确拒绝并要求重新确认，系统不得静默发布未查看的修订 6

#### Scenario: 同一轮多次更新同一文档
- **WHEN** 一个 run 对同一工作文档生成多个中间修订
- **THEN** 本轮文件变更卡只显示一行，并比较该 run 开始前修订与最后修订的净变化

#### Scenario: 多轮后查看旧变更卡
- **WHEN** 文档在后续 run 已继续修改，管理员点击较早 run 的文件行
- **THEN** 抽屉展示该历史 run 的起止修订，不把当前累计正文冒充为旧 Patch Set

#### Scenario: 当前修订在审核后变化
- **WHEN** 管理员提交的 reviewed revision 与任一工作文档当前修订不一致
- **THEN** 整批发布被拒绝，管理员必须重新读取累计变更

### Requirement: Agent 完成后任务会话必须允许继续调整
在任务仍为 `PROCESSING` 时，系统 SHALL 在任何 run 完成、失败或取消后允许管理员提交下一条用户消息，并 SHALL 创建新的 run。运行中 MUST 禁止提交新消息；用户 MAY 请求停止当前 run，停止在安全边界后把 run 标记为 `CANCELLED`，已经提交的工作修订继续保留，后续消息 MUST 创建新 run而非恢复旧 run。

#### Scenario: 完成后要求缩小结论
- **WHEN** 首轮整理完成后管理员追加“删除没有双来源支持的建议”
- **THEN** 系统创建新 run，注入有界历史问答，Agent 读取当前工作区并通过 Tool 形成下一修订，页面保留首轮过程并展示新的 Diff

#### Scenario: 完成后无需修改
- **WHEN** 新 run 复核后认为当前工作区已满足指导且没有必要更新
- **THEN** Agent 可以只提交最终公开结论而不调用 `draft_update`，系统不制造空修订或伪造文档变化

#### Scenario: 运行中提交消息
- **WHEN** 当前 run 仍为 ACCEPTED 或 RUNNING
- **THEN** 服务端拒绝新消息且不改变现有 run，页面保持输入禁用

#### Scenario: 停止后继续
- **WHEN** 管理员请求停止且当前 run 在安全边界进入 CANCELLED
- **THEN** 页面保留已公开消息、Tool 与部分工作修订，输入恢复可用，下一条指导创建新 run

#### Scenario: 失败后继续修正
- **WHEN** run 因模型、Tool 或限制失败且已经生成部分工作修订
- **THEN** 任务仍为 PROCESSING，页面明确标记本轮未完成和保留的部分修改，管理员可以通过新 run 修正；存在未处理失败变更时不得发布

## ADDED Requirements

### Requirement: Agent 必须实时公开有限决策过程
知识整理 Skill SHALL 要求模型通过 AssistantMessage 可见正文输出面向用户的公开决策说明，通常覆盖开始计划、关键判断、准备写入和最终结果；系统 MUST NOT 为公开沟通增加消息 Tool。系统 SHALL 从每轮 `AGENT_MODEL_FINISHED` 的聚合响应持久化模型真实撰写的完整公开消息并实时通知页面，MUST NOT 把最后一个增量 token 当作最终回复，MUST NOT 根据其他 Tool 事件伪造决策文本，也 MUST NOT 读取、保存或展示原始思维链、供应商 reasoning 字段、系统提示或隐藏消息。

#### Scenario: 模型主动说明计划与原因
- **WHEN** run 已读取 Skill 且准备调用检索、读取或写入 Tool
- **THEN** 模型在包含 Tool Call 的原生 AssistantMessage 可见正文中以简短自然语言说明当前结论、主要依据或下一步，MUST NOT 机械复述每次 Tool 调用

#### Scenario: Tool 调用前包含公开说明
- **WHEN** 模型返回非空可见文本并同时请求 Tool
- **THEN** 系统把可见文本保存为该 run 的 Agent 公开决策消息并通过任务事件通知页面，Tool 调用仍作为独立 Invocation 展示

#### Scenario: 模型未提供公开说明
- **WHEN** 某次 Tool Call 没有可见文本
- **THEN** 系统不根据该 Tool 生成占位理由；模型可在后续关键节点使用原生可见 AssistantMessage 主动沟通

#### Scenario: Tool 全部执行后生成最终总结
- **WHEN** 模型已处理最后一个 Tool Response 且不再需要调用 Tool
- **THEN** ReactAgent 再次调用模型，系统从 `AGENT_MODEL_FINISHED` 聚合响应取得一条非空、无 Tool Call 的完整 AssistantMessage，作为最终总结；缺少该消息时本轮失败而不是写入固定“运行已完成”占位

#### Scenario: 模型返回原始推理字段
- **WHEN** 模型供应商响应包含独立 reasoning 内容
- **THEN** 该内容不进入知识任务消息、公开事件、日志或页面

#### Scenario: Agent 需要用户确认或发现风险
- **WHEN** Agent 无法从授权来源确定结论，或发现冲突、缺口和需要用户处理的警告
- **THEN** Agent 把警告、主要依据和具体问题统一写入最终回复并正常结束 run，MUST NOT 为此调用专用警告记录 Tool，也 MUST NOT 把“待确认事项”、面向管理员的问题或执行过程写入可发布工作文档

#### Scenario: 框架在最终回复后产生空消息
- **WHEN** 模型已返回非空最终提问，而框架流末尾又出现空 AssistantMessage
- **THEN** 系统保存最后一条非空、无 Tool Call 的模型可见正文，MUST NOT 用通用完成占位文案覆盖该提问

### Requirement: 每次 Tool 调用必须聚合为可核验 Invocation
系统 SHALL 以模型稳定 `toolCallId` 为同一 run 内 Tool Invocation 的唯一身份。调用开始、完成或失败 SHALL 更新同一 Invocation，而不是产生多张前端卡。Invocation SHALL 保存模型实际提交和实际收到的业务参数/结果、状态、用途、摘要、耗时与截断标记；输入输出分别有长度上限。系统 MUST NOT 保存或公开 ToolContext、权限、操作者、安全上下文或其他未传给模型的字段。前端 SHALL 把每个 run 的公开过程消息与 Tool Invocation 放入一个可折叠执行过程区；运行中默认展开，run 结束后默认收起，最终 AssistantMessage MUST 位于折叠区外并以不执行原始 HTML 的 Markdown 预览展示。

#### Scenario: Tool 正常完成
- **WHEN** 模型以 toolCallId 调用允许 Tool 并收到结果
- **THEN** REST 快照与 SSE 最终只表示一张 COMPLETED Invocation，展开内容与模型业务输入输出一致且不含服务端注入字段

#### Scenario: Tool 调用失败
- **WHEN** Tool 返回业务错误或抛出异常
- **THEN** 同一 Invocation 更新为 FAILED 并记录脱敏业务错误与耗时，不创建额外“工具结果”消息卡

#### Scenario: Tool 输出超过上限
- **WHEN** 模型收到的 Tool 输出超过服务端保存上限
- **THEN** Invocation 保存有界前缀并设置结果截断标记，页面明确提示截断，不声称保存了完整结果

#### Scenario: 已完成运行默认突出最终回复
- **WHEN** run 已结束且包含公开过程、多个 Tool Invocation 和最终 AssistantMessage
- **THEN** 页面默认收起整轮执行过程，只在外部展示安全 Markdown 最终回复与文档变更；管理员仍可展开过程并继续展开单次 Tool 的参数和结果

### Requirement: 知识任务页面必须通过快照与持久化事件恢复
系统 SHALL 提供知识任务聚合 REST 快照和带数据库单调序号的 SSE。快照 SHALL 包含任务、候选输入、runs、messages、Tool Invocations、每轮 Patch Set 与累计工作区摘要；SSE SHALL 通知消息、Tool、run、Patch Set、任务和发布事实变化，但 MUST NOT 推送逐 Token 内容或把前端内存作为事实来源。

#### Scenario: 首次打开任务
- **WHEN** 管理员打开一个有多轮历史的知识任务
- **THEN** 页面先从 REST 恢复完整快照，再从快照末尾事件序号订阅后续变化，不重复历史卡片

#### Scenario: SSE 断线续接
- **WHEN** 浏览器携带最后已消费事件序号重新连接
- **THEN** 服务端只按序推送更大序号的持久化事件，Tool 状态按 Invocation ID 原位合并

#### Scenario: 用户查看历史时出现新事件
- **WHEN** 用户已经向上滚动且运行产生新消息
- **THEN** 页面不强制改变滚动位置，并显示可点击的新消息提示

### Requirement: 管理员必须显式关闭或放弃未发布任务
系统 SHALL 只允许管理员对仍为 PROCESSING 的任务执行关闭或放弃。确认无需变更 MUST 要求工作区没有有效工作文档和非空有限结论，并把任务设为 CLOSED_NO_CHANGE、候选输入设为 CURATED。放弃 SHALL 把任务设为 ABANDONED、候选输入恢复 PENDING。两种操作 SHALL 保留全部历史并使任务只读。

#### Scenario: 确认无需变更
- **WHEN** 工作区没有有效文档且管理员提交合法结论
- **THEN** 系统原子关闭任务、标记候选输入已整理并写入系统记录

#### Scenario: 有工作文档时确认无需变更
- **WHEN** 当前存在至少一份有效工作文档
- **THEN** 服务端拒绝关闭且不改变任务或候选输入状态

#### Scenario: 放弃任务
- **WHEN** 管理员放弃 PROCESSING 任务
- **THEN** 系统原子保留历史、恢复候选输入为 PENDING、把任务标记 ABANDONED并拒绝后续消息

## REMOVED Requirements

### Requirement: 用户必须能够在安全步骤边界暂停并指导长任务
**Reason**: 当前 MVP 不需要人工暂停后恢复同一个 run；Agent 有疑问时结束当前 run，用户回复创建新 run。继续保留 Checkpoint/Human-in-the-loop 页面协议会增加状态与恢复复杂度而不改善核心闭环。
**Migration**: 新页面移除暂停/恢复入口。既有后端端点可以暂时保留为未公开兼容能力，但新契约与验收不依赖它们。
