## MODIFIED Requirements

### Requirement: MVC 层之间必须遵循最短依赖路径
Controller SHALL 只调用本模块 Service 或统一 Web 基础能力，不得直接访问 Mapper。Service SHALL 承载业务规则、实体转换和事务边界，并 MAY 调用本模块 Mapper；跨模块协作 MUST 通过被调用模块的 `api` 契约包（Service 接口与最小稳定契约类型），Service MUST NOT 直接依赖其他模块 Mapper、Service 实现、Entity、内部 DTO 或过程模型。Mapper SHALL 只表达持久化访问，不得包含 Entity、Repository 实现、Service、DTO、record 或 Converter；HTTP 请求/响应模型 MUST NOT 作为 Mapper 输入输出。跨模块调用 MUST NOT 形成循环依赖。

#### Scenario: 一个模块需要另一个模块的数据
- **WHEN** Agent 模块需要查询知识或代码证据
- **THEN** Agent Service 只通过 Knowledge 或 Code 模块 `api` 包中的稳定契约调用其能力，不越过模块边界访问其 Mapper、Service 实现或内部模型

## ADDED Requirements

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
