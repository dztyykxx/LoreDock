## Context

见 `proposal.md` 的动机以及两份增量规格的行为边界。当前后端已经具备 Java 21、Spring Boot 4.1、MyBatis-Plus、Flyway、Sa-Token、Commons Compress、本地 `ObjectStorage`、PostgreSQL `background_job` 状态机，以及项目/分支的普通与管理查询；`background_job` 表也已预留 `project_id`、`branch_id` 和 `snapshot_id`。Lucene 10.5.0 版本已在 T1 锁定，但尚未引入具体模块或建立代码索引目录。

代码快照同时跨越数据库、对象存储、临时文件和 Lucene 目录，无法形成单一 XA 事务。设计必须让 PostgreSQL 中的活动指针成为查询入口事实，并保证数据库只指向已经完整发布且可打开的索引目录。MVP 是单应用实例，但范围隔离、同分支排他构建和活动切换仍应由数据库约束保护，不能只依赖页面禁用或线程执行顺序。

## Goals / Non-Goals

**Goals:**

- 以稳定应用端口统一服务 Web、后续 MCP 和 Agent，范围解析、活动快照选择和片段上限只实现一次。
- 流式处理受限 ZIP，不按不可信条目名落盘；对 20 万行级模拟代码保持可接受的索引和查询成本。
- 让候选 generation 在文件系统验证完成后才进入一次短数据库事务切换，任何失败都保留旧活动入口。
- 将原始 ZIP、快照业务状态、后台任务状态和可重建 Lucene 索引分别建模，便于恢复与诊断。

**Non-Goals:**

- 不克隆或挂载 Git 仓库，不调用 Git 平台验证 commit，也不计算完整 diff。
- 不建立历史快照查询、增量索引、代码向量、AST、符号关系、调用图或跨仓库依赖图。
- 不做通用内容秘密扫描；文件名与路径默认排除是安全底线，不是秘密审计结论。
- 不在 T4 实现代码快照管理页面、MCP 工具或问答编排；这些能力只消费本次应用端口。
- 不为 Lucene 索引设计跨机器共享目录或多实例 reader 协调；扩展为多实例部署前需另建规格。

## Decisions

### 1. 新增独立 `code` 能力并先冻结应用与 HTTP 契约

后端使用 `io.github.loredock.code` 能力包，内部按 `domain`、`application`、`infrastructure.persistence`、`infrastructure.archive`、`infrastructure.index` 和 `infrastructure.web` 分层。领域层只表达快照生命周期、generation 状态、仓库相对路径和文件选择结果；应用层定义上传、重建、状态查询、搜索和片段读取端口；Commons Compress、Lucene、MyBatis-Plus、Spring MVC 与物理路径仅出现在基础设施层。后续 MCP 和 Agent 直接调用 `CodeSearchUseCase` 与 `CodeSnippetReadUseCase`，不得绕过它们访问 Lucene。

首批 HTTP 契约如下，时间均为 UTC ISO 8601，普通项目路径继续使用项目 identifier，管理写操作使用 UUID：

| 方法与路径 | 主要输入 | 成功响应 | 权限与语义 |
|---|---|---|---|
| `POST /api/admin/code-snapshots` | multipart `file/projectId/branchId/commit` | 202 `CodeSnapshotJobView` | `ADMIN`；非幂等；同分支活动任务冲突 |
| `GET /api/admin/code-snapshots` | `projectId/branchId`、分页 | `CodeSnapshotAdminView` 页 | `ADMIN`；含候选、失败、活动与已替换记录 |
| `GET /api/admin/code-snapshot-jobs/{jobId}` | 代码任务 ID | `CodeSnapshotJobView` | `ADMIN`；其他任务 ID 按不存在处理 |
| `POST /api/admin/code-snapshots/{snapshotId}/reindex` | 无 | 202 `CodeSnapshotJobView` | `ADMIN`；只重建当前活动快照；非幂等 |
| `GET /api/projects/{identifier}/code-snapshot` | 可选 `branch` | `ActiveCodeSnapshotView` | `ADMIN`/`MEMBER`；无快照返回 `NOT_INDEXED` |
| `GET /api/projects/{identifier}/code-search` | `query/target/pathPrefix/branch/limit` | `CodeSearchResponse` | `ADMIN`/`MEMBER`；只查活动 generation |
| `GET /api/projects/{identifier}/code-snippets` | `path/startLine/lineCount/branch` | `CodeSnippetResponse` | `ADMIN`/`MEMBER`；只读已索引文件 |

