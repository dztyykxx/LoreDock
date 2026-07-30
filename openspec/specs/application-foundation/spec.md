# application-foundation Specification

## Purpose

定义 LoreDock 后端、前端与数据库在开发和部署环境中的共同运行基线，使后续能力能够依赖稳定的版本、启动、健康检查、迁移和测试入口，而无需各自重复建设工程基础。

## Requirements

### Requirement: 工程运行时和依赖版本必须可复现
项目 SHALL 使用 Java 21 后端和 Vue 3、TypeScript、Vite 前端，并在版本控制文件中锁定 Spring Boot、Spring AI、pgvector、Lucene、Node.js 与包管理器的实际版本。生产依赖 MUST 使用正式发布版本，不得使用动态范围、SNAPSHOT 或 Milestone。

#### Scenario: 使用声明的工具链构建
- **WHEN** 开发者在干净环境中使用仓库声明的 Java、Node.js 和包管理器版本执行构建
- **THEN** 后端与前端依赖解析结果可复现，且构建不依赖开发者机器上的未声明全局工具

#### Scenario: 工具链版本不兼容
- **WHEN** 开发者使用不满足仓库版本约束的 Java 或 Node.js 运行构建
- **THEN** 构建在执行编译或测试前失败，并指出不兼容的工具版本

### Requirement: 应用栈必须可统一启动和检查
项目 SHALL 提供文档化的本地开发统一启动入口：PostgreSQL/pgvector 和后续中间件通过 Docker Compose 运行，后端与前端作为宿主机进程运行，不要求本地开发容器镜像。后端 SHALL 提供不暴露敏感配置的存活与就绪检查；数据库不可连接或迁移未成功时，就绪检查 MUST 不得报告成功。

#### Scenario: 空环境首次启动
- **WHEN** 开发者配置必需的非敏感本地参数并执行统一开发启动命令
- **THEN** Compose 数据库与宿主机前后端均可访问，后端存活与就绪检查成功

#### Scenario: 数据库不可用
- **WHEN** 后端进程运行但 PostgreSQL 不可连接
- **THEN** 存活检查仍能反映进程状态，就绪检查报告不可用且不泄露连接凭据

### Requirement: 数据库迁移必须可重复执行
系统 SHALL 在应用启动时按顺序执行版本化数据库迁移，并启用 pgvector 扩展。对同一数据库重复启动 MUST 不重复应用已成功迁移；校验和不匹配或迁移失败时应用 MUST 拒绝进入就绪状态，并保留可诊断错误。

#### Scenario: 初始化空数据库
- **WHEN** 应用连接到空的受支持 PostgreSQL 数据库
- **THEN** 系统创建迁移历史、启用 pgvector 并建立本变更所需基础表

#### Scenario: 对已迁移数据库再次启动
- **WHEN** 应用连接到所有迁移均已成功执行的数据库
- **THEN** 迁移过程不修改既有迁移记录，应用正常进入就绪状态

#### Scenario: 已执行迁移被修改
- **WHEN** 已记录的迁移文件校验和与当前文件不一致
- **THEN** 应用拒绝进入就绪状态并记录迁移校验失败，但不自动篡改迁移历史

### Requirement: 持久化映射必须显式且与迁移一致
后端 SHALL 使用 MyBatis-Plus 访问业务数据库。每个持久化实体 MUST 显式声明表名、主键和所有持久化字段的列映射，不得依赖驼峰转换等隐式命名推断；实体 SHALL 优先使用 Lombok 生成机械访问器。SQL SHALL 优先由 `BaseMapper`、Wrapper 等 Java API 表达，仅在 Java API 无法清楚表达时使用 Mapper 方法注解 SQL，并且 MUST NOT 使用 XML Mapper。领域对象和 HTTP DTO MAY 保持不可变并使用 Java `record`，不得为了复用持久化注解而与数据库实体强制合并。Flyway MUST 继续作为数据库结构的唯一变更入口，MyBatis-Plus 或其他运行时组件不得自动创建或更新表结构。

#### Scenario: 映射实体读写基础表
- **WHEN** 应用通过 MyBatis-Plus 对 `stored_object` 或 `background_job` 执行读写
- **THEN** 表、主键和每个字段均由显式注解映射到 Flyway 创建的对应列，UTC 时间和状态值往返后语义不变

#### Scenario: 数据库实体字段缺少显式映射
- **WHEN** 持久化实体新增字段但没有声明对应列注解
- **THEN** 映射约束测试失败，阻止依赖默认命名规则的实体进入构建结果

#### Scenario: 持久化实现引入 XML Mapper
- **WHEN** 后端资源或 Mapper 配置中出现 XML SQL 映射
- **THEN** 持久化约束检查失败，要求改用 MyBatis-Plus Java API 或 Mapper 方法注解

#### Scenario: 应用在空库启动
- **WHEN** MyBatis-Plus 已启用但 Flyway 迁移尚未成功完成
- **THEN** 应用不得由 MyBatis-Plus 自动创建或修改业务表，也不得绕过迁移失败进入就绪状态

### Requirement: 工程必须提供分层测试入口
项目 SHALL 提供可独立执行的后端单元测试、真实 PostgreSQL 集成测试和前端测试命令。集成测试 MUST 使用隔离数据库并执行与生产相同的迁移，不得依赖开发者预先准备的共享数据库。

#### Scenario: 在干净检出上运行验证
- **WHEN** 开发者执行仓库文档规定的验证命令
- **THEN** 单元测试、数据库集成测试和前端测试可重复运行，测试数据不会写入开发或生产数据库
