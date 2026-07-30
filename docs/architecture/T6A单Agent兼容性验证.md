# T6A 单 Agent 兼容性验证

本文记录 T6A 在修改主应用之前完成的隔离 PoC。可验收行为以 OpenSpec change `add-single-agent-project-qa-runtime` 为准；依赖许可证、CVE 与供应链安全审计不属于本次内网答辩 MVP 范围。

## 1. 固定版本

PoC 位于 `backend/poc/agent-compatibility/`，不属于主应用 Maven 模块，也不会进入生产 fat-jar。最终候选为 Java 21、Spring Boot 3.5.16、Spring AI 1.1.2、Spring AI Alibaba Agent Framework 1.1.2.3、MyBatis-Plus Boot 3 Starter 3.5.17、Sa-Token Boot 3 Starter 1.45.0 和 Flyway 11.7.2。

Spring AI 与 Spring AI Alibaba 分别通过 BOM 固定。Agent Framework 的旧 MCP 0.14 传递依赖与 Spring AI 1.1.2 使用的 MCP JSON 0.17 冲突，T6A 本身不启用 MCP，因此排除旧 `io.modelcontextprotocol.sdk:mcp`。同时排除 T6A 不使用的 A2A Client 和已经证明核心路径不需要的 fastjson 1.x。JetBrains annotations 固定为 24.0.1 以消除框架与 flexmark 的双版本。

Agent Framework 的默认 Jackson 状态序列化器会直接链接 Spring AI DeepSeek 与 ZhiPuAI 消息类型，因此这两个模型 API jar 不能从框架依赖中裁剪；主应用仍只配置 OpenAI 兼容模型 Starter，不创建对应模型客户端或自动配置。

## 2. 真实 API 差异

- `ReactAgent.call` 返回 `AssistantMessage`，`streamMessages` 返回 `Flux<Message>`；同步调用和流式调用都由相同图执行。
- `ReactAgent` 默认走流式模型路径，Fake `ChatModel` 必须同时实现 `call(Prompt)` 与 `stream(Prompt)`。
- 固定 Skill 内容可通过每运行 Agent 的 `instruction` 注入，并能在模型收到的 Prompt 中观察到；T6A 不启用框架文件系统 Skill 扫描。
- `ModelCallLimitHook` 在下一次模型调用前直接抛出 `ModelCallLimitExceededException`，不是包装异常。
- 外层 Reactor `timeout` 会取消尚未交付的模型流；PoC 观察到取消计数为 1、迟到答案交付数为 0。
- `ReactAgent` 会把普通默认 ChatOptions 转换为禁用内部工具执行的 `ToolCallingChatOptions`，测试日志中的警告不影响受控工具循环。

## 3. 测试证据

在 Homebrew OpenJDK 21.0.12 下执行：

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./mvnw -f poc/agent-compatibility/pom.xml test
```

实际通过 6 个测试，覆盖 fastjson 类路径缺失、Agent/Hook/ToolCallback 类链接、固定 Skill Prompt 注入、单次白名单工具循环、结构化 ANSWER 与引用、流式消息、第二次模型调用前的 Hook 限制，以及超时取消和迟到结果丢弃。关键证据为：受控工具循环调用模型 2 次、工具查询恰为“场景包 刷新拓扑”；调用上限场景只调用模型 1 次；超时场景交付 0 个答案并收到 1 次取消信号。

Maven Enforcer 的 Java 版本、正式依赖、依赖收敛和禁用依赖规则全部通过。运行依赖树只包含 Spring Boot 3.5.16、Spring Framework 6.2.19、Spring AI 1.1.2 与 Spring AI Alibaba 1.1.2.3；没有 Spring Boot 4、Spring AI 2、Boot 4 Starter、SNAPSHOT/Milestone、A2A Client、DashScope/Studio/Nacos Starter 或 fastjson 1.x。

## 4. 迁移结论

候选版本可以在 Java 21 上驱动真实 `ReactAgent` 完成 T6A 所需核心路径，依赖冲突已有明确排除或版本固定方案。主应用可以按相同版本与排除项迁移到 Boot 3，并在 Agent 默认关闭、无模型密钥时先验证 T1～T5 回归，再实现运行事实、受控工具与 `project_qa` 用例。
