## Context

参见 `proposal.md` 的动机，以及 `specs/knowledge-hybrid-search/spec.md`、`specs/knowledge-search-benchmark/spec.md` 的行为契约。T3 已建立 `knowledge_document` 事实表、不可变 `knowledge_index_document` 投影、`knowledge_index_generation` 原子切换和 `PublishedKnowledgeEligibilityReader` 实时资格复核；当前重建器只复制整篇文档，不分块、不计算关键词或向量。T4 已提供可复用的项目/分支解析、活动代码快照查询和明确的“无活动快照”语义，但知识与代码仍保持独立检索。

当前后端是 Java 21、Spring Boot 4.1.0、Spring AI 2.0.0、MyBatis-Plus、PostgreSQL 17 + pgvector 0.8.1 和 Lucene 10.5.0。T6A 将迁移到 Spring Boot 3.5.x 与 Spring AI Alibaba 1.1.2.x，因此 T5 不能把 Spring AI 类型泄漏进知识检索应用契约。部署是内网单实例、CPU 推理，模型文件必须离线提供；现有活动 generation 可能在部署 T5 前已存在，但没有可搜索分块。

官方资料确认 BGE `bge-small-zh-v1.5` 为 24M 参数、MIT 许可证且适用于中文检索，pgvector 支持余弦距离并提醒近似索引叠加过滤可能召回不足。实现期 PoC 进一步确认：官方 Sentence-Transformers ONNX 导出同时提供 `token_embeddings` 与已完成 CLS pooling、L2 归一化的 `sentence_embedding`；后者与固定参考夹具最大绝对误差为 `2.32e-7`。Spring AI 2.0.0 `TransformersEmbeddingModel` 则固定对三维输出执行 mean pooling 且不归一化，同一查询的输出范数为 `9.1581`、与官方句向量余弦仅 `0.7371`，因此不能作为本模型适配器。T5 改用最小 ONNX Runtime 与 Hugging Face tokenizer 直接消费官方 `sentence_embedding`，不在 Java 中重写 tokenizer、pooling 或归一化。

## Goals / Non-Goals

**Goals:**

- 以接口优先方式形成 Web、后续 Agent 与 MCP 都能复用的 `KnowledgeSearchUseCase`，让调用方无法指定内部 generation、向量或排序参数。
- 在 PostgreSQL 内完成范围过滤、关键词候选和精确向量候选，先保证范围正确性、可恢复切换与小规模 MVP 的可维护性。
- 把中文分析、分块、Embedding、候选读取、融合和实时资格复核拆成有真实替换/测试意义的端口，隔离下一任务的 Spring AI 版本迁移。
- 延续 T3 的 single-flight 后台任务和失败回退语义，同时缩短持有文档事实表快照事务的时间。
- 用固定、可公开提交的基准数据证明 Top-5、范围隔离和 3 秒目标，而不是只依赖单元测试通过。

**Non-Goals:**

- 不统一知识与 Lucene 代码结果、不生成回答、不判断“能否回答”或实现拒答文案；无答案问题只记录候选风险，最终拒答由 T6A/T7 完成。
- 不引入 Elasticsearch/OpenSearch、外部 Python/TEI 服务、reranker、动态权重、个性化排序或增量索引。
- 不让客户端选择旧 generation、模型、向量、候选数或融合公式，也不暴露分块正文之外的完整知识正文。
- 不在 T5 改造知识浏览页面或新增独立搜索页面；只提供后端契约和基准执行入口。

## Decisions

### 1. 先定义知识检索应用契约，再实现 PostgreSQL 与 ONNX 适配器

在既有 `knowledge` 能力内增加语义明确的子包，保持“基础设施 → 应用 → 领域”方向：

```text
knowledge/
  application/search/
    KnowledgeSearchUseCase
    KnowledgeSearchQuery / KnowledgeSearchResponse / KnowledgeSearchResult
    ActiveKnowledgeSearchGenerationReader
    KnowledgeKeywordCandidatePort / KnowledgeSemanticCandidatePort
    KnowledgeEmbeddingPort / KnowledgeSearchEligibilityReader
    KnowledgeSearchService / ReciprocalRankFusion
  infrastructure/search/
    PostgresKnowledgeSearchRepository
    OnnxRuntimeKnowledgeEmbeddingAdapter
    CjkKnowledgeTextAnalyzer / DeterministicKnowledgeChunker
  infrastructure/web/
    KnowledgeSearchController
```

