## Purpose

为管理员安全接收人工整理文件和从公司 Wiki 转换出的 Markdown 批次，在不执行、不渲染和不信任上传内容的前提下创建可审核草稿，并以逐文件结果保存部分成功、明确失败和可解释忽略的证据。

## ADDED Requirements

### Requirement: 导入只接受明确支持的文件与 UTF-8 文本
系统 SHALL 接受单个 `.md`、`.markdown`、`.txt` 文件或 ZIP 文件；单个 Markdown 按 `MARKDOWN`、单个文本按 `PLAIN_TEXT` 创建文档，ZIP 中只处理 `.md` 与 `.markdown` 普通文件。文本 MUST 使用 UTF-8，可接受 UTF-8 BOM；系统 MUST NOT 解析 Markdown front matter、执行内容中的指令、获取外部 URL 或把 HTML 当作可信界面渲染。外层文件类型不支持时返回 HTTP 415 和 `DOCUMENT_IMPORT_TYPE_UNSUPPORTED`，超过上传总大小时返回 HTTP 413 和 `DOCUMENT_IMPORT_TOO_LARGE`。

#### Scenario: 导入单个 Markdown
- **WHEN** 管理员上传合法 UTF-8 `.md` 文件并提交合法范围、目录、标签和来源默认值
- **THEN** 系统创建一个 `MARKDOWN` 草稿，标题默认取去除扩展名后的文件名，正文保持原始文本语义，原文件名记录在来源信息中

#### Scenario: 导入单个纯文本
- **WHEN** 管理员上传合法 UTF-8 `.txt` 文件
- **THEN** 系统创建一个 `PLAIN_TEXT` 草稿，不执行 Markdown 或 HTML 解释

#### Scenario: 上传不支持的外层文件
- **WHEN** 管理员上传 PDF、Word、图片、网页文件或其他不支持格式
- **THEN** 系统返回 HTTP 415 和 `DOCUMENT_IMPORT_TYPE_UNSUPPORTED`，不保存导入批次、原始对象或文档

#### Scenario: 文本不是有效 UTF-8
- **WHEN** 受支持的单文件或 ZIP Markdown 条目包含非法 UTF-8 字节
- **THEN** 系统把对应条目标记为 `FAILED` 和 `INVALID_TEXT_ENCODING`，不使用平台默认编码替换或猜测内容

### Requirement: ZIP 处理必须限制路径、条目类型和资源消耗
系统 MUST 在创建文档前按可配置且已公布的上传字节数、条目数、单条目展开字节数、总展开字节数和压缩比例限制验证 ZIP。系统 MUST 拒绝绝对路径、盘符路径、包含 `..` 的路径、NUL 字符、名称冲突和符号链接，不得把条目按其名称写入文件系统。加密、分卷、损坏或超过资源限制的 ZIP MUST 返回 HTTP 422 和 `DOCUMENT_IMPORT_ARCHIVE_INVALID`，不得创建任何文档；错误响应和日志不得包含上传正文、内部文件路径或堆栈。

#### Scenario: ZIP 包含多级 Markdown 目录
- **WHEN** ZIP 中包含安全的相对目录和多个 Markdown 普通文件且未超过任何限制
- **THEN** 系统将安全相对目录追加到请求的目录前缀，为每个 Markdown 文件创建独立候选条目，且不在本地按 ZIP 路径解压

#### Scenario: ZIP 包含路径穿越条目
- **WHEN** ZIP 条目使用绝对路径、盘符、NUL 字符或 `..` 逃逸导入根目录
- **THEN** 系统将该条目标记为 `FAILED` 和 `UNSAFE_ENTRY_PATH`，不读取为文档、不创建文件系统路径，并继续报告其他安全条目

#### Scenario: ZIP 包含符号链接
- **WHEN** ZIP 元数据表明某个条目是 Unix 符号链接或其他非普通文件
- **THEN** 系统将该条目标记为 `FAILED` 和 `UNSUPPORTED_ENTRY_TYPE`，既不跟随链接也不把链接目标文本导入为文档

#### Scenario: ZIP 超过展开限制
- **WHEN** ZIP 的任一条目、累计展开字节数、条目数或压缩比例超过配置限制
- **THEN** 系统中止整个批次并返回 HTTP 422 和 `DOCUMENT_IMPORT_ARCHIVE_INVALID`，不创建任何文档且删除本次尚未被业务记录引用的原始对象

#### Scenario: ZIP 损坏或加密
- **WHEN** 上传内容不是结构完整、可读取且未加密的普通 ZIP
- **THEN** 系统返回 HTTP 422 和 `DOCUMENT_IMPORT_ARCHIVE_INVALID`，不泄露解析器内部信息且不创建部分文档

