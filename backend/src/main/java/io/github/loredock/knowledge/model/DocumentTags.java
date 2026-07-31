package io.github.loredock.knowledge.model;

import java.util.HashSet;
import java.util.List;

/**
 * 一篇文档的有序标签集合契约。
 *
 * @param values 保留输入顺序的标签
 */
public record DocumentTags(List<DocumentTag> values) {

    public DocumentTags {
        if (values == null) {
            throw new IllegalArgumentException("document tags must not be null");
        }
        if (values.size() > KnowledgeDocumentLimits.TAG_MAX_COUNT) {
            throw new IllegalArgumentException("too many document tags");
        }
        HashSet<String> normalizedNames = new HashSet<>();
        for (DocumentTag tag : values) {
            if (tag == null || !normalizedNames.add(tag.normalizedName())) {
                throw new IllegalArgumentException("duplicate document tag");
            }
        }
        values = List.copyOf(values);
    }

    /**
     * @param rawTags 原始标签文本
     * @return 保留输入顺序的规范标签集合
     */
    public static DocumentTags of(List<String> rawTags) {
        if (rawTags == null) {
            throw new IllegalArgumentException("document tags must not be null");
        }
        return new DocumentTags(rawTags.stream().map(DocumentTag::of).toList());
    }
}
