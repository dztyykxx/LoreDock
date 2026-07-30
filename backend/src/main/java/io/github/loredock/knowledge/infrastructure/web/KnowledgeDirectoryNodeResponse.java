package io.github.loredock.knowledge.infrastructure.web;

/** 当前查询范围内的目录节点响应。 */
public record KnowledgeDirectoryNodeResponse(String path, String name, long documentCount) {
}