`KnowledgeSearchUseCase.search` 接收业务查询值：上下文、项目标识、分支名、纯文本查询、模式、标签、格式、来源类型和 limit。应用服务复用 `KnowledgeScopeResolver.resolveBrowse` 得到 UUID 范围，并通过 T4 的活动代码快照只读用例生成 `CODE_SNAPSHOT_NOT_INDEXED` 警告；代码快照不存在不阻止知识检索。响应 DTO 只包含规格允许的引用元数据。

`KnowledgeEmbeddingPort` 只暴露模型描述、批量文档向量和单条查询向量，全部使用项目自有 DTO；Hugging Face tokenizer 与 ONNX Runtime 类型只存在于基础设施适配器。这样 T6A 更换 Spring AI BOM/Starter 时不改变检索、索引或范围契约。适配器只组织输入、调用锁定导出图的 `sentence_embedding` 并校验输出，不自行实现 tokenizer、pooling 或归一化；直接在应用服务注入 ONNX 或后续 Spring AI 类型会破坏稳定业务边界，因此不采用。

公共端口、应用服务、Controller 和适配器使用中文 Javadoc；范围必须在候选 SQL 前解析、旧投影仍要实时复核、混合模式不能静默降级、查询文本不能进入日志等关键分支使用中文原因注释并引用本 change 的业务术语。

### 2. 单一只读 API 表达三种模式，默认混合检索

新增：

```text
GET /api/knowledge-search
  ?query=<1..500 chars>
  &context=GLOBAL|PROJECT
  &project=<identifier>          # PROJECT 必填
  &branch=<name>                 # PROJECT 可选，默认 main
  &mode=KEYWORD|SEMANTIC|HYBRID  # 默认 HYBRID
  &tag=<tag>                     # 可重复，最多 10，全部匹配
  &format=MARKDOWN|PLAIN_TEXT
  &sourceType=MANUAL|WIKI|UPLOAD
  &limit=<1..50>                 # 默认 10
```

成功响应为：

```text
KnowledgeSearchResponse
  context { type, projectIdentifier?, branch? }
  mode
  generationId
  warnings[]
  results[]
    documentId
    scope { type, projectIdentifier?, branch? }
    title, snippet, truncated
    format, tags[]
    source { type, wikiUrl?, originalFilename?, curationNote? }
    sourceUpdatedAt
    relevance
    matchedBy
```

`curationNote` 只有在现有普通文档视图已经允许公开时才返回；否则响应映射删除该字段，以既有知识浏览契约为准。`relevance` 是本 generation 内用于排序和调试的 0～1 归一化融合值，不承诺跨 generation 可比较。响应不提供总命中数，因为两路候选融合后的精确总数需要无界计算且后续 Agent 只需要 Top-K。

无活动可搜索 generation 返回 503 `KNOWLEDGE_INDEX_UNAVAILABLE`；语义/混合模式的模型不可用或校验不一致返回 503 `KNOWLEDGE_EMBEDDING_UNAVAILABLE`；未知/停用项目与未知分支复用 T2 的 404；输入校验返回 400 `INVALID_REQUEST`。GET 幂等，不产生索引或模型下载副作用。选择一个端点加 `mode` 而不是三套端点，是为了让范围、过滤、结果和错误完全共用，避免后续 Agent/MCP 复制隔离规则。

### 3. 追加 V5 分块表并与 T3 generation 一对一绑定

不修改 V1～V4，追加 `V5__create_knowledge_search_tables.sql`。V5 不建立 Spring AI 默认 `vector_store`，因为默认表不能清楚表达现有 generation、实时文档事实和强范围字段。

