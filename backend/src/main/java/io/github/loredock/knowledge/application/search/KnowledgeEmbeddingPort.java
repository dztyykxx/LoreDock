package io.github.loredock.knowledge.application.search;

import java.util.List;

/** 离线 CPU Embedding 应用端口；不得在调用期间下载模型或暴露框架类型。 */
public interface KnowledgeEmbeddingPort {

    /**
     * 惰性校验并描述当前本地模型；资源缺失或 checksum 不符必须明确失败。
     *
     * @return 当前可用模型描述
     */
    KnowledgeEmbeddingModelDescriptor describeModel();

    /**
     * 按输入顺序批量生成文档分块向量，不得重排或遗漏。
     *
     * @param inputs 有界批次输入
     * @return 与输入一一对应的 L2 归一化向量
     */
    List<KnowledgeEmbeddingVector> embedDocuments(List<KnowledgeEmbeddingInput> inputs);

    /**
     * 使用模型规定的查询指令生成单条查询向量。
     *
     * @param normalizedQuery 已校验纯文本查询
     * @return L2 归一化查询向量
     */
    KnowledgeEmbeddingVector embedQuery(String normalizedQuery);
}
