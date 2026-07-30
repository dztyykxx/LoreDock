package io.github.loredock.agent.application;

/** 模型可控制的知识搜索参数，仅含查询文本和可向下收紧的数量。 */
public record KnowledgeSearchToolRequest(String query, Integer limit) {
}
