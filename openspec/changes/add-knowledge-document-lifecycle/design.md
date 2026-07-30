## Context

参见 `proposal.md` 的动机与范围，以及 `specs/knowledge-document-lifecycle/spec.md`、`specs/knowledge-document-import/spec.md` 的可观察行为。当前后端已具备 T1 的对象存储、持久后台任务、审计/时间和统一错误能力，以及 T2 的 Web 角色、项目/分支主数据与普通/管理查询端口；尚无知识表、知识查询端口或正式检索数据集。前端已有会话、路由、设计令牌和项目页面骨架，但项目知识页仍是静态样例，`ProjectTabs` 只启用了设置页。

本变更跨越领域状态机、项目/分支范围、PostgreSQL、文件上传、ZIP 安全、后台索引切换和两个 Vue 页面，因此必须在实现前冻结数据与事务边界。继续遵守 Java 21、Spring Boot 4.1.0、MyBatis-Plus 显式映射、Flyway 唯一建表入口、真实 PostgreSQL 集成测试、Vue 3 + TypeScript、中文 Javadoc/关键原因注释和每个测试用例的中文业务目的注释。UI 结构以 Pencil `03 · 项目详情 / 知识管理`、`05 · 新建 / 编辑知识` 及现有组件实例为基线，只验收电脑浏览器。

## Goals / Non-Goals

**Goals:**

- 形成供 Web、T5 检索、未来 Agent 草稿和 MCP 复用的单一知识文档领域模型与只读/管理应用端口。
- 让范围、状态、替代和实时检索资格由领域与查询层共同强制，任何入口都不能靠前端隐藏实现隔离。
- 让 ZIP 输入在进入业务事务前经过资源、路径和条目类型校验，并保留逐文件处理证据。
- 复用 T1 对象存储和后台任务状态机，使用可原子切换、失败可回退的 PostgreSQL 知识索引快照为 T5 提供稳定输入。
- 在不引入 WYSIWYG、前端状态框架或 Markdown 执行链的情况下，把 Pencil 的知识目录和编辑工作流接入真实 API。

**Non-Goals:**

- 不在 T3 实现关键词分词、向量、混合排序、搜索 API、引用片段或 MCP 工具；T5 读取本变更的活动索引数据集再实现检索。
- 不建立文档版本历史、多人协作、评论、回收站或归档恢复；`revision` 只用于同步状态与并发条件，不保留每次编辑正文。
- 不解析 Markdown front matter、渲染上传 HTML、下载 Wiki URL 或调用模型；编辑器保存纯文本，预览若需要只展示转义文本。
- 不把 ZIP 条目解压到业务文件路径，不支持密码 ZIP、分卷 ZIP、网页、Word、PDF 或附件。
- 不实现多实例任务互斥、增量索引或移动端/窄屏专项布局；MVP 仍是单实例部署和全量知识重建。

## Decisions

### 1. 按 knowledge 能力分层，并用适配端口复用项目与平台能力

新增边界：

```text
knowledge/
  domain/                         文档、范围、来源、状态、替代与导入结果规则
  application/                    command/query 用例、仓储/范围/索引/导入端口与 DTO
  infrastructure/persistence/     MyBatis-Plus 实体、Mapper、仓储与索引快照适配器
  infrastructure/importing/       文件识别、UTF-8、ZIP 安全与对象存储协调
  infrastructure/indexing/        KNOWLEDGE_REINDEX JobHandler 与活动快照读取适配器
  infrastructure/web/             普通/管理员 Controller、multipart 与响应映射
```

`domain` 不依赖 Spring、项目能力、数据库或文件系统。`application` 定义 `KnowledgeScopeResolver`，基础设施适配器组合 T2 的 `ProjectQueryUseCase` 与 `AdminProjectQueryUseCase`：普通浏览只解析已启用项目和明确分支，管理写入可解析停用项目但仍验证分支归属。这样知识用例不直接依赖项目持久化实体，也不会复制默认 `main`、停用可见性或未知分支失败规则。

