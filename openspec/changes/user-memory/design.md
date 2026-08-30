# Design: user-memory

## Context

见 proposal.md - Why。当前知识整理会话里"用户过去的偏好"只存在于 `conversationHistory`（最近 4 轮/8000 码点）与滚动 `conversationSummary` 中，跨 run 即失。会话图顶层主 Agent（`main_agent`）负责跟用户对话、持有三个专家 `AgentTool`、路由下一步（`MainTurnResult`）；其上下文由 `ContextAssemblyService.buildMessages()` 按节点入口组装，业务后缀块汇总在 `purposeBlock()`（`【当前指令】/【任务】/【任务状态摘要】/阶段标记` 等，`ContextAssemblyService.java:187-257`）。框架事实：Spring AI Alibaba 1.1.2.3；工具以 `@Component + @Tool` 经 `MethodToolCallbackProvider` 注册（`KnowledgeCurationTools` 先例）；工具内范围校验统一回查 `agent_run`（`KnowledgeCurationTools.scope()`）；主 Agent 当前无业务 Tool（`MAIN_AGENT_TOOLS=List.of()`）。

## Goals / Non-Goals

Goals：
- 一张 `user_memory` 表 + `memory` 模块（REST 管理 + `memory.api.MemoryService` 契约），共享语义、无用户隔离；
- 主 Agent 入口摘要预载（≤30 条/条≤300/块≤1800，GLOBAL∪本项目，全文匹配+频次打分）；
- `memory_search/memory_read/memory_write` 三工具只注册主 Agent；写入内嵌「值得写/重复/冲突仍写」LLM 判断；冲突双写、模型采纳时刻择优；
- 全部行为可测试可观测（AGENTS.md §9 证据日志），测试只选有业务用例的少量代表。

Non-Goals：
- 不做前端记忆管理页（本期只落地接口）、不做 MCP 暴露、不做向量语义检索、不从项目问答会话提炼、不做 membership/分享权限、不做草稿—确认状态机、不做记忆到期衰变；
- 不改专家（retriever/drafter/reviewer）与完整子图 coordinator 的工具集与上下文语义（记忆对其不可见）。

## Decisions

1. **模块归属与依赖**：新增 `memory` 模块（AGENTS.md §7），`memory.api.MemoryService` 为唯一跨模块契约（含不可变类型与枚举）；依赖方向 `Agent → Memory(api)`、`Memory → Project(api)`。判断用 LLM 调用在 memory 模块内直接调现有 ChatModel（与 `ContextCompressionService` 同先例：内部受限调用、非 Agent、无 Spec/Tool/Saver）。备选（否决）：放 knowledge 模块——记忆不是知识内容且不得进入知识检索，语义冲突；放 agent 模块——管理接口与模型编排无关，且 agent 不应持有所有业务表的访问权。

2. **表结构（单表，无唯一键）**：`user_memory(id, scope_type, project_id, project_identifier, category, title, summary, content, source_type, source_run_id, source_conversation_id, status, use_count, last_used_at, created_by, updated_by, created_at, updated_at)`。不建唯一键——语义去重由判断层负责，且"冲突双写"必须允许两条共存，唯一键会错误阻挡；无 operator 列（全共享，已确认）；无 vector 列（关键词检索，已确认）。`summary ≤300`、`content ≤4000`、`title ≤200` 由 CHECK 与写入校验双层保证。

3. **检索打分器（确定性）**：一次 SQL 预筛（`status=ACTIVE AND scope_type='GLOBAL' OR project_id=?`，查询词对 title/summary/content 做 ILIKE 初筛，候选 ≤100）→ Java 打分（CJK 二元组 + 空白分词；标题命中×3 + 摘要命中×2 + 正文命中×1 + `log2(use_count+1)`；同分按 `last_used_at DESC, id DESC`）→ 无命中时兜底热度 Top3（按 use_count），预载总量 ≤30。查询词 = 原始目标 + 最近用户消息（各截断≤100 码点）。打分器为纯函数（单测友好）。备选（否决）：pg 全文索引/tsvector——数据量级小、后续如需语义可平滑升级 pgvector。

