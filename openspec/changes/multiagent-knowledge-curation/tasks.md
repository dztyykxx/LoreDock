## Tasks

- [x] 定义四份 Agent Markdown 定义（coordinator/retriever/drafter/reviewer）与唯一结构化结果契约 `KnowledgeCurationGraphResult`
- [x] 实现 `KnowledgeCurationGraphFactory`（`validate()` 白名单 fail-fast；`build()` 组装 StateGraph/条件边/状态策略/`interruptAfter`/PostgresSaver）
- [x] 让 `KnowledgeAgentDefinitionService` 启动加载并校验四份定义，`load()` 从四份定义计算 run `RuntimeDefinition`
- [x] 重写 `KnowledgeCurationRunExecutor` 驱动父 Graph（同 threadId 的 resume 循环、边界状态检查、`AGENT_STAGE` 事件、`SUB_AGENT` 消息、`COORDINATOR_AGENT` 最终回复）
- [x] 删除单 Agent `agent-skills/knowledge-curator` Skill 与 `LoadedDefinition.createSkillHook`/Skill Registry 依赖
- [x] 前端：`KnowledgeTask.events` 对齐后端 `AgentEvent` 契约；`KnowledgeTaskWorkspace` 把 `AGENT_STAGE` 投影为阶段卡片
- [x] 自动化：`KnowledgeCurationGraphAssemblyTest`（定义/白名单/组装）、`KnowledgeCurationGraphRunIT`（CHAT、DRAFT→REVIEW→PASS、返工上限）、`KnowledgeCurationRunExecutorDriveIT`（Executor 闲聊短路 + `AGENT_STAGE`）、`KnowledgeCurationGraphRoutingTest`（§9 安全规则）
- [x] 文档：FR-AGENT-10、详细流程、开发计划、OpenSpec change 同步为多 Agent 现状

## 验证命令

后端（需 JDK 21，`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`）：
```bash
./mvnw -o test -Dtest=KnowledgeCurationPersistenceIT,KnowledgeCurationRunExecutorTest,KnowledgeCurationRunExecutorDriveIT,KnowledgeCurationGraphAssemblyTest,KnowledgeCurationGraphRunIT,KnowledgeCurationGraphRoutingTest,AgentRunPersistenceIT,BackendMvcArchitectureTest
```
前端：`vue-tsc -b --noEmit`；`vitest run src/components/KnowledgeTaskWorkspace.test.ts`。
