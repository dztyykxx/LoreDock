# T4 代码快照与 Lucene 检索

本文记录 MVP 开发计划 T4 已实现的代码快照、全文索引与恢复边界。可验收行为以 OpenSpec 的 `code-snapshot-management` 和 `code-snapshot-search` capability 为准。

## 1. 数据与依赖方向

`code` 能力内部遵循“基础设施 → 应用 → 领域”。Web、后台任务、PostgreSQL、对象存储、ZIP 和 Lucene 适配器都复用应用端口；领域值对象不依赖 Spring、MyBatis-Plus、文件系统或 HTTP。

V4 Flyway 迁移增加 `code_snapshot`、`code_index_generation`，并为 `background_job` 增加项目、分支和快照范围。数据库约束保证同一分支最多一个 `ACTIVE` 快照、同一快照最多一个 `ACTIVE` generation；持久化实体、领域记录和 HTTP DTO 相互分离。

## 2. 导入与文件选择

管理员上传请求先按实际读取字节限制为 100 MiB，并同时校验 `.zip` 扩展名、允许 MIME 和 ZIP 魔数。原始 ZIP 使用对象存储的不透明 UUID 键保存；客户端文件名、ZIP 路径和数据库值都不能决定服务器物理路径。

后台构建先校验完整中央目录，再按中央目录顺序打开普通文件流。路径穿越、反斜杠、绝对/盘符路径、NUL、重复规范路径、符号链接、特殊 Unix 类型、加密、分卷、未知展开大小、压缩炸弹或损坏归档会使候选整体失败。正文选择一次最多保留当前单文件上限，并直接写入 Lucene writer，不把整个仓库正文聚合到内存，也不按条目名解压到磁盘。

默认排除版本控制、依赖、构建、缓存目录，以及环境文件、证书、私钥和明显密钥名。超过 2 MiB、包含 NUL、明显二进制或非法 UTF-8 的文件整项忽略，不截断为伪完整文件。片段读取只访问已发布 Lucene `StoredField`，不能回读原始 ZIP 绕过选择规则。

## 3. generation 发布与切换

每次构建或重建使用服务端 UUID 创建独立 generation：

1. 写入索引根直属的 `<uuid>.building`；
2. 关闭 writer，并重新打开验证文档数、唯一路径和项目/分支/快照/Commit/generation 身份；
3. 在同一索引根内原子移动为 `<uuid>`；
4. 在 PostgreSQL 短事务中激活新 generation；新快照成功时同时退休旧快照；
5. 旧 reader 在现有请求释放最后引用后关闭并清理退休目录。

写入、验证、移动或数据库激活失败时，新候选终结为失败，旧活动快照继续服务。应用启动先恢复陈旧后台任务，再把关联的遗留候选和 `BUILDING` generation 标记失败，并清理 `.building` 与数据库未引用孤儿；未知目录名不会被删除。

## 4. 查询隔离

普通查询先由 PostgreSQL 固定项目、显式或默认分支、`ACTIVE` 快照和 `ACTIVE` generation。Lucene 查询再以 project、branch、snapshot、generation 四个精确 `FILTER` 约束同一描述符，不会在召回后通过展示层隐藏跨范围结果。

搜索支持 `ALL`、`PATH`、`CONTENT`，可选规范路径前缀，按相关性后接路径稳定排序。响应带活动 Commit 和有限纯文本片段，不暴露对象键、generation UUID 或物理目录。片段接口以规范路径和有限行范围读取；未索引路径统一返回 404，起始行越界返回 416。分支没有活动快照时明确返回未索引语义，不回退其他分支、历史快照或候选。

## 5. 配置与容量

| 环境变量 | 默认值 | 约束 |
|---|---:|---|
| `LOREDOCK_CODE_SNAPSHOT_MAX_UPLOAD_SIZE` | `100MB` | 不得超过 100 MiB |
| `LOREDOCK_CODE_SNAPSHOT_MAX_ARCHIVE_ENTRIES` | `50000` | 中央目录条目上限 |
| `LOREDOCK_CODE_SNAPSHOT_MAX_ARCHIVE_ENTRY_UNCOMPRESSED_SIZE` | `100MB` | 单条目结构性展开上限 |
| `LOREDOCK_CODE_SNAPSHOT_MAX_INDEXED_FILE_SIZE` | `2MB` | 完整文本索引上限 |
| `LOREDOCK_CODE_SNAPSHOT_MAX_ARCHIVE_UNCOMPRESSED_SIZE` | `1GB` | 声明累计展开上限 |
| `LOREDOCK_CODE_SNAPSHOT_MAX_COMPRESSION_RATIO` | `100` | 单条目展开/压缩比 |
| `LOREDOCK_CODE_SNAPSHOT_MAX_SEARCH_SNIPPET_CHARS` | `2000` | 单个搜索片段字符上限 |
| `LOREDOCK_CODE_SNAPSHOT_WORK_ROOT` | `./data/work/code` | 服务生成的临时任务目录 |
| `LOREDOCK_CODE_SNAPSHOT_INDEX_ROOT` | `./data/indexes/code` | Lucene generation 根目录 |

工作根和索引根必须互不包含，否则应用拒绝启动。反向代理和 Spring multipart 请求上限必须至少容纳 100 MiB ZIP 与 multipart 边界，但业务层 100 MiB 计数字节限制仍是最终硬上限。

20 万行确定性 Java 模拟仓库的验收会记录索引、内容查询和路径查询耗时；门禁要求两类查询各小于 3 秒，并验证错误项目过滤无召回。该结果是本机验收基线，不是对不同磁盘、CPU 或文件分布的容量承诺。

## 6. 备份与故障恢复

数据库与对象根是不可缺少的持久证据，必须在同一静默写入窗口成对备份。索引根是派生数据：若要求恢复后立即查询，应与数据库和对象根在同一窗口一并复制；若不备份，恢复数据库和对象后可通过管理员重建接口为活动快照生成新 generation。工作根只保存可清理临时文件，不进入备份。

查询返回 503 时，先检查磁盘空间、索引根权限和数据库所指活动 generation 目录是否存在。不得手工移动 `.building`、伪造 UUID 目录或修改数据库状态；应保留旧活动目录，解除文件占用或权限问题后重启恢复清理，必要时从仍存在的原始 ZIP 发起重建。
