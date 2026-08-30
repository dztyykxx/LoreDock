package io.github.loredock.memory.api;

/**
 * 人工创建记忆命令（MANUAL 来源）。
 *
 * <p>范围与所属项目必须显式给定；PROJECT 时项目必须存在且启用，否则
 * {@link MemoryRequestException.Code#MEMORY_PROJECT_INVALID}。
 * 人工路径不做语义判断（判断归人），但字段与 scope 校验不可绕过。</p>
 *
 * @param scope 记忆范围
 * @param projectId PROJECT 范围的项目主键；GLOBAL 为空
 * @param category 分类
 * @param title 短标题（≤200 码点）
 * @param summary 摘要（≤300 码点）
 * @param content 全文（≤4000 码点）
 * @param operatorId 创建操作者（审计）
 */
public record MemoryDraftInput(MemoryScope scope, Long projectId, MemoryCategory category,
                                 String title, String summary, String content, String operatorId) {
}
