package io.github.loredock.qa.model.response;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import java.time.Instant;
import java.util.List;

/**
 * 问答创建、历史与详情共用的安全快照；不包含操作者、请求摘要、模型配置或服务器路径。
 */
public record WebQaQuestionResponse(
        Long questionId,
        Long runId,
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
