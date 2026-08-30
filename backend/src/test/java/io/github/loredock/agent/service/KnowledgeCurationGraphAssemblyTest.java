package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.core.io.ClassPathResource;

/** 验证知识整理多 Agent 定义装载、Tool 白名单校验与 Graph 组装。 */
class KnowledgeCurationGraphAssemblyTest {

    private static final List<String> SPEC_FILES = List.of(
            "main_agent.md", "coordinator.md", "retriever.md", "drafter.md", "reviewer.md");
    private static final List<String> ALL_BUSINESS_TOOLS = List.of(
            "selected_draft_list", "selected_draft_read",
            "knowledge_directory_list", "knowledge_document_list",
            "knowledge_document_read", "knowledge_grep", "knowledge_search",
            "workspace_document_list",
            "draft_create", "draft_read", "draft_update", "draft_rename", "draft_diff");

    private final KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper(), ContextAssemblyFixtures.assembly(new ObjectMapper()));

    /**
     * 业务目的：五份 Agent 定义必须随应用产物发布并可从 classpath 加载，且五个角色齐全、名称唯一；
     * 防止 IDE、命令行与部署环境工作目录不同导致多 Agent 流程无法启动。
     */
    @Test
    void bundledAgentSpecsLoadAsFiveUniqueRoles() throws Exception {
        List<AgentSpec> specs = loadSpecs();

        assertThat(specs).extracting(AgentSpec::name)
                .containsExactly("main_agent", "coordinator", "retriever", "drafter", "reviewer");
        assertThat(specs.stream().map(AgentSpec::name).distinct().count()).isEqualTo(5);
        for (AgentSpec spec : specs) {
            assertThat(spec.systemPrompt()).isNotBlank();
            assertThat(spec.toolNames()).containsExactlyElementsOf(
                    KnowledgeCurationGraphFactory.DESIGN_TOOLS.get(spec.name()));
        }
        System.out.println("测试证据：场景=多Agent定义装载，角色数=5，白名单一致=true");
    }

    /**
     * 业务目的：定义声明了服务端不存在的 Tool 时应用必须启动失败；
     * 防止模型拿到未知 Tool 被框架静默忽略而无法完成写入。
     */
    @Test
    void validateRejectsUnknownTool() throws Exception {
        java.util.ArrayList<AgentSpec> specs = new java.util.ArrayList<>(loadSpecs());
        AgentSpec tampered = tamperTools(specs, "retriever", "not_a_real_tool");
        specs.set(retrieverIndex(), tampered);

        assertThatThrownBy(() -> factory.validate(specs, ALL_BUSINESS_TOOLS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 Tool").hasMessageContaining("not_a_real_tool");
        System.out.println("测试证据：场景=未知Tool校验，retriever声明不存在Tool，启动失败=true");
    }

    /**
     * 业务目的：Agent 定义缺失写能力或白名单与设计不一致时应用必须启动失败；
     * 防止草稿 Agent 意外持有检索范围，或审查 Agent 被放开写 Tool 造成越权写入。
     */
    @Test
    void validateRejectsWhitelistMismatch() throws Exception {
        java.util.ArrayList<AgentSpec> specs = new java.util.ArrayList<>(loadSpecs());
        // 草稿 Agent 声称自己拥有正式发布能力会被白名单校验拒绝，防止越权。
        AgentSpec drafter = specs.stream().filter(spec -> "drafter".equals(spec.name())).findFirst().orElseThrow();
        List<String> declared = drafter.toolNames();
        List<String> adjusted = declared.stream().map(name -> "draft_read".equals(name) ? "draft_diff" : name).toList();
        AgentSpec adjustedSpec = new AgentSpec(drafter.name(), drafter.description(),
                drafter.systemPrompt(), adjusted, drafter.model());
        specs.set(drafterIndex(), adjustedSpec);

        assertThatThrownBy(() -> factory.validate(specs, ALL_BUSINESS_TOOLS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单与设计不一致");
        System.out.println("测试证据：场景=白名单越界校验，drafter白名单被改动，启动失败=true");
    }

    /**
     * 业务目的：四个 ReactAgent 能按白名单接入父 Graph，并以设计状态键、合并策略、条件边和
     * PostgresSaver 编译成合法 CompiledGraph；防止图结构错误导致 Executor 无法驱动。
     */
    @Test
    void buildAssemblesGraphWithToolWhitelistsAndSaver() throws Exception {
        List<AgentSpec> specs = loadSpecs();
        factory.validate(specs, ALL_BUSINESS_TOOLS);
        Map<String, ToolCallback> callbacks = ALL_BUSINESS_TOOLS.stream()
                .collect(Collectors.toMap(name -> name, KnowledgeCurationGraphAssemblyTest::tool, (a, b) -> a, LinkedHashMap::new));
        StaticToolCallbackResolver resolver = new StaticToolCallbackResolver(List.copyOf(callbacks.values()));
        ChatModel model = Mockito.mock(ChatModel.class);
        PostgresSaver saver = Mockito.mock(PostgresSaver.class);

        KnowledgeCurationGraphFactory.GraphBundle bundle = factory.build(
                new KnowledgeCurationGraphFactory.AgentSpecSet(specs), model, resolver,
                Map.of("operatorId", "admin", "conversationId", 1L, "runId", 2L), saver,
                List.of(), List.of(), KnowledgeCurationRunExecutor.toolExceptionProcessor());

        assertThat(bundle.graph()).isNotNull();
        assertThat(bundle.agents()).containsKeys(
                "coordinator", "retriever", "drafter", "reviewer");
        assertThat(bundle.agents().get("coordinator").getOutputKey()).isEqualTo("coordinationResult");
        assertThat(bundle.agents().get("retriever").getOutputKey()).isEqualTo("retrievalResult");
        assertThat(bundle.agents().get("drafter").getOutputKey()).isEqualTo("draftResult");
        assertThat(bundle.agents().get("reviewer").getOutputKey()).isEqualTo("reviewResult");
        System.out.println("测试证据：场景=多Agent Graph组装，节点数=4，编译成功=true，输出键=4");
    }

    /** @return retriever 在五份定义中的位置（main, coord, retriever, drafter, reviewer）。 */
    private static int retrieverIndex() {
        return 2;
    }

    /** @return drafter 在五份定义中的位置。 */
    private static int drafterIndex() {
        return 3;
    }

    private List<AgentSpec> loadSpecs() throws Exception {
        return SPEC_FILES.stream().map(file -> readSpec("agent-specs/knowledge-curation/" + file)).toList();
    }

    private AgentSpec readSpec(String path) {
        try {
            AgentSpec spec = AgentSpecLoader.loadFromResource(new ClassPathResource(path));
            if (spec == null) {
                throw new IllegalStateException("Agent 定义解析失败：" + path);
            }
            return spec;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private AgentSpec tamperTools(List<AgentSpec> specs, String role, String replacement) {
        AgentSpec target = specs.stream().filter(spec -> role.equals(spec.name())).findFirst().orElseThrow();
        List<String> declared = target.toolNames();
        List<String> tampered = declared.isEmpty() ? List.of(replacement)
                : declared.stream().map(name -> name.equals(declared.get(0)) ? replacement : name).toList();
        return new AgentSpec(target.name(), target.description(), target.systemPrompt(), tampered, target.model());
    }

    private static ToolCallback tool(String name) {
        return FunctionToolCallback.builder(name, (EchoInput input) -> input.value())
                .description("测试工具").inputType(EchoInput.class).build();
    }

    private record EchoInput(String value) { }
}
