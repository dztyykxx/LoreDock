package io.github.loredock.knowledge.model;

import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;

/**
 * 已解析的知识范围契约，只保存项目和分支的稳定 Long，不保存名称拼接出的伪路径。
 *
 * @param type 范围层级
 * @param projectId 项目 Long，GLOBAL 时为空
 * @param branchId 分支 Long，仅 BRANCH 时存在
 */
public record KnowledgeScope(KnowledgeScopeType type, Long projectId, Long branchId) {

    public KnowledgeScope {
        if (type == null) {
            throw new IllegalArgumentException("knowledge scope type is required");
        }
        // 范围只接受主数据解析出的 Long，禁止从项目标识或分支名拼接出不稳定的伪标识。
        boolean valid = switch (type) {
            case GLOBAL -> projectId == null && branchId == null;
            case PROJECT -> projectId != null && branchId == null;
            case BRANCH -> projectId != null && branchId != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("knowledge scope identifiers do not match type");
        }
    }

    /** @return 不关联项目或分支的通用范围。 */
    public static KnowledgeScope global() {
        return new KnowledgeScope(KnowledgeScopeType.GLOBAL, null, null);
    }

    /**
     * @param projectId 已解析项目 Long
     * @return 项目范围
     */
    public static KnowledgeScope project(Long projectId) {
        return new KnowledgeScope(KnowledgeScopeType.PROJECT, projectId, null);
    }

    /**
     * @param projectId 已解析项目 Long
     * @param branchId 已解析且属于该项目的分支 Long
     * @return 分支范围
     */
    public static KnowledgeScope branch(Long projectId, Long branchId) {
        return new KnowledgeScope(KnowledgeScopeType.BRANCH, projectId, branchId);
    }
}
