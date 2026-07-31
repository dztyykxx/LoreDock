package io.github.loredock.knowledge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 离线知识 Embedding 模型配置。配置只描述服务端受控资源，不接受请求级覆盖。
 *
 * <p>模型和 tokenizer URI 仅允许 {@code file:} 与 {@code classpath:}，从而保证推理期间不会联网下载。
 */
@Getter
@Setter
@ConfigurationProperties("loredock.knowledge.search.embedding")
public class KnowledgeEmbeddingProperties {

    private String modelId = "BAAI/bge-small-zh-v1.5";
    private String modelUri = "";
    private String tokenizerUri = "";
    private String checksum = "";
    private String outputName = "sentence_embedding";
    private int maxTokens = 512;
    private String queryInstruction = "为这个句子生成表示以用于检索相关文章：";
}
