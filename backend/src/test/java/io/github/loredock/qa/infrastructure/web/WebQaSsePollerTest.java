package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentEventQueryUseCase;
import io.github.loredock.agent.application.AgentEventSnapshot;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.identity.application.WebSessionContinuityPort;
import io.github.loredock.qa.application.QueryWebQaDetailCommand;
import io.github.loredock.qa.application.WebQaAssistantMessageMaterializer;
import io.github.loredock.qa.application.WebQaQuestionNotFoundException;
import io.github.loredock.qa.application.WebQaQuestionRecord;
import io.github.loredock.qa.application.WebQaStreamAccessUseCase;
import io.github.loredock.qa.application.WebQaStreamTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebQaSsePollerTest {
    private static final UUID QUESTION_ID = UUID.fromString("77000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("77000000-0000-0000-0000-000000000002");
    private static final UUID PROJECT_ID = UUID.fromString("77000000-0000-0000-0000-000000000003");
    private static final UUID BRANCH_ID = UUID.fromString("77000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private final WebSessionContinuityPort.Lease lease = new WebSessionContinuityPort.Lease() { };
    private WebQaStreamAccessUseCase access;
    private AgentEventQueryUseCase events;
    private WebSessionContinuityPort sessions;
    private WebQaAssistantMessageMaterializer materializer;
    private WebQaSsePoller poller;

    @BeforeEach
    void setUp() {
        access = mock(WebQaStreamAccessUseCase.class);
        events = mock(AgentEventQueryUseCase.class);
        sessions = mock(WebSessionContinuityPort.class);
        materializer = mock(WebQaAssistantMessageMaterializer.class);
        poller = new WebQaSsePoller(access, events, sessions, materializer, 200, Duration.ofSeconds(15));
        when(sessions.isValid(lease, "member")).thenReturn(true);
        when(access.authorize(command())).thenReturn(target(AgentRunStatus.RUNNING));
    }

    /**
     * 业务目的：从零读取时只能按严格递增序号发送一次，数据库或重连重复行不能造成页面重复阶段或正文。
     */
    @Test
    void fromZeroSkipsDuplicateSequenceAndAdvancesStrictly() throws IOException {
        when(events.list(RUN_ID, "member", 0, 200)).thenReturn(List.of(
                event(1, AgentEventType.RUN_ACCEPTED, "accepted"),
                event(1, AgentEventType.RUN_ACCEPTED, "accepted"),
                event(2, AgentEventType.RUN_STARTED, "running")));
        RecordingSink sink = new RecordingSink();

        WebQaSsePollResult result = poller.poll(request(0), 0, NOW.minusSeconds(1), NOW, sink);

        assertThat(sink.events).extracting(value -> value.data().sequence()).containsExactly(1L, 2L);
        assertThat(result.cursor()).isEqualTo(2);
        assertThat(result.sentCount()).isEqualTo(2);
        System.out.println("测试证据：场景=SSE从零续读，输入序号=1/1/2，实际发送=1/2，重复数=0");
    }

    /**
     * 业务目的：断线后从序号 8 续读必须只请求和发送 8 之后的事件，不重复已经消费的正文。
     */
    @Test
    void continuationAfterEightSendsOnlyNewerEvents() throws IOException {
        when(events.list(RUN_ID, "member", 8, 200)).thenReturn(List.of(
                event(9, AgentEventType.ANSWER_DELTA, "新片段")));
        RecordingSink sink = new RecordingSink();

        WebQaSsePollResult result = poller.poll(request(8), 8, NOW.minusSeconds(1), NOW, sink);

        assertThat(sink.events).singleElement().satisfies(value -> {
            assertThat(value.data().sequence()).isEqualTo(9);
            assertThat(value.data().textDelta()).isEqualTo("新片段");
        });
        assertThat(result.cursor()).isEqualTo(9);
        verify(events).list(RUN_ID, "member", 8, 200);
        System.out.println("测试证据：场景=SSE断线续读，afterSequence=8，发送首序号=9，旧正文重复=false");
    }

    /**
     * 业务目的：无业务事件达到间隔时只发无 ID 心跳，不能推进客户端业务游标。
     */
    @Test
    void idlePollEmitsHeartbeatWithoutChangingCursor() throws IOException {
        when(events.list(RUN_ID, "member", 8, 200)).thenReturn(List.of());
        RecordingSink sink = new RecordingSink();

        WebQaSsePollResult result = poller.poll(request(8), 8, NOW.minusSeconds(15), NOW, sink);

        assertThat(sink.heartbeats).isEqualTo(1);
        assertThat(sink.events).isEmpty();
        assertThat(result.cursor()).isEqualTo(8);
        System.out.println("测试证据：场景=SSE空闲心跳，业务事件=0，心跳=1，游标仍为8");
    }

    /**
     * 业务目的：终态事件全部发送后必须调用同一助手消息投影并关闭连接，使刷新详情可从数据库恢复一致正文。
     */
    @Test
    void terminalSnapshotMaterializesAssistantAndCloses() throws IOException {
        WebQaStreamTarget completed = target(AgentRunStatus.COMPLETED);
        when(access.authorize(command())).thenReturn(completed);
        when(events.list(RUN_ID, "member", 9, 200)).thenReturn(List.of(
                event(10, AgentEventType.RUN_COMPLETED, "ANSWER")));
        when(events.lastSequence(RUN_ID, "member")).thenReturn(10L);
        RecordingSink sink = new RecordingSink();

        WebQaSsePollResult result = poller.poll(request(9), 9, NOW.minusSeconds(1), NOW, sink);

        verify(materializer).materialize(completed.question(), completed.run());
        assertThat(sink.completed).isTrue();
        assertThat(result.closed()).isTrue();
        System.out.printf("测试证据：场景=SSE终态收敛，末序号=%d，消息投影=1，连接关闭=true%n", result.cursor());
    }

    /**
     * 业务目的：建连后会话失效或项目停用必须停止轮询，不能继续发送任何问答状态或来源摘要。
     */
    @Test
    void invalidSessionOrDisabledProjectClosesWithoutEvents() throws IOException {
        when(sessions.isValid(lease, "member")).thenReturn(false);
        RecordingSink expired = new RecordingSink();
        WebQaSsePollResult sessionResult = poller.poll(request(0), 0, NOW, NOW, expired);
        verify(access, never()).authorize(any());

        when(sessions.isValid(lease, "member")).thenReturn(true);
        when(access.authorize(command())).thenThrow(new WebQaQuestionNotFoundException());
        RecordingSink disabled = new RecordingSink();
        WebQaSsePollResult projectResult = poller.poll(request(0), 0, NOW, NOW, disabled);

        assertThat(sessionResult.closed()).isTrue();
        assertThat(projectResult.closed()).isTrue();
        assertThat(expired.events).isEmpty();
        assertThat(disabled.events).isEmpty();
        verify(events, never()).list(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        System.out.println("测试证据：场景=SSE持续授权，会话失效/项目停用均关闭，发送事件=0");
    }

    private WebQaSseStreamRequest request(long afterSequence) {
        return new WebQaSseStreamRequest(
                "member", "atlas", QUESTION_ID, RUN_ID, afterSequence, lease);
    }

    private QueryWebQaDetailCommand command() {
        return new QueryWebQaDetailCommand("member", "atlas", QUESTION_ID);
    }

    private AgentEventSnapshot event(long sequence, AgentEventType type, String payload) {
        return new AgentEventSnapshot(UUID.randomUUID(), RUN_ID, sequence, type, payload, NOW);
    }

    private WebQaStreamTarget target(AgentRunStatus status) {
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "key", "a".repeat(64), PROJECT_ID, "atlas",
                BRANCH_ID, "main", RUN_ID, NOW);
        AgentRunSnapshot run = new AgentRunSnapshot(
                RUN_ID, "member", "agent-key", "b".repeat(64), "project_qa", status,
                null, null, null, null,
                new AgentScopeSnapshot(PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "c".repeat(64),
                        "fake", "fake-model", "prompt", "tools", "limits"),
                3, 0, 0, null, null, NOW, null, status.terminal() ? NOW : null, List.of());
        return new WebQaStreamTarget(question, run);
    }

    private static final class RecordingSink implements WebQaSseSink {
        private final List<WebQaSsePublicEvent> events = new ArrayList<>();
        private int heartbeats;
        private boolean completed;

        @Override
        public void send(WebQaSsePublicEvent event) {
            events.add(event);
        }

        @Override
        public void heartbeat() {
            heartbeats++;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
