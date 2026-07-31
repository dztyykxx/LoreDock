package io.github.loredock.knowledge.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.knowledge.config.KnowledgeEmbeddingProperties;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.model.request.KnowledgeEmbeddingInput;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class KnowledgeEmbeddingServiceTest {

    private EmbeddingModel model;
    private KnowledgeEmbeddingService service;

    @BeforeEach
    void setUp() {
        model = mock(EmbeddingModel.class);
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setChecksum("a".repeat(64));
        service = new KnowledgeEmbeddingService(model, properties);
    }

    /**
     * 业务目的：文档向量输入必须由知识服务按标题、标签、正文组合，并通过标准 EmbeddingModel 批量调用。
     */
    @Test
    void documentsAreComposedAndEmbeddedInOneBatch() {
        when(model.embed(List.of("标题\n标签：安全、导入\n正文")))
                .thenReturn(List.of(new float[]{1F, 0F}));

        var result = service.embedDocuments(List.of(new KnowledgeEmbeddingInput(
                8000000000000000018L, 0, "标题", List.of("安全", "导入"), "正文")));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().values()).containsExactly(1F, 0F);
        verify(model).embed(List.of("标题\n标签：安全、导入\n正文"));
    }

    /**
     * 业务目的：查询指令属于知识检索规则而非模型实现，替换 EmbeddingModel 后仍必须保持一致。
     */
    @Test
    void queryInstructionIsAppliedOutsideEmbeddingModel() {
        when(model.embed("为这个句子生成表示以用于检索相关文章：恢复方案"))
                .thenReturn(new float[]{0F, 1F});

        assertThat(service.embedQuery("恢复方案").values()).containsExactly(0F, 1F);
    }

    /**
     * 业务目的：索引模型摘要使用标准模型维度，维度读取失败统一转换为稳定资源不可用错误。
     */
    @Test
    void descriptorUsesStandardDimensionsAndMapsUnavailableModel() {
        when(model.dimensions()).thenReturn(512);
        assertThat(service.describeModel().dimension()).isEqualTo(512);

        when(model.dimensions()).thenThrow(new IllegalStateException("missing model"));
        assertThatThrownBy(service::describeModel)
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class);
    }
}
