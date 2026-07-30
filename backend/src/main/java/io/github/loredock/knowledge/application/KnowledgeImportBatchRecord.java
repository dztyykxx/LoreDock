package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.platform.audit.AuditMetadata;

import java.util.UUID;

/** 导入批次持久化模型；对象键只在应用与仓储内部流转。 */
public record KnowledgeImportBatchRecord(
        UUID id,
        String objectKey,
        String originalFilename,
        KnowledgeScope scope,
        String directoryPrefix,
        ImportBatchStatus status,
        int succeededCount,
        int failedCount,
        int ignoredCount,
        AuditMetadata audit
) {
}
