package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 管理员知识查询端口，可查看草稿、已发布和已归档文档及其追溯和同步状态。 */
public interface AdminKnowledgeDocumentQueryUseCase {

    /**
     * 按明确筛选和稳定的 updatedAt DESC、id ASC 顺序分页。
     *
     * @param query 管理筛选与分页
     * @return 管理摘要页
     */
    PageResult<KnowledgeDocumentSummary> list(AdminKnowledgeDocumentQuery query);

    /**
     * 按 ID 读取管理详情；文档不存在时以统一的文档不存在语义失败。
     *
     * @param documentId 文档 UUID
     * @return 完整管理详情
     */
    KnowledgeDocumentView get(UUID documentId);
}
