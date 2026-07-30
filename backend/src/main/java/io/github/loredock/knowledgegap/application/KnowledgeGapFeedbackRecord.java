package io.github.loredock.knowledgegap.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;

import java.time.Instant;
import java.util.UUID;

/** 固定范围、服务端结果摘要和人工状态的知识缺口仓储记录。 */
public record KnowledgeGapFeedbackRecord(
        UUID id, String operatorId, String idempotencyKey, String requestHash,
        UUID projectId, String projectIdentifier, UUID branchId, String branch,
        UUID questionId, UUID runId, KnowledgeGapType type, KnowledgeGapStatus status,
        String question, String note, AgentResultType resultType,
        AgentRefusalReason refusalReason, AgentErrorCode errorCode,
        Instant createdAt, Instant updatedAt, String createdBy, String updatedBy
) {
}
