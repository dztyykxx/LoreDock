package io.github.loredock.knowledge.config;

import io.github.loredock.knowledge.service.search.ReciprocalRankFusion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识搜索纯应用组件装配；Spring 依赖不进入应用层实现。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeSearchConfiguration {

    /** @return 固定版本、无状态且可复用的 RRF 融合器。 */
    @Bean
    public ReciprocalRankFusion reciprocalRankFusion() {
        return new ReciprocalRankFusion();
    }
}
