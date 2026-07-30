package io.github.loredock.agent.infrastructure.runtime;

import io.github.loredock.agent.application.AgentExecutionRequest;
import io.github.loredock.agent.application.AgentRunScheduler;
import io.github.loredock.agent.application.AgentRunTaskExecutor;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用专用有界线程池调度 Agent，不占用 Web 或知识索引执行器。 */
@Component
@Slf4j
public class BoundedAgentRunScheduler implements AgentRunScheduler {

    private final AgentRunTaskExecutor taskExecutor;
    private final ThreadPoolExecutor executor;
    private final AgentProperties properties;

    /** @param taskExecutor 运行任务执行器 @param properties Agent 有界执行器配置 */
    public BoundedAgentRunScheduler(AgentRunTaskExecutor taskExecutor, AgentProperties properties) {
        this.taskExecutor = taskExecutor;
        this.properties = properties;
        var limits = properties.executor();
        BlockingQueue<Runnable> queue = limits.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(limits.queueCapacity());
        this.executor = new ThreadPoolExecutor(
                limits.corePoolSize(), limits.maxPoolSize(), 30, TimeUnit.SECONDS,
                queue,
                threadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public boolean schedule(AgentExecutionRequest request) {
        try {
            executor.execute(() -> taskExecutor.execute(request));
            return true;
        } catch (RejectedExecutionException exception) {
            log.warn("agent_run scheduling rejected runId={} activeCount={} queuedCount={}",
                    request.runId(), executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    /** 停机时不接受新运行，有界等待后中断剩余线程。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.executor().shutdownAwait().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "loredock-agent-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
