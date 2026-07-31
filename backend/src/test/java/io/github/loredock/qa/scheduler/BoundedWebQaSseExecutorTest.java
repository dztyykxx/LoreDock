package io.github.loredock.qa.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.qa.config.WebQaSseProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedWebQaSseExecutorTest {
    /**
     * 业务目的：SSE 连接容量耗尽时必须立即拒绝额外连接，不能无界排队或占用 Web、Agent 和通用任务线程。
     */
    @Test
    void rejectsConnectionWhenDedicatedCapacityIsFull() throws Exception {
        WebQaSseProperties properties = new WebQaSseProperties(
                Duration.ofSeconds(5), Duration.ofMinutes(1),
                200, 1, 1, 0, Duration.ofSeconds(1));
        BoundedWebQaSseExecutor executor = new BoundedWebQaSseExecutor(properties);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            boolean first = executor.execute(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            boolean second = executor.execute(() -> { });

            assertThat(first).isTrue();
            assertThat(second).isFalse();
            System.out.println("测试证据：场景=SSE有界容量，线程=1，队列=0，首连接=接受，第二连接=拒绝");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
