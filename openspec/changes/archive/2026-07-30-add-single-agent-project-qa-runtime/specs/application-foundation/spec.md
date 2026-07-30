## MODIFIED Requirements

### Requirement: 工程运行时和依赖版本必须可复现
项目 SHALL 使用 Java 21 后端和 Vue 3、TypeScript、Vite 前端，并在版本控制文件中锁定 Spring Boot、Spring AI、Spring AI Alibaba、pgvector、Lucene、Node.js 与包管理器的实际版本。后端 Agent 基线 MUST 使用经隔离兼容性验证的 Spring Boot 3.5.x、Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.x 正式发布版本；实际补丁版本及 BOM 组合 MUST 固定且记录依赖树，不得残留 Spring Boot 4.x、Spring AI 2.0.x、Boot 4 专用 Starter，或同时解析两个不兼容版本线。生产依赖 MUST 使用正式发布版本，不得使用动态范围、SNAPSHOT 或 Milestone。

#### Scenario: 使用声明的工具链构建
- **WHEN** 开发者在干净环境中使用仓库声明的 Java、Node.js 和包管理器版本执行构建
- **THEN** 后端与前端依赖解析结果可复现，且构建不依赖开发者机器上的未声明全局工具

#### Scenario: 工具链版本不兼容
- **WHEN** 开发者使用不满足仓库版本约束的 Java 或 Node.js 运行构建
- **THEN** 构建在执行编译或测试前失败，并指出不兼容的工具版本

#### Scenario: Agent 依赖基线通过兼容性门禁
- **WHEN** 在隔离 PoC 中解析锁定的后端 BOM 与 Starter 并验证模型调用、流式输出和工具调用
- **THEN** 依赖树只包含选定的 Spring Boot 3.5.x、Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.x 正式版本线，验证结果记录实际补丁版本、必要排除项和核心 Agent API 差异；依赖安全审计不属于本次内网 MVP 范围

#### Scenario: 不兼容 Spring 版本混入构建
- **WHEN** 直接或传递依赖重新引入 Spring Boot 4.x、Spring AI 2.0.x、Boot 4 专用 Starter、SNAPSHOT 或 Milestone
- **THEN** 自动化依赖收敛检查失败，并在应用编译或启动验证前指出冲突构件

#### Scenario: 迁移后的既有能力回归
- **WHEN** 后端完成 Agent 依赖基线迁移
- **THEN** T1～T5 的单元测试、Web 契约测试、真实 PostgreSQL 集成测试、Flyway 迁移和应用启动检查继续通过，认证、项目/分支隔离、文档生命周期、代码检索与知识混合检索的既有契约不改变
