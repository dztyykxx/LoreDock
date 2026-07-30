## Context

参见 `proposal.md` 的动机与范围。T6A 已提供 `StartProjectQaRunUseCase`、`AgentRunQueryUseCase`、`AgentEventQueryUseCase`，以及持久化 `ACCEPTED → RUNNING → 终态`、单调事件、证据和引用的 Agent 运行时；当前没有浏览器 Controller、SSE、完整问题存储和问答历史。`StartProjectQaRunService` 目前在接受短事务提交后立即把携带问题正文的内存请求交给有界执行器，这一顺序不能直接嵌套到新的 Web 问答事务，否则外层事务尚未提交时工作线程可能读不到运行。

前端是 Vue 3 + TypeScript + Vue Router，使用浏览器 cookie 会话和轻量 API 模块，没有全局状态库。Pencil `04 · 项目问答 / 引用展开` 已确认桌面布局由最近问题侧栏、固定项目/分支、对话主区、三种可信状态、输入区和引用侧栏组成；UI Kit 的三种稳定语义为“有可靠依据”“来源存在冲突”“当前知识库没有足够依据”。实现必须使用真实快照和事件，不得保留样例答案作为成功状态。

T6A 的引用快照已有知识标题/更新时间以及代码路径、分支、commit、索引时间，但尚未持久化知识范围与公开来源字段，也没有持久化 `AnswerBasis`。T7 若只在展示时重新查询当前文档，会把运行发生后的文档修改冒充当时证据，因此必须补齐运行时来源快照，而不是动态拼接当前状态。

## Goals / Non-Goals

**Goals:**

- 在不复制 Agent 范围、工具与可信结果规则的前提下，提供契约明确的问答创建、历史、详情和 SSE 入口。
- 使问答记录与 Agent 运行的首次接受具备原子可见性，并保证只有事务提交后的运行才会调度。
- 以数据库快照和持久化事件为事实来源实现断线续读，不要求浏览器连接持续在线。
- 保存最少但足够的用户可见消息、事实依据类型和来源快照，支持可信展示与反馈追溯。
- 用简单、可审计的知识缺口状态流转承接成员反馈，不触发知识写入或 Agent 自动发布。

**Non-Goals:**

- 不把历史问答作为后续模型上下文，不实现多轮记忆、会话摘要、对话分支或跨项目聊天。
- 不引入 WebFlux、消息队列、Redis Pub/Sub 或新的前端状态框架；MVP 内部试用并发以有界数据库轮询即可承载。
- 不实现 T6B 的 Graph 检查点恢复、多 Agent、项目记忆或 Skill 管理，也不实现 T10 的自动知识体检。
- 不为知识缺口增加指派、评论、优先级、SLA、通知或外部工单同步。
- 不提供移动端专项布局；桌面页面仍需保持键盘可操作和基本可访问性。

## Decisions

### 1. 新增 `qa` 与 `knowledgegap` 业务能力，入口层只编排既有 Agent 用例

后端新增两个按能力分包的边界：

- `qa.domain`：问答记录身份、消息角色、Web 可信状态与不可变范围值；不依赖 Spring、HTTP、MyBatis 或 Agent 框架。
- `qa.application`：`CreateWebQaQuestionUseCase`、`QueryWebQaQuestionUseCase`、`StreamWebQaEventsUseCase`，以及问答/消息仓储端口和面向 Web 的快照 DTO。它只依赖 Agent 的公开应用用例，不读取 Mapper 或框架消息。
- `qa.infrastructure.persistence` 与 `qa.infrastructure.web`：MyBatis-Plus 实体/Mapper/仓储、REST/SSE Controller 和安全 DTO 映射。
- `knowledgegap.domain/application/infrastructure`：反馈类型、状态机、创建/管理用例和持久化/HTTP 适配。关联问答通过 `qa.application` 的只读端口校验，不直接查询问答表。

Controller 从现有 Web 会话取得操作者 ID/角色并传给应用命令；应用层重新校验记录归属和项目可访问性。`/api/admin/knowledge-gaps/**` 继续由既有服务端角色规则保护，前端隐藏控件不参与授权结论。公共 Java 接口、DTO、实现和状态机分支使用中文 Javadoc/注释，重点说明范围固定、404 防枚举、提交后调度、终态投影和反馈不修改正式知识的原因。

