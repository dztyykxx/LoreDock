## Why

知识挖掘和冲突整理不是一次性“让模型输出一篇 Markdown”的生成任务。一次全量输出难以证明哪些段落实际被修改、无法安全处理并发或恢复，也会让审批退化为重新阅读全文。与此同时，系统定时触发的整理任务如果只表现为后台 Job，用户无法像对话一样查看过程、暂停、补充方向或在完成后继续调整。

本变更依据产品需求 v1.3 的 `FR-KG-11～15`、`FR-AGENT-17～20` 和开发计划 T6B/T8，把知识任务定义为系统或管理员触发的长期会话，并把草稿定义为通过安全 Tool 增量修改、按修订和 Diff 审批的独立产物。需求确定阶段已经对照项目锁定版本确认 Spring AI Alibaba 提供 Skill Registry/Hook、Agent Spec Loader、Task/Agent Tool、ReactAgent Hook、PostgresSaver、Graph interrupt 和 Human-in-the-Loop；本变更不重复实现这些通用运行时能力。

## What Changes

- 手动和定时整理统一创建知识任务会话；系统触发范围和目标作为首条消息，Agent 运行、用户指导、公开过程和草稿修订按会话串联。
- 直接配置 Spring AI Alibaba `FileSystemSkillRegistry`/`SkillsAgentHook`、`AgentSpecLoader`/`TaskToolsBuilder`、`ToolCallback`/`ToolCallbackResolver`、`PostgresSaver` 和 Human-in-the-Loop；LoreDock 只实现平台业务 Tool、安全范围和业务持久化。
- 会话与 Agent run 分离：首次触发创建 run，暂停后的指导恢复当前 run，一轮正常完成后的追加调整创建新 run；各 run 保留独立状态、用量和错误。
- 增加安全暂停协议：运行先进入 `PAUSE_REQUESTED`，在当前模型或 Tool 步骤结束并提交 Checkpoint 后进入 `WAITING_FOR_USER`；用户指导写入会话后恢复。
- 草稿创建、读取和修改必须通过版本化 Tool。`draft_update` 使用基础修订号、幂等键、结构化区块操作和来源，成功后形成不可变修订。
- 服务端生成正式文档/空基线到当前修订，以及任意两个草稿修订之间的 Markdown Diff；管理员审核明确修订后发布。
- 一轮 Agent run 完成后会话保持可继续；用户追加意见创建新 run，从当前草稿修订继续修改，不覆盖旧消息、事件或修订。
- 非目标：不建设通用 Agent Runtime、Skill/Agent Spec Loader、Tool Registry 框架、子 Agent 调度器、Checkpoint/Human-in-the-Loop，不建设逐 hunk 合并器、Git 式草稿分支、多人实时协同编辑、模型流中途续传或任意脚本式文件修改。

## Capabilities

### New Capabilities

- `knowledge-curation-agent`：对话式知识任务、安全暂停、版本化草稿更新、Diff 审批和完成后继续调整。

### Modified Capabilities

无。

## Impact

- 后端：知识任务会话/消息、Agent run 关联、框架暂停状态投影、草稿修订、来源关联、Diff 查询和发布修订锁定。
- Agent 接入：使用 Spring AI Alibaba 原生 Skill、Agent Spec、Task/Agent Tool、Hook、PostgresSaver 和 Human-in-the-Loop，新增 `knowledge_document_read`、`draft_create`、`draft_read`、`draft_update`、`draft_diff` 等 LoreDock ToolCallback；不增加自研 Agent 平台层。
- 前端：对话与产物双栏、运行过程、暂停/恢复、修订列表、Markdown Diff 和完成后继续输入。
- 安全：模型不能以最终消息或未知文件 Tool 覆盖草稿；所有写入继续受项目范围、修订冲突、来源和人工发布边界约束。
