package io.github.loredock.agent.model.result;

import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.snapshot.EvidenceSourceMetadata;
import java.time.Instant;

/**
 * 当前运行内可引用的有限来源元数据；不保存知识正文、代码正文、对象键或服务器路径。
 */
public record AgentEvidence(
        Long id,
        Long runId,
        EvidenceSourceType sourceType,
        boolean retained,
        double relevance,
        Long documentId,
        Long snapshotId,
        String projectIdentifier,
        String branch,
        String commit,
        String repositoryPath,
        String title,
        Instant sourceUpdatedAt,
        EvidenceSourceMetadata sourceMetadata
) {
    public AgentEvidence(
            Long id, Long runId, EvidenceSourceType sourceType, boolean retained, double relevance,
            Long documentId, Long snapshotId, String projectIdentifier, String branch, String commit,
            String repositoryPath, String title, Instant sourceUpdatedAt
    ) {
        this(id, runId, sourceType, retained, relevance, documentId, snapshotId, projectIdentifier, branch,
                commit, repositoryPath, title, sourceUpdatedAt, EvidenceSourceMetadata.historicalUnknown());
    }

    public AgentEvidence {
        sourceMetadata = sourceMetadata == null ? EvidenceSourceMetadata.historicalUnknown() : sourceMetadata;
    }
}
