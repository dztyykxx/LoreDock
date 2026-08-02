package io.github.loredock.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.agent.model.result.AgentToolResult;
import io.github.loredock.agent.model.tool.KnowledgeSearchToolRequest;
import io.github.loredock.agent.service.ProjectQaToolService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;

/** 验证项目问答 Tool 直接使用 Spring AI 注解方法和服务端 ToolContext。 */
class ProjectQaToolsTest {

    /**
     * 业务目的：项目问答只能把 query/limit 暴露给模型，runId 和本轮统计必须由服务端上下文注入；
     * 防止模型伪造运行范围，也防止注解化后形成多余 input 包装层。
     */
    @Test
    void annotatedKnowledgeSearchUsesFlatSchemaAndServerContext() {
        ProjectQaToolService service = mock(ProjectQaToolService.class);
        when(service.knowledgeSearch(eq(61L), any(KnowledgeSearchToolRequest.class)))
                .thenReturn(new AgentToolResult("evidence", List.of(), 1, 0));
        ProjectQaTools tools = new ProjectQaTools(service);
        ProjectQaTools.RunState state = new ProjectQaTools.RunState();
        ToolCallback callback = tools.provider().getToolCallbacks()[0];
        ToolContext context = new ToolContext(Map.of(
                ProjectQaTools.RUN_ID_CONTEXT_KEY, 61L,
                ProjectQaTools.RUN_STATE_CONTEXT_KEY, state));

        String result = callback.call("{\"query\":\"审核规则\",\"limit\":2}", context);

        assertThat(callback).isInstanceOf(MethodToolCallback.class);
        assertThat(callback.getToolDefinition().name()).isEqualTo("knowledge_search");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("\"query\"", "\"limit\"", "要检索的项目知识问题", "期望返回数量")
                .doesNotContain("\"input\"", "ToolContext", "runId", "runState");
        assertThat(result).contains("evidence");
        assertThat(state.retrievalCount()).isEqualTo(1);
        verify(service).knowledgeSearch(61L, new KnowledgeSearchToolRequest("审核规则", 2));
        System.out.println("测试证据：场景=项目问答注解Tool，模型参数=query+limit，服务端runId=61，命中=1");
    }
}
