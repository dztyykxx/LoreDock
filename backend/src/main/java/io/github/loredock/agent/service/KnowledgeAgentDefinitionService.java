package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpec;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecLoader;
import com.alibaba.cloud.ai.graph.agent.tools.task.AgentSpecReactAgentFactory;
import com.alibaba.cloud.ai.graph.agent.tools.task.TaskToolsBuilder;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.config.KnowledgeAgentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 每个新 run 使用框架 Registry 与 AgentSpecLoader 重新取得本地定义，并在模型调用前执行 LoreDock
 * 的确定性 Tool 允许集预检。该类不缓存、不解析替代格式，也不实现自研 Loader 或 Tool Registry。
 */
@Service
public class KnowledgeAgentDefinitionService {

    private final FileSystemSkillRegistry skills;
    private final SkillsAgentHook skillHook;
    private final KnowledgeAgentProperties properties;
    private final ToolCallbackProvider toolProvider;
    private final ObjectProvider<ChatModel> chatModel;
    private final AgentProperties agentProperties;

    /**
     * @param skills 框架文件系统 Skill Registry
     * @param skillHook 框架自动重载 Hook
     * @param properties 固定定义目录
     * @param toolProvider 标准 ToolCallbackProvider 候选集
     * @param chatModel 可选生产或测试模型
     * @param agentProperties 固定模型描述
     */
    public KnowledgeAgentDefinitionService(
            FileSystemSkillRegistry skills,
            SkillsAgentHook skillHook,
            KnowledgeAgentProperties properties,
            ToolCallbackProvider toolProvider,
            ObjectProvider<ChatModel> chatModel,
            AgentProperties agentProperties
    ) {
        this.skills = skills;
        this.skillHook = skillHook;
        this.properties = properties;
        this.toolProvider = toolProvider;
        this.chatModel = chatModel;
        this.agentProperties = agentProperties;
    }

    /**
     * 加载并冻结本轮定义摘要；Agent Spec 未显式声明 Tool、引用未知 Tool 或文件无效时明确拒绝。
     *
     * @param skillName 本轮目标 Skill
     * @return 固定摘要与框架 Task/TaskOutput Tool
     * @throws KnowledgeTaskRequestException Skill/Spec 无效或 Tool 越权
     */
    public LoadedDefinition load(String skillName) {
        try {
            skills.reload();
            if (!skillHook.hasSkill(skillName)) {
                throw invalidDefinition();
            }
            String skillContent = skills.readSkillContent(skillName);
            Path specDirectory = Path.of(properties.agentSpecsDirectory());
            List<AgentSpec> specs = AgentSpecLoader.loadFromDirectory(specDirectory);
            if (specs.isEmpty()) {
                throw invalidDefinition();
            }
            List<ToolCallback> callbacks = List.of(toolProvider.getToolCallbacks());
            List<String> allowed = callbacks.stream()
                    .map(value -> value.getToolDefinition().name()).sorted().toList();
            validateSpecs(specs, allowed);
            List<ToolCallback> taskTools = assembleTaskTools(specs, callbacks);
            RuntimeDefinition runtime = new RuntimeDefinition(
                    skillName, hash(skillContent), digestAgentSpecs(specDirectory),
                    agentProperties.modelName(), allowed);
            return new LoadedDefinition(runtime, taskTools);
        } catch (KnowledgeTaskRequestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDefinition();
        }
    }

    private void validateSpecs(List<AgentSpec> specs, List<String> allowedTools) {
        Set<String> names = new LinkedHashSet<>();
        for (AgentSpec spec : specs) {
            if (spec.name() == null || spec.name().isBlank() || !names.add(spec.name())
                    || spec.toolNames() == null || spec.toolNames().isEmpty()) {
                throw invalidDefinition();
            }
            if (spec.toolNames().stream().anyMatch(tool -> !allowedTools.contains(tool))) {
                throw new KnowledgeTaskRequestException(
                        KnowledgeTaskRequestException.Code.AGENT_TOOL_NOT_ALLOWED);
            }
        }
    }

    private List<ToolCallback> assembleTaskTools(List<AgentSpec> specs, List<ToolCallback> callbacks) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            // 未启用模型的管理/迁移进程仍执行定义与 Tool 预检；实际运行入口会明确检查模型可用性。
            return List.of();
        }
        AgentSpecReactAgentFactory factory = AgentSpecReactAgentFactory.builder()
                .chatModel(model).defaultTools(callbacks).build();
        var builder = TaskToolsBuilder.builder().agentSpecFactory(factory);
        specs.forEach(spec -> builder.subAgent(spec.name(), factory.create(spec)));
        return List.copyOf(builder.build());
    }

    private String digestAgentSpecs(Path root) throws IOException {
        List<Path> files;
        try (var values = Files.walk(root)) {
            files = values.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        List<String> values = new ArrayList<>(files.size());
        for (Path file : files) {
            values.add(root.relativize(file) + "\n" + Files.readString(file, StandardCharsets.UTF_8));
        }
        return hash(String.join("\n---\n", values));
    }

    private KnowledgeTaskRequestException invalidDefinition() {
        return new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.AGENT_DEFINITION_INVALID);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /** @param runtime 本轮不可变定义摘要 @param taskTools 框架原生 Task 与 TaskOutput Tool */
    public record LoadedDefinition(RuntimeDefinition runtime, List<ToolCallback> taskTools) {
        public LoadedDefinition {
            taskTools = List.copyOf(taskTools);
        }
    }
}
