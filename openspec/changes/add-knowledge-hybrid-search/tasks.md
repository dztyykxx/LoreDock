## 1. 冻结契约与 Embedding 可行性

- [x] 1.1 先定义 `KnowledgeSearchUseCase`、查询/响应/结果模型、三种模式、过滤、活动 generation、关键词/语义候选、Embedding 和实时资格复核应用端口，以及 `KNOWLEDGE_INDEX_UNAVAILABLE`、`KNOWLEDGE_EMBEDDING_UNAVAILABLE` 错误；公共接口使用中文 Javadoc 明确范围、默认 `main`、上限、幂等性和失败语义，此步不创建 Controller、Mapper 或框架适配器。
- [x] 1.2 先定义 `GET /api/knowledge-search` 请求/响应 DTO 与 Web 契约，并编写带中文业务目的注释的失败契约测试，覆盖 GLOBAL/PROJECT、默认分支、KEYWORD/SEMANTIC/HYBRID、标签/格式/来源过滤、有限可引用结果、400/401/404/503 和非契约内部参数不能控制检索。
- [x] 1.3 使用 10～20 条公开中文句子建立 BGE 官方参考向量/排序夹具和带中文业务目的注释的失败 PoC 测试，覆盖 512 维、pooling、L2 归一化、查询指令、重复稳定、批量顺序、模型 checksum、离线资源和 CPU 启动/延迟/内存证据。
- [x] 1.4 记录 Spring AI 2.0.0 Transformers 与 BGE 官方参考不兼容的源码和 PoC 证据；核验并锁定 ONNX Runtime、Hugging Face tokenizer 的正式版本、Maven 依赖树、许可证、安全公告和 Java 21 兼容性，添加最小依赖、强类型离线模型配置和惰性 `KnowledgeEmbeddingPort` 适配器，直接读取锁定导出物的 `sentence_embedding` 使 1.3 通过；禁止 HTTP(S) 模型 URI、在线下载、Java 自制 tokenizer/pooling/归一化和框架类型泄漏到应用层。

## 2. V5 持久化、分块与关键词数据

- [x] 2.1 为 V5 编写带中文业务目的注释的失败真实 PostgreSQL 迁移测试，覆盖 V1→V5、V4→V5、空库/重复迁移、generation 一对一、分块复合键/FK、三类范围、offset/计数、512 维、标签/全文索引和旧 ACTIVE 无搜索元数据时浏览仍可用。
- [x] 2.2 追加 `V5__create_knowledge_search_tables.sql` 及 `knowledge_search_generation`、`knowledge_search_chunk` 的独立 Lombok 持久化实体与显式 MyBatis-Plus 映射，使 2.1 通过；不得修改 V1～V4、自动建表、使用 H2、Spring AI 默认 vector_store 或 XML Mapper。
- [x] 2.3 为 `cjk-v1` 确定性分块和 CJK 文本分析编写带中文业务目的注释的失败单元测试，覆盖 Markdown 标题/段落边界、400 code point 上限、80 重叠、Unicode 安全 offset、空正文、标题/标签/正文词项权重、英文缩写、单字和特殊字符不解释为查询语法。
- [x] 2.4 实现最小 `DeterministicKnowledgeChunker` 与 `CjkKnowledgeTextAnalyzer` 使 2.3 通过；复用已有 Lucene CJK 分析能力，不执行 Markdown/HTML/指令，并用中文注释解释分块可追溯性、短查询受范围限制回退和同一分析器处理索引/查询的原因。
- [x] 2.5 为分块批量写入和检索元数据仓储编写带中文业务目的注释的失败 PostgreSQL 测试，覆盖 TSVector A/B/C 权重、安全参数绑定、vector 维度/NaN/Infinity 拒绝、标签数组、模型/分块/融合版本和文档/分块计数往返；实现 Mapper 注解 SQL 与仓储使测试通过。

## 3. 分阶段重建与失败回退

- [x] 3.1 为知识重建的短 `REPEATABLE READ` 快照阶段编写带中文业务目的注释的失败集成测试，覆盖只冻结任务开始时的 PUBLISHED 文档、BUILDING 对搜索不可见、项目/分支/来源/标签元数据完整、文档并发编辑不混入同一 generation，并保留 T3 同步状态行为。
- [x] 3.2 重构现有 `KnowledgeIndexRebuilder` 为不可变投影快照、分块/批量 Embedding、完整校验和短事务激活四阶段，使 3.1 通过；按实际文档/分块更新进度与心跳，并为长 CPU 工作不得占用事实表快照事务补充中文实现说明。
- [x] 3.3 为重建故障编写带中文业务目的注释的失败真实 PostgreSQL 测试，覆盖模型缺失/checksum 不符、Embedding 中途失败、分块写入失败、维度/计数校验失败、激活事务失败、任务进程中断、重复 single-flight 不重复计算和失败后旧 ACTIVE 继续查询。
- [x] 3.4 实现 3.3 所需的幂等批次写入、完整性校验、原子退休/激活、失败 BUILDING 清理和恢复协调，使测试通过；日志记录 job/generation/模型摘要/计数/状态/错误码但不记录模型绝对路径、正文、向量或连接信息。
- [x] 3.5 为成功后保留当前与上一个 RETIRED、旧数据尽力清理失败不影响 ACTIVE 编写回归测试并实现最小清理；运行全部既有 T3 重建、生命周期和浏览测试，确认分阶段事务没有改变发布、归档、STALE/PENDING 或 single-flight 契约。

## 4. 关键词、语义候选与混合排序

