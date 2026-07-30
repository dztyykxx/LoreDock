package io.github.loredock.code.application;

/**
 * 活动索引代码片段读取输入。路径是纯逻辑仓库相对路径，不能映射到服务器文件系统或原始 ZIP。
 *
 * @param projectIdentifier 已启用项目标识
 * @param branch 分支名；空值由服务端解析为项目默认 main
 * @param path 活动 generation 中的精确规范化路径
 * @param startLine 起始行，空值默认 1
 * @param lineCount 行数，空值默认 80、最大 200
 */
public record CodeSnippetQuery(
        String projectIdentifier,
        String branch,
        String path,
        Integer startLine,
        Integer lineCount
) {
}
