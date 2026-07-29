# 项目业务上下文知识库——Java 技术栈调研与 MVP 落地建议

| 属性 | 内容 |
|---|---|
| 调研日期 | 2026-07-29 |
| 调研目标 | 判断 Java 主技术栈能否覆盖 Agent、RAG、MCP、代码检索和 CPU Embedding，并给出 MVP 可执行组合 |
| 结论 | 使用 Java 单体后端；MVP 运行时不引入 Python，Embedding 优先采用 Java ONNX，必要时再切换独立推理服务 |

## 1. 结论摘要

Java 可以完整覆盖当前 MVP，且比 Python 主后端更符合本项目的业务结构和后续维护要求。

推荐组合：

| 领域 | 推荐方案 |
|---|---|
| Java 运行时 | Java 21 |
| Web 后端 | Spring Boot 4.1.x、Spring MVC |
| AI 集成 | Spring AI 2.0.x，使用 BOM 锁定版本 |
| 大模型 | 公司 MiniMax M2.7，通过兼容接口接入 |
| Agent | Spring AI Tool Calling 基础能力 + 自研受控执行循环 |
| Skill | 项目内 Markdown/YAML 文件，纳入 Git 版本管理 |
| MCP | Spring AI MCP WebMVC Starter，Streamable HTTP |
| 业务数据库 | PostgreSQL 15+ |
| 向量 | pgvector 0.8.x，HNSW/Cosine |
| CPU Embedding | 首选 `BAAI/bge-small-zh-v1.5` ONNX，在 Java 进程内运行 |
| 代码检索 | Apache Lucene 10.x |
| 原始文件 | MVP 使用本地持久化卷；通过统一接口保留 S3 兼容实现 |
| 前端 | Vue 3 + TypeScript + Vite |
| 部署 | 单体应用 + PostgreSQL，Docker Compose |

两个重要调整：

1. **不默认部署 MinIO。** MinIO 社区仓库已在 2026 年 4 月归档，社区版改为源码分发，并采用 AGPLv3。MVP 使用本地持久化卷更稳妥；如公司已有 S3 兼容存储，再通过适配器接入。
2. **不默认运行 Python 服务。** Spring AI 已能通过 ONNX 在 JVM 内计算 Embedding。只有在中文模型兼容性或 CPU 性能实测不达标时，才增加独立推理容器。

## 2. 为什么 Java 方案成立

当前系统的主要复杂度并不是训练模型，而是：

- 项目、分支、代码快照和文档版本管理；
- 需求、PR、commit、代码文件与知识文档的追溯关系；
- 文件导入、解压、索引、失败恢复和原子切换；
- 草稿、审核、发布、归档和检索范围隔离；
- Web API、MCP、Token、运行记录和后台任务；
- Agent 工具权限、最大步骤数、拒答和引用。

这些功能属于典型服务端业务系统，Java 的类型、事务、模块边界和团队可维护性比 Python 主后端更重要。

