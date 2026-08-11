package io.github.loredock.knowledge.model.snapshot;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;

/**
 * 普通浏览用例已经解析的查询上下文。
 *
 * @param type 入口类型
 * @param projectId 项目入口的项目 Long
 * @param branchId 项目入口明确选择的分支 Long
 * @param excludeGlobal 项目列表是否排除通用（GLOBAL）文档；默认 false 保持联合范围兼容语义
 */
public record KnowledgeBrowseContext(
        KnowledgeBrowseContextType type,
        Long projectId,
        Long branchId,
        boolean excludeGlobal
) {
    /** 保留旧调用方不排除通用文档的兼容语义。 */
    public KnowledgeBrowseContext(KnowledgeBrowseContextType type, Long projectId, Long branchId) {
        this(type, projectId, branchId, false);
    }
}
