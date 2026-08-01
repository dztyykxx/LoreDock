package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.config.AgentProperties;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
import io.github.loredock.agent.model.snapshot.AgentEventSnapshot;
import io.github.loredock.agent.model.snapshot.AgentRunSnapshot;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentServiceImplTest {

    private AgentRunService runs;
    private AgentEventService events;
    private ProjectService projects;
    private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        runs = mock(AgentRunService.class);
        events = mock(AgentEventService.class);
        projects = mock(ProjectService.class);
        service = new AgentServiceImpl(
                mock(AgentProperties.class), mock(AgentDefinitionProvider.class), projects,
                mock(KnowledgeSearchService.class), runs, events,
                mock(BoundedAgentRunScheduler.class), mock(PersistentAgentRunDispatchFailureHandler.class),
                Clock.systemUTC());
        when(projects.resolveEnabledScope("atlas", "main"))
                .thenReturn(new ProjectScope(11L, "atlas", "Atlas", true, 12L, "main"));
    }

    /**
     * 业务目的：QA 必须能从同一公开契约明确区分可信回答、证据不足拒答和运行失败，
     * 防止页面把模型故障误显示为拒答。
     */
    @Test
    void getDistinguishesAnswerRefusalAndFailure() {
        when(runs.findById(1L)).thenReturn(Optional.of(snapshot(
                1L, AgentRunStatus.COMPLETED, AgentResultType.ANSWER, null, null)));
        when(runs.findById(2L)).thenReturn(Optional.of(snapshot(
                2L, AgentRunStatus.COMPLETED, AgentResultType.REFUSAL,
                AgentRefusalReason.INSUFFICIENT_EVIDENCE, null)));
        when(runs.findById(3L)).thenReturn(Optional.of(snapshot(
                3L, AgentRunStatus.FAILED, null, null, AgentErrorCode.AGENT_MODEL_UNAVAILABLE)));

        AgentRun answer = service.get(1L, "operator");
        AgentRun refusal = service.get(2L, "operator");
        AgentRun failure = service.get(3L, "operator");

        System.out.printf("场景=Agent公开终态区分 answer=%s refusal=%s/%s failure=%s/%s%n",
                answer.status(), refusal.status(), refusal.refusalReason(), failure.status(), failure.errorCode());
        assertThat(answer.resultType()).isEqualTo(AgentRun.ResultType.ANSWER);
        assertThat(refusal.resultType()).isEqualTo(AgentRun.ResultType.REFUSAL);
        assertThat(refusal.refusalReason()).isEqualTo(AgentRun.RefusalReason.INSUFFICIENT_EVIDENCE);
        assertThat(failure.status()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(failure.errorCode()).isEqualTo(AgentRun.ErrorCode.AGENT_MODEL_UNAVAILABLE);
    }

    /**
     * 业务目的：SSE 只能读取 Agent 已提交的公开事件与连续序号，避免 QA 依赖事件表实体或内部事件模型。
     */
    @Test
    void listEventsReturnsCommittedPublicEvents() {
        when(runs.findById(1L)).thenReturn(Optional.of(snapshot(
                1L, AgentRunStatus.RUNNING, null, null, null)));
        when(events.findAfter(1L, 2L, 20)).thenReturn(List.of(
                new AgentEventSnapshot(31L, 1L, 3L, AgentEventType.SOURCE_FOUND,
                        "knowledge_search count=2", Instant.EPOCH)));

        List<AgentEvent> result = service.listEvents(1L, "operator", 2L, 20);

        System.out.printf("场景=Agent公开事件 runId=%d sequence=%d type=%s%n",
                result.getFirst().runId(), result.getFirst().sequence(), result.getFirst().type());
        assertThat(result).containsExactly(new AgentEvent(
                31L, 1L, 3L, AgentEvent.Type.SOURCE_FOUND, "knowledge_search count=2", Instant.EPOCH));
    }

    private AgentRunSnapshot snapshot(
            Long runId,
            AgentRunStatus status,
            AgentResultType resultType,
            AgentRefusalReason refusalReason,
            AgentErrorCode errorCode
    ) {
        return new AgentRunSnapshot(
                runId, "operator", "key-" + runId, "hash", "project_qa", status, resultType,
                resultType == null ? null : "result", refusalReason, errorCode,
                new AgentScopeSnapshot(11L, "atlas", 12L, "main", null, null, 13L,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "fake", "v1"),
                8, 2, 1, null, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, List.of());
    }
}