管理列表沿用 `page` 从 0 开始、`size` 默认 20/最大 100，以及 `createdAt DESC, id ASC` 稳定排序。`CodeSnapshotJobView` 聚合快照 ID、任务 ID、目标范围、声明 commit、任务状态/进度、文件统计、创建/完成时间和脱敏错误；不得返回 object key、工作目录或 generation 目录。新增稳定错误码：`PROJECT_DISABLED`(409)、`CODE_SNAPSHOT_TYPE_UNSUPPORTED`(415)、`CODE_SNAPSHOT_TOO_LARGE`(413)、`CODE_SNAPSHOT_ARCHIVE_INVALID`(422，用于同步校验或任务失败码)、`CODE_SNAPSHOT_JOB_ACTIVE`(409)、`CODE_SNAPSHOT_NOT_FOUND`(404)、`CODE_SNAPSHOT_NOT_ACTIVE`(409)、`CODE_SNAPSHOT_JOB_NOT_FOUND`(404)、`CODE_FILE_NOT_FOUND`(404)、`CODE_SNIPPET_RANGE_INVALID`(416)、`CODE_INDEX_UNAVAILABLE`(503)。字段问题继续使用 `INVALID_REQUEST`。

选择独立普通状态端点而不是把快照字段塞入现有项目详情，是因为快照读取需要索引状态与失败隔离，且 T2 项目接口不应随索引实现变化。完整页面由 T12 消费这些契约。

### 2. 使用追加 V4 迁移保存快照与 generation，并复用后台任务范围列

新增 `V4__create_code_snapshot_tables.sql`，不修改已经执行的 V1～V3：

```text
code_snapshot
  id UUID PK
  project_id UUID FK project_space
  branch_id UUID FK project_branch
  commit_hash VARCHAR(64)
  input_object_key VARCHAR(64) UNIQUE FK stored_object
  status CANDIDATE|ACTIVE|RETIRED|FAILED
  previous_snapshot_id UUID NULL FK code_snapshot
  indexed_file_count, ignored_file_count BIGINT >= 0
  indexed_at TIMESTAMPTZ NULL
  created_at/by, updated_at/by

code_index_generation
  id UUID PK
  snapshot_id UUID FK code_snapshot
  job_id UUID UNIQUE FK background_job
  status BUILDING|ACTIVE|RETIRED|FAILED
  document_count BIGINT >= 0
  created_at, activated_at NULL
```

数据库检查约束保护 commit 格式、状态/索引时间组合、非负计数和 previous 非自指；外键保护项目、分支、对象、快照与任务存在。部分唯一索引保证每个 `branch_id` 最多一个 `ACTIVE` 快照、每个 `snapshot_id` 最多一个 `ACTIVE` generation。分支确实属于项目仍由项目应用端口与集成测试验证，因为普通外键无法表达两表归属组合；激活事务再次检查该归属和候选状态。

现有 `background_job.project_id/branch_id/snapshot_id` 纳入实体、领域快照与 `JobRequest`，并补外键。为同时阻止上传构建和重建并发，增加只覆盖 `CODE_SNAPSHOT_BUILD|CODE_SNAPSHOT_REINDEX` 且状态为 `PENDING|RUNNING` 的 `branch_id` 部分唯一索引。任务提交服务新增“按分支排他提交”契约：命中该命名约束时映射为 `CODE_SNAPSHOT_JOB_ACTIVE`，其他数据库错误保留原语义。相比仅使用 JVM 锁，这能避免重启和意外多实例让两个任务竞速激活；相比新增通用锁表，它直接复用已经存在的任务范围列和状态。

`code_snapshot` 只保存业务生命周期，不复制后台任务错误。上传失败把候选置为 `FAILED`；活动快照重建失败则保持 `ACTIVE`，最近任务状态从 `background_job` 查询。变化提示通过 `previous_snapshot_id` 比较两次成功快照的 commit 得到：首次为 `INITIAL`，不同为 `CHANGED`，重建同一快照为 `UNCHANGED`，不生成 diff。

所有持久化实体与领域/API DTO 分离，字段逐一使用 MyBatis-Plus 显式注解。简单查询优先 Lambda Wrapper；活动范围联合、部分约束冲突识别等 Java API 无法清楚表达的 SQL 使用注解 Mapper，禁止 XML。

### 3. 对象先安全持久化，快照与任务同事务登记，任务只在提交后调度

