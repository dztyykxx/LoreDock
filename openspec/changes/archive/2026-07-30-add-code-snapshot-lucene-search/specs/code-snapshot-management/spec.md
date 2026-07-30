## Purpose

为 LoreDock 提供与项目、分支和 commit 严格关联的代码快照导入与活动版本管理能力，使代码索引可以安全重建、原子替换，并在新任务失败时继续提供上一份已验证快照。

## ADDED Requirements

### Requirement: 管理员可以提交带明确版本范围的代码快照
系统 SHALL 通过 `POST /api/admin/code-snapshots` 接收 multipart ZIP、`projectId`、`branchId` 和 `commit`，为已启用项目中确实属于该项目的分支创建候选快照，并返回 HTTP 202、快照 ID 与后台索引任务状态。`commit` SHALL 为去除首尾空白后的 7～64 位十六进制 Git 对象标识；系统 SHALL 将它作为管理员声明的版本元数据保存，但 MUST NOT 在未接入 Git 平台时声称已远程验证该 commit。

该操作为非幂等上传。同一项目分支已有 `PENDING` 或 `RUNNING` 的快照构建或重建任务时，系统 MUST 拒绝新的提交，而不是合并不同文件或允许完成顺序决定活动版本。ZIP 原始对象与快照元数据 SHALL 分离保存，以便删除索引后从原始输入重建。

#### Scenario: 为项目分支提交有效快照
- **WHEN** 管理员为已启用项目的所属分支上传合法 ZIP 和合法 commit，且该分支没有活动中的代码索引任务
- **THEN** 系统返回 HTTP 202，持久化候选快照和原始对象引用，并返回可轮询的 `PENDING` 或 `RUNNING` 任务，但在索引验证成功前不把候选快照暴露为活动快照

#### Scenario: 项目与分支不匹配
- **WHEN** 管理员提交的 `branchId` 属于另一个项目
- **THEN** 系统返回 HTTP 404 和 `BRANCH_NOT_FOUND`，不创建快照、任务或可查询对象引用

#### Scenario: 为停用项目提交快照
- **WHEN** 管理员尝试为状态为 `DISABLED` 的项目提交代码快照
- **THEN** 系统返回 HTTP 409 和 `PROJECT_DISABLED`，不创建候选快照或后台任务

#### Scenario: commit 格式无效
- **WHEN** 管理员省略 commit，或提交非十六进制、少于 7 位或超过 64 位的值
- **THEN** 系统返回 HTTP 400 字段校验错误，不把该值保存为版本事实

#### Scenario: 同一分支已有活动任务
- **WHEN** 管理员在同一项目分支的快照构建或重建仍为 `PENDING` 或 `RUNNING` 时再次上传
- **THEN** 系统返回 HTTP 409 和 `CODE_SNAPSHOT_JOB_ACTIVE`，已有任务与输入不被替换

#### Scenario: 成员尝试上传快照
- **WHEN** `MEMBER` 调用代码快照上传接口
- **THEN** 系统返回 HTTP 403，且不读取或持久化上传内容

### Requirement: ZIP 导入必须在读取代码前完成结构与资源安全校验
系统 MUST 对上传大小、ZIP 签名与中央目录、条目数量、单条目展开大小、累计展开大小和压缩比应用服务端配置的正数上限。系统 MUST 拒绝损坏、加密、分卷、规范化路径重复、绝对路径、盘符路径、包含 NUL 或 `..` 逃逸段的路径，以及符号链接或其他非普通文件条目；MUST NOT 按条目名在工作目录之外创建文件或跟随链接。任一结构性安全校验失败 SHALL 使整个候选快照失败，不得建立部分活动索引。

#### Scenario: ZIP 包含路径穿越条目
- **WHEN** 上传 ZIP 中任一条目规范化后会逃离服务端生成的工作目录
- **THEN** 索引任务失败并记录脱敏的 `CODE_SNAPSHOT_ARCHIVE_INVALID` 原因，不读取该条目、不激活候选快照，已有活动快照保持不变

