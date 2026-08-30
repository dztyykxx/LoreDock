## MODIFIED Requirements

### Requirement: 会话级 threadId 与多轮状态继承
系统 SHALL 使用会话级稳定 `threadId = knowledge-task-conversation-{conversationId}` 承载父 Graph State 与 Checkpoint；同一会话的每个正常用户轮次 SHALL 创建新 run 但复用同一 `threadId`。上一轮正常完成的轮次 SHOULD 停在 `WAIT_INPUT` 边界，且新轮 MUST 从 Checkpoint 指向节点继续，MUST NOT 重跑已完成的入口节点或重建无关会话状态。旧会话已保存的未完成 run MUST 继续使用其原 `threadId`；旧会话在无会话级 Checkpoint 时开始新轮，SHALL 从业务消息与工作区重建一次初始会话状态。

#### Scenario: 上一轮完成后继续对话
- **WHEN** 上一轮 run 已在 `WAIT_INPUT` 正常完成并保留 Checkpoint，管理员提交新消息
- **THEN** 创建新 run，`thread_id` 与上一轮相同；主 Agent 能读到角色化会话历史与上轮结论；本轮临时字段已重置，不携带上一轮的检索候选与路由结果

#### Scenario: 检索后暂停并恢复
- **WHEN** 完整整理的 run 在检索节点后的边界被暂停，随后由管理员恢复
- **THEN** 恢复使用最新 `StateSnapshot.config()` 从 Checkpoint 指向的下一节点继续，检索节点不重跑，已提交草稿修订不重复

#### Scenario: 会话级并发轮次被拒绝
- **WHEN** 同一会话存在非终态 run 或处于执行中的轮次时收到新的轮次请求
- **THEN** 服务端拒绝该请求并返回“上一轮处理中”的明确提示，不创建第二个并行 run，会话 Checkpoint 不被并发写入

### Requirement: 主 Agent 多路调用与意图路由
系统 SHALL 让主 Agent 持有 `retrieve_expert`、`draft_expert`、`review_expert` 三个专家 `AgentTool`，并把主 Agent 的最终模型输出约束为 `MainTurnResult`（`action = CHAT | FULL_CURATION | TURN_DONE`），父图条件边 MUST 只按该枚举路由。`CHAT` 与 `TURN_DONE` MUST 携带非空可见回复；`TURN_DONE` 所在轮若直调过 Drafter，最终回复 MUST 声明“已修改、未经专家审查”。主 Agent 允许先直调若干专家后再输出 `FULL_CURATION` 进入完整整理子图。专家直调 MUST NOT 把用户会话交接给专家，主 Agent 始终是唯一对话口径。

#### Scenario: 元对话不触发业务链路
- **WHEN** 用户消息是问候、流程状态或上轮结论的解释
- **THEN** 主 Agent 输出 `CHAT` 并回复，三个专家调用数与完整子图调用数均为 0

#### Scenario: 只读查询只调检索专家
- **WHEN** 用户查询某个业务事实或当前草稿内容
- **THEN** 主 Agent 只调用 `retrieve_expert` 并组装回复，无草稿写入与审查调用，引用与项目范围正确

#### Scenario: 直接调整草稿后声明未经审查
- **WHEN** 用户要求修改标题或其他明确写入操作
- **THEN** 主 Agent 调用 `draft_expert` 产生一个新 revision；最终回复声明“已修改、未经专家审查”，不宣称完成整理或进入发布

#### Scenario: 高风险整理进入完整子图
- **WHEN** 用户要求整理候选材料、合并多份文档或处理事实冲突
- **THEN** 主 Agent 输出 `FULL_CURATION`，完整子图的检索、调度、起草、审查与最终汇总节点真实执行，全程不自动发布

### Requirement: 分层失败与写入对账
系统 SHALL 按失败类别处理可预期错误：JSON/Schema/业务字段校验失败经 Validate 观测并反馈同一 Agent 重生成（默认上限 2 次）；只读 Tool 暂时失败允许幂等重试（默认上限 2 次）；写 Tool 失败或结果未知 MUST 按 `idempotencyKey + draftId + baseRevision` 对账，MUST NOT 盲目重试；Drafter 已产生新 revision 时 SHALL 从 Tool 回执与当前 PatchSet/Workspace 重建 typed 结果。重试耗尽 MUST 把 run 保留为可恢复等待（`WAITING_FOR_USER` + `RECOVERY_REQUIRED`），MUST NOT 直接终结整轮；仅权限越界、明确取消、不可迁移定义不兼容与缺失语义稳定恢复点才进入终态 `FAILED`。完整子图校验 MUST 通过静态边进入确定性 Validate 节点，MUST NOT 仅依赖条件边解析原始候选。

#### Scenario: 无效 JSON 后同 run 修复
- **WHEN** 专家首次输出无效 JSON，重生成后输出有效
- **THEN** Validate 记录失败状态与错误摘要，同一 run 的同一 Agent 收到具体校验错误后重新生成，run 不失败、不重新创建

#### Scenario: Drafter 已写入但输出无效
- **WHEN** Drafter 已成功写入新 revision 但最终结构化 JSON 无效
- **THEN** 按 Tool 回执与当前 revision 对账并重建 typed `DraftResult`，草稿不产生第二个无关 revision，不重复执行相同写入

#### Scenario: 重试耗尽保留可恢复状态
- **WHEN** 结构化结果持续无效直至重试上限
- **THEN** run 保留原 `threadId`、最后语义稳定 Checkpoint 与具体失败原因，进入 `WAITING_FOR_USER`（`RECOVERY_REQUIRED`），不落入终态 `FAILED`

### Requirement: 上下文所有权与定义版本
系统 SHALL 只由会话级父 Graph 持有 Checkpoint：专家直调与子图节点的子 Agent MUST NOT 配置 Saver，MUST NOT 继承跨轮或返工的旧模型/Tool 历史。顶层 `messages` SHALL 由 `TURN_FINISH` 节点重建为角色化会话历史（用户指令 + 主 Agent 最终回复），MUST NOT 把未校验的原始候选作为已完成阶段公开。恢复时 MUST 先比对 run 持久化定义摘要（`agent_spec_digest` 与 `config_summary` 携带的 Graph 定义版本）与当前定义：不一致时 SHALL 停在 `RECOVERY_REQUIRED`，MUST NOT 用新定义直接解读旧 Checkpoint；部署修复后可从语义稳定点重建续跑。

#### Scenario: 定义摘要变化时不解读旧状态
- **WHEN** 重启或恢复时发现 Agent 定义内容或 Graph 定义版本与 run 记录不一致
- **THEN** run 停在 `RECOVERY_REQUIRED` 并记录诊断，不解析不兼容 Checkpoint，不重复执行业务写入

#### Scenario: 校验前候选不可见
- **WHEN** 模型候选尚未通过 Validate
- **THEN** 系统不产生已完成阶段消息，候选只保存为可审计的内部诊断，用户可见投影只在校验通过后写入
