package io.github.loredock.knowledge.domain;

/**
 * 知识文档正文契约；内容始终是纯文本，不因格式字段而执行 Markdown 或 HTML。
 *
 * @param value 原样保存的正文文本
 */
public record DocumentBody(String value) {

    public DocumentBody {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("document body must not be blank");
        }
        DocumentTextRules.requireMaxCodePoints(
                value, KnowledgeDocumentLimits.BODY_MAX_CODE_POINTS, "document body");
    }
}
