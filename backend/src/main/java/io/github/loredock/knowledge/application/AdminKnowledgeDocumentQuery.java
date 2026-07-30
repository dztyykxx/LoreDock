package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;

import java.util.UUID;

/** 管理员按范围、目录、状态和标签查询全部生命周期文档的分页输入。 */
public record AdminKnowledgeDocumentQuery(
        KnowledgeScopeType scopeType,
        UUID projectId,
        UUID branchId,
        DocumentDirectory directory,
        DocumentStatus status,
        String tag,
        int page,
        int size
) {
}
