package io.github.loredock.memory.api;

import java.util.List;

/**
 * 记忆列表分页结果。
 *
 * @param total 过滤后总条数
 * @param page 当前页码
 * @param size 每页条数
 * @param items 当前页记忆完整视图（管理视图，含正文与审计信息）
 */
public record MemoryPage(long total, int page, int size, List<MemoryFull> items) {
}
