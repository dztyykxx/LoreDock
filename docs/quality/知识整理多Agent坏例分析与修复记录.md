# 知识整理多 Agent 坏例分析与修复记录

## 1. 背景与结论

本记录包含 2026-09-02 的初始重测、修复后冒烟，以及最新 8 条真实模型复跑结果。

初始重测报告为：

- 真实运行报告：`backend/target/atlas-agent-eval-report-projection-full.json`
- 离线裁判报告：`backend/target/atlas-agent-eval-report-projection-full-judged.json`
- 实际执行 8 条，7 条 `COMPLETED`，1 条 `WAITING_FOR_USER`；误写率为 0%。
- 在线评估动作正确率为 33.33%，离线 Judge 问题识别正确率为 66.67%，动作正确率为 50.00%。

初始重测的问题不是 FINISH/REPORT 上下文投影本身造成的。投影修复后，正常案例的 Main Agent 已经可以看到检索结论；主要坏例发生在 Retriever、Coordinator 的 DECIDE 以及结构化结果修复阶段。当前系统从“可能误写”偏向了“过度保守，连有效新增也不写”。

## 2. 坏例定位

| 用例 | 表现 | 直接原因 |
|---|---|---|
| CUR-001 | 内部 Coordinator 选择 `ASK_USER`，但纯重复场景本应 `NO_CHANGE` | 对“是否保留口语化材料”这类非业务阻断问题过度追问 |
| CUR-002 | Retriever 实际输出了 `DUPLICATE` 和事实，Coordinator 却收到 `NONE` | 长 JSON 截断后，尾部嵌套对象被当成完整 JSON；Retriever 没有独立结果校验 |
| CUR-003 | 已正确识别冲突，重复 `question` 后进入恢复，最后 `WAITING_FOR_USER` | 重复键 last-wins 覆盖有效字段；修复上下文丢失 DECIDE 阶段和检索事实 |
| CUR-004 | 正确识别冲突，却选择 `NO_CHANGE` | Coordinator 没有明确规定“冲突必须请求人工确认” |
| CUR-005 | 识别为 `DUPLICATE`，预期主问题是 `MISSING` | 混合场景缺少问题类型优先级，关键权限缺失被重复内容掩盖 |
| CUR-006 | 正常识别 `MISSING → ASK_USER` | 当前链路正常 |
| CUR-007 | 有效新增验证步骤被判断为重复且无证据 | Retriever 没有把候选草稿本身视为新增事实的可用来源 |
| CUR-008 | 有效新增通知模板未写入，Main 最后只输出“已汇总裁剪整理结果” | 同样误判新增事实；REPORT 只校验正文非空，允许空洞汇报通过 |

## 3. 根因

### 3.1 结构化解析和 Retriever 校验缺口

`KnowledgeCurationGraphFactory.tolerantStructured` 从右向左寻找尾部 JSON，但当前只验证 JSON 语法，不验证目标结构是否完整。模型输出外层 JSON 被截断时，内部 `sourceRefs` 等对象可能被误认为根结果。

另外，Graph 当前直接从 Retriever 进入 `set_decide`，没有像 Coordinator、Drafter、Reviewer 一样校验 Retriever 的必填字段。

重复键采用 last-wins 也会产生静默数据损坏：CUR-003 的第一个 `question` 有效，尾部重复的空 `question` 覆盖了它。

### 3.2 修复回路丢失语义

当前只要 `retryAttempt > 0` 就把后续入口统一视为 `REPAIR`。但 REPAIR 上下文没有阶段标记，且不携带上游事实；全局重试计数也没有按 Agent 隔离或在成功后清理。因此 Coordinator 修复时会把 DECIDE 误判为 START，输出 `CHAT`，随后再次失败。

### 3.3 Retriever 和 Coordinator 规则不完整

候选草稿是本轮被选中的业务材料，明确、稳定、不与正式知识冲突的新事实可以以 `SELECTED_DRAFT` 作为来源。当前 Prompt 把“正式知识没有出现”过度解释为 `INSUFFICIENT`，导致 CUR-002、CUR-007、CUR-008 的有效新增无法进入工作区。

同时没有明确以下动作矩阵：

