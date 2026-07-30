# 项目业务上下文知识库——Java 技术栈调研与 MVP 落地建议

| 属性 | 内容 |
|---|---|
| 调研日期 | 2026-07-29 |
| 最近修订 | 2026-07-30，多 Agent 文档整理决策 |
| 调研目标 | 判断 Java 主技术栈能否覆盖 Agent、RAG、MCP、代码检索和 CPU Embedding，并给出 MVP 可执行组合 |
| 结论 | 使用 Java 单体后端；MVP 运行时不引入 Python，Embedding 优先采用 Java ONNX，必要时再切换独立推理服务 |

## 1. 结论摘要

Java 可以完整覆盖当前 MVP，且比 Python 主后端更符合本项目的业务结构和后续维护要求。

推荐组合：

| 领域 | 推荐方案 |
|---|---|
| Java 运行时 | Java 21 |
| Web 后端 | Spring Boot 3.5.x、Spring MVC |
| AI 集成 | Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.x，使用 BOM 锁定版本 |
| 大模型 | 公司 MiniMax M2.7，通过兼容接口接入 |
| Agent | Spring AI Alibaba Graph 固定工作流 + ReactAgent + PostgreSQL 检查点 |
| Skill 与项目记忆 | 版本化 `SKILL.md` + 每项目一个 `PROJECT_MEMORY.md`，数据库记录版本并由 ObjectStorage 保存正文 |
| MCP | Spring AI MCP WebMVC Starter，Streamable HTTP |
| 业务数据库 | PostgreSQL 15+ |
| 向量 | pgvector 0.8.x，HNSW/Cosine |
| CPU Embedding | 首选 `BAAI/bge-small-zh-v1.5` ONNX，在 Java 进程内运行 |
| 代码检索 | Apache Lucene 10.x |
| 原始文件 | MVP 使用本地持久化卷；通过统一接口保留 S3 兼容实现 |
| 前端 | Vue 3 + TypeScript + Vite |
| 部署 | 单体应用 + PostgreSQL，Docker Compose |

三个重要调整：

1. **不默认部署 MinIO。** MinIO 社区仓库已在 2026 年 4 月归档，社区版改为源码分发，并采用 AGPLv3。MVP 使用本地持久化卷更稳妥；如公司已有 S3 兼容存储，再通过适配器接入。
2. **不默认运行 Python 服务。** Spring AI 已能通过 ONNX 在 JVM 内计算 Embedding。只有在中文模型兼容性或 CPU 性能实测不达标时，才增加独立推理容器。
3. **文档整理采用固定多 Agent Graph。** 需求、代码和测试证据并行提取，随后编写、独立审查并在证据不足时等待人工；Web 项目问答仍使用单 Agent。MVP 不建设动态 Supervisor、分布式 Agent 或可视化工作流编辑器。

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

### 3.2 推荐 Spring Boot 3.5.x + Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.x

