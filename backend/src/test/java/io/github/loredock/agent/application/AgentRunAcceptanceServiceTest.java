package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunAcceptanceServiceTest {

    /**
     * 业务目的：并发幂等受理的数据库胜者才能追加首事件，失败竞争者必须复用原运行且保持外层事务可提交。
     */
    @Test
    void duplicateAcceptanceReusesWinnerWithoutAppendingEvent() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        AgentRunCreateData data = mock(AgentRunCreateData.class);
        AgentRunSnapshot existing = mock(AgentRunSnapshot.class);
        UUID attemptedRunId = UUID.fromString("71000000-0000-0000-0000-000000000001");
        when(data.runId()).thenReturn(attemptedRunId);
        when(data.operatorId()).thenReturn("member");
        when(data.idempotencyKey()).thenReturn("web-qa:key");
        when(runs.insertIfAbsent(data)).thenReturn(false);
        when(runs.findByOperatorAndIdempotencyKey("member", "web-qa:key"))
                .thenReturn(Optional.of(existing));
        AgentRunAcceptanceService service = new AgentRunAcceptanceService(runs, events);

        AgentRunAcceptanceResult result = service.accept(data);

        assertThat(result.newlyAccepted()).isFalse();
        assertThat(result.snapshot()).isSameAs(existing);
        verify(events, never()).append(any(), any(), any(), any());
        System.out.printf("测试证据：场景=并发受理复用，尝试runId=%s，新受理=false，首事件追加数=0%n",
                attemptedRunId);
    }

    /**
     * 业务目的：首次受理必须在同一事务内写入运行和唯一首事件，保证提交后调度可读取完整起点。
     */
    @Test
    void newAcceptanceAppendsFirstEventAndReturnsCreatedRun() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        AgentRunCreateData data = mock(AgentRunCreateData.class);
        AgentRunSnapshot created = mock(AgentRunSnapshot.class);
        UUID runId = UUID.fromString("71000000-0000-0000-0000-000000000002");
        when(data.runId()).thenReturn(runId);
        when(runs.insertIfAbsent(data)).thenReturn(true);
        when(runs.findById(runId)).thenReturn(Optional.of(created));
        AgentRunAcceptanceService service = new AgentRunAcceptanceService(runs, events);

        AgentRunAcceptanceResult result = service.accept(data);

        assertThat(result.newlyAccepted()).isTrue();
        assertThat(result.snapshot()).isSameAs(created);
        verify(events).append(runId, AgentEventType.RUN_ACCEPTED, "accepted", data.acceptedAt());
        System.out.printf("测试证据：场景=首次受理，runId=%s，新受理=true，首事件=RUN_ACCEPTED%n", runId);
    }
}
