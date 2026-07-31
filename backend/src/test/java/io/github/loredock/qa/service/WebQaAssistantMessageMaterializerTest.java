package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentRun;
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
                AgentRun.Status.COMPLETED, AgentRun.ResultType.ANSWER, null, null, "可信回答"))).isTrue();
        assertThat(materializer.materialize(question(), run(
                AgentRun.Status.COMPLETED, AgentRun.ResultType.ANSWER, null, null, "可信回答"))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRun.Status.COMPLETED, AgentRun.ResultType.REFUSAL,
                AgentRun.RefusalReason.INSUFFICIENT_EVIDENCE, null, "当前知识库没有足够依据"))).isTrue();

        ArgumentCaptor<WebQaMessageRecord> values = ArgumentCaptor.forClass(WebQaMessageRecord.class);
        verify(messages, org.mockito.Mockito.times(3)).insertIfAbsent(values.capture());
        assertThat(values.getAllValues()).allSatisfy(value ->
                assertThat(value.role()).isEqualTo(WebQaMessageRole.ASSISTANT));
        assertThat(values.getAllValues().get(0).resultType()).isEqualTo(AgentRun.ResultType.ANSWER);
        assertThat(values.getAllValues().get(2).refusalReason())
                .isEqualTo(AgentRun.RefusalReason.INSUFFICIENT_EVIDENCE);
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
                AgentRun.Status.RUNNING, null, null, null, null))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRun.Status.FAILED, null, null, AgentRun.ErrorCode.AGENT_MODEL_UNAVAILABLE, null))).isFalse();
        assertThat(materializer.materialize(question(), run(
                AgentRun.Status.TERMINATED, null, null, AgentRun.ErrorCode.AGENT_RUN_TIMEOUT, null))).isFalse();

        verify(messages, never()).insertIfAbsent(any());
        System.out.println("测试证据：场景=非完成运行不投影，活动/失败/终止的助手消息数=0");
    }

    private WebQaQuestionRecord question() {
        return new WebQaQuestionRecord(QUESTION_ID, "member", "key", "a".repeat(64),
                8000000000000000084L, "atlas", 8000000000000000085L, "main", RUN_ID, NOW);
    }

    private AgentRun run(
            AgentRun.Status status,
            AgentRun.ResultType resultType,
            AgentRun.RefusalReason refusalReason,
            AgentRun.ErrorCode errorCode,
            String text
    ) {
        return new AgentRun(
                RUN_ID, status, resultType, null, text, refusalReason, errorCode,
                new AgentRun.Scope(8000000000000000086L, "atlas", 8000000000000000087L, "main",
                        null, null, null),
                0, 0, NOW, status == AgentRun.Status.RUNNING ? NOW : null,
                status.terminal() ? NOW.plusSeconds(1) : null, List.of());
    }
}
