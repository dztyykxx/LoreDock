package io.github.loredock.agent.service;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import io.github.loredock.agent.api.KnowledgeTaskService.RuntimeDefinition;
import io.github.loredock.agent.config.AgentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.stereotype.Service;

/**
 * 直接使用框架 ClasspathSkillRegistry 取得随应用发布的 Workflow Skill，
 * 并将服务端显式注册的业务 Tool 交给 SkillsAgentHook 渐进披露。不加载 Agent Spec，
 * 不组装子 Agent，不实现自研 Loader 或 Tool Registry。
 */
@Service
public class KnowledgeAgentDefinitionService {

    private final ClasspathSkillRegistry skills;
    private final ToolCallbackProvider toolProvider;
    private final AgentProperties agentProperties;

    /**
     * @param skills 随应用发布的框架 Skill Registry
     * @param toolProvider 标准 ToolCallbackProvider 候选集
     * @param agentProperties 固定模型描述
     */
    public KnowledgeAgentDefinitionService(
            ClasspathSkillRegistry skills,
            ToolCallbackProvider toolProvider,
            AgentProperties agentProperties
    ) {
        this.skills = skills;
        this.toolProvider = toolProvider;
        this.agentProperties = agentProperties;
    }

    /**
     * 加载并冻结本轮 Skill 和 Tool 摘要。
     *
     * @param skillName 本轮目标 Skill
     * @return 固定摘要、随应用发布的 Registry 和业务 Tool
     * @throws KnowledgeTaskRequestException Skill 无效
     */
    public LoadedDefinition load(String skillName) {
        try {
            if (!skills.contains(skillName)) {
                throw invalidDefinition();
            }
            String skillContent = skills.readSkillContent(skillName);
            List<ToolCallback> callbacks = List.of(toolProvider.getToolCallbacks());
            List<String> allowed = callbacks.stream()
                    .map(value -> value.getToolDefinition().name()).sorted().toList();
            RuntimeDefinition runtime = new RuntimeDefinition(
                    skillName, hash(skillContent), hash(""),
                    agentProperties.modelName(), allowed);
            return new LoadedDefinition(runtime, skills, callbacks);
        } catch (KnowledgeTaskRequestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDefinition();
        }
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

    /**
     * @param runtime 本轮不可变定义摘要
     * @param skills 随应用发布、供本轮 Hook 使用的 Registry
     * @param businessTools 仅在 Skill 激活后动态披露的服务端业务 Tool
     */
    public record LoadedDefinition(
            RuntimeDefinition runtime,
            SkillRegistry skills,
            List<ToolCallback> businessTools
    ) {
        public LoadedDefinition {
            businessTools = List.copyOf(businessTools);
        }

        /** @return 绑定内置 Registry 并按 Skill 渐进披露业务 Tool 的独立框架 Hook */
        public SkillsAgentHook createSkillHook(ToolCallbackResolver resolver) {
            return SkillsAgentHook.builder()
                    .skillRegistry(skills)
                    .groupedTools(java.util.Map.of(runtime.skillName(), businessTools))
                    .toolCallbackResolver(resolver)
                    .autoReload(false)
                    .build();
        }
    }
}
