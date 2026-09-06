# 设计

## 事件投影

`AgentEvent.Payload.curation` 只出现在 `AGENT_STAGE` 事件中，字段为动作、问题类型、草稿状态、审查结论、稳定来源/草稿/发现 ID 和专家调用名。服务端在落库前校验白名单、文本长度和列表上限，旧 Payload 缺少该字段时按 null 读取。

Executor 在节点结构化结果通过现有解析/路由校验后构造投影；解析失败不生成投影，继续交由现有修复或恢复路径处理。工作区与修订的真实写入事实仍以业务快照为准。

## 评估采集

`AtlasCurationEvalRunner` 读取任务快照中的 `AGENT_STAGE`、Tool 安全摘要、运行计数和工作区，按 `runId + sequence` 建立 `GraphTrace`。Token 缺失时保留“未知”，不把缺失用量伪装为零。报告增加系统变体、模型/Skill/Agent Spec 摘要、Graph 版本和固定种子指纹。

## 兼容与安全

旧 Java 构造器保留，旧 JSON 缺失新增字段可以反序列化。评估器不依赖内部 Checkpoint，不将投影用于页面正文展示。数据集中的 `null issueType` 在加载后归一为 `NONE`；人工复核门禁单独记录，开发验证和正式评估不混淆。
