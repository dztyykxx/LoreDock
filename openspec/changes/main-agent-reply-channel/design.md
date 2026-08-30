# Design: 主 Agent 最终回复双通道分离

## Context

现状与约束（动机见 proposal.md - Why，不重复）：

- 主 Agent 输出契约是单个 JSON（`{"action":..., "summary":..., "expertCalls":[...]}`），`summary` 承担面向管理员的完整回复，须经 JSON 转义；生产 runId=60（重复键）、runId=63（省略可空 summary 后投影 NPE，213a4ab 已降级修复）证实风险面。
- `KnowledgeCurationGraphFactory.jsonObject` 现用「首 `{` → 末 `}`」截取——正文含花括号或 JSON 示例时会把正文误当结构化输出（双通道契约落地后的直接风险点）。
- `finalReply` 链：recoveryInfo → MainTurnResult.summary → CoordinatorResult.summary → 抛 IllegalStateException；`projectSummary`（AGENT_STAGE 投影）对主 Agent 读 MainTurnResult.summary，缺失回退全文并截断到 `MAX_PUBLIC_PROGRESS_CODE_POINTS=1000`（213a4ab）。
- 五个 Agent 输出统一走 `tolerantStructured` 共读口（路由条件边 + finalReply + projectSummary），解析失败的共同路由是 `FIX_<role>` 修复回路（retryAttempt ≥ 2 → RECOVERY_GATE 非失败收场）。
- 定义兼容守卫：`GRAPH_DEF_VERSION = "knowledge-curation-sess-v3"`，digest 为 null 的历史 run 放行，否则严格 digest + 前缀匹配。

## Goals / Non-Goals

**Goals:**

- 主 Agent 的最终回复经消息可见正文承载，JSON 尾缀仅承载路由与调用元数据（action/expertCalls）+ 可空极短 `memo`。
- JSON 提取改为「消息尾部、语法优先」的确定性规则，正文中的花括号/JSON 片段不干扰。
- 消费端（finalReply / projectSummary）统一「正文 → memo → 既有回退」优先级，公开投影与最终消息持久化均为提取后正文，不含尾缀 JSON。
- `MainTurnResult.summary` 更名 `memo`，语义收窄为「极短说明（≤100 码点），仅投影回退」。

**Non-Goals:**

- 专家节点（retriever/drafter/reviewer/coordinator）的 `summary` 语义不变（仍是公开行动摘要短文本），其 HTTP 契约不随本变更变动。
- 不引入 run 级自动重跑；修复/恢复回路行为不变。
- 不调整消息组织策略（top-level messages 仍 APPEND、conversationHistory 角色化重建留白）——模型在后续轮次中能看到自己上一轮的原始输出，属既有留白。
- 不改前端消费契约（AGENT_STAGE 事件与最终消息字段名不变）。

## Decisions

### D1. 尾部 JSON 提取：候选从右向左、语法验证须消耗到底（替代「首 `{` → 末 `}`」）

`jsonObject` 语义变更：从消息末尾向左扫描每一个 `{` 位置，对 `substring(i)` 用 Jackson 解析并**验证流已消费到底**（`readTree(parser)` 后 `parser.nextToken()==null`——实测锁定版 Jackson 默认容忍尾随文本，`{"a":1} trailing` 也能解析成功，只验 readTree 成功会把正文后的散文吞进 JSON 块），取第一个满足「完整 JSON 文档」的块。理由：

- 契约本身就是「JSON 是尾部尾缀」，所以合法的结构化块必然以消息末尾的 `}` 收尾；取「最靠右且语法完整」的 `{` 起始块天然满足该约定。
- 正文含花括号/JSON 示例时（如「例子：`{"a":1}` ...」+ 尾部 JSON），最靠右的候选是尾部 JSON；若尾部 JSON 语法破损（截断等），则可能退到正文示例——但该输出已不可解析，无论落到哪个候选都会因 schema 校验失败进修复回路，行为安全。
- 与字符串内容感知的括号配对相比，语法验证（readTree）是更强的判定——伪 JSON（不成对/非法转义）直接被拒，无需维护引号转义状态机。
- 预算：`{` 密集的病理文本最多 O(n) 次 readTree 尝试，消息体在截断预算内，成本可忽略（该路径仅 Agent 输出时触发）。

