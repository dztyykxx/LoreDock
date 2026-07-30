## Context

参见 `proposal.md` 的动机与范围，以及两份增量规格中的可观察行为。当前后端只有 T1 的平台、对象存储和后台任务能力；`ActorProvider` 固定返回 `SYSTEM`，数据库没有项目/分支表，前端仅有运行状态页且未引入路由或组件体系。T2 跨越认证、授权、持久化、审计和三个 Vue 页面，并新增安全敏感依赖，因此需要在实现前冻结边界。

约束包括：Spring Boot 4.1.0、Java 21、MyBatis-Plus、Flyway 唯一建表入口、真实 PostgreSQL 集成测试、Vue 3 + TypeScript + Vite、中文 Javadoc/关键原因注释，以及每个测试用例的中文业务目的注释。UI 结构和视觉以 Pencil 的 `01 · 登录`、`02 · 项目列表`、`10 · 项目设置 / 分支管理` 为基线，本变更只验收电脑浏览器页面。当前接口已提供的数据不得被静态示例覆盖；没有对应后端接口的后续能力允许沿用设计稿样例作为展示占位。

## Goals / Non-Goals

**Goals:**

- 形成可被后续知识、快照、检索、Web 问答和 MCP 复用的身份、项目及分支端口。
- 让 Web 会话、角色授权和 MCP Token 在入口层互相独立，并统一落入 T1 的安全错误格式与 trace ID。
- 让项目创建、默认分支、启停可见性和分支唯一性同时受领域规则、事务和数据库约束保护。
- 从 Pencil 可复用组件映射出最小 Vue 组件与设计令牌，交付由现有 API 驱动的加载、空、失败和权限状态，并为尚无接口的后续能力保留设计稿展示样例。
- 保留可回滚、可重复迁移和可重复演示数据准备路径。

**Non-Goals:**

- 不建立用户表、账号管理后台、注册/找回密码或分布式会话基础设施。
- 不实现项目基本信息编辑、项目/分支删除或重命名；设置页的基本信息在 T2 为只读。
- 不实现 MCP 工具和检索逻辑，只建立 `/mcp/**` 的共享 Token 前置校验。
- 不为设计稿中的知识数、草稿数、活动快照或 commit 新增后端占位数据或接口，也不提前实现 T3/T4/T12 页面；前端可以静态复现这些设计样例，后续接口具备时再替换。
- 不实现移动端、平板或窄屏专项布局，本变更只按电脑浏览器和 Pencil 桌面画布验收。

## Decisions

### 1. 按身份与项目能力分包，入口复用应用用例

后端采用以下边界：

```text
identity/
  domain/                 WebRole、AuthenticatedActor
  application/            登录、会话、角色与 MCP Token 端口
  infrastructure/config/  固定账号与 Token 摘要配置
  infrastructure/web/     Auth API、Sa-Token 拦截器、MCP 过滤器
project/
  domain/                 Project、ProjectIdentifier、ProjectBranch、BranchName、ProjectStatus
  application/            command/query 用例、仓储端口、API 无关 DTO
  infrastructure/persistence/  显式实体、Mapper、仓储适配器
  infrastructure/web/     普通与管理员 Controller、请求/响应模型
  infrastructure/demo/    显式启用的幂等演示数据准备器
```

Controller、拦截器和数据准备器只调用应用服务；项目状态、标识、分支名、默认 `main` 和范围解析规则集中在领域/应用层。Web、未来 MCP 和后台入口不得复制这些规则。

选择按能力分包而不是全局 `controller/service/mapper`，以保持“基础设施 → 应用 → 领域”的依赖方向。也不为每个简单 DTO 或单一校验创建无业务意义的包装层。

### 2. Web 认证使用 Sa-Token，固定账号保持配置化

引入 `cn.dev33:sa-token-spring-boot4-starter:1.45.0`；该版本的官方发布说明明确新增 Spring Boot 4 starter。只额外引入 Spring Security 的 `spring-security-crypto` 模块使用 `BCryptPasswordEncoder`，不引入完整 Spring Security 认证链，也不自行实现密码哈希。

两个固定账号从强类型配置读取：稳定账号标识、显示名、角色和 BCrypt 哈希。启动校验要求恰好一个 `ADMIN`、一个 `MEMBER`、账号标识不同且哈希符合 BCrypt 格式。正式仓库和 Flyway 不包含默认明文密码或可直接使用的生产凭据；`.env.example` 只列变量名和生成哈希说明。

