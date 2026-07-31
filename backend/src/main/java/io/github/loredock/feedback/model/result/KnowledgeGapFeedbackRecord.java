package io.github.loredock.feedback.model.result;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;
import io.github.loredock.feedback.model.enums.KnowledgeGapType;
import java.time.Instant;

/** 固定范围、服务端结果摘要和人工状态的知识缺口仓储记录。 */
public record KnowledgeGapFeedbackRecord(
        Long id, String operatorId, String idempotencyKey, String requestHash,
        Long projectId, String projectIdentifier, Long branchId, String branch,
        Long questionId, Long runId, KnowledgeGapType type, KnowledgeGapStatus status,
        String question, String note, AgentResultType resultType,
        AgentRefusalReason refusalReason, AgentErrorCode errorCode,
        Instant createdAt, Instant updatedAt, String createdBy, String updatedBy
) {
}
