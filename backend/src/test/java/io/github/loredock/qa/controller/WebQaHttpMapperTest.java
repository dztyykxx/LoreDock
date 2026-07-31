package io.github.loredock.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.qa.converter.WebQaHttpMapper;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.response.WebQaQuestionResponse;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebQaHttpMapperTest {

    private static final Long PROJECT_ID = 8000000000000000165L;
    private static final Long BRANCH_ID = 8000000000000000166L;
    private static final Long QUESTION_ID = 8000000000000000167L;
    private static final Long RUN_ID = 8000000000000000168L;
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
                        8000000000000000169L, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "fake-model", "project-qa-v1"),
                8, 4, 2, null, null, NOW, NOW, NOW, List.of());
        return new WebQaQuestionSnapshot(question, run, trustState, List.of());
    }
}
