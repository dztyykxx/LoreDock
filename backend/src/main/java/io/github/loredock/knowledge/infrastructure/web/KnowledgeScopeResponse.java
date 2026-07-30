package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.KnowledgeScopeType;

import java.util.UUID;

/** 文档已解析的范围响应，使用稳定 UUID 表达归属。 */
public record KnowledgeScopeResponse(KnowledgeScopeType type, UUID projectId, UUID branchId) {
}
