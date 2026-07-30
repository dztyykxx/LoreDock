package io.github.loredock.knowledgegap.application;

/** 管理端过滤与不透明游标输入。 */
public record QueryKnowledgeGapsCommand(KnowledgeGapFilter filter, String cursor, int limit) {
}