不建立用户表，因为需求明确只有两个固定账号且没有账号管理生命周期；引入数据库会制造未被规格要求的用户维护、迁移与初始密码流程。未来若需要多用户或密码变更，应新建 OpenSpec change，将固定账号端口替换为持久化账号仓储。

### 3. Web 会话通过受保护 Cookie 传递

Sa-Token 的登录 ID 使用稳定账号标识，角色信息从固定账号目录解析，不信任客户端提交角色。会话 Token 由 `HttpOnly` Cookie 传递；生产配置启用 `Secure`、`SameSite=Strict`、限定路径和明确过期时间，本地开发通过 Vite `/api` 代理保持同站请求。所有写接口只接受 JSON 且不使用 GET 改变状态。

T2 使用 Sa-Token 单实例内存会话，应用重启后用户重新登录。这与 MVP 单实例边界一致，避免提前引入 Redis；若后续部署要求多实例或会话跨重启，再通过 Sa-Token DAO 适配器独立演进。

`POST /api/auth/logout` 在无会话时也返回成功。认证异常转换为既有 `ApiError`，不会把 Sa-Token 内部异常或 Token 暴露给客户端。

### 4. MCP Token 使用独立高熵共享密钥摘要

MCP 不复用 Web 登录或管理员会话。配置只保存共享 Token 的 SHA-256 十六进制摘要；请求过滤器解析严格的单个 Bearer 值，对来值计算摘要并常量时间比较。选择 SHA-256 摘要而不是 BCrypt 是因为 MCP Token 必须由高熵随机值生成，不是低熵人类密码；启动时校验摘要格式，日志永不记录原值或摘要。

`OncePerRequestFilter` 仅覆盖 `/mcp/**`，成功后写入只读机器身份并继续过滤链；T2 没有 MCP Controller，因此有效 Token 最终可能得到 404，这是“认证已通过但工具尚未实现”的正确边界。无效 Token 在分派前由共享的安全错误写出器返回 `MCP_TOKEN_INVALID`。该机器身份不会进入 `/api/admin/**` 的 Sa-Token 角色判定。

### 5. HTTP 契约先于实现冻结

接口与最小模型如下；所有时间为 UTC ISO 8601，错误统一使用 T1 `ApiError`：

| 方法与路径 | 请求/查询 | 成功响应 | 权限与幂等性 |
|---|---|---|---|
| `POST /api/auth/login` | `username`, `password` | `SessionView { username, displayName, role }` | 匿名；成功建立/替换当前浏览器会话 |
| `GET /api/auth/session` | 无 | `SessionView` | 有效 Web 会话；幂等 |
| `POST /api/auth/logout` | 无 | 204 | 匿名可调；幂等 |
| `GET /api/projects` | 无 | `ProjectSummary[]` | `ADMIN`/`MEMBER`；仅启用；幂等 |
| `GET /api/projects/{identifier}` | 可选 `branch` | `ProjectDetail`，含 `selectedBranch` 与 `branches` | `ADMIN`/`MEMBER`；仅启用；幂等 |
| `GET /api/admin/projects` | 可选 `status` | `AdminProjectSummary[]` | `ADMIN`；含停用；幂等 |
| `GET /api/admin/projects/{projectId}` | 无 | `AdminProjectDetail` | `ADMIN`；幂等 |
| `POST /api/admin/projects` | `name`, `identifier`, `description`, `technologyStack` | 201 `AdminProjectDetail` | `ADMIN`；非幂等，标识唯一防重复 |
| `POST /api/admin/projects/{projectId}/branches` | `name` | 201 `BranchView` | `ADMIN`；非幂等，项目内名称唯一 |
| `PATCH /api/admin/projects/{projectId}/status` | `status` | `AdminProjectDetail` | `ADMIN`；设置目标状态幂等 |

普通摘要同时返回项目 UUID，便于前端从列表进入按 UUID 管理的设置路由；项目标识仍是对检索/MCP 暴露的稳定业务键。响应不返回知识数或快照字段，后续能力通过新增可选字段或新资源演进，不改变 T2 字段语义。