```text
knowledge_search_generation
  generation_id UUID PK/FK knowledge_index_generation ON DELETE CASCADE
  model_id VARCHAR(200)
  model_checksum CHAR(64)
  vector_dimension INT CHECK = 512
  chunk_strategy_version VARCHAR(64)
  fusion_config_version VARCHAR(64)
  document_count BIGINT
  chunk_count BIGINT
  created_at TIMESTAMPTZ

knowledge_search_chunk
  generation_id UUID
  document_id UUID
  chunk_no INT
  start_offset INT, end_offset INT
  content TEXT
  title_terms TEXT, tag_terms TEXT, content_terms TEXT
  search_vector TSVECTOR
  embedding VECTOR(512)
  scope_type, project_id, branch_id
  format, source_type
  normalized_tags TEXT[]
  source_updated_at TIMESTAMPTZ
  PK(generation_id, document_id, chunk_no)
  FK(generation_id, document_id) -> knowledge_index_document
```

关系字段在 chunk 上有意冗余，使关键词与向量 SQL 在生成候选时就带上 generation、`GLOBAL|(PROJECT)|(BRANCH)`、格式、来源和标签条件；不得先扫描全库再按文档表隐藏。数据库 `CHECK` 复用三类范围组合、正数计数、offset 与 512 维约束；B-tree 索引覆盖 generation/范围/过滤，GIN 覆盖 `search_vector` 和标签数组。

MyBatis-Plus 实体逐字段显式映射；`TSVECTOR` 构造、`@@`/`ts_rank_cd`、`VECTOR` 写入和 `<=>` 余弦距离通过 Mapper 注解 SQL 表达，不使用 XML。Embedding 作为经过长度和数值校验的参数绑定，禁止字符串拼接 SQL；任何 NaN、Infinity 或维度不符在写入前失败。

现有 T5 之前的 `ACTIVE` generation 没有 `knowledge_search_generation` 元数据，因此知识浏览继续正常，而新搜索端点明确返回 `KNOWLEDGE_INDEX_UNAVAILABLE`，直到管理员成功执行一次重建。不做启动时隐式迁移或自动模型计算，避免部署即触发高 CPU 和不可回滚副作用。

### 4. 关键词使用 Lucene CJK 分析结果写入 PostgreSQL tsvector

关键词路径复用项目已有 `lucene-analysis-common` 的 `CJKAnalyzer`，对 NFC 规范化后的标题、标签和正文分块产生词项；词项作为参数交给 PostgreSQL `to_tsvector('simple', ...)`，分别设置标题 A、标签 B、正文 C 权重并合并为 `search_vector`。查询使用同一分析器生成受限词项，并构造服务端控制的 OR `tsquery`；标题或标签的相同命中通过 `ts_rank_cd` 权重高于正文。无法形成 CJK 词项的合法极短查询只在已限定 generation/范围/过滤后的标题、标签和有限分块上使用转义后的精确子串回退，不解释客户端查询语法。

选择 CJK 词项 + PostgreSQL GIN，而不是 PostgreSQL 默认语言配置，是因为默认分词不能可靠覆盖无空格中文；选择它而不是为知识再建一套文件系统 Lucene generation，是为了让关键词、向量和 T3 投影共享同一次 PostgreSQL 原子激活。`pg_trgm` 可处理多语言子串，但短词阈值和长正文相似度难以表达标题/标签/正文权重，故本次不新增该扩展。

### 5. Embedding 采用离线 BGE 小模型、正确性夹具和惰性初始化

首选 `BAAI/bge-small-zh-v1.5` ONNX，固定 512 维与余弦距离。部署通过配置提供 `model.onnx`、`tokenizer.json`、模型 ID、SHA-256、输出节点、最大 token 数和是否使用查询指令；配置示例只写相对挂载位置，不提交大模型文件。适配器禁止 HTTP(S) URI并禁用远程缓存下载，启动时不因模型缺失阻止文档浏览或关键词查询；第一次语义重建/查询前在受控锁内惰性校验与加载。

