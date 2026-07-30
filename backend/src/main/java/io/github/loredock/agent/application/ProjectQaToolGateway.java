package io.github.loredock.agent.application;

import java.util.UUID;

/**
 * project_qa 唯一可用的三项只读工具。业务范围只由 runId 对应的固定快照解析，模型参数不能携带范围。
 */
public interface ProjectQaToolGateway {

    /** 在当前运行固定的三层已发布知识范围执行混合搜索。 */
    AgentToolResult knowledgeSearch(UUID runId, KnowledgeSearchToolRequest request);

    /** 在当前运行固定活动 snapshot/commit 内执行代码搜索。 */
    AgentToolResult codeSearch(UUID runId, CodeSearchToolRequest request);

    /** 在当前运行固定活动 snapshot/commit 内读取有限代码片段。 */
    AgentToolResult codeSnippetRead(UUID runId, CodeSnippetToolRequest request);
}
