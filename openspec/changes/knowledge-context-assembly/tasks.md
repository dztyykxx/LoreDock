## 0. 前置与契约

- [x] 0.1 核对锁定版框架（spring-ai-alibaba 1.1.2.3）本地源码：`MessagesModelHook.BEFORE_MODEL` 行为、`ReactAgent.asNode` 子图状态复制与消息过滤、`PostgresSaver.updateState` 同事务替换；结论写入 design.md（已核验，未发现新的出入）
- [x] 0.2 契约：`agent` 模块内新增上下文组装契约——`ContextAssemblyRequest`、`PreparedModelContext`、`ContextReceipt`、`AgentNode`、`ContextPurpose`、`ContextMode`、`ConversationContext`、`WorkflowContext`、`ContextBudget`（与设计文档 §4 一致，camelCase + record 风格）
- [x] 0.3 预算配置：`application.yml` 新增 `loredock.agent.context` 配置组（9 项，默认值与设计文档 §3 一致），提供 `LOREDOCK_AGENT_CONTEXT_*` 环境变量覆盖；启动时校验 `compressionTarget < compressionTrigger < maxInput`、`maxInput + outputReserve + safetyReserve <= maxWindow`、各上限 > 0，配置不合法直接抛错
- [x] 0.4 失败测试（阶段 1）：`ContextAssemblyTest`（单元）——按 `agentNode × purpose` 组装后消息不包含禁止继承的内容（旧 Tool 链/旧专家 JSON），包含系统规则/当前任务/人工决定/稳定引用；`purpose=REPAIR` 只含最小输入 + 有界错误摘要 + `lastValidatedNode`
- [x] 0.5 失败测试（阶段 1）：`ContextAssemblyTest`——`purpose=CHAT` 组装结果仅含会话摘要、最近角色化轮次、当前用户指令、已确认决定与任务状态摘要；`purpose=FULL_CURATION_DRAFT` 含草稿基线 revision 且不含 Retriever 原文

## 1. 图结构：准备节点与一次性消息缓冲区

- [x] 1.1 实现（阶段 1）：新增 `currentInstruction` REPLACE state 键；父 Graph `messages` 目标合并策略改为 REPLACE（一次性输入缓冲区），Agent 子图内部 APPEND 不动；运行输入由 Executor 写入 `currentInstruction`（不再只依赖 messages）
- [x] 1.2 实现（阶段 1）：为 Main/Retriever/Coordinator/Drafter/Reviewer 各插入准备节点（进入专家节点前必经），准备节点调用组装服务并把 `PreparedModelContext.messages` 写入 `messages`；准备节点生成的消息视图显式携带与现有 spec 约定一致的阶段标记文本（DECIDE/FINISH/完整整理完成，无标记即 START 语义保持）；撤销 set_decide/set_finish/set_draft_context/set_review_context/set_main_resume 的增量提示注入，由准备节点统一重建
- [x] 1.3 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——完整整理子图各专家节点实际收到的初始消息不含旧轮/旧专家 JSON（从工具调用记录与事件投影观察），Drafter 节点消息含基线 revision
- [x] 1.4 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——直调路径（DIRECT_RETRIEVE/DRAFT/REVIEW）主 Agent 生成 `actualInput` 文本，专家侧崭新 state 无父图会话历史（复用 `KnowledgeCurationConversationStateIT` 的注入方式与框架行为断言）
- [x] 1.5 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——DECIDE/FINISH 阶段标记仍显式且唯一，无标记输入下 Coordinator 按 START 语义执行（防重复开场白回归）；修复回路（REPAIR）经准备节点重新组装且有界错误摘要可见
- [x] 1.6 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——`TURN_FINISH` 重建历史以 `currentInstruction` 为权威来源（不扫描 messages），主回复来自结构化键（`mainTurnResult`/`recoveryInfo`），且与守卫裁剪并存时不丢最终回复
- [x] 1.7 实现（阶段 1）：`TURN_FINISH` 与恢复注入适配 `currentInstruction`/准备节点语义；存量 run 恢复缺新键时按"无摘要"回退并 WARN

## 2. Token 估算与确定性压缩

- [x] 2.1 失败测试（单元）：`ContextBudgetService`——优先使用模型 Tokenizer（配置指向），回退 UTF-8 字节上界；`estimateMode` 如实标识；前后码点与 Token 估算一致（编码为中文与英文混合用例）
- [x] 2.2 实现（阶段 2）：Token 估算器与预算判定：输入估算 ≤72k 返回 FULL；72k~96k 执行确定性压缩至 64k 目标；>96k 进入 LLM 压缩候选（阶段 5）
- [x] 2.3 失败测试（单元）：确定性压缩——移除旧 Tool 参数与 Tool 原文、移除已完成节点原始 JSON、正文替换为 `documentId`/`draftId`/`revision`/`blockId` 引用、按完整轮次从旧到新整轮丢弃（半轮不截断）、保留原始目标/当前指令/人工决定/未解决问题/当前引用；同一输入两次结果完全一致（可复现断言）
- [x] 2.4 实现（阶段 2）：确定性压缩服务（按 2.3 顺序规则实现），输出 `ContextReceipt`（droppedHistoryTurns、replacedBodiesWithReferences、appliedPolicies 等）
- [x] 2.5 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——构造超过 72k 的节点上下文，断言节点入口执行确定性压缩、日志含压缩前后 Token 与策略、清理后不丢关键事实

