package io.github.loredock.knowledge.service;

import io.github.loredock.knowledge.mapper.KnowledgeSearchEligibilityMapper;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.result.KnowledgeSearchResolvedScope;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 实时资格适配器；候选生成已经完成第一层范围隔离，本适配器只处理生成后发生的归档、
 * 草稿化或范围迁移，并按输入顺序返回，不触发候选补足。
 */
@Repository
public class KnowledgeSearchEligibilityService {

    private final KnowledgeSearchEligibilityMapper mapper;

    /** @param mapper 当前知识事实表资格 Mapper */
    public KnowledgeSearchEligibilityService(KnowledgeSearchEligibilityMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<Long> retainEligible(
            Collection<Long> candidateIds,
            KnowledgeSearchResolvedScope scope
    ) {
        if (candidateIds == null || scope == null) {
            throw new IllegalArgumentException("knowledge search eligibility input is required");
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        boolean validScope = switch (scope.contextType()) {
            case GLOBAL -> scope.projectId() == null && scope.branchId() == null;
            case PROJECT -> scope.projectId() != null && scope.branchId() != null;
            // 全库模式只允许 Agent 内部路径构造，不解析项目主数据。
            case ALL -> scope.projectId() == null && scope.branchId() == null;
        };
        if (!validScope || candidateIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("knowledge search eligibility scope or candidates are invalid");
        }
        List<Long> uniqueIds = candidateIds.stream().distinct().toList();
        Set<Long> eligible = new HashSet<>(mapper.selectEligibleIds(
                uniqueIds, scope.contextType().name(), scope.projectId(), scope.branchId()));
        // SQL 只判定资格；使用原候选顺序重建列表，避免数据库物理顺序影响最终稳定排序。
        return uniqueIds.stream().filter(eligible::contains).toList();
    }
}
