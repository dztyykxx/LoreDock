package io.github.loredock.qa.infrastructure.web;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个 SSE 连接占用一个专用工作任务，线程和等待队列均有硬上限。 */
@Component
final class BoundedWebQaSseExecutor {
    private final ThreadPoolExecutor executor;
    private final WebQaSseProperties properties;

    /** @param properties SSE 有界执行配置 */
    BoundedWebQaSseExecutor(WebQaSseProperties properties) {
        this.properties = properties;
        BlockingQueue<Runnable> queue = properties.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(properties.queueCapacity());
        this.executor = new ThreadPoolExecutor(
                properties.corePoolSize(), properties.maxPoolSize(), 30, TimeUnit.SECONDS,
                queue, threadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return 任务已接受时为 true；容量满时不阻塞 Web 请求 */
    boolean execute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    /** 停机时停止接收连接并有界等待，超时后中断仍存活的轮询任务。 */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.shutdownAwait().toMillis(), TimeUnit.MILLISECONDS)) {
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
            Thread thread = new Thread(task, "loredock-qa-sse-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
