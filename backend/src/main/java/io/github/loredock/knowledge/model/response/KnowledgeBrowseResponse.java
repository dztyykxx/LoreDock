package io.github.loredock.knowledge.model.response;

import java.util.List;

/** 普通列表响应，同时返回当前目录节点和已发布文档摘要页。 */
public record KnowledgeBrowseResponse(
        List<KnowledgeDirectoryNodeResponse> directories,
        PageResponse<KnowledgeDocumentSummaryResponse> documents
) {
}
