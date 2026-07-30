# identity-access Specification

## Purpose

为 LoreDock 的 Web 管理、普通只读访问和后续 MCP 服务建立彼此清晰、可审计且不会互相替代的身份边界，使所有业务入口在读取内部内容或执行写操作前都能获得一致的认证与授权结论。

## Requirements

### Requirement: 固定 Web 账号可以安全登录
系统 SHALL 仅接受已配置的一个管理员账号和一个组内共享只读账号。`POST /api/auth/login` SHALL 接收账号与密码；凭据正确时建立 Web 会话并返回账号显示名及 `ADMIN` 或 `MEMBER` 角色，凭据错误时返回 HTTP 401 和稳定错误码 `AUTH_INVALID_CREDENTIALS`，且响应不得暴露账号是否存在。

#### Scenario: 管理员使用正确凭据登录
- **WHEN** 管理员提交正确的账号和密码
- **THEN** 系统建立管理员 Web 会话，并返回不包含密码哈希或会话密钥的管理员身份摘要

#### Scenario: 组内成员使用正确凭据登录
- **WHEN** 组内共享只读账号提交正确的账号和密码
- **THEN** 系统建立只读 Web 会话，并返回角色为 `MEMBER` 的身份摘要

#### Scenario: 错误凭据被统一拒绝
- **WHEN** 用户提交不存在的账号或错误密码
- **THEN** 系统返回 HTTP 401 和 `AUTH_INVALID_CREDENTIALS`，两种失败的响应正文和可观察错误语义一致

#### Scenario: 缺少必要字段
- **WHEN** 登录请求缺少账号或密码
- **THEN** 系统返回 HTTP 400 参数校验错误，不建立任何会话，也不回显密码

### Requirement: Web 会话具有明确生命周期
系统 SHALL 提供 `GET /api/auth/session` 查询当前身份和 `POST /api/auth/logout` 退出当前会话。受保护的 Web API SHALL 只接受有效 Web 会话；会话无效或过期时返回 HTTP 401 和 `AUTH_LOGIN_REQUIRED`。退出操作 SHALL 幂等，且不得影响其他浏览器会话。

#### Scenario: 页面刷新后恢复有效会话
- **WHEN** 已登录用户携带有效会话调用会话查询接口
- **THEN** 系统返回账号显示名与角色，前端无需重新提交密码即可恢复登录状态

#### Scenario: 未登录访问受保护接口
- **WHEN** 请求没有有效 Web 会话并访问受保护的 Web API
- **THEN** 系统返回 HTTP 401 和 `AUTH_LOGIN_REQUIRED`，不返回任何内部业务数据

#### Scenario: 重复退出
- **WHEN** 客户端对同一浏览器会话连续调用两次退出接口
- **THEN** 两次调用均安全完成，第二次不产生额外副作用，后续受保护请求被视为未登录

### Requirement: 密码和会话凭据不得泄露
系统 MUST 只接受带盐 BCrypt 密码哈希作为账号密码保存形式，并 MUST 在生产配置缺失、哈希格式无效或两个账号标识重复时拒绝就绪。日志、错误响应、审计字段和前端状态 MUST NOT 包含明文密码、密码哈希或完整会话令牌。

#### Scenario: 生产账号配置无效
- **WHEN** 生产环境缺少任一固定账号的 BCrypt 哈希或配置了非 BCrypt 值
- **THEN** 应用拒绝进入就绪状态，并仅报告不包含凭据值的配置错误

#### Scenario: 登录失败被记录
- **WHEN** 一次登录因凭据错误而失败
- **THEN** 日志可以记录失败类型、时间和 trace ID，但不得记录提交的密码、密码哈希或完整会话令牌

### Requirement: 管理写操作由服务端强制授权
系统 SHALL 将所有 `/api/admin/**` 业务接口限制为 `ADMIN` 角色，并 SHALL 允许 `ADMIN` 与 `MEMBER` 访问其被授权的只读接口。前端隐藏按钮不得作为授权依据；已登录但角色不足时返回 HTTP 403 和 `AUTH_FORBIDDEN`。

#### Scenario: 只读成员访问项目列表
- **WHEN** `MEMBER` 会话请求普通项目只读接口
- **THEN** 系统正常返回其可见的项目数据

#### Scenario: 只读成员直接调用管理接口
- **WHEN** `MEMBER` 会话绕过前端并直接请求任一 `/api/admin/**` 写接口
- **THEN** 系统返回 HTTP 403 和 `AUTH_FORBIDDEN`，且不产生数据或审计变更

#### Scenario: 管理员执行写操作时记录真实操作者
- **WHEN** `ADMIN` 会话成功执行项目或分支写操作
- **THEN** 产生的审计字段记录该管理员身份，而不是固定的 `SYSTEM` 操作者

### Requirement: MCP Token 与 Web 会话相互独立
系统 SHALL 在任何 `/mcp/**` 请求进入后续 MCP 工具处理前校验 `Authorization: Bearer <token>`。缺失、格式错误或不匹配的 Token SHALL 返回 HTTP 401 和 `MCP_TOKEN_INVALID`；有效 Token 仅代表 MCP 只读调用身份，MUST NOT 获得 Web 管理员权限，Web 会话也 MUST NOT 替代 MCP Token。

#### Scenario: 缺少或错误 MCP Token
- **WHEN** 请求访问 `/mcp/**` 且未提供 Bearer Token、格式错误或 Token 不匹配
- **THEN** 系统在业务处理前返回 HTTP 401 和 `MCP_TOKEN_INVALID`，不返回内部内容

#### Scenario: Web 管理员会话不能替代 MCP Token
- **WHEN** 请求仅携带有效管理员 Web 会话但没有有效 MCP Token 访问 `/mcp/**`
- **THEN** 系统仍返回 HTTP 401 和 `MCP_TOKEN_INVALID`

#### Scenario: 有效 MCP Token 只能进入只读边界
- **WHEN** 请求携带有效 MCP Token 访问后续已注册的 MCP 只读端点
- **THEN** 系统允许请求继续进入 MCP 处理链，但该身份不能调用 `/api/admin/**` 或建立 Web 会话

#### Scenario: MCP Token 校验失败被安全记录
- **WHEN** MCP Token 校验失败
- **THEN** 日志仅记录失败分类和 trace ID，不记录完整 Token、请求正文或内部内容

### Requirement: 登录页面遵循设计基线并清晰表达权限
前端 SHALL 参考 Pencil `01 · 登录` 画布实现统一登录页，保留 LoreDock 品牌区、账号/密码字段、登录按钮、管理员与组内账号权限说明以及安全错误状态。页面 SHALL 使用真实 API 状态，不得用静态成功状态代替认证。

#### Scenario: 未登录用户进入应用
- **WHEN** 未登录用户打开受保护页面或应用根路径
- **THEN** 前端导航到登录页，并在登录成功后进入原目标页或默认项目列表页

#### Scenario: 登录失败留在当前页面
- **WHEN** 登录 API 返回凭据错误
- **THEN** 页面保留账号输入、清空密码、显示不区分账号与密码的错误提示，并允许用户重试

#### Scenario: 只读用户登录后的界面
- **WHEN** `MEMBER` 登录成功并进入应用
- **THEN** 页面显示其只读身份说明，且不渲染项目创建、分支添加或项目启停操作

#### Scenario: 登录表单可访问性
- **WHEN** 用户仅使用键盘和表单标签操作登录页
- **THEN** 账号、密码、提交和错误提示均可获得焦点或被辅助技术关联，提交期间按钮展示明确忙碌状态并避免重复提交