## 3. 预算守卫 Hook 与调用日志

- [x] 3.1 失败测试（单元）：`ContextBudgetGuardHook`——`beforeModel` 收到完整消息链；≤maxInput 且 run 预算未耗尽时放行；>maxInput 清理最旧闭合 Tool Call/Result 组且最新未闭合配对与当前任务消息受保护
- [x] 3.2 实现（阶段 3）：`ContextBudgetGuardHook`（`MessagesModelHook` 实现）：估算、保守裁剪、阈值与 run 累计判定；超限抛 `ContextLimitExceeded`（单次上限且不可裁剪）与 `ContextRunBudgetExceeded`（run 累计耗尽），均不发送模型请求
- [x] 3.3 实现（阶段 3）：Hook 注册进 Main/Retriever/Coordinator/Drafter/Reviewer 五个 Agent 构建 hooks；`ContextLimitExceeded`/`ContextRunBudgetExceeded` 由 Executor 失败分类衔接为 `WAITING_FOR_USER`（标记从 `waitReason` 区分，复用 `markKnowledgeRecovery`，不落 FAILED）
- [x] 3.4 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——节点内部超限时只清最旧闭合组；清理后仍超限的 run 转 `WAITING_FOR_USER`、Checkpoint 保留、模型业务调用数不增加；run 累计预算耗尽直接中断
- [x] 3.5 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——模型未返回 usage 时日志实际用量为 null 而非 0；压缩调用记录为 `agent=context_compressor`

## 4. LLM 压缩兜底与滚动摘要

- [x] 4.1 失败测试（单元）：压缩调用（内部 ChatModel 结构化输出，隔离测试替身）——输入含旧轮块与目标预算；返回引用/决定/问题 ID 必须是输入子集，非法 ID 结果被拒绝
- [x] 4.2 实现（阶段 4）：`ContextAssemblyService` 内部受限压缩调用（结构化输出 schema + Jackson），无 Agent/Spec/Tool/Saver，不入父图；压缩批次按 `knowledge_task_message` 完整轮次选择有界批次
- [x] 4.3 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——压缩后仍超限返回 BLOCKED → run 转 `WAITING_FOR_USER`（等待人工指导），不进入失败终态、不进同输入重试
- [x] 4.4 实现（阶段 4）：摘要 field 集（`conversationSummary`/`summaryThroughMessageId`/`summarySourceDigest`/`summarySchemaVersion`/`summaryGeneration` REPLACE 键）；滚动摘要（已提交摘要 + 之后增量轮次，仅完整轮次）；`max-rolling-summary-generations` 达到后从原始消息低频重建并重置代数；同 `summarySourceDigest+summarySchemaVersion+targetTokens` 最多压缩一次
- [ ] 4.5 失败测试（集成）：`KnowledgeCurationContextAssemblyIT`——同会话后续轮次复用已提交摘要+增量（不重复压缩完整历史）；摘要失效条件（定义/版本变化、来源消息异常、无会话 Checkpoint）触发从业务消息表重建；摘要字段随 Checkpoint 持久化（模拟重启/恢复后直接复用，不重复调用压缩）
  - 说明：复用与失效逻辑已实现（usableSummary 摘要+digest/schema 校验、滚动 generation 重建），尚未以“跨 run 复用+失效重建”专项 IT 断言——留待后续批次补测

## 5. 回归与验收

- [ ] 5.1 跨轮权威来源：压缩输入只含真实 USER 与最终回复（subject 过滤排除公开行动摘要），不含子 Agent/工具调用/阶段事件——失败测试 + 实现修复既有读取逻辑（复用 `KnowledgeTaskServiceImpl` 同款过滤）
  - 说明：readOldTurns 已复用同款过滤（正例经 ContextCompressionServiceTest/IT 覆盖）；公开行动摘要“排除负例”尚未单测——留待后续批次补测
- [x] 5.2 全量回归：后端全部测试通过（含既有 61 项 agent 集成 + 新增上下文组装专项）；验证命令见下
- [x] 5.3 文档同步：OpenSpec tasks 勾选；`docs/architecture/知识整理上下文组装与压缩设计.md` 与实现一致（若有出入更新文档或设计）
- [x] 5.4 检查“无意义重复测试 / 仅覆盖率测试”；确认未新增跨模块 API、无新依赖、无新表

## 验证命令

后端（需 JDK 21，`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）：

```bash
./mvnw -o test -Dtest=ContextAssemblyTest,ContextBudgetGuardHookTest,KnowledgeCurationContextAssemblyIT,KnowledgeCurationConversationStateIT,KnowledgeCurationGraphRunIT,KnowledgeCurationRunExecutorDriveIT,KnowledgeCurationGraphRoutingTest,KnowledgeCurationPersistenceIT,AgentRunPersistenceIT,BackendMvcArchitectureTest
```

全量：`./mvnw -o test`。