对象存储、后台任务、审计和时间直接依赖其既有应用端口；这些是已有稳定跨能力边界，不再包装成无业务价值的二次接口。公共应用端口、实现类和跨能力适配器使用中文 Javadoc，关键注释说明“范围必须在查询前解析”“机器后台任务不得冒充管理员”和“上传内容不可信”的原因。

### 2. 文档聚合集中保护范围、来源、状态和修订规则

核心类型为 `KnowledgeDocument`、`KnowledgeScope`、`DocumentFormat`、`DocumentSource`、`DocumentStatus`、`DocumentDirectory`、`DocumentTag` 与 `ReplacementLink`。范围内部只保存 UUID：

| 范围 | `project_id` | `branch_id` |
|---|---:|---:|
| `GLOBAL` | NULL | NULL |
| `PROJECT` | NOT NULL | NULL |
| `BRANCH` | NOT NULL | NOT NULL |

Web 请求可以使用项目 identifier/分支 name，必须先由 `KnowledgeScopeResolver` 解析为 UUID 后才能构造领域值。目录是逻辑路径而不是文件路径：根目录用空字符串表示，分隔符统一为 `/`，拒绝空段、`.`、`..`、反斜杠、控制字符和首尾 `/`。标签进行 Unicode NFC 与首尾空白清理，保留输入显示值；规范化后大小写相同的重复标签作为无效请求拒绝，不静默丢弃任一输入。不创建独立“标签管理”功能。

来源类型在 T3 只定义 `MANUAL`、`WIKI`、`UPLOAD`：`WIKI` 要求合法 `http`/`https` URL，`UPLOAD` 要求原文件名，`MANUAL` 可以记录人工整理说明。正文始终以 `TEXT` 保存；格式只决定后续展示与索引解释，不在后端执行 Markdown 或 HTML。

状态转换由聚合方法表达：创建/导入 → `DRAFT`，`DRAFT` → `PUBLISHED`，`DRAFT|PUBLISHED` → `ARCHIVED`；归档为终态。编辑、首次发布、归档和替代发布增加 `revision`；字段完全未变化或重复请求已处于目标状态时返回当前聚合，不增加修订或审计时间。编辑已发布文档保持 `PUBLISHED`，但活动索引中的旧修订继续服务并显示 `STALE`，直到管理员重建；这保留 FR-DOC-07 的显式重新索引边界，避免每次保存启动后台任务。

选择单聚合而不是文档版本表，是因为 MVP 没有历史版本或协同编辑需求。`revision` 与条件更新只防止生命周期操作和索引状态相互覆盖，未来若要求版本回溯应另建 change，不能把修订号误当正文历史。

### 3. 先冻结普通、管理、导入与索引 HTTP 契约

所有响应时间使用 UTC ISO 8601，UUID 作为内部实体标识，项目 identifier/分支 name 作为普通浏览上下文。列表使用 `page`（从 0 开始）、`size`（默认 20，上限 100）与稳定的 `updatedAt DESC, id ASC` 排序；响应为 `PageResponse { items, page, size, totalElements, totalPages }`。接口如下：