备选方案是把 Controller 直接接到三个 Agent 用例并由前端保存历史。该方案无法在刷新后恢复完整问题，也无法建立反馈与问答的稳定关联，因此不采用。另一个方案是把 Web 字段加入 `agent_run`；这会破坏运行追踪最小化边界，并让后续非 Web Agent 任务承担无关字段，也不采用。

### 2. Web 问答与 Agent 接受共享短事务，运行只在最外层事务提交后调度

`CreateWebQaQuestionUseCase` 使用一个短事务完成以下步骤：

1. 规范化操作者、客户端幂等键、项目、分支和问题，并按 `(operatorId, idempotencyKey)` 查询既有 Web 问答；相同请求直接返回，摘要不同则冲突。
2. 用 `web-qa:` 加客户端幂等键 SHA-256 得到不超过 128 字符的 Agent 幂等键，调用既有 `StartProjectQaRunUseCase` 完成 Skill/模型可用性、项目/分支、活动快照、知识 generation 与请求限制校验。
3. `AgentRunAcceptanceService` 以默认 `REQUIRED` 事务传播加入当前事务，插入 `agent_run` 和首条事件；问答服务随后插入 `web_qa_question` 与唯一 `USER` 消息。并发创建使用数据库唯一键和 `INSERT ... ON CONFLICT DO NOTHING` 后复读，比较请求摘要，不以捕获后继续使用已标记回滚的事务实现幂等。
4. Agent 调度从 `StartProjectQaRunService` 的立即调用重构为提交后协调器：存在事务时注册 `afterCommit`，没有外层事务时在接受事务已经返回后立即调度。事务回滚不产生执行任务。
5. 队列拒绝仍通过独立短事务把已提交运行终结为 `FAILED/AGENT_RUNTIME_BUSY` 并追加事件。提交后失败处理使用 `REQUIRES_NEW`，避免在已完成事务上下文中写入。

问题正文只存在于 Web 消息表和调度所需的短生命周期内存对象；`agent_run` 继续只保存摘要与 Unicode 长度。创建接口只有在问答、用户消息、运行和首事件共同提交后返回 HTTP 202。这样既不需要分布式事务，也不会让工作线程读取未提交运行。

备选方案一是“先启动运行，再插入问答记录”，但进程可能在两步之间退出并留下无法从 Web 找回的孤立运行。备选方案二是建立数据库 outbox/独立任务消费者；对当前单体和内部试用规模过重，且 T6A 已有可靠的进程内有界调度器，因此本次仅把调度时机修正为最外层提交后。

### 3. V7 追加问答、消息、反馈表和回答依据列，不修改既有迁移

新增 `V7__create_web_qa_and_knowledge_gap_tables.sql`，不修改 V1～V6：

| 表/变更 | 核心字段与约束 |
|---|---|
| `web_qa_question` | ID、操作者、客户端幂等键/请求摘要、项目/分支 ID 与快照名称、唯一 `run_id`、创建时间；`(operator_id, idempotency_key)` 唯一，项目/分支/运行使用外键 |
| `web_qa_message` | ID、问答 ID、`USER|ASSISTANT`、正文、结果类型/拒答原因、创建时间；`(question_id, role)` 唯一，单个 T7 问答最多一条用户消息和一条终态公开消息 |
| `knowledge_gap_feedback` | ID、操作者、幂等键/请求摘要、项目/分支、可空问答/运行外键、类型、状态、问题正文、可空说明、结果/拒答/错误摘要、创建/更新审计；状态和类型使用数据库检查约束 |
| `knowledge_gap_feedback_citation` | 反馈与运行证据的有序关联；复合外键保证引用属于关联运行，避免把核心关系塞入 JSONB |
| `agent_run.answer_basis` | 可空 `BUSINESS_RULE|CURRENT_IMPLEMENTATION|MIXED`；新应用在完成运行时写入，旧完成记录在读取时按已验证引用类型稳定推导 |