- `CONFLICT → ASK_USER`
- `MISSING → ASK_USER`
- `DUPLICATE` 且无增量 → `NO_CHANGE`
- `DUPLICATE` 且有已支持小增量 → `DRAFT/MODIFY`
- `NONE` 且有完整新增 → `DRAFT/ADD`

### 3.4 最终汇报和评估口径不足

Main Agent 的 REPORT 只要返回非空正文即可通过，像“已汇总裁剪整理结果”也会被接受。评估器的动作正确率只统计问题案例，正常新增案例的写入失败没有进入同一分母；同时只根据工作区为空判断 `NO_CHANGE`，没有使用 Coordinator 的实际 DECIDE 动作。

## 4. 修复方案

### P0：解析与恢复

1. 为 Retriever 增加结果校验节点和修复路由，校验 `issueType`、`summary`、facts、support、sourceRefs。
2. 解析时保留“自然语言正文 + 尾部 JSON”能力，但要求尾部对象满足目标 Schema；禁止把内部 JSON 片段当作结果。
3. 对冲突重复键进入修复，不再静默使用 last-wins；阶段字段必须与 Graph 当前阶段一致。
4. 修复入口保留当前阶段、当前指令和已通过校验的上游事实；重试次数按 Agent 隔离，成功后清理对应状态。

### P1：判定规则

1. 明确候选草稿本身可以为新增事实提供 `SELECTED_DRAFT` 证据；只有草稿明确表示待定、猜测或缺少关键执行条件时才标记 `INSUFFICIENT`。
2. 增加问题类型优先级和动作矩阵，避免冲突被 `NO_CHANGE` 吞掉、关键缺失被普通重复覆盖。
3. 明确“工作草稿写入不等于正式发布”：候选来源充分且无冲突时可以写入工作区，Reviewer 和管理员继续负责发布前门禁。
4. 纯重复场景不得因为组织偏好或口语化保留问题而额外 `ASK_USER`。

### P2：最终汇报与评估

1. REPORT 必须包含问题类型、实际动作、写入情况、目标文档或待确认项；空洞汇报进入修复。
2. 保存 DECIDE 阶段的实际动作，供 REPORT 和评估使用。
3. 分开统计整体动作正确率、问题案例安全处置率、正常新增写入率和最终自然语言汇报正确率。

## 5. 验证顺序

先用确定性测试覆盖：重复键、截断嵌套 JSON、Retriever 结果校验、Coordinator 修复上下文和 REPORT 空洞回复；再用真实模型重跑 CUR-002、CUR-003、CUR-007、CUR-008；最后重新运行 8 条和离线 Judge。

本记录只描述当前 checkout 的实现和实验结果，不把设计目标或单次实验结果表述为生产保证。

## 6. 本次修复后的验证结果

- 受影响单元测试通过：`KnowledgeCurationGraphRoutingTest`、`ContextAssemblyTest`、`AtlasEvalMetricsTest`，共 26 个测试通过。
- 图级回归通过：`KnowledgeCurationGraphRunIT#injectsExplicitFinishStageMarkerOnNoChangePath`，确认 REPORT 上下文保留 DECIDE 的 `NO_CHANGE`，不会被 FINISH 的 `END` 覆盖。
- 真实模型前 4 条冒烟通过：4 条均正常结束、无误写；CUR-001 的最终汇报已明确写出 `DUPLICATE + NO_CHANGE`，CUR-003/004 均保留 `CONFLICT + ASK_USER`。
- 真实模型第 1 条补充回归通过：最终汇报能够同时看到问题类型、DECIDE 动作、写入情况和待确认项；本次无文档变更、无误写。
- 离线裁判报告：`backend/target/atlas-agent-eval-report-projection-repair-smoke-judged.json`。4 条中 3 条问题类型与动作符合预期；CUR-002 仍为 `CONFLICT/ASK_USER`，说明剩余问题是 Retriever 对“有效补充规则 + 旁支冲突”如何拆分和归类的业务判定，不是结构化解析失败。

因此，当前已完成的是“结果不丢失、非法结构可修复、错误动作不被汇报层掩盖、评估读取内部动作”这组修复；CUR-002、CUR-007、CUR-008 的细粒度事实拆分和增量识别仍应作为下一轮 Prompt/评估数据修订重点，未在本次冒烟结果中宣称已解决。

## 7. 修复后 8 条在线与离线复跑

