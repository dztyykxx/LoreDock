package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentScopeSnapshot;
import io.github.loredock.agent.domain.AgentVersionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionAwareAgentRunDispatchCoordinatorTest {

    private static final UUID RUN_ID = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private AgentRunScheduler scheduler;
    private AgentRunDispatchFailureHandler failures;

    @BeforeEach
    void setUp() {
        scheduler = mock(AgentRunScheduler.class);
        failures = mock(AgentRunDispatchFailureHandler.class);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * 业务目的：T6A 直接启动没有调用方事务时必须立即进入有界执行器，防止提交后协调重构延迟既有入口。
     */
    @Test
    void noOuterTransactionDispatchesImmediately() {
        when(scheduler.schedule(request())).thenReturn(true);

        coordinator().dispatchAfterCommit(request());

        verify(scheduler).schedule(request());
        verifyNoInteractions(failures);
        System.out.printf("测试证据：场景=无外层事务，runId=%s，提交状态=无外层事务，调度次数=1，终态错误=无%n", RUN_ID);
    }

    /**
     * 业务目的：问答和运行加入调用方事务后，只能在最外层提交成功后调度，防止工作线程读取未提交范围。
     */
    @Test
    void activeOuterTransactionDispatchesOnceAfterCommit() {
        when(scheduler.schedule(request())).thenReturn(true);
        beginSynchronization();

        coordinator().dispatchAfterCommit(request());
        verifyNoInteractions(scheduler);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(value -> value.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(scheduler).schedule(request());
        verifyNoInteractions(failures);
        System.out.printf("测试证据：场景=外层事务提交，runId=%s，提交状态=COMMITTED，调度次数=1，终态错误=无%n", RUN_ID);
    }

    /**
     * 业务目的：调用方事务回滚时不得留下模型或工具副作用，防止执行不存在的问答记录。
     */
    @Test
    void rolledBackOuterTransactionNeverDispatches() {
        beginSynchronization();

        coordinator().dispatchAfterCommit(request());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(value -> value.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(scheduler, failures);
        System.out.printf("测试证据：场景=外层事务回滚，runId=%s，提交状态=ROLLED_BACK，调度次数=0，终态错误=无%n", RUN_ID);
    }

    /**
     * 业务目的：提交后的队列拒绝必须转成可追溯失败终态，不能让已接受运行永久停留或被静默丢弃。
     */
    @Test
    void rejectedDispatchRecordsRuntimeBusyThroughIndependentHandler() {
        when(scheduler.schedule(request())).thenReturn(false);

        coordinator().dispatchAfterCommit(request());

        verify(failures).finish(RUN_ID, AgentErrorCode.AGENT_RUNTIME_BUSY);
        System.out.printf("测试证据：场景=调度队列拒绝，runId=%s，提交状态=COMMITTED，调度次数=1，终态错误=%s%n",
                RUN_ID, AgentErrorCode.AGENT_RUNTIME_BUSY);
    }

    private AgentRunDispatchCoordinator coordinator() {
        return new TransactionAwareAgentRunDispatchCoordinator(scheduler, failures);
    }

    private void beginSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private AgentExecutionRequest request() {
        AgentScopeSnapshot scope = new AgentScopeSnapshot(
                UUID.fromString("71000000-0000-0000-0000-000000000002"), "atlas",
                UUID.fromString("71000000-0000-0000-0000-000000000003"), "main",
                null, null, null, List.of("GLOBAL", "PROJECT", "BRANCH"));
        AgentVersionSnapshot versions = new AgentVersionSnapshot(
                UUID.fromString("71000000-0000-0000-0000-000000000004"), "project_qa", "1.0.0",
                "a".repeat(64), "openai-compatible", "fake", "project-qa-v1",
                "project-qa-readonly-v1", "project-qa-policy-v1");
        return new AgentExecutionRequest(RUN_ID, "问题", "skill", "schema", scope, versions,
                new AgentRuntimeLimits(8, 8, Duration.ofSeconds(90), 10, 2000, 24000, 8000, 200),
                Instant.parse("2026-07-30T01:01:30Z"));
    }
}
