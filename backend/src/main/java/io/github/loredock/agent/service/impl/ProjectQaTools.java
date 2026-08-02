package io.github.loredock.agent.service.impl;

import io.github.loredock.agent.exception.AgentToolException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.agent.service.ProjectQaToolService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** 项目问答的有限业务 Tool；运行范围与统计状态只从服务端 ToolContext 取得。 */
final class ProjectQaTools {

    static final String RUN_ID_CONTEXT_KEY = "loredockProjectQaRunId";
    static final String RUN_STATE_CONTEXT_KEY = "loredockProjectQaRunState";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectQaTools.class);
    private static final int TOOL_RESULT_PREVIEW_CODE_POINTS = 500;

    private final ProjectQaToolService service;
    private final MethodToolCallbackProvider provider;

    ProjectQaTools(ProjectQaToolService service) {
        this.service = service;
        this.provider = MethodToolCallbackProvider.builder().toolObjects(this).build();
    }

    /** @return 由 Spring AI 从当前对象的注解方法生成的 Tool Provider */
    MethodToolCallbackProvider provider() {
        return provider;
    }

    /**
     * 在服务端固定项目、分支和知识 generation 内执行混合搜索。
     *
     * @param query 模型提供的检索问题
     * @param limit 模型期望数量，最终受服务端限制
     * @param context 框架注入的运行 ID 与本轮状态
     * @return 提供给模型的有限证据上下文
     */
    @Tool(name = "knowledge_search", description = "在服务端固定项目、分支和知识 generation 内执行混合搜索")
    public String knowledgeSearch(
            @ToolParam(description = "要检索的项目知识问题") String query,
            @ToolParam(description = "期望返回数量，最终仍受服务端上限约束") Integer limit,
            ToolContext context
    ) {
        Long runId = runId(context);
        RunState state = runState(context);
        AgentToolResult result;
        try {
            result = service.knowledgeSearch(runId, new KnowledgeSearchToolRequest(query, limit));
        } catch (AgentToolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 框架会记录 Tool 节点异常，先转换为稳定业务码，避免底层端点或连接细节进入日志。
            LOGGER.error("agent_tool_unexpected tool=knowledge_search failureType={}",
                    exception.getClass().getSimpleName());
            throw new AgentToolException(AgentErrorCode.AGENT_INTERNAL_ERROR);
        }
        System.out.println("project_qa.tool_result tool=knowledge_search"
                + " resultCount=" + result.resultCount()
                + " evidenceCount=" + result.evidence().size());
        System.out.println(preview(result.modelContext()));
        state.record(result);
        return result.modelContext();
    }

    private Long runId(ToolContext context) {
        Object value = contextValue(context, RUN_ID_CONTEXT_KEY);
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw new IllegalArgumentException("项目问答 Tool 缺少服务端运行 ID");
    }

    private RunState runState(ToolContext context) {
        Object value = contextValue(context, RUN_STATE_CONTEXT_KEY);
        if (value instanceof RunState state) {
            return state;
        }
        throw new IllegalArgumentException("项目问答 Tool 缺少本轮统计状态");
    }

    private Object contextValue(ToolContext context, String name) {
        if (context == null) {
            throw new IllegalArgumentException("项目问答 Tool 缺少服务端上下文");
        }
        Map<String, Object> values = context.getContext();
        return values.get(name);
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= TOOL_RESULT_PREVIEW_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, TOOL_RESULT_PREVIEW_CODE_POINTS);
        return value.substring(0, end) + "\n...[truncated]";
    }

    /** 单次运行的证据与检索统计；实例通过 ToolContext 绑定，不在 Agent 之间共享。 */
    static final class RunState {
        private final List<AgentEvidence> evidence = new CopyOnWriteArrayList<>();
        private final AtomicInteger retrievalCount = new AtomicInteger();
        private final AtomicInteger trimmed = new AtomicInteger();
        private final AtomicInteger successfulRetrievalCount = new AtomicInteger();
        private final AtomicInteger retainedEvidenceCount = new AtomicInteger();

        private void record(AgentToolResult result) {
            successfulRetrievalCount.incrementAndGet();
            retainedEvidenceCount.addAndGet((int) result.evidence().stream()
                    .filter(AgentEvidence::retained).count());
            evidence.addAll(result.evidence());
            retrievalCount.addAndGet(result.resultCount());
            trimmed.addAndGet(result.trimmedCharacterCount());
        }

        List<AgentEvidence> evidence() {
            return List.copyOf(evidence);
        }

        int retrievalCount() {
            return retrievalCount.get();
        }

        int trimmedCharacterCount() {
            return trimmed.get();
        }

        int successfulRetrievalCount() {
            return successfulRetrievalCount.get();
        }

        int retainedEvidenceCount() {
            return retainedEvidenceCount.get();
        }
    }
}
