package io.github.loredock.knowledge.config;

import io.github.loredock.knowledge.service.search.OnnxRuntimeKnowledgeEmbeddingAdapter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 离线 Embedding 基础设施装配；模型在首次实际调用时才加载。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeEmbeddingConfiguration {

    /**
     * @param properties 服务端受控模型配置
     * @return 不泄漏 ONNX 或 tokenizer 类型的应用端口
     */
    @Bean(destroyMethod = "close")
    EmbeddingModel embeddingModel(KnowledgeEmbeddingProperties properties) {
        return new OnnxRuntimeKnowledgeEmbeddingAdapter(properties);
    }
}
