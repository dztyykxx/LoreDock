package io.github.loredock.memory.model.request;

import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员人工创建记忆请求；人工路径不做语义判断（判断归人），但字段与 scope 校验不可绕过。
 *
 * @param scope 记忆范围；PROJECT 时必须绑定存在且启用的项目
 * @param projectId PROJECT 范围的项目主键；GLOBAL 为空
 * @param category 分类
 * @param title 短标题（码点 ≤200）
 * @param summary 摘要（码点 ≤300）；为空时服务端取正文前 300 码点
 * @param content 全文正文（码点 ≤4000）
 */
public record MemoryCreateRequest(
        @NotNull MemoryScope scope,
        Long projectId,
        @NotNull MemoryCategory category,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 300) String summary,
        @NotBlank @Size(max = 4000) String content
) {
}
