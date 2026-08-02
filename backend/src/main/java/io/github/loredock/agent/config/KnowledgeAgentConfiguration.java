package io.github.loredock.agent.config;

import io.github.loredock.agent.service.KnowledgeCurationToolCallbacks;
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
