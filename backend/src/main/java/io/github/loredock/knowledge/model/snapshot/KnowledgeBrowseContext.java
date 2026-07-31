package io.github.loredock.knowledge.model.snapshot;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;

/**
 * 普通浏览用例已经解析的查询上下文。
 *
 * @param type 入口类型
 * @param projectId 项目入口的项目 Long
 * @param branchId 项目入口明确选择的分支 Long
 */
public record KnowledgeBrowseContext(
        KnowledgeBrowseContextType type,
        Long projectId,
        Long branchId
) {
}