新增稳定错误码：`AUTH_INVALID_CREDENTIALS`(401)、`AUTH_LOGIN_REQUIRED`(401)、`AUTH_FORBIDDEN`(403)、`MCP_TOKEN_INVALID`(401)、`PROJECT_NOT_FOUND`(404)、`BRANCH_NOT_FOUND`(404)、`PROJECT_IDENTIFIER_CONFLICT`(409)、`BRANCH_NAME_CONFLICT`(409)。字段错误沿用 `INVALID_REQUEST`(400)。唯一约束异常只按已命名约束转换，其他数据库异常保留为内部错误，避免误报冲突。

### 6. 项目与分支模型使用数据库约束和应用事务双重保护

新增只追加迁移（预期 `V2__create_project_and_branch_tables.sql`）：

```text
project_space
  id UUID PK
  identifier VARCHAR(64) UNIQUE NOT NULL
  name VARCHAR(100) NOT NULL
  description VARCHAR(1000) NOT NULL
  technology_stack VARCHAR(255) NOT NULL
  status VARCHAR(16) NOT NULL CHECK ENABLED|DISABLED
  created_at / updated_at TIMESTAMPTZ
  created_by / updated_by VARCHAR(128)

project_branch
  id UUID PK
  project_id UUID NOT NULL FK project_space(id)
  name VARCHAR(128) NOT NULL
  created_at / updated_at TIMESTAMPTZ
  created_by / updated_by VARCHAR(128)
  UNIQUE(project_id, name)
```

项目标识保存为已校验的小写原值；分支名保留大小写并始终使用 `branch.id + project.id` 作为后续内部隔离键，不能直接拼成文件路径。创建项目的应用事务依次生成项目和 `main` 分支，任何一步失败全部回滚。停用只更新项目状态；分支仍可由管理员维护，普通查询在仓储查询条件中直接限定 `ENABLED`，不能先跨状态加载再由 Controller 隐藏。

领域值对象在入库前处理首尾空白、长度和名称规则；数据库唯一约束解决并发竞态。应用层捕获命名唯一约束并映射为稳定 409。所有实体使用 `@TableName`、`@TableId`、每字段 `@TableField`，DTO、领域对象与实体互相分离，不使用 XML Mapper。

项目写事务通过既有 `TimeProvider` 与 `AuditMetadataFactory` 获取时间和操作者。请求线程存在 Web 登录时，新的 `ActorProvider` 返回账号标识；无登录的启动恢复、演示数据和后台任务仍明确返回 `SYSTEM`。此分支需要中文注释解释“为何不能把机器工作伪装成人工操作”。

### 7. 前端使用 Vue Router 和轻量会话组合函数

引入并锁定与 Vue 3.5 兼容的官方 Vue Router；实现前以官方文档和实际构建解析精确版本，当前候选为 5.2.0。路由结构：

```text
/login                         LoginView
/projects                      ProjectListView
/projects/:projectId/settings  ProjectSettingsView（管理员管理态）
/projects/:identifier          ProjectSettingsView（成员只读态）
```

`useSession` 作为模块级组合函数保存 `checking/authenticated/anonymous` 状态、身份与登录/退出动作，不新增 Pinia。全局路由守卫先恢复 `/api/auth/session`；401 转到登录并保留安全的 `redirect`，管理员路由还检查角色。所有 `fetch` 通过一个小型 API 客户端统一 JSON、Cookie、`ApiError` 和 401 处理，但不封装成通用网络框架。

将 Pencil 可复用组件映射为 Vue：`AppButton`、`FormField`、`StatusBadge`、`NoticeBanner`、`PageHeader`、`AppSidebar`、`AppTopBar`、`ProjectHero`、`ProjectTabs`、`ProjectCard`。只有实际出现在 T2 三张页面的组件才实现；页面专属组合留在 view 内，避免为单次布局拆出过多组件。

全局 CSS 令牌直接对应设计稿：`#F7F7F8` 画布、`#FFFFFF` 表面、`#F2F2F0` 侧栏、`#202123` 主文本、`#6B6B6B` 次文本、`#E5E5E5` 边框、`#15836D` 主色及告警/危险色；字体使用仓库自托管且许可证允许的 Geist/Geist Mono WOFF2，并提供系统字体回退。不得依赖运行时外网字体。

实现顺序遵循设计到代码验证：逐一读取三个 Pencil frame 及其实际组件实例/覆盖，先建立设计令牌和共用组件，再集成页面；在 Pencil 的 1440px 桌面尺寸下将本地页面截图与设计稿比对。项目、分支、状态和身份等当前接口已覆盖的数据必须来自真实响应；设计稿中来自 T3/T4 且尚无后端接口的数量、commit 与状态作为前端静态展示样例保留，集中定义并避免进入 API 模型、权限判断或写请求，便于后续以真实接口替换。基本信息为只读，管理员写操作使用设计稿按钮、提示和确认模式。

