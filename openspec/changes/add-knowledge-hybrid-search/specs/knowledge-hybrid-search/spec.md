## Purpose

为 Web、内部 Agent 与后续 MCP 提供同一套可引用的业务知识检索契约，在中文关键词和相似表达召回之间取得稳定效果，并在候选生成阶段严格执行发布状态、项目、分支与知识范围隔离。

## ADDED Requirements

### Requirement: 知识搜索必须使用明确且受服务端解析的查询范围
系统 SHALL 允许已登录的 `ADMIN` 与 `MEMBER` 通过 `GET /api/knowledge-search` 搜索正式知识。请求 MUST 包含去除首尾空白后 1～500 字符的 `query` 和 `context=GLOBAL|PROJECT`；`PROJECT` 上下文 MUST 提交项目标识，可选分支省略时 MUST 使用该项目的 `main`，`GLOBAL` 上下文 MUST 拒绝项目或分支参数。

全局查询 MUST 只允许当前仍为 `PUBLISHED` 的 `GLOBAL` 文档；项目查询 MUST 只允许当前仍为 `PUBLISHED` 的 `GLOBAL` 文档、当前项目的 `PROJECT` 文档及当前项目当前分支的 `BRANCH` 文档。系统 MUST 在关键词和向量候选查询中直接应用该范围，不得先跨项目或跨分支召回再在响应层隐藏。

#### Scenario: 全局查询只返回通用知识
- **WHEN** 已登录用户以 `context=GLOBAL` 搜索一个同时存在于通用、项目和分支文档中的词语
- **THEN** 系统只返回当前已发布的通用文档，不返回任何项目级或分支级候选、计数或元数据

#### Scenario: 项目查询包含三层允许知识
- **WHEN** 已登录用户选择已启用项目及存在的分支执行搜索
- **THEN** 系统只从通用知识、当前项目级知识和当前项目当前分支知识中生成候选并返回结果

#### Scenario: 项目查询省略分支
- **WHEN** 已登录用户以 `context=PROJECT` 提交有效项目但省略分支
- **THEN** 系统使用该项目的 `main` 分支完成整次查询，并在响应范围中明确返回 `main`

#### Scenario: 同项目其他分支存在更高相似结果
- **WHEN** 当前分支与同项目其他分支都存在相似文档且其他分支的匹配分数更高
- **THEN** 关键词候选、向量候选和最终结果均不得包含其他分支文档

#### Scenario: 查询上下文无效
- **WHEN** 查询为空或过长、全局上下文携带项目或分支、项目上下文缺少项目、项目不存在或停用、或指定分支不存在
- **THEN** 系统分别返回字段级 HTTP 400、`PROJECT_NOT_FOUND` 或 `BRANCH_NOT_FOUND`，且不执行无界或回退查询

#### Scenario: 分支没有活动代码快照
- **WHEN** 项目与分支存在但没有活动代码快照，且允许范围内存在已发布人工知识
- **THEN** 知识搜索正常返回这些人工知识，同时在项目查询响应中包含 `CODE_SNAPSHOT_NOT_INDEXED` 警告，不使用其他分支代码或知识补齐结果

### Requirement: 系统必须提供关键词、语义和混合三种受控检索模式
请求 SHALL 支持 `mode=KEYWORD|SEMANTIC|HYBRID`，默认值 MUST 为 `HYBRID`。`KEYWORD` MUST 查询标题、正文和标签；`SEMANTIC` MUST 使用与活动检索 generation 匹配的中文 Embedding 查询向量召回相似表达；`HYBRID` MUST 分别生成受范围及过滤条件约束的关键词和语义候选，再通过服务端固定、可重复的融合规则排序。

请求 SHALL 支持 `limit`（默认 10、最小 1、最大 50）、最多 10 个 `tag`、可选 `format=MARKDOWN|PLAIN_TEXT` 和可选 `sourceType=MANUAL|WIKI|UPLOAD`。多个标签 MUST 按“结果包含全部请求标签”解释，所有过滤条件 MUST 同时进入两路候选查询。客户端不得提交原始 SQL、全文检索表达式、向量、融合权重、候选数量或 generation 标识。

#### Scenario: 关键词命中标题正文和标签
- **WHEN** 用户以 `KEYWORD` 分别查询只出现在标题、正文或标签中的中文关键词
- **THEN** 系统在允许范围内召回对应文档，并使标题或标签的同等命中优先于仅正文命中

#### Scenario: 语义模式召回相似表达
- **WHEN** 用户以 `SEMANTIC` 查询基准集中未与正确文档共享主要字面词语的中文口语或相似表达
- **THEN** 系统使用活动 generation 对应模型生成查询向量，并在允许范围内返回语义相近的正确来源候选

#### Scenario: 混合模式融合两路候选
- **WHEN** 同一文档只被关键词或语义一路命中，另一个文档被两路命中
- **THEN** 系统保留单路有效候选，并按固定融合规则给予两路共同命中文档额外排序优势，结果在相同数据与配置下可重复

