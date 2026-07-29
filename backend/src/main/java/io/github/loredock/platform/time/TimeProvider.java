package io.github.loredock.platform.time;

import java.time.Instant;

/**
 * 领域和应用代码获取当前时间的唯一端口，避免依赖服务器默认时区并支持确定性测试。
 */
@FunctionalInterface
public interface TimeProvider {

    /**
     * @return 当前 UTC 瞬间
     */
    Instant now();
}
