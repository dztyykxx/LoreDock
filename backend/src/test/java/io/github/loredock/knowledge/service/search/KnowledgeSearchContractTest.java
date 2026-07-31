package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.platform.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KnowledgeSearchContractTest {

    /**
     * 业务目的：索引未建立和语义模型不可用必须保持可区分的 503 语义，防止调用方把基础设施失败误判为空结果。
     */
    @Test
    void unavailableSearchCapabilitiesExposeStableServiceUnavailableErrors() {
        assertThat(ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.KNOWLEDGE_EMBEDDING_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        System.out.printf(
                "测试证据：场景=知识检索基础设施不可用，索引错误=%s，Embedding错误=%s，HTTP状态=%d%n",
                ErrorCode.KNOWLEDGE_INDEX_UNAVAILABLE,
                ErrorCode.KNOWLEDGE_EMBEDDING_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    /**
     * 业务目的：Embedding 结果跨端口传递时必须保持不可变，防止候选查询或批量处理意外改写同一向量并破坏稳定排序。
     */
    @Test
    void embeddingVectorDefensivelyCopiesValuesAcrossApplicationBoundary() {
        float[] source = {0.25F, 0.5F, 0.75F};
        KnowledgeEmbeddingVector vector = new KnowledgeEmbeddingVector(source);

        source[0] = 9F;
        float[] exposed = vector.values();
        exposed[1] = 8F;

        assertThat(vector.dimension()).isEqualTo(3);
        assertThat(vector.values()).containsExactly(0.25F, 0.5F, 0.75F);
        System.out.printf(
                "测试证据：场景=Embedding端口不可变边界，维度=%d，首值=%.2f，中间值=%.2f%n",
                vector.dimension(), vector.values()[0], vector.values()[1]);
    }
}