| 方法与路径 | 主要输入 | 成功响应 | 权限与幂等性 |
|---|---|---|---|
| `GET /api/knowledge-documents` | `context=GLOBAL|PROJECT`；项目上下文需 `project`，可选 `branch`、`directory`、分页 | 已发布摘要页与目录节点 | `ADMIN`/`MEMBER`；前置范围过滤；幂等 |
| `GET /api/knowledge-documents/{id}` | 与列表相同的明确上下文 | 已发布文档详情 | `ADMIN`/`MEMBER`；上下文不匹配统一 404；幂等 |
| `GET /api/admin/knowledge-documents` | 可选 `scopeType/projectId/branchId/directory/status/tag`、分页 | 管理摘要页 | `ADMIN`；含草稿/归档；幂等 |
| `GET /api/admin/knowledge-documents/{id}` | 无 | 管理文档详情与替代/同步信息 | `ADMIN`；幂等 |
| `POST /api/admin/knowledge-documents` | 完整可编辑字段 | 201 管理详情 | `ADMIN`；非幂等，只创建草稿 |
| `PUT /api/admin/knowledge-documents/{id}` | 完整可编辑字段 | 管理详情 | `ADMIN`；同值重复请求幂等，归档冲突 |
| `POST /api/admin/knowledge-documents/{id}/publish` | 可选 `replacesDocumentId` | 管理详情 | `ADMIN`；目标状态幂等，替代发布原子执行 |
| `POST /api/admin/knowledge-documents/{id}/archive` | 无 | 管理详情 | `ADMIN`；目标状态幂等 |
| `POST /api/admin/knowledge-document-imports` | multipart `file` + JSON `options` | 201 `ImportBatchView` | `ADMIN`；非幂等，同步返回结果 |
| `GET /api/admin/knowledge-document-imports/{batchId}` | 无 | `ImportBatchView` | `ADMIN`；幂等 |
| `POST /api/admin/knowledge-index-jobs` | 无 | 202 `KnowledgeIndexJobView` | `ADMIN`；有活动任务时返回同一任务 |
| `GET /api/admin/knowledge-index-jobs/{jobId}` | 无 | `KnowledgeIndexJobView` | `ADMIN`；只接受知识任务 ID；幂等 |

`KnowledgeDocumentView` 包含 ID、格式、标题、正文、目录、标签、来源、范围、状态、revision、发布/归档审计、替代关系、`NEVER_INDEXED|PENDING|STALE|SYNCED` 同步状态和时间；普通摘要不返回草稿/归档审计或内部对象键。导入 `options` 复用创建命令的范围、目录前缀、标签与来源默认值，ZIP 标题和子目录从安全条目名派生。

新增错误码：`DOCUMENT_NOT_FOUND`(404)、`DOCUMENT_SCOPE_INVALID`(400)、`DOCUMENT_STATE_CONFLICT`(409)、`DOCUMENT_REPLACEMENT_CONFLICT`(409)、`DOCUMENT_IMPORT_TYPE_UNSUPPORTED`(415)、`DOCUMENT_IMPORT_TOO_LARGE`(413)、`DOCUMENT_IMPORT_ARCHIVE_INVALID`(422)、`DOCUMENT_IMPORT_BATCH_NOT_FOUND`(404)、`DOCUMENT_INDEX_JOB_NOT_FOUND`(404)。Bean Validation 字段错误继续使用 `INVALID_REQUEST`。只按已知业务异常和命名约束转换错误；数据库、解析器和文件系统异常保留内部上下文并向客户端输出脱敏摘要。

选择同步导入是因为安全默认限制使单次输入有明确上界，且需求要求提交后立即查看逐文件结果；后台执行只用于可能扫描全部正式文档的重建。若实测 20 MiB 上限仍无法在反向代理超时内稳定处理，再通过新规格引入异步导入，不在 T3 同时维护两套语义。

### 4. 使用追加 V3 数据模型和数据库约束双重保护

追加 `V3__create_knowledge_document_tables.sql`，不修改 V1/V2：

