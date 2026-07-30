package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.KnowledgeScope;

/** 已解析的导入默认值；每个成功条目仍须复用文档领域校验并只创建 DRAFT。 */
public record KnowledgeImportOptions(
        KnowledgeScope scope,
        DocumentDirectory directoryPrefix,
        DocumentTags tags,
        DocumentSource sourceDefaults
) {
}
