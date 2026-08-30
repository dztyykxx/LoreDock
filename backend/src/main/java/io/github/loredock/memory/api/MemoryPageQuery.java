package io.github.loredock.memory.api;

/**
 * 记忆列表过滤与分页；所有过滤项可组合（交集）。
 *
 * @param scope 范围过滤；为空不限制
 * @param category 分类过滤；为空不限制
 * @param status 状态过滤；为空不限制
 * @param keyword 标题/摘要/正文关键词过滤；为空不限制
 * @param page 页码（从 1 开始）
 * @param size 每页条数（服务端按配置上限收紧）
 */
public record MemoryPageQuery(MemoryScope scope, MemoryCategory category, MemoryStatus status,
                              String keyword, int page, int size) {
}
