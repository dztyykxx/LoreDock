package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.KnowledgeScope;

/** 已解析的导入默认值；每个成功条目仍须复用文档领域校验并只创建 DRAFT。 */
public record KnowledgeImportOptions(
        KnowledgeScope scope,
        DocumentDirectory directoryPrefix,
        DocumentTags tags,
        DocumentSource sourceDefaults
) {
}
