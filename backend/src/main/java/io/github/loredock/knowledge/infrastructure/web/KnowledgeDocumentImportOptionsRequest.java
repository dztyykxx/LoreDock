package io.github.loredock.knowledge.infrastructure.web;

import java.util.List;

/** multipart 的 JSON {@code options} 部分；范围、目录、标签与来源默认值复用文档写入规则。 */
public record KnowledgeDocumentImportOptionsRequest(
        KnowledgeScopeRequest scope,
        String directoryPrefix,
        List<String> tags,
        DocumentSourceRequest sourceDefaults
) {
}
