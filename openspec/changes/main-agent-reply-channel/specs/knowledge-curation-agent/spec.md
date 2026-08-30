## MODIFIED Requirements

### Requirement: Agent 必须实时公开有限决策过程
知识整理 Skill SHALL 要求模型通过 AssistantMessage 可见正文输出面向用户的公开决策说明，通常覆盖开始计划、关键判断、准备写入和最终结果；系统 MUST NOT 为公开沟通增加消息 Tool。当 Agent 输出带有结构化契约（路由/元数据 JSON）时，契约 MUST NOT 承载面向用户的回复——回复与公开决策说明 SHALL 落在消息可见正文，结构化契约仅承载路由、工具参数等元数据；系统持久化并实时通知页面的公开消息 MUST 为可见正文（剔除结构化尾缀），MUST NOT 把结构化字段单独作为可展示回复、MUST NOT 用最后一个增量 token 当作最终回复、MUST NOT 根据其他 Tool 事件伪造决策文本，也 MUST NOT 读取、保存或展示原始思维链、供应商 reasoning 字段、系统提示或隐藏消息。

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
- **THEN** ReactAgent 再次调用模型，系统从 `AGENT_MODEL_FINISHED` 聚合响应取得一条非空、无 Tool Call 的完整 AssistantMessage 作为最终总结；若该消息携带结构化 JSON 尾缀，系统 SHALL 以去除尾缀后的可见正文作为最终总结，MUST NOT 使用结构化字段代替正文；缺少可见正文时本轮失败而不是写入固定"运行已完成"占位

#### Scenario: 模型返回原始推理字段
- **WHEN** 模型供应商响应包含独立 reasoning 内容
- **THEN** 该内容不进入知识任务消息、公开事件、日志或页面

#### Scenario: Agent 需要用户确认或发现风险
- **WHEN** Agent 无法从授权来源确定结论，或发现冲突、缺口和需要用户处理的警告
- **THEN** Agent 把警告、主要依据和具体问题统一写入最终回复的可见正文并正常结束 run，MUST NOT 为此调用专用警告记录 Tool，也 MUST NOT 把"待确认事项"、面向管理员的问题或执行过程写入可发布工作文档

#### Scenario: 框架在最终回复后产生空消息
- **WHEN** 模型已返回非空最终提问，而框架流末尾又出现空 AssistantMessage
- **THEN** 系统保存最后一条非空、无 Tool Call 的模型可见正文，MUST NOT 用通用完成占位文案覆盖该提问

## ADDED Requirements

### Requirement: 主 Agent 结构化输出仅承载路由与调用元数据
主 Agent（会话级调度节点）每次输出 MUST 由两部分构成：可见正文（面向管理员的完整回复，可直接展示，MUST NOT 承担 JSON 转义责任）与位于消息**尾部的结构化 JSON 尾缀**。结构化尾缀 MUST 仅承载路由与调用元数据：`action`（CHAT/TURN_DONE/FULL_CURATION，路由输入）与 `expertCalls`（专家调用参数），可含可空 `memo`（极短说明，用于公开投影降级摘要，MUST NOT 作为可展示回复）。系统 SHALL 从消息尾部以引号感知的括号配对识别结构化 JSON（消息正文中出现的花括号、JSON 示例文本不得被误识别为结构化输出）；识别失败时按既有结构化修复回路处理，MUST NOT 把无效正文直接当作路由依据。主 Agent 回复与公开投影的降级顺序 MUST 为：可见正文 → `memo` → 协调结果摘要；全部缺失时该轮按"无可见最终消息"既有语义失败。专家节点（检索/起草/审查/协调）的 `summary` 仍为公开行动摘要短文本，不受本要求影响。

#### Scenario: 主 Agent 正常完成轮次
- **WHEN** 主 Agent 完成一轮并需要向管理员展示最终回复
- **THEN** 消息可见正文承载完整回复，消息尾部 JSON 仅含 `action`/`expertCalls`/`memo`；持久化消息与页面展示内容为可见正文，不包含结构化尾缀

#### Scenario: 回复正文含花括号或 JSON 示例
- **WHEN** 主 Agent 的回复正文中出现 `{`、`}` 或完整 JSON 示例文本
- **THEN** 结构化提取仅匹配消息尾部的系统 JSON，正文中的花括号与示例不被误提取，路由与最终回复均不受影响

#### Scenario: 正文缺失但提供 memo
- **WHEN** 主 Agent 输出仅含结构化尾缀（含简短 `memo`），无可见正文
- **THEN** 最终回复按降级顺序回退 `memo` 以保持已有可见消息语义，该轮不得因正文缺失单独失败；若 `memo` 也缺失则回退协调结果摘要

#### Scenario: memo 达到上限视为违规输出
- **WHEN** 主 Agent 输出无可见正文、而 `memo` 达到 100 码点防御上限（模型把完整回复误写进结构化字段，runId=68 实测表现为展示半句）
- **THEN** 系统按结构化修复回路处理（进入 fix_main_agent 重试），修复指导明确要求「完整回复写在可见正文、memo 仅 ≤20 字摘要」；MUST NOT 把截断后的 `memo` 作为最终回复放行

#### Scenario: 输出不含可识别结构化尾缀
- **WHEN** 主 Agent 输出既无可见正文中可识别的尾部 JSON，也不含合法结构化数据
- **THEN** 系统按既有结构化修复回路处理（进入对应修复节点重试，重试耗尽走恢复门），不得把该输出作为回复或路由依据直接放行

#### Scenario: CHAT 与 TURN_DONE 的正文语义
- **WHEN** 主 Agent 选择 CHAT（未调用专家）或 TURN_DONE（调用专家后完成轮次）
- **THEN** 两种轮次的回复均 SHALL 来自可见正文；TURN_DONE 且调用过 drafter 时，正文内容必须声明"已修改、未经专家审查"，MUST NOT 宣称整理完成或进入发布

#### Scenario: 完整整理流程后的最终汇报
- **WHEN** 主 Agent 收到【当前阶段：FULL CURATION 完成】标记并输出 TURN_DONE 汇报
- **THEN** 汇报（结论、主要依据、已写入/未写入内容、待人工判断项）写入可见正文，不再调用专家、不再发起 FULL_CURATION

#### Scenario: 定义版本的既有 run 兼容语义
- **WHEN** 部署新主 Agent 指令定义后，新 run 与既有 run（按定义摘要与版本前缀比对）同时存在
- **THEN** 新 run 按双通道契约运行；既有定义摘要不匹配的 run 在恢复路径上被判定为不兼容的既有守卫行为保持不变
