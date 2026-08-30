package io.github.loredock.memory.model.response;

import java.util.List;

/**
 * 记忆列表分页响应。
 *
 * @param total 过滤后总条数
 * @param page 当前页码
 * @param size 每页条数
 * @param items 当前页记忆
 */
public record MemoryPageResponse(long total, int page, int size, List<MemoryResponse> items) {
}
