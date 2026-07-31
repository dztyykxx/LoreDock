package io.github.loredock.knowledge.model;


/**
 * 文档替代追溯契约；任一方向没有关系时对应字段为空。
 *
 * @param replacesDocumentId 当前文档替代的旧文档
 * @param replacedByDocumentId 替代当前文档的新文档
 */
public record ReplacementLink(Long replacesDocumentId, Long replacedByDocumentId) {

    /** @return 尚未建立替代关系的空追溯。 */
    public static ReplacementLink none() {
        return new ReplacementLink(null, null);
    }
}
