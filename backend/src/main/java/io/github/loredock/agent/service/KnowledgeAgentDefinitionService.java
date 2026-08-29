package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.service.KnowledgeCurationGraphFactory.AgentSpecSet;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 知识整理多 Agent 定义装载与校验。
 *
 * <p>启动时加载并校验四份随应用发布的 Agent Markdown 定义（coordinator/retriever/drafter/reviewer），
 * 校验角色齐全、Tool 白名单与设计一致；定义缺失、名称不唯一或声明未知 Tool 时应用启动失败
 * （不沿用框架“空列表代表全部 Tool”或“未知 Tool 静默忽略”的默认行为）。</p>
 *
 * <p>每个新 run 的 {@link RuntimeDefinition} 直接由这四份定义计算：{@code skillName} 使用运行级稳定
 * 标识（保留前端按 run 定义识别最终消息的既有契约），摘要与工具集合来自四份定义内容，不作为 Skill
 * Registry 加载。</p>
 */
@Service
public class KnowledgeAgentDefinitionService {

    /** 四份随应用发布的 Agent 定义 classpath，顺序与 {@code KnowledgeCurationGraphFactory.ROLES} 一致。 */
    private static final String GRAPH_SPEC_PREFIX = "agent-specs/knowledge-curation/";
    private static final List<String> GRAPH_SPEC_FILES = List.of(
            "coordinator.md", "retriever.md", "drafter.md", "reviewer.md");

    private final ToolCallbackProvider toolProvider;
    private final AgentProperties agentProperties;
    private final KnowledgeCurationGraphFactory graphFactory;
    private AgentSpecSet graphSpecs;

    /** @param toolProvider 标准 ToolCallbackProvider 候选集
     * @param agentProperties 固定模型描述
     * @param objectMapper 供 Graph 工厂校验/解析输出 */
    public KnowledgeAgentDefinitionService(
            ToolCallbackProvider toolProvider,
            AgentProperties agentProperties,
            ObjectMapper objectMapper
    ) {
        this.toolProvider = toolProvider;
        this.agentProperties = agentProperties;
        this.graphFactory = new KnowledgeCurationGraphFactory(objectMapper);
    }

    /** 启动时加载并校验四份多 Agent 定义；定义缺失、名称不唯一或白名单与设计不一致时启动失败。 */
    @PostConstruct
    void loadGraphSpecDefinitions() {
        try {
            List<AgentSpec> specs = GRAPH_SPEC_FILES.stream()
                    .map(file -> readGraphSpec(GRAPH_SPEC_PREFIX + file))
                    .toList();
            List<String> available = List.of(toolProvider.getToolCallbacks()).stream()
                    .map(value -> value.getToolDefinition().name())
                    .sorted()
                    .toList();
            graphFactory.validate(specs, available);
            this.graphSpecs = new AgentSpecSet(specs);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("知识整理多 Agent 定义校验失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 装载本轮固定运行时定义（由已校验的四份 Agent 定义计算）。
     *
     * @param skillName 运行级稳定标识（保留既有 run 定义契约）
     * @return 本轮运行使用的不可变定义摘要
     * @throws KnowledgeTaskRequestException 定义尚未加载或标识无效
     */
    public LoadedDefinition load(String skillName) {
        if (skillName == null || skillName.isBlank() || graphSpecs == null) {
            throw new KnowledgeTaskRequestException(
                    KnowledgeTaskRequestException.Code.AGENT_DEFINITION_INVALID);
        }
        List<AgentSpec> specs = graphSpecs.definitions();
        String skillDigest = hash(specs.stream()
                .filter(spec -> KnowledgeCurationGraphFactory.COORDINATOR.equals(spec.name()))
                .map(AgentSpec::systemPrompt).reduce("", (left, right) -> left + right));
        String agentSpecDigest = hash(specs.stream()
                .map(spec -> specContent(spec)).reduce("", (left, right) -> left + right));
        List<String> toolNames = new TreeSet<>(specs.stream()
                .flatMap(spec -> spec.toolNames().stream()).toList()).stream().toList();
        return new LoadedDefinition(new RuntimeDefinition(
                skillName, skillDigest, agentSpecDigest, agentProperties.modelName(), toolNames));
    }

    private String specContent(AgentSpec spec) {
        return spec.name() + "\n" + spec.description() + "\n" + spec.systemPrompt() + "\n" + spec.toolNames();
    }

    private AgentSpec readGraphSpec(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("知识整理 Agent 定义资源缺失：" + path);
        }
        try {
            AgentSpec spec = AgentSpecLoader.loadFromResource(resource);
            if (spec == null) {
                throw new IllegalArgumentException("知识整理 Agent 定义无法解析：" + path);
            }
            return spec;
        } catch (IOException exception) {
            throw new IllegalStateException("知识整理 Agent 定义读取失败：" + path, exception);
        }
    }

    /** @return 启动阶段校验通过的四个多 Agent 定义集合 */
    public AgentSpecSet graphSpecs() {
        return graphSpecs;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /** 本轮运行固定定义摘要；多 Agent 流程下仅保留运行时定义（不再暴露 Skill Registry 或业务 Tool 集合）。 */
    public record LoadedDefinition(RuntimeDefinition runtime) {
        public LoadedDefinition {
            Objects.requireNonNull(runtime, "runtime");
        }
    }
}
