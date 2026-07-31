package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;

/** 文档已解析的范围响应，使用稳定 Long 表达归属。 */
public record KnowledgeScopeResponse(KnowledgeScopeType type, Long projectId, Long branchId) {
}
