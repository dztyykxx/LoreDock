package io.github.loredock.memory.api;

/**
 * 人工编辑记忆命令：只允许修改分类、标题、摘要、正文与状态。
 *
 * <p>范围与所属项目不可编辑（变更范围视为新建）；缺省为 null 的字段保持原值，
 * {@code scope}/{@code projectId} 一旦非空即按
 * {@link MemoryRequestException.Code#MEMORY_SCOPE_EDIT_FORBIDDEN} 拒绝，记录保持不变。</p>
 *
 * @param id 记忆编号
 * @param category 新分类（可空=不修改）
 * @param title 新标题（可空=不修改）
 * @param summary 新摘要（可空=不修改）
 * @param content 新全文（可空=不修改）
 * @param status 新状态（可空=不修改）
 * @param scope 变更范围探索字段；非空即被拒绝（可空=不修改）
 * @param projectId 变更所属项目探索字段；非空即被拒绝（可空=不修改）
 * @param operatorId 更新操作者（审计）
 */
public record MemoryEditInput(Long id, MemoryCategory category, String title, String summary,
                                 String content, MemoryStatus status, MemoryScope scope,
                                 Long projectId, String operatorId) {
}
