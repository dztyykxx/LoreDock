package io.github.loredock.knowledge.model.request;

import java.util.List;

/**
 * 批量文档 Embedding 输入；适配器负责按固定模型规则组合标题、标签和当前正文块。
 *
 * @param documentId 文档标识，仅用于保持批次可追溯
 * @param chunkNo 分块序号
 * @param title 标题
 * @param tags 规范化标签文本
 * @param content 当前分块正文
 */
public record KnowledgeEmbeddingInput(
        Long documentId,
        int chunkNo,
        String title,
        List<String> tags,
        String content
) {
    public KnowledgeEmbeddingInput {
        tags = List.copyOf(tags);
    }
}