`web_qa_message.content`、反馈问题和说明都设置数据库字节上限及应用 Unicode code point 上限；数据库不保存 HTML。所有持久化实体与领域/API DTO 分离，并逐字段使用 `@TableName`、`@TableId`、`@TableField`。分页使用 `(created_at, id)` 不透明游标，不使用随着并发插入漂移的 offset。

`ASSISTANT` 消息是 `agent_run` 终态的可恢复投影，而不是第二个终态事实来源。问答详情和历史读取终态运行时调用幂等 materializer，以 `INSERT ... ON CONFLICT DO NOTHING` 写入经校验的回答或拒答；SSE 处理终态时也触发同一路径。投影暂时失败时接口仍从 Agent 快照返回正确终态并记录告警，下次读取自愈；失败/终止运行不创建伪助手消息。

采用单表直接保存问题/答案可少一次 join，但难以用唯一约束表达消息角色与终态投影，也会把问答身份和正文生命周期耦合；本设计保留最小的两表结构。完整通用多轮会话模型不在范围内。

### 4. 运行终态补齐 `AnswerBasis` 和当时的安全来源快照

完成运行时把 `TrustedProjectQaResult.basis` 写入 `agent_run.answer_basis` 并加入 `AgentRunSnapshot`。对迁移前 `ANSWER`，读取适配器只根据最终引用类型推导：仅知识为 `BUSINESS_RULE`、仅代码为 `CURRENT_IMPLEMENTATION`、两类同时存在为 `MIXED`；不能满足既有引用不变量的历史行按安全错误处理，不猜测。

知识工具登记证据时，把当时 `KnowledgeSearchResult` 的以下可公开字段写入已有 `agent_evidence.metadata`：schema 版本、范围类型、来源类型、Wiki HTTP(S) URL或原上传文件名。人工整理来源仅标记为 `MANUAL`，不复制完整整理说明。代码证据继续以 `source_updated_at` 表示该快照的索引时间。`AgentCitationSnapshot` 新增类型化的安全来源元数据，Web Mapper 不读取当前文档或当前活动快照来覆盖运行时快照。

这保证页面展示的是“本次回答使用的版本”。备选方案是根据 `document_id` 和 `snapshot_id` 在请求时回查当前对象；该方案在文档编辑、归档或活动快照切换后会产生时间穿越，因此不采用。JSONB 只保存版本化的可公开来源附属字段；项目、分支、文档、快照和运行外键继续使用规范化列。

### 5. HTTP 契约以问答记录为聚合根，SSE 不直接暴露任意运行 ID

接口固定为：

- `POST /api/projects/{identifier}/qa/questions`：创建或幂等返回问答，HTTP 202。
- `GET /api/projects/{identifier}/qa/questions?limit=&cursor=`：当前操作者的最近问题，默认 20、最大 50。
- `GET /api/projects/{identifier}/qa/questions/{questionId}`：问题、固定范围、运行快照、可信状态、最后事件序号和引用。
- `GET /api/projects/{identifier}/qa/questions/{questionId}/events`：SSE 续读，支持 `Last-Event-ID` 或 `afterSequence`；两者同时存在但值不一致时返回 HTTP 400 `INVALID_REQUEST`。
- `POST /api/projects/{identifier}/knowledge-gaps`：成员或管理员幂等创建，HTTP 201。
- `GET /api/admin/knowledge-gaps` 与 `GET /api/admin/knowledge-gaps/{feedbackId}`：管理员过滤分页与详情。
- `PATCH /api/admin/knowledge-gaps/{feedbackId}/status`：管理员幂等推进一个状态。

问答详情先以 URL 项目、当前操作者和问答记录授权，再调用 `AgentRunQueryUseCase`；任一不匹配统一映射为 `QA_QUESTION_NOT_FOUND`。API 不提供按任意 `runId` 读取的 Web 入口。请求/响应 record 与领域模型分离，错误继续使用平台统一结构和 trace ID。

公开事件不原样序列化 Spring AI 或数据库 payload。HTTP Mapper 把既有事件映射为版本 1 的有限 DTO：阶段、工具名/数量、来源数量、已校验正文增量、拒答/终态和错误码。问题、完整证据、隐藏提示、内部路径、模型异常和会话令牌均不进入事件。

### 6. Spring MVC SSE 使用有界轮询持久化事件，并在终态后以快照收敛

