package io.github.loredock.agent.scheduler;

import io.github.loredock.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 使用专用有界线程池调度 Agent，不占用 Web 或知识索引执行器。 */
@Component
@Slf4j
public class BoundedAgentRunScheduler {

    private final ThreadPoolExecutor executor;
    private final AgentProperties properties;

    /** @param properties Agent 有界执行器配置 */
    public BoundedAgentRunScheduler(AgentProperties properties) {
        this.properties = properties;
        var limits = properties.executor();
        BlockingQueue<Runnable> queue = limits.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(limits.queueCapacity());
        this.executor = new ThreadPoolExecutor(
                limits.corePoolSize(), limits.maxPoolSize(), 30, TimeUnit.SECONDS,
                queue,
                threadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 复用同一专用有界线程池执行其他已持久化 Agent 运行，不创建第二套调度基础设施。
     *
     * @param runId 已持久化运行标识
     * @param task 只负责该运行的框架调用
     * @return 是否进入有界执行器
     */
    public boolean schedule(Long runId, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            log.warn("agent_run scheduling rejected runId={} activeCount={} queuedCount={}",
                    runId, executor.getActiveCount(), executor.getQueue().size());
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