行为变化：**不再容忍「JSON 之后还有文字」**（旧规则的首 `{` → 末 `}` 会吞掉）；新契约下这属于模型违约，与其用最坏解析兜底，不如走修复回路。**已解析成功的候选无需「末 `}` 处再验」**——readTree 全串成功即闭环。

`tolerantStructured` 中 Map/AssistantMessage 分支结构不变，仅字符串抽取路径替换为 D1 提取器；提取器返回 `null`（无候选）时保持既有 IllegalStateException → FIX 路由。

### D2. 主 Agent 正文提取是 shared extractor 的伴生结果

新增公共方法（`KnowledgeCurationGraphFactory`）：`tailJsonExtract(text)` 返回 `{jsonStart, jsonText}`（或 null）。`jsonObject` 内部复用；`KnowledgeCurationRunExecutor` 的 `finalReply`/`projectSummary` 用 `jsonStart` 得到正文（`text.substring(0, jsonStart).trim()`，空则无正文）。好处：提取规则单点定义，正文与结构化块永不错切。

### D3. finalReply 优先级：正文 → memo → coordinator 摘要 → 抛错

`finalReply` 改为：

1. recoveryInfo（恢复门，不变）；
2. 主 Agent 轮次：`tailJsonExtract` 得到正文；正文非空 → 返回正文；正文空 → `mainTurnResult.memo()` 非空 → 返回 memo；否则抛 IllegalStateException（既有兜底）；
3. coordinator 摘要（仅当主 Agent 轮次解析整体失败时的既有回退保留）。

注意顺序：主 Agent 轮次「结构合法但无正文无 memo」→ 抛错走失败路径，**现状即是如此**（runId=63 是投影路径，不是 finalReply 路径）；正文缺失但有 memo 是新增的降级分支，不失败。memo 的可空性由契约保证（record 字段可空，规范化仅截断）。

`mainRoute` 同步改造（同一语义的另一消费方）：CHAT/TURN_DONE 的「可见回复」校验从 `summary 非空` 改为「正文非空 或 memo 非空」，两者均缺时继续进入修复回路（提示文案「没有可见回复」不变）。

### D4. projectSummary（AGENT_STAGE 公开投影）：主 Agent 正文优先，memo 回退

`persistAgentResult.projectSummary` 的主 Agent 分支：`tailJsonExtract` 成功 → 正文（经既有 `MAX_PUBLIC_PROGRESS_CODE_POINTS=1000` bounded 截断）→ 正文空则 memo → memo 空则与解析失败同路径「全文截断」（213a4ab 已建立的降级姿态）。专家节点分支不变。**公开投影仍是降级段而非失败段**：任何提取失败都不打废 run。

### D5. MainTurnResult 契约更名与规范性

`MainTurnResult(MainAction action, String memo, List<String> expertCalls)`：`summary` 更名 `memo`（JSON 键随之变化，旧键 `summary` 不再识别——对旧定义 run 恢复本就不兼容，见 D6）。compact constructor 规范化：`memo` 非空白时截断到 100 码点（防超长说明淹没投影；防御式截断而非拒绝——runId=63 教训：投影字段的格式防御不阻塞 run），null 保留；`expertCalls` 沿用既有 List 规范化。

### D6. GRAPH_DEF_VERSION 前缀推进

`"knowledge-curation-sess-v3"` → `"knowledge-curation-sess-v4"`。指令定义（main_agent.md 输出契约）必然改变 digest，前缀推进让守卫对在途旧定义 run（WAIT_INPUT/恢复路径）判定不兼容——**既有预期行为**（digest 不一致即 AGENT_DEFINITION_MISMATCH），非本变更新增语义。不推进则新旧定义同前缀而 digest 不同，守卫行为一样严格，但版本号无法表征契约代际。

### D7. 消息持久化与投影内容

主 Agent 状态键仍存**原始消息**（正文+尾缀 JSON，供图形 state 与后续轮上下文）；只有落库消息（`finalReply` 结果）与 AGENT_STAGE 事件（`projectSummary`）是提取后的正文/摘要。SUB_AGENT 消息（专家）路径不变。

### D8. 测试策略