继续使用现有 Spring MVC；每个 SSE 连接由专用、有上限的调度执行器驱动，默认每 500ms 查询最多 200 条 `sequence > cursor` 的持久化事件。事件逐条发送，SSE `id` 等于 Agent 序号；15 秒无业务事件时发送不带 `id` 的注释心跳。发送失败、客户端断开、会话失效、项目停用或达到“运行总超时 + 有界宽限”时清理任务和 emitter，不占用 Web 请求线程。

连接建立前验证 cookie 会话并捕获不透明会话标识、操作者和问答范围；每轮读取通过身份适配端口确认会话仍有效，再由问答/Agent 查询用例复核操作者与项目。会话标识只存在内存，不写日志或事件。服务端不维护不可恢复的答案缓存；连接终止后浏览器以最后业务序号重连。到达终态且已发送所有持久化事件时发送终态、触发消息投影并完成 emitter；客户端随后 GET 详情，用快照覆盖本地阶段、正文、原因和引用。

单体内存发布订阅延迟更低，但会在进程重启或订阅竞态时丢事件；数据库轮询直接复用 T6A 的持久化顺序契约并更适合 T7。WebFlux 会引入第二套 Web 编程模型和连接数据库方式，本次收益不足。

### 7. Web 可信状态由服务端快照确定，前端只负责渐进展示

服务端按固定映射生成 `trustState`：

| 运行事实 | Web 可信状态 |
|---|---|
| `COMPLETED/ANSWER` 且引用有效 | `SUPPORTED`（有可靠依据） |
| `COMPLETED/REFUSAL` + `SOURCE_CONFLICT` | `CONFLICT`（来源存在冲突） |
| 其他 `COMPLETED/REFUSAL` | `INSUFFICIENT`（当前知识库没有足够依据） |
| `ACCEPTED/RUNNING` | `PENDING` |
| `FAILED/TERMINATED` | `ERROR`，绝不当作可信回答 |

前端新增 `/projects/:identifier/qa` 路由和 `qa.ts`、`knowledgeGaps.ts` 类型化客户端，不引入 Pinia。页面状态由页面级 composable 管理：进入页面加载项目详情与历史；选中问题先读取详情，再从最后序号订阅；切换记录或离开路由时关闭旧 EventSource；每次终态重新获取详情。新问题通过 `crypto.randomUUID()` 生成幂等键，网络结果不确定时保留原键，用户主动“重新提问”才生成新键。

组件按 Pencil 结构拆为最近问题侧栏、范围栏、消息/阶段区、可信状态徽标、提问框、引用抽屉和反馈对话框。项目/分支选择只构造下一次请求；已接受消息始终渲染服务端固定范围。回答正文以纯文本保留换行，不使用 `v-html`；Wiki 链接只接受后端校验后的 HTTP(S) URL并使用 `noopener noreferrer`。来源抽屉关闭后恢复触发按钮焦点，状态文字不只依赖颜色。

### 8. 知识缺口保存提交时事实快照，状态机不触发知识副作用

创建反馈时，应用服务先解析项目/分支，再按可选问答 ID读取同一操作者的问答聚合。有关联问答时忽略客户端对问题、结果和引用的任何伪造字段，复制用户问题、运行终态摘要并把最终引用写入关联表；无关联问答时要求显式问题。请求摘要覆盖规范化项目、分支、类型、关联记录、问题和说明；日志只记录长度、摘要、反馈 ID、范围、类型和状态。

领域状态机只允许 `OPEN → ACKNOWLEDGED → CLOSED`，同状态幂等；数据库条件更新防止并发管理员覆盖。状态变更独立短事务并记录前后状态、操作者、traceId 和时间。反馈永远不调用知识文档命令、索引、Agent 写工具或发布接口。管理员查询按数据库条件强制项目/分支/类型/状态过滤，不能先全量加载再在 Controller 隐藏。

### 9. 测试集中保护跨边界业务事实和实际执行证据

按 TDD 逐行为推进，优先测试：

