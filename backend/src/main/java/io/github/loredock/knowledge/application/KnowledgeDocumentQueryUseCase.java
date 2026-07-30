package io.github.loredock.knowledge.application;

/** 已登录管理员与成员共用的普通只读端口，只返回查询范围内仍为 PUBLISHED 的文档。 */
public interface KnowledgeDocumentQueryUseCase {

    /**
     * 在查询层按入口上下文、目录与稳定顺序分页，不得先跨范围加载。
     *
     * @param query 已解析查询上下文
     * @return 目录节点与文档摘要页
     */
    KnowledgeBrowseResult browse(BrowseKnowledgeDocumentsQuery query);

    /**
     * 在入口上下文内读取详情；不存在、越界、草稿或归档统一按文档不存在失败。
     *
     * @param query 携带上下文的文档读取输入
     * @return 已发布文档详情
     */
    KnowledgeDocumentView get(ReadKnowledgeDocumentQuery query);
}
