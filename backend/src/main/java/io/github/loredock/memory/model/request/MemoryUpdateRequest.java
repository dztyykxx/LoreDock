package io.github.loredock.memory.model.request;

import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryStatus;
import jakarta.validation.constraints.Size;

/**
 * 管理员人工编辑记忆请求：只允许修改分类、标题、摘要、正文与状态；缺省字段保持原值。
 *
 * <p>{@code scope}/{@code projectId} 仅作探测字段——一旦非空服务端按
 * {@code MEMORY_SCOPE_EDIT_FORBIDDEN} 拒绝、记录保持不变（变更范围视为新建），
 * 因此这里不对它们做非空校验，直接透传给服务端。</p>
 *
 * @param category 新分类（可空=不修改）
 * @param title 新标题（码点 ≤200；可空=不修改）
 * @param summary 新摘要（码点 ≤300；可空=不修改）
 * @param content 新全文（码点 ≤4000；可空=不修改）
 * @param status 新状态（可空=不修改）
 * @param scope 探测字段：非空即被拒绝
 * @param projectId 探测字段：非空即被拒绝
 */
public record MemoryUpdateRequest(
        MemoryCategory category,
        @Size(max = 200) String title,
        @Size(max = 300) String summary,
        @Size(max = 4000) String content,
        MemoryStatus status,
        MemoryScope scope,
        Long projectId
) {
}