- 领域单元：可信状态映射、反馈类型输入和单向状态机；每个测试用中文注释说明保护目的。
- 应用服务：同键重试、同键冲突、项目/分支固定、历史不进入新模型输入、404 防枚举、终态消息幂等投影、反馈关联范围与无知识副作用。
- 真实 PostgreSQL 集成：V1→V7 和 V6→V7、显式实体映射、事务回滚不调度、并发问答/反馈幂等、游标稳定、反馈条件状态更新和引用复合外键。
- Web/SSE 集成：使用脚本化 Fake Model 真实经过创建、Agent 事件持久化、SSE 序号、断线续读、终态详情、会话失效与模型不可用；测试从响应和数据库状态输出运行 ID、项目/分支、序号范围、终态、引用数和错误码。
- 前端：空历史、范围锁定、SSE 重连/去重、终态快照校正、三种可信状态、引用抽屉、反馈幂等重试和模型故障不阻塞普通入口。

不为 DTO getter、框架序列化或静态样式堆测试。生产日志在问答创建、SSE 建立/续读/关闭、终态投影、反馈创建/状态转换和失败处记录 traceId、稳定 ID、范围、序号/数量、状态前后和错误码，不记录问题、答案、说明、URL、令牌或证据正文。

## Risks / Trade-offs

- [每个 SSE 连接轮询 PostgreSQL 会增加查询量] → 使用专用有界执行器、500ms 可配置间隔、批量上限、终态立即关闭和连接总时限；实际内部试用数据用于后续决定是否引入通知机制。
- [提交后回调不是持久 outbox，进程可能在提交与调度之间退出] → T6A 启动恢复会把遗留 `ACCEPTED` 运行终结为 `AGENT_RUN_INTERRUPTED`，用户可用新键重试；T6B 才提供检查点恢复，不在 T7 引入半套恢复。
- [问题原文与反馈说明属于内部业务内容] → 仅在独立授权表保存，限制长度和访问范围，日志/事件只记录摘要与数量；不向其他操作者开放历史。共享 `MEMBER` 账号下的操作者本身按需求是同一身份，因此其历史也会共享，这是现有账号模型的明确限制。
- [终态助手消息是运行快照的派生副本] → `agent_run` 始终为事实来源，唯一约束和幂等 materializer 防止重复，详情读取可在投影失败时直接返回快照并后续自愈。
- [旧运行没有 `answer_basis` 和知识公开来源 metadata] → 依据最终引用类型安全推导 basis；旧知识引用缺失的附属来源显示“历史运行未记录”，不回查当前文档伪造当时快照。
- [Wiki URL 或上传文件名是不可信元数据] → 后端只接受既有领域校验后的字段，前端纯文本显示文件名并限制链接协议，不渲染 HTML。
- [来源面板和阶段事件可能被误认为完整证据] → UI 明确标注为引用摘要，完整文档仍通过既有受范围控制的文档详情读取；T7 不把代码正文或完整知识正文塞入 SSE。

## Migration Plan

1. 先用失败测试冻结现有 T6A 直接启动行为，再重构“最外层事务提交后调度”，验证无外层事务时行为与错误码不变。
2. 添加 V7、实体和迁移集成测试；验证空库 V1→V7、现有 V6→V7、重复启动、并发唯一约束和旧应用面对新增表/可空列的兼容性。
3. 按接口优先实现问答/消息与反馈应用端口、状态机、仓储和 HTTP DTO，再接 REST 与 SSE；模型与搜索实现不变。
4. 扩展回答依据和证据安全 metadata，在 Fake Model 端到端路径验证知识、代码、混合、冲突、无快照和模型失败。
5. 实现前端真实页面与状态测试，最后使用本地 PostgreSQL、后端 Fake Model 和浏览器完成一次创建→流式→引用→刷新恢复→反馈闭环。
6. 部署时先执行 V7，再发布后端，最后发布前端；观察创建失败、SSE 活跃数/轮询延迟、模型错误、投影告警和反馈数量。只有全部门禁通过后才把开发计划 T7 和 OpenSpec tasks 标记完成。

回滚时先回退前端和后端；旧应用忽略 V7 新表及 `answer_basis` 可空列，T1～T6A 能力继续工作。已产生的问答与反馈数据保留，不删除迁移或手工回退表；修复版本恢复后继续读取。若 Agent 调度重构出现问题，可关闭 Agent 功能，普通文档浏览和搜索仍保持可用。
