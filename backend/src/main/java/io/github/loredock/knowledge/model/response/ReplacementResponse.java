package io.github.loredock.knowledge.model.response;


/** 双向替代追溯响应。 */
public record ReplacementResponse(Long replacesDocumentId, Long replacedByDocumentId) {
}
