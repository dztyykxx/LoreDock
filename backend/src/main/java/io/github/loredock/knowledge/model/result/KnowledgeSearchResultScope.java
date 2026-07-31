package io.github.loredock.knowledge.model.result;

import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;

/**
 * 可引用结果的公开知识范围。
 *
 * @param type 通用、项目或分支范围
 * @param projectIdentifier 项目或分支范围的项目标识
 * @param branch 分支范围的分支名
 */
public record KnowledgeSearchResultScope(
        KnowledgeScopeType type,
        String projectIdentifier,
        String branch
) {
}
