package io.github.loredock.knowledge.model.response;

import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;

/** 已由服务端解析的实际搜索上下文；PROJECT 省略分支时 branch 明确返回 main。 */
public record KnowledgeSearchContextResponse(
        KnowledgeBrowseContextType type,
        String projectIdentifier,
        String branch
) {
}
