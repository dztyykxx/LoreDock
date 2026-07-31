package io.github.loredock.agent.model.snapshot;

import java.util.List;

/**
 * 首次接收运行时固定的项目、分支和两类活动检索版本。
 *
 * @param projectId 项目数据库标识
 * @param projectIdentifier 项目业务标识
 * @param branchId 分支数据库标识
 * @param branch 实际分支名
 * @param snapshotId 活动代码快照；未索引时为空
 * @param commit 活动 commit；未索引时为空
 * @param knowledgeGenerationId 活动知识 generation
 * @param allowedKnowledgeScopes 允许的知识层级
 */
public record AgentScopeSnapshot(
        Long projectId,
        String projectIdentifier,
        Long branchId,
        String branch,
        Long snapshotId,
        String commit,
        Long knowledgeGenerationId,
        List<String> allowedKnowledgeScopes
) {
    public AgentScopeSnapshot {
        allowedKnowledgeScopes = List.copyOf(allowedKnowledgeScopes);
    }

    /** @return 当前分支是否有可作为实现事实的活动快照 */
    public boolean hasCodeSnapshot() {
        return snapshotId != null && commit != null;
    }
}