- **现有契约驱动型用例全部需要同步输出格式**：DriveIT/GraphRunIT/GraphRoutingTest/AtlasAgentEvalDeterministicIT 的脚本化主 Agent 响应改为「正文 + 尾部 JSON」或「纯尾部 JSON（memo 场景）」；脚本模型仍按角色识别，不改变既有驱动方式。
- **新增双通道回归用例（DriveIT）**：
  1. 正文含花括号与 JSON 示例 + 尾部 JSON —— 路由正确、finalReply 为完整正文（红灯：旧提取器把正文当 JSON，解析失败或路由错乱；绿：正文完整）；
  2. 正文缺失、仅尾缀（含 memo）—— 最终回复回退 memo、轮次正常结束、无 failKnowledge（红灯：新契约下正文缺失曾直接失败或无回退；绿：降级成功）；
  3. 正文与 memo 均缺失但结构合法 —— 与现状一致的失败/修复路径（防「不抛错」回归）。
- 先写失败测试（红灯）再最小实现，再全量 verify（配方：nohup 脱离 + until MAVEN_EXIT 守护）。
- 不加无业务价值的测试：3 个新用例对应 3 个 spec 场景（正文优先、memo 回退、两者皆缺）。

### D9. memo 达到上限 = 违规形态（runId=68 实测补充，2026-08-30）

生产 runId=68 实测：模型把约 400 字的完整回复写进 JSON `memo` 且正文为空，`MainTurnResult` 的 100 码点防御截断把回复切成「……（或只对拿不」半句，finalReply/projectSummary 按降级规则把半句当作最终回复展示——双通道契约的本意（正文完整、memo 仅供降级）被击穿。

**决策**：把「正文缺失且 memo 触顶（≥100 码点）」定义为**模型违反双通道的强信号**，与「正文/memo 皆缺」同路径进入 fix_main_agent 修复回路（mainRoute 校验)，修复指导明确「完整回复写在可见正文、memo 仅 ≤20 字动作摘要」（validationErrorSummary 为 main_agent 生成专项文案）。同时 main_agent.md 警示「长 memo 会被判违规退回重写」，并把 memo 建议长度从「不超过 100 字」收紧为「≤20 字」。

**理由**：合法使用下 memo 是极短摘要，生产输出几乎不可能逼近 100 码点；触顶说明模型仍走「回复塞 JSON」的旧习惯——静默截断展示半句危害用户，而进修复回路（一次模型重试）成本低且把错误演化为「重写正文」的正确路径。memo 未触顶（<100 码点）的降级放行不变（spec「正文缺失但提供 memo」场景）。

覆盖：`mainChatAndTurnDoneRequireVisibleReplyPerDualChannel`（memo=100 码点 → fix_main_agent；短 memo → 放行）、`mainReplyLongMemoRequiresRepairThenRewritesBody`（长 memo → 修复回路 → 正文重写 → completeKnowledge(完整正文)）。

## Risks / Trade-offs

- **[契约收紧：JSON 必须在消息尾部]** 尾部提取对「JSON 后附加文字」不再容忍，旧格式输出入修复回路 → 缓解：契约写入 main_agent.md 且 FIX_<role> 修复指导里已含「输出必须为……JSON」提示；生产负载无旧格式存量（该契约本次才引入）。
- **[正文不含尾部 JSON 的纯文本输出]**（模型只说话不输出结构）→ 修复回路，这是既有语义（无 action 无法路由），无行为回归。
- **[memo 截断丢失信息]** 接受：memo 是降级摘要，正常路径根本不用它。
- **[旧线程在途 run 被守卫判不兼容]** 既有部署语义（D6），部署窗口内禁止恢复旧定义 run；回滚策略见下。
- **[双通道与专家 summary 共存的两套「摘要」概念]** 易混淆 → main_agent.md 与 Javadoc 明确「memo 仅供降级」「专家 summary 是公开行动摘要」。

## Migration Plan

1. 代码（契约/解析/消费端）与指令定义（main_agent.md）**同批部署**——旧代码读新指令（digest 不匹配被守卫捕获，启动即 FIX 前未入库 run 不受影响……实际为新指令 run 需新代码解析）。
2. 部署后行为分层：新 run 双通道；旧定义在途 run 恢复被判不兼容（既有守卫语义，报表可查）；已完成 run 的数据与页面不受影响（字段名不变）。
3. 回滚：前端无需动；后端回滚版本即回旧契约——部署前未开始的新 run 用回滚后的旧指令，无事；恢复路径上的新契约 run 会被旧守卫判不兼容，接受（与正向对称）。

## Open Questions

无。前端在 MVP 回归中确认 AGENT_STAGE/最终消息消费即可，不阻塞实现。
