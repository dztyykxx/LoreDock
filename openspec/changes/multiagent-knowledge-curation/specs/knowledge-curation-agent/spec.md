## MODIFIED Requirements

### Requirement: 知识整理由四 Agent StateGraph 编排
系统 SHALL 使用项目锁定版本 Spring AI Alibaba 的显式 `StateGraph` 编排四个职责不同的 `ReactAgent`：调度 Agent（coordinator）判定意图与 `ASK_USER/DRAFT/NO_CHANGE` 并汇总，检索 Agent（retriever）只提交证据事实，草稿 Agent（drafter）只执行写入，审查 Agent（reviewer）独立核对来源与最新修订。Graph MUST 固定合法执行顺序、条件分支和最多两轮草稿返工，Agent 不得决定跳过必要节点。调度 Agent MUST NOT 持有业务 Tool，检索与审查 Agent MUST NOT 持有写 Tool，任何 Agent MUST NOT 持有发布 Tool。

#### Scenario: 普通闲聊短路
- **WHEN** 用户消息是问候、致谢或无需访问业务知识的普通对话
- **THEN** 调度 Agent 直接返回可见回复并结束本轮，不执行检索、草稿、审查节点，知识 Tool 调用数与草稿修订数均为 0

#### Scenario: 有充分来源的新知识
- **WHEN** 候选材料与现有知识无冲突且已有支持来源
- **THEN** 真实经过检索、调度、草稿、审查，产生带来源的草稿修订与 Diff，但不自动发布

#### Scenario: 检索后由调度决定下一步
- **WHEN** 检索 Agent 提交证据事实后
- **THEN** 由调度 Agent 结合管理员目标决定 `DRAFT/ASK_USER/NO_CHANGE`；检索 Agent 不输出动作字段，携带动作字段视为结构化结果无效

#### Scenario: 审查持续不通过
- **WHEN** 审查 Agent 持续返回 `REVISE`
- **THEN** 最多返工两轮（draftRound 最大 2），达到上限后交人工且不发布，不进入第三轮

#### Scenario: Tool 权限边界
- **WHEN** 任一 Agent 越权调用非白名单 Tool 或写类/发布 Tool
- **THEN** 越权请求不能改变业务数据；调度 Agent 无业务 Tool，检索/审查 Agent 无写 Tool

### Requirement: Graph 使用真实 PostgreSQL Checkpoint 可恢复
系统 SHALL 使用 `PostgresSaver` 按每个 run 的稳定 `RunnableConfig.threadId` 保存父 Graph 状态，并在关键节点后配置框架 `interruptAfter`；Executor 每次运行到边界检查 run 状态（`RUNNING` 用同一 threadId 续跑、`PAUSE_REQUESTED` 投影 `WAITING_FOR_USER`、`CANCELLED` 结束）。恢复 MUST 使用原 run 的同一 `threadId` 与 `StateSnapshot.config()`（含 `checkPointId`/`nextNode`），不得创建新 thread 伪装恢复。

#### Scenario: 进程重启后恢复同一 run
- **WHEN** 已保存 Checkpoint 的知识整理 run 在进程重启后仍处于可恢复状态
- **THEN** 使用同一 threadId 从 Checkpoint 指向的下一节点继续，已提交草稿修订不重复、不回滚，总超时按 run 首次开始时间计算

#### Scenario: 暂停后加入人工指导
- **WHEN** 运行处于 `WAITING_FOR_USER` 且管理员提交指导
- **THEN** 指导作为会话消息持久化，新 run（或同一 run 恢复后）沿用现有会话消息与工作区继续处理

### Requirement: 运行定义与公开过程投影
系统 SHALL 在启动时加载并校验四份随应用发布的 Agent Markdown 定义（角色齐全、Tool 白名单一致、声明未知 Tool 启动失败），并从四份定义计算 run 的 `RuntimeDefinition`（保留稳定 `skillName` 以维持前端按 run 定义识别最终消息的契约）。公开过程 SHOULD 以 `AGENT_STAGE` 事件 + `SUB_AGENT` 消息 + `COORDINATOR_AGENT` 最终回复投影，页面与公开事件只含白名单字段（不含 Prompt、思维链、Checkpoint、完整 Graph State 或 Tool 原始返回）。

#### Scenario: 启动时定义失效即失败
- **WHEN** 任一 Agent 定义缺少角色、名称重复、声明未知 Tool 或白名单与设计不一致
- **THEN** 应用启动失败，不沿用框架“空列表代表全部 Tool”或“未知 Tool 静默忽略”的默认行为

#### Scenario: 页面过程可恢复且不重复
- **WHEN** 页面刷新或 SSE 重连后重新加载同一任务
- **THEN** 过程从持久化事件重建，阶段事件与 Tool 按公开顺序交错展示，不缺失、不重复，不展示隐藏字段
