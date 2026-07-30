package io.github.loredock.knowledge.application;

import java.util.Collection;
import java.util.UUID;

/** 为管理视图批量读取活动投影修订的端口，不向文档表回写派生状态。 */
public interface KnowledgeIndexSyncStateReader {

    /** @return 活动代次存在性和请求文档中实际存在的投影修订。 */
    ActiveKnowledgeIndexRevisions readActiveRevisions(Collection<UUID> documentIds);
}
