package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.ImportBatchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 导入批次 HTTP 响应，不包含上传正文、对象键、临时路径或解析器错误。 */
public record KnowledgeImportBatchResponse(
        UUID id,
        String originalFilename,
        KnowledgeScopeResponse scope,
        String directoryPrefix,
        ImportBatchStatus status,
        int succeededCount,
        int failedCount,
        int ignoredCount,
        List<KnowledgeImportItemResponse> items,
        Instant createdAt,
        String createdBy
) {
}
