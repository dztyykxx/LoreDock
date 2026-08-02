package io.github.loredock.agent.scheduler;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** 知识任务 SSE 专用有界执行器，避免长连接占用 Agent 或通用请求线程池。 */
@Component
public final class KnowledgeTaskSseExecutor {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20),
            task -> {
                Thread thread = new Thread(task, "loredock-knowledge-task-sse-" + SEQUENCE.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** @return 连接任务是否进入有界执行器 */
    public boolean execute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
