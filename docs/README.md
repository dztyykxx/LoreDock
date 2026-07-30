# LoreDock 文档目录

## 当前产品文档

`product/` 保存当前有效的产品背景、范围和需求基线：

- `项目业务上下文知识库_MVP需求文档_v1.0.md`：当前 MVP 需求基线；
- `LoreDock_MVP功能开发计划.md`：完整 MVP 的功能任务、依赖顺序、状态和逐任务完成门禁；
- `项目业务上下文知识库_MVP_选题说明.md`：选题背景和演示闭环说明。

## 调研资料

`research/` 保存技术选型和开源项目调研。这些文档用于辅助决策，不自动成为实现要求：

- `Java技术栈调研与MVP落地建议.md`；
- `开源代码知识库项目调研_v0.1.md`。

## 架构与运维

`architecture/` 保存已实现基础能力的结构、数据模型与运行约定：

- `T1工程与基础设施.md`：T1 的本地拓扑、后端分层、数据库、对象存储、后台任务、错误/时间/审计和备份限制。
- `T4代码快照与Lucene检索.md`：T4 的 ZIP 安全边界、generation 生命周期、查询隔离、配置、恢复和备份约定。

## UI 设计稿

`UI/` 保存 LoreDock MVP 的 Pencil 设计源稿、设计说明和总览预览：

- `UI/README.md`：画布范围、视觉规范和实现约束；
- `UI/LoreDock-MVP-UI.pen`：Pencil 可编辑源稿，只能通过 Pencil 或 Pencil MCP 工具访问；
- `UI/LoreDock-design-overview.png`：核心页面总览预览。

## 历史归档

`archive/` 保存已被新版本替代的历史文档，仅用于追溯。除非当前 OpenSpec 明确引用，否则不得把归档内容作为当前实现依据。

## 规格与变更

正式、可验收的功能规格和变更工件保存在仓库根目录的 `openspec/` 中。开发过程遵循 `AGENTS.md` 规定的 SDD + TDD 工作流。
