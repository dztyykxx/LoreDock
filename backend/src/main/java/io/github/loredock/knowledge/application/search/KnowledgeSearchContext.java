package io.github.loredock.knowledge.application.search;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;

/**
 * 响应中返回的实际查询上下文。
 *
 * @param type 实际入口类型
 * @param projectIdentifier PROJECT 的项目标识
 * @param branch PROJECT 的实际分支；省略请求分支时明确为 main
 */
public record KnowledgeSearchContext(
        KnowledgeBrowseContextType type,
        String projectIdentifier,
        String branch
) {
}
