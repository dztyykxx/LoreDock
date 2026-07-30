package io.github.loredock.knowledge.infrastructure.indexing;

import io.github.loredock.knowledge.application.search.indexing.DeterministicKnowledgeChunker;
import io.github.loredock.knowledge.application.search.indexing.KnowledgeChunker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识搜索构建策略装配；纯 Java 分块实现不直接依赖 Spring。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeSearchIndexingConfiguration {

    /** @return 当前锁定为 `cjk-v1` 的确定性分块策略。 */
    @Bean
    KnowledgeChunker knowledgeChunker() {
        return new DeterministicKnowledgeChunker();
    }
}