#### Scenario: 应用标签与类型过滤
- **WHEN** 用户同时提交多个标签、文档格式和来源类型过滤条件
- **THEN** 关键词与语义候选都只包含同时满足全部条件的文档，不在融合后才隐藏不匹配结果

#### Scenario: 查询参数试图控制内部检索
- **WHEN** 请求包含向量、SQL、原始查询语法、融合权重、候选数量或 generation 等非契约字段
- **THEN** 系统拒绝或忽略这些字段，仍由服务端控制查询解释、范围、候选上限和活动 generation

#### Scenario: 检索模式参数无效
- **WHEN** `mode`、`limit`、标签数量、格式或来源类型不符合公布限制
- **THEN** 系统返回 HTTP 400 和字段级 `INVALID_REQUEST`，不执行部分检索

### Requirement: 搜索结果必须可引用、有限且稳定
响应 SHALL 返回解析后的上下文、实际分支、检索模式、活动 generation 标识、警告和结果列表。每条结果 MUST 包含稳定文档 ID、当前范围、标题、不超过 500 个 Unicode 字符的文本片段、来源类型及可公开来源字段、文档格式、标签、来源更新时间、归一化相关性、`matchedBy=KEYWORD|SEMANTIC|BOTH` 和 `truncated`；不得返回完整正文、服务器绝对路径、对象键、Embedding 向量或内部索引配置。

系统 MUST 将同一文档的多个分块命中折叠为一条文档结果，并选择与查询最相关的有限片段。结果 MUST 按相关性降序稳定排序；相关性相同则按来源更新时间降序、文档 ID 升序排序。没有匹配文档时 MUST 返回 HTTP 200 与空列表，不得扩大范围或使用模型常识生成内容。

#### Scenario: 多个分块命中同一文档
- **WHEN** 同一文档的标题和多个正文分块均命中查询
- **THEN** 响应只包含一条该文档结果，并返回相关性最高分块形成的有限片段和正确来源元数据

#### Scenario: 命中片段超过上限
- **WHEN** 最相关正文上下文超过片段上限
- **THEN** 系统在 Unicode 字符边界安全截断片段、设置 `truncated=true`，且不返回相邻文档内容

#### Scenario: 相同输入产生稳定排序
- **WHEN** 在同一活动 generation、模型和配置下重复执行相同查询
- **THEN** 系统返回相同的文档顺序、匹配类型和可用于引用的元数据

#### Scenario: 查询没有命中
- **WHEN** 允许范围内没有满足查询和过滤条件的文档
- **THEN** 系统返回 HTTP 200 与空结果，不搜索其他项目、其他分支、草稿、归档或代码快照

### Requirement: 正式检索必须对活动投影执行实时资格复核
关键词、语义和混合搜索 SHALL 只读取一个已完整激活的知识检索 generation，并在返回前根据知识文档事实表复核 `PUBLISHED` 状态、当前项目/分支范围及文档 ID。搜索开始时解析出的 generation MUST 固定到请求结束；并发激活新 generation 时，一次响应不得混合两个 generation 的分块、分数或模型元数据。

#### Scenario: 已归档文档仍存在于活动 generation
- **WHEN** 文档在上次重建后被归档但旧活动 generation 仍含其分块和向量
- **THEN** 任何检索模式都立即排除该文档，不等待下一次重建

#### Scenario: 已发布文档修改范围但尚未重建
- **WHEN** 文档从当前项目或分支移到其他范围且活动 generation 仍保存旧范围
- **THEN** 原范围查询不得返回该文档，新范围查询仅在成功重建后才可从新范围召回它

#### Scenario: 查询期间并发切换 generation
- **WHEN** 搜索开始后管理员成功激活新的知识检索 generation
- **THEN** 当前响应完整使用搜索开始时解析的旧 generation，下一次请求才使用新 generation

#### Scenario: 草稿或归档内容相似度最高
- **WHEN** 草稿或归档文档与查询的关键词或语义相似度高于所有已发布文档
- **THEN** 候选查询和最终结果都不包含这些无资格文档，也不泄露其标题、分数或存在性

### Requirement: 知识重建必须原子产出可查询的关键词与向量 generation
现有知识重建任务 SHALL 对任务开始时快照到的有效已发布文档进行确定性分块，生成标题/标签/正文关键词数据和中文 Embedding，并记录模型标识、模型资源校验值、向量维度、分块策略版本、文档数和分块数。只有来源投影、全部分块、关键词数据、向量维度及数量校验均成功后，系统才可原子激活该 generation。

