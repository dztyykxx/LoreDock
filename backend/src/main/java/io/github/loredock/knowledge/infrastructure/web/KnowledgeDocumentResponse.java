package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 普通已发布文档详情，不返回归档审计、内部对象键或管理失败信息。 */
public record KnowledgeDocumentResponse(
        UUID id,
        DocumentFormat format,
        String title,
        String body,
        String directory,
        List<String> tags,
        DocumentSourceResponse source,
        KnowledgeScopeResponse scope,
        DocumentStatus status,
        long revision,
        Instant publishedAt,
        Instant updatedAt
) {
}
