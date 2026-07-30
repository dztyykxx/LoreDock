package io.github.loredock.knowledge.domain;

/**
 * 知识文档标题契约。
 *
 * @param value 向用户展示的标题文本
 */
public record DocumentTitle(String value) {

    public DocumentTitle {
        value = DocumentTextRules.normalizedRequired(
                value, KnowledgeDocumentLimits.TITLE_MAX_CODE_POINTS, "document title");
    }
}
