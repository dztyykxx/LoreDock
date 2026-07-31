package io.github.loredock.knowledge.service.search;

import io.github.loredock.knowledge.config.KnowledgeEmbeddingProperties;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.model.request.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * 知识检索对标准 Spring AI {@link EmbeddingModel} 的业务层，只保留文本组合、查询指令和模型摘要。
 */
@Service
public class KnowledgeEmbeddingService {

    private final EmbeddingModel model;
    private final KnowledgeEmbeddingProperties properties;

    /** @param model 可替换的标准 EmbeddingModel @param properties 知识检索规则和模型摘要配置 */
    public KnowledgeEmbeddingService(EmbeddingModel model, KnowledgeEmbeddingProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    /** @return 当前模型摘要；模型资源不可用时抛出稳定 503 */
    public KnowledgeEmbeddingModelDescriptor describeModel() {
        try {
            return new KnowledgeEmbeddingModelDescriptor(
                    properties.getModelId(), properties.getChecksum().toLowerCase(), model.dimensions());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** @param inputs 有界知识块 @return 与输入顺序一致的向量 */
    public List<KnowledgeEmbeddingVector> embedDocuments(List<KnowledgeEmbeddingInput> inputs) {
        Objects.requireNonNull(inputs, "embedding inputs are required");
        if (inputs.isEmpty()) {
            return List.of();
        }
        try {
            return model.embed(inputs.stream().map(this::composeDocumentText).toList())
                    .stream().map(KnowledgeEmbeddingVector::new).toList();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    /** @param normalizedQuery 已校验查询 @return 应用固定查询指令后的向量 */
    public KnowledgeEmbeddingVector embedQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            throw new KnowledgeEmbeddingUnavailableException(new IllegalArgumentException("query is blank"));
        }
        try {
            return new KnowledgeEmbeddingVector(model.embed(properties.getQueryInstruction() + normalizedQuery));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private String composeDocumentText(KnowledgeEmbeddingInput input) {
        Objects.requireNonNull(input, "embedding input is required");
        List<String> sections = new ArrayList<>();
        if (input.title() != null && !input.title().isBlank()) {
            sections.add(input.title().strip());
        }
        if (input.tags() != null && !input.tags().isEmpty()) {
            sections.add("标签：" + String.join("、", input.tags()));
        }
        if (input.content() != null && !input.content().isBlank()) {
            sections.add(input.content().strip());
        }
        if (sections.isEmpty()) {
            throw new KnowledgeEmbeddingUnavailableException(new IllegalArgumentException("document text is blank"));
        }
        return String.join("\n", sections);
    }

    private KnowledgeEmbeddingUnavailableException unavailable(RuntimeException exception) {
        return exception instanceof KnowledgeEmbeddingUnavailableException unavailable
                ? unavailable : new KnowledgeEmbeddingUnavailableException(exception);
    }
}
