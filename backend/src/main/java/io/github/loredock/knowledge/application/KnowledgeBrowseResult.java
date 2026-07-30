package io.github.loredock.knowledge.application;

import java.util.List;

/** 普通目录查询结果，同时返回当前目录的子节点和稳定分页摘要。 */
public record KnowledgeBrowseResult(
        List<KnowledgeDirectoryNode> directories,
        PageResult<KnowledgeDocumentSummary> documents
) {
}
