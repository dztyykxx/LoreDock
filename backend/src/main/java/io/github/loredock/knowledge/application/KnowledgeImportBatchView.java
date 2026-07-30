package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.KnowledgeScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 完整导入批次视图；三类计数必须与条目明细一致且不暴露服务端对象键。 */
public record KnowledgeImportBatchView(
        UUID id,
        String originalFilename,
        KnowledgeScope scope,
        String directoryPrefix,
        ImportBatchStatus status,
        int succeededCount,
        int failedCount,
        int ignoredCount,
        List<KnowledgeImportItemView> items,
        Instant createdAt,
        String createdBy
) {
}