实现前先建立隔离 PoC：用官方参考实现为 10～20 条固定公开中文句子生成基准，验证 ONNX 导出包含正确的 pooling 与 L2 归一化、同文本重复结果在容差内稳定、查询指令只加在 query 侧、维度为 512、余弦排序与参考一致，并记录启动时间、批量吞吐、单次延迟、堆/直接内存。PoC 已按上述数值证据拒绝 Spring AI 2.0.0 `TransformersEmbeddingModel`；修订后的门禁要求 ONNX Runtime 适配器直接读取官方导出图的二维 `sentence_embedding`，并与参考向量及排序一致。若该路径仍不一致，继续停止实现并更新技术决策，不用错误向量、Java 自制 pooling/归一化或临时公网服务通过测试。

选择 ONNX Runtime 与 Hugging Face tokenizer 的最小成熟组件，是因为官方导出图已经提供正确句向量，继续套用 Spring AI 的固定 mean pooling 反而会改变模型语义。适配器不得复制 Transformer、tokenizer、pooling 或归一化算法，只负责本地资源校验、输入批处理、输出节点选择和业务 DTO 转换；端口隔离控制 T6A 迁移影响。选择 24M 的 BGE small 而不是 BGE-M3，是为了满足 CPU 单机约束。查询使用模型推荐中文检索指令，passage 不加指令；该选择与模型 checksum、分块和融合配置一起写入 generation 元数据。

### 6. 以不可见 BUILDING generation 分阶段构建并短事务激活

现有 T3 将“复制投影 + 激活”放在一个 `REPEATABLE READ` 事务中。加入 CPU Embedding 后继续持有长事务会放大 MVCC、连接占用和失败成本，因此在不改变外部 single-flight/原子可见性语义的前提下改为：

1. 在短 `REPEATABLE READ` 事务中创建 `BUILDING` generation，并把任务开始时的全部 `PUBLISHED` 文档复制为不可变 `knowledge_index_document` 投影后提交；它对正式搜索不可见。
2. 从该不可变投影按文档 ID 分批读取，确定性分块、批量计算 Embedding，并以短事务幂等写入 `knowledge_search_chunk`；后台任务按实际文档/分块数量更新进度和心跳。
3. 校验来源文档数、分块连续性、每文档至少一个分块、offset、关键词向量非空、Embedding 维度/有限数值、模型 checksum 和持久化计数。
4. 在单个短事务中锁定当前 `ACTIVE`，将其改为 `RETIRED`，再把完整 BUILDING generation 改为 `ACTIVE`。`knowledge_search_generation` 与 chunk 通过 FK 同步可见。
5. 成功后保留当前与上一个 `RETIRED` generation；更旧数据尽力清理。失败时后台任务保存脱敏错误，上一个 ACTIVE 不变，失败 generation 尽力删除；进程中断恢复时清理属于失败任务的 BUILDING 数据。

分块策略 `cjk-v1` 优先按 Markdown 标题/段落和换行边界切分，再按 Unicode code point 截断到最多 400 字符、重叠 80 字符；空正文仍用标题和标签产生一个分块。Embedding 输入为标题、标签与当前正文块，tokenizer 最长 512 tokens 并显式 padding/truncation。offset 相对 T3 投影正文，供结果构造片段；分块器不能执行 Markdown、HTML 或指令。

这样重建期间文档状态变化不会修改已冻结投影，但返回前实时资格复核仍能立即排除归档或移出当前范围的文档。发布或编辑的新修订继续显示 `PENDING/STALE`，只有下一次成功重建才进入搜索。

### 7. 先做精确向量检索，再用 RRF 稳定融合文档结果

MVP 首版使用 pgvector 余弦 `<=>` 在已限定 generation、范围、格式、来源和标签后的 chunk 集合上精确 Top-K，不建 HNSW。理由是当前知识量有限，严格过滤后的精确检索没有近似索引“先取近邻再过滤导致不足”的召回风险，并能让 15～20 问题基准更稳定。只有基准证明精确扫描无法达到 3 秒且数据量足以受益时，才另行评估 HNSW；若在本 change 内加入，则必须启用 pgvector 0.8 的 iterative scan、保留范围关系索引并重新跑全部隔离/召回基准。

