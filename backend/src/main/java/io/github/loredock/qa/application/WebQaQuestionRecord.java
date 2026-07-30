package io.github.loredock.qa.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 问答仓储边界内的身份和固定范围，不包含正文或当前可变项目元数据。
 */
public record WebQaQuestionRecord(
        UUID id,
        String operatorId,
        String idempotencyKey,
        String requestHash,
        UUID projectId,
        String projectIdentifier,
        UUID branchId,
        String branch,
        UUID runId,
        Instant createdAt
) {
}
