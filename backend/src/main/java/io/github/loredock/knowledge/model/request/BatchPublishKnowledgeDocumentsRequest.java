package io.github.loredock.knowledge.model.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 管理员批量发布请求；只携带当前页人工勾选的文档标识，不表达替代关系或索引动作。
 *
 * @param documentIds 一至一百个文档 Long
 */
public record BatchPublishKnowledgeDocumentsRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> documentIds
) {
}
