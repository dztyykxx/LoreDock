## Why

知识整理多 Agent 会话和文档产出强依赖用户的长期偏好（格式、模板、内容取舍、沟通风格），但目前偏好只存在于会话历史里、跨 run 即失，调度 Agent 每轮都要靠用户重复说明，产出风格不可预期。需要一个可跨会话沉淀、可检索的"用户记忆"能力：调度 Agent 在对话中按上下文语义决定如何做下一步（推进到最后仍等人工发布），知识整理对话把用户偏好提炼成可供后续使用的记忆。

注：MVP 需求基线原本把"项目长期记忆"列为当前阶段不建设项（需求文档 v1.0；开发计划同），本次为用户在当前任务中的明确要求，优先于基线；记忆按"偏好而非事实"约束，不改变既有"调度 Agent 不得以长期记忆自行创建任务、不得扩大工具/范围/发布权限"的架构决策。

## What Changes

- 新增 `memory` 业务模块与一张 `user_memory` 表：`GLOBAL/PROJECT` 两种范围（共享语义、不做用户隔离）、分类枚举（格式/模板/内容/风格/流程/其他）、title/summary/content 三段（摘要用于注入，全文按需加载）、来源溯源、`ACTIVE/DISABLED`、使用频次；
- REST 管理：`GET /api/memories`（登录可读）、`POST/PUT/DELETE /api/admin/memories`（仅 ADMIN：创建、编辑、停用/启用、删除）；
- 知识整理会话主 Agent（会话图顶层 `main_agent`）：
  - 入口注入【用户记忆】摘要块：全文匹配 + 项目类型（GLOBAL ∪ 当前会话项目）、仅 `ACTIVE`、硬上限 30 条摘要（≤300 码点/条），仅主 Agent 节点注入，专家节点无记忆块无记忆工具；
  - 新业务工具仅注册给主 Agent：`memory_search`（摘要级搜索）、`memory_read`（按需加载全文并计入使用频次）、`memory_write`（提炼写入，内嵌"值得写—重复—冲突仍写"三方语义判断，冲突记忆并存、由模型在采纳时刻按上下文语义择优）；
  - 记忆不进入知识检索、不得作为证据引用、不扩大 Agent 工具/范围/发布权限、不触发或创建任务；
- 提炼从"轮次后自动抽取"改为"主 Agent 主动通过 `memory_write` 触发"，写入自动生效、可随时编辑/停用/删除（无草稿—确认状态机）。

## Capabilities

### New Capabilities

- `user-memory`: 用户记忆的数据模型与生命周期（范围/分类/状态/溯源/频次）、REST 读写管理、关键词+过滤+频次的确定性检索打分、按需全文加载、写入时的"值得写/语义重复/冲突仍写"判断与预算约束。

### Modified Capabilities

- `knowledge-curation-agent`: 主 Agent 新增记忆注入块与 `memory_search/memory_read/memory_write` 工具（仅主 Agent 可见）；记忆使用边界（非证据、非任务、非权限拓展）；提问与起草环节按记忆偏好执行的约束。

## Impact

- 后端新增模块：`backend/src/main/java/io/github/loredock/memory/**`（api/controller/service/mapper/model），依赖方向 `Memory → Project`、`Agent → Memory`（经 memory api）；
- Agent 模块改动：`MemoryTools` 注册到会话图主 Agent、`ContextAssemblyService` 主 Agent 门控注入、主 Agent spec 指令补充；
- 新增 Flyway 迁移 `V10__add_user_memory.sql`；无前端页面（本期不做记忆管理 UI，管理接口先落地）；不做 MCP 暴露、不做向量检索、不做项目问答会话提炼。
