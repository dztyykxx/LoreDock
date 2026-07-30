package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 在单个 PostgreSQL REPEATABLE READ 快照中构建并原子切换 generation 的应用端口。 */
public interface KnowledgeIndexRebuilder {

    /**
     * 只快照当时的 PUBLISHED 文档。任一步失败必须回滚 BUILDING 数据与切换并保留原 ACTIVE generation；
     * 不得吞异常，也不得写入 Lucene、Embedding 或 pgvector。
     *
     * @param jobId 当前 KNOWLEDGE_REINDEX 后台任务 ID，用作 generation 可追溯外键
     * @param progress 后台任务进度与心跳
     * @return 成功激活结果
     */
    KnowledgeIndexRebuildResult rebuild(UUID jobId, KnowledgeIndexRebuildProgress progress);
}