上传入口先用服务器 multipart 上限和计数字节流把外层请求限制为 100 MiB，只接受 ZIP 扩展名、`application/zip`/`application/octet-stream` 及 ZIP 魔数的一致组合，然后调用现有 `ObjectStorage.put` 流式保存并计算 SHA-256。对象成功后开启 PostgreSQL 事务：校验已启用项目与所属分支，插入 `CANDIDATE` 快照，并通过带项目/分支/快照范围的后台端口插入 `PENDING` 任务。数据库事务失败时幂等补偿删除对象；补偿失败只留下没有快照引用、无法通过业务入口读取的对象并记录 trace ID。

现有 `PersistentBackgroundJobService` 需要在调用方事务存在时把执行器提交注册为 `afterCommit`：数据库回滚则不调度，提交后才让处理器读取快照；无外层事务的既有调用仍在自身插入提交后调度。执行器拒绝继续把已提交任务终结为 `FAILED/CAPACITY_EXCEEDED`。这是必要的一致性修正，不改变已有任务状态机或自动重试策略。

不在 HTTP 请求线程中打开中央目录，是为了让大型但合法快照的结构检查、索引和验证都具有进度与失败记录。外层类型/体积错误同步返回 415/413；损坏、加密或恶意 ZIP 通常在 202 后使任务终结为 `FAILED`。上传响应只说明任务已受理，不暗示快照已经可查询。

### 4. Commons Compress 只读取服务生成的临时 ZIP，不展开条目树

处理器从 `ObjectStorage` 获取流并复制到 `/data/work/code/{jobId}/input.zip`；工作目录和文件名只由 UUID 生成。使用现有 Commons Compress `ZipFile` 读取中央目录，先完成全包校验，再开始 Lucene 写入。默认限制为 50,000 个条目、单个可索引文本 2 MiB、累计声明展开量 1 GiB、压缩比 100:1；所有限制通过 `CodeSnapshotProperties` 暴露，启动时校验为正且不超过外层上传边界允许的安全关系。测试可调低限制构造小型攻击夹具。

路径使用 `/` 统一分隔，拒绝 NUL、反斜杠、绝对/盘符路径、空段、`.`/`..` 逃逸和规范化重复。目录条目允许但不读取；符号链接、特殊 Unix 类型、加密、分卷、未知或不一致的展开大小使整包失败。安全校验遍历所有条目，包括之后会被忽略的目录，防止攻击文件藏在默认排除区。校验完成后按中央目录顺序逐项打开允许文件的流，最多只在内存保存单文件上限的字节，不按条目路径创建磁盘文件。

文件选择器采用可审查的路径段、文件名和扩展名规则，至少覆盖规格列出的目录和敏感文件；随后用 NUL 探测与严格 UTF-8 解码排除二进制/非法文本。超大、二进制、非法编码和规则排除均产生计数型原因，不把原路径或正文写入普通日志。这里应添加中文原因注释，说明“所有条目先做结构校验”和“被忽略文件也不能从原 ZIP 读取”分别防止什么绕过。

备选方案是安全解压到工作目录后让 Lucene 扫描文件树，但它扩大了路径穿越、链接、磁盘配额和异常清理面；当前只需文件级索引，直接流入 writer 更简单。JDK `ZipInputStream` 又缺少足够的中央目录/Unix 类型信息，因此继续使用已经引入且维护中的 Commons Compress。

### 5. 每个任务建立独立 Lucene 目录并在发布后短事务激活

Maven 按已锁定的 10.5.0 版本增加最小模块 `lucene-core`、`lucene-analysis-common` 和 `lucene-highlighter`，不引入 Elasticsearch、OpenSearch 或 QueryParser。索引根目录固定为 `/data/indexes/code`（与对象目录位于同一持久化卷但不同子目录），安全解析器只接受数据库生成的 generation UUID。构建写入 `<generationId>.building`，关闭 writer 后重新以 `DirectoryReader` 打开，校验文档数、唯一规范化路径及所有文档的 project/branch/snapshot/commit/generation 字段，再在同一文件系统原子重命名为 `<generationId>`。

Lucene 文档包含：

```text
project_id, branch_id, snapshot_id, generation_id, commit   StringField
path_exact                                                  StringField
path, file_name                                             TextField
language                                                    StringField
content                                                     TextField + StoredField
line_count                                                  StoredField
```

