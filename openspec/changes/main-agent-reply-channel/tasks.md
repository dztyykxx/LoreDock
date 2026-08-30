## 1. 契约与共享提取器

- [ ] 1.1 核对锁定依赖版本（Spring AI Alibaba 1.1.2.3 / Jackson）下 `tolerantStructured`/`jsonObject` 的现有分支（Map/AssistantMessage/String）与相关测试断言，确认 D1/D2 改造点清单；确认本项目无框架级能力可复用来替代自定义尾部提取（无重复实现框架能力问题）——结论作为 1.3/1.4 的输入
- [ ] 1.2 `main_agent.md` 输出契约改写为双通道：可见正文承载面向管理员的完整回复；消息尾部 JSON 尾缀仅含 `action`/`expertCalls`/可空 `memo`（≤100 字，仅投影降级）；各 action 正文语义重述（CHAT/TURN_DONE/FULL_CURATION、【当前阶段：FULL CURATION 完成】轮）
- [ ] 1.3 `KnowledgeCurationGraphResult.MainTurnResult`：`summary` 更名 `memo`（可空），compact constructor 防御式截断到 100 码点并配中文 Javadoc（说明 memo 仅供投影降级、MUST NOT 作为可展示回复）
- [ ] 1.4 `KnowledgeCurationGraphFactory` 新增 `tailJsonExtract(text)`（返回正文与 JSON 的切分点+JSON 文本），`jsonObject` 改为「候选 `{` 从右向左、readTree 全串验证取最先成功者」；配中文注释说明规则、理由与「JSON 后附加文字不再容忍」的契约收紧
- [ ] 1.5 `GRAPH_DEF_VERSION` 前缀推进到 `knowledge-curation-sess-v4`

## 2. 失败测试（先红）

- [ ] 2.1 DriveIT 新增用例「主 Agent 正文含花括号与 JSON 示例 + 尾部 JSON」：断言路由正确、`finalReply` 为完整正文、AGENT_STAGE 摘要含正文内容；中文注释说明防「正文被误提取/误走修复回路」的回归——红灯（旧首`{`末`}` 提取器失败走上修复回路）
- [ ] 2.2 DriveIT 新增用例「主 Agent 正文缺失但尾缀含 memo」：断言轮次正常结束（`completeKnowledge`、无 `failKnowledge`）、最终消息回退 memo；中文注释说明防「正文缺失即失败」回归——红灯（旧实现 finalReply 取 summary 为 null 而失败）

## 3. 最小实现

- [ ] 3.1 `finalReply` 与 `mainRoute` 的 CHAT/TURN_DONE「可见回复」改为「正文非空或 memo 非空」；`GraphFactory.mainSummaryText`（会话历史重建的助手轮文本）同步为正文优先、memo 回退；配中文注释
- [ ] 3.2 `projectSummary` 主 Agent 分支改为：正文（经既有限额截断）→ `memo` → 全文截断降级；配中文注释；专家节点分支不动

## 4. 既有用例同步

- [x] 4.1 DriveIT / GraphRunIT 中主 Agent 脚本化输出同步为「正文 + 尾部 JSON」或「纯尾缀（memo 场景）」，断言正文相关处核对
- [x] 4.2 `KnowledgeCurationGraphRoutingTest` 同步：新契约格式的合法/非法输入断言；若存在「JSON 前后附加文字仍容忍」旧断言，改为尾部约束断言
- [x] 4.3 `AtlasAgentEvalDeterministicIT`（`EvalScriptedChatModel` 主 Agent 分支）同步新输出格式
- [x] 4.4 红灯转绿：2.1/2.2 两个回归用例在 3.1/3.2 后通过（若仍有红灯，回查实现与契约一致性）

## 5. 重构与验证

- [x] 5.1 复核 `tailJsonExtract` 在既有测试（GraphRunIT/GraphRoutingTest/DriveIT 专家角色纯 JSON 输出）下无行为回归
- [x] 5.2 定向运行受影响测试全绿（DriveIT、GraphRunIT、GraphRoutingTest、AtlasAgentEvalDeterministicIT、KnowledgeCurationPersistenceIT）
- [x] 5.3 全量 verify（nohup 脱离 + until MAVEN_EXIT 守护配方），确认 BUILD SUCCESS 无回归
- [x] 5.4 校验实现与 delta spec 场景一一对应、勾选若干「无可见最终消息」边界（正文与 memo 均缺时走既有失败路径的回归断言）并同步任务复选框

## 6. 生产实测补充：memo 触顶回炉（runId=68，2026-08-30）

- [x] 6.1 定位：runId=68 模型把完整回复写进 memo（正文为空），MainTurnResult 100 码点防御截断产生「（或只对拿不」半句，降级链默认放行（排查结论=截断串恰好 100 码点确认）
- [x] 6.2 RoutingTest 红灯：正文缺失且 memo 触顶（≥100 码点）→ fix_main_agent；memo 未触顶仍降级放行（先红后绿）
- [x] 6.3 mainRoute 校验：正文缺失 + `MainTurnResult.memoHitLimit` → IllegalStateException → fix_main_agent；`MEMO_MAX_CODE_POINTS` 提升为 public static final + `memoHitLimit(String)` 静态判定（含 Javadoc 与 runId=68 教训注释）
- [x] 6.4 validationErrorSummary 主 Agent 专项修复文案（「完整回复必须写在可见正文、memo 仅 ≤20 字」）；main_agent.md memo 警示与建议长度收紧（100→20 字）
- [x] 6.5 DriveIT 集成用例：长 memo → 修复回路 → 正文重写 → completeKnowledge(完整正文)
- [x] 6.6 spec 场景「memo 达到上限视为违规输出」+ design D9 已同步；定向 18/18 绿，全量 clean verify 204 测试 0 失败 0 错误（4 真实模型 IT 跳过）BUILD SUCCESS
