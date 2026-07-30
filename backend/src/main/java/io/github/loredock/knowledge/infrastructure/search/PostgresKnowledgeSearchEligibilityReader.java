package io.github.loredock.knowledge.infrastructure.search;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.search.KnowledgeSearchEligibilityReader;
import io.github.loredock.knowledge.application.search.KnowledgeSearchResolvedScope;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchEligibilityMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL 实时资格适配器；候选生成已经完成第一层范围隔离，本适配器只处理生成后发生的归档、
 * 草稿化或范围迁移，并按输入顺序返回，不触发候选补足。
 */
@Repository
public class PostgresKnowledgeSearchEligibilityReader implements KnowledgeSearchEligibilityReader {

    private final KnowledgeSearchEligibilityMapper mapper;

    /** @param mapper 当前知识事实表资格 Mapper */
    public PostgresKnowledgeSearchEligibilityReader(KnowledgeSearchEligibilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> retainEligible(
            Collection<UUID> candidateIds,
            KnowledgeSearchResolvedScope scope
    ) {
        if (candidateIds == null || scope == null) {
            throw new IllegalArgumentException("knowledge search eligibility input is required");
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        boolean validScope = scope.contextType() == KnowledgeBrowseContextType.GLOBAL
                ? scope.projectId() == null && scope.branchId() == null
                : scope.contextType() == KnowledgeBrowseContextType.PROJECT
                && scope.projectId() != null && scope.branchId() != null;
        if (!validScope || candidateIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("knowledge search eligibility scope or candidates are invalid");
        }
        List<UUID> uniqueIds = candidateIds.stream().distinct().toList();
        Set<UUID> eligible = new HashSet<>(mapper.selectEligibleIds(
                uniqueIds, scope.contextType().name(), scope.projectId(), scope.branchId()));
        // SQL 只判定资格；使用原候选顺序重建列表，避免数据库物理顺序影响最终稳定排序。
        return uniqueIds.stream().filter(eligible::contains).toList();
    }
}
