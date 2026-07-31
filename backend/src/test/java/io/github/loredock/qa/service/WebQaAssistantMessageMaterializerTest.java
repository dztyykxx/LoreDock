package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.qa.model.enums.WebQaMessageRole;
import io.github.loredock.qa.model.result.WebQaMessageRecord;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebQaAssistantMessageMaterializerTest {
    private static final Long QUESTION_ID = 2034004871959281666L;
    private static final Long RUN_ID = 2034004871959281667L;
    private static final Instant NOW = Instant.parse("2026-07-30T04:00:00Z");

    /**
     * 业务目的：可信回答或拒答只能投影为一个终态助手消息，重复读取不得生成重复正文。
     */
    @Test
    void completedAnswerAndRefusalMaterializeIdempotentAssistantMessages() {
        WebQaMessageDataService messages = mock(WebQaMessageDataService.class);
        when(messages.insertIfAbsent(any())).thenReturn(
                Optional.of(1L), Optional.empty(), Optional.of(2L));
        DefaultWebQaAssistantMessageMaterializer materializer =
                new DefaultWebQaAssistantMessageMaterializer(messages);

        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.COMPLETED, AgentResultType.ANSWER, null, null, "可信回答"))).isTrue();
        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.COMPLETED, AgentResultType.ANSWER, null, null, "可信回答"))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.COMPLETED, AgentResultType.REFUSAL,
                AgentRefusalReason.INSUFFICIENT_EVIDENCE, null, "当前知识库没有足够依据"))).isTrue();

        ArgumentCaptor<WebQaMessageRecord> values = ArgumentCaptor.forClass(WebQaMessageRecord.class);
        verify(messages, org.mockito.Mockito.times(3)).insertIfAbsent(values.capture());
        assertThat(values.getAllValues()).allSatisfy(value ->
                assertThat(value.role()).isEqualTo(WebQaMessageRole.ASSISTANT));
        assertThat(values.getAllValues().get(0).resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(values.getAllValues().get(2).refusalReason())
                .isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        System.out.println("测试证据：场景=终态消息投影，回答首次=true，回答重复=false，拒答首次=true");
    }

    /**
     * 业务目的：失败、终止或活动运行不得创建助手消息，防止错误摘要被持久化成可信答复。
     */
    @Test
    void nonCompletedRunsNeverMaterializeAssistantMessage() {
        WebQaMessageDataService messages = mock(WebQaMessageDataService.class);
        DefaultWebQaAssistantMessageMaterializer materializer =
                new DefaultWebQaAssistantMessageMaterializer(messages);

        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.RUNNING, null, null, null, null))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.FAILED, null, null, AgentErrorCode.AGENT_MODEL_UNAVAILABLE, null))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRunStatus.TERMINATED, null, null, AgentErrorCode.AGENT_RUN_TIMEOUT, null))).isFalse();

        verify(messages, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=非完成运行不投影，活动/失败/终止的助手消息数=0");
    }

    private WebQaQuestionRecord question() {
        return new WebQaQuestionRecord(QUESTION_ID, "member", "key", "a".repeat(64),
                8000000000000000084L, "atlas", 8000000000000000085L, "main", RUN_ID, NOW);
    }

    private AgentRunSnapshot run(
            AgentRunStatus status,
            AgentResultType resultType,
            AgentRefusalReason refusalReason,
            AgentErrorCode errorCode,
            String text
    ) {
        return new AgentRunSnapshot(
                RUN_ID, "member", "agent-key", "b".repeat(64), "project_qa", status,
                resultType, text, refusalReason, errorCode,
                new AgentScopeSnapshot(8000000000000000086L, "atlas", 8000000000000000087L, "main",
                        null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "fake", "v1"),
                4, 0, 0, null, null, NOW, status == AgentRunStatus.RUNNING ? NOW : null,
                status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                        || status == AgentRunStatus.TERMINATED ? NOW.plusSeconds(1) : null,
                List.of());
    }
}
