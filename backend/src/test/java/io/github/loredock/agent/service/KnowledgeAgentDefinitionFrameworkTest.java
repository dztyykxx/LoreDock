package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecReactAgentFactory;
import com.alibaba.cloud.ai.graph.agent.tools.task.TaskToolsBuilder;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/** 验证知识整理直接使用项目锁定版本的 Skill、Agent Spec 与子 Agent 组件。 */
class KnowledgeAgentDefinitionFrameworkTest {

    @TempDir
    private Path temporaryDirectory;

    /**
     * 业务目的：新 run 必须重新读取已经修改的本地 Skill 与 Agent Spec，同时仍由框架创建子 Agent Tool；
     * 防止长驻进程把旧定义永久缓存，或项目另写一套定义加载器。
     */
    @Test
    void newAssemblyReloadsSkillAndAgentSpecThroughFrameworkComponents() throws Exception {
        Path emptyUserSkills = Files.createDirectories(temporaryDirectory.resolve("user-skills"));
        Path projectSkills = Files.createDirectories(temporaryDirectory.resolve("project-skills"));
        Path skillFile = writeSkill(projectSkills, "第一版：先读取草稿");
        Path specs = Files.createDirectories(temporaryDirectory.resolve("agent-specs"));
        Path specFile = writeAgentSpec(specs, "第一版：只核对来源", "knowledge_read");
        ToolCallback knowledgeRead = tool("knowledge_read");
        ChatModel model = new DefinitionOnlyChatModel();
        FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(emptyUserSkills.toString())
                .projectSkillsDirectory(projectSkills.toString())
                .build();
        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .autoReload(true)
                .build();

        String firstSkill = registry.readSkillContent("knowledge_curator");
        AgentSpec firstSpec = AgentSpecLoader.loadFromDirectory(specs).getFirst();

        Files.writeString(skillFile, skillMarkdown("第二版：先读取草稿再增量修改"));
        Files.writeString(specFile, agentSpecMarkdown("第二版：核对适用版本", "knowledge_read"));
        registry.reload();
        AgentSpec secondSpec = AgentSpecLoader.loadFromDirectory(specs).getFirst();
        AgentSpecReactAgentFactory factory = AgentSpecReactAgentFactory.builder()
                .chatModel(model)
                .defaultTools(knowledgeRead)
                .build();
        ReactAgent child = factory.create(secondSpec);
        List<ToolCallback> delegationTools = TaskToolsBuilder.builder()
                .chatModel(model)
                .defaultTools(knowledgeRead)
                .addAgentDirectory(specs.toString())
                .build();

        assertThat(hook.hasSkill("knowledge_curator")).isTrue();
        assertThat(firstSkill).contains("第一版");
        assertThat(registry.readSkillContent("knowledge_curator")).contains("第二版");
        assertThat(firstSpec.systemPrompt()).contains("第一版");
        assertThat(secondSpec.systemPrompt()).contains("第二版");
        assertThat(child.name()).isEqualTo("source_reviewer");
        assertThat(delegationTools).extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrder("Task", "TaskOutput");
        System.out.println("测试证据：场景=定义热加载，Skill版本=第二版，AgentSpec版本=第二版，框架委派Tool=2");
    }

    /**
     * 业务目的：Agent Spec 中的未知或越权 Tool 必须在模型调用前被完整识别，
     * 防止依赖框架静默过滤后以缺少能力的 Agent 继续运行。
     */
    @Test
    void precheckCanRejectUnknownToolBeforeFrameworkSilentlyFiltersIt() throws Exception {
        Path specs = Files.createDirectories(temporaryDirectory.resolve("unsafe-agent-specs"));
        writeAgentSpec(specs, "核对来源", "knowledge_read, shell");
        AgentSpec spec = AgentSpecLoader.loadFromDirectory(specs).getFirst();
        List<String> allowedTools = List.of("knowledge_read", "draft_read", "draft_update");

        List<String> unknownTools = spec.toolNames().stream()
                .filter(name -> !allowedTools.contains(name))
                .sorted()
                .toList();

        assertThat(unknownTools).containsExactly("shell");
        System.out.println("测试证据：场景=Agent Spec Tool预检，声明Tool=2，未知Tool=[shell]，模型调用=0");
    }

    /**
     * 业务目的：协调 Agent 必须由框架 ReactAgent 真实调用模型，并携带框架生成的 Task/TaskOutput Tool 与稳定 threadId；
     * 防止只完成定义扫描却仍由项目代码手写模型循环或子 Agent 调度。
     */
    @Test
    void reactAgentExecutesWithFrameworkTaskToolsAndStableThread() throws Exception {
        Path specs = Files.createDirectories(temporaryDirectory.resolve("runtime-agent-specs"));
        writeAgentSpec(specs, "核对来源后返回结论", "knowledge_read");
        ToolCallback knowledgeRead = tool("knowledge_read");
        ChatModel model = new FinalAnswerChatModel();
        List<ToolCallback> taskTools = TaskToolsBuilder.builder()
                .chatModel(model).defaultTools(knowledgeRead).addAgentDirectory(specs.toString()).build();
        String threadId = "knowledge-task-test-run-1";
        ReactAgent coordinator = ReactAgent.builder()
                .name("knowledge-curation-test")
                .instruction("按 Skill 整理草稿")
                .model(model)
                .tools(taskTools)
                .saver(new MemorySaver())
                .releaseThread(false)
                .parallelToolExecution(false)
                .build();

        AssistantMessage result = coordinator.call(
                "整理 Atlas 项目知识", RunnableConfig.builder().threadId(threadId).build());

        assertThat(taskTools).extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrder("Task", "TaskOutput");
        assertThat(result.getText()).isEqualTo("已完成知识整理");
        System.out.printf("测试证据：场景=框架协调执行，threadId=%s，TaskTool=2，模型最终响应=%s%n",
                threadId, result.getText());
    }

    private Path writeSkill(Path root, String instruction) throws Exception {
        Path directory = Files.createDirectories(root.resolve("knowledge_curator"));
        Path file = directory.resolve("SKILL.md");
        Files.writeString(file, skillMarkdown(instruction));
        return file;
    }

    private Path writeAgentSpec(Path root, String instruction, String tools) throws Exception {
        Path file = root.resolve("source-reviewer.md");
        Files.writeString(file, agentSpecMarkdown(instruction, tools));
        return file;
    }

    private String skillMarkdown(String instruction) {
        return """
                ---
                name: knowledge_curator
                description: 整理项目知识草稿
                ---
                # 知识整理
                %s
                """.formatted(instruction);
    }

    private String agentSpecMarkdown(String instruction, String tools) {
        return """
                ---
                name: source_reviewer
                description: 核对知识来源
                tools: %s
                ---
                # 来源核对
                %s
                """.formatted(tools, instruction);
    }

    private ToolCallback tool(String name) {
        return FunctionToolCallback.builder(name, (EchoInput input) -> input.value())
                .description("测试工具")
                .inputType(EchoInput.class)
                .build();
    }

    private record EchoInput(String value) {
    }

    /** 只承担框架构建期默认参数读取；本测试不允许发生真实模型调用。 */
    private static final class DefinitionOnlyChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("定义装配阶段不得调用模型");
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }

    /** 确定性最终响应模型；不发起 Tool，验证真实 ReactAgent 调用边界。 */
    private static final class FinalAnswerChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已完成知识整理"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }
}
