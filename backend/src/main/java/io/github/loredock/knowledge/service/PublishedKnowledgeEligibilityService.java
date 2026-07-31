package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用知识文档事实表实时过滤活动投影候选，供后续检索入口统一复用。 */
@Service
public class PublishedKnowledgeEligibilityService {

    private final KnowledgeDocumentDataService documents;

    /** @param documents 带 SQL 前置范围隔离的文档仓储 */
    public PublishedKnowledgeEligibilityService(KnowledgeDocumentDataService documents) {
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<Long> retainEligible(Collection<Long> candidateIds, KnowledgeBrowseContext context) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }
        Set<Long> eligible = new HashSet<>(documents.findPublishedEligibleIds(candidateIds, context));
        return candidateIds.stream().distinct().filter(eligible::contains).toList();
    }
}
