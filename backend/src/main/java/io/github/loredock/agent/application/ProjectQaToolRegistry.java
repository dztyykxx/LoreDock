package io.github.loredock.agent.application;

import io.github.loredock.agent.domain.AgentErrorCode;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** project_qa 的固定工具注册表；未知名称在进入任何适配器前被拒绝。 */
@Component
public class ProjectQaToolRegistry {

    private static final Set<String> ALLOWED = Set.of(
            "knowledge_search", "code_search", "code_snippet_read");
    private final ProjectQaToolGateway gateway;

    /** @param gateway 三个服务端只读工具的统一应用网关 */
    public ProjectQaToolRegistry(ProjectQaToolGateway gateway) {
        this.gateway = gateway;
    }

    /** @return 不可变的允许工具名称集合 */
    public Set<String> allowedToolNames() {
        return ALLOWED;
    }

    /**
     * 按固定名称和强类型请求执行工具；请求类型不匹配也按越权工具处理，避免框架反射扩大入口。
     *
     * @param runId 当前运行标识
     * @param toolName 模型请求的工具名称
     * @param request 对应工具的强类型请求
     * @return 有界模型上下文与证据元数据
     */
    public AgentToolResult execute(UUID runId, String toolName, Object request) {
        if (!ALLOWED.contains(toolName)) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_NOT_ALLOWED);
        }
        return switch (toolName) {
            case "knowledge_search" -> gateway.knowledgeSearch(runId,
                    requireType(request, KnowledgeSearchToolRequest.class));
            case "code_search" -> gateway.codeSearch(runId,
                    requireType(request, CodeSearchToolRequest.class));
            case "code_snippet_read" -> gateway.codeSnippetRead(runId,
                    requireType(request, CodeSnippetToolRequest.class));
            default -> throw new AgentToolException(AgentErrorCode.AGENT_TOOL_NOT_ALLOWED);
        };
    }

    private <T> T requireType(Object request, Class<T> type) {
        if (!type.isInstance(request)) {
            throw new AgentToolException(AgentErrorCode.AGENT_TOOL_NOT_ALLOWED);
        }
        return type.cast(request);
    }
}
