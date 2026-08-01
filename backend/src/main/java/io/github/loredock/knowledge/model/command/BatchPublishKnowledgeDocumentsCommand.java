package io.github.loredock.knowledge.model.command;

import java.util.HashSet;
import java.util.List;

/**
 * 原子批量发布命令；文档标识必须唯一，以免重复输入掩盖实际人工选择数量。
 *
 * @param documentIds 一至一百个唯一文档 Long
 */
public record BatchPublishKnowledgeDocumentsCommand(List<Long> documentIds) {

    public BatchPublishKnowledgeDocumentsCommand {
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 100
                || documentIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new IllegalArgumentException("batch publish document ids are invalid");
        }
        documentIds = List.copyOf(documentIds);
    }
}
