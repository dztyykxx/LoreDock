package io.github.loredock.agent.service;

import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.request.AgentExecutionRequest;
import io.github.loredock.agent.scheduler.BoundedAgentRunScheduler;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务感知的调度策略：复用现有有界执行器，但保证工作线程只能观察已经提交的运行和业务输入记录。
 */
@Service
@Slf4j
public class TransactionAwareAgentRunDispatchCoordinator {

    private final BoundedAgentRunScheduler scheduler;
    private final PersistentAgentRunDispatchFailureHandler failures;

    /** @param scheduler Agent 专用有界调度器 @param failures 提交后失败的独立持久化边界 */
    public TransactionAwareAgentRunDispatchCoordinator(
            BoundedAgentRunScheduler scheduler,
            PersistentAgentRunDispatchFailureHandler failures
    ) {
        this.scheduler = scheduler;
        this.failures = failures;
    }

    public void dispatchAfterCommit(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Web 问答会把运行与用户消息放入同一外层事务；回滚时 afterCommit 不会执行。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(request);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        log.info("agent_run dispatch skipped runId={} transactionStatus={}",
                                request.runId(), status);
                    }
                }
            });
            return;
        }
        dispatch(request);
    }

    private void dispatch(AgentExecutionRequest request) {
        final boolean scheduled;
        try {
            scheduled = scheduler.schedule(request);
        } catch (RuntimeException exception) {
            log.error("agent_run dispatch failed runId={} errorCode={}",
                    request.runId(), AgentErrorCode.AGENT_INTERNAL_ERROR, exception);
            finishSafely(request, AgentErrorCode.AGENT_INTERNAL_ERROR);
            return;
        }
        if (!scheduled) {
            finishSafely(request, AgentErrorCode.AGENT_RUNTIME_BUSY);
        }
    }

    private void finishSafely(AgentExecutionRequest request, AgentErrorCode errorCode) {
        try {
            failures.finish(request.runId(), errorCode);
        } catch (RuntimeException exception) {
            // 原事务已经提交，不能让持久化失败伪装成调用方事务回滚；启动恢复会终结遗留活动运行。
            log.error("agent_run dispatch failure persistence failed runId={} errorCode={}",
                    request.runId(), errorCode, exception);
        }
    }
}
