## 1. 契约与持久化基础

- [x] 1.1 先定义代码快照上传、重建、管理查询、活动状态、搜索和片段读取的应用端口、命令/响应模型与 HTTP 契约，登记规格中的稳定错误码和 `CodeSnapshotProperties` 配置边界；公共接口使用中文 Javadoc 明确权限、默认 `main`、幂等性、长度上限和失败语义，此步不创建 Controller、Mapper 或 Lucene 实现。
- [x] 1.2 为 V4 迁移和显式映射编写带中文业务目的注释的失败 PostgreSQL 集成测试，覆盖从 V1～V3 升级、空库/重复迁移、commit/状态/计数检查、项目/分支/对象/快照/任务外键、每分支唯一活动快照、每快照唯一活动 generation，以及 MyBatis-Plus 实体逐字段映射；追加迁移和最小仓储映射使测试通过，不修改历史迁移、不自动建表、不使用 H2 或 XML Mapper。
- [x] 1.3 为后台任务项目/分支/快照范围、同分支构建与重建排他、调用方事务回滚不调度、提交后才执行及既有知识任务不回归编写失败单元/真实 PostgreSQL 测试；扩展任务端口、领域快照和仓储并实现 `afterCommit` 调度与命名约束冲突映射，使测试通过并用中文注释说明排他和事务顺序的原因。

## 2. ZIP 输入与文件选择安全

- [x] 2.1 为 commit 值对象、仓库相对路径、外层 ZIP 扩展名/MIME/魔数、100 MiB 计数字节上限和非法配置拒绝就绪编写带中文业务目的注释的失败测试；实现最小规范化、类型识别与流式限额逻辑，使 400/413/415 语义稳定且不依赖原始文件名生成服务器路径。
- [x] 2.2 使用程序化模拟 fixture 为 ZIP 中央目录安全校验编写失败测试，覆盖绝对/盘符/反斜杠/空段/`.`/`..`/NUL、规范化重复、符号链接/特殊 Unix 条目、损坏/加密/分卷、条目数量、声明展开量和压缩比上限；实现只复制到服务生成临时 ZIP、先校验全包再读取条目的 Commons Compress 适配器，使任一结构风险都让候选任务失败并在 `finally` 清理临时输入。
- [x] 2.3 为代码文件选择和受限解码编写失败测试，覆盖 `.git`、依赖/构建/缓存目录、`.env` 变体、证书/私钥/明显密钥名、超大文件、NUL、二进制和非法 UTF-8，以及允许源码与排除文件混合时的稳定忽略计数；实现逐条目流式选择且最多保留单文件上限的文本，不截断伪装完整文件，并为“被忽略文件也不得从原 ZIP 旁路读取”补充中文原因注释。

## 3. 上传事务与管理接口

- [x] 3.1 为上传应用用例编写失败集成测试，覆盖已启用项目所属分支成功受理、停用项目、项目/分支不匹配、非法 commit、同分支活动任务冲突、对象成功但快照/任务事务失败的幂等补偿，以及补偿失败只留下不可查询对象；实现对象持久化、`CANDIDATE` 快照与 `PENDING` 任务同事务登记，确保 202 只表示受理且候选绝不进入普通查询。
- [x] 3.2 为 `POST /api/admin/code-snapshots`、管理分页列表和 `GET /api/admin/code-snapshot-jobs/{jobId}` 编写失败 Web 契约测试，覆盖完整请求/响应、202、400/401/403/404/409/413/415、非代码任务 ID 404、稳定分页、任务进度/失败摘要和成员请求不持久化正文；实现只调用应用端口的 Controller 与映射，确认响应和日志不含代码正文、object key、服务器路径或内部异常。

## 4. Lucene generation 与生命周期