路径、文件名与内容使用面向代码的 analyzer：保留原 token，同时按分隔符、大小写和数字边界拆分并小写化；精确身份字段使用 keyword 语义。查询由服务端根据已分析词项程序化构造 BooleanQuery，客户端字符不进入原始 Lucene 查询语法。`ALL` 对文件名、路径、内容使用递减 boost；`PATH` 和 `CONTENT` 只查询指定字段。命中片段使用 Lucene highlighter 的无标记 formatter 从 StoredField 产生纯文本，所有响应仍由 JSON 序列化，不生成可执行 HTML。

最终目录发布成功后，单个 PostgreSQL 事务锁定目标分支：上传任务把旧活动 snapshot/generation 置为 `RETIRED` 并把候选与新 generation 置为 `ACTIVE`；重建只替换同一 snapshot 的 generation。事务提交前再次验证目录存在且可打开。若数据库提交失败，旧活动记录不变，新目录只是不可查询的孤儿；若数据库提交成功后进程退出，目录已完整存在，查询仍可恢复。后台任务随后记为 `SUCCEEDED`；任务成功状态晚于激活短暂可见是可接受的，因为活动快照数据已完整，状态接口以两者分别表达。

文件系统与数据库无法真正原子提交，因此查询必须先从数据库解析活动 snapshot+generation 描述符，再按 generation UUID 打开索引，绝不能扫描目录猜测最新版本。`LuceneIndexHandleRegistry` 对每个 generation 提供引用计数 reader：一次请求固定一个 handle，切换后旧 handle 可服务已开始请求；只有 generation 已退休且引用数归零才允许清理。启动恢复把关联任务已终结或不存在的 `BUILDING` generation 置为 `FAILED`，删除 `.building` 与无数据库活动引用的孤儿目录，但绝不删除当前活动目录和原始对象。

### 6. 搜索与片段读取都先解析活动范围，再访问同一个 generation handle

普通应用服务先通过现有项目查询能力解析已启用项目与分支（省略分支使用 `main`），随后由 `ActiveCodeSnapshotResolver` 在一次数据库读取中取得 snapshot ID、generation ID、commit 和 indexedAt。Lucene 查询仍附加 project/branch/snapshot/generation 的强制 `FILTER` 子句，即使目录只对应一个 generation，也不依赖目录隔离作为唯一权限边界。代码中要用中文原因注释说明该双层约束用于防止目录放置错误或未来索引布局变化造成跨范围召回，不是展示层补丁。

搜索结果先按 score 降序、path 升序排序，最大 50 条；片段长度配置独立于 `lineCount`，避免单个搜索响应返回大量代码。`pathPrefix` 和片段 `path` 使用与导入相同的纯逻辑规范化器，但从不解析为 `java.nio.file.Path`。片段读取通过活动 Lucene 文档的 `path_exact` 精确查询取得 StoredField，以 Java 行边界计算实际范围；不存在、被忽略或属于其他范围统一映射为 `CODE_FILE_NOT_FOUND`。超过文件末尾的起始行返回 416，越过末尾的结束范围正常截短。

`CodeSearchUseCase` 和 `CodeSnippetReadUseCase` 的输入类型只包含业务范围、查询、路径和上限，不暴露 generation 参数。Web Controller、T11 MCP 适配器和 T6 Agent 工具只能依赖这些端口。索引物理读取失败记录 generation、snapshot 和 trace ID，但错误响应只返回 `CODE_INDEX_UNAVAILABLE`；不得静默回退到旧 commit、另一分支或候选目录。

### 7. 后台进度、失败和清理保持可观察但不保存不必要代码

`CODE_SNAPSHOT_BUILD` 与 `CODE_SNAPSHOT_REINDEX` 处理器按“复制/校验 ZIP、选择文件、写入索引、验证、激活”阶段单调更新 0～99 进度并定期心跳。可预期异常映射为稳定任务失败码；未知 Commons Compress、Lucene、I/O 或数据库异常保留 cause 与快照/任务上下文写错误日志，再由既有分类器输出脱敏摘要。日志只记录计数、UUID、commit 和规范化错误类别，不记录完整文件正文、密钥内容、对象路径或大量文件清单。

应用启动时先由既有 `JobRecovery` 将陈旧 `RUNNING` 任务标记 `PROCESS_INTERRUPTED`，随后代码恢复器协调候选 snapshot 和 BUILDING generation：没有终态成功任务支持的候选不得激活，活动 snapshot/generation 不改动。`PENDING` 任务是否未曾被调度仍沿用 T1 的“不自动重放”边界；管理员可重新上传，或对仍完整的活动快照重新索引。工作目录在处理器 `finally` 中幂等删除；退休目录在 reader 引用释放后清理，失败不影响活动查询并留下可重试日志。

