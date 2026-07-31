package io.github.loredock.knowledge.model.command;

import io.github.loredock.knowledge.model.request.KnowledgeImportOptions;
import io.github.loredock.knowledge.model.request.KnowledgeImportUpload;

/** 单次同步导入命令；没有客户端幂等键，相同文件的重复提交创建新批次和新草稿。 */
public record KnowledgeImportCommand(KnowledgeImportUpload upload, KnowledgeImportOptions options) {
}
