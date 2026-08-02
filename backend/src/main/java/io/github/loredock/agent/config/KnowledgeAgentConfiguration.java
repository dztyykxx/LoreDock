package io.github.loredock.agent.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.InterruptionHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import io.github.loredock.agent.service.KnowledgeCurationToolCallbacks;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring AI Alibaba 知识整理 Skill、Hook 与标准 Tool 解析器组装。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeAgentConfiguration {

    /** @return 通过空反馈请求和用户反馈恢复的框架 interrupt Hook */
    @Bean
    public InterruptionHook knowledgeInterruptionHook() {
        return InterruptionHook.builder().build();
    }

    /**
     * @return 框架原生 Human-in-the-loop Hook；当前不对安全业务 Tool 增加逐次审批，
     * 草稿正式发布仍完全不进入 Agent Tool 集合
     */
    @Bean
    public HumanInTheLoopHook knowledgeHumanInTheLoopHook() {
        return HumanInTheLoopHook.builder().build();
    }

    /** @return 只扫描服务端固定本地目录的框架文件系统 Skill Registry */
    @Bean
    public FileSystemSkillRegistry knowledgeSkillRegistry(KnowledgeAgentProperties properties) {
        return FileSystemSkillRegistry.builder()
                .userSkillsDirectory(Path.of(properties.skillsDirectory(), ".empty-user").toString())
                .projectSkillsDirectory(properties.skillsDirectory())
                .build();
    }

    /** @return 新 run 前自动重载本地 Skill 的框架 Hook */
    @Bean
    public SkillsAgentHook knowledgeSkillsAgentHook(FileSystemSkillRegistry knowledgeSkillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(knowledgeSkillRegistry)
                .autoReload(true)
                .build();
    }

    /** @return 仅包含 LoreDock 知识整理业务能力的标准 ToolCallbackProvider */
    @Bean
    public ToolCallbackProvider knowledgeToolCallbackProvider(KnowledgeCurationToolCallbacks tools) {
        return new StaticToolCallbackProvider(tools.callbacks());
    }

    /** @return 与 Provider 使用同一显式候选集的标准 ToolCallbackResolver */
    @Bean
    public ToolCallbackResolver knowledgeToolCallbackResolver(ToolCallbackProvider knowledgeToolCallbackProvider) {
        List<ToolCallback> callbacks = List.of(knowledgeToolCallbackProvider.getToolCallbacks());
        return new StaticToolCallbackResolver(callbacks);
    }
}