#### Scenario: ZIP 包含符号链接或特殊文件
- **WHEN** 中央目录声明符号链接、设备文件或其他非目录且非普通文件条目
- **THEN** 系统拒绝整个候选快照，不跟随或索引该条目，已有活动快照保持不变

#### Scenario: ZIP 超出资源配额
- **WHEN** 上传体积、条目数量、单项展开量、累计展开量或压缩比超过配置上限
- **THEN** 系统以 `CODE_SNAPSHOT_TOO_LARGE` 或 `CODE_SNAPSHOT_ARCHIVE_INVALID` 结束提交或任务，清理临时资源且不切换活动快照

#### Scenario: ZIP 损坏或加密
- **WHEN** 上传内容不是可完整读取的普通 ZIP，或者条目使用系统不支持的加密或分卷形式
- **THEN** 系统返回或记录 `CODE_SNAPSHOT_ARCHIVE_INVALID`，不将可读的局部条目当作成功快照

### Requirement: 代码文件选择必须默认排除不安全或无检索价值的内容
系统 SHALL 在索引前按规范化仓库相对路径排除依赖目录、版本控制目录、构建产物、缓存、二进制文件、`.env` 文件、证书、私钥和明显密钥类文件。超出单个可索引文件上限、包含 NUL、无法按严格 UTF-8 解码或被规则识别为二进制的普通文件 SHALL 被忽略而非部分索引。忽略统计 SHALL 按稳定原因分类返回，但普通与管理查询 MUST NOT 返回被忽略文件的正文。

默认规则 MUST 至少覆盖 `.git`、`node_modules`、`vendor`、`target`、`build`、`dist`、`.env` 及其变体、常见证书/私钥扩展名和明显密钥文件名。MVP 不要求对已允许文本内容执行通用秘密扫描，也不得把“未命中文件名规则”表述为内容安全审计通过。

#### Scenario: 快照同时包含源码和默认排除目录
- **WHEN** 合法 ZIP 包含源码文件以及 `.git`、`node_modules`、`target` 或 `dist` 下的文件
- **THEN** 任务只索引允许的源码文件，在快照结果中增加相应忽略计数，搜索和片段读取均无法访问被排除文件

#### Scenario: 快照包含环境文件和私钥
- **WHEN** 合法 ZIP 包含 `.env.production`、证书、私钥或明显密钥文件名
- **THEN** 这些文件不进入任何候选或活动索引，任务结果以安全类别记录忽略数量但不记录敏感正文

#### Scenario: 普通文件为二进制或超大文本
- **WHEN** 条目路径安全但内容为二进制、非法 UTF-8 或超过单文件索引上限
- **THEN** 系统忽略整个文件并记录稳定原因，不截取其中一部分伪装成完整可读代码

### Requirement: 候选索引验证成功后才原子替换活动快照
系统 SHALL 为每次上传或重建创建独立且不可被普通查询读取的索引 generation，并在确认索引可打开、文档计数与选定文件一致、每个文档均属于目标项目/分支/快照且关键元数据完整后，原子地把候选快照和 generation 切换为该分支唯一活动版本。活动元数据与实际查询 generation MUST 同时变化，查询不得观察到新 commit 配旧索引或旧 commit 配新索引。

构建或验证失败 SHALL 把候选快照或重建任务标记为 `FAILED` 并保留可诊断但不泄漏代码或内部路径的错误摘要；如果已有活动快照，所有普通查询继续使用它。如果没有旧活动快照，系统 SHALL 明确报告该分支尚无可查询快照。

#### Scenario: 首次索引成功
- **WHEN** 分支尚无活动快照，候选 generation 完整构建并通过验证
- **THEN** 系统一次性把候选快照和 generation 激活，任务变为 `SUCCEEDED`，后续状态与查询返回该 commit 和索引时间

