package io.github.loredock.knowledge.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 对活动投影候选执行实时 PUBLISHED 与范围资格复核的端口。 */
public interface PublishedKnowledgeEligibilityReader {

    /**
     * @param candidateIds 活动投影返回的候选文档 ID
     * @param context 当前已解析浏览上下文
     * @return 保留输入顺序且仍实时有资格的 ID
     */
    List<UUID> retainEligible(Collection<UUID> candidateIds, KnowledgeBrowseContext context);
}
