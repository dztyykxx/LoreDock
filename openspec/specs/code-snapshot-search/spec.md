# code-snapshot-search Specification

## Purpose

为 Web 问答、后续 MCP 和内部 Agent 提供同一套受控代码检索契约，只查询指定已启用项目与分支的活动快照，并以有限片段和可追溯版本元数据返回代码证据。

## Requirements

### Requirement: 代码搜索必须在解析后的项目和分支范围内执行
系统 SHALL 允许 `ADMIN` 与 `MEMBER` 通过 `GET /api/projects/{identifier}/code-search` 在已启用项目的指定分支活动快照中搜索。`branch` 省略时 SHALL 使用该项目的 `main` 分支；指定分支不存在时返回 `BRANCH_NOT_FOUND`，没有活动快照时返回 `CODE_SNAPSHOT_NOT_FOUND`，MUST NOT 回退到其他项目、其他分支、历史快照或尚在构建的候选 generation。

查询参数 SHALL 包含去除首尾空白后 1～200 字符的 `query`、可选 `target=ALL|PATH|CONTENT`、可选规范化仓库相对 `pathPrefix` 以及 `limit`（默认 10、最大 50）。查询文本 SHALL 作为用户关键词处理，而不是允许客户端提交原始索引查询语法；特殊字符或无法形成有效词项的输入不得导致内部解析异常泄漏。

#### Scenario: 省略分支搜索默认分支
- **WHEN** 已登录用户对已启用项目搜索代码且省略 `branch`
- **THEN** 系统只搜索该项目 `main` 分支的活动快照，并在每条结果中返回 `main` 与活动 commit

#### Scenario: 两个分支存在同名文件
- **WHEN** 同一项目的 `main` 与功能分支活动快照都包含相同路径，但用户明确搜索功能分支
- **THEN** 所有候选、命中片段和计数都来自功能分支，不混入 `main` 的同名文件

#### Scenario: 不同项目存在同名分支和文件
- **WHEN** 两个项目都有 `main` 和相同文件路径，用户只请求其中一个项目
- **THEN** 系统在索引查询阶段只返回目标项目活动快照的结果，不先跨项目召回再在展示层隐藏

#### Scenario: 分支没有活动快照
- **WHEN** 项目和分支存在，但该分支从未成功激活代码快照
- **THEN** 系统返回 HTTP 404 和 `CODE_SNAPSHOT_NOT_FOUND`，不使用其他分支代码冒充当前实现

#### Scenario: 查询参数无效
- **WHEN** `query` 为空或过长、`target` 不受支持、`pathPrefix` 逃逸根目录或 `limit` 超出范围
- **THEN** 系统返回 HTTP 400 字段校验错误，不执行无界查询

#### Scenario: 成员未登录
- **WHEN** 匿名请求代码搜索
- **THEN** 系统返回 HTTP 401，不暴露项目、分支、commit 或命中存在性

### Requirement: 搜索结果必须提供有限片段和版本来源
搜索 SHALL 同时支持规范化路径和文本内容匹配；`ALL` 模式下路径与文件名命中 SHALL 获得高于同等内容命中的权重。响应 SHALL 按相关性降序、规范化路径升序稳定排序，并为每条结果返回项目标识、分支、快照 ID、commit、索引时间、仓库相对路径、有限命中片段、相关性分数和 `truncated` 标志。结果 MUST NOT 包含服务器文件路径、索引目录或原始对象键。

如果没有命中，系统 SHALL 返回空结果而不是扩大到其他范围。普通搜索目标为在脱敏或模拟的 20 万行级活动快照上于 3 秒内完成代表性路径和内容查询；性能验收不得使用真实公司代码或凭据。

#### Scenario: 路径搜索命中文件
- **WHEN** 用户以 `target=PATH` 搜索路径或文件名关键词
- **THEN** 系统只返回路径字段匹配的活动快照文件，包含有限片段和完整版本来源元数据

#### Scenario: 内容搜索命中标识符
- **WHEN** 用户以 `target=CONTENT` 搜索活动快照源码中的标识符或文本
- **THEN** 系统返回内容匹配结果和围绕命中的有限片段，不返回仅路径匹配的文件

#### Scenario: 无结果时不扩大范围
- **WHEN** 指定项目、分支和活动快照中没有匹配内容
- **THEN** 系统返回 HTTP 200 与空结果，不查询其他项目、分支或历史快照