4. **注入门控与 run 快照固定**：`ContextAssemblyRequest` 增加 `projectId`（会话项目，可为 null）；`purposeBlock` 组装时按 `agentNode` 判断，仅顶层主 Agent 节点注入【用户记忆】块（块序紧跟【当前指令】前，算高优先级 suffix，裁剪守卫优先保留；实现时以 `ContextDeterministicCompressor` 实际块序为准并补测试）。**快照固定**：一次 run 内主 Agent 会被多次组装（`START→prep_main`、子图完成后 `set_main_resume→prep_main` 汇总、恢复重试），记忆块 SHALL 在 run 首次主 Agent 组装时一次性检索并按 runId 固定（`MemoryPreloadSupply` 缓存，run 终态逐出 + 惰性 TTL），同 run 内再次组装复用同一快照；run 中发生的记忆读写（频次变化、新增记忆）MUST NOT 改变当前 run 前缀，快照只在下一 run 重新计算——保证「一次用户请求开始后确定、直到该请求完成」的前缀稳定性。检索失败 → WARN + 跳过注入，不阻塞主链路。摘要行格式：`[分类/范围] 标题：摘要`，末尾占位指引"如需全文用 memory_read；冲突记忆按当前任务语义择优"。`agentNode=MAIN` 之外（coordinator/retriever/drafter/reviewer）不注入也不挂工具。

5. **工具注册与范围**：新增 `MemoryTools`（`@Component`，`@Tool` 三方法），仅加入会话图主 Agent 的 `ToolCallbackProvider`（`MAIN_AGENT_TOOLS` 由 `List.of()` 扩为专家 AgentTool + 内存工具），主 Agent Agent 定义 `tool_names` 白名单同步，`validate()` 启动 fail-fast 不变。工具内范围校验复用 `KnowledgeCurationTools.scope()` 模式：回查 `agent_run`（taskType=knowledge_curation、RUNNING、操作者/会话/项目一致）；`memory_read`/`memory_search` 只返回「GLOBAL ∪ 本 run 项目」记忆（摘要/全文有界），**只作偏好上下文、不改变当前 run 前缀**；`memory_write` 的 scope 由 run 决定（会话挂项目→PROJECT，否则 GLOBAL），模型不可指定，且仅提炼对话中用户明确表达的偏好（一次性任务指令由判断链拒写），预算与判断链兜底"不能随便读/写"。

6. **memory_write 判断链**：入参候选 ≤3（title+content，可带 category）→ 校验（枚举/长度/数量）→ 取同 scope 相近既有记忆（同分类或候选关键词命中，ACTIVE+DISABLED，≤50 最新）→ 单次 ChatModel 调用逐条判 `CREATED / CONFLICT_CREATED / SKIP_DUPLICATE / SKIP_NOT_WORTH`（冲突仍写，输出 `conflictsWith` 仅入日志）→ 按结果写入或缺省 summary（judge 未给则取正文前 300 码点）。预算：每工具调用 ≤3 条；本 run（`source_run_id`）累计新增 ≥10 条后拒写并说明需人工管理。判断过程失败 → 抛工具错误（可重试），不静默丢弃。人工 REST 路径不做语义判断（判断归人），但字段/scope 校验不可绕过。

7. **审计与日志**：`created_by/updated_by` 记会话操作者或 ADMIN 用户名（无权限语义，仅审计）；预载/加载/写入关键点结构化 INFO/WARN 日志（scope、命中数、判断结论、预算余量），不记正文全文。

## Risks / Trade-offs

- [每次内存写入附带一次 LLM 判断，成本/延迟] → 预算 ≤3 条/次调用，仅工具触发；判断失败返回可重试错误，不产生无判断数据。
- [冲突记忆双写增加上下文噪声] → 摘要 ≤300 码点；命中越多越按打分收敛；人工可停用任意一条。
- [注入 30 条摘要的成本上限] → 块总长 ≤1800 码点，且只有匹配命中多时才接近上限；后续可按观测收紧默认值。
- [专家无法感知记忆] → 偏好由主 Agent 以指令传达（如 `draftInstruction` 中写明格式/模板要求），专家保持干净、记忆原文不出现（spec 场景已约定）。
- [主 Agent 工具面扩大] → 仅三个只读偏好类工具 + 判断型写入工具，均受 run 范围校验与预算约束；不影响专家白名单与发布边界。

## Migration Plan

1. 一次性部署：`V10__add_user_memory.sql` 仅新增表；无既有数据迁移；REST 新增端点无兼容性影响。
2. 回滚：撤销本次实现（工具不注册即主 Agent 行为回到原状；表与接口保留不影响主链路），无需数据回滚。
3. 主 Agent spec 指令（`agent-specs/knowledge-curation/` 主 Agent 定义）随本次更新，与 `KnowledgeAgentDefinitionService` 摘要校验一致（启动 fail-fast 覆盖）。

## Open Questions

无。记忆管理前端页面属于后续前端 change。
