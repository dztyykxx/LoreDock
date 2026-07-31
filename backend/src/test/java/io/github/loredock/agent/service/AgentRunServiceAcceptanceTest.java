package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.loredock.agent.mapper.AgentRunMapper;
import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.snapshot.AgentScopeSnapshot;
import io.github.loredock.agent.model.snapshot.AgentVersionSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunServiceAcceptanceTest {

    /**
     * 业务目的：并发幂等受理的数据库胜者才能追加首事件，失败竞争者必须复用原运行。
     */
    @Test
    void duplicateAcceptanceReusesWinnerWithoutAppendingEvent() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentEvidenceService evidence = mock(AgentEvidenceService.class);
        AgentEventService events = mock(AgentEventService.class);
        AgentRunCreateData data = createData("web-qa:key");
        AgentRunEntity existing = entity(data, 6130197811678937090L);
        when(mapper.insertIfAbsent(any())).thenReturn(null);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        AgentRunService service = new AgentRunService(mapper, evidence, events);

        var result = service.accept(data);

        assertThat(result.newlyAccepted()).isFalse();
        assertThat(result.snapshot().runId()).isEqualTo(existing.getId());
        verify(events, never()).append(any(), any(), any(), any());
        System.out.printf("测试证据：场景=并发受理复用，runId=%s，新受理=false，首事件追加数=0%n",
                existing.getId());
    }

    /**
     * 业务目的：首次受理必须在同一事务内写入运行和唯一首事件，保证提交后可读取完整起点。
     */
    @Test
    void newAcceptanceAppendsFirstEventAndReturnsCreatedRun() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentEvidenceService evidence = mock(AgentEvidenceService.class);
        AgentEventService events = mock(AgentEventService.class);
        AgentRunCreateData data = createData("web-qa:new-key");
        Long runId = 6130197811678937091L;
        AgentRunEntity created = entity(data, runId);
        when(mapper.insertIfAbsent(any())).thenReturn(runId);
        when(mapper.selectById(runId)).thenReturn(created);
        AgentRunService service = new AgentRunService(mapper, evidence, events);

        var result = service.accept(data);

        assertThat(result.newlyAccepted()).isTrue();
        assertThat(result.snapshot().runId()).isEqualTo(runId);
        verify(events).append(runId, AgentEventType.RUN_ACCEPTED, "accepted", data.acceptedAt());
        System.out.printf("测试证据：场景=首次受理，runId=%s，新受理=true，首事件=RUN_ACCEPTED%n", runId);
    }

    private AgentRunCreateData createData(String idempotencyKey) {
        return new AgentRunCreateData(
                null, "member", idempotencyKey, "request-hash", "project_qa", "question-hash", 8,
                new AgentScopeSnapshot(11L, "atlas", 12L, "main", 13L, "abcdef1", 14L,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot("project_qa", "test-model", "project-qa-v1"),
                Instant.parse("2026-07-30T01:00:00Z"));
    }

    private AgentRunEntity entity(AgentRunCreateData data, Long runId) {
        return AgentRunEntity.builder()
                .id(runId)
                .operatorId(data.operatorId())
                .idempotencyKey(data.idempotencyKey())
                .requestHash(data.requestHash())
                .taskType(data.taskType())
                .questionHash(data.questionHash())
                .questionLength(data.questionLength())
                .projectId(data.scope().projectId())
                .projectIdentifier(data.scope().projectIdentifier())
                .branchId(data.scope().branchId())
                .branchName(data.scope().branch())
                .snapshotId(data.scope().snapshotId())
                .commitHash(data.scope().commit())
                .knowledgeGenerationId(data.scope().knowledgeGenerationId())
                .agentName(data.versions().agentName())
                .modelName(data.versions().modelName())
                .configSummary(data.versions().configSummary())
                .status("ACCEPTED")
                .stepCount(0)
                .modelCallCount(0)
                .acceptedAt(data.acceptedAt())
                .updatedAt(data.acceptedAt())
                .build();
    }
}
