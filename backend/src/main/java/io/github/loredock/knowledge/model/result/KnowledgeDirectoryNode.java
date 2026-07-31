package io.github.loredock.knowledge.model.result;

/** 目录树节点及其当前普通浏览范围内的已发布文档数量。 */
public record KnowledgeDirectoryNode(String path, String name, long documentCount) {
}