关键词与语义每路候选数固定为 `min(max(limit * 5, 50), 200)`。先在各路按 chunk 分数、document ID、chunk_no 稳定排序，再按文档折叠，保留每路最佳分块和最小名次。混合使用 Reciprocal Rank Fusion：`score = Σ 1 / (60 + rank)`，两路同权；单路模式只使用该路名次。最终分数除以理论最大值归一到 0～1，依次按分数降序、`sourceUpdatedAt` 降序、document ID 升序，`matchedBy` 由实际命中路数产生。

RRF 比直接相加 `ts_rank_cd` 与余弦分数更稳健，因为两者量纲和分布不同，也不需要为当前小基准训练权重。固定候选上限、常数和版本写入配置与 generation 元数据，客户端不能覆盖。文档折叠后再调用批量实时资格端口；若资格复核删除结果，不再从越界候选补足，也不扩大候选范围。

### 8. 结果片段只来自固定 generation 的最佳分块

最佳分块 `content` 是 T3 活动投影正文的有限片段，不重新读取当前完整正文；这样同一响应的分数、片段和来源更新时间属于同一个 generation。关键词模式以分析器命中的 token offset 选择窗口，语义模式以最佳向量 chunk 为窗口，混合模式优先使用两路共同命中的块，否则使用 RRF 贡献更高的块。窗口最多 500 Unicode code points，并在 code point 边界截断；不使用 HTML 高亮，避免把导入 Markdown 当作可执行内容。

实时资格复核只决定候选是否仍可返回，不把当前新修订正文拼到旧分数上。已发布文档编辑但未重建时继续返回旧 projection 片段并由既有同步状态提示 `STALE`；后续问答引用稳定文档 ID 和 generation，可追溯到检索时实际证据。

### 9. 基准使用可提交 fixture、生产应用端口和双格式报告

新增：

```text
backend/src/test/resources/knowledge-search-benchmark/
  documents/*.md
  manifest.json
  questions.json
backend/src/test/java/.../KnowledgeSearchBenchmarkIT.java
docs/quality/T5知识混合检索基准报告.md
```

fixture 创建两个模拟项目、`main` 与演示分支、三层范围、草稿/归档和 15～20 个问题；稳定文档 ID 在 manifest 中固定。集成测试使用真实 pgvector PostgreSQL、真实分块/关键词/Embedding/混合服务和正式应用端口；只有模型正确性小测试允许使用固定向量假实现隔离排序规则。正式基准需要显式 profile 和本地离线模型，缺少模型时报告为未执行而不是伪造通过。

执行器先校验问题构成和敏感模式，再固定 generation/config checksum，预热模型，然后逐题运行 KEYWORD、SEMANTIC、HYBRID。JSON 测试输出保存逐题实际值；中文报告汇总 Top-5、所有范围/生命周期违规、无答案候选、每次服务端耗时和环境摘要。提交报告前人工检查 fixture 与标注。测试证据日志从真实响应提取场景、范围、返回 ID/排名、候选数、耗时和错误码，与断言一致且不输出正文或原始查询。

### 10. 日志、错误与测试围绕范围泄漏和失败回退

生产日志在搜索开始、范围解析、候选完成、资格复核、完成和失败位置记录结构化字段：traceId、operation、上下文、项目/分支 UUID、mode、queryLength/queryHash、过滤数量、generation、关键词/语义候选数、资格排除数、结果数、耗时、result/errorCode。重建日志记录 job/generation、模型 ID/checksum 短摘要、批次文档/分块数、状态转换和失败类别；不记录模型绝对路径、原始查询、正文、向量或连接信息。

最小但高风险测试集：

