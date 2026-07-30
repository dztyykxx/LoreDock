package io.github.loredock.knowledge.domain;

/** 当前文档修订与活动知识索引 generation 之间的派生同步状态。 */
public enum KnowledgeIndexSyncStatus {
    NOT_APPLICABLE,
    NEVER_INDEXED,
    PENDING,
    STALE,
    SYNCED
}
