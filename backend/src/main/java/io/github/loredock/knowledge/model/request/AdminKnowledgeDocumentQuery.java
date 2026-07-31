package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;

/** 管理员按范围、目录、状态和标签查询全部生命周期文档的分页输入。 */
public record AdminKnowledgeDocumentQuery(
        KnowledgeScopeType scopeType,
        Long projectId,
        Long branchId,
        DocumentDirectory directory,
        DocumentStatus status,
        String tag,
        int page,
        int size
) {
}
