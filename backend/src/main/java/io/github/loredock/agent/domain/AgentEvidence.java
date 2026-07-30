package io.github.loredock.agent.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 当前运行内可引用的有限来源元数据；不保存知识正文、代码正文、对象键或服务器路径。
 */
public record AgentEvidence(
        UUID id,
        UUID runId,
        EvidenceSourceType sourceType,
        boolean retained,
        double relevance,
        UUID documentId,
        UUID snapshotId,
        String projectIdentifier,
        String branch,
        String commit,
        String repositoryPath,
        String title,
        Instant sourceUpdatedAt,
        EvidenceSourceMetadata sourceMetadata
) {
    public AgentEvidence(
            UUID id, UUID runId, EvidenceSourceType sourceType, boolean retained, double relevance,
            UUID documentId, UUID snapshotId, String projectIdentifier, String branch, String commit,
            String repositoryPath, String title, Instant sourceUpdatedAt
    ) {
        this(id, runId, sourceType, retained, relevance, documentId, snapshotId, projectIdentifier, branch,
                commit, repositoryPath, title, sourceUpdatedAt, EvidenceSourceMetadata.historicalUnknown());
    }

    public AgentEvidence {
        sourceMetadata = sourceMetadata == null ? EvidenceSourceMetadata.historicalUnknown() : sourceMetadata;
    }
}
