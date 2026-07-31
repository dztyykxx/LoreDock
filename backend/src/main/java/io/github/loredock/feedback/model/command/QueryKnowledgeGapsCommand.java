package io.github.loredock.feedback.model.command;

import io.github.loredock.feedback.model.result.KnowledgeGapFilter;

/** 管理端过滤与不透明游标输入。 */
public record QueryKnowledgeGapsCommand(KnowledgeGapFilter filter, String cursor, int limit) {
}