```text
knowledge_document
  id UUID PK
  format MARKDOWN|PLAIN_TEXT
  title VARCHAR(200), body TEXT, directory_path VARCHAR(1000)
  scope_type GLOBAL|PROJECT|BRANCH
  project_id UUID NULL FK project_space
  branch_id UUID NULL FK project_branch
  source_type MANUAL|WIKI|UPLOAD
  wiki_url VARCHAR(2000) NULL
  original_filename VARCHAR(512) NULL
  curation_note VARCHAR(2000) NULL
  status DRAFT|PUBLISHED|ARCHIVED
  revision BIGINT > 0
  replaces_document_id UUID NULL UNIQUE FK knowledge_document
  published_at/by, archived_at/by NULL
  created_at/by, updated_at/by

knowledge_document_tag
  document_id UUID FK knowledge_document ON DELETE CASCADE
  normalized_name VARCHAR(100), display_name VARCHAR(100)
  PK(document_id, normalized_name)

knowledge_import_batch
  id UUID PK, object_key FK stored_object
  original_filename, scope_type, project_id, branch_id, directory_prefix
  status COMPLETED|PARTIAL|FAILED
  succeeded_count, failed_count, ignored_count
  created_at/by, updated_at/by

knowledge_import_item
  id UUID PK, batch_id UUID FK
  ordinal INT, entry_name VARCHAR(1000)
  status SUCCEEDED|FAILED|IGNORED
  reason_code VARCHAR(64), message VARCHAR(500), document_id UUID NULL FK
  UNIQUE(batch_id, ordinal)

knowledge_index_generation
  id UUID PK, job_id UUID UNIQUE FK background_job
  status BUILDING|ACTIVE|RETIRED
  document_count, created_at, activated_at

knowledge_index_document
  generation_id UUID FK knowledge_index_generation ON DELETE CASCADE
  document_id UUID, source_revision BIGINT
  format, title, body, directory_path, tags JSONB
  scope_type, project_id, branch_id, source fields, source_updated_at
  PK(generation_id, document_id)
```

数据库 `CHECK` 约束保护三类范围空值组合、状态时间组合、正数 revision/计数和替代非自指；命名唯一约束保护一个旧文档最多被一个新文档替代；`knowledge_index_generation` 使用部分唯一索引保证最多一个 `ACTIVE`。分支属于项目、来源字段组合、循环替代和状态转换仍由领域/应用事务校验，因为普通 `CHECK` 无法清楚表达这些跨行规则。

普通项目浏览 SQL 直接表达 `(GLOBAL) OR (PROJECT and project_id=?) OR (BRANCH and project_id=? and branch_id=?)`，并同时限定 `PUBLISHED`；全局 SQL 只允许 `GLOBAL/PUBLISHED`。目录、状态、标签与分页都在数据库条件中完成，不先跨范围加载。实体、领域对象和 HTTP DTO 分离；每个实体使用 `@TableName`、`@TableId`、逐字段 `@TableField`，查询优先 MyBatis-Plus Java API，只有复杂范围联合和活动 generation 查询无法清楚表达时使用注解 SQL，禁止 XML Mapper。

### 5. 替代发布使用单事务、条件更新和唯一约束

发布无替代的事务锁定当前文档并校验状态；替代发布按 UUID 稳定顺序锁定新旧两行，避免并发相反锁序。应用层检查同范围、旧文档为 `PUBLISHED`、替代链无循环，然后依次写入新文档发布状态、`replaces_document_id` 和旧文档归档状态，最后提交。数据库唯一约束处理两个新文档并发竞争同一旧文档；适配器只把该命名约束映射为 `DOCUMENT_REPLACEMENT_CONFLICT`，其他数据库异常不伪装成业务冲突。

普通读取和正式检索资格始终以实时 `knowledge_document.status` 为准，因此归档事务提交后立即不可见，不需要等待索引删除。这里需要中文原因注释说明“双层过滤不是展示层补丁，而是防止旧索引候选越权返回”的业务规则。

### 6. ZIP 使用 Commons Compress 做中央目录检查，但所有资源限制由业务代码显式执行

JDK `ZipInputStream` 无法可靠提供中央目录中的 Unix 条目类型；为拒绝符号链接并检查重复规范化路径，引入最小依赖 `org.apache.commons:commons-compress:1.28.0`。该版本由 Apache 于 2025-07-26 发布、要求 Java 8+、采用 Apache-2.0，官方安全页列出的 ZIP 资源消耗历史问题已在早期版本修复；实现前仍运行 Maven 依赖树、许可证和安全扫描，不能把“使用库”当作压缩炸弹防护。