#### Scenario: 命中内容超过片段上限
- **WHEN** 结果文件很大或一次命中周围内容超过响应片段上限
- **THEN** 系统截断片段、设置 `truncated=true`，并仍返回可用于后续受限读取的仓库相对路径

#### Scenario: 20 万行级代码查询
- **WHEN** 使用公开可提交的脱敏或模拟 20 万行级代码夹具构建活动快照并执行代表性路径和内容查询
- **THEN** 查询在目标环境 3 秒内返回正确范围的结果，且测试产物不包含内部真实代码

### Requirement: 代码片段读取只能访问活动索引中的允许文件
系统 SHALL 允许 `ADMIN` 与 `MEMBER` 通过 `GET /api/projects/{identifier}/code-snippets` 提交分支、规范化仓库相对 `path`、可选 `startLine` 和 `lineCount`，读取指定活动快照中已经建立索引的文本文件。`startLine` 默认 1 且必须为正数；`lineCount` 默认 80、最大 200。响应 SHALL 返回项目标识、分支、快照 ID、commit、索引时间、路径、实际起止行、有限正文和 `truncated` 标志。

读取 MUST 以活动索引中的已允许文件为事实来源，不得把客户端路径拼接到服务器文件系统，也不得通过原始 ZIP 绕过忽略规则。路径不存在、被忽略、属于其他范围或不在活动 generation 中时 SHALL 统一返回 HTTP 404 和 `CODE_FILE_NOT_FOUND`，避免泄漏其他范围文件存在性。

#### Scenario: 读取活动快照中的有限片段
- **WHEN** 用户请求当前项目与分支活动索引中存在的文本路径及合法行范围
- **THEN** 系统返回不超过请求和服务端上限的正文、实际行范围、活动 commit 与截断状态

#### Scenario: 请求范围超过文件结尾
- **WHEN** `startLine` 合法但请求行数越过文件末尾
- **THEN** 系统返回从起始行到文件末尾的内容和实际结束行，不读取相邻文件或填充不存在的行

#### Scenario: 起始行超过文件行数
- **WHEN** 请求路径存在但 `startLine` 超过该文件总行数
- **THEN** 系统返回 HTTP 416 和 `CODE_SNIPPET_RANGE_INVALID`，响应不包含文件正文

#### Scenario: 尝试读取被排除的敏感文件
- **WHEN** 用户请求 `.env`、私钥或其他未进入活动索引的路径
- **THEN** 系统返回 HTTP 404 和 `CODE_FILE_NOT_FOUND`，不从原始 ZIP 或工作目录读取内容

#### Scenario: 路径来自另一分支
- **WHEN** 请求路径只存在于同项目的另一个分支
- **THEN** 系统返回 `CODE_FILE_NOT_FOUND`，不透露另一分支是否存在该文件

#### Scenario: 片段参数无效
- **WHEN** 路径为空或不规范、`startLine` 非正数、`lineCount` 非正数或超过 200
- **THEN** 系统返回 HTTP 400 字段校验错误，不读取索引或文件系统

### Requirement: 所有代码查询入口必须复用相同范围与活动版本规则
Web、后续 MCP 和内部 Agent SHALL 通过同一代码搜索与片段读取应用能力执行查询，调用方只能提供项目、分支、查询和片段限制，MUST NOT 指定服务器索引目录、任意 generation、历史快照或绕过范围校验。一次请求解析出的活动快照 SHALL 固定到查询完成；并发激活新快照时，该请求要么完整读取旧 generation，要么完整读取新 generation，不得混合两个 commit 的结果与元数据。

#### Scenario: 查询期间并发切换快照
- **WHEN** 搜索或片段读取开始后同一分支成功激活新快照
- **THEN** 当前响应的全部结果、正文和元数据仍来自同一个已解析 generation，下一请求才可使用新 commit

#### Scenario: 调用方尝试指定历史 generation
- **WHEN** Web、内部工具或未来 MCP 输入包含非契约字段以选择快照 ID、generation 或服务器路径
- **THEN** 系统忽略或拒绝该输入，始终由服务端解析目标分支唯一活动快照

#### Scenario: 活动索引在读取时不可用
- **WHEN** 已记录的活动 generation 无法安全打开或读取
- **THEN** 系统返回脱敏的 `CODE_INDEX_UNAVAILABLE`，记录带上下文的内部错误，且不回退到其他分支或未验证 generation
