## Context

T6B 将提供本地 Skill/Agent Spec、安全 Tool Registry 和 PostgreSQL Graph Checkpoint，T8 在其上实现知识挖掘与冲突整理。当前文档草稿若只保存一次模型全文，会混淆“对话输出”和“待审核产物”，也无法在暂停、重启恢复、用户追问或并发编辑时提供稳定写入语义。

## Goals / Non-Goals

**Goals:**

- 让系统触发和管理员触发使用同一种可查看、可指导的知识任务会话。
- 把对话、运行和草稿产物分离，同时保持完整追溯关系。
- 用乐观修订和结构化操作实现可幂等、可恢复的草稿增量更新。
- 让管理员通过真实 Diff 审批，并能在完成后继续让 Agent 调整。

**Non-Goals:**

- 不实现逐 hunk 接受、三方合并、草稿分支或多人实时编辑。
- 不在正在执行的模型/Tool 调用中途注入用户消息。
- 不从模型 Token 流或 Tool 内部恢复，只从框架 Checkpoint 节点恢复。
- 不把 Tool 原始返回、完整提示或模型思维链作为会话消息公开。

## Decisions

### 1. Conversation、Run、Event 和 Draft Revision 分离

知识任务会话保存项目、触发类型、目标、可见消息和当前草稿。首次系统/人工触发创建 Agent run，等待状态下的用户指导恢复当前 run，一轮正常完成后的追加调整创建新的 run；run 保存状态、资源用量、Checkpoint 和错误。Agent/Tool/来源等真实过程继续保存为运行事件，前端可以把安全事件投影到对话时间线，但不把所有 Tool 正文写成下一轮模型消息。

草稿是独立 Artifact，拥有空基线或待修订正式文档基线以及单调修订号。每个修订关联产生它的 run、Tool 幂等键、来源和变更摘要。会话消息不能直接改变草稿。

### 2. 系统触发也是会话首轮

手动入口和定时调度器都调用同一知识任务创建 Service。定时调度器只提供项目、触发原因、目标 Skill 和幂等键，Service 创建首条 `SYSTEM_TRIGGER` 消息并启动 run；调度器不得复制检索、冲突分析或草稿修改流程。

### 3. draft_update 使用基础修订与结构化区块操作

`draft_create` 创建空草稿或绑定待修订正式文档；`draft_read` 返回当前修订、Markdown 目录和服务端稳定区块 ID。`draft_update` 至少包含：

- `draftId`、`baseRevision` 和调用幂等键；
- 一个有界操作列表，操作类型限定为 `insert_after`、`replace_block`、`delete_block`；
- 每个操作的目标区块、Markdown 内容和 `sourceRefs`；来源可指向本轮 evidenceId 或当前会话用户消息，新增项目事实必须至少引用有效 evidenceId；
- 面向审核者的有限变更摘要。

服务端在同一事务内锁定草稿、验证项目/操作者、基础修订、区块、内容大小和来源，再原子应用全部操作并生成一个新修订。相同幂等键和相同输入返回原修订；同键不同输入冲突；过期 `baseRevision` 返回 `DRAFT_REVISION_CONFLICT`，Agent 必须重新读取。Registry 不向 Agent 提供 `replace_all` 或任意文件写 Tool。管理员直接编辑可以通过独立受权接口形成完整新修订，但仍不得覆盖历史。

### 4. Diff 由服务端基于已提交修订生成

`draft_diff` 读取正式文档/空基线或指定旧修订与新修订，返回有长度限制的 Markdown unified diff、变更统计和截断状态。Diff 不是模型声明，前端只根据服务端修订生成结果展示。发布请求携带明确 `draftId` 和 `revision`；如果当前修订已经变化则返回冲突，管理员必须重新查看 Diff。

MVP 按整份修订审批。需要局部修改时，管理员继续对话或直接编辑生成新修订，再审核新的完整 Diff。

### 5. 暂停是协作协议，不是强制取消调用

管理员请求暂停后，run 从 `RUNNING` 进入 `PAUSE_REQUESTED`。执行器允许当前模型或 Tool 步骤完成并安全提交结果，在下一个调度边界写入 Checkpoint 后进入 `WAITING_FOR_USER`。只有此后提交的用户指导才进入恢复输入。若当前步骤超时或失败，则保留真实失败状态，不伪造已暂停。

恢复使用同一 run/threadId 从 Checkpoint 继续；一轮正常完成后追加消息则创建新的 run，并以当前草稿修订作为起点。两者在审计和资源统计上不得混淆。

## Data and Transaction Boundaries

- 会话、消息、run、草稿、修订和修订来源具有独立生命周期与外键关系；具体表结构在实现前通过接口和 Flyway 设计确定。
- `draft_update` 的修订校验、操作应用、来源写入和新修订提交属于一个事务。
- 暂停状态与 Checkpoint 提交必须有明确先后：没有可读取 Checkpoint 不得公开为 `WAITING_FOR_USER`。
- 发布锁定明确修订，发布事务仍由现有知识生命周期 Service 执行，Agent 无发布 Tool。

## Risks / Trade-offs

- [区块 ID 在 Markdown 大改后失效] → 只由服务端解析和分配，冲突时要求重新读取，不根据标题文本猜测目标。
- [模型产生大量微小修订] → 限制单 run 更新次数、操作数与内容大小；Skill 要求按有审核意义的变更批次提交。
- [暂停等待时间过长] → 明确显示 `PAUSE_REQUESTED`，Tool 必须服从既有超时，不能宣称立即暂停。
- [恢复后重复写入] → run 级限制配合 Tool 幂等键、基础修订号和业务副作用记录。
- [对话历史膨胀] → 模型输入只选择有界用户/Agent公开消息、当前草稿摘要和必要来源；完整历史供 UI/审计读取，不全量注入模型。

## Test Strategy

- 契约和 PostgreSQL 集成测试：系统/手动触发幂等、会话/run 关系、修订递增、过期基础修订、重复 Tool 调用和发布修订冲突。
- Agent Fake Model 测试：先读后改、无 `replace_all`、完成后追问创建新 run、来源不合法时拒绝修订。
- 暂停/恢复测试：运行中请求暂停、当前步骤结束后等待、指导恢复、重启加载 Checkpoint，以及失败步骤不伪装暂停。
- 前端测试：对话/事件合流、暂停状态、完成后输入、修订切换、Diff 截断和发布前修订变化。

暂停状态机、草稿乐观修订、幂等写入、历史非证据边界和发布修订锁定需要中文 Javadoc 说明业务原因与风险。
