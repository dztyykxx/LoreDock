package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.EvidenceSourceType;

import java.time.Instant;
import java.util.UUID;

/** 最终回答或冲突拒答对外可解析的安全引用元数据。 */
public record AgentCitationSnapshot(
        UUID evidenceId,
        EvidenceSourceType sourceType,
        UUID documentId,
        UUID snapshotId,
        String projectIdentifier,
        String branch,
        String commit,
        String repositoryPath,
        String title,
        Instant sourceUpdatedAt,
        int order
) {
}
