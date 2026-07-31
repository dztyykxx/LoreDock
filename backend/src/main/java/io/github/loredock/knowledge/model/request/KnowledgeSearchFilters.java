package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import java.util.List;

/**
 * 两路候选共同使用的服务端过滤条件。
 *
 * @param tags 最多十个标签，候选必须包含全部标签
 * @param format 可选文档格式
 * @param sourceType 可选来源类型
 */
public record KnowledgeSearchFilters(
        List<String> tags,
        DocumentFormat format,
        DocumentSourceType sourceType
) {
    public KnowledgeSearchFilters {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
