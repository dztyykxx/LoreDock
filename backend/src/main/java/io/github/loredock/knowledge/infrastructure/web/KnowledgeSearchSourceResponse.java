package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentSourceType;

/** 可引用的公开来源字段；不包含对象键、服务器路径或内部配置。 */
public record KnowledgeSearchSourceResponse(
        DocumentSourceType type,
        String wikiUrl,
        String originalFilename,
        String curationNote
) {
}
