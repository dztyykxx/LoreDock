package io.github.loredock.memory.model.request;

import io.github.loredock.memory.api.MemoryStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 记忆停用/启用请求。
 *
 * @param status ACTIVE（启用）或 DISABLED（停用）
 */
public record MemoryStatusRequest(
        @NotNull MemoryStatus status
) {
}
