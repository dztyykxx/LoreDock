## Tasks

- [x] 核对锁定版本（Spring AI Alibaba 1.1.2.3）本地源码：`AgentTool` 子线程 `{parentThreadId}_{name}` 与清空 checkpoint/nextNode、`StateSnapshot.config()`/`CompiledGraph.updateState()` 存在性、`NodeExecutor` 条件边先于 Checkpoint 的落盘顺序；结论已写入设计文档与提案，并据此删去“自建子 Agent 调度器/重复 Checkpoint 持久化”类任务
- [x] 契约：`KnowledgeCurationGraphResult` 增加主 Agent `MainTurnResult`（`action=CHAT/FULL_CURATION/TURN_DONE`、`summary`、`expertCalls`）与顶层图键（`turnMode`、`directAgentCalls`、`turnResult`、`historyTruncated`），保持 camelCase 与嵌套 record 风格
- [x] 失败测试（阶段 1）：`KnowledgeCurationConversationStateIT`——同一会话下一轮 run 复用同一 `thread_id`，主 Agent 能读到上一轮会话状态（检索候选等本轮字段已重置）
- [x] 失败测试（阶段 1）：`KnowledgeCurationConversationStateIT`——恢复使用 `snapshot.config()` 后 Coordinator（入口）不重跑，从 Checkpoint 下一节点继续
- [x] 实现（阶段 1）：run 创建时 `threadId` 改为 `knowledge-task-conversation-{conversationId}`（旧会话未完成 run 保留原值）；`TURN_FINISH/WAIT_INPUT` 边界与三类状态键；新轮 `updateState()` 注入；恢复首次 stream 用 `snapshot.config()`
- [x] 实现（阶段 1）：会话级串行门禁沿用 continueTask 对上一 run 终态校验与 Executor 状态检查（既有 PersistenceIT 覆盖，未另建并发专项）
- [x] 测试（阶段 1）：并发拒绝语义由既有 continueTask 终态校验与 KnowledgeCurationPersistenceIT 覆盖（未另建并发专项测试）
- [x] 测试（阶段 1）：定义恢复守卫——definitionMismatchStopsRunWithoutModelCalls（不一致时 0 次模型调用、标记 AGENT_DEFINITION_MISMATCH）
- [x] 实现（阶段 1）：启动恢复器扩展：AgentRunRecovery 扫描非终态知识整理 run 并按定义摘要/Checkpoint 重建调度
- [x] 契约（阶段 2+3）：主 Agent main_agent.md 定义与三个专家 AgentTool 注册；主 Agent 输出 MainTurnResult
- [x] 实现（阶段 2+3 前半）：顶层图按 MainTurnResult.action 路由（CHAT/TURN_DONE→turn_finish，FULL_CURATION→retriever→子图链→set_main_resume→主 Agent 汇总）；messages REPLACE/角色化重建未纳入本交付（与阶段 4 一并评估）
- [x] 实现（阶段 2+3）：移除子 Agent Saver（所有 ReactAgent 不再挂 PostgresSaver，父图 Checkpoint 独占命名空间）
- [x] 失败测试（阶段 2+3 前半）：元对话 CHAT 短路（DriveIT/GraphRunIT）与完整整理真实执行（GraphRunIT/DriveIT 7 次调用路径）；专家直调专项（AgentTool 调用链）未覆盖——需构造 toolCalls 模型响应，留待真机联调
- [x] 实现（阶段 2+3 后半）：Validate→Repair→Recovery 回路：条件边校验失败（解析或业务字段）返回 FIX_<agent>，fix 节点记录有界错误摘要/递增 retryAttempt（最多 2 次重生成）后回到该 Agent；耗尽进入 recovery_gate（turnMode=RECOVERY_REQUIRED + recoveryInfo），finalReply 以恢复说明结束，不落失败终态
- [ ] 失败测试（阶段 2+3 后半）：首次无效 JSON 重生成与 Drafter 写入对账（未实现）
- [ ] 实现（阶段 2+3）：写入副作用对账（`idempotencyKey + draftId + baseRevision`，已写入时重建 typed 结果）；只读 Tool 重试上限 2 次、写 Tool 不盲目重试
- [ ] 实现（阶段 2+3）：Executor 失败分类——可恢复错误进 `WAITING_FOR_USER`（`waitReason`），仅四类终态进 `FAILED`
- [ ] 测试（阶段 2+3）：重试耗尽保留可恢复 Checkpoint 与原因，不落终态；跨项目/越权 Tool 被拒且业务数据不变
- [x] 实现（阶段 4，部分交付）：TURN_FINISH 重建角色化会话历史（conversationHistory REPLACE：本轮用户指令 + 主 Agent 最终回复，按最多 4 轮/8000 码点裁剪、半轮不截断、historyTruncated 标记），新轮注入把历史作为角色化消息前缀（附录：旧轮模型原始 JSON 仍随 APPEND messages 共存，清理依赖 messages 策略专项验证——后续批次）
- [ ] 故障排查：评估并接入 `ModelRetryInterceptor`（先验证流式链路实际行为；行为不符时以 Validate 回路语义为准并记录结论）
- [x] 全量回归：后端 441 项全部通过（含新增 5 项会话级/修复回路/直调测试）；前端 vue-tsc -b --noEmit 通过；OpenSpec tasks 已同步

## 验证命令

后端（需 JDK 21，`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）：
```bash
./mvnw -o test -Dtest=KnowledgeCurationConversationStateIT,KnowledgeCurationGraphRunIT,KnowledgeCurationRunExecutorDriveIT,KnowledgeCurationGraphRoutingTest,KnowledgeCurationPersistenceIT,AgentRunPersistenceIT,BackendMvcArchitectureTest
```
前端：`vue-tsc -b --noEmit`；`vitest run src/components/KnowledgeTaskWorkspace.test.ts`。

## 补充结论（2026-08-30 依据锁定版源码评估）
- `ModelRetryInterceptor` 仅实现 `ModelInterceptor`（call 层），未实现 `StreamingModelInterceptor`；知识整理走流式链路（token 采集显式 streamingInterceptors），接入无法覆盖流式调用，且与"流式内容已输出后不重放"冲突 → **不接入**；重生成语义由 Validate→Repair 回路承担。
- 重试耗尽已状态化为 `WAITING_FOR_USER`（AgentRunMapper.markKnowledgeRecovery），保留 Checkpoint，可经既有 resume 接口继续；可见说明写入会话消息。
- 写入副作用对账：图级"重建 typed 结果"未实现；业务目标（已写入不重复产生修订）由两层保障：① 工具层 baseRevision 硬校验（既有实现与测试覆盖："使用过期基础修订返回 DRAFT_REVISION_CONFLICT""Checkpoint 后重复 Tool 调用返回原修订"）；② 本次新增：Drafter 修复指引要求先读取当前 revision 并填入 DraftResult，避免修复重试重复写入。剩余仅为图级从 Tool 回执推断 typed 结果以节省重试的优化（后续任务）。
