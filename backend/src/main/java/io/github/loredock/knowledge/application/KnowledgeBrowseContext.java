package io.github.loredock.knowledge.application;

import java.util.UUID;

/**
 * 普通浏览用例已经解析的查询上下文。
 *
 * @param type 入口类型
 * @param projectId 项目入口的项目 UUID
 * @param branchId 项目入口明确选择的分支 UUID
 */
public record KnowledgeBrowseContext(
        KnowledgeBrowseContextType type,
        UUID projectId,
        UUID branchId
) {
}
