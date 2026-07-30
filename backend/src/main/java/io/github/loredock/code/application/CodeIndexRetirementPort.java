package io.github.loredock.code.application;

import java.util.UUID;

/** 数据库提交退休后通知 Lucene reader 注册表延迟释放物理 generation。 */
@FunctionalInterface
public interface CodeIndexRetirementPort {
    /** 标记 generation 不再接受新请求，并在最后引用释放后幂等清理。 */
    void retire(UUID generationId);
}
