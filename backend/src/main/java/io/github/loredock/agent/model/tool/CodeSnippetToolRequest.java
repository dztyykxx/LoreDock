package io.github.loredock.agent.model.tool;

/** 模型可控制的仓库相对路径和可向下收紧的行范围。 */
public record CodeSnippetToolRequest(String repositoryPath, Integer startLine, Integer lineCount) {
}