默认配置为：外层上传 20 MiB、最多 200 个中央目录条目、单条目展开 2 MiB、累计展开 50 MiB、最大压缩比 100:1；这些值通过强类型配置暴露并在启动时校验正数及相互关系。HTTP 层先用计数字节流限制上传，再写入 T1 `ObjectStorage`。ZIP 检查把已存对象复制到服务生成的单个安全临时文件，以 Commons Compress `ZipFile` 读取中央目录；从不使用条目名创建文件或目录。`ZipArchiveEntry.isUnixSymlink()`、目录/普通文件类型、加密/分卷标记、规范化名称唯一性和资源计数全部在创建任何文档前完成，临时文件在 `finally` 删除。

处理分两阶段：

1. **批次安全阶段**：验证外层签名、中央目录、条目类型、路径与资源上界。结构损坏、加密、分卷、重复规范化路径或资源超限使整个批次 422，删除尚未被业务记录引用的对象且不创建文档。
2. **条目业务阶段**：在已安全的 ZIP 中按中央目录顺序读取；目录/非 Markdown 为 `IGNORED`，不安全路径/符号链接为 `FAILED`，Markdown 再做严格 UTF-8、标题、正文、目录与范围校验。每个合法条目通过 `REQUIRES_NEW` 风格的独立事务创建草稿并关联结果，一个条目失败不回滚其他已提交项。

由于规格要求路径穿越和符号链接产生明细而非批次拒绝，安全阶段会记录这些条目但绝不读取其数据；它们仍计入条目数，防止攻击者用大量无效项绕过限制。批次级异常发生在文档事务前。单文件沿用同一结果模型，支持类型但编码/字段失败时返回一个 `FAILED` 项的 201 批次；外层类型、大小和结构失败继续使用 415/413/422。

对象存储与数据库无法组成 XA 事务：对象成功但批次记录失败时执行幂等补偿删除并保留脱敏日志；补偿也失败时对象保持不可被业务查询引用，由运维故障排查识别。MVP 不新增通用垃圾回收器，因为它超出当前能力边界。

### 7. 正式检索输入使用 PostgreSQL generation 快照和单实例 single-flight 任务

T3 不提前引入 Lucene、Embedding 或 pgvector 写入。`knowledge_index_document` 是对已发布知识的不可变活动投影，保存 T5 构建关键词/向量索引所需的原文、标签、来源和范围快照。`PublishedKnowledgeIndexReader` 只暴露当前 `ACTIVE` generation 的批量读取，并要求调用方携带明确项目/分支过滤；T5 仍必须把候选文档 ID 回到实时资格查询端口做 `PUBLISHED`、项目、分支复核。

新增 `KNOWLEDGE_REINDEX` 处理器，复用 `BackgroundJobService`。为满足重复提交返回同一活动任务，在既有服务增加 `submitSingleFlight(JobRequest)` 与按类型查询 `PENDING|RUNNING` 的仓储能力：MVP 单实例内在同一 JVM 锁内执行“查活动任务—插入 PENDING—提交执行器”，数据库仍先持久化任务再执行。该方法只用于声明 single-flight 的任务类型；默认 `submit` 继续非幂等，不改变其他任务行为。多实例下该互斥不成立，属于已明确非目标；未来需要多实例时再加入数据库并发键或分布式锁。

