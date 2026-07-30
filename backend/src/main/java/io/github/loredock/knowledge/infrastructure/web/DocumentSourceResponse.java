package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.DocumentSourceType;

/** 来源响应不包含对象存储键，所有文本字段均应由前端按文本显示。 */
public record DocumentSourceResponse(
        DocumentSourceType type,
        String wikiUrl,
        String originalFilename,
        String curationNote
) {
}
