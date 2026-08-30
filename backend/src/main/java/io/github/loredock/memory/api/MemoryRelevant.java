package io.github.loredock.memory.api;

/**
 * 摘要级记忆条目；用于主 Agent 上下文注入的固定格式，
 * 不携带正文，全文只能通过按需加载接口获取。
 *
 * @param id 记忆编号
 * @param scope 范围
 * @param projectId 所属项目主键；GLOBAL 为空
 * @param projectIdentifier 所属项目稳定标识；GLOBAL 为空
 * @param category 分类
 * @param title 短标题（摘要行展示）
 * @param summary 注入摘要（≤300 码点，展示前不截断）
 * @param useCount 历史使用频次（检索排序权重）
 */
public record MemoryRelevant(
        Long id,
        MemoryScope scope,
        Long projectId,
        String projectIdentifier,
        MemoryCategory category,
        String title,
        String summary,
        long useCount
) {
}