任务在一个 PostgreSQL `REPEATABLE READ` 事务中创建 `BUILDING` generation、分批读取快照开始时所有 `PUBLISHED` 文档、写入投影并校验数量/唯一性，然后把旧 `ACTIVE` 改为 `RETIRED`、新 generation 改为 `ACTIVE`。没有外部模型或向量调用，事务只做本地数据库读写，MVP 数据量下换取一致快照比设计增量恢复更简单。异常回滚整个 generation 和活动切换，后台任务边界保存 `FAILED`；进程中断由 T1 恢复逻辑标记失败，旧 `ACTIVE` 不受影响。成功后保留当前与上一个 generation，删除更老的 `RETIRED` 数据属于成功后的尽力清理，失败不影响活动查询。

同步状态按当前文档 revision 与活动投影 `source_revision` 计算：无投影为 `NEVER_INDEXED`（草稿显示为不适用）或发布后的 `PENDING`，相等为 `SYNCED`，活动投影较旧为 `STALE`。不在 `knowledge_document` 写派生的 `indexed_revision`，避免活动切换与每行回写之间出现双重事实来源。

### 8. 前端在现有项目骨架上启用知识页和纯文本编辑工作流

路由增加：

```text
/knowledge                                      通用业务知识目录
/knowledge/:documentId                          通用文档详情
/projects/:identifier/knowledge                 项目知识目录，branch 查询参数默认 main
/projects/:identifier/knowledge/:documentId     项目上下文文档详情
/admin/knowledge/new                            新建/导入，scope 查询参数预填
/admin/knowledge/:documentId/edit               编辑与生命周期操作
```

`/projects/:identifier` 从 T2 的设置占位切换为项目知识默认入口；管理员设置仍保留 `/projects/:projectId/settings`。`ProjectTabs` 根据角色和路由启用知识、设置对应导航，不再用静态禁用按钮模拟业务页。全局侧栏的“通用业务知识”和项目卡片都进入真实知识路由。

组件只按实际复用边界新增 `DocumentDirectoryTree`、`DocumentList`、`DocumentStatusBadge`、`KnowledgeEditor`、`ScopeFields`、`TagInput`、`ImportResultPanel` 和 `ConfirmDialog`；API/类型放在 `api/knowledgeDocuments.ts` 与 `api/types.ts`，不引入 Pinia、富文本编辑器或前端 Markdown 解析依赖。编辑页采用 Pencil 的正文主区域 + 右侧元数据栏，使用原生 `textarea` 保存 Markdown/纯文本；任何正文、文件名、错误消息和来源说明都用 Vue 文本插值，不使用 `v-html`。

页面在 API 返回前显示骨架或加载状态，空目录提供与角色匹配的下一步，失败保留项目/分支/目录上下文和重试。发布替代文档与归档使用明确确认对话框；上传/保存/发布/归档/重建期间禁用重复提交。任务状态用有上限的前台轮询，仅在用户停留页面时查询，终态或离开路由即停止；浏览失败不影响本地编辑内容。

### 9. 测试围绕范围泄漏、状态原子性、恶意 ZIP 和索引回退

- 领域单元测试：目录/标签/来源边界、三类范围组合、状态机幂等、归档终态、同范围替代、循环替代和修订变化。
- PostgreSQL 集成测试：V3 从 V1/V2 升级及重复迁移、显式实体映射、范围/状态/唯一/FK 约束、普通查询前置隔离、替代发布事务和并发竞争、每项导入事务、活动 generation 唯一与切换回滚。
- Web 契约测试：普通/管理端点字段、分页排序、400/401/403/404/409/413/415/422、上下文不匹配统一 404、重复生命周期请求、single-flight 任务和错误脱敏。
- ZIP 安全测试：使用小型程序化 fixture 覆盖绝对/盘符/`..`/NUL、Unix 符号链接、重复规范化路径、损坏/加密/分卷、条目/单项/累计/比例上限、非法 UTF-8、部分成功、非 Markdown 忽略及临时文件/对象补偿；测试数据只含模拟内容。
- 后台任务测试：草稿不入投影、发布后待同步、编辑后 stale、成功原子切换、失败/进程恢复保留旧 generation、归档实时资格过滤及同类型重复提交复用任务。
- 前端测试：全局/项目目录加载空错、分支切换不串数据、成员无写控件、编辑字段与保存状态、导入三类结果、确认对话框、任务轮询终止、不可信文本不作为 HTML。
- 桌面验证：重新通过 Pencil MCP 读取两张 frame，在设计稿宽度逐页截图对照字体、颜色、间距、目录层级、表单栏、状态和控制台错误；不为覆盖率重复验证相同行为。

