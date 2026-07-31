# backend-module-architecture Specification

## Purpose

约束 LoreDock 后端以业务功能为第一组织维度，并在每个功能模块内部使用统一、可检查的 MVC 结构，使新增核心功能能够沿最短依赖路径实现，同时保留真实的模型、Agent 和外部基础设施替换边界。

## Requirements

### Requirement: 业务代码必须按功能模块和 MVC 职责组织
后端生产代码 SHALL 先按业务功能模块划分，并固定使用 `Controller → Service → Mapper → PostgreSQL` 主链路。`controller` MUST 只放 HTTP 入口；`service` MUST 只放业务服务接口、实现和业务流程；`mapper` MUST 只放 MyBatis-Plus Mapper 接口。所有数据载体 MUST 按语义归入 `model/entity|request|response|command|result|tool|snapshot|enums`；配置、业务异常、调度、复杂转换和 Agent Skill MAY 分别进入 `config`、`exception`、`scheduler`、`converter` 和 `skill`。不得继续使用 `application`、`domain`、`infrastructure`、`usecase`、`port`、`gateway`、`repository` 或 `adapter` 作为业务模块内的结构层，也不得为没有实际职责的层级创建占位类。

#### Scenario: 新增模块内业务接口
- **WHEN** 开发者为既有功能模块新增一个 HTTP 业务能力
- **THEN** 请求入口、业务流程、MyBatis 访问和数据模型分别落入该模块的 `controller`、`service`、`mapper` 和对应 `model` 子包，且不新增旧分层目录或仅用于转发的中间层

#### Scenario: 数据载体被放入 Service 或 Mapper
- **WHEN** Entity、Request、Response、Command、Result、Tool 请求、Snapshot、Record 或枚举出现在 `service` 或 `mapper` 包
- **THEN** 自动化架构检查失败并指出其应迁移到的 `model` 子包；私有内部 record 不受此限制

#### Scenario: 功能模块没有数据库访问
- **WHEN** 某功能模块只需要 Controller 和 Service 而不需要持久化
- **THEN** 模块不创建空 Mapper、Repository 或占位实现，现有代码仍符合 MVC 职责检查

#### Scenario: 旧分层重新进入生产代码
- **WHEN** 后端生产代码新增或残留被禁止的结构包，或将业务类放入无法归属的通用目录
- **THEN** 自动化架构检查失败并指出违规包或类

### Requirement: MVC 层之间必须遵循最短依赖路径
Controller SHALL 只调用本模块 Service 或统一 Web 基础能力，不得直接访问 Mapper。Service SHALL 承载业务规则、实体转换和事务边界，并 MAY 调用本模块 Mapper；跨模块协作 MUST 通过被调用模块的 `api` 契约包（Service 接口与最小稳定契约类型），Service MUST NOT 直接依赖其他模块 Mapper、Service 实现、Entity、内部 DTO 或过程模型。Mapper SHALL 只表达持久化访问，不得包含 Entity、Repository 实现、Service、DTO、record 或 Converter；HTTP 请求/响应模型 MUST NOT 作为 Mapper 输入输出。跨模块调用 MUST NOT 形成循环依赖。

#### Scenario: Controller 执行业务操作
- **WHEN** Controller 接收并校验一个合法请求
- **THEN** Controller 调用 Service 完成业务操作并转换响应，不直接拼接 SQL、操作 Mapper 或复制业务规则

#### Scenario: 一个模块需要另一个模块的数据
- **WHEN** Agent 模块需要查询知识或代码证据
- **THEN** Agent Service 只通过 Knowledge 或 Code 模块 `api` 包中的稳定契约调用其能力，不越过模块边界访问其 Mapper、Service 实现或内部模型

#### Scenario: 模块依赖形成闭环
- **WHEN** 新增依赖导致两个或多个功能模块的 Service 相互循环调用
- **THEN** 自动化架构检查失败，并要求将调用方向或共同职责调整为无环结构

