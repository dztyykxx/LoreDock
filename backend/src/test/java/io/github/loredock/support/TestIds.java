package io.github.loredock.support;

import java.util.concurrent.atomic.AtomicLong;

/** 为测试夹具提供进程内唯一的 Long 标识，避免共享数据库中的测试数据互相碰撞。 */
public final class TestIds {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_000_000_000L);

    private TestIds() {
    }

    /** @return 当前测试 JVM 中唯一的正数标识 */
    public static Long next() {
        return SEQUENCE.incrementAndGet();
    }
}
