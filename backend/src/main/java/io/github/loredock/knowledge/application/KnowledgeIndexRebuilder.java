package io.github.loredock.knowledge.application;

import java.util.UUID;

/** 以短事实快照、事务外搜索构建和短激活事务分阶段重建 generation 的应用端口。 */
public interface KnowledgeIndexRebuilder {

    /**
     * 只冻结任务开始时的 PUBLISHED 文档。任一步失败必须清理 BUILDING 数据并保留原 ACTIVE generation；
     * CPU Embedding 不得持有事实表快照事务，完整性校验通过后才能原子切换。
     *
     * @param jobId 当前 KNOWLEDGE_REINDEX 后台任务 ID，用作 generation 可追溯外键
     * @param progress 后台任务进度与心跳
     * @return 成功激活结果
     */
    KnowledgeIndexRebuildResult rebuild(UUID jobId, KnowledgeIndexRebuildProgress progress);
}
