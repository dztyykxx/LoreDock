package io.github.loredock.memory.model.response;

import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemorySourceType;
import io.github.loredock.memory.api.MemoryStatus;
import java.time.OffsetDateTime;

/**
 * 记忆列表/管理完整视图响应（与内部 {@code MemoryFull} 同构，含正文与审计信息）。
 *
 * @param id 记忆编号
 * @param scope 范围（创建后不可编辑）
 * @param projectId 所属项目主键；GLOBAL 为空
 * @param projectIdentifier 所属项目稳定标识；GLOBAL 为空
 * @param category 分类
 * @param title 短标题
 * @param summary 注入摘要
 * @param content 全文正文
 * @param status 状态
 * @param sourceType 来源类型
 * @param sourceRunId 提炼来源 run；MANUAL 为空
 * @param sourceConversationId 提炼来源会话；MANUAL 为空
 * @param useCount 使用频次
 * @param lastUsedAt 最近全文加载时间
 * @param createdAt 创建时间
 * @param updatedAt 最近编辑时间
 */
public record MemoryResponse(
        long id,
        MemoryScope scope,
        Long projectId,
        String projectIdentifier,
        MemoryCategory category,
        String title,
        String summary,
        String content,
        MemoryStatus status,
        MemorySourceType sourceType,
        Long sourceRunId,
        Long sourceConversationId,
        long useCount,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
