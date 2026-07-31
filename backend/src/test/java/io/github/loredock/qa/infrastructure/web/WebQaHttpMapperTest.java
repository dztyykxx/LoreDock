package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.qa.application.WebQaQuestionRecord;
import io.github.loredock.qa.application.WebQaQuestionSnapshot;
import io.github.loredock.qa.domain.WebQaTrustState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebQaHttpMapperTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-31T02:00:00Z");

    /**
     * 业务目的：证据不足是已完成的业务拒答，必须返回拒答正文且不能附带系统失败说明，防止用户误以为服务故障。
     */
    @Test
    void completedRefusalKeepsResultTextWithoutFailureMessage() {
        WebQaQuestionResponse response = WebQaHttpMapper.toResponse(snapshot(
                AgentRunStatus.COMPLETED,
                AgentResultType.REFUSAL,
                "当前知识库没有足够依据",
                AgentRefusalReason.INSUFFICIENT_EVIDENCE,
                null,
                WebQaTrustState.INSUFFICIENT_EVIDENCE), 5);

        assertThat(response.resultText()).isEqualTo("当前知识库没有足够依据");
        assertThat(response.failureMessage()).isNull();
        System.out.printf("测试证据：场景=业务拒答，状态=%s，拒答原因=%s，失败说明=%s%n",
                response.status(), response.refusalReason(), response.failureMessage());
    }

    /**
     * 业务目的：真实运行失败必须由服务端返回安全、可操作的说明与稳定诊断码，防止前端只显示“系统问题”。
     */
    @Test
    void terminatedRunReturnsSafeFailureMessageAndDiagnosticCode() {
        WebQaQuestionResponse response = WebQaHttpMapper.toResponse(snapshot(
                AgentRunStatus.TERMINATED,
                null,
                null,
                null,
                AgentErrorCode.AGENT_STEP_LIMIT_EXCEEDED,
                WebQaTrustState.FAILED), 8);

        assertThat(response.resultText()).isNull();
        assertThat(response.failureMessage()).contains("达到运行上限").contains("缩小问题范围");
        assertThat(response.errorCode()).isEqualTo(AgentErrorCode.AGENT_STEP_LIMIT_EXCEEDED);
        System.out.printf("测试证据：场景=运行终止，状态=%s，错误码=%s，说明长度=%d%n",
                response.status(), response.errorCode(), response.failureMessage().length());
    }

    private WebQaQuestionSnapshot snapshot(
            AgentRunStatus status,
            AgentResultType resultType,
            String resultText,
            AgentRefusalReason refusalReason,
            AgentErrorCode errorCode,
            WebQaTrustState trustState
    ) {
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "question-key", "a".repeat(64), PROJECT_ID, "nanobot",
                BRANCH_ID, "main", RUN_ID, NOW);
        AgentRunSnapshot run = new AgentRunSnapshot(
                RUN_ID, "member", "run-key", "b".repeat(64), "project_qa", status,
                resultType, null, resultText, refusalReason, errorCode,
                new AgentScopeSnapshot(PROJECT_ID, "nanobot", BRANCH_ID, "main", null, null,
                        UUID.randomUUID(), List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.1", "c".repeat(64),
                        "fake", "fake-model", "project-qa-v1", "tools-v1", "limits-v1"),
                8, 4, 2, null, null, NOW, NOW, NOW, List.of());
        return new WebQaQuestionSnapshot(question, run, trustState, List.of());
    }
}