- [x] 4.1 以两个项目、同名分支、三层知识范围、草稿/归档和相似内容为夹具，为关键词候选编写带中文业务目的注释的失败 PostgreSQL 测试，覆盖标题/标签高于正文、短查询回退、所有标签 AND、格式/来源过滤、固定 generation/candidate 上限及全局/项目/分支在候选 SQL 阶段零泄漏。
- [x] 4.2 使用参数化注解 SQL 实现关键词候选仓储使 4.1 通过，稳定按分数、document ID、chunk_no 排序；不接受客户端 TSQuery/SQL，不进行跨范围扫描后隐藏，并检查关键范围与短词分支的中文原因注释。
- [x] 4.3 为精确 pgvector 语义候选编写带中文业务目的注释的失败 PostgreSQL 测试，覆盖余弦排序、相似表达、generation/范围/标签/格式/来源前置过滤、固定候选上限、查询向量维度错误和其他项目/分支更近向量仍零泄漏。
- [x] 4.4 实现精确 `<=>` 语义候选仓储和查询 Embedding 协调使 4.3 通过；首版不增加 HNSW，记录代表性 EXPLAIN/耗时，只有完整基准证明超出 3 秒时才按 design 的 iterative scan 门禁评估近似索引。
- [x] 4.5 为 Reciprocal Rank Fusion 编写带中文业务目的注释的失败单元测试，覆盖固定 `k=60`、单路候选保留、双路共同命中加成、跨量纲排序、同文档多分块折叠、0～1 归一化、最佳片段来源和 score/更新时间/UUID 稳定 tie-break。
- [x] 4.6 实现最小 RRF、文档折叠和 500 code point 片段构造使 4.5 通过；在测试保护下集中候选上限和融合版本，不增加动态权重、reranker 或完整正文读取。

## 5. 搜索应用服务、实时资格与 HTTP

- [x] 5.1 为 `KnowledgeSearchService` 编写带中文业务目的注释的失败应用测试，覆盖范围解析、分支默认 `main`、全局参数残留、三模式调度、所有过滤传递、固定单一 generation、无索引/模型不匹配明确 503、KEYWORD 不依赖模型及无结果不扩大范围。
- [x] 5.2 扩展 5.1 覆盖实时归档、改范围、草稿、并发 generation 切换、资格删除后不从越界候选补足、项目分支无代码快照仍返回人工知识并携带 `CODE_SNAPSHOT_NOT_INDEXED`；实现应用服务复用 T2/T3/T4 端口使测试通过，并用中文注释解释双层资格复核不是展示层过滤。
- [x] 5.3 为搜索结构化日志编写捕获测试，确认开始、范围、两路候选、资格复核、完成和失败包含 traceId、稳定范围、mode、queryLength/hash、generation、候选/排除/返回数量、耗时和错误码，且不出现原始查询、正文、向量、对象键、模型绝对路径或内部异常；实现日志使测试通过。
- [x] 5.4 实现只调用 `KnowledgeSearchUseCase` 的 `KnowledgeSearchController`、Bean Validation、响应/错误映射和鉴权，使 1.2 的 Web 契约测试通过；确认未知/停用项目与未知分支复用现有 404，搜索结果不含完整正文、服务器路径、对象键、向量或内部配置。
- [x] 5.5 增加真实 PostgreSQL + Web 端到端测试，验证 ADMIN/MEMBER 成功、匿名 401、参数 400、无索引/Embedding 503、三个模式可引用响应、同输入稳定排序、同项目其他分支和其他项目不泄漏，并输出与断言一致的场景、范围、generation、结果 ID/排名和耗时证据。

## 6. 检索基准、文档与完成门禁

- [x] 6.1 创建仅含公开、脱敏或模拟内容的基准文档、`manifest.json` 和 15～20 个中文 `questions.json`，满足约 10 个场景包、约 5 个项目/架构/通用、至少 3 个无答案、至少 2 个口语相似表达；编写带中文业务目的注释的失败结构/敏感模式校验测试并实现校验器，标注由人工审查且不从待评估结果反推。
- [x] 6.2 编写并实现使用生产 `KnowledgeSearchUseCase` 的真实 PostgreSQL 基准执行器，固定应用/generation/模型 checksum/分块/融合/基准版本，分别运行 KEYWORD、SEMANTIC、HYBRID，生成逐题机器结果和中文摘要；generation 或配置中途变化、查询错误或模型缺失必须使正式运行失败而非输出伪成功。
- [x] 6.3 使用离线 BGE 模型和目标 CPU 环境运行正式基准，记录逐题文档 ID/排名、警告、候选/结果数、服务端耗时、无答案候选和环境摘要；只有 HYBRID 有答案 Top-5 ≥80%、所有范围/生命周期泄漏为零且代表性预热查询均不超过 3 秒时才提交 `docs/quality/T5知识混合检索基准报告.md`，调优只能修改统一版本化配置并重跑全量用例。
- [x] 6.4 更新 `.env.example`、README/运行与故障排查说明及 T5 架构文档，记录离线模型准备/checksum、CPU/内存、首次重建、503、失败回退、备份、模型许可和无在线下载边界；示例不得包含模型绝对路径、可用凭据、内部地址或真实材料。
- [x] 6.5 运行后端单元/Web/真实 PostgreSQL集成测试、Flyway 重复迁移、Embedding PoC、正式基准、Maven 依赖树/许可证/安全检查和敏感信息扫描；逐项检查测试中文业务注释、公共接口/实现/事务/范围/失败回退中文注释及生产/测试证据日志，记录任何未执行验证。
- [x] 6.6 对照两份 delta spec 逐条复核正常、边界和失败场景，勾选实际完成任务并运行 `openspec validate add-knowledge-hybrid-search --strict`；只有全部完成门禁满足、主规格同步且 change 准备归档时，才把 `docs/product/LoreDock_MVP功能开发计划.md` 的 T5 更新为 `[x]`。