所有业务测试先确认因目标行为缺失而失败，再写最小实现。每个测试方法用中文注释说明保护的业务事实和防止的回归；公共端口、实现策略、事务、范围过滤、ZIP 防护和索引双层资格校验补充中文 Javadoc/原因注释。

## Risks / Trade-offs

- [同步 ZIP 导入占用请求线程，较大批次可能接近代理超时] → 用 20 MiB/200 条目等硬上限和流式计数限制成本，先以真实演示批次测量；超过边界明确拒绝，不在 T3 混入异步语义。
- [Commons Compress 仍不能替代资源配额，恶意 ZIP 可能消耗 CPU/磁盘] → 只启用 ZIP、先读中央目录、使用安全单临时文件、限制条目/展开量/比例并在文档事务前完成全局校验；实现前复核官方安全页和依赖树。
- [对象存储与数据库补偿删除不具备强事务] → 业务查询只认 import batch 引用，失败立即幂等删除并记录 trace ID；不让未引用对象成为文档来源。
- [已发布文档编辑后，旧活动投影在重建前仍含旧正文] → 明确显示 `STALE`，由管理员手动重建；实时状态/范围资格仍阻止归档或越界文档返回。
- [全量 generation 的 REPEATABLE READ 事务随文档量增长] → T3 只有本地 PostgreSQL 投影且 MVP 数据量有限；按主键分批但保持单快照，T5 性能基准若证明不足再设计增量 generation。
- [单实例 JVM 锁无法阻止多实例并行重建] → 与当前 MVP 单实例会话/任务边界一致；部署文档明确单实例，扩展部署前必须新增数据库并发键规格。
- [替代发布归档旧文档而新文档尚未重建，正式检索会短暂没有对应内容] → 这是“归档立即退出、发布后手动重建”的安全优先结果；UI 发布成功后突出 `PENDING` 与重建入口，不继续返回已作废旧知识。
- [T2 项目页目前承载设置占位，路由切换可能回归现有导航] → 保留管理员 settings 路由和 T2 接口，新增路由测试覆盖管理员/成员入口与浏览器返回行为。

## Migration Plan

1. 实现前重新核验 Commons Compress 官方发布、安全页、Apache-2.0、Java 21 和 Spring Boot 依赖树，锁定 1.28.0；配置 multipart 与知识导入上限时保持反向代理限制不低于应用限制。
2. 先定义知识领域模型、应用端口、HTTP 请求/响应和错误码，再追加 V3 迁移；在真实 PostgreSQL 验证从现有 V2 升级、空库迁移和重复迁移，绝不修改 V1/V2。
3. 先部署后端表、普通/管理 API、导入和索引任务，再部署启用真实知识路由的前端；所有新增接口为追加式，T1/T2 存活、认证、项目与设置接口保持兼容。
4. 使用模拟 Markdown、纯文本和恶意 ZIP fixture 完成安全验收，再导入脱敏演示文档；首次发布后由管理员显式运行全量知识重建并确认活动 generation 与文档同步状态。
5. 回滚应用版本时保留 V3 表、导入对象和 generation，不执行破坏性降级；旧 T2 应用不会读取这些表。若新前端先于后端回滚，必须同步回滚前端以避免调用不存在的知识接口。
6. 上线后若一次重建失败，继续使用旧 `ACTIVE` generation，管理员修复原因后重新提交；不得手工把 `BUILDING` generation 改为活动状态。
