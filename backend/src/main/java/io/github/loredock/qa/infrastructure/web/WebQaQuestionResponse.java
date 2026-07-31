package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AnswerBasis;
import io.github.loredock.qa.domain.WebQaTrustState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 问答创建、历史与详情共用的安全快照；不包含操作者、请求摘要、模型配置或服务器路径。
 */
public record WebQaQuestionResponse(
        UUID questionId,
        UUID runId,
        WebQaScopeResponse scope,
        Instant createdAt,
        AgentRunStatus status,
        AgentResultType resultType,
        WebQaTrustState trustState,
        AnswerBasis answerBasis,
        AgentRefusalReason refusalReason,
        AgentErrorCode errorCode,
        String failureMessage,
        String resultText,
        int stepCount,
        int modelCallCount,
        long lastEventSequence,
        List<WebQaMessageResponse> messages,
        List<WebQaCitationResponse> citations
) {
    public WebQaQuestionResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
