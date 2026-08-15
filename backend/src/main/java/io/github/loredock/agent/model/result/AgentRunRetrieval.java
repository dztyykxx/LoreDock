package io.github.loredock.agent.model.result;

import java.util.List;

/**
 * 一次 knowledge_search 实际提供给模型的内容记录，供评估与审计读取；不进公开事件流。
 *
 * @param runId 所属运行
 * @param sequenceNo 运行内按调用顺序递增的序号
 * @param query 模型本轮发起的检索查询
 * @param documents 按检索顺序的全部候选文档（保留项含模型实际看到的片段）
 */
public record AgentRunRetrieval(
        Long runId,
        int sequenceNo,
        String query,
        List<RetrievedDocument> documents
) {
    public AgentRunRetrieval {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /**
     * @param evidenceId 证据标识（可与最终引用关联）
     * @param documentId 知识文档标识
     * @param title 文档标题
     * @param relevance 归一化相关度
     * @param retained 是否进入模型上下文
     * @param content 保留项为模型实际看到的片段，过滤项为空
     * @param truncated 片段是否被裁剪，或该候选被过滤未完整提供
     */
    public record RetrievedDocument(
            Long evidenceId,
            Long documentId,
            String title,
            double relevance,
            boolean retained,
            String content,
            boolean truncated
    ) {
    }
}
