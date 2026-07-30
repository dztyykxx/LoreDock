package io.github.loredock.knowledge.application;

import java.util.List;

/**
 * 稳定的零基分页结果。
 *
 * @param items 当前页数据
 * @param page 零基页码
 * @param size 页容量
 * @param totalElements 总记录数
 * @param totalPages 总页数
 */
public record PageResult<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
