package io.github.loredock.knowledge.infrastructure.web;

import java.util.List;

/**
 * HTTP 零基分页模型，默认容量 20、最大 100。
 *
 * @param items 当前页数据
 * @param page 零基页码
 * @param size 页容量
 * @param totalElements 总记录数
 * @param totalPages 总页数
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