- [x] 4.1 为代码 analyzer 与 Lucene 文档契约编写真实临时目录失败测试，覆盖路径/文件名/内容拆词、原 token 保留、身份字段精确值、StoredField、line count、唯一规范化路径和文件计数；引入锁定版本的 `lucene-core`、`lucene-analysis-common`、`lucene-highlighter` 最小模块并实现 generation writer，使索引可关闭后重新打开验证且不接受客户端原始 QueryParser 语法。
- [x] 4.2 为 generation 文件系统发布编写故障注入测试，覆盖 `.building` 目录、关闭重开校验、元数据/文档数不一致、最终 UUID 目录原子重命名、发布前候选不可读取，以及写入/验证/移动失败的临时目录清理；实现安全 generation 路径解析与发布端口，并为数据库只能指向已发布目录的规则补充中文 Javadoc 和原因注释。
- [x] 4.3 为首次激活和新 commit 替换编写 PostgreSQL + Lucene 集成测试，覆盖同一事务退休旧 snapshot/generation、激活候选、commit/索引时间一致、数据库提交失败保留旧活动入口、任务失败标记候选 `FAILED` 和进度/心跳；实现 `CODE_SNAPSHOT_BUILD` 处理器与短激活事务，使目录、活动元数据和查询 generation 不出现新旧混配。
- [x] 4.4 为活动快照重建编写失败集成/Web 测试，覆盖只有当前活动快照可提交、原始对象缺失、同分支任务冲突、202 状态查询、同 commit 新 generation 成功替换，以及重建失败仍保留原活动 snapshot/generation；实现重建用例、`CODE_SNAPSHOT_REINDEX` 处理器和 `POST /api/admin/code-snapshots/{snapshotId}/reindex`，不提供历史快照重建或查询入口。
- [x] 4.5 为进程中断与索引回收编写失败测试，覆盖陈旧运行任务终结后候选/generation 不激活、活动索引不删除、`.building` 与无引用孤儿清理、退休 reader 有引用时延迟删除及清理失败不影响查询；实现启动协调恢复、引用计数 `LuceneIndexHandleRegistry` 和幂等清理，并检查日志只记录 UUID、commit、计数和脱敏错误类别。

## 5. 活动范围、搜索与片段读取

- [x] 5.1 为活动快照解析与普通状态查询编写失败 PostgreSQL/Web 测试，覆盖项目停用普通 404、分支省略默认 `main`、未知分支不回退、无成功快照返回 `NOT_INDEXED`、`INITIAL|CHANGED|UNCHANGED` 提示、候选不可见及响应不含物理位置；实现复用现有项目端口的 `ActiveCodeSnapshotResolver` 与 `/api/projects/{identifier}/code-snapshot`，一次解析固定 snapshot/generation/commit/indexedAt。
- [x] 5.2 以两个项目、同名分支和同名文件为夹具，为搜索应用与 Web 契约编写失败测试，覆盖 `ALL|PATH|CONTENT`、`pathPrefix`、特殊字符、路径权重、score/path 稳定排序、有限纯文本片段、空结果、无快照、候选不可见、并发切换单请求单 commit 和索引不可用不回退；实现服务端构造查询、数据库范围加 Lucene FILTER 的双层隔离及 `/api/projects/{identifier}/code-search`，补充防止跨范围召回的中文原因注释。
- [x] 5.3 为片段读取应用与 Web 契约编写失败测试，覆盖精确活动路径、默认/最大行数、越过文件末尾、起始行越界 416、路径参数非法、敏感/已忽略/跨分支/不存在路径统一 404、响应截断和并发切换单 commit；实现只读取活动 Lucene StoredField 的 `/api/projects/{identifier}/code-snippets`，确认任何调用方都不能指定历史 snapshot、generation、原始对象或服务器目录。

## 6. 验收、文档与计划同步

- [x] 6.1 生成但不提交大型产物的确定性 20 万行级 Java/Vue 模拟仓库，验证索引可完成、代表性路径/内容查询在目标环境 3 秒内、不同项目/分支结果严格隔离且敏感文件不可读取；运行后端单元/集成/Web 全套测试、Flyway 重复迁移、依赖树/许可证/安全检查和敏感信息扫描，记录测量结果与任何未执行验证。
- [x] 6.2 对照两份 delta spec 逐条复核正常、边界和失败场景，检查每个测试方法的中文业务注释以及公共端口、实现类、事务、ZIP 安全、双层范围过滤和失败回退的中文注释；同步后端配置/运行、备份恢复与故障排查说明、OpenSpec 复选项和 `docs/product/LoreDock_MVP功能开发计划.md`，只有全部门禁满足后才把 T4 标记完成。