### 8. 演示数据通过显式 profile 准备

使用 `demo`/`test` profile 下、且 `loredock.demo.seed-enabled=true` 时才运行的准备器，调用项目应用服务按项目标识和分支名执行 find-or-create。它不开放生产 HTTP 端点，不写入 Flyway 基线，也不包含真实公司材料或凭据。重复运行报告 `created/reused` 结果，供验收脚本确认两个项目及演示分支已经就绪。

### 9. 测试策略围绕高风险业务事实

- 身份单元/契约测试：正确与错误登录具有相同失败外观、BCrypt 配置校验、会话恢复/退出、401 与 403 区分、Web 会话不能替代 MCP Token、Token 不进入日志。
- 项目领域测试：项目标识与分支名边界、默认 `main`、停用不删除、未知分支不回退。
- PostgreSQL 集成测试：V2 空库/重复迁移、显式实体映射、创建项目与 `main` 原子性、普通查询排除停用项目、同项目分支唯一/跨项目可重名、并发唯一约束和重启后往返。
- Web 集成测试：所有接口的请求/响应/错误码、只读用户直接写入被 403 拒绝且无副作用、MCP 过滤器在分派前拒绝无效 Token。
- 前端组件/页面测试：登录成功与失败、守卫恢复会话、角色隐藏写操作、项目列表加载/筛选/空/失败、创建项目、添加分支、停用确认、真实 API 数据不被覆盖，以及无接口的设计样例不触发后端请求。
- 视觉与交互验证：在 Pencil 桌面尺寸对照三个 frame 检查字体、令牌、间距、组件数量、键盘操作和浏览器控制台错误。

每个测试方法必须用中文注释说明其业务目的和防止的回归。先确认测试因目标行为缺失而失败，再写最小实现；不为覆盖率重复验证相同行为。

## Risks / Trade-offs

- [Sa-Token 的 Spring Boot 4 starter 较新，可能与当前 4.1.0 产生自动配置差异] → 锁定 1.45.0，先做最小启动、登录、拦截器与 Jackson 3 错误序列化验证；不保留另一套认证框架兜底。
- [单实例内存会话在重启后失效] → 明确让用户重新登录，并把持久化/分布式 Sa-Token DAO 留给真实部署需求，而非 T2 预建 Redis。
- [严格 SameSite Cookie 要求前后端同站部署] → 本地由 Vite 代理，生产由同站反向代理承载；部署文档写明该前提并验证 Cookie 属性。
- [固定共享账号不支持个人审计] → 审计只能区分管理员和共享成员，这是需求明确的 MVP 边界；不得将共享成员显示成具体个人。
- [停用规则若只在 Controller 处理，后续检索可能绕过] → 普通查询仓储/应用端口直接限定启用状态，后续能力必须复用该端口。
- [设计稿样例可能被误解为后端真实数据] → 仅对没有对应接口的后续能力保留前端静态样例，并与 API 模型、权限和写操作隔离；已有接口字段始终以响应为准，后续接入接口时集中替换样例来源。
- [演示准备器被误用于生产] → profile 与显式开关双重控制，生产默认关闭且自动化测试保护。

## Migration Plan

1. 在实现前核验并锁定 Sa-Token、BCrypt 模块、Vue Router 与字体资产的版本、许可证、传递依赖和 Spring Boot/Vue 兼容性。
2. 追加 V2 Flyway 迁移并先在真实 PostgreSQL 验证空库、T1 已迁移数据库和重复迁移；绝不修改 V1。
3. 部署前配置两个固定账号的 BCrypt 哈希和 MCP Token SHA-256 摘要；生产缺失时后端保持未就绪。
4. 先部署后端契约与认证边界，再部署前端路由和页面；旧状态页不再作为业务入口，但存活/就绪 API 保持兼容。
5. 在演示环境显式运行幂等数据准备器，核对两个项目和网络设计工具的两个分支。
6. 回滚应用版本时保留新增表和数据，因为迁移为追加式且 T1 不读取这些表；不执行破坏性降级。回滚或重启会使内存 Web 会话失效，用户需要重新登录。
