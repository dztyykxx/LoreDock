## 1. 框架边界与失败测试

- [x] 1.1 复核锁定的 Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.3 本地源码和官方资料，记录 Skill Tool 披露、Hook/Interceptor、Tool 异常、Agent Spec 与 Checkpoint 的原生能力和唯一保留缺口
- [x] 1.2 为生产知识整理装配增加失败测试，证明 Skill 激活前不暴露业务 Tool、激活后只加入服务端分组 Tool，且实际执行定义与 run 摘要一致
- [x] 1.3 为项目问答增加失败测试，证明业务 Tool 第一次失败后模型调用立即停止并保留原业务错误
- [x] 1.4 为知识整理增加失败测试，证明框架限制记录真实模型/Tool 次数，暂停使用真实 Checkpoint，重启恢复不会被短运行恢复器终结

## 2. 知识整理框架原生装配

- [x] 2.1 将 Skill Registry、独立 Hook、标准 Tool Provider/Resolver 和 Task Tool 收敛为每 run 框架装配，使用 groupedTools 渐进披露并删除未使用单例 Hook/空 HITL
- [x] 2.2 让定义加载返回与摘要一致的框架 Registry/Agent Spec 结果，保留未知 Tool 确定性预检并消除执行前二次 autoReload
- [x] 2.3 为知识整理接入模型/Tool 调用限制、总超时、立即失败处理和真实调用统计，保持 ToolContext、草稿来源和正式发布边界
- [x] 2.4 使用真实 `InterruptionHook + PostgresSaver + threadId` 验证安全步骤暂停和同 run 恢复，修正不可用模型或调度失败时的稳定终态

## 3. 项目问答运行时收敛

- [x] 3.1 将 project_qa Skill 改由框架 classpath Registry/Hook 加载，并通过标准 Tool Provider/Resolver 提供 `knowledge_search`
- [x] 3.2 把项目问答执行迁移为直接 `ReactAgent` 调用和框架 Interceptor，保留流式正文、业务结果解析、证据台账、引用校验和 stdout 边界
- [x] 3.3 配置 Tool 异常立即传播并删除失败补偿 Ledger、ChatModel 包装和通用 `AgentRuntime` 转发接口
- [x] 3.4 删除 `AgentDefinitionProvider`、classpath Provider、正则 Skill Validator 及其过程模型/测试，更新架构约束和受影响 Fake Model 测试

## 4. 重启语义、验证与文档

- [x] 4.1 将启动恢复按任务类型分流：只终结不可恢复 `project_qa`，知识整理依据真实 Checkpoint 投影为可恢复等待或真实中断失败
- [x] 4.2 使用 Java 21 运行 Agent 单元测试、真实 PostgreSQL 集成测试、Web QA/架构受影响回归和 OpenSpec strict validation
- [x] 4.3 同步主 OpenSpec、MVP 开发计划、框架核验与架构文档，检查删除项、注释、日志、敏感信息和完整 Git diff
