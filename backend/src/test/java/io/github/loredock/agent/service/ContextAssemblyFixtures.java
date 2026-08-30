package io.github.loredock.agent.service;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.context.ContextBudget;

/**
 * 上下文组装相关测试夹具：预算使用默认值（128k 窗口 / 96k 输入 / 72k 触发 / 64k 目标），
 * 组装服务只注入 mock 数据访问层——组装路径在预算内时不会触达数据库（FULL 模式无需旧轮读取）。
 */
final class ContextAssemblyFixtures {

    private ContextAssemblyFixtures() {
    }

    /** @return 与生产默认一致的测试预算 */
    static ContextBudget budget() {
        return new ContextBudget(128000, 96000, 24000, 8000, 72000, 64000, 512000L, 1, 3);
    }

    /** @return 以 mock Mapper 构成的最小可用组装服务（预算内组装不触达 DB） */
    static ContextAssemblyService assembly(ObjectMapper objectMapper) {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        KnowledgeTaskMessageMapper messages = mock(KnowledgeTaskMessageMapper.class);
        return new ContextAssemblyService(
                mock(KnowledgeTaskConversationMapper.class), messages, budget(), estimator,
                new ContextCompressionService(objectMapper, messages, estimator));
    }

    /** @return 带最小组装服务的 Graph 工厂（供会话/路由/装配类测试直接构建 Graph） */
    static KnowledgeCurationGraphFactory factory(ObjectMapper objectMapper) {
        return new KnowledgeCurationGraphFactory(objectMapper, assembly(objectMapper));
    }
}
