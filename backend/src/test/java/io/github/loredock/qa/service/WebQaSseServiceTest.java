package io.github.loredock.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.AgentRunEventMapper;
import io.github.loredock.agent.model.entity.AgentRunEventEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.service.AgentEventService;
import io.github.loredock.agent.service.AgentRunQueryService;
import io.github.loredock.auth.service.SessionService;
import io.github.loredock.qa.config.WebQaSseProperties;
import io.github.loredock.qa.model.request.WebQaSseStreamRequest;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.result.WebQaStreamTarget;
import io.github.loredock.qa.model.snapshot.WebQaSsePublicEvent;
import io.github.loredock.qa.scheduler.BoundedWebQaSseExecutor;
import io.github.loredock.qa.service.impl.WebQaSseSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebQaSseServiceTest {
    private static final Long RUN_ID = 2553040173361004547L;
    private static final Long QUESTION_ID = 2553040173361004546L;
    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

    /**
     * 业务目的：建连后必须由提交事件立即唤醒 SSE，不再等待固定数据库轮询间隔。
     */
    @Test
    void committedEventWakesLiveConnectionAndTerminalSnapshotClosesIt() throws Exception {
        AgentRunEventMapper mapper = mock(AgentRunEventMapper.class);
        AgentEventService eventStream = new AgentEventService(mapper, new ObjectMapper());
        AgentRunQueryService runs = mock(AgentRunQueryService.class);
        QueryWebQaQuestionService access = mock(QueryWebQaQuestionService.class);
        SessionService sessions = mock(SessionService.class);
        SessionService.SessionLease lease = mock(SessionService.SessionLease.class);
        DefaultWebQaAssistantMessageMaterializer materializer = mock(DefaultWebQaAssistantMessageMaterializer.class);
        CountDownLatch waiting = new CountDownLatch(1);
        when(sessions.isValid(lease, "member")).thenAnswer(ignored -> {
            waiting.countDown();
            return true;
        });
        when(access.authorize(any())).thenReturn(target(AgentRunStatus.RUNNING), target(AgentRunStatus.COMPLETED));
        when(runs.list(any(), any(), any(Long.class), any(Integer.class))).thenReturn(List.of());
        when(runs.lastSequence(RUN_ID, "member")).thenReturn(1L);
        when(mapper.appendReturning(any(), any(), any(), any())).thenReturn(AgentRunEventEntity.builder()
                .id(1L).runId(RUN_ID).sequence(1L).eventType(AgentEventType.MODEL_STARTED.name())
                .payload("{\"value\":\"model\"}").createdAt(NOW).build());
        WebQaSseService service = service(runs, eventStream, sessions, access, materializer);
        RecordingSink sink = new RecordingSink();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var stream = executor.submit(() -> service.stream(request(lease), sink, Instant.now()));
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            eventStream.append(RUN_ID, AgentEventType.MODEL_STARTED, "model", NOW);
            stream.get(2, TimeUnit.SECONDS);
        }

        assertThat(sink.events).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("model.started");
            assertThat(event.data().sequence()).isEqualTo(1);
        });
        assertThat(sink.closed).isTrue();
        verify(materializer).materialize(any(), any());
        System.out.println("测试证据：场景=SSE提交事件唤醒，数据库轮询间隔=无，事件=model.started#1，终态关闭=true");
    }

    /**
     * 业务目的：长连接会话失效时必须在读取或发送任何运行事件前关闭。
     */
    @Test
    void invalidSessionClosesBeforeReadingEvents() {
        AgentRunQueryService runs = mock(AgentRunQueryService.class);
        AgentEventService eventStream = new AgentEventService(mock(AgentRunEventMapper.class), new ObjectMapper());
        QueryWebQaQuestionService access = mock(QueryWebQaQuestionService.class);
        SessionService sessions = mock(SessionService.class);
        SessionService.SessionLease lease = mock(SessionService.SessionLease.class);
        when(sessions.isValid(lease, "member")).thenReturn(false);
        RecordingSink sink = new RecordingSink();

        service(runs, eventStream, sessions, access, mock(DefaultWebQaAssistantMessageMaterializer.class))
                .stream(request(lease), sink, Instant.now());

        assertThat(sink.closed).isTrue();
        assertThat(sink.events).isEmpty();
        verify(access, never()).authorize(any());
        verify(runs, never()).list(any(), any(), any(Long.class), any(Integer.class));
        System.out.println("测试证据：场景=SSE会话失效，会话复核=false，读取事件=0，发送事件=0");
    }

    private WebQaSseService service(
            AgentRunQueryService runs,
            AgentEventService eventStream,
            SessionService sessions,
            QueryWebQaQuestionService access,
            DefaultWebQaAssistantMessageMaterializer materializer
    ) {
        return new WebQaSseService(
                new WebQaSseProperties(Duration.ofSeconds(5), Duration.ofMinutes(1),
                        20, 1, 1, 0, Duration.ofSeconds(1)),
                mock(BoundedWebQaSseExecutor.class), Clock.systemUTC(), access, runs, eventStream,
                sessions, materializer);
    }

    private WebQaSseStreamRequest request(SessionService.SessionLease lease) {
        return new WebQaSseStreamRequest("member", "atlas", QUESTION_ID, RUN_ID, 0, lease);
    }

    private WebQaStreamTarget target(AgentRunStatus status) {
        WebQaQuestionRecord question = new WebQaQuestionRecord(
                QUESTION_ID, "member", "key", "a".repeat(64), 11L, "atlas", 12L, "main", RUN_ID, NOW);
        AgentRunSnapshot run = new AgentRunSnapshot(
                RUN_ID, "member", "agent-key", "b".repeat(64), "project_qa", status,
                null, null, null, null,
                new AgentScopeSnapshot(11L, "atlas", 12L, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "fake-model", "prompt"),
                3, 0, 0, null, null, NOW, null, status.terminal() ? NOW : null, List.of());
        return new WebQaStreamTarget(question, run);
    }

    private static final class RecordingSink implements WebQaSseSink {
        private final List<WebQaSsePublicEvent> events = new ArrayList<>();
        private boolean closed;

        @Override public void send(WebQaSsePublicEvent event) { events.add(event); }
        @Override public void heartbeat() { }
        @Override public void complete() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
