package io.github.loredock.knowledge.domain;

import java.util.UUID;

/**
 * 已解析的知识范围契约，只保存项目和分支的稳定 UUID，不保存名称拼接出的伪路径。
 *
 * @param type 范围层级
 * @param projectId 项目 UUID，GLOBAL 时为空
 * @param branchId 分支 UUID，仅 BRANCH 时存在
 */
public record KnowledgeScope(KnowledgeScopeType type, UUID projectId, UUID branchId) {

    public KnowledgeScope {
        if (type == null) {
            throw new IllegalArgumentException("knowledge scope type is required");
        }
        // 范围只接受主数据解析出的 UUID，禁止从项目标识或分支名拼接出不稳定的伪标识。
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
     * @param projectId 已解析项目 UUID
     * @return 项目范围
     */
    public static KnowledgeScope project(UUID projectId) {
        return new KnowledgeScope(KnowledgeScopeType.PROJECT, projectId, null);
    }

    /**
     * @param projectId 已解析项目 UUID
     * @param branchId 已解析且属于该项目的分支 UUID
     * @return 分支范围
     */
    public static KnowledgeScope branch(UUID projectId, UUID branchId) {
        return new KnowledgeScope(KnowledgeScopeType.BRANCH, projectId, branchId);
    }
}
