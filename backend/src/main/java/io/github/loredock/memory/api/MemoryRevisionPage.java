package io.github.loredock.memory.api;

import java.util.List;

/** 记忆版本历史分页结果。 */
public record MemoryRevisionPage(long total, int page, int size, List<MemoryRevision> items) {
}
