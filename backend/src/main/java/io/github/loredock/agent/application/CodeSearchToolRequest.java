package io.github.loredock.agent.application;

/** 模型可控制的代码搜索参数，不允许项目、分支、快照或服务器路径。 */
public record CodeSearchToolRequest(String query, String pathPrefix, Integer limit) {
}
