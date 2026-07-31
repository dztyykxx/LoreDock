package io.github.loredock.agent.model.snapshot;

import io.github.loredock.agent.model.enums.EvidenceSourceType;
import java.time.Instant;

/** 最终回答或冲突拒答对外可解析的安全引用元数据。 */
public record AgentCitationSnapshot(
        Long evidenceId,
        EvidenceSourceType sourceType,
        Long documentId,
        Long snapshotId,
        String projectIdentifier,
        String branch,
        String commit,
        String repositoryPath,
        String title,
        Instant sourceUpdatedAt,
        int order,
        EvidenceSourceMetadata sourceMetadata
) {
}