本次先修复了一个在线运行中发现的图路由缺陷：Retriever 校验失败返回 `fix_retriever`，但 `retrievalRoutes()` 错误使用了 `REPAIR/RECOVERY` 作为路由键，导致修复节点无法进入。改为使用 `fixEntries()` 后，相关单元测试通过，Retriever 修复路径可继续执行。

在线复跑命令只执行 8 条知识整理，不执行 QA；真实模型、PostgreSQL、BGE 检索均实际运行。报告为：

- 在线报告：`backend/target/atlas-agent-eval-report-projection-repair-full.json`
- 离线 Judge 报告：`backend/target/atlas-agent-eval-report-projection-repair-full-judged.json`
- 在线 8/8 条达到 `COMPLETED`，完成率 100%，误写率 0%，问题案例动作正确率 83.33%。
- 离线 Judge 问题识别正确率 50.00%，问题案例动作正确率 83.33%，误写率 0%。

逐条观察如下：

| 用例 | 在线实际表现 | 结论 |
|---|---|---|
| CUR-001 | `DUPLICATE → NO_CHANGE`，无写入 | 正常 |
| CUR-002 | `DUPLICATE → ASK_USER`，幂等键规则被视为待确认 | 仍未命中预期 `MERGE` |
| CUR-003 | 主 Agent 直接 `CHAT`，未进入检索 | 仍未识别预期冲突，疑似入口上下文/候选注入问题 |
| CUR-004 | `CONFLICT → ASK_USER`，无写入 | 正常 |
| CUR-005 | 检索为 `DUPLICATE`，但对新增操作细节 `ASK_USER` | 问题类型仍偏向重复，未命中预期 `MISSING` |
| CUR-006 | `MISSING → ASK_USER`，无写入 | 正常 |
| CUR-007 | 主 Agent `TURN_DONE`，只检索未写入 | 未命中预期新增/更新 |
| CUR-008 | `CONFLICT → ASK_USER`，无写入 | 与数据集预期新增/更新不一致，但安全处置正确 |

离线 Judge 的结果确认了：本轮没有非法写入，修复回路和最终汇报投影已能稳定保留有效结论；剩余失败主要是业务判定与评估数据预期的偏差，尤其是“重复核心 + 小增量/待确认细节”的优先级，以及 CUR-003、CUR-007 的入口上下文是否正确注入。另需注意，在线 `curationMetrics` 的问题案例动作正确率只统计 6 条 `DUPLICATE/CONFLICT/MISSING` 用例，因此不等同于 8 条全量动作准确率。

## 8. CUR-003 与 CUR-007 的后续修复

对 CUR-003 的链路核对显示，启动参数和数据库快照均未丢失：`selectedDraftId=720003` 已在任务启动时固定保存。真正缺口是首轮主 Agent 的上下文投影只包含通用整理目标，没有包含“本轮存在固定候选”的元数据；候选正文又按权限只允许 Retriever 通过 Tool 读取，因此主 Agent 会把本轮误判为没有材料并输出 CHAT。

修复为：`ContextAssemblyService` 在主 Agent 首轮 CHAT 和 Retriever 入口投影固定候选的 `draftId`、revision、标题、目录和正文码点数，不注入正文；同时主 Agent 提示词明确要求出现【已固定候选材料】时必须进入 `FULL_CURATION`，不能回复“未收到候选材料”。

对 CUR-007，主 Agent 提示词增加了直接推进规则：Retriever 已确认来源明确、稳定、可执行且无冲突的新增事实时，不因落款缺失、正文完整性未显式声明、独立成文还是并入已有文档等组织偏好向管理员追问；由 Coordinator 决定 `DRAFT`，Drafter 写入工作草稿，最后由 FINISH/REPORT 汇报，管理员后续仍可要求修改。只有候选明确截断/不完整、缺少关键执行条件、来源不足或存在冲突时才 `ASK_USER`。

本次针对性验证：`ContextAssemblyTest` 8/8 通过；`KnowledgeCurationGraphAssemblyTest` 与 `KnowledgeCurationGraphRoutingTest` 合计 12/12 通过。尚未重新运行真实模型 8 条在线评估和离线 Judge；此前 `KnowledgeCurationGraphRunIT` 的一条脚本模型调用次数断言仍为期望 7、实际 8，属于已有的主 Agent 汇总 JSON 修复回路问题。