#### Scenario: 新快照替换旧快照
- **WHEN** 分支已有活动快照且不同 commit 的候选 generation 验证成功
- **THEN** 系统原子切换到新快照，旧快照退出普通查询入口，新状态标明 commit 已变化且不提供历史快照查询

#### Scenario: 新索引构建失败
- **WHEN** 分支已有活动快照，而新候选 generation 在解压、写入、打开或验证阶段失败
- **THEN** 新任务和候选快照标记为 `FAILED`，旧活动 commit、索引时间和查询结果保持不变

#### Scenario: 激活事务失败
- **WHEN** 候选索引已写完但活动指针与元数据无法一起提交
- **THEN** 系统不暴露候选 generation，继续使用完整的旧活动快照，并允许后续安全清理未引用 generation

### Requirement: 管理员可以从原始对象重建活动快照索引
系统 SHALL 通过 `POST /api/admin/code-snapshots/{snapshotId}/reindex` 为当前活动快照创建新的后台 generation，复用原始 ZIP、安全校验和文件选择规则。该 POST 在无活动任务时返回 HTTP 202；目标快照不存在、不是该分支当前活动快照或原始对象不可用时 MUST 明确失败，不得从其他快照或服务器任意目录补齐输入。

#### Scenario: 重建当前活动快照
- **WHEN** 管理员对原始对象仍存在的活动快照请求重建，且该分支没有活动任务
- **THEN** 系统返回 HTTP 202，使用相同项目、分支和 commit 建立新 generation，成功后原子替换旧 generation

#### Scenario: 重建失败时保留原索引
- **WHEN** 活动快照重建任务失败
- **THEN** 原活动 generation 继续服务查询，快照状态显示最近重建失败但仍可查询

#### Scenario: 请求重建非活动快照
- **WHEN** 管理员尝试重建已经被替换的快照或属于其他分支的快照
- **THEN** 系统返回 HTTP 409 和 `CODE_SNAPSHOT_NOT_ACTIVE`，不提供历史快照查询或重建入口

### Requirement: 快照与任务状态必须可追踪且可恢复
系统 SHALL 允许管理员查询候选及历史任务状态，并允许已登录用户查询已启用项目指定分支的活动快照摘要。活动摘要 SHALL 返回项目标识、分支名、快照 ID、commit、索引时间、已索引文件数以及 `INITIAL|CHANGED|UNCHANGED` 变化提示；该提示只比较本次与前一成功活动快照的 commit，不声称计算了完整 diff。没有活动快照时 SHALL 返回显式的 `NOT_INDEXED` 状态。

服务异常退出后，遗留的 `RUNNING` 任务 SHALL 按既有后台任务恢复规则结束为失败并保留错误语义，MUST NOT 自动激活未完成 generation。临时工作目录和未引用 generation SHALL 可被幂等清理；原始对象和已有活动 generation 不得因清理失败而被误删。

#### Scenario: 查询活动快照摘要
- **WHEN** 已登录用户查询已启用项目存在分支的快照状态
- **THEN** 系统返回当前活动 commit、索引时间、文件数与基于前一成功 commit 的变化提示，不返回原始对象键或服务器索引路径

#### Scenario: 分支尚未建立快照
- **WHEN** 已登录用户查询的项目和分支存在但没有成功激活的快照
- **THEN** 系统返回 HTTP 200 和 `NOT_INDEXED` 状态，不借用其他分支快照

#### Scenario: 进程在构建中退出
- **WHEN** 服务重启并发现上一进程遗留的运行中代码索引任务
- **THEN** 系统把任务恢复为可诊断失败，保持旧活动 generation 或 `NOT_INDEXED`，且不会因存在部分索引目录而将其激活

#### Scenario: 普通入口查询停用项目
- **WHEN** 已登录用户通过普通快照状态接口请求停用项目
- **THEN** 系统返回 HTTP 404 和 `PROJECT_NOT_FOUND`，不暴露其快照或 commit 元数据
