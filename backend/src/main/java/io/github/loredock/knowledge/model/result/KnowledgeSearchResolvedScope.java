package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;

/**
 * 候选 SQL 前已经由主数据解析的强范围。
 *
 * @param contextType GLOBAL 或 PROJECT
 * @param projectIdentifier PROJECT 的稳定项目标识
 * @param branch PROJECT 的实际分支名
 * @param projectId PROJECT 的项目 Long
 * @param branchId PROJECT 的分支 Long
 */
public record KnowledgeSearchResolvedScope(
        KnowledgeBrowseContextType contextType,
        String projectIdentifier,
        String branch,
        Long projectId,
        Long branchId
) {
}
