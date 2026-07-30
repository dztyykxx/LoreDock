package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentEventType;
import io.github.loredock.agent.domain.AgentRunStatus;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import io.github.loredock.project.application.ProjectQueryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunQueryServiceTest {

    private static final UUID RUN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private AgentRunRepository runs;
    private AgentEventRepository events;
    private ProjectQueryUseCase projects;
    private AgentRunQueryService service;

    @BeforeEach
    void setUp() {
        runs = mock(AgentRunRepository.class);
        events = mock(AgentEventRepository.class);
        projects = mock(ProjectQueryUseCase.class);
        service = new AgentRunQueryService(runs, events, projects);
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(snapshot("member")));
    }

    /**
     * 业务目的：运行查询必须直接返回数据库保存的版本与范围快照，并在读取时重新确认项目仍可访问。
     */
    @Test
    void ownerReadsPersistedSnapshotAfterProjectAccessRevalidation() {
        AgentRunSnapshot result = service.get(RUN_ID, "member");

        assertThat(result.versions().modelName()).isEqualTo("deepseek-v4-flash");
        assertThat(result.scope().branch()).isEqualTo("main");
        verify(projects).getEnabledProject("atlas", "main");
        System.out.printf("测试证据：场景=运行快照查询，runId=%s，操作者=member，固定模型=%s%n",
                result.runId(), result.versions().modelName());
    }

    /**
     * 业务目的：运行标识不得泄露给其他操作者，项目禁用后也必须按不存在处理。
     */
    @Test
    void otherOperatorOrDisabledProjectObservesNotFound() {
        assertThatThrownBy(() -> service.get(RUN_ID, "other"))
                .isInstanceOf(AgentRunNotFoundException.class);
        when(projects.getEnabledProject("atlas", "main")).thenThrow(new IllegalStateException("disabled"));
        assertThatThrownBy(() -> service.get(RUN_ID, "member"))
                .isInstanceOf(AgentRunNotFoundException.class);
        System.out.println("测试证据：场景=运行查询隔离，其他操作者与已禁用项目均返回不存在");
    }

    /**
     * 业务目的：事件续读必须使用已提交序号并把客户端页大小限制在 200，避免一次读取无界事件。
     */
    @Test
    void eventContinuationBoundsPageAndRejectsNegativeSequence() {
        AgentEventSnapshot event = new AgentEventSnapshot(UUID.randomUUID(), RUN_ID, 12,
                AgentEventType.SOURCE_FOUND, "count=1", Instant.parse("2026-07-30T01:00:00Z"));
        when(events.findAfter(RUN_ID, 11, 200)).thenReturn(List.of(event));

        assertThat(service.list(RUN_ID, "member", 11, 2000)).containsExactly(event);
        verify(events).findAfter(RUN_ID, 11, 200);
        assertThatThrownBy(() -> service.list(RUN_ID, "member", -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.printf("测试证据：场景=事件断线续读，afterSequence=11，实际返回序号=%d，服务端上限=200%n",
                event.sequence());
    }

    /**
     * 业务目的：详情返回的最后事件序号必须经过与事件续读相同的归属和项目访问复核，防止绕过授权探测运行。
     */
    @Test
    void lastSequenceUsesAuthorizedPersistedCounter() {
        when(events.lastSequence(RUN_ID)).thenReturn(18L);

        long sequence = service.lastSequence(RUN_ID, "member");

        assertThat(sequence).isEqualTo(18);
        verify(projects).getEnabledProject("atlas", "main");
        verify(events).lastSequence(RUN_ID);
        System.out.printf("测试证据：场景=详情事件序号，runId=%s，最后已提交序号=%d%n", RUN_ID, sequence);
    }

    private AgentRunSnapshot snapshot(String operator) {
        Instant now = Instant.parse("2026-07-30T01:00:00Z");
        return new AgentRunSnapshot(RUN_ID, operator, "key", "a".repeat(64), "project_qa",
                AgentRunStatus.ACCEPTED, null, null, null, null,
                new AgentScopeSnapshot(UUID.randomUUID(), "atlas", UUID.randomUUID(), "main",
                        UUID.randomUUID(), "abcdef1", null, List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(UUID.randomUUID(), "project_qa", "1.0.0", "b".repeat(64),
                        "openai-compatible", "deepseek-v4-flash", "project-qa-v1",
                        "project-qa-readonly-v1", "project-qa-policy-v1"),
                8, 0, 0, null, null, now, null, null, List.of());
    }
}