Spring AI Alibaba 1.1.2.x 的正式版本线对应 Spring AI 1.1.2 与 Spring Boot 3.5.x，并提供 Agent Framework、Graph、多 Agent 编排、Skill、人工介入、持久化和流式输出。本项目允许从已经建立的 Spring Boot 4.x / Spring AI 2.0.x 工程基线降级，以换取与核心文档整理创新直接匹配的成熟 Java Agent 能力。[Spring AI Alibaba 版本说明](https://java2ai.com/docs/versions/)、[Spring AI Alibaba 概览](https://java2ai.com/docs/overview/)

选择原则：

- 使用正式发布版，不使用 SNAPSHOT 或 Milestone；
- 通过 Spring Boot Parent、Spring AI BOM 和 Spring AI Alibaba BOM 锁定一致版本；
- MVP 开发期间不主动升级大版本；
- 直接使用 Starter，避免手动混入不同补丁版本的 Agent Framework、Graph、模型和 MCP SDK；
- 最终 Spring AI Alibaba 补丁版本由 PoC 在 Maven Central 正式构件中选择并记录依赖树。

降级前必须用隔离 PoC 验证现有测试编译、MiniMax `ChatModel`、三个并行节点、PostgreSQL 检查点跨进程恢复和人工中断继续。PoC 未通过前不得先全面改写业务代码；正式迁移后必须重新运行已完成任务的相关测试。

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

### 5.1 固定多 Agent Graph

Spring AI Alibaba Agent Framework 提供 `ReactAgent`，Graph 提供顺序、并行、条件、循环、持久化和人工中断能力。[多智能体文档](https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent/)、[持久化执行](https://java2ai.com/en/docs/frameworks/graph-core/core/long-time-running-task/)

本项目不使用 LLM Supervisor 自由规划主流程，而由应用定义固定 Graph：

```text
准备输入
→ 并行执行需求证据、代码证据、测试证据 Agent
→ 确定性合并证据台账
→ 文档编写 Agent
→ 独立审查 Agent
→ 通过、最多一次返工或等待人工
→ 待审核草稿
```

固定 Graph 保留多 Agent 的核心价值，同时减少动态路由、无限循环和不可恢复副作用。Web 问答继续使用单个 `project_qa` Agent；普通知识生成复用证据—编写—审查流程；知识体检使用固定 audit—curator—reviewer 预设。

应用仍然负责：

- 角色工具白名单和工具参数；
- 项目、分支、Commit 和知识范围；
- 最大节点、一次返工、超时、检索和上下文限制；
- 草稿、报告和知识缺口的幂等写入；
- 来源引用和未确认项；
- 运行、步骤、公开事件、取消、恢复和人工反馈；
- 禁止 Agent 发布正式知识。

### 5.2 持久状态与恢复

Graph checkpoint 与 LoreDock 业务状态分开：

```text
PostgreSQL Graph Saver
  └─ 节点检查点、下一节点和 Graph 状态

LoreDock 业务表
  └─ 运行、步骤、事件、版本快照、错误和人工请求

ObjectStorage
  └─ 大型证据、草稿版本和审查报告 Markdown
```

Graph `threadId` 使用服务端生成的运行标识，不使用项目名、对话 ID 或用户输入路径。节点完成后提交检查点；后端异常退出后把遗留运行标记为可恢复，由管理员从 Web 手动继续。已完成节点使用持久结果重放，草稿等副作用使用稳定幂等键，避免重复模型调用和重复写入。

如果正式 Spring AI Alibaba BOM 中的 PostgreSQL Saver 无法关闭自动建表或不能通过跨实例恢复测试，则不使用 SNAPSHOT，而是在基础设施层实现 Saver 端口并继续由 Flyway 管理表结构。

### 5.3 Skill 与项目记忆

Spring AI Alibaba 支持以 `SKILL.md`、可选 references/examples/scripts 组织可复用 Skill，并支持自定义 `SkillRegistry`。[Skills 文档](https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/)

LoreDock 使用以下分层：

```text
不可覆盖的系统安全规则
+ 全局版本化 SKILL.md
+ 项目 PROJECT_MEMORY.md
+ 本次任务输入与人工反馈
```

每个项目在 MVP 只维护一个 `PROJECT_MEMORY.md`，记录项目目标、术语、模块边界、历史约束和文档提取方向。全局 Skill 和项目记忆正文使用 Markdown，数据库保存版本、状态、哈希、修改人与 ObjectStorage object key。运行开始后固定实际使用版本；中途更新只影响新运行。

系统实现自定义 `SkillRegistry` 从应用端口读取已发布版本，不给 Agent 任意文件系统、Shell 或网络访问。工具权限、项目范围、运行限制和发布权限不从 Markdown 推导，也不能被项目记忆或人工反馈放宽。

### 5.4 运行可观测与人在回路

证据不足或独立审查阻断时，Graph 通过检查点暂停。人工可以补充当前运行方向、允许保留待核实项、要求重写或取消；反馈绑定运行、等待节点和目标产物版本。将反馈保存为项目记忆是独立二次确认，新记忆版本不改变当前运行快照。[Human-in-the-loop](https://java2ai.com/en/docs/frameworks/agent-framework/advanced/human-in-the-loop/)

前端首次通过 REST 获取运行聚合快照，通过 SSE 接收阶段性事件。只保存和展示角色状态、工具与来源摘要、中间产物、审查问题、Token、耗时和错误，不保存或展示模型原始思维链。

### 5.5 Skill 文件示例

```text
skills/
├── project-qa/SKILL.md
├── requirement-evidence/SKILL.md
├── code-evidence/SKILL.md
├── test-evidence/SKILL.md
├── document-writer/SKILL.md
└── document-reviewer/SKILL.md
```

Skill Markdown 声明角色、输入、步骤、输出结构、引用和拒答要求；工具白名单、最大步骤和写入权限使用受控结构化配置保存并由后端强制校验。运行记录保存 Graph、Skill、项目记忆、模型和输出结构版本，以及步骤、工具摘要、引用、Token 和最终状态。

## 6. MCP 实现

Spring AI 1.1.x 提供 WebMVC Streamable HTTP Starter，并可通过注解生成工具 Schema。MCP 依赖必须与 Spring AI Alibaba 选定的 Spring AI 1.1.2 版本保持一致。[Spring AI Streamable HTTP MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)、[MCP Server Annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)

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

### Spike 7：多 Agent、人工中断与恢复

使用 Fake Model 和脱敏材料验证需求、代码和测试证据节点真实并行，文档编写结果由上下文隔离的 reviewer 审查；在节点完成后停止并重新从 IDEA 启动后端，使用相同运行标识从 PostgreSQL 检查点继续；人工反馈能够恢复等待中的 Graph，已完成节点和草稿写入不会重复。

通过条件：Spring Boot 3.5.x、Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.x 正式构件兼容；并行、检查点、人工中断和恢复全部通过；工具范围、项目隔离和禁止发布仍由后端强制执行。

## 14. 最终建议

采用以下落地顺序：

```text
Java 21 + Spring Boot 3.5 + PostgreSQL
→ Spring AI Alibaba 多 Agent、检查点与人工中断验证
→ MiniMax ChatModel 与 Tool Calling 验证
→ Java ONNX Embedding 验证
→ pgvector 文档检索
→ Lucene 代码检索
→ 可恢复 Agent Runtime、版本化 Skill 与项目记忆
→ 单 Agent Web 问答
→ 多 Agent 证据提取、编写、独立审查与人工审核
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
