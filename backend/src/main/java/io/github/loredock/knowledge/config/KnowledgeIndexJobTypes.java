package io.github.loredock.knowledge.config;

/** 知识后台任务类型常量。 */
public final class KnowledgeIndexJobTypes {

    public static final String KNOWLEDGE_REINDEX = "KNOWLEDGE_REINDEX";

    /** 重建模式：管理员手动触发的全量重建，用于模型或索引策略变更后的整体刷新。 */
    public static final String REINDEX_MODE_FULL = "FULL";

    /** 重建模式：发布自动触发的增量刷新，只重算修订变化的文档，秒级完成。 */
    public static final String REINDEX_MODE_REFRESH = "REFRESH";

    private KnowledgeIndexJobTypes() {
    }
}
