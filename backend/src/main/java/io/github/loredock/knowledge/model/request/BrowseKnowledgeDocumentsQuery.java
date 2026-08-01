package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;

/** 普通知识目录与摘要分页查询；后代模式由调用方显式选择以保留旧精确目录兼容语义。 */
public record BrowseKnowledgeDocumentsQuery(
        KnowledgeBrowseContext context,
        DocumentDirectory directory,
        boolean includeDescendants,
        int page,
        int size
) {

    /**
     * 兼容既有精确目录调用。
     *
     * @param context 已解析浏览范围
     * @param directory 可选精确目录
     * @param page 零基页码
     * @param size 页容量
     */
    public BrowseKnowledgeDocumentsQuery(
            KnowledgeBrowseContext context,
            DocumentDirectory directory,
            int page,
            int size
    ) {
        this(context, directory, false, page, size);
    }
}