同时，Java AI 生态已经具备本项目需要的基础能力：Spring AI 官方 API 包含模型抽象、同步/流式调用、Tool Calling、RAG、Vector Store、ETL 和 MCP；不再需要为了 Agent 或 MCP 单独选择 Python。[Spring AI API](https://docs.spring.io/spring-ai/reference/api/)

## 3. 版本选择

### 3.1 推荐 Java 21

Lucene 10 要求 Java 21 或更高版本，因此 Java 21 是当前最合理的统一基线。[Lucene 10 系统要求](https://lucene.apache.org/core/10_0_0/SYSTEM_REQUIREMENTS.html)

使用 Java 21 还能获得较新的 JVM 性能和并发能力，同时仍是长期支持版本。

### 3.2 推荐 Spring Boot 4.1.x + Spring AI 2.0.x

Spring AI 2.0.x 官方支持 Spring Boot 4.0.x 和 4.1.x，并提供 `spring-ai-bom:2.0.0` 管理依赖版本。[Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)

选择原则：

- 使用正式发布版，不使用 SNAPSHOT 或 Milestone；
- 通过 Spring Boot Parent 和 Spring AI BOM 锁定依赖；
- MVP 开发期间不主动升级大版本；
- 直接使用 Starter，避免手动混入不同版本的 MCP SDK。

如果公司内网只能提供 Spring Boot 3.x，则降级方案是 Spring Boot 3.5.x + Spring AI 1.1.8；该分支同样已有 Streamable HTTP MCP 支持。但这是兼容方案，不是首选。

## 4. MiniMax M2.7 接入

MiniMax 官方提供 OpenAI 兼容接口，并明确文本模型支持工具调用；Spring AI 的 OpenAI 模块支持自定义 `base-url`、API Key、工具、流式返回和手动 Tool Calling 循环，因此协议层面具备直接接入条件。[MiniMax API 概览](https://platform.minimax.io/docs/api-reference/api-overview)、[Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)

但是公司提供的内部网关不一定与公网接口完全一致，因此模型接入必须放在独立接口后：

```java
public interface KnowledgeChatModel {
    AgentModelResponse call(AgentModelRequest request);
    Flux<AgentModelEvent> stream(AgentModelRequest request);
}
```

建议实现：

```text
SpringAiMiniMaxChatModel     默认实现
RawHttpMiniMaxChatModel      协议兼容出现问题时的备用实现
FakeChatModel                本地测试实现
```

开发第一天需要验证：

- 普通对话；
- 流式输出；
- 单次工具调用；
- 连续两轮工具调用；
- 工具参数 JSON；
- 超时、限流和错误格式；
- 输出中 reasoning/thinking 字段的处理；
- 最大输出长度是否足以生成知识文档。

## 5. Agent 与 Skill 实现

### 5.1 不使用复杂 Agent 框架

Spring AI 支持由应用控制 Tool Calling 循环：应用检查模型返回的工具调用、执行工具、将结果加入上下文，然后继续请求模型。[Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)

这正适合当前需求，因为系统必须自己控制：

- 当前 Skill 的工具白名单；
- 最大执行步骤；
- 最大检索数量和上下文长度；
- 项目和分支范围；
- 草稿写入权限；
- 来源引用；
- 超时、取消和运行日志。

建议执行结构：

```text
加载 Skill
→ 构造系统提示和工具白名单
→ 请求 MiniMax
→ 校验工具名与参数
→ 执行内部工具
→ 保存工具调用摘要
→ 回传结果
→ 完成、拒答或达到运行限制
```

### 5.2 Skill 文件格式

MVP 使用项目内文件，不做数据库可视化编排器：

```text
skills/
├── project_qa/SKILL.md
├── document_writer/SKILL.md
└── change_documenter/SKILL.md
```

每个 Skill 头部使用 YAML 元数据：

```yaml
name: change_documenter
version: 1.0.0
allowed_tools:
  - knowledge_search
  - document_read
  - code_search
  - change_material_read
  - draft_save
max_steps: 8
output_schema: change_knowledge_draft_v1
```

Agent 运行记录保存 Skill 名称、版本、模型、步骤数、工具调用、引用和最终状态。

## 6. MCP 实现

Spring AI 2.0 提供 WebMVC Streamable HTTP Starter，并可通过 `@McpTool` 自动生成工具 Schema。[Spring AI Streamable HTTP MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)、[MCP Server Annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)

建议：

- 使用 Spring MVC，不为 MCP 单独引入 WebFlux；
- 对外只暴露 `knowledge_search`、`document_read`、`code_search`；
- MCP 工具直接复用内部查询 Service，不复制检索逻辑；
- 使用 Spring Security Filter 校验共享 Token；
- 不启用 MCP Sampling、Elicitation 和写工具；
- 首先尝试 Stateless Streamable HTTP；若 Claude Code 兼容性测试不通过，再切换有状态 Streamable HTTP。

MCP Java SDK已有正式版本并支持 Streamable HTTP，Java 不再是 MCP 的次级实现。[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)

## 7. 文档向量检索

### 7.1 PostgreSQL + pgvector 可行

Spring AI 为 pgvector 提供 Starter、HNSW、Cosine Distance、批量写入和元数据过滤。[Spring AI PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)

pgvector 当前 0.8.x 支持 HNSW、过滤和迭代扫描；官方也提醒近似索引与过滤组合可能导致返回数量不足，需要配置迭代扫描、分区或额外索引。[pgvector Filtering](https://github.com/pgvector/pgvector)

本项目必须把以下条件作为服务器生成的强制过滤条件：

```text
status = PUBLISHED
AND (
  scope = COMMON
  OR project_id = 当前项目
)
AND (
  branch_id IS NULL
  OR branch_id = 当前分支
)
```

不能把过滤表达式完全交给模型或客户端生成。

### 7.2 表结构建议

不要只依赖 Spring AI 默认的通用 `vector_store` 表。建议建立自己的 `knowledge_chunk`：

```text
id
document_id
document_version_id
project_id
branch_id
scope
status
chunk_no
content
token_count
embedding vector(512)
metadata jsonb
created_at
```

关系字段用于严格隔离和普通索引，`metadata` 只保存扩展信息。

## 8. CPU Embedding

### 8.1 首选 `bge-small-zh-v1.5`

该模型面向中文，约 24M 参数、512 维、MIT 许可证，适合 CPU 和当前文档规模。模型卡给出的中文检索指标也高于 `multilingual-e5-small`。[BGE Small Chinese 模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)

不建议 MVP 使用 BGE-M3：它为 1024 维、多语言和长上下文模型，能力更强，但约 0.5B 级别，对仅有 CPU 的单机 MVP 过重。[BGE-M3 模型卡](https://huggingface.co/BAAI/bge-m3)

### 8.2 Java 内嵌 ONNX

Spring AI 的 `TransformersEmbeddingModel` 使用 DJL 和 ONNX Runtime 在 JVM 内计算向量，并允许使用自定义 Hugging Face 模型；模型需要提前导出为 `model.onnx` 和 `tokenizer.json`。[Spring AI ONNX Embedding](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)

部署时不得在线下载模型，应把以下文件作为内网部署资源：

```text
models/bge-small-zh-v1.5/model.onnx
models/bge-small-zh-v1.5/tokenizer.json
models/bge-small-zh-v1.5/model-metadata.json
```

需要实测并确认：

- pooling 与归一化方式和原模型一致；
- 查询是否需要添加中文检索指令前缀；
- 单条查询延迟；
- 批量文档向量化吞吐；
- JVM 内存和直接内存占用；
- 同一文本多次生成向量是否稳定。

### 8.3 备用方案

如果 Java ONNX 兼容或性能不达标，优先考虑独立的 Hugging Face Text Embeddings Inference CPU 容器，而不是自己维护 Python FastAPI。TEI 官方提供 x86_64 和 ARM64 CPU 镜像，并支持 BERT 类模型。[TEI 支持模型与硬件](https://huggingface.co/docs/text-embeddings-inference/supported_models)

因此 Python 仅可能用于开发阶段导出 ONNX 文件，不进入最终运行架构。

## 9. 代码检索

Apache Lucene 是 Java 原生的高性能全文检索库，适合代码路径、标识符和文件内容检索。[Apache Lucene](https://lucene.apache.org/core/)

MVP 索引字段：

```text
project_id       KeywordField
branch_id        KeywordField
snapshot_id      KeywordField
commit           KeywordField
path             KeywordField + TextField
file_name        KeywordField
language         KeywordField
content          TextField + StoredField（限制大小）
```

建议：

- 不使用 Elasticsearch/OpenSearch，避免多一个重型服务；
- 不对代码做向量化；
- 每次新快照建立新的索引 generation；
- 新索引验证成功后再切换活动索引；
- 原始 ZIP 保留，Lucene 索引可以重建；
- 超大文件只索引前后受限内容或跳过；
- 路径、类名和方法名匹配给予更高权重。

## 10. 文件存储调整

### 10.1 不再默认选择 MinIO

MinIO 官方 GitHub 仓库已于 2026-04-25 归档，社区版本改为源码分发，历史预编译版本不再维护；同时采用 AGPLv3，企业内部使用仍需自行确认许可证义务。[MinIO 官方仓库](https://github.com/minio/minio)

因此，不建议为了 MVP 单独引入 MinIO Server。

### 10.2 MVP 存储方式

实现统一接口：

```java
public interface ObjectStorageService {
    StoredObject put(InputStream input, ObjectMetadata metadata);
    InputStream get(String objectKey);
    void delete(String objectKey);
    boolean exists(String objectKey);
}
```

MVP 使用：

```text
LocalFileObjectStorage
└── Docker 持久化卷 /data/objects
```

文件名使用 UUID/object key，并在 PostgreSQL 记录原始文件名、大小、MIME、SHA-256 和业务归属。

后续如果公司已有 S3/OSS，再实现 `S3ObjectStorage`。AWS SDK for Java v2 支持自定义 Endpoint，因此可以接入第三方 S3 兼容服务。[AWS Java SDK S3 Endpoint](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html)

### 10.3 解压文件

- 原始 ZIP 持久化；
- 解压到 `/data/work/{jobId}`；
- 检查路径穿越、符号链接、文件数量、总大小和压缩比；
- 完成文档解析或 Lucene 索引后删除临时目录；
- 定时清理异常退出留下的临时目录。

## 11. 后台任务

MVP 不建议引入消息队列，也不必立即引入完整 Spring Batch。

使用 PostgreSQL 任务表和受控线程池即可：

```text
ImportJob
id / type / status / progress
input_object_key
project_id / branch_id / snapshot_id
started_at / finished_at
error_code / error_message
heartbeat_at
```

任务状态：

```text
PENDING → RUNNING → SUCCEEDED
                  ↘ FAILED
                  ↘ CANCELLED
```

应用启动时将长时间无心跳的 `RUNNING` 标记为 `FAILED` 或重新排队。后续任务规模扩大，再考虑 Spring Batch；其官方能力包括任务状态、重启、跳过和批量处理。[Spring Batch](https://docs.spring.io/spring-batch/reference/spring-batch-intro.html)

## 12. 建议依赖边界

建议使用以下主要依赖，不同时引入功能重叠框架：

```text
spring-boot-starter-web
spring-boot-starter-security
spring-boot-starter-validation
spring-boot-starter-jdbc
spring-ai-starter-model-openai
spring-ai-starter-model-transformers
spring-ai-starter-vector-store-pgvector
spring-ai-starter-mcp-server-webmvc
postgresql
flyway-core
lucene-core
lucene-analysis-common
lucene-queryparser
```

暂不引入：

- LangChain4j；
- Python FastAPI；
- Celery/RabbitMQ/Kafka；
- Elasticsearch/OpenSearch；
- Spring Cloud；
- Kubernetes；
- MinIO Server；
- Spring Batch；
- Apache Tika 全格式解析。

文档格式后续扩展时可以引入 Apache Tika；其官方解析器覆盖 Office、PDF、HTML、压缩包和源代码等格式，但不属于当前 Markdown MVP 的必要依赖。[Apache Tika 支持格式](https://tika.apache.org/3.2.2/formats.html)

## 13. 必须先完成的技术验证

正式写业务功能前，先做六个小验证：

### Spike 1：MiniMax Tool Calling

```text
Java → MiniMax → knowledge_search 工具调用
→ Java 执行假数据检索 → 回传模型 → 结构化答案
```

通过条件：流式和非流式至少一种稳定；工具参数可正确解析；连续调用不会丢失上下文。

### Spike 2：Claude Code MCP

实现一个临时 `knowledge_search`，使用 Token 连接 Claude Code。

通过条件：Claude Code 能列出和调用工具；中文参数与返回内容正常；错误 Token 被拒绝。

### Spike 3：CPU Embedding

用 100～500 篇模拟中文文档比较 Java ONNX 和备用推理服务。

记录：模型启动时间、单次延迟、批量吞吐、内存和 Top-5 召回。

### Spike 4：20 万行代码索引

使用脱敏或模拟的大型 Java/Vue 仓库建立 Lucene 索引。

记录：过滤后文件数、索引时间、索引体积、查询延迟和内存峰值。

### Spike 5：分支隔离

两个分支放入同名但内容不同的文件，确认 Web、内部 Agent 工具和 MCP 都不会跨分支召回。

### Spike 6：快照原子切换

故意让新索引任务失败，确认旧快照仍然可查；成功后再切换活动 snapshot。

## 14. 最终建议

采用以下落地顺序：

```text
Java 21 + Spring Boot 4.1 + PostgreSQL
→ MiniMax Tool Calling 验证
→ Java ONNX Embedding 验证
→ pgvector 文档检索
→ Lucene 代码检索
→ 受控 Agent Runtime 与三个 Skill
→ Web 问答和草稿审核
→ MCP Streamable HTTP
```

最终运行架构应尽量保持：

```text
一个 Java 应用
+ 一个 PostgreSQL
+ 一个文件持久化卷
+ 公司 MiniMax 接口
```

这套组合能覆盖 MVP，又避免引入 Python 服务、消息队列、搜索集群和对象存储服务器带来的额外部署成本。等真实规模和性能数据出现后，再决定是否拆分 Embedding、任务执行器或对象存储。
