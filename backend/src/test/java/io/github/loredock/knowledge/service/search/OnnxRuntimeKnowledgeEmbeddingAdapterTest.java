package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.config.KnowledgeEmbeddingProperties;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import org.junit.jupiter.api.Test;

class OnnxRuntimeKnowledgeEmbeddingAdapterTest {

    /**
     * 业务目的：部署遗漏离线模型配置时必须返回稳定的 Embedding 不可用语义，防止后台任务泄漏底层参数错误并被误报为未知故障。
     */
    @Test
    void missingModelConfigurationUsesStableEmbeddingUnavailableError() {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        OnnxRuntimeKnowledgeEmbeddingAdapter adapter = new OnnxRuntimeKnowledgeEmbeddingAdapter(properties);

        assertThatThrownBy(adapter::dimensions)
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class)
                .hasMessage("knowledge embedding model unavailable");
        System.out.println("测试证据：场景=Embedding配置缺失，公开错误=KNOWLEDGE_EMBEDDING_UNAVAILABLE，底层参数泄漏=false");
    }
}
