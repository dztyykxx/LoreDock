package io.github.loredock.knowledge.application;

/** 后续 T5 读取正式知识活动 generation 的只读端口。 */
public interface PublishedKnowledgeIndexReader {

    /**
     * 只读取当前 ACTIVE generation 并前置应用项目/分支参数；无活动 generation 时返回空批次。
     * 投影是候选来源而非实时授权事实，调用方必须再通过实时资格端口排除归档或越界文档。
     *
     * @param query 明确范围和主键游标
     * @return 活动投影批次
     */
    PublishedKnowledgeIndexBatch read(PublishedKnowledgeIndexQuery query);
}
