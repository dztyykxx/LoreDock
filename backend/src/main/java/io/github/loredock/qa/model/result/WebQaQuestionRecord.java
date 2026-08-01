package io.github.loredock.qa.model.result;

import java.time.Instant;

/**
 * 问答仓储边界内的身份和固定范围，不包含正文或当前可变项目元数据。
 */
public record WebQaQuestionRecord(
        Long id,
        Long conversationId,
        String operatorId,
        String idempotencyKey,
        String requestHash,
        Long projectId,
        String projectIdentifier,
        Long branchId,
        String branch,
        Long runId,
        Instant createdAt
) {
    /** 保留 T7 测试夹具；迁移后旧问题的 conversationId 等于 questionId。 */
    public WebQaQuestionRecord(
            Long id,
            String operatorId,
            String idempotencyKey,
            String requestHash,
            Long projectId,
            String projectIdentifier,
            Long branchId,
            String branch,
            Long runId,
            Instant createdAt
    ) {
        this(id, id, operatorId, idempotencyKey, requestHash, projectId, projectIdentifier,
                branchId, branch, runId, createdAt);
    }
}
