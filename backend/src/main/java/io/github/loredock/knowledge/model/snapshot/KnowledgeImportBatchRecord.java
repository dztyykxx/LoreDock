package io.github.loredock.knowledge.model.snapshot;

import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.enums.ImportBatchStatus;
import io.github.loredock.platform.persistence.AuditMetadata;

/** 导入批次持久化模型；对象键只在应用与仓储内部流转。 */
public record KnowledgeImportBatchRecord(
        Long id,
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