### Requirement: 接口必须对应真实替换边界
系统 SHALL 保留 Agent Runtime、Agent 定义加载、聊天模型、Embedding 模型和外部存储等真实可替换边界，并优先复用依赖框架已经定义的稳定接口。仅有单一固定实现且只进行转发、DTO 搬运或 Mapper 包装的业务接口 MUST NOT 保留；删除接口不得改变既有 HTTP 契约、权限、范围隔离或错误语义。

#### Scenario: 更换聊天或 Embedding 模型
- **WHEN** 部署配置选择另一种兼容模型实现
- **THEN** 业务 Controller 和核心业务 Service 无需修改即可使用新模型实现

#### Scenario: Agent Runtime 被业务能力调用
- **WHEN** 项目问答或后续 Agent 任务启动一次运行
- **THEN** 调用方依赖稳定的 Agent Runtime 契约，不直接依赖具体 Agent 框架的执行类型

#### Scenario: 发现单实现转发接口
- **WHEN** 一个接口只有固定实现且接口本身不隔离外部系统、可替换算法或业务能力
- **THEN** 重构将调用方直接收敛到具体 Service 或 Mapper，并由相关行为测试证明外部结果不变

### Requirement: 跨模块公共代码必须保持最小范围
只有确实被多个功能模块复用且没有业务归属的配置、Web 错误处理、时间和持久化基础支持 MAY 放入公共模块。业务规则、业务 DTO、模型选择规则和无法命名的辅助代码 MUST NOT 进入 `common`、`platform` 或 `util` 形成新的杂物层。

#### Scenario: 多个模块复用统一错误响应
- **WHEN** 不同 Controller 返回已知业务错误
- **THEN** 它们复用公共 Web 错误处理能力，错误响应语义保持一致

#### Scenario: 业务辅助逻辑试图进入公共包
- **WHEN** 仅服务于知识、代码或 Agent 模块的类被放入公共目录
- **THEN** 自动化架构检查或代码审查要求其返回所属功能模块

### Requirement: 跨模块协作必须通过所属模块的 api 契约包
只有被其他模块调用的模块 SHALL 创建 `<module>.api` 契约包；`api` MUST 只保存 Service 接口、接口必需的最小不可变输入/输出类型和少量稳定枚举，不得包含 Controller、Service 实现、Mapper、Entity、配置、调度器或内部过程模型。跨模块代码 MUST 只引用对方模块的 `api` 包，禁止引用对方 `service`、`mapper`、`model/entity`、内部 DTO、Snapshot、Command、Result 或实现异常。接口命名直接使用 `XxxService`，实现类使用 `XxxServiceImpl`，不使用 `I` 前缀风格；HTTP DTO 只有在语义完全相同时才复用 `api` 契约类型。没有跨模块调用的模块 MUST NOT 创建空 `api` 包；每个 `api` 类型都必须能说明稳定性价值，删除仅为一次转换创建的契约类。

#### Scenario: 模块提供稳定契约给其他模块
- **WHEN** `project`、`knowledge`、`code`、`agent`、`qa`、`job`、`storage` 被其他模块跨模块调用
- **THEN** 调用只指向这些模块 `api` 包中的 Service 接口与最小契约类型，接口实现仍位于模块内部 `service`，`api` 不成为新的 HTTP 层或业务层

#### Scenario: 跨模块引用非 api 内容
- **WHEN** 某模块代码 import 其他模块的 `service` 实现、`mapper`、`model/entity`、内部 DTO 或过程模型
- **THEN** 自动化架构检查失败并列出具体文件与应迁移到的 `api` 契约

#### Scenario: 没有调用方的模块创建空 api
- **WHEN** 某模块当前没有其他业务模块调用其能力
- **THEN** 不创建空 `api` 包；该模块内部 Service 保持具体类，出现真实跨模块调用后再建立契约

#### Scenario: api 中放入实现细节
- **WHEN** `api` 包中出现 Controller、Service 实现、Mapper、Entity、配置、调度器或仅转发一次的过程模型
- **THEN** 自动化架构检查失败并要求把这些内容移回模块内部，只保留稳定接口与最小契约类型

#### Scenario: 模块依赖形成闭环
- **WHEN** 新增依赖导致两个或多个功能模块的 Service 相互循环调用
- **THEN** 自动化架构检查失败，并要求将调用方向或共同职责调整为无环结构