- 应用契约测试先覆盖全局/项目/main 默认范围、三模式、过滤、稳定排序、有限结果、代码快照警告和错误码。
- PostgreSQL 集成测试覆盖 V5 升级/重复迁移、显式映射、范围字段和 512 维约束、关键词三字段权重、精确余弦范围过滤、同名跨项目/分支零泄漏、实时归档/改范围复核。
- 重建集成测试覆盖短快照事务、确定性分块、批量 Embedding、计数/维度校验、原子激活、中途失败与进程恢复保留旧 ACTIVE、single-flight 不重复计算。
- Embedding PoC/集成测试覆盖离线资源、checksum、参考向量/排序、查询指令、重复稳定、CPU 延迟/内存证据和缺失模型不影响 KEYWORD。
- 融合单元测试只保护 RRF 跨量纲、单路保留、双路加成、文档折叠和稳定 tie-break，不测试框架或 getter。
- 基准集成测试保护 80% Top-5、全部隔离门禁、无答案记录与 3 秒报告；性能结果按记录环境解释，不用 CI 抖动放宽业务断言。

所有业务测试遵循红—绿—重构，每个测试方法有中文注释说明业务目的和防止的回归，并输出来自真实返回/数据库状态的证据。

## Risks / Trade-offs

- [直接 ONNX 适配可能因导出工具升级改变输出节点或数值] → 锁定模型 revision、导出工具版本、`sentence_embedding` 输出名和模型 checksum；每次更换产物都重跑完整参考向量/排序 PoC，禁止退回 Spring AI mean pooling 或在 Java 中补写 pooling。
- [精确向量扫描随分块数增长而变慢] → 先用关系条件缩小集合、固定候选上限并记录 explain/耗时；只有真实基准超标才引入 HNSW iterative scan，避免过早牺牲范围召回。
- [构建流程由单事务改为分阶段后会遗留 BUILDING 数据] → generation 对正式查询不可见，恢复流程按失败 job 清理，激活仍是短事务与唯一 ACTIVE 约束。
- [标题和标签复制到每个 chunk 增加存储] → 换取候选 SQL 无需跨范围后过滤和清晰权重；MVP 文档规模可接受，报告记录 chunk 数与表体积。
- [CJK bigram 对单字、专有英文缩写或混合标点召回不稳定] → 英文/数字沿用标准 token，合法短查询有受范围限制的精确子串回退，基准包含中文口语和项目术语。
- [RRF 固定同权可能不是所有问题的最优排序] → 用版本化常数和逐模式报告保持可解释；只根据完整基准调一次配置，不为单题设置权重。
- [模型惰性加载使第一次语义查询接近或超过 3 秒] → 正式环境在重建/健康准备阶段预热，基准分开记录冷启动与预热查询；服务不在线下载模型。
- [旧 ACTIVE generation 在 V5 部署后不可搜索] → 浏览保持可用，搜索明确 503；部署步骤要求管理员执行一次成功重建后再开放搜索入口。

## Migration Plan

1. 用隔离 PoC 记录 Spring AI 2.0.0 Transformers 路径与 BGE 官方参考不兼容的证据；锁定最小 ONNX Runtime、Hugging Face tokenizer 依赖、许可证、安全公告、BGE ONNX 导出参数、`sentence_embedding` 输出节点、checksum、参考向量和 CPU 指标，全部通过后才保留运行依赖。
2. 先定义应用接口、DTO、错误码和失败测试，再追加 V5 与显式 MyBatis-Plus 实体/Mapper；在真实 PostgreSQL 验证 V1→V5、V4→V5 和重复迁移。
3. 实现分块/分析/Embedding 端口及分阶段重建，使旧 T3 测试继续通过；用故障注入证明任何中途失败保留旧 ACTIVE。
4. 实现受范围约束的关键词/语义候选、RRF、实时资格复核与 HTTP 适配器，完成业务日志和接口契约测试。
5. 导入公开模拟基准文档，执行首次 T5 重建；只有新的 `knowledge_search_generation` 完整激活后才开放搜索端点。
6. 运行 15～20 问题正式基准并提交机器结果与中文摘要；Top-5、零范围泄漏或代表性查询任一未达门禁时继续调优，不更新 T5 为完成。
7. 回滚应用版本时保留 V5 表，不执行破坏性降级；旧应用忽略 V5。若新重建失败，旧 ACTIVE 继续服务；若回滚到不认识搜索表的版本，同步关闭搜索入口但知识浏览和 T3 重建仍可按旧版本运行。