模型资源 MUST 从部署方配置的本地或类路径资源读取；正式运行不得在请求或后台重建期间从公网下载模型。BGE 模型产物 MUST 提供与锁定官方参考一致、已经完成 CLS pooling 与 L2 归一化的句向量输出，适配器 MUST 直接消费该输出，不得用未经规格确认的 mean pooling、临时后处理或在线服务替代。任一文档分块、Embedding、持久化、校验或激活失败 MUST 使任务进入 `FAILED` 并保存脱敏错误语义，上一个成功活动 generation MUST 继续可查询，未完成 generation MUST NOT 成为任何搜索入口的候选来源。

#### Scenario: 首次完整重建成功
- **WHEN** 管理员提交知识重建且模型资源、已发布文档和 PostgreSQL 均可用
- **THEN** 任务生成完整关键词与向量数据、校验模型和数量后原子激活新 generation，后续三种模式均可查询它

#### Scenario: Embedding 处理中途失败
- **WHEN** 新 generation 已写入部分分块后 Embedding 计算失败
- **THEN** 重建任务标记为 `FAILED`，部分 generation 不可查询，上一个活动 generation 和搜索结果保持可用

#### Scenario: 模型资源缺失或校验值不匹配
- **WHEN** 重建所需离线模型文件不存在、不可读或与配置的校验值不一致
- **THEN** 系统拒绝开始语义索引构建或使任务明确失败，不在线下载替代模型且不激活缺少向量的数据

#### Scenario: 重建期间文档被归档
- **WHEN** 文档被重建快照包含但在新 generation 激活前已归档
- **THEN** generation 可以完成原子激活，但实时资格复核保证该文档从未通过正式搜索返回

#### Scenario: 重复提交知识重建
- **WHEN** 已有 `PENDING` 或 `RUNNING` 的知识重建任务时管理员再次提交
- **THEN** 系统沿用现有 single-flight 契约返回同一任务，不并行计算重复 Embedding 或创建可竞争激活的 generation

### Requirement: 检索基础设施不可用时必须提供明确且安全的失败语义
如果没有完整活动检索 generation，系统 SHALL 返回 HTTP 503 和 `KNOWLEDGE_INDEX_UNAVAILABLE`。如果 `SEMANTIC` 或 `HYBRID` 无法使用与活动 generation 匹配的查询 Embedding 模型，系统 SHALL 返回 HTTP 503 和 `KNOWLEDGE_EMBEDDING_UNAVAILABLE`，不得静默退化为关键词模式；`KEYWORD` 模式在关键词数据可用时 SHALL 不依赖查询 Embedding 模型。

所有失败响应 MUST 遵守统一错误契约并隐藏数据库连接、模型文件绝对路径、对象键、向量和正文。关键日志 MUST 记录 traceId、上下文类型、项目/分支稳定标识、模式、过滤数量、generation、候选数、返回数、耗时、结果或错误码，不得记录原始查询或知识正文；查询仅记录长度和脱敏摘要。

#### Scenario: 尚未建立活动检索 generation
- **WHEN** 用户在首次成功知识重建前执行任一模式搜索
- **THEN** 系统返回 HTTP 503 和 `KNOWLEDGE_INDEX_UNAVAILABLE`，不把“尚未索引”伪装为空结果

#### Scenario: 混合查询的 Embedding 模型不可用
- **WHEN** 活动 generation 存在但查询模型不可加载或与 generation 校验值不匹配
- **THEN** `HYBRID` 返回 HTTP 503 和 `KNOWLEDGE_EMBEDDING_UNAVAILABLE`，不返回仅关键词候选冒充混合结果

#### Scenario: 关键词模式不依赖模型运行时
- **WHEN** 活动 generation 的关键词数据可用但查询 Embedding 模型临时不可用
- **THEN** `KEYWORD` 仍正常返回受范围约束的结果

#### Scenario: 内部查询异常
- **WHEN** PostgreSQL、索引读取或结果复核发生非预期异常
- **THEN** 系统返回脱敏的 `KNOWLEDGE_INDEX_UNAVAILABLE`，保留带业务上下文的错误日志且不回退到其他范围或 generation

### Requirement: 代表性知识搜索必须满足 MVP 响应目标
在项目记录的 CPU、JVM、PostgreSQL、文档量、分块量、模型和预热条件下，普通 `KEYWORD`、`SEMANTIC` 与 `HYBRID` 知识搜索 SHALL 以服务端总耗时不超过 3 秒为 MVP 目标。每次查询 MUST 使用有上限的候选集合、分块数、返回数和 Embedding 输入长度，禁止因短查询、无结果或过滤条件而执行无界扫描或返回无限正文。

#### Scenario: 代表性数据集执行三种模式
- **WHEN** 在记录的目标环境和固定活动 generation 上预热后执行基准集的代表性关键词、语义和混合查询
- **THEN** 报告记录每次服务端耗时、候选数和结果数，并标记任何超过 3 秒的查询为未通过

#### Scenario: 请求返回数量超限
- **WHEN** 客户端请求超过 50 条结果或尝试扩大内部候选上限
- **THEN** 系统在查询前返回 HTTP 400 或忽略非契约字段，不执行无界检索
