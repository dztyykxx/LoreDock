package io.github.loredock.code.application;

/** Web、后续 MCP 与内部 Agent 共用的活动索引代码片段读取端口。 */
public interface CodeSnippetReadUseCase {

    /**
     * 只读活动 Lucene StoredField；startLine 默认 1，lineCount 默认 80、最大 200。
     * 不存在、被忽略或跨范围路径统一按文件不存在失败；起始行越界使用稳定 416 语义。
     *
     * @param query 项目、分支、规范化路径与有界行范围
     * @return 固定单一 commit 的有限纯文本
     */
    CodeSnippetResponse read(CodeSnippetQuery query);
}
