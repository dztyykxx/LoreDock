## 1. 规格、契约与数据

- [x] 1.1 核对 Spring AI Alibaba 1.1.2.3 本地源码中的 streamMessages、ToolCallRequest/Response、Tool Interceptor 和安全中断能力，记录直接复用点与不实现的 Human-in-the-loop 场景
- [x] 1.2 更新知识任务、工作区、Tool Invocation、Patch Set、任务状态和批量发布 Java API 契约，并为关键边界补中文 Javadoc
- [x] 1.3 先增加 PostgreSQL 失败测试，覆盖多工作文档、固定基线、任务状态、Tool Invocation 聚合、完整审核集合和原子回滚
- [x] 1.4 增加 Flyway 迁移；开发数据不值得兼容时允许重建，不增加双写或旧 API 分支

## 2. 多文档工作区与 Agent 过程

- [x] 2.1 解除 current_draft_id 单草稿依赖，按 conversation_id 提供最多 10 份有效工作文档列表
- [x] 2.2 扩展 draft_create 的 ADD/MODIFY、baseline_revision 与目录校验，并增加 workspace_document_list Tool
- [x] 2.3 更新 knowledge-curator Skill：每轮恢复工作区、按稳定主题拆分、输出公开决策、问题结束当前 run
- [x] 2.4 持久化公开 AssistantMessage 并去重；禁止读取原始 reasoning 字段
- [x] 2.5 按 toolCallId 保存并更新 Tool Invocation 的脱敏限长业务输入输出
- [x] 2.6 从修订事实查询每轮 Patch Set 与累计工作区统计
- [x] 2.7 移除 finding_record 警告记录 Tool，冲突、缺口、风险和待确认问题统一进入模型最终回复
- [x] 2.8 移除 public_message，公开过程只使用原生 AssistantMessage；新 run 仅注入历史用户消息与最终回复，不注入 Tool 过程

## 3. 实时接口与任务生命周期

- [x] 3.1 定义并实现任务聚合 REST 快照和持久化 SSE 游标接口
- [x] 3.2 实现前端按实体 ID 合并 message/tool/run/patch/task 事件，刷新与断线不产生重复卡片
- [x] 3.3 将浏览器主动关闭 SSE 识别为正常客户端断连，避免异步心跳 Broken pipe 落入全局 ERROR
- [x] 3.4 实现 PROCESSING/PUBLISHED/CLOSED_NO_CHANGE/ABANDONED 与候选 PENDING/PROCESSING/CURATED 转换
- [x] 3.5 隐藏旧暂停恢复入口，实现停止本轮后 CANCELLED 且后续新建 run

## 4. Pencil 与前端页面

- [x] 4.1 更新 Pencil 07A 为全宽对话、顶部累计审核条、紧凑 Tool 卡、本轮文件变更卡和右侧 Diff 抽屉，并完成截图检查
- [x] 4.2 重构 KnowledgeTaskWorkspace 为连续时间线，区分用户、Agent、系统、Tool 和 Patch Set；风险与待确认问题由最终回复承载
- [x] 4.3 实现固定输入折叠卡、轮次分隔、输入框状态、自动滚动与新消息提示
- [x] 4.4 实现累计审核条、文档清单和按需 Unified Diff 抽屉，不引入复杂 Diff 依赖
- [x] 4.5 更新任务列表与标题，使任务状态和最新 run 状态分别呈现
- [x] 4.6 将每轮公开过程与 Tool Invocation 整体折叠，并对折叠区外的最终回复进行安全 Markdown 预览

## 5. 多文档原子发布

- [x] 5.1 在 knowledge api 定义完整审核集合与幂等发布契约，先写 ADD/MODIFY 混合失败测试
- [x] 5.2 实现 MODIFY 保持正式 document_id 并校验 baseline_revision，ADD 校验目录标题
- [x] 5.3 在一个事务内完成全部正式写入、草稿/任务/候选状态、发布记录和单个索引 Job
- [x] 5.4 实现同键同参幂等、同键异参冲突和任一冲突整批回滚
- [x] 5.5 实现确认无需变更和放弃任务终态

## 6. 验证与收口

- [x] 6.1 运行受影响后端单元与 PostgreSQL 集成测试、前端 Vitest 和构建
- [x] 6.2 运行 OpenSpec 严格校验并核对需求、Pencil、API 与实现一致
- [x] 6.3 使用真实模型完成两份输入、一份 MODIFY、一份 ADD、第二轮修改、原子发布和单索引任务烟测（2026-08-02 用户实测通过）
- [x] 6.4 检查 ToolContext、原始推理、完整敏感正文和内部错误未进入公开存储或页面
