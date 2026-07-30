## Why

T3 已提供可原子切换的已发布知识投影，T4 已完成按项目与分支隔离的代码检索，但 LoreDock 仍缺少能够理解中文自然语言、严格执行知识范围并返回可引用来源的业务知识搜索。依据 MVP 开发计划的 T5 及需求基线 FR-SRCH-01～06，现在需要补齐关键词、语义与混合检索，作为后续单 Agent 问答和 MCP 只读服务的共同检索入口。

## What Changes

- 新增通用知识查询与项目知识查询契约；项目查询解析项目和分支（省略分支时使用 `main`），全局查询只允许通用知识。
- 对活动知识 generation 中的标题、正文和标签建立中文关键词索引，并使用 CPU 可运行的中文 Embedding 与 pgvector 建立语义索引。
- 将关键词候选与向量候选进行稳定融合和排序，并支持标签、知识类型、来源类型过滤以及有上限的 Top-K 返回。
- 在候选生成、融合和最终返回前强制执行发布状态、项目、分支与知识范围校验，旧投影中的已归档或已越界文档不得返回。
- 每条结果返回范围、标题、有限片段、来源、更新时间、相关性及可供后续问答/MCP 引用的稳定文档标识；无命中时返回空结果，不扩大范围。
- 扩展现有知识重建流程，使关键词与向量数据在完整构建、校验成功后随同一个 generation 原子激活；Embedding 或索引构建失败时保留旧活动 generation。
- 建立 15～20 个不含内部敏感数据的真实问题基准集和可重复执行的评估报告，验证 Top-5 正确来源命中率、范围隔离和代表性查询性能。
- 不在本变更中实现 Web 问答、模型答案生成、代码与知识的统一混合排序、MCP 工具或搜索页面专项改版；这些由后续 T6A、T7、T11、T12 处理。

## Capabilities

### New Capabilities

- `knowledge-hybrid-search`: 定义知识关键词、语义与混合检索的范围解析、过滤、结果、错误、索引切换和性能行为。
- `knowledge-search-benchmark`: 定义检索基准集、Top-5 质量门槛、隔离校验和可复现报告要求。

### Modified Capabilities

无。现有 `knowledge-document-lifecycle` 仍负责文档事实、发布资格和活动 generation；本变更通过新增检索能力消费并扩展该稳定边界，不改变既有生命周期需求。

## Impact

- 后端新增知识检索应用端口、请求/响应 DTO、范围解析与混合排序服务，并在 `knowledge.infrastructure` 增加 PostgreSQL 全文/pgvector 持久化和 CPU Embedding 适配器。
- Flyway 追加知识分块、关键词向量、Embedding 模型元数据及必要关系/向量索引，不修改既有迁移；MyBatis-Plus 实体与领域/HTTP DTO 保持分离。
- `POST /api/admin/knowledge-index-jobs` 的同一后台流程增加分块、Embedding 和检索结构构建，但保留 single-flight、失败回退和活动 generation 原子切换语义。
- 新增只读知识搜索 API，供后续 Web 问答、内部 Agent 和 MCP 复用；现有代码搜索 API 与 Lucene 索引不改变。
- 需要引入 Java ONNX Runtime 与 Hugging Face tokenizer 的最小依赖和离线模型配置，直接读取官方 Sentence-Transformers 导出图中已完成 CLS pooling 与 L2 归一化的 `sentence_embedding`；实现前验证与当前 Java 21/Spring Boot 基线的兼容性、许可证、传递依赖和 CPU 性能，不把 Spring AI 或 ONNX 类型泄漏到应用层。
- 新增真实 PostgreSQL 集成测试、Embedding 可重复性/失败测试、范围泄漏测试、排序测试、性能夹具和基准报告；所有夹具仅使用公开、脱敏或模拟内容。