### 8. 测试只围绕高风险业务事实组织

实现遵循接口优先与红—绿—重构，测试不按类或层机械铺开：

- 领域与纯 Java 测试保护 commit/逻辑路径规则、文件默认排除、状态转换、结果截断和行范围，不测试 getter、框架绑定或 Lucene 自身算法。
- 程序化 ZIP 安全测试覆盖路径逃逸、规范化重复、符号链接/特殊条目、损坏/加密、条目/大小/比例上限，以及源码与敏感文件混合时的全包校验和忽略结果；夹具只含模拟内容。
- PostgreSQL + 临时 Lucene 目录集成测试覆盖 V4 从 V1～V3 升级、显式映射、同分支排他任务、首次激活、替换/重建原子事务、失败保留旧索引、进程恢复和孤儿清理。
- Web 契约测试覆盖角色、202/400/401/403/404/409/413/415/416/422/503、字段与错误脱敏、默认 main、活动状态和不暴露物理路径。
- 查询集成测试以两个项目、两个同名分支和同名文件证明候选在 Lucene 查询前已强制隔离，并覆盖并发激活时单请求只读取一个 commit、敏感文件无法片段读取以及 reader 故障不回退。
- 单独的验收级测试生成 20 万行左右的确定性模拟 Java/Vue 文本，记录索引耗时并断言代表性路径/内容查询在目标环境 3 秒内完成；不把庞大 fixture 或构建索引提交到 Git。

每个测试方法必须有中文注释说明保护的业务目的和回归风险；公共端口、实现策略、事务/文件系统边界、结构校验、双层范围过滤与失败回退必须有符合项目规范的中文 Javadoc 或原因注释。

## Risks / Trade-offs

- [100 MiB 上传和 1 GiB 声明展开上限仍可能占用较长 CPU/磁盘时间] → 请求线程只流式持久化，后台逐文件限额处理并心跳；先用 20 万行模拟仓库测量，超界明确失败而不扩大默认值。
- [StoredField 保存完整允许文本会增大 Lucene 目录] → 单文件限制 2 MiB、不做代码向量化，MVP 用存储换取无需保留解压树的安全片段读取；实测容量不满足时再规格化独立内容存储。
- [数据库和 Lucene 目录无法组成真正原子事务] → 先完整发布/验证目录，再短事务切换数据库；查询只认数据库指针，孤儿目录可恢复清理，旧活动目录延迟回收。
- [自定义代码 analyzer 对所有语言的标识符拆分不可能完美] → 保留原 token 并组合分隔符/大小写拆分，路径权重高于内容；T4 只承诺关键词全文检索，语义检索和 AST 明确不在范围。
- [同分支部分唯一索引使用特定代码任务类型] → 迁移与任务常量共享契约测试；新增其他代码索引任务类型前必须更新该约束，不允许只加处理器绕过排他规则。
- [进程在对象写入后、数据库提交前退出可能留下未引用对象] → 正常异常路径立即补偿并记录；业务入口只认快照引用，运维可按无引用对象审计清理，不在 T4 引入通用垃圾回收器。
- [严格 UTF-8 会忽略使用其他编码的旧代码] → 明确记录 `UNSUPPORTED_ENCODING` 忽略计数，不猜测编码；如真实演示仓库需要其他编码，再基于样本更新规格和配置。

## Migration Plan

1. 先加入 Lucene 模块、强类型配置与 V4 迁移，并为 `/data/indexes/code` 和 `/data/work/code` 使用现有持久化卷/工作目录边界；部署前运行依赖树、许可证与安全扫描。
2. 部署包含新任务范围字段映射、事务提交后调度和代码能力的应用。V4 只有新表、索引和对当前可空任务范围列的外键，不转换现有知识/项目数据。
3. 使用小型模拟 ZIP 完成首次上传、搜索、片段读取、重建失败回退和服务重启恢复冒烟，再运行 20 万行级验收测试。
4. 回滚应用时停止新代码任务并等待或终结运行任务，然后部署旧应用；旧应用会忽略 V4 表和 Lucene 目录。不要回退已执行的 Flyway 版本，原始对象和新表保留供重新部署或人工清理。
5. 备份以 PostgreSQL 与原始对象为必要数据；Lucene 目录是可重建产物，但为缩短恢复时间可以一并备份。恢复后必须核对数据库活动 generation 指向的目录可打开，不能按目录修改时间自行选择索引。
