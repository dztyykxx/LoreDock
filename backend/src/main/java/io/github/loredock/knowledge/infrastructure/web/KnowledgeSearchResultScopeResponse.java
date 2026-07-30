package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.KnowledgeScopeType;

/** 搜索结果的公开适用范围，不暴露数据库内部关系或伪路径。 */
public record KnowledgeSearchResultScopeResponse(
        KnowledgeScopeType type,
        String projectIdentifier,
        String branch
) {
}
