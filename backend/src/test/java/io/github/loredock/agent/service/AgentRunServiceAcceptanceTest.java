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
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentEventType;
import io.github.loredock.agent.model.enums.AgentRunStatus;
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

    /**
     * 业务目的：管理员主动停止的知识整理运行（status=CANCELLED、error_code=AGENT_RUN_CANCELLED）
     * 必须能通过 AgentRunService 快照转换正常读取；
     * 防止停止后任务详情（agentService.listEvents → authorized → findById）抛出枚举转换异常，
     * 导致页面刷新和重新进入都失败、管理员无法继续整理。
     */
    @Test
    void cancelledKnowledgeRunSnapshotRemainsReadable() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentEvidenceService evidence = mock(AgentEvidenceService.class);
        AgentEventService events = mock(AgentEventService.class);
        Long runId = 6130197811678937092L;
        AgentRunEntity stopped = AgentRunEntity.builder()
                .id(runId).operatorId("admin").taskType("knowledge_curation")
                .projectIdentifier("GLOBAL").status("CANCELLED")
                .errorCode("AGENT_RUN_CANCELLED").questionLength(8).stepCount(3).modelCallCount(2).toolCallCount(1)
                .acceptedAt(Instant.parse("2026-08-11T02:00:00Z"))
                .startedAt(Instant.parse("2026-08-11T02:00:01Z"))
                .finishedAt(Instant.parse("2026-08-11T02:00:30Z"))
                .updatedAt(Instant.parse("2026-08-11T02:00:30Z")).build();
        when(mapper.selectById(runId)).thenReturn(stopped);
        when(evidence.findCitations(runId)).thenReturn(List.of());
        AgentRunService service = new AgentRunService(mapper, evidence, events);

        var snapshot = service.findById(runId);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(snapshot.orElseThrow().errorCode()).isEqualTo(AgentErrorCode.AGENT_RUN_CANCELLED);
        assertThat(snapshot.orElseThrow().finishedAt()).isNotNull();
        System.out.printf("测试证据：场景=停止后运行快照读取，runId=%s，状态=%s，错误码=%s%n",
                runId, snapshot.orElseThrow().status(), snapshot.orElseThrow().errorCode());
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