### Requirement: 每个可处理条目必须产生成功、失败或忽略结果
系统 SHALL 为已通过批次级安全校验的导入持久化批次及逐条目结果。每个条目 MUST 恰好为 `SUCCEEDED`、`FAILED` 或 `IGNORED`，并提供稳定原因码、对用户安全的说明以及成功时的文档 ID；批次 MUST 汇总三类数量并以 `COMPLETED`、`PARTIAL` 或 `FAILED` 表达总体结果。一个条目的内容或业务校验失败 MUST NOT 回滚其他条目的成功结果，也不得把失败记为忽略。

#### Scenario: ZIP 部分成功
- **WHEN** 一个安全 ZIP 同时包含合法 Markdown、非法 UTF-8 Markdown 和不支持文件
- **THEN** 合法 Markdown 为 `SUCCEEDED` 并创建草稿，非法文本为 `FAILED`，不支持文件为 `IGNORED`，批次为 `PARTIAL` 且三类计数与明细一致

#### Scenario: ZIP 只有不支持文件和目录
- **WHEN** 安全 ZIP 只包含目录、系统元数据或非 Markdown 文件
- **THEN** 系统保存 `FAILED` 批次，所有条目均为 `IGNORED`，不创建文档并明确说明没有可导入 Markdown

#### Scenario: 重复条目名称
- **WHEN** ZIP 中多个中央目录记录解析为相同规范化相对路径
- **THEN** 系统在批次级校验阶段拒绝整个 ZIP，返回 `DOCUMENT_IMPORT_ARCHIVE_INVALID`，不得用后一个条目覆盖前一个条目

#### Scenario: 查询历史导入结果
- **WHEN** 管理员按批次 ID 查询已完成导入
- **THEN** 系统返回原文件名、请求范围、批次状态、三类计数和稳定排序的条目明细，但不返回原始上传正文或服务端对象键

### Requirement: 每个成功导入项只能原子创建一个草稿
系统 SHALL 对每个通过条目校验的文件分别执行文档创建事务；该事务 MUST 同时保存文档、标签、来源、批次条目关联和审计信息，任一步失败 MUST 回滚该条目的全部业务记录并把条目标记为 `FAILED`。导入 MUST 复用知识文档的范围与字段校验，所有成功文档 MUST 为 `DRAFT` 且不得自动发布、自动加入活动检索数据集或触发模型调用。

#### Scenario: 成功项创建草稿
- **WHEN** 一个导入条目通过文本、字段与范围校验
- **THEN** 系统原子创建一个带导入来源的 `DRAFT` 文档，并在条目结果中返回其文档 ID

#### Scenario: 条目保存中途失败
- **WHEN** 文档、标签、来源或批次关联任一步数据库写入失败
- **THEN** 该条目不留下孤立文档、标签或关系，结果为 `FAILED`，其他已提交成功项保持成功

#### Scenario: 导入范围已失效
- **WHEN** 处理条目时所选项目、分支不存在或分支不属于项目
- **THEN** 对应条目标记为 `FAILED` 和 `DOCUMENT_SCOPE_INVALID`，不回退到 `main`、项目级或通用范围

#### Scenario: 重复上传同一文件
- **WHEN** 管理员在没有客户端幂等键的情况下再次上传相同文件
- **THEN** 系统创建新的导入批次和新的草稿，不静默覆盖或合并现有文档，并由管理员决定是否使用替代关系

### Requirement: 导入页面必须展示安全限制和完整结果
前端 SHALL 为管理员提供单文件/ZIP 选择、范围、目录、标签与来源默认值输入，并在提交前说明支持格式与大小限制。页面 SHALL 在上传和处理期间防止重复提交，完成后按成功、失败、忽略分组显示文件名、原因和成功文档入口；普通成员 MUST NOT 看到导入入口。所有来自文件名和错误明细的文本 MUST 作为不可信文本显示，不得按 HTML 执行。

#### Scenario: 管理员完成部分成功导入
- **WHEN** 导入 API 返回 `PARTIAL` 批次
- **THEN** 页面同时展示准确的成功、失败、忽略计数和逐文件原因，并允许管理员打开成功创建的草稿

#### Scenario: 批次级安全拒绝
- **WHEN** API 因文件过大、ZIP 损坏、加密或展开限制拒绝整个上传
- **THEN** 页面显示安全且可操作的失败说明，不展示伪造的逐文件成功项，也不把失败误报为网络错误

#### Scenario: 普通成员访问导入路由
- **WHEN** 普通成员直接访问导入页面或调用导入 API
- **THEN** 前端导航到其允许的知识浏览入口，服务端对直接 API 调用返回 HTTP 403 且不接收上传内容
