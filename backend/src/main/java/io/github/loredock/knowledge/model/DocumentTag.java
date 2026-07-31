package io.github.loredock.knowledge.model;

/**
 * 标签显示值与去重值契约；显示值保留首个输入，规范值用于大小写无关去重。
 *
 * @param displayName 展示名称
 * @param normalizedName Unicode 规范化后的去重名称
 */
public record DocumentTag(String displayName, String normalizedName) {

    public DocumentTag {
        displayName = DocumentTextRules.normalizedRequired(
                displayName, KnowledgeDocumentLimits.TAG_MAX_CODE_POINTS, "document tag");
        String expectedNormalizedName = displayName.toLowerCase(java.util.Locale.ROOT);
        if (normalizedName != null) {
            String provided = DocumentTextRules.normalizedRequired(
                    normalizedName, KnowledgeDocumentLimits.TAG_MAX_CODE_POINTS, "normalized document tag");
            if (!provided.equals(expectedNormalizedName)) {
                throw new IllegalArgumentException("normalized document tag does not match display name");
            }
        }
        normalizedName = expectedNormalizedName;
    }

    /**
     * @param value 原始标签输入
     * @return 已规范化的标签
     */
    public static DocumentTag of(String value) {
        return new DocumentTag(value, null);
    }
}
