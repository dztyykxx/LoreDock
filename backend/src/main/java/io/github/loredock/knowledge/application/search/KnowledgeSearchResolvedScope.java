package io.github.loredock.knowledge.application.search;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;

import java.util.UUID;

/**
 * 候选 SQL 前已经由主数据解析的强范围。
 *
 * @param contextType GLOBAL 或 PROJECT
 * @param projectIdentifier PROJECT 的稳定项目标识
 * @param branch PROJECT 的实际分支名
 * @param projectId PROJECT 的项目 UUID
 * @param branchId PROJECT 的分支 UUID
 */
public record KnowledgeSearchResolvedScope(
        KnowledgeBrowseContextType contextType,
        String projectIdentifier,
        String branch,
        UUID projectId,
        UUID branchId
) {
}
