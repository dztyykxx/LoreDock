package io.github.loredock.knowledge.infrastructure.web;

import java.util.UUID;

/** 双向替代追溯响应。 */
public record ReplacementResponse(UUID replacesDocumentId, UUID replacedByDocumentId) {
}
