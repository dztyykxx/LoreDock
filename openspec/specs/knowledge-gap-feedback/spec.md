# knowledge-gap-feedback Specification

## Purpose

允许组内成员把无回答、错误回答和过期知识以可追溯但轻量的方式记录为知识缺口，并让管理员按明确范围查看和处理这些反馈，而不引入完整工单系统或自动修改正式知识。

## Requirements

### Requirement: 已登录用户可以在项目范围内幂等创建知识缺口
系统 SHALL 允许已认证 `ADMIN` 或 `MEMBER` 通过 `POST /api/projects/{identifier}/knowledge-gaps` 提交 `idempotencyKey`、可选分支、缺口类型、可选问答记录 ID 和不超过 1000 个 Unicode 字符的补充说明。缺口类型 MUST 为 `NO_ANSWER`、`WRONG_ANSWER` 或 `OUTDATED_KNOWLEDGE`；省略分支时 SHALL 使用项目 `main`。

有关联问答记录时，系统 MUST 校验该记录属于当前操作者、URL 项目和解析后的同一分支，并从已持久化消息复制问题摘要、运行 ID、结果/拒答/错误码和引用标识；客户端不得替换这些事实。没有关联问答记录时，请求 MUST 另含 1～2000 个 Unicode 字符的问题。成功创建 SHALL 返回 HTTP 201、稳定反馈 ID、固定范围、类型、状态 `OPEN` 和创建时间，不修改正式知识、索引或 Agent 运行。

#### Scenario: 对拒答记录没有回答
- **WHEN** 用户对自己当前项目和分支的一次拒答提交 `NO_ANSWER` 与新幂等键
- **THEN** 系统创建 `OPEN` 知识缺口，绑定该问答的问题与运行结果摘要，且不复制完整答案或证据正文

#### Scenario: 手动记录知识已过期
- **WHEN** 用户不关联问答记录并提交合法问题、`OUTDATED_KNOWLEDGE` 和补充说明
- **THEN** 系统在解析后的项目/分支创建独立 `OPEN` 知识缺口，不启动模型也不修改被认为过期的文档

#### Scenario: 关联问答范围不匹配
- **WHEN** 提交的问答记录属于其他操作者、项目或分支
- **THEN** 系统返回 HTTP 404 `QA_QUESTION_NOT_FOUND`，不创建反馈且不暴露该记录是否存在

#### Scenario: 缺口输入无效
- **WHEN** 类型不在允许枚举内、无关联记录时问题缺失、说明超限或项目/分支无效
- **THEN** 系统返回对应 HTTP 400 或 404 稳定错误，不写入部分反馈

### Requirement: 知识缺口创建必须防止重复副作用
系统 SHALL 以当前操作者和 `idempotencyKey` 唯一标识一次知识缺口创建。相同键和相同规范化输入的重试 MUST 返回原反馈且不新增记录；相同键对应不同项目、分支、类型、关联问答、问题或说明时 MUST 返回 HTTP 409 `KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT`。幂等比较 MUST 使用稳定摘要，日志不得记录完整问题或说明。

#### Scenario: 创建响应丢失后重试
- **WHEN** 客户端未收到首次成功响应并以相同幂等键重试相同反馈
- **THEN** 系统返回原反馈 ID 与当前状态，数据库中只有一条知识缺口

#### Scenario: 复用幂等键提交不同类型
- **WHEN** 用户以已使用的幂等键把 `NO_ANSWER` 改为 `WRONG_ANSWER`
- **THEN** 系统返回 HTTP 409 `KNOWLEDGE_GAP_IDEMPOTENCY_CONFLICT`，原反馈类型和状态保持不变

### Requirement: 管理员可以查询和执行最小状态流转
系统 SHALL 仅允许 `ADMIN` 通过 `GET /api/admin/knowledge-gaps` 以有界游标分页查询知识缺口，并支持按项目、分支、类型和状态过滤。列表与详情 SHALL 返回反馈 ID、固定范围、类型、状态、有限问题与说明、可选问答/运行标识、结果摘要、创建/更新时间和审计操作者；不得返回完整 Agent 证据正文、隐藏提示、思维链或服务器路径。

系统 SHALL 仅允许 `ADMIN` 通过 `PATCH /api/admin/knowledge-gaps/{feedbackId}/status` 把状态按 `OPEN → ACKNOWLEDGED → CLOSED` 单向推进；设置为当前状态 SHALL 幂等成功，倒退或跳过状态 MUST 返回 HTTP 409 `KNOWLEDGE_GAP_STATUS_CONFLICT`。状态变化只表示人工处理进度，MUST NOT 自动生成、编辑、发布或归档知识。T7 不提供指派、评论、优先级、SLA 或外部工单同步。

#### Scenario: 管理员按项目查看未处理缺口
- **WHEN** 管理员按项目和 `OPEN` 状态查询知识缺口
- **THEN** 系统按创建时间与稳定 ID 倒序返回匹配记录和下一页游标，不混入其他项目或状态

#### Scenario: 成员绕过页面查询管理列表
- **WHEN** `MEMBER` 直接请求任一 `/api/admin/knowledge-gaps` 接口
- **THEN** 系统返回 HTTP 403 `AUTH_FORBIDDEN`，不返回反馈内容或修改状态

#### Scenario: 管理员确认并关闭反馈
- **WHEN** 管理员先把 `OPEN` 更新为 `ACKNOWLEDGED`，随后更新为 `CLOSED`
- **THEN** 两次状态变化均记录真实操作者与 UTC 时间，反馈最终关闭且正式知识保持不变

#### Scenario: 非法倒退或跳过状态
- **WHEN** 管理员把 `CLOSED` 改回 `OPEN`，或把 `OPEN` 直接改为 `CLOSED`
- **THEN** 系统返回 HTTP 409 `KNOWLEDGE_GAP_STATUS_CONFLICT`，原状态和审计信息保持不变

### Requirement: 问答页面必须提供上下文明确的反馈入口
问答页面 SHALL 在可信回答、来源冲突、无依据拒答和运行失败状态提供“记录知识缺口”入口，并按当前结果推荐但不强制对应类型：无依据/失败推荐“没有回答”，可信回答推荐“回答错误”或“知识已过期”，冲突状态提供全部三类。提交前页面 MUST 显示将被固定的项目、分支、问题和缺口类型；提交期间防止重复操作，成功后显示稳定确认，失败时保留用户说明并允许以同一幂等键安全重试。

#### Scenario: 从拒答创建反馈
- **WHEN** 用户在“当前知识库没有足够依据”状态点击记录知识缺口
- **THEN** 表单预选“没有回答”，展示当前项目、分支和问题，成功后显示已记录且不改变原拒答

#### Scenario: 反馈提交暂时失败
- **WHEN** 创建反馈请求因网络中断没有得到确定结果
- **THEN** 页面保留类型和说明，并以原幂等键重试以避免重复知识缺口
