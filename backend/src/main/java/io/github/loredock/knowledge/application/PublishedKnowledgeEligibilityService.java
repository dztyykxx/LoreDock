package io.github.loredock.knowledge.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 使用知识文档事实表实时过滤活动投影候选，供后续检索入口统一复用。 */
@Service
public class PublishedKnowledgeEligibilityService implements PublishedKnowledgeEligibilityReader {

    private final KnowledgeDocumentRepository documents;

    /** @param documents 带 SQL 前置范围隔离的文档仓储 */
    public PublishedKnowledgeEligibilityService(KnowledgeDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> retainEligible(Collection<UUID> candidateIds, KnowledgeBrowseContext context) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> eligible = new HashSet<>(documents.findPublishedEligibleIds(candidateIds, context));
        return candidateIds.stream().distinct().filter(eligible::contains).toList();
    }
}
